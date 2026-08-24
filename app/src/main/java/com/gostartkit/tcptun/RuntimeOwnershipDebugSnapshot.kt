package com.tcptun.client

import java.util.concurrent.ConcurrentHashMap

/**
 * Debug/test-only ownership view. It deliberately contains no profile,
 * endpoint, credential, URI, or configuration payload.
 */
internal data class RuntimeOwnershipDebugSnapshot(
    val serviceInstanceId: Long,
    val lifecycleGeneration: Int,
    val persistentGeneration: Int,
    val recoveryGeneration: Long,
    val bridgeEpoch: Long,
    val bridgeResourcePhase: BridgeResourcePhase,
    val tunOwned: Boolean,
    val leaseOwner: Long,
    val teardownPending: Boolean,
    val runtimePhase: String,
    val activeServiceOwner: Boolean,
    val destroyed: Boolean,
    val vpnStatus: VpnStatus,
    val connectionsReady: Boolean,
    val actorPhase: String = runtimePhase,
    val actorOwnerServiceId: Long = serviceInstanceId,
    val actorGeneration: Int = lifecycleGeneration,
)

internal data class RuntimeOwnershipDebugCapture(
    val lifecycleGeneration: Int,
    val persistentGeneration: Int,
    val recoveryGeneration: Long,
    val bridgeEpoch: Long,
    val bridgeResourcePhase: BridgeResourcePhase,
    val tunOwned: Boolean,
    val leaseOwner: Long,
    val teardownPending: Boolean,
    val runtimePhase: String,
    val activeServiceOwner: Boolean,
    val destroyed: Boolean,
    val vpnStatus: VpnStatus,
    val connectionsReady: Boolean,
    val actorPhase: String = runtimePhase,
    val actorOwnerServiceId: Long = 0L,
    val actorGeneration: Int = lifecycleGeneration,
)

internal fun stableRuntimeOwnershipDebugSnapshot(
    serviceInstanceId: Long,
    capture: () -> RuntimeOwnershipDebugCapture,
): RuntimeOwnershipDebugSnapshot {
    var latest = capture()
    repeat(8) {
        val next = capture()
        if (latest == next) return next.toSnapshot(serviceInstanceId)
        latest = next
    }
    return latest.toSnapshot(serviceInstanceId)
}

private fun RuntimeOwnershipDebugCapture.toSnapshot(
    serviceInstanceId: Long,
) = RuntimeOwnershipDebugSnapshot(
    serviceInstanceId = serviceInstanceId,
    lifecycleGeneration = lifecycleGeneration,
    persistentGeneration = persistentGeneration,
    recoveryGeneration = recoveryGeneration,
    bridgeEpoch = bridgeEpoch,
    bridgeResourcePhase = bridgeResourcePhase,
    tunOwned = tunOwned,
    leaseOwner = leaseOwner,
    teardownPending = teardownPending,
    runtimePhase = runtimePhase,
    activeServiceOwner = activeServiceOwner,
    destroyed = destroyed,
    vpnStatus = vpnStatus,
    connectionsReady = connectionsReady,
    actorPhase = actorPhase,
    actorOwnerServiceId = actorOwnerServiceId.takeIf { it > 0L } ?: serviceInstanceId,
    actorGeneration = actorGeneration,
)

/** Tracks every live debug Service, including an old instance retaining native cleanup. */
internal object RuntimeOwnershipDebugRegistry {
    private val providers = ConcurrentHashMap<Long, () -> RuntimeOwnershipDebugSnapshot>()

    fun install(
        serviceInstanceId: Long,
        provider: () -> RuntimeOwnershipDebugSnapshot,
    ) {
        if (!BuildConfig.DEBUG) return
        check(providers.putIfAbsent(serviceInstanceId, provider) == null) {
            "debug ownership provider already installed"
        }
    }

    fun remove(serviceInstanceId: Long) {
        if (BuildConfig.DEBUG) providers.remove(serviceInstanceId)
    }

    fun snapshots(): List<RuntimeOwnershipDebugSnapshot> {
        check(BuildConfig.DEBUG) { "runtime ownership snapshots require a debug build" }
        var latest = captureAll()
        repeat(8) {
            val next = captureAll()
            if (latest == next) return next
            latest = next
        }
        return latest
    }

    private fun captureAll(): List<RuntimeOwnershipDebugSnapshot> =
        providers.values.map { it() }.sortedBy(RuntimeOwnershipDebugSnapshot::serviceInstanceId)
}
