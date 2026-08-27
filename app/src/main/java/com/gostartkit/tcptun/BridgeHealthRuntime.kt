package com.tcptun.client

import android.os.SystemClock
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal interface HealthBridgePort {
    fun statusJson(ownership: VpnRuntimeOwnership): String
    fun outboundsStatusJson(ownership: VpnRuntimeOwnership): String
    fun probeOutboundHealth(
        ownership: VpnRuntimeOwnership,
        tag: String,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): Long
}

/** Owns Bridge observation, monitor, and probe control flow without lifecycle transitions. */
internal class BridgeHealthRuntime(
    lifecycleExecutor: ScheduledExecutorService,
    private val bridgePort: HealthBridgePort,
    private val currentOwnership: () -> VpnRuntimeOwnership?,
    private val isOwnershipCurrent: (VpnRuntimeOwnership) -> Boolean,
    private val currentPlan: () -> ProfileRunPlan?,
    private val currentSettings: () -> AppliedRuntimeSettings,
    private val memberProbesAllowed: () -> Boolean,
    private val canHandleStatusEvent: () -> Boolean,
    private val restoreConnectionsReady: (VpnRuntimeOwnership) -> Unit,
    private val dispatchDiagnostics: (() -> Unit) -> Boolean,
    private val onRestartRequired: (VpnRuntimeOwnership, String, Boolean) -> Unit,
    private val log: (String) -> Unit,
    private val parseRuntimeSnapshot: (String, Long) -> BridgeRuntimeSnapshot = { raw, epoch ->
        BridgeStatusJson.parse(raw).runtimeSnapshot(epoch)
    },
) {
    // Native outbound probes are serialized by the Service's Bridge port, so a
    // wider pool only creates cancelled workers waiting on the same engine.
    private val memberHealthExecutor = newBoundedLifecycleExecutor(
        threadName = "TcptunMemberHealth",
        queueCapacity = MaxMemberHealthExecutorQueueCapacity,
    )
    private val memberHealthBatchSelector = RoundRobinBatchSelector()
    private val monitorGeneration = AtomicInteger()
    private val monitorWakeGeneration = AtomicInteger()
    private val monitorWaitLock = Object()
    private val localProxyHealthProbe = LocalProxyHealthProbe()
    private val monitor = BridgeHealthMonitorLoop(
        failureLimit = HealthFailureLimit,
        nextCheckDelayMillis = { confirmingFailure ->
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = currentSettings().powerSavingMode,
                confirmingFailure = confirmingFailure,
            )
        },
    )
    private val memberHealthProbeScheduler = MemberHealthProbeScheduler(
        executor = lifecycleExecutor,
        canRun = { currentOwnership() != null && memberProbesAllowed() },
        markProbeForced = VpnHealthCheckRequests::markMemberProbeForced,
        wakeMonitor = ::wake,
        log = log,
        maxDelayMs = VpnHealthCheckRequests.MaxMemberProbeDelayMs,
    )

    @Volatile
    private var monitorThread: Thread? = null

    val monitorWakeCallback: () -> Unit = ::wake
    val memberHealthProbeCallback: (String, Long) -> Unit = ::scheduleMemberProbe

    fun start() {
        stop()
        val ownership = currentOwnership() ?: return
        val generation = monitorGeneration.incrementAndGet()
        val initialHandledWakeGeneration = monitorWakeGeneration.get()
        monitorThread = startCrashGuardedThread(
            threadName = "TcptunBridgeMonitor",
            onFailure = { error -> log(failureDescription(error)) },
        ) {
            monitor.run(
                initialWakeGeneration = initialHandledWakeGeneration,
                isCurrent = {
                    generation == monitorGeneration.get() && isOwnershipCurrent(ownership)
                },
                canProbe = {
                    generation == monitorGeneration.get() && isOwnershipCurrent(ownership)
                },
                awaitEvent = ::awaitEvent,
                probeFailureReason = { healthFailure(generation, ownership)?.reason },
                onSchedule = schedule@{ schedule ->
                    if (!isOwnershipCurrent(ownership)) return@schedule
                    val diagnostics = TcptunState.state.value.diagnostics
                    if (
                        diagnostics.healthCheckEventDriven != schedule.eventDriven ||
                        diagnostics.healthCheckIntervalSeconds != schedule.intervalSeconds
                    ) {
                        TcptunState.updateDiagnosticsForBridgeEpoch(ownership.bridgeEpoch) {
                            it.copy(
                                healthCheckEventDriven = schedule.eventDriven,
                                healthCheckIntervalSeconds = schedule.intervalSeconds,
                            )
                        }
                    }
                },
                onFailure = { reason -> log("VPN health check failed: $reason") },
                onRestartRequired = { reason ->
                    onRestartRequired(ownership, reason, true)
                },
                onRecoverableError = { error ->
                    log("tcptun bridge monitor error: ${failureDescription(error)}")
                },
            )
        }
    }

    fun stop() {
        monitorGeneration.incrementAndGet()
        wake()
        monitorThread?.interrupt()
        monitorThread = null
    }

    fun wake() {
        PowerSavingObservability.bridgeMonitorEventWake()
        monitorWakeGeneration.incrementAndGet()
        synchronized(monitorWaitLock) { monitorWaitLock.notifyAll() }
    }

    fun scheduleMemberProbe(reason: String, requestedDelayMs: Long = 0L) {
        val ownership = currentOwnership() ?: return
        memberHealthProbeScheduler.schedule(reason, requestedDelayMs) {
            isOwnershipCurrent(ownership)
        }
    }

    fun cancelMemberProbe() {
        memberHealthProbeScheduler.cancel()
        VpnHealthCheckRequests.clearMemberProbeForce()
    }

    fun reset() {
        memberHealthBatchSelector.clear()
        memberHealthProbeScheduler.reset()
    }

    fun shutdown() {
        stop()
        memberHealthProbeScheduler.cancel()
        memberHealthExecutor.shutdownNow()
    }

    fun onStatusEvent(epoch: Long, event: BridgeStatusEvent) {
        if (!canHandleStatusEvent()) return
        val ownership = currentOwnership()?.takeIf { it.bridgeEpoch == epoch } ?: return
        val eventState = event.state.lowercase()
        if (
            eventState == "remote_endpoints_changed" ||
            event.reason.equals(TcptunBridgeEvents.RemoteEndpointsChanged, ignoreCase = true)
        ) {
            return
        }
        when (eventState) {
            "degraded", "reconnecting" -> scheduleMemberProbe("tcptun reported $eventState")
            "error", "stopped" -> {
                TcptunState.setConnectionsReady(false)
                TcptunState.clearTcping()
                scheduleMemberProbe("tcptun reported $eventState")
                onRestartRequired(ownership, "tcptun reported $eventState", true)
            }
        }
    }

    fun requestClientIpsRefresh() {
        dispatchDiagnostics(::refreshClientIps)
    }

    private fun awaitEvent(handledWakeGeneration: Int, timeoutMs: Long?): Int {
        val deadlineMs = timeoutMs?.let { SystemClock.elapsedRealtime() + it }
        synchronized(monitorWaitLock) {
            while (!Thread.currentThread().isInterrupted) {
                val currentWakeGeneration = monitorWakeGeneration.get()
                if (handledWakeGeneration != currentWakeGeneration) return currentWakeGeneration
                if (deadlineMs == null) {
                    monitorWaitLock.wait()
                } else {
                    val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0) return handledWakeGeneration
                    monitorWaitLock.wait(remainingMs)
                }
            }
        }
        return handledWakeGeneration
    }

    private fun healthFailure(
        monitorEpoch: Int,
        ownership: VpnRuntimeOwnership,
    ): HealthFailure? {
        if (!isOwnershipCurrent(ownership)) return null
        val sessionEpoch = ownership.bridgeEpoch
        val uiVisible = TcptunState.isUiVisible
        val previous = TcptunState.state.value.diagnostics
        // Prefer callback state already folded into TcptunState. Only a forced
        // refresh reconciles against the authoritative StatusJSON snapshot.
        val reconcile = shouldReconcileStatusJson()
        val observedStatus = if (reconcile) {
            reconcileStatusFromJson(ownership)?.ifBlank { TcptunState.status.displayName }
                ?: run {
                    TcptunState.updateDiagnosticsForBridgeEpoch(sessionEpoch) {
                        it.copy(localProxyReachable = false)
                    }
                    return HealthFailure("status unavailable")
                }
        } else {
            TcptunState.state.value.diagnostics.bridgeStatus
                .ifBlank { TcptunState.status.displayName }
                .ifBlank { "Unknown" }
        }
        val probeLocalProxy = BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible)
        val settings = currentSettings()
        val localProxyReachable = if (probeLocalProxy) {
            localProxyHealthProbe.canConnect(settings.socksPort)
        } else {
            true
        }
        if (!isOwnershipCurrent(ownership)) return null
        val localProxyAddress = localProxyHealthProbe.connectAddress(settings.socksPort)
        val nextLocalProxyReachable = if (probeLocalProxy) {
            localProxyReachable
        } else {
            previous.localProxyReachable
        }
        if (
            uiVisible ||
            previous.localProxyReachable != nextLocalProxyReachable ||
            previous.localProxyAddress != localProxyAddress ||
            previous.localProxyPort != settings.socksPort
        ) {
            if (!isOwnershipCurrent(ownership)) return null
            TcptunState.updateDiagnosticsForBridgeEpoch(sessionEpoch) {
                it.copy(
                    localProxyReachable = nextLocalProxyReachable,
                    localProxyAddress = localProxyAddress,
                    localProxyPort = settings.socksPort,
                )
            }
        }
        val status = TcptunState.state.value.diagnostics.bridgeStatus
            .ifBlank { observedStatus }
            .ifBlank { "Unknown" }
        if (status != "Running") return HealthFailure("bridge status is $status")
        if (probeLocalProxy && !localProxyReachable) {
            return HealthFailure("local proxy $localProxyAddress is not accepting connections")
        }
        if (reconcile && probeLocalProxy && localProxyReachable) {
            restoreConnectionsReady(ownership)
        }
        // Member probes only run when an event forced them. The aggregate local
        // SOCKS/HTTP probe remains UI-only through shouldRunUpstreamProbe().
        if (shouldProbeMemberHealth()) {
            val targets = localProxyHealthProbe.orderedTargets()
            probeActiveMembers(targets, monitorEpoch, ownership)
            if (monitorEpoch != monitorGeneration.get() || !isOwnershipCurrent(ownership)) return null
        }
        if (shouldRunUpstreamProbe()) {
            val targets = localProxyHealthProbe.orderedTargets()
            if (!isOwnershipCurrent(ownership)) return null
            val upstreamFailure = upstreamProbeFailure(targets)
            if (!isOwnershipCurrent(ownership)) return null
            updateRawProfileHealth(upstreamFailure, ownership)
            upstreamFailure?.let { return HealthFailure(it) }
        }
        return null
    }

    private fun reconcileStatusFromJson(ownership: VpnRuntimeOwnership): String? =
        runRecoverableCatching {
            val snapshot = BridgeStatusJson.parse(bridgePort.statusJson(ownership))
            if (!isOwnershipCurrent(ownership)) return@runRecoverableCatching null
            val status = TcptunState.bridgeSimpleStatus(snapshot.state)
            val applied = TcptunState.reconcileBridgeStatusSnapshotForEpoch(
                epoch = ownership.bridgeEpoch,
                sessionId = snapshot.sessionId,
                sequence = snapshot.sequence,
                bridgeStatus = status,
                bridgeLastError = snapshot.lastError.orEmpty(),
                eventState = snapshot.state,
            ) {
                snapshot.applyTo(it, status)
            }
            when {
                applied -> status
                isOwnershipCurrent(ownership) ->
                    TcptunState.state.value.diagnostics.bridgeStatus.ifBlank { status }
                else -> null
            }
        }.getOrNull()

    private fun probeActiveMembers(
        targets: List<UpstreamProbeTarget>,
        monitorEpoch: Int,
        ownership: VpnRuntimeOwnership,
    ) {
        val candidates = currentPlan()?.activeProfiles.orEmpty().filter { it.rawConfigJson.isBlank() }
        val sessionEpoch = ownership.bridgeEpoch
        if (candidates.isEmpty() || targets.isEmpty() || sessionEpoch <= 0L) return
        val worstCaseProfileMs = MemberHealthProbeTimeoutMs.toLong() * targets.size
        val maxProfiles = (MemberHealthBatchTimeoutMs / worstCaseProfileMs)
            .toInt()
            .coerceAtLeast(1)
        val profiles = memberHealthBatchSelector.select(candidates, maxProfiles)
        val tasks = profiles.map { profile ->
            Callable { probeMember(profile, targets, ownership) }
        }
        val timeoutMs = minOf(
            MemberHealthBatchTimeoutMs,
            worstCaseProfileMs * profiles.size,
        ) + MemberHealthProbeGraceMs
        val futures = try {
            memberHealthExecutor.purge()
            memberHealthExecutor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            memberHealthExecutor.purge()
        }
        if (monitorEpoch != monitorGeneration.get() || !isOwnershipCurrent(ownership)) return
        val coreRefreshProfiles = mutableListOf<AppConfig>()
        var retryTransientFailure = false
        futures.forEachIndexed { index, future ->
            val profile = profiles[index]
            if (!isOwnershipCurrent(ownership) || profile.id !in currentPlan()?.activeIds.orEmpty()) {
                return@forEachIndexed
            }
            val result = runRecoverableCatching {
                if (future.isCancelled) {
                    MemberHealthProbeResult(profile, error = "health probe timed out")
                } else {
                    future.get()
                }
            }.getOrElse { error ->
                MemberHealthProbeResult(
                    profile,
                    error = error.cause?.message ?: error.message ?: error.javaClass.simpleName,
                )
            }
            val previous = TcptunState.state.value.profileHealth[profile.id]
            val now = System.currentTimeMillis()
            val hasNoCompletedProbe = previous == null ||
                (previous.lastSucceededAtMs <= 0L && previous.lastCheckedAtMs <= 0L)
            val health = if (result.elapsedMs != null) {
                ProfileHealth(
                    status = ProfileHealthStatus.Healthy,
                    latencyMs = result.elapsedMs,
                    failures = 0,
                    lastCheckedAtMs = now,
                    lastSucceededAtMs = now,
                )
            } else if (
                BridgeHealthPolicy.isTransientMemberProbeFailure(result.error) &&
                hasNoCompletedProbe
            ) {
                log("connection ${profile.name} health probe deferred: ${result.error}")
                retryTransientFailure = true
                previous?.copy(lastCheckedAtMs = now) ?: ProfileHealth(lastCheckedAtMs = now)
            } else {
                coreRefreshProfiles += profile
                ProfileHealth(
                    status = ProfileHealthStatus.Degraded,
                    latencyMs = previous?.latencyMs,
                    failures = (previous?.failures ?: 0) + 1,
                    lastCheckedAtMs = now,
                    lastSucceededAtMs = previous?.lastSucceededAtMs ?: 0,
                    error = result.error,
                )
            }
            if (result.elapsedMs != null) coreRefreshProfiles += profile
            if (!isOwnershipCurrent(ownership)) return@forEachIndexed
            TcptunState.setProfileHealthForBridgeEpoch(sessionEpoch, profile.id, health)
            if (previous?.status != health.status) {
                val detail = health.latencyMs?.let { "${it}ms" }
                    ?: health.error.ifBlank { "unknown" }
                log("connection ${profile.name} health: ${health.status.name.lowercase()} $detail")
            }
        }
        if (coreRefreshProfiles.isNotEmpty()) {
            refreshProfileHealthFromCore(coreRefreshProfiles, ownership)
        }
        if (
            retryTransientFailure && monitorEpoch == monitorGeneration.get() &&
            isOwnershipCurrent(ownership)
        ) {
            scheduleMemberProbe(
                reason = "retry transient member health failure",
                requestedDelayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
            )
        }
    }

    private fun updateRawProfileHealth(failure: String?, ownership: VpnRuntimeOwnership) {
        if (!isOwnershipCurrent(ownership)) return
        val profile = currentPlan()?.activeProfiles
            ?.singleOrNull { it.rawConfigJson.isNotBlank() }
            ?: return
        val previous = TcptunState.state.value.profileHealth[profile.id]
        val now = System.currentTimeMillis()
        val health = if (failure == null) {
            ProfileHealth(
                status = ProfileHealthStatus.Healthy,
                failures = 0,
                lastCheckedAtMs = now,
                lastSucceededAtMs = now,
            )
        } else {
            ProfileHealth(
                status = ProfileHealthStatus.Degraded,
                latencyMs = previous?.latencyMs,
                failures = (previous?.failures ?: 0) + 1,
                lastCheckedAtMs = now,
                lastSucceededAtMs = previous?.lastSucceededAtMs ?: 0,
                error = failure,
            )
        }
        if (!isOwnershipCurrent(ownership)) return
        TcptunState.setProfileHealthForBridgeEpoch(ownership.bridgeEpoch, profile.id, health)
    }

    private fun refreshProfileHealthFromCore(
        profiles: List<AppConfig>,
        ownership: VpnRuntimeOwnership,
    ) {
        val profileByTag = profiles.associateBy(AppConfig::runtimeOutboundTag)
        runRecoverableCatching {
            BridgeStatusJson.parseOutboundHealth(bridgePort.outboundsStatusJson(ownership))
        }
            .onSuccess { statuses ->
                for (status in statuses) {
                    if (!isOwnershipCurrent(ownership)) return@onSuccess
                    val profile = profileByTag[status.tag] ?: continue
                    if (profile.id !in currentPlan()?.activeIds.orEmpty()) continue
                    val previous = TcptunState.state.value.profileHealth[profile.id]
                    TcptunState.setProfileHealthForBridgeEpoch(
                        ownership.bridgeEpoch,
                        profile.id,
                        ProfileHealth(
                            status = status.health,
                            latencyMs = status.latencyMs,
                            failures = status.failures,
                            lastCheckedAtMs = status.lastObservedAtMs,
                            lastSucceededAtMs = status.lastSucceededAtMs,
                            error = previous?.error
                                .takeIf { status.health == ProfileHealthStatus.Degraded }
                                .orEmpty(),
                        ),
                    )
                }
            }
            .onFailure { error ->
                log("outbound health status unavailable: ${error.message}")
            }
    }

    private fun probeMember(
        profile: AppConfig,
        targets: List<UpstreamProbeTarget>,
        ownership: VpnRuntimeOwnership,
    ): MemberHealthProbeResult {
        val failures = mutableListOf<String>()
        for (target in targets) {
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("health probe cancelled")
            }
            val elapsed = runRecoverableCatching {
                bridgePort.probeOutboundHealth(
                    ownership = ownership,
                    tag = profile.runtimeOutboundTag(),
                    host = target.host,
                    port = target.port,
                    timeoutMillis = MemberHealthProbeTimeoutMs,
                )
            }
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("health probe cancelled")
            }
            elapsed.getOrNull()?.let { return MemberHealthProbeResult(profile, elapsedMs = it) }
            val error = elapsed.exceptionOrNull()
            failures += "${target.label}: ${error?.message ?: error?.javaClass?.simpleName ?: "failed"}"
        }
        return MemberHealthProbeResult(profile, error = failures.joinToString("; "))
    }

    private fun refreshClientIps() {
        val ownership = currentOwnership() ?: return
        if (TcptunState.status != VpnStatus.Running) return
        val snapshot = runRecoverableCatching {
            parseRuntimeSnapshot(bridgePort.statusJson(ownership), ownership.bridgeEpoch)
        }.getOrNull() ?: return
        if (!isOwnershipCurrent(ownership)) return
        TcptunState.updateDiagnosticsForBridgeEpoch(snapshot.epoch) {
            it.copy(
                bridgeActiveConnections = snapshot.activeConnections,
                bridgeClientIps = snapshot.clientIps,
            )
        }
    }

    private fun shouldRunUpstreamProbe(): Boolean {
        val force = VpnHealthCheckRequests.consumeUpstreamProbeForce()
        val allowed = BridgeHealthPolicy.shouldRunUpstreamProbe(
            uiVisible = TcptunState.isUiVisible,
            force = force,
        )
        if (!allowed && force && !TcptunState.isUiVisible) {
            VpnHealthCheckRequests.restoreUpstreamProbeForce()
        }
        return allowed
    }

    private fun shouldReconcileStatusJson(): Boolean {
        val force = VpnHealthCheckRequests.consumeStatusReconcileForce()
        val allowed = BridgeHealthPolicy.shouldReconcileStatusJson(
            uiVisible = TcptunState.isUiVisible,
            force = force,
        )
        if (!allowed && force && !TcptunState.isUiVisible) {
            VpnHealthCheckRequests.restoreStatusReconcileForce()
        }
        return allowed
    }

    private fun shouldProbeMemberHealth(): Boolean {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val notBeforeMs = memberHealthProbeScheduler.notBeforeMs
        // Do not consume the force during the settle window; its delayed wake
        // must still be able to run the requested probe afterward.
        if (notBeforeMs > 0L && nowElapsedMs < notBeforeMs) return false
        val force = VpnHealthCheckRequests.consumeMemberProbeForce()
        return BridgeHealthPolicy.shouldProbeMemberHealth(
            force = force,
            nowMs = nowElapsedMs,
            notBeforeMs = notBeforeMs,
        )
    }

    private fun upstreamProbeFailure(targets: List<UpstreamProbeTarget>): String? {
        val settings = currentSettings()
        val probeUser = settings.localProxyUsers.firstOrNull()
        return localProxyHealthProbe.upstreamFailure(
            orderedTargets = targets,
            localPort = settings.socksPort,
            proxyUser = probeUser,
            onSuccess = { target -> log("upstream probe ${target.label} succeeded") },
        )
    }

    private companion object {
        const val HealthFailureLimit = 2
        const val MemberHealthProbeTimeoutMs = 3_000L
        const val MemberHealthProbeGraceMs = 1_000L
        const val MemberHealthBatchTimeoutMs = 30_000L
        // One-target batches can contain up to ten profiles. Leave headroom for
        // invokeAll's current batch while bounding captured ownership graphs.
        const val MaxMemberHealthExecutorQueueCapacity = 16
    }
}
