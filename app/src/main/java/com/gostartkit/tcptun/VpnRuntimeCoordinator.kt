package com.tcptun.client

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Commands admitted to the serialized VPN runtime mutation lane. */
internal sealed interface VpnRuntimeCommand {
    val description: String

    data object Start : VpnRuntimeCommand { override val description = "VPN start" }
    data object Stop : VpnRuntimeCommand { override val description = "VPN stop" }
    data object UpdateOutbounds : VpnRuntimeCommand { override val description = "VPN outbound update" }
    data object ApplyRuntimeSettings : VpnRuntimeCommand { override val description = "runtime settings apply" }
    data object BridgeRecovery : VpnRuntimeCommand { override val description = "Bridge recovery" }
    data object UpdateFlowAnalysis : VpnRuntimeCommand { override val description = "flow analysis update" }
    data object RefreshDiagnostics : VpnRuntimeCommand { override val description = "Bridge diagnostics refresh" }
    data class Internal(override val description: String) : VpnRuntimeCommand
}

internal data class VpnRuntimeCommandToken(
    val serviceInstanceId: Long,
    val lifecycleGeneration: Int,
    val persistentGeneration: Int,
)

internal data class VpnRuntimeRecoveryToken(
    val lifecycleToken: VpnRuntimeCommandToken,
    val recoveryGeneration: Long,
)

internal sealed interface VpnRuntimeCleanupOwner {
    val lifecycleToken: VpnRuntimeCommandToken

    data class StartRollback(
        override val lifecycleToken: VpnRuntimeCommandToken,
    ) : VpnRuntimeCleanupOwner

    data class RecoveryRollback(
        val recoveryToken: VpnRuntimeRecoveryToken,
    ) : VpnRuntimeCleanupOwner {
        override val lifecycleToken: VpnRuntimeCommandToken
            get() = recoveryToken.lifecycleToken
    }
}

internal sealed interface VpnRuntimePhase {
    data object Idle : VpnRuntimePhase
    data class Starting(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data class Running(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data class Stopping(
        val token: VpnRuntimeCommandToken,
        val reason: String,
    ) : VpnRuntimePhase
    data class CleaningUp(
        val owner: VpnRuntimeCleanupOwner,
        val reason: String,
    ) : VpnRuntimePhase {
        val token: VpnRuntimeCommandToken
            get() = owner.lifecycleToken
    }
    data class Recovering(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data object Destroyed : VpnRuntimePhase
}

internal data class VpnRuntimeSnapshot(
    val phase: VpnRuntimePhase = VpnRuntimePhase.Idle,
    val lifecycleGeneration: Int = 0,
    val persistentCommandGeneration: Int = 0,
    val recoveryGeneration: Long = 0L,
    val serviceInstanceId: Long = 0L,
    val runningPlan: ProfileRunPlan? = null,
    val explicitStopRequested: Boolean = false,
    val stopping: Boolean = false,
    val bridgeRestarting: Boolean = false,
)

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
 * Single owner of VPN command generations and runtime state.
 *
 * Tokens are claimed synchronously at Android command admission so a newer command can invalidate
 * an in-progress JNI transaction. Runtime transitions themselves execute on [executor]. Readers
 * only observe immutable [snapshot] values.
 */
internal class VpnRuntimeCoordinator(
    private val executor: Executor,
    private val canExecute: () -> Boolean,
) {
    private val stateLock = Any()
    private val workInFlight = AtomicInteger()

    @Volatile
    private var publishedSnapshot = VpnRuntimeSnapshot()

    val inFlight: Int
        get() = workInFlight.get()

    val snapshot: VpnRuntimeSnapshot
        get() = publishedSnapshot

    fun claimStart(
        serviceInstanceId: Long,
        persistent: Boolean,
    ): VpnRuntimeCommandToken = synchronized(stateLock) {
        if (publishedSnapshot.phase is VpnRuntimePhase.Destroyed) {
            return@synchronized inertToken(serviceInstanceId)
        }
        val token = nextToken(serviceInstanceId, persistent)
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Starting(token),
            lifecycleGeneration = token.lifecycleGeneration,
            persistentCommandGeneration = token.persistentGeneration,
            serviceInstanceId = token.serviceInstanceId,
            recoveryGeneration = nextRecoveryGeneration(),
            explicitStopRequested = false,
            bridgeRestarting = false,
        )
        token
    }

    fun claimStop(serviceInstanceId: Long, reason: String): VpnRuntimeCommandToken =
        synchronized(stateLock) {
            if (publishedSnapshot.phase is VpnRuntimePhase.Destroyed) {
                return@synchronized inertToken(serviceInstanceId)
            }
            val token = nextToken(serviceInstanceId, persistent = false)
            publishedSnapshot = publishedSnapshot.copy(
                phase = VpnRuntimePhase.Stopping(token, reason),
                lifecycleGeneration = token.lifecycleGeneration,
                serviceInstanceId = token.serviceInstanceId,
                recoveryGeneration = nextRecoveryGeneration(),
                explicitStopRequested = true,
                stopping = true,
                bridgeRestarting = false,
            )
            token
        }

    /** Claims a newer lifecycle owner without changing the user's desired-running decision. */
    fun claimReplacement(serviceInstanceId: Long): VpnRuntimeCommandToken =
        claimStart(serviceInstanceId, persistent = false)

    /** Invalidates older work while preserving the current runtime phase and plan. */
    fun claimAuxiliaryCommand(
        serviceInstanceId: Long,
        persistent: Boolean = false,
    ): VpnRuntimeCommandToken = synchronized(stateLock) {
        if (publishedSnapshot.phase is VpnRuntimePhase.Destroyed) {
            return@synchronized inertToken(serviceInstanceId)
        }
        val token = nextToken(serviceInstanceId, persistent)
        publishedSnapshot = publishedSnapshot.copy(
            phase = publishedSnapshot.phase.withToken(token),
            lifecycleGeneration = token.lifecycleGeneration,
            persistentCommandGeneration = token.persistentGeneration,
            serviceInstanceId = token.serviceInstanceId,
            recoveryGeneration = nextRecoveryGeneration(),
        )
        token
    }

    fun currentToken(serviceInstanceId: Long): VpnRuntimeCommandToken = synchronized(stateLock) {
        check(publishedSnapshot.serviceInstanceId == serviceInstanceId) {
            "service instance does not own the current VPN command"
        }
        VpnRuntimeCommandToken(
            serviceInstanceId = serviceInstanceId,
            lifecycleGeneration = publishedSnapshot.lifecycleGeneration,
            persistentGeneration = publishedSnapshot.persistentCommandGeneration,
        )
    }

    fun isCurrent(token: VpnRuntimeCommandToken): Boolean {
        val current = publishedSnapshot
        return current.phase !is VpnRuntimePhase.Destroyed &&
            token.serviceInstanceId == current.serviceInstanceId &&
            token.lifecycleGeneration == current.lifecycleGeneration &&
            token.persistentGeneration == current.persistentCommandGeneration
    }

    fun claimRecovery(token: VpnRuntimeCommandToken): VpnRuntimeRecoveryToken? =
        synchronized(stateLock) {
            if (!isCurrentLocked(token)) return@synchronized null
            when (publishedSnapshot.phase) {
                is VpnRuntimePhase.Running,
                is VpnRuntimePhase.Recovering,
                -> Unit

                else -> return@synchronized null
            }
            VpnRuntimeRecoveryToken(
                lifecycleToken = token,
                recoveryGeneration = nextRecoveryGeneration(),
            ).also { recoveryToken ->
                publishedSnapshot = publishedSnapshot.copy(
                    recoveryGeneration = recoveryToken.recoveryGeneration,
                )
            }
        }

    fun claimRecoveryRetry(token: VpnRuntimeRecoveryToken): VpnRuntimeRecoveryToken? =
        synchronized(stateLock) {
            if (!isCurrentRecoveryLocked(token)) return@synchronized null
            if (
                publishedSnapshot.phase !is VpnRuntimePhase.Recovering ||
                publishedSnapshot.bridgeRestarting
            ) return@synchronized null
            val next = token.copy(recoveryGeneration = nextRecoveryGeneration())
            publishedSnapshot = publishedSnapshot.copy(recoveryGeneration = next.recoveryGeneration)
            next
        }

    fun isCurrent(token: VpnRuntimeRecoveryToken): Boolean = synchronized(stateLock) {
        isCurrentRecoveryLocked(token)
    }

    fun isCurrentGeneration(generation: Int): Boolean =
        publishedSnapshot.lifecycleGeneration == generation &&
            publishedSnapshot.phase !is VpnRuntimePhase.Destroyed

    fun completeNoOpStart(token: VpnRuntimeCommandToken, runningPlan: ProfileRunPlan?): Boolean =
        synchronized(stateLock) {
            val startingPhase = publishedSnapshot.phase as? VpnRuntimePhase.Starting
                ?: return@synchronized false
            if (startingPhase.token != token || !isCurrentLocked(token)) return@synchronized false
            publishedSnapshot = if (runningPlan == null) {
                publishedSnapshot.copy(
                    phase = VpnRuntimePhase.Idle,
                    runningPlan = null,
                    stopping = false,
                    bridgeRestarting = false,
                )
            } else {
                publishedSnapshot.copy(
                    phase = VpnRuntimePhase.Running(token),
                    runningPlan = runningPlan,
                    explicitStopRequested = false,
                    stopping = false,
                    bridgeRestarting = false,
                )
            }
            true
        }

    fun destroy(serviceInstanceId: Long): VpnRuntimeCommandToken = synchronized(stateLock) {
        val token = nextToken(serviceInstanceId, persistent = false)
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Destroyed,
            lifecycleGeneration = token.lifecycleGeneration,
            serviceInstanceId = token.serviceInstanceId,
            recoveryGeneration = nextRecoveryGeneration(),
            explicitStopRequested = true,
            stopping = true,
            bridgeRestarting = false,
        )
        token
    }

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
        if (!isCurrent(token)) return@start
        try {
            if (hasRuntimeResources()) {
                transition(token) { current ->
                    current.copy(
                        phase = VpnRuntimePhase.Stopping(token, "runtime replacement"),
                        stopping = true,
                    )
                }
                stopExisting(token) { isCurrent(token) }
            }
            if (!isCurrent(token)) return@start
            transition(token) { current ->
                current.copy(phase = VpnRuntimePhase.Starting(token), stopping = false)
            }
            startRuntime(request, token, { isCurrent(token) }) { plan ->
                commitRunning(token, plan)
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val superseded = !isCurrent(token)
            if (!superseded) beginStartRollbackCleanup(token)
            val cleanupResult = rollbackStart(request, token, error, superseded)
            if (!superseded) completeStartRollbackCleanup(token, cleanupResult)
            throw error
        }
    }

    fun completeStartRollbackCleanup(
        token: VpnRuntimeCommandToken,
        result: VpnPlatformStopResult,
    ): Boolean = synchronized(stateLock) {
        val cleanupPhase = publishedSnapshot.phase as? VpnRuntimePhase.CleaningUp
            ?: return@synchronized false
        val cleanupOwner = cleanupPhase.owner as? VpnRuntimeCleanupOwner.StartRollback
            ?: return@synchronized false
        if (cleanupOwner.lifecycleToken != token || !isCurrentLocked(token)) {
            return@synchronized false
        }
        when (result) {
            VpnPlatformStopResult.Released -> {
                publishedSnapshot = publishedSnapshot.copy(
                    phase = VpnRuntimePhase.Idle,
                    runningPlan = null,
                    stopping = false,
                    bridgeRestarting = false,
                )
                true
            }
            VpnPlatformStopResult.RetainedForRetry -> true
        }
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
        if (!isCurrent(token)) return@stop
        transition(token) { current ->
            current.copy(phase = VpnRuntimePhase.Stopping(token, reason), stopping = true)
        }
        beforeStop(token) { isCurrent(token) }
        val result = stopRuntime(options, token) { isCurrent(token) }
        completePlatformStop(token, result)
    }

    /** Completes a token-owned teardown retry without exposing a generic phase setter. */
    fun completePlatformStop(
        token: VpnRuntimeCommandToken,
        result: VpnPlatformStopResult,
    ): Boolean = synchronized(stateLock) {
        val stoppingPhase = publishedSnapshot.phase as? VpnRuntimePhase.Stopping
            ?: return@synchronized false
        if (stoppingPhase.token != token || !isCurrentLocked(token)) return@synchronized false
        when (result) {
            VpnPlatformStopResult.Released -> {
                publishedSnapshot = publishedSnapshot.copy(
                    phase = VpnRuntimePhase.Idle,
                    runningPlan = null,
                    stopping = false,
                    bridgeRestarting = false,
                )
                true
            }
            VpnPlatformStopResult.RetainedForRetry -> true
        }
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
            if (error.isFatalProcessError()) throw error
            val superseded = !isCurrent(token)
            if (!superseded) beginRecoveryRollbackCleanup(token)
            val cleanupResult = rollbackRecovery(request, token, error, superseded)
            if (!superseded) completeRecoveryRollbackCleanup(token, cleanupResult)?.let { retryToken ->
                onRetryRequired(retryToken, request, error)
            }
        }
    }

    /** Completes only the retained cleanup owned by one failed Recovery generation. */
    fun completeRecoveryRollbackCleanup(
        token: VpnRuntimeRecoveryToken,
        result: VpnPlatformStopResult,
    ): VpnRuntimeRecoveryToken? = synchronized(stateLock) {
        val cleanupPhase = publishedSnapshot.phase as? VpnRuntimePhase.CleaningUp
            ?: return@synchronized null
        val cleanupOwner = cleanupPhase.owner as? VpnRuntimeCleanupOwner.RecoveryRollback
            ?: return@synchronized null
        if (cleanupOwner.recoveryToken != token || !isCurrentRecoveryLocked(token)) {
            return@synchronized null
        }
        when (result) {
            VpnPlatformStopResult.RetainedForRetry -> null
            VpnPlatformStopResult.Released -> finishRecoveryFailureLocked(token)
        }
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

    private fun nextToken(serviceInstanceId: Long, persistent: Boolean): VpnRuntimeCommandToken {
        val current = publishedSnapshot
        return VpnRuntimeCommandToken(
            serviceInstanceId = serviceInstanceId,
            lifecycleGeneration = current.lifecycleGeneration + 1,
            persistentGeneration = current.persistentCommandGeneration + if (persistent) 1 else 0,
        )
    }

    private fun nextRecoveryGeneration(): Long =
        if (publishedSnapshot.recoveryGeneration == Long.MAX_VALUE) 1L
        else publishedSnapshot.recoveryGeneration + 1L

    private fun inertToken(serviceInstanceId: Long) = VpnRuntimeCommandToken(
        serviceInstanceId = serviceInstanceId,
        lifecycleGeneration = publishedSnapshot.lifecycleGeneration,
        persistentGeneration = publishedSnapshot.persistentCommandGeneration,
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

    private fun commitRunning(token: VpnRuntimeCommandToken, plan: ProfileRunPlan): Boolean =
        synchronized(stateLock) {
            if (!isCurrentLocked(token)) return@synchronized false
            publishedSnapshot = publishedSnapshot.copy(
                phase = VpnRuntimePhase.Running(token),
                runningPlan = plan,
                explicitStopRequested = false,
                stopping = false,
                bridgeRestarting = false,
            )
            true
        }

    private fun commitRunningPlan(token: VpnRuntimeCommandToken, plan: ProfileRunPlan): Boolean =
        commitRunning(token, plan)

    private fun commitRecoveryRunning(
        token: VpnRuntimeRecoveryToken,
        plan: ProfileRunPlan,
    ): Boolean = synchronized(stateLock) {
        if (!isCurrentRecoveryLocked(token)) return@synchronized false
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Running(token.lifecycleToken),
            runningPlan = plan,
            explicitStopRequested = false,
            stopping = false,
            bridgeRestarting = false,
        )
        true
    }

    private fun requestReplacement(
        token: VpnRuntimeCommandToken,
        plan: ProfileRunPlan,
        failure: Throwable?,
        onReplacementRequired: (VpnRuntimeCommandToken, ProfileRunPlan, Throwable?) -> Unit,
    ) {
        val replacement = synchronized(stateLock) {
            if (!isCurrentLocked(token)) null else {
                val next = nextToken(token.serviceInstanceId, persistent = false)
                publishedSnapshot = publishedSnapshot.copy(
                    phase = VpnRuntimePhase.Starting(next),
                    lifecycleGeneration = next.lifecycleGeneration,
                    serviceInstanceId = next.serviceInstanceId,
                    recoveryGeneration = nextRecoveryGeneration(),
                    runningPlan = if (failure == null) publishedSnapshot.runningPlan else null,
                    explicitStopRequested = false,
                    stopping = false,
                    bridgeRestarting = false,
                )
                next
            }
        } ?: return
        onReplacementRequired(replacement, plan, failure)
    }

    private fun beginStartRollbackCleanup(token: VpnRuntimeCommandToken): Boolean =
        transition(token) { current ->
            current.copy(
                phase = VpnRuntimePhase.CleaningUp(
                    owner = VpnRuntimeCleanupOwner.StartRollback(token),
                    reason = "failed start rollback",
                ),
                runningPlan = null,
                stopping = true,
                bridgeRestarting = false,
            )
        }

    private fun beginRecoveryRollbackCleanup(token: VpnRuntimeRecoveryToken): Boolean =
        synchronized(stateLock) {
            if (!isCurrentRecoveryLocked(token)) return@synchronized false
            if (publishedSnapshot.phase !is VpnRuntimePhase.Recovering) return@synchronized false
            publishedSnapshot = publishedSnapshot.copy(
                phase = VpnRuntimePhase.CleaningUp(
                    owner = VpnRuntimeCleanupOwner.RecoveryRollback(token),
                    reason = "failed recovery rollback",
                ),
                runningPlan = null,
                stopping = true,
                bridgeRestarting = false,
            )
            true
        }

    private fun beginRecovery(token: VpnRuntimeRecoveryToken): Boolean = synchronized(stateLock) {
        if (!isCurrentRecoveryLocked(token)) return@synchronized false
        when (publishedSnapshot.phase) {
            is VpnRuntimePhase.Running,
            is VpnRuntimePhase.Recovering,
            -> Unit

            else -> return@synchronized false
        }
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Recovering(token.lifecycleToken),
            stopping = false,
            bridgeRestarting = true,
        )
        true
    }

    private fun completeRecoverySuccess(token: VpnRuntimeRecoveryToken): Boolean =
        synchronized(stateLock) {
            if (!isCurrentRecoveryLocked(token)) return@synchronized false
            if (publishedSnapshot.phase !is VpnRuntimePhase.Running) return@synchronized false
            publishedSnapshot = publishedSnapshot.copy(recoveryGeneration = nextRecoveryGeneration())
            true
        }

    private fun finishRecoveryFailureLocked(
        token: VpnRuntimeRecoveryToken,
    ): VpnRuntimeRecoveryToken {
        val retryToken = token.copy(recoveryGeneration = nextRecoveryGeneration())
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Recovering(token.lifecycleToken),
            stopping = false,
            bridgeRestarting = false,
            recoveryGeneration = retryToken.recoveryGeneration,
        )
        return retryToken
    }

    private fun VpnRuntimeCleanupOwner.withLifecycleToken(
        token: VpnRuntimeCommandToken,
    ): VpnRuntimeCleanupOwner = when (this) {
        is VpnRuntimeCleanupOwner.StartRollback -> copy(lifecycleToken = token)
        is VpnRuntimeCleanupOwner.RecoveryRollback -> copy(
            recoveryToken = recoveryToken.copy(lifecycleToken = token),
        )
    }

    private inline fun transition(
        token: VpnRuntimeCommandToken,
        change: (VpnRuntimeSnapshot) -> VpnRuntimeSnapshot,
    ): Boolean = synchronized(stateLock) {
        if (!isCurrentLocked(token)) return@synchronized false
        publishedSnapshot = change(publishedSnapshot)
        true
    }

    private fun isCurrentLocked(token: VpnRuntimeCommandToken): Boolean =
        publishedSnapshot.phase !is VpnRuntimePhase.Destroyed &&
            token.serviceInstanceId == publishedSnapshot.serviceInstanceId &&
            token.lifecycleGeneration == publishedSnapshot.lifecycleGeneration &&
            token.persistentGeneration == publishedSnapshot.persistentCommandGeneration

    private fun isCurrentRecoveryLocked(token: VpnRuntimeRecoveryToken): Boolean =
        isCurrentLocked(token.lifecycleToken) &&
            token.recoveryGeneration == publishedSnapshot.recoveryGeneration
}
