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

internal sealed interface VpnRuntimePhase {
    data object Idle : VpnRuntimePhase
    data class Starting(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data class Running(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data class Stopping(
        val token: VpnRuntimeCommandToken,
        val reason: String,
    ) : VpnRuntimePhase
    data class Recovering(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data object Destroyed : VpnRuntimePhase
}

internal data class VpnRuntimeSnapshot(
    val phase: VpnRuntimePhase = VpnRuntimePhase.Idle,
    val lifecycleGeneration: Int = 0,
    val persistentCommandGeneration: Int = 0,
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

    fun isCurrentGeneration(generation: Int): Boolean =
        publishedSnapshot.lifecycleGeneration == generation &&
            publishedSnapshot.phase !is VpnRuntimePhase.Destroyed

    fun updateRunningPlan(plan: ProfileRunPlan?) = synchronized(stateLock) {
        if (publishedSnapshot.phase !is VpnRuntimePhase.Destroyed) {
            publishedSnapshot = publishedSnapshot.copy(runningPlan = plan)
        }
    }

    fun markBridgeRestarting(restarting: Boolean) = synchronized(stateLock) {
        if (publishedSnapshot.phase !is VpnRuntimePhase.Destroyed) {
            publishedSnapshot = publishedSnapshot.copy(bridgeRestarting = restarting)
        }
    }

    fun markRecovering(token: VpnRuntimeCommandToken): Boolean = synchronized(stateLock) {
        if (!isCurrentLocked(token)) return@synchronized false
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Recovering(token),
            stopping = false,
        )
        true
    }

    fun completeNoOpStart(token: VpnRuntimeCommandToken, runningPlan: ProfileRunPlan?): Boolean =
        if (runningPlan == null) {
            transitionToIdle(token)
        } else {
            commitRunning(token, runningPlan)
        }

    fun completeCurrentStop(): Boolean = synchronized(stateLock) {
        val phase = publishedSnapshot.phase as? VpnRuntimePhase.Stopping ?: return@synchronized false
        transitionToIdle(phase.token)
    }

    fun destroy(serviceInstanceId: Long): VpnRuntimeCommandToken = synchronized(stateLock) {
        val token = nextToken(serviceInstanceId, persistent = false)
        publishedSnapshot = publishedSnapshot.copy(
            phase = VpnRuntimePhase.Destroyed,
            lifecycleGeneration = token.lifecycleGeneration,
            serviceInstanceId = token.serviceInstanceId,
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
        rollbackStart: (VpnRuntimeStartRequest, VpnRuntimeCommandToken, Throwable, Boolean) -> Unit,
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
            rollbackStart(request, token, error, superseded)
            if (!superseded) transitionToIdle(token)
            throw error
        }
    }

    /** Owns the common Stop transaction entry and terminal runtime-state commit. */
    fun dispatchStop(
        token: VpnRuntimeCommandToken,
        reason: String,
        options: VpnRuntimeStopOptions,
        onFailure: (Throwable) -> Unit,
        beforeStop: (VpnRuntimeCommandToken, () -> Boolean) -> Unit = { _, _ -> },
        stopRuntime: (VpnRuntimeStopOptions, VpnRuntimeCommandToken, () -> Boolean) -> Boolean,
    ): Boolean = dispatch(VpnRuntimeCommand.Stop, onFailure) stop@{
        if (!isCurrent(token)) return@stop
        transition(token) { current ->
            current.copy(phase = VpnRuntimePhase.Stopping(token, reason), stopping = true)
        }
        beforeStop(token) { isCurrent(token) }
        val released = stopRuntime(options, token) { isCurrent(token) }
        if (released && isCurrent(token)) transitionToIdle(token)
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

    private fun transitionToIdle(token: VpnRuntimeCommandToken): Boolean = transition(token) { current ->
        current.copy(
            phase = VpnRuntimePhase.Idle,
            runningPlan = null,
            stopping = false,
            bridgeRestarting = false,
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
}
