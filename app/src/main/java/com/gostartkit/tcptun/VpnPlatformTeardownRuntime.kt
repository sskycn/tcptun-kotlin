package com.tcptun.client

import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal sealed interface VpnPlatformCleanupOwner {
    data class Stop(
        val token: VpnRuntimeCommandToken,
    ) : VpnPlatformCleanupOwner

    data class StartRollback(
        val token: VpnRuntimeCommandToken,
    ) : VpnPlatformCleanupOwner

    data class RecoveryRollback(
        val token: VpnRuntimeRecoveryToken,
        val request: VpnRuntimeRecoveryRequest,
        val failure: Throwable,
    ) : VpnPlatformCleanupOwner
}

internal data class VpnPlatformTeardownRequest(
    val setStopped: Boolean = true,
    val clearSavedConfig: Boolean = true,
    val stopSelfService: Boolean = true,
    val globalStateOwner: (() -> Boolean)? = null,
    val globalStateCommitLock: Any? = null,
    val cleanupOwner: VpnPlatformCleanupOwner? = null,
)

internal fun VpnPlatformTeardownRequest.runGlobalCleanupStep(
    serviceOwnerLock: Any,
    label: String,
    cleanupStep: (String, () -> Unit) -> Unit,
    action: () -> Unit,
) {
    val owner = globalStateOwner
    if (owner == null) {
        cleanupStep(label, action)
        return
    }
    synchronized(serviceOwnerLock) {
        val commitLock = globalStateCommitLock
        if (commitLock == null) {
            if (owner()) cleanupStep(label, action)
        } else {
            synchronized(commitLock) {
                if (owner()) cleanupStep(label, action)
            }
        }
    }
}

internal fun releasedVpnDiagnostics(current: TcptunDiagnostics): TcptunDiagnostics = current.copy(
    bridgeStatus = "Stopped",
    bridgeActiveConnections = 0,
    bridgeClientIps = emptyList(),
    bridgeMuxSources = 0,
    bridgeMuxSessions = 0,
    bridgeMuxStreams = 0,
    localProxyReachable = false,
    localProxyAddress = RuntimeSettingsRepository.defaultLocalSocksConnectAddress(),
    localProxyPort = RuntimeSettingsDefaults.SocksPort,
    healthCheckEventDriven = true,
    healthCheckIntervalSeconds = 0,
    socketProtectEnabled = false,
    vpnIpv4RouteCount = 0,
    vpnIpv6RouteCount = 0,
    vpnDnsServerCount = 0,
    vpnFakeIpRouteCount = 0,
    p2pConfigured = false,
    p2pHostCandidatesConfigured = false,
)

internal val DefaultVpnPlatformTeardownRetryDelaysMillis =
    listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L)

internal fun newVpnPlatformTeardownRuntime(
    executor: ScheduledExecutorService,
    performCleanup: (VpnPlatformTeardownRequest) -> VpnPlatformStopResult,
    completeOwner: (VpnPlatformCleanupOwner, VpnPlatformStopResult) -> Unit,
    resourcesOwned: () -> Boolean,
    dispatchLifecycleRetry: (task: () -> Unit) -> Boolean,
    isDestroyed: () -> Boolean,
    log: (String) -> Unit,
) = VpnPlatformTeardownRuntime(
    performCleanup = performCleanup,
    completeOwner = completeOwner,
    resourcesOwned = resourcesOwned,
    scheduleRetry = { delayMillis, task ->
        scheduleCrashGuardedFuture(
            executor = executor,
            delay = delayMillis,
            unit = TimeUnit.MILLISECONDS,
            taskName = "tcptun teardown retry",
            onFailure = { error ->
                if (!isDestroyed()) {
                    log("tcptun teardown retry failed: ${failureDescription(error)}")
                }
            },
            task = task,
        )
    },
    dispatchLifecycleRetry = dispatchLifecycleRetry,
    isDestroyed = isDestroyed,
    log = log,
)

/**
 * Owns retained platform-cleanup generations, backoff, and the pending retry Future.
 * Coordinator completion remains a Service callback so this runtime cannot mutate phases.
 */
internal class VpnPlatformTeardownRuntime(
    retryDelaysMillis: List<Long> = DefaultVpnPlatformTeardownRetryDelaysMillis,
    private val performCleanup: (VpnPlatformTeardownRequest) -> VpnPlatformStopResult,
    private val completeOwner: (VpnPlatformCleanupOwner, VpnPlatformStopResult) -> Unit,
    private val resourcesOwned: () -> Boolean,
    private val scheduleRetry: (delayMillis: Long, task: () -> Unit) -> Future<*>?,
    private val dispatchLifecycleRetry: (task: () -> Unit) -> Boolean,
    private val isDestroyed: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private data class RetainedCleanup(
        val generation: Long,
        val request: VpnPlatformTeardownRequest,
        var scheduledAttempts: Int = 0,
        var exhaustionLogged: Boolean = false,
    )

    private data class AdmittedRetryFuture(
        val generation: Long,
        val attempt: Int,
        val future: Future<*>,
    )

    private val lock = Any()
    private val retryDelaysMillis = retryDelaysMillis.toList()
    private var generation = 0L
    private var retainedCleanup: RetainedCleanup? = null
    private var retryFuture: AdmittedRetryFuture? = null
    private var shutdown = false

    init {
        require(retryDelaysMillis.isNotEmpty()) { "teardown retry delays must not be empty" }
        require(retryDelaysMillis.all { it >= 0L }) {
            "teardown retry delays must not be negative"
        }
    }

    val pending: Boolean
        get() = synchronized(lock) { retainedCleanup != null }

    /** Records the result returned to the coordinator by the initial one-shot attempt. */
    fun acceptInitialResult(
        request: VpnPlatformTeardownRequest,
        result: VpnPlatformStopResult,
    ) {
        when (result) {
            VpnPlatformStopResult.Released -> invalidateRetainedCleanup()
            VpnPlatformStopResult.RetainedForRetry -> retain(request)
        }
    }

    fun shutdown() {
        val future = synchronized(lock) {
            if (shutdown) return
            shutdown = true
            generation = nextGeneration(generation)
            retainedCleanup = null
            retryFuture.also { retryFuture = null }?.future
        }
        future?.cancel(false)
    }

    private fun retain(request: VpnPlatformTeardownRequest) {
        val admission: Pair<RetainedCleanup, Future<*>?> = synchronized(lock) {
            if (shutdown || isDestroyed()) return
            generation = nextGeneration(generation)
            val cleanup = RetainedCleanup(generation, request)
            retainedCleanup = cleanup
            cleanup to retryFuture.also { retryFuture = null }?.future
        }
        val (next, previousFuture) = admission
        previousFuture?.cancel(false)
        scheduleNext(next.generation)
    }

    private fun scheduleNext(expectedGeneration: Long) {
        if (completeIfReleased(expectedGeneration) || isDestroyed()) return
        val retry = synchronized(lock) {
            val cleanup = retainedCleanup ?: return
            if (shutdown || cleanup.generation != expectedGeneration) return
            val delay = retryDelaysMillis.getOrNull(cleanup.scheduledAttempts)
            if (delay == null) {
                if (!cleanup.exhaustionLogged) cleanup.exhaustionLogged = true else return
                null
            } else {
                cleanup.scheduledAttempts += 1
                Retry(cleanup.request, cleanup.scheduledAttempts, retryDelaysMillis.size, delay)
            }
        }
        if (retry == null) {
            log(
                "tcptun cleanup remains incomplete after ${retryDelaysMillis.size} retries; " +
                    "service retained for safe process teardown",
            )
            return
        }
        val future = scheduleRetry(retry.delayMillis) {
            runRetry(expectedGeneration, retry)
        }
        if (future == null) {
            if (isCurrent(expectedGeneration)) {
                log("tcptun teardown retry could not be scheduled; service retained")
            }
            return
        }
        val accepted = synchronized(lock) {
            val cleanup = retainedCleanup
            val previous = retryFuture
            val admissionIsFresh = !shutdown &&
                cleanup?.generation == expectedGeneration &&
                cleanup.scheduledAttempts == retry.attempt
            val newerOrDifferentFutureInstalled = previous != null &&
                (previous.generation != expectedGeneration || previous.attempt > retry.attempt)
            if (!admissionIsFresh || newerOrDifferentFutureInstalled) {
                false
            } else {
                previous?.future?.cancel(false)
                retryFuture = AdmittedRetryFuture(expectedGeneration, retry.attempt, future)
                true
            }
        }
        if (!accepted) {
            future.cancel(false)
            return
        }
        // Native teardown may finish between the first ownership check and Future admission.
        completeIfReleased(expectedGeneration)
    }

    private fun runRetry(expectedGeneration: Long, retry: Retry) {
        if (!isCurrent(expectedGeneration) || isDestroyed()) return
        if (completeIfReleased(expectedGeneration)) return
        log("retrying tcptun cleanup (${retry.attempt}/${retry.maxAttempts})")
        val dispatched = dispatchLifecycleRetry {
            if (!isCurrent(expectedGeneration) || isDestroyed()) return@dispatchLifecycleRetry
            if (completeIfReleased(expectedGeneration)) return@dispatchLifecycleRetry
            val result = performCleanup(retry.request)
            when (result) {
                VpnPlatformStopResult.Released -> completeReleased(expectedGeneration)
                VpnPlatformStopResult.RetainedForRetry -> scheduleNext(expectedGeneration)
            }
        }
        if (!dispatched && isCurrent(expectedGeneration) && !isDestroyed()) {
            log("tcptun teardown retry could not enter lifecycle lane; service retained")
        }
    }

    private fun completeIfReleased(expectedGeneration: Long): Boolean {
        if (resourcesOwned()) return false
        completeReleased(expectedGeneration)
        return true
    }

    private fun completeReleased(expectedGeneration: Long) {
        val completion: Pair<VpnPlatformCleanupOwner?, Future<*>?> = synchronized(lock) {
            val cleanup = retainedCleanup ?: return
            if (shutdown || cleanup.generation != expectedGeneration) return
            retainedCleanup = null
            generation = nextGeneration(generation)
            val future = retryFuture?.future
            retryFuture = null
            cleanup.request.cleanupOwner to future
        }
        completion.second?.cancel(false)
        completion.first?.let { completeOwner(it, VpnPlatformStopResult.Released) }
    }

    private fun invalidateRetainedCleanup() {
        val future = synchronized(lock) {
            generation = nextGeneration(generation)
            retainedCleanup = null
            retryFuture.also { retryFuture = null }?.future
        }
        future?.cancel(false)
    }

    private fun isCurrent(expectedGeneration: Long): Boolean = synchronized(lock) {
        !shutdown && retainedCleanup?.generation == expectedGeneration
    }

    private data class Retry(
        val request: VpnPlatformTeardownRequest,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMillis: Long,
    )

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L
}
