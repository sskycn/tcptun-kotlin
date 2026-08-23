package com.tcptun.client

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal data class RuntimeSettingsDesiredMutation(
    val sequence: Long,
    val forceRestart: Boolean,
)

internal data class RuntimeSettingsApplyClaim(
    val mutation: RuntimeSettingsDesiredMutation,
    val ownership: VpnRuntimeOwnership,
)

internal data class AppliedRuntimeState(
    val ownership: VpnRuntimeOwnership,
    val settings: AppliedRuntimeSettings,
)

/** Settings are the previous runtime's recovery input, not authoritative desired state. */
internal data class RuntimeSettingsRecoveryInput(
    val configJson: String,
    val plan: ProfileRunPlan,
    val settings: AppliedRuntimeSettings,
    val reason: String,
)

internal enum class RuntimeSettingsReconciliationAction {
    Satisfied,
    ApplyHot,
    Replace,
}

internal enum class RuntimeSettingsHotMutation {
    LogLevel,
    FlowAnalysis;

    val description: String
        get() = when (this) {
            LogLevel -> "log.level"
            FlowAnalysis -> "flow analysis"
        }
}

internal sealed interface RuntimeSettingsHotApplyResult {
    data object Applied : RuntimeSettingsHotApplyResult
    data object Superseded : RuntimeSettingsHotApplyResult
    data class RestartRequired(
        val mutation: RuntimeSettingsHotMutation,
        val failure: Throwable,
    ) : RuntimeSettingsHotApplyResult
}

/** Owns desired/runtime-applied metadata; platform and native mutations remain in the service. */
internal class RuntimeSettingsRuntimeState {
    private var sequence = 0L
    private var pendingMutation: RuntimeSettingsDesiredMutation? = null
    private val debounceTask = LatestTaskSlot()

    @Volatile
    private var appliedState: AppliedRuntimeState? = null

    val latestSequence: Long
        @Synchronized get() = sequence

    val pending: RuntimeSettingsDesiredMutation?
        @Synchronized get() = pendingMutation

    val applied: AppliedRuntimeState?
        get() = appliedState

    val effectiveSettings: AppliedRuntimeSettings
        get() = appliedState?.settings ?: AppliedRuntimeSettings()

    @Synchronized
    fun requestDesired(forceRestart: Boolean): RuntimeSettingsDesiredMutation {
        sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
        return RuntimeSettingsDesiredMutation(
            sequence = sequence,
            forceRestart = pendingMutation?.forceRestart == true || forceRestart,
        ).also { pendingMutation = it }
    }

    fun requestDesiredDebounced(
        forceRestart: Boolean,
        ownership: VpnRuntimeOwnership?,
        executor: ScheduledExecutorService,
        delayMillis: Long,
        canRun: () -> Boolean,
        onFailure: (Throwable) -> Unit,
        onReady: (RuntimeSettingsApplyClaim) -> Unit,
        onUnavailable: (retained: Boolean) -> Unit,
    ) {
        val mutation = requestDesired(forceRestart)
        if (ownership == null) {
            onUnavailable(true)
            return
        }
        val future = scheduleCrashGuardedFuture(
            executor = executor,
            delay = delayMillis,
            unit = TimeUnit.MILLISECONDS,
            taskName = "runtime settings debounce",
            onFailure = onFailure,
        ) {
            if (!canRun()) return@scheduleCrashGuardedFuture
            val claim = bindLatest(ownership) ?: return@scheduleCrashGuardedFuture
            if (claim.mutation.sequence == mutation.sequence) onReady(claim)
        } ?: run {
            if (canRun()) onUnavailable(false)
            return
        }
        debounceTask.replace(future)
    }

    /**
     * Queue the debounced apply behind every lifecycle command already accepted.
     * The snapshots prevent a delayed settings thread from restarting over a
     * newer start/stop/outbound update.
     */
    @Synchronized
    fun dispatchLatest(
        claim: RuntimeSettingsApplyClaim,
        activeOwnership: VpnRuntimeOwnership?,
        blocked: Boolean,
        dispatch: () -> Boolean,
    ): Boolean? {
        if (
            blocked ||
            pendingMutation?.sequence != claim.mutation.sequence ||
            activeOwnership != claim.ownership
        ) return null
        return dispatch()
    }

    @Synchronized
    fun <T> runIfLatestOwned(
        claim: RuntimeSettingsApplyClaim,
        activeOwnership: VpnRuntimeOwnership?,
        action: () -> T,
    ): T? {
        if (
            pendingMutation?.sequence != claim.mutation.sequence ||
            activeOwnership != claim.ownership
        ) return null
        return action()
    }

    @Synchronized
    fun bindLatest(ownership: VpnRuntimeOwnership): RuntimeSettingsApplyClaim? =
        pendingMutation?.let { RuntimeSettingsApplyClaim(it, ownership) }

    @Synchronized
    fun isLatest(claim: RuntimeSettingsApplyClaim): Boolean =
        pendingMutation?.sequence == claim.mutation.sequence

    @Synchronized
    fun acknowledge(sequence: Long): Boolean {
        if (pendingMutation?.sequence != sequence) return false
        pendingMutation = null
        return true
    }

    @Synchronized
    fun publishFreshRuntime(
        ownership: VpnRuntimeOwnership,
        settings: AppliedRuntimeSettings,
        activeOwnership: VpnRuntimeOwnership?,
    ): Boolean {
        if (ownership != activeOwnership) return false
        appliedState = AppliedRuntimeState(ownership, settings)
        return true
    }

    @Synchronized
    fun publishFreshRuntime(
        token: VpnRuntimeCommandToken,
        settings: AppliedRuntimeSettings,
        activeOwnership: VpnRuntimeOwnership?,
    ): VpnRuntimeOwnership? {
        val active = activeOwnership ?: return null
        val ownership = VpnRuntimeOwnership(token, active.bridgeEpoch)
        appliedState = AppliedRuntimeState(ownership, settings).takeIf {
            ownership == active
        } ?: return null
        return ownership
    }

    /** Atomically records a confirmed native mutation without requiring desired-sequence freshness. */
    @Synchronized
    fun checkpointHotApplied(
        ownership: VpnRuntimeOwnership,
        activeOwnership: VpnRuntimeOwnership?,
        transform: (AppliedRuntimeSettings) -> AppliedRuntimeSettings,
    ): Boolean {
        val current = appliedState ?: return false
        if (current.ownership != ownership || activeOwnership != ownership) return false
        appliedState = current.copy(settings = transform(current.settings))
        return true
    }

    fun checkpointHotAppliedOrRejectCurrent(
        ownership: VpnRuntimeOwnership,
        activeOwnership: VpnRuntimeOwnership?,
        transform: (AppliedRuntimeSettings) -> AppliedRuntimeSettings,
    ): Boolean {
        val updated = checkpointHotApplied(ownership, activeOwnership, transform)
        check(updated || activeOwnership != ownership) {
            "current runtime rejected its hot-applied settings checkpoint"
        }
        return updated
    }

    /** Rebinds the same live native state after an auxiliary lifecycle claim, or invalidates it. */
    @Synchronized
    fun rebindAppliedOwnership(
        token: VpnRuntimeCommandToken,
        activeOwnership: VpnRuntimeOwnership?,
    ): Boolean {
        val previous = appliedState ?: return false
        if (
            activeOwnership == null ||
            activeOwnership.runtimeToken != token ||
            previous.ownership.bridgeEpoch != activeOwnership.bridgeEpoch
        ) {
            appliedState = null
            return false
        }
        appliedState = AppliedRuntimeState(activeOwnership, previous.settings)
        return true
    }

    @Synchronized
    fun reconciliationAction(
        claim: RuntimeSettingsApplyClaim,
        desired: AppliedRuntimeSettings,
        freshRuntimeSatisfiesForce: Boolean,
    ): RuntimeSettingsReconciliationAction? {
        if (pendingMutation?.sequence != claim.mutation.sequence) return null
        val current = appliedState?.takeIf { it.ownership == claim.ownership } ?: return null
        return desiredRuntimeSettingsAction(
            current.settings,
            desired,
            claim.mutation.forceRestart,
            freshRuntimeSatisfiesForce,
        )
    }

    /** Consumes an already-satisfied mutation, otherwise binds it to the fresh runtime. */
    @Synchronized
    fun reconcileFreshRuntime(
        ownership: VpnRuntimeOwnership,
        desired: AppliedRuntimeSettings,
        freshRuntimeDesiredSequence: Long?,
        activeOwnership: VpnRuntimeOwnership?,
        onPending: (RuntimeSettingsApplyClaim) -> Unit,
    ) {
        if (activeOwnership != ownership) return
        val mutation = pendingMutation ?: return
        val current = appliedState?.takeIf { it.ownership == ownership } ?: return
        val action = desiredRuntimeSettingsAction(
            current.settings,
            desired,
            mutation.forceRestart,
            freshRuntimeDesiredSequence == mutation.sequence,
        )
        if (action == RuntimeSettingsReconciliationAction.Satisfied) {
            pendingMutation = null
            return
        }
        onPending(RuntimeSettingsApplyClaim(mutation, ownership))
    }

    /**
     * Runs only native hot adapters supplied by the service. Every confirmed side effect is
     * checkpointed before desired freshness is consulted again.
     */
    fun reconcile(
        claim: RuntimeSettingsApplyClaim,
        desired: AppliedRuntimeSettings,
        applyLogLevel: (String) -> Unit,
        applyFlowAnalysis: (String) -> Unit,
        checkpoint: (
            (AppliedRuntimeSettings) -> AppliedRuntimeSettings,
        ) -> Boolean,
        onApplied: () -> Unit,
        onReplacementRequired: (RuntimeSettingsHotApplyResult.RestartRequired?) -> Unit,
    ) {
        when (reconciliationAction(claim, desired, freshRuntimeSatisfiesForce = false)) {
            null -> return
            RuntimeSettingsReconciliationAction.Replace -> {
                onReplacementRequired(null)
                return
            }
            RuntimeSettingsReconciliationAction.Satisfied,
            RuntimeSettingsReconciliationAction.ApplyHot,
            -> Unit
        }
        when (
            val result = applyHot(
                claim,
                desired,
                applyLogLevel,
                applyFlowAnalysis,
                checkpoint,
            )
        ) {
            RuntimeSettingsHotApplyResult.Applied -> onApplied()
            RuntimeSettingsHotApplyResult.Superseded -> Unit
            is RuntimeSettingsHotApplyResult.RestartRequired -> onReplacementRequired(result)
        }
    }

    fun applyHot(
        claim: RuntimeSettingsApplyClaim,
        desired: AppliedRuntimeSettings,
        applyLogLevel: (String) -> Unit,
        applyFlowAnalysis: (String) -> Unit,
        checkpoint: (
            (AppliedRuntimeSettings) -> AppliedRuntimeSettings,
        ) -> Boolean,
    ): RuntimeSettingsHotApplyResult {
        var actual = appliedForLatestClaim(claim) ?: return RuntimeSettingsHotApplyResult.Superseded
        if (actual.logLevel != desired.logLevel) {
            hotMutationFailure(RuntimeSettingsHotMutation.LogLevel) {
                applyLogLevel(desired.logLevel)
            }?.let { return it }
            if (!checkpoint { it.copy(logLevel = desired.logLevel) }) {
                return RuntimeSettingsHotApplyResult.Superseded
            }
            if (!isLatest(claim)) return RuntimeSettingsHotApplyResult.Superseded
            actual = appliedForLatestClaim(claim) ?: return RuntimeSettingsHotApplyResult.Superseded
        }
        if (actual.flowAnalysisApp != desired.flowAnalysisApp) {
            hotMutationFailure(RuntimeSettingsHotMutation.FlowAnalysis) {
                applyFlowAnalysis(desired.flowAnalysisApp)
            }?.let { return it }
            if (!checkpoint { it.copy(flowAnalysisApp = desired.flowAnalysisApp) }) {
                return RuntimeSettingsHotApplyResult.Superseded
            }
            if (!isLatest(claim)) return RuntimeSettingsHotApplyResult.Superseded
        }
        if (!checkpoint { desired }) return RuntimeSettingsHotApplyResult.Superseded
        return if (acknowledge(claim.mutation.sequence)) {
            RuntimeSettingsHotApplyResult.Applied
        } else {
            RuntimeSettingsHotApplyResult.Superseded
        }
    }

    @Synchronized
    fun clearApplied() {
        appliedState = null
    }

    @Synchronized
    fun clearForStop() {
        pendingMutation = null
        appliedState = null
        debounceTask.cancel()
    }

    @Synchronized
    private fun appliedForLatestClaim(claim: RuntimeSettingsApplyClaim): AppliedRuntimeSettings? {
        if (pendingMutation?.sequence != claim.mutation.sequence) return null
        return appliedState?.takeIf { it.ownership == claim.ownership }?.settings
    }

    private inline fun hotMutationFailure(
        mutation: RuntimeSettingsHotMutation,
        action: () -> Unit,
    ): RuntimeSettingsHotApplyResult.RestartRequired? = try {
        action()
        null
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        RuntimeSettingsHotApplyResult.RestartRequired(mutation, error)
    }
}

internal fun desiredRuntimeSettingsAction(
    applied: AppliedRuntimeSettings,
    desired: AppliedRuntimeSettings,
    forceRestart: Boolean,
    freshRuntimeSatisfiesForce: Boolean,
): RuntimeSettingsReconciliationAction = when {
    applied == desired && (!forceRestart || freshRuntimeSatisfiesForce) ->
        RuntimeSettingsReconciliationAction.Satisfied
    forceRestart || BridgeHealthPolicy.isStructuralRuntimeChange(
        applied.structuralSettings(),
        desired.structuralSettings(),
    ) -> RuntimeSettingsReconciliationAction.Replace
    else -> RuntimeSettingsReconciliationAction.ApplyHot
}
