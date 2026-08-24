package com.tcptun.client

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Commands admitted to the serialized VPN runtime mutation lane. */
internal sealed interface VpnRuntimeCommand {
    val description: String

    data object Start : VpnRuntimeCommand { override val description = "VPN start" }
    data object Stop : VpnRuntimeCommand { override val description = "VPN stop" }
    data object UpdateOutbounds : VpnRuntimeCommand { override val description = "VPN outbound update" }
    data class UpdateUnderlyingNetwork(
        val ownership: VpnRuntimeOwnership,
    ) : VpnRuntimeCommand {
        override val description = "underlying network update"
    }
    data class ApplyRuntimeSettings(
        val request: RuntimeSettingsApplyClaim,
    ) : VpnRuntimeCommand {
        override val description = "runtime settings apply"
    }
    data object BridgeRecovery : VpnRuntimeCommand { override val description = "Bridge recovery" }
    data object RefreshDiagnostics : VpnRuntimeCommand { override val description = "Bridge diagnostics refresh" }
    data class Internal(override val description: String) : VpnRuntimeCommand
}

internal data class VpnRuntimeStartRequest(
    val command: VpnStartCommand,
    val expectedProfileMutationRevision: Long,
    val preserveDesiredStateOnFailure: Boolean = false,
    val restartReason: String? = null,
)

internal data class VpnRuntimeStopOptions(
    val setStopped: Boolean = true,
    val clearSavedConfig: Boolean = true,
    val stopSelfService: Boolean = true,
    val propagateBridgeStopFailure: Boolean = false,
)

internal sealed interface VpnPlatformStopResult {
    data object Released : VpnPlatformStopResult
    data object RetainedForRetry : VpnPlatformStopResult
}

internal data class VpnRuntimeOutboundUpdateRequest(
    val nextPlan: ProfileRunPlan,
    val hasRuntimeResources: Boolean,
)

internal data class VpnRuntimeRecoveryRequest(
    val plan: ProfileRunPlan,
    val reason: String,
)

/**
 * Compatibility policy/effect dispatcher around the single-writer [VpnRuntimeActor].
 *
 * Start/Stop/Replacement admission and completion are reducer events. Recovery, outbound updates,
 * and in-flight accounting stay here for this migration, but their state commits are serialized by
 * the same actor lane. Blocking JNI/platform effects execute on [executor], never on the actor.
 *
 * Logical ownership matrix:
 * - Start / Stop / Replacement lifecycle phase: VpnRuntimeActor reducer events.
 * - Recovery policy and scheduling: Coordinator; Recovery logical commits: Actor lane.
 * - Outbound mutation policy: Coordinator; running-plan commits: Actor lane.
 * - Physically applied settings: RuntimeSettingsRuntimeState, scoped to runtime ownership.
 */
internal class VpnRuntimeCoordinator(
    private val executor: Executor,
    private val canExecute: () -> Boolean,
) {
    private val workInFlight = AtomicInteger()
    private val actor = VpnRuntimeActor()

    val inFlight: Int
        get() = workInFlight.get()

    val snapshot: VpnRuntimeSnapshot
        get() = actor.state

    fun claimStart(
        serviceInstanceId: Long,
        persistent: Boolean,
    ): VpnRuntimeCommandToken {
        var claimed: VpnRuntimeCommandToken? = null
        actor.send { state ->
            nextToken(state, serviceInstanceId, persistent).also { claimed = it }
                .let(VpnRuntimeEvent::StartRequested)
        }
        return checkNotNull(claimed)
    }

    fun claimStop(serviceInstanceId: Long, reason: String): VpnRuntimeCommandToken =
        run {
            var claimed: VpnRuntimeCommandToken? = null
            actor.send { state ->
                nextToken(state, serviceInstanceId, persistent = false).also { claimed = it }
                    .let { VpnRuntimeEvent.StopRequested(it, reason) }
            }
            checkNotNull(claimed)
        }

    /** Claims a newer lifecycle owner without changing the user's desired-running decision. */
    fun claimReplacement(serviceInstanceId: Long): VpnRuntimeCommandToken =
        claimStart(serviceInstanceId, persistent = false)

    /** Invalidates older work while preserving the current runtime phase and plan. */
    fun claimAuxiliaryCommand(
        serviceInstanceId: Long,
        persistent: Boolean = false,
    ): VpnRuntimeCommandToken {
        var claimed: VpnRuntimeCommandToken? = null
        actor.compatibilityMutation { state ->
            if (state.phase is VpnRuntimePhase.Destroyed) {
                claimed = inertToken(state, serviceInstanceId)
                state
            } else {
                val token = nextToken(state, serviceInstanceId, persistent).also { claimed = it }
                state.copy(
                    phase = state.phase.withToken(token),
                    lifecycleGeneration = token.lifecycleGeneration,
                    persistentCommandGeneration = token.persistentGeneration,
                    serviceInstanceId = token.serviceInstanceId,
                    recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
                )
            }
        }
        return checkNotNull(claimed)
    }

    fun currentToken(serviceInstanceId: Long): VpnRuntimeCommandToken {
        val state = snapshot
        check(state.serviceInstanceId == serviceInstanceId) {
            "service instance does not own the current VPN command"
        }
        return VpnRuntimeCommandToken(
            serviceInstanceId = serviceInstanceId,
            lifecycleGeneration = state.lifecycleGeneration,
            persistentGeneration = state.persistentCommandGeneration,
        )
    }

    fun isCurrent(token: VpnRuntimeCommandToken): Boolean {
        return snapshot.isCurrent(token)
    }

    fun claimRecovery(token: VpnRuntimeCommandToken): VpnRuntimeRecoveryToken? =
        run {
            var claimed: VpnRuntimeRecoveryToken? = null
            actor.compatibilityMutation { state ->
                if (!state.isCurrent(token)) return@compatibilityMutation state
                when (state.phase) {
                    is VpnRuntimePhase.Running,
                    is VpnRuntimePhase.Recovering,
                    -> Unit

                    else -> return@compatibilityMutation state
                }
                VpnRuntimeRecoveryToken(
                    lifecycleToken = token,
                    recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
                ).also { recoveryToken ->
                    claimed = recoveryToken
                }.let { recoveryToken ->
                    state.copy(recoveryGeneration = recoveryToken.recoveryGeneration)
                }
            }
            claimed
        }

    fun claimRecoveryRetry(token: VpnRuntimeRecoveryToken): VpnRuntimeRecoveryToken? =
        run {
            var claimed: VpnRuntimeRecoveryToken? = null
            actor.compatibilityMutation { state ->
                if (!isCurrentRecovery(state, token)) return@compatibilityMutation state
                if (state.phase !is VpnRuntimePhase.Recovering || state.bridgeRestarting) {
                    return@compatibilityMutation state
                }
                val next = token.copy(
                    recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
                ).also { claimed = it }
                state.copy(recoveryGeneration = next.recoveryGeneration)
            }
            claimed
        }

    fun isCurrent(token: VpnRuntimeRecoveryToken): Boolean = isCurrentRecovery(snapshot, token)

    fun isCurrentGeneration(generation: Int): Boolean =
        snapshot.lifecycleGeneration == generation && snapshot.phase !is VpnRuntimePhase.Destroyed

    fun completeNoOpStart(token: VpnRuntimeCommandToken, runningPlan: ProfileRunPlan?): Boolean =
        run {
            var accepted = false
            actor.sendInternal { state ->
                accepted = (state.phase as? VpnRuntimePhase.Starting)?.token == token &&
                    state.isCurrent(token)
                VpnRuntimeEvent.NoOpStartCompleted(token, runningPlan)
            }
            accepted
        }

    fun destroy(serviceInstanceId: Long): VpnRuntimeCommandToken {
        var destroyedToken: VpnRuntimeCommandToken? = null
        actor.beginDestroy { state ->
            VpnRuntimeEvent.Destroyed(
                nextToken(state, serviceInstanceId, persistent = false)
                    .also { destroyedToken = it },
            )
        }
        return checkNotNull(destroyedToken)
    }

    fun closeExternalIngress() = actor.closeExternalIngress()

    fun shutdownActor(): Boolean = actor.shutdown()

    /** Owns replacement sequencing and the Starting -> Running transition. */
    fun dispatchStart(
        token: VpnRuntimeCommandToken,
        request: VpnRuntimeStartRequest,
        onFailure: (Throwable) -> Unit,
        hasRuntimeResources: () -> Boolean,
        stopExisting: (VpnRuntimeCommandToken, () -> Boolean) -> Unit,
        startRuntime: (
            VpnRuntimeStartRequest,
            VpnRuntimeCommandToken,
            () -> Boolean,
            (ProfileRunPlan) -> Boolean,
        ) -> Unit,
        rollbackStart: (
            VpnRuntimeStartRequest,
            VpnRuntimeCommandToken,
            Throwable,
            Boolean,
        ) -> VpnPlatformStopResult,
    ): Boolean = dispatch(VpnRuntimeCommand.Start, onFailure) start@{
        try {
            val effect = actor.sendInternal(
                VpnRuntimeEvent.StartExecutionRequested(
                    token,
                    request,
                    hasRuntimeResources(),
                ),
            ).effects.singleOrNull() as? VpnRuntimeEffect.StartRuntime ?: return@start
            if (effect.replaceExisting) {
                actor.sendInternal(VpnRuntimeEvent.ReplacementStopStarted(token))
                stopExisting(token) { isCurrent(token) }
                actor.sendInternal(VpnRuntimeEvent.ReplacementStopCompleted(token))
            }
            if (!isCurrent(token)) return@start
            startRuntime(effect.request, token, { isCurrent(token) }) { plan ->
                val decision = actor.sendInternal(VpnRuntimeEvent.StartSucceeded(token, plan))
                decision.state.phase == VpnRuntimePhase.Running(token) &&
                    decision.state.runningPlan === plan
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val rollback = actor.sendInternal(
                VpnRuntimeEvent.StartFailed(token, request, error),
            ).effects.singleOrNull() as? VpnRuntimeEffect.RollbackStart
                ?: throw error
            val cleanupResult = rollbackStart(
                rollback.request,
                rollback.token,
                rollback.error,
                rollback.superseded,
            )
            if (!rollback.superseded) completeStartRollbackCleanup(token, cleanupResult)
            throw error
        }
    }

    fun completeStartRollbackCleanup(
        token: VpnRuntimeCommandToken,
        result: VpnPlatformStopResult,
    ): Boolean {
        val owner = VpnRuntimeCleanupOwner.StartRollback(token)
        var accepted = false
        actor.sendInternal { state ->
            accepted = (state.phase as? VpnRuntimePhase.CleaningUp)?.owner == owner &&
                state.isCurrent(token)
            when (result) {
                VpnPlatformStopResult.Released -> VpnRuntimeEvent.CleanupReleased(owner)
                VpnPlatformStopResult.RetainedForRetry -> VpnRuntimeEvent.CleanupRetained(owner)
            }
        }
        return accepted
    }

    /** Owns the common Stop transaction entry and terminal runtime-state commit. */
    fun dispatchStop(
        token: VpnRuntimeCommandToken,
        reason: String,
        options: VpnRuntimeStopOptions,
        onFailure: (Throwable) -> Unit,
        beforeStop: (VpnRuntimeCommandToken, () -> Boolean) -> Unit = { _, _ -> },
        stopRuntime: (
            VpnRuntimeStopOptions,
            VpnRuntimeCommandToken,
            () -> Boolean,
        ) -> VpnPlatformStopResult,
    ): Boolean = dispatch(VpnRuntimeCommand.Stop, onFailure) stop@{
        val effect = actor.sendInternal(
            VpnRuntimeEvent.StopExecutionRequested(token, reason, options),
        ).effects.singleOrNull() as? VpnRuntimeEffect.StopRuntime ?: return@stop
        beforeStop(token) { isCurrent(token) }
        val result = stopRuntime(effect.options, effect.token) { isCurrent(effect.token) }
        completePlatformStop(token, result)
    }

    /** Completes a token-owned teardown retry without exposing a generic phase setter. */
    fun completePlatformStop(
        token: VpnRuntimeCommandToken,
        result: VpnPlatformStopResult,
    ): Boolean {
        val owner = VpnRuntimeCleanupOwner.Stop(token)
        var accepted = false
        actor.sendInternal { state ->
            accepted = (state.phase as? VpnRuntimePhase.Stopping)?.token == token &&
                state.isCurrent(token)
            when (result) {
                VpnPlatformStopResult.Released -> VpnRuntimeEvent.CleanupReleased(owner)
                VpnPlatformStopResult.RetainedForRetry -> VpnRuntimeEvent.CleanupRetained(owner)
            }
        }
        return accepted
    }

    /**
     * Owns Running A -> membership mutation -> persistence -> Running B, including rollback.
     * JNI and persistence remain adapters supplied by the Service.
     */
    fun dispatchOutboundUpdate(
        token: VpnRuntimeCommandToken,
        request: VpnRuntimeOutboundUpdateRequest,
        onFailure: (Throwable) -> Unit,
        persistPlan: (ProfileRunPlan, () -> Boolean) -> Boolean,
        mutateOutbound: (AppConfig, Boolean, () -> Boolean) -> Unit,
        onCommitted: (ProfileRunPlan) -> Unit,
        onRolledBack: (ProfileRunPlan, Throwable) -> Unit,
        onMutationFailure: (Throwable, Boolean) -> Unit,
        onReplacementRequired: (VpnRuntimeCommandToken, ProfileRunPlan, Throwable?) -> Unit,
    ): Boolean = dispatch(VpnRuntimeCommand.UpdateOutbounds, onFailure) update@{
        if (!isCurrent(token)) return@update
        val currentSnapshot = snapshot
        val currentPlan = currentSnapshot.runningPlan
        if (
            currentSnapshot.phase !is VpnRuntimePhase.Running ||
            !request.hasRuntimeResources || currentPlan == null ||
            currentPlan.profiles != request.nextPlan.profiles
        ) {
            requestReplacement(token, request.nextPlan, null, onReplacementRequired)
            return@update
        }

        val changedIds = (currentPlan.activeIds - request.nextPlan.activeIds) +
            (request.nextPlan.activeIds - currentPlan.activeIds)
        val changedProfiles = currentPlan.profiles.filter { it.id in changedIds }
        var nativeMutationAttempted = false
        val transactionFailure = try {
            for (profile in changedProfiles) {
                if (!isCurrent(token)) return@update
                nativeMutationAttempted = true
                mutateOutbound(profile, profile.id in request.nextPlan.activeIds) { isCurrent(token) }
            }
            if (!isCurrent(token)) return@update
            check(persistPlan(request.nextPlan) { isCurrent(token) }) {
                "connection update persistence was not committed"
            }
            null
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            error
        }
        if (transactionFailure == null) {
            if (!commitRunningPlan(token, request.nextPlan)) return@update
            onCommitted(request.nextPlan)
            return@update
        }

        onMutationFailure(transactionFailure, nativeMutationAttempted)
        if (!isCurrent(token)) return@update
        if (!nativeMutationAttempted) {
            commitRunningPlan(token, currentPlan)
            return@update
        }
        try {
            for (profile in changedProfiles) {
                if (!isCurrent(token)) return@update
                mutateOutbound(profile, profile.id in currentPlan.activeIds) { isCurrent(token) }
            }
        } catch (rollbackError: Throwable) {
            if (rollbackError.isFatalProcessError()) throw rollbackError
            if (isCurrent(token)) {
                transactionFailure.addSuppressed(rollbackError)
                requestReplacement(token, currentPlan, transactionFailure, onReplacementRequired)
            }
            return@update
        }
        if (!isCurrent(token)) return@update
        commitRunningPlan(token, currentPlan)
        onRolledBack(currentPlan, transactionFailure)
    }

    /** Owns Running -> Recovering -> Running and rejects stale retry/restart completion. */
    fun dispatchRecovery(
        token: VpnRuntimeRecoveryToken,
        request: VpnRuntimeRecoveryRequest,
        onFailure: (Throwable) -> Unit,
        recoverRuntime: (
            VpnRuntimeRecoveryRequest,
            VpnRuntimeRecoveryToken,
            () -> Boolean,
            (ProfileRunPlan) -> Boolean,
        ) -> Unit,
        rollbackRecovery: (
            VpnRuntimeRecoveryRequest,
            VpnRuntimeRecoveryToken,
            Throwable,
            Boolean,
        ) -> VpnPlatformStopResult,
        onRetryRequired: (VpnRuntimeRecoveryToken, VpnRuntimeRecoveryRequest, Throwable) -> Unit,
    ): Boolean = dispatch(VpnRuntimeCommand.BridgeRecovery, onFailure) recovery@{
        if (!beginRecovery(token)) return@recovery
        try {
            recoverRuntime(request, token, { isCurrent(token) }) { plan ->
                commitRecoveryRunning(token, plan)
            }
            if (isCurrent(token) && snapshot.phase is VpnRuntimePhase.Recovering) {
                commitRecoveryRunning(token, request.plan)
            }
            completeRecoverySuccess(token)
        } catch (error: Throwable) {
            handleRecoveryFailure(token, request, error, rollbackRecovery, onRetryRequired)
        }
    }

    /** Begins Recovery but deliberately leaves completion to a scheduled continuation. */
    fun dispatchRecoveryPreparation(
        token: VpnRuntimeRecoveryToken,
        request: VpnRuntimeRecoveryRequest,
        onFailure: (Throwable) -> Unit,
        prepareRuntime: (VpnRuntimeRecoveryRequest, VpnRuntimeRecoveryToken, () -> Boolean) -> Unit,
        rollbackRecovery: (
            VpnRuntimeRecoveryRequest,
            VpnRuntimeRecoveryToken,
            Throwable,
            Boolean,
        ) -> VpnPlatformStopResult,
        onRetryRequired: (VpnRuntimeRecoveryToken, VpnRuntimeRecoveryRequest, Throwable) -> Unit,
    ): Boolean = dispatch(VpnRuntimeCommand.BridgeRecovery, onFailure) recovery@{
        if (!beginRecovery(token)) return@recovery
        try {
            prepareRuntime(request, token) { isCurrent(token) }
        } catch (error: Throwable) {
            handleRecoveryFailure(token, request, error, rollbackRecovery, onRetryRequired)
        }
    }

    /** Completes only the still-current Recovery generation prepared above. */
    fun dispatchRecoveryContinuation(
        token: VpnRuntimeRecoveryToken,
        request: VpnRuntimeRecoveryRequest,
        onFailure: (Throwable) -> Unit,
        recoverRuntime: (
            VpnRuntimeRecoveryRequest,
            VpnRuntimeRecoveryToken,
            () -> Boolean,
            (ProfileRunPlan) -> Boolean,
        ) -> Unit,
        rollbackRecovery: (
            VpnRuntimeRecoveryRequest,
            VpnRuntimeRecoveryToken,
            Throwable,
            Boolean,
        ) -> VpnPlatformStopResult,
        onRetryRequired: (VpnRuntimeRecoveryToken, VpnRuntimeRecoveryRequest, Throwable) -> Unit,
    ): Boolean = dispatch(VpnRuntimeCommand.BridgeRecovery, onFailure) recovery@{
        if (!isRecovering(token)) return@recovery
        try {
            recoverRuntime(request, token, { isCurrent(token) }) { plan ->
                commitRecoveryRunning(token, plan)
            }
            if (isCurrent(token) && snapshot.phase is VpnRuntimePhase.Recovering) {
                commitRecoveryRunning(token, request.plan)
            }
            completeRecoverySuccess(token)
        } catch (error: Throwable) {
            handleRecoveryFailure(token, request, error, rollbackRecovery, onRetryRequired)
        }
    }

    /** Completes only the retained cleanup owned by one failed Recovery generation. */
    fun completeRecoveryRollbackCleanup(
        token: VpnRuntimeRecoveryToken,
        result: VpnPlatformStopResult,
    ): VpnRuntimeRecoveryToken? {
        var retryToken: VpnRuntimeRecoveryToken? = null
        actor.compatibilityMutationInternal { state ->
            val cleanupOwner = (state.phase as? VpnRuntimePhase.CleaningUp)
                ?.owner as? VpnRuntimeCleanupOwner.RecoveryRollback
            if (cleanupOwner?.recoveryToken != token || !isCurrentRecovery(state, token)) {
                return@compatibilityMutationInternal state
            }
            if (result is VpnPlatformStopResult.RetainedForRetry) {
                return@compatibilityMutationInternal state
            }
            token.copy(
                recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
            ).also { retryToken = it }.let { next ->
                state.copy(
                    phase = VpnRuntimePhase.Recovering(token.lifecycleToken),
                    stopping = false,
                    bridgeRestarting = false,
                    recoveryGeneration = next.recoveryGeneration,
                )
            }
        }
        return retryToken
    }

    fun dispatch(
        command: VpnRuntimeCommand,
        onFailure: (Throwable) -> Unit,
        operation: () -> Unit,
    ): Boolean {
        if (!canExecute()) return false
        workInFlight.incrementAndGet()
        val accepted = executeCrashGuarded(executor, command.description, onFailure) {
            try {
                if (canExecute()) operation()
            } finally {
                workInFlight.decrementAndGet()
            }
        }
        if (!accepted) workInFlight.decrementAndGet()
        return accepted
    }

    private fun nextToken(
        current: VpnRuntimeState,
        serviceInstanceId: Long,
        persistent: Boolean,
    ): VpnRuntimeCommandToken {
        return VpnRuntimeCommandToken(
            serviceInstanceId = serviceInstanceId,
            lifecycleGeneration = current.lifecycleGeneration + 1,
            persistentGeneration = current.persistentCommandGeneration + if (persistent) 1 else 0,
        )
    }

    private fun inertToken(state: VpnRuntimeState, serviceInstanceId: Long) = VpnRuntimeCommandToken(
        serviceInstanceId = serviceInstanceId,
        lifecycleGeneration = state.lifecycleGeneration,
        persistentGeneration = state.persistentCommandGeneration,
    )

    private fun VpnRuntimePhase.withToken(token: VpnRuntimeCommandToken): VpnRuntimePhase = when (this) {
        VpnRuntimePhase.Idle -> this
        is VpnRuntimePhase.Starting -> copy(token = token)
        is VpnRuntimePhase.Running -> copy(token = token)
        is VpnRuntimePhase.Stopping -> copy(token = token)
        is VpnRuntimePhase.CleaningUp -> copy(owner = owner.withLifecycleToken(token))
        is VpnRuntimePhase.Recovering -> copy(token = token)
        VpnRuntimePhase.Destroyed -> this
    }

    private fun commitRunningPlan(token: VpnRuntimeCommandToken, plan: ProfileRunPlan): Boolean {
        var committed = false
        actor.compatibilityMutation { state ->
            if (!state.isCurrent(token)) return@compatibilityMutation state
            committed = true
            state.copy(
                phase = VpnRuntimePhase.Running(token),
                runningPlan = plan,
                explicitStopRequested = false,
                stopping = false,
                bridgeRestarting = false,
            )
        }
        return committed
    }

    private fun commitRecoveryRunning(
        token: VpnRuntimeRecoveryToken,
        plan: ProfileRunPlan,
    ): Boolean {
        var committed = false
        actor.compatibilityMutation { state ->
            if (!isCurrentRecovery(state, token)) return@compatibilityMutation state
            committed = true
            state.copy(
                phase = VpnRuntimePhase.Running(token.lifecycleToken),
                runningPlan = plan,
                explicitStopRequested = false,
                stopping = false,
                bridgeRestarting = false,
            )
        }
        return committed
    }

    private fun requestReplacement(
        token: VpnRuntimeCommandToken,
        plan: ProfileRunPlan,
        failure: Throwable?,
        onReplacementRequired: (VpnRuntimeCommandToken, ProfileRunPlan, Throwable?) -> Unit,
    ) {
        var replacement: VpnRuntimeCommandToken? = null
        actor.compatibilityMutation { state ->
            if (!state.isCurrent(token)) return@compatibilityMutation state
            val next = nextToken(state, token.serviceInstanceId, persistent = false)
                .also { replacement = it }
            reduceRuntime(state, VpnRuntimeEvent.StartRequested(next)).state.copy(
                runningPlan = if (failure == null) state.runningPlan else null,
            )
        }
        onReplacementRequired(replacement ?: return, plan, failure)
    }

    private fun beginRecoveryRollbackCleanup(token: VpnRuntimeRecoveryToken): Boolean {
        var accepted = false
        actor.compatibilityMutation { state ->
            if (!isCurrentRecovery(state, token) || state.phase !is VpnRuntimePhase.Recovering) {
                return@compatibilityMutation state
            }
            accepted = true
            state.copy(
                phase = VpnRuntimePhase.CleaningUp(
                    owner = VpnRuntimeCleanupOwner.RecoveryRollback(token),
                    reason = "failed recovery rollback",
                ),
                runningPlan = null,
                stopping = true,
                bridgeRestarting = false,
            )
        }
        return accepted
    }

    private fun beginRecovery(token: VpnRuntimeRecoveryToken): Boolean {
        var accepted = false
        actor.compatibilityMutation { state ->
            if (!isCurrentRecovery(state, token)) return@compatibilityMutation state
            when (state.phase) {
                is VpnRuntimePhase.Running,
                is VpnRuntimePhase.Recovering,
                -> Unit

                else -> return@compatibilityMutation state
            }
            accepted = true
            state.copy(
                phase = VpnRuntimePhase.Recovering(token.lifecycleToken),
                stopping = false,
                bridgeRestarting = true,
            )
        }
        return accepted
    }

    private fun isRecovering(token: VpnRuntimeRecoveryToken): Boolean =
        isCurrentRecovery(snapshot, token) && snapshot.phase is VpnRuntimePhase.Recovering

    private fun handleRecoveryFailure(
        token: VpnRuntimeRecoveryToken,
        request: VpnRuntimeRecoveryRequest,
        error: Throwable,
        rollbackRecovery: (
            VpnRuntimeRecoveryRequest,
            VpnRuntimeRecoveryToken,
            Throwable,
            Boolean,
        ) -> VpnPlatformStopResult,
        onRetryRequired: (VpnRuntimeRecoveryToken, VpnRuntimeRecoveryRequest, Throwable) -> Unit,
    ) {
        if (error.isFatalProcessError()) throw error
        val superseded = !isCurrent(token)
        if (!superseded) beginRecoveryRollbackCleanup(token)
        val cleanupResult = rollbackRecovery(request, token, error, superseded)
        if (!superseded) completeRecoveryRollbackCleanup(token, cleanupResult)?.let { retryToken ->
            onRetryRequired(retryToken, request, error)
        }
    }

    private fun completeRecoverySuccess(token: VpnRuntimeRecoveryToken): Boolean {
        var accepted = false
        actor.compatibilityMutation { state ->
            if (!isCurrentRecovery(state, token) || state.phase !is VpnRuntimePhase.Running) {
                return@compatibilityMutation state
            }
            accepted = true
            state.copy(recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration))
        }
        return accepted
    }

    private fun VpnRuntimeCleanupOwner.withLifecycleToken(
        token: VpnRuntimeCommandToken,
    ): VpnRuntimeCleanupOwner = when (this) {
        is VpnRuntimeCleanupOwner.Stop -> copy(lifecycleToken = token)
        is VpnRuntimeCleanupOwner.StartRollback -> copy(lifecycleToken = token)
        is VpnRuntimeCleanupOwner.RecoveryRollback -> copy(
            recoveryToken = recoveryToken.copy(lifecycleToken = token),
        )
    }

    private fun isCurrentRecovery(
        state: VpnRuntimeState,
        token: VpnRuntimeRecoveryToken,
    ): Boolean = state.isCurrent(token.lifecycleToken) &&
        token.recoveryGeneration == state.recoveryGeneration
}
