package com.tcptun.client

internal const val MaxVpnCommandPayloadLength = 384 * 1024

internal fun isVpnCommandPayloadWithinLimit(
    configLength: Int,
    planLength: Int,
    settingsPayloadLength: Int,
): Boolean {
    if (configLength < 0 || planLength < 0 || settingsPayloadLength < 0) return false
    return configLength.toLong() + planLength.toLong() + settingsPayloadLength.toLong() <=
        MaxVpnCommandPayloadLength.toLong()
}

/**
 * The Android service may only advertise a terminal stop after every bridge
 * resource has been released. Keeping this decision pure makes it difficult
 * for individual cleanup call sites to accidentally stop the service early.
 */
internal data class BridgeTeardownDisposition(
    val resourcesReleased: Boolean,
) {
    val mayPublishStopped: Boolean
        get() = resourcesReleased

    val mayRemoveForeground: Boolean
        get() = resourcesReleased

    val mayStopService: Boolean
        get() = resourcesReleased

    val shouldRetry: Boolean
        get() = !resourcesReleased
}

internal fun bridgeTeardownDisposition(hasOwnedResources: Boolean) =
    BridgeTeardownDisposition(resourcesReleased = !hasOwnedResources)

internal data class DeferredServiceStopRequest(
    val lifecycleGeneration: Int,
    val persistentCommandGeneration: Int,
    val startId: Int?,
)

/**
 * Remembers a stop that could not be honored while native resources were
 * owned. A newer lifecycle generation supersedes the request, preventing an
 * old cleanup retry from stopping a replacement VPN session.
 */
internal class DeferredServiceStopGate {
    private var pending: DeferredServiceStopRequest? = null

    @Synchronized
    fun defer(
        lifecycleGeneration: Int,
        persistentCommandGeneration: Int,
        startId: Int?,
    ) {
        pending = DeferredServiceStopRequest(
            lifecycleGeneration,
            persistentCommandGeneration,
            startId,
        )
    }

    @Synchronized
    fun consumeIfReleased(
        currentLifecycleGeneration: Int,
        currentPersistentCommandGeneration: Int,
        resourcesReleased: Boolean,
        activeServiceOwner: Boolean,
    ): DeferredServiceStopRequest? {
        if (!resourcesReleased) return null
        val request = pending ?: return null
        pending = null
        if (
            !activeServiceOwner ||
            request.lifecycleGeneration != currentLifecycleGeneration ||
            request.persistentCommandGeneration != currentPersistentCommandGeneration
        ) {
            return null
        }
        return request
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}

internal enum class ServiceCommandKind {
    StartOrRestore,
    Stop,
    UpdateConnections,
    Auxiliary,
}

/**
 * Fire-and-forget auxiliary commands must not turn a cold, empty service into
 * a sticky one. They remain accepted while a VPN start is already queued.
 */
internal fun shouldRejectColdAuxiliaryCommand(
    commandKind: ServiceCommandKind,
    hasRuntimeResources: Boolean,
    lifecycleWorkPending: Boolean,
    bridgeRecoveryPending: Boolean,
    teardownRetryPending: Boolean,
    terminalStopPending: Boolean,
): Boolean = commandKind == ServiceCommandKind.Auxiliary && (
    terminalStopPending || (
        !hasRuntimeResources &&
            !lifecycleWorkPending &&
            !bridgeRecoveryPending &&
            !teardownRetryPending
        )
    )

private val ExplicitlyHealthyBridgeEventStates = setOf(
    "core_ready",
    "running",
    "upstream_connected",
    "remote_endpoints_changed",
    "outbound_running",
)

/**
 * Only states that positively confirm a usable bridge may recover UI actions.
 * Transitional/degraded states intentionally remain non-healthy even though
 * they are displayed under the broader "Running" service bucket.
 */
internal fun isExplicitlyHealthyBridgeEventState(eventState: String): Boolean =
    eventState.trim().lowercase() in ExplicitlyHealthyBridgeEventStates

internal fun canRestoreConnectionsReady(
    runtimeStatus: VpnStatus,
    bridgeStatus: String,
    bridgeEventState: String,
    localProxyReachable: Boolean,
    sessionCurrent: Boolean,
    hasTun: Boolean,
    hasRunningPlan: Boolean,
    stopping: Boolean,
    bridgeRestarting: Boolean,
    explicitStopRequested: Boolean,
): Boolean = runtimeStatus == VpnStatus.Running &&
    bridgeStatus == "Running" &&
    isExplicitlyHealthyBridgeEventState(bridgeEventState) &&
    localProxyReachable &&
    sessionCurrent &&
    hasTun &&
    hasRunningPlan &&
    !stopping &&
    !bridgeRestarting &&
    !explicitStopRequested

/** Serializes callback delivery with registration invalidation. */
internal class CallbackEpochGate {
    private var nextEpoch = 0L
    private var activeEpoch = 0L

    @Synchronized
    fun activateNext(): Long {
        nextEpoch = if (nextEpoch == Long.MAX_VALUE) 1L else nextEpoch + 1L
        activeEpoch = nextEpoch
        return activeEpoch
    }

    @Synchronized
    fun invalidate(epoch: Long): Boolean {
        if (epoch <= 0L || activeEpoch != epoch) return false
        activeEpoch = 0L
        return true
    }

    @Synchronized
    fun <T> runIfActive(epoch: Long, action: () -> T): T? {
        if (epoch <= 0L || activeEpoch != epoch) return null
        return action()
    }

    @Synchronized
    fun isActive(epoch: Long): Boolean = epoch > 0L && activeEpoch == epoch
}
