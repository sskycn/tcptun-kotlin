package com.tcptun.client

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class TcptunDiagnostics(
    val vpnStatus: String = "Stopped",
    val bridgeStatus: String = "Unknown",
    val bridgeEventState: String = "Unknown",
    val bridgeEventReason: String = "None",
    val bridgeEventPhase: String = "None",
    val bridgeOutboundTag: String = "",
    val bridgeListen: String = "",
    val bridgeRemote: String = "",
    val bridgeActiveConnections: Int = 0,
    val bridgeClientIps: List<String> = emptyList(),
    val bridgeMuxSources: Int = 0,
    val bridgeMuxSessions: Int = 0,
    val bridgeMuxStreams: Int = 0,
    val bridgeRecoverable: Boolean = false,
    val bridgeLastError: String = "",
    val bridgeTimestampMs: Long = 0,
    val bridgeSessionId: Long = 0,
    val bridgeSequence: Long = 0,
    val underlyingNetwork: String = "None",
    val localProxyReachable: Boolean = false,
    val localProxyAddress: String = "127.0.0.1:1080",
    val localProxyPort: Int = 1080,
    val lastRestartReason: String = "None",
    val mtu: Int = 1400,
    val powerSavingMode: Boolean = true,
    /** True when health checks are event/pull-driven only (no timer poll). */
    val healthCheckEventDriven: Boolean = true,
    val healthCheckIntervalSeconds: Long = 0,
    val socketProtectEnabled: Boolean = false,
)

data class TcptunRuntimeState(
    val status: VpnStatus = VpnStatus.Stopped,
    val lastError: String = "",
    /**
     * True only after VPN/outbounds have finished starting or updating.
     * TCPing and similar actions require this so users cannot probe while
     * StartOutbound / bridge bring-up is still in progress.
     */
    val connectionsReady: Boolean = false,
    val diagnostics: TcptunDiagnostics = TcptunDiagnostics(),
    val tcping: TcpingProgress = TcpingProgress(),
    val profileHealth: Map<String, ProfileHealth> = emptyMap(),
    val logs: List<String> = emptyList(),
    val flowAnalysisApp: String = "",
    val profileStateRevision: Long = 0,
)

data class FlowAnalysisState(
    val events: List<FlowAnalysisEvent> = emptyList(),
    val droppedEvents: Long = 0,
)

enum class ProfileHealthStatus {
    Unknown,
    Healthy,
    Degraded,
}

data class ProfileHealth(
    val status: ProfileHealthStatus = ProfileHealthStatus.Unknown,
    val latencyMs: Long? = null,
    val failures: Long = 0,
    val lastCheckedAtMs: Long = 0,
    val lastSucceededAtMs: Long = 0,
    val error: String = "",
)

data class TcpingLinkResult(
    val profileName: String,
    val elapsedMs: Long? = null,
    val error: String = "",
)

data class TcpingProgress(
    val requestId: Long = 0,
    val targetLabel: String = "",
    val running: Boolean = false,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val currentProfileName: String = "",
    val results: List<TcpingLinkResult> = emptyList(),
    val error: String = "",
) {
    val averageMs: Long?
        get() {
            val successes = results.mapNotNull(TcpingLinkResult::elapsedMs)
            return successes.takeIf { it.isNotEmpty() }?.average()?.roundToLong()
    }
}

internal class UiVisibilityTracker {
    private var ownerCount = 0

    val isVisible: Boolean
        @Synchronized get() = ownerCount > 0

    @Synchronized
    fun acquire(): UiVisibilityLease {
        ownerCount += 1
        return UiVisibilityLease(::release)
    }

    @Synchronized
    private fun release() {
        check(ownerCount > 0) { "UI visibility lease released without an owner" }
        ownerCount -= 1
    }
}

internal class UiVisibilityLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

object TcptunState {
    private const val MAX_LOGS = 80
    private const val MAX_LOG_LENGTH = 4_096
    private const val MAX_STATUS_EVENT_JSON_LENGTH = 64 * 1024
    private const val MAX_STATUS_FIELD_LENGTH = 4 * 1024
    private const val MAX_FLOW_EVENTS = 256
    private const val FLOW_PUBLISH_INTERVAL_MILLIS = 100L
    private const val LOG_TAG = "TcpTun"

    private val _state = MutableStateFlow(TcptunRuntimeState())
    val state: StateFlow<TcptunRuntimeState> = _state.asStateFlow()
    private val _flowAnalysis = MutableStateFlow(FlowAnalysisState())
    val flowAnalysis: StateFlow<FlowAnalysisState> = _flowAnalysis.asStateFlow()

    val vpnStatusFlow: Flow<VpnStatus> = state.selectRuntimeState(::selectVpnStatus)
    val diagnosticsFlow: Flow<TcptunDiagnostics> = state.selectRuntimeState(::selectDiagnostics)
    val logsFlow: Flow<List<String>> = state.selectRuntimeState(::selectLogs)
    val tcpingFlow: Flow<TcpingProgress> = state.selectRuntimeState(::selectTcping)
    val profileHealthFlow: Flow<Map<String, ProfileHealth>> = state.selectRuntimeState(::selectProfileHealth)
    val flowAnalysisAppFlow: Flow<String> = state.selectRuntimeState(::selectFlowAnalysisApp)
    val profileStateRevisionFlow: Flow<Long> = state.selectRuntimeState(::selectProfileStateRevision)
    internal val profilesRuntimeUiFlow: Flow<ProfilesRuntimeUiState> = state.selectRuntimeState(::selectProfilesRuntimeUi)
    internal val diagnosticsRuntimeUiFlow: Flow<DiagnosticsRuntimeUiState> = state.selectRuntimeState(::selectDiagnosticsRuntimeUi)
    internal val ipInformationRuntimeUiFlow: Flow<IpInformationRuntimeUiState> = state.selectRuntimeState(::selectIpInformationRuntimeUi)
    internal val settingsRuntimeUiFlow: Flow<SettingsRuntimeUiState> = state.selectRuntimeState(::selectSettingsRuntimeUi)

    val status: VpnStatus get() = state.value.status
    val lastError: String get() = state.value.lastError
    val diagnostics: TcptunDiagnostics get() = state.value.diagnostics
    val logs: List<String> get() = state.value.logs
    val profileStateRevision: Long get() = state.value.profileStateRevision
    internal val profilesRuntimeUi: ProfilesRuntimeUiState get() = selectProfilesRuntimeUi(state.value)
    internal val diagnosticsRuntimeUi: DiagnosticsRuntimeUiState get() = selectDiagnosticsRuntimeUi(state.value)
    internal val ipInformationRuntimeUi: IpInformationRuntimeUiState get() = selectIpInformationRuntimeUi(state.value)
    internal val settingsRuntimeUi: SettingsRuntimeUiState get() = selectSettingsRuntimeUi(state.value)

    private var bridgeEpoch = 0L
    private var bridgeSessionId = -1L
    private var bridgeSequence = -1L
    private var flowSessionId = -1L
    private var flowSequence = -1L
    private var bridgeDroppedFlowEvents = 0L
    private val flowEvents = BoundedRingBuffer<FlowAnalysisEvent>(MAX_FLOW_EVENTS)
    private val flowPublishScheduled = AtomicBoolean()
    private val flowPublishExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tcptun-flow-publisher").apply { isDaemon = true }
    }
    private val pendingBackgroundLogs = BoundedRingBuffer<String>(MAX_LOGS)
    private var pendingBackgroundLogLast: String? = null
    private var tcpingRequestId = 0L
    private val uiVisibility = UiVisibilityTracker()

    val isUiVisible: Boolean
        get() = uiVisibility.isVisible

    @Synchronized
    internal fun acquireUiVisibility(): UiVisibilityLease {
        val lease = uiVisibility.acquire()
        flushBackgroundLogs()
        publishFlowEventsNow()
        return lease
    }

    @Synchronized
    fun beginBridgeSession(): Long {
        bridgeEpoch = if (bridgeEpoch == Long.MAX_VALUE) 1L else bridgeEpoch + 1L
        bridgeSessionId = -1L
        bridgeSequence = -1L
        flowSessionId = -1L
        flowSequence = -1L
        val current = _state.value
        _state.value = current.copy(
            diagnostics = current.diagnostics.copy(
                bridgeStatus = "Starting",
                bridgeEventState = "starting",
                bridgeEventReason = "START_REQUESTED",
                bridgeEventPhase = "Core runtime is starting",
                bridgeOutboundTag = "",
                bridgeRecoverable = false,
                bridgeLastError = "",
                bridgeActiveConnections = 0,
                bridgeClientIps = emptyList(),
                bridgeMuxSources = 0,
                bridgeMuxSessions = 0,
                bridgeMuxStreams = 0,
                bridgeSessionId = 0,
                bridgeSequence = 0,
            ),
        )
        return bridgeEpoch
    }

    /** Invalidates callbacks from a stopped engine before a replacement session starts. */
    @Synchronized
    internal fun endBridgeSession(epoch: Long): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch) return false
        bridgeEpoch = if (bridgeEpoch == Long.MAX_VALUE) 1L else bridgeEpoch + 1L
        bridgeSessionId = -1L
        bridgeSequence = -1L
        flowSessionId = -1L
        flowSequence = -1L
        return true
    }

    @Synchronized
    fun setFlowAnalysisApp(packageName: String) {
        val normalized = normalizeFlowAnalysisApp(packageName)
        val current = _state.value
        if (normalized == current.flowAnalysisApp) return
        flowSessionId = -1L
        flowSequence = -1L
        flowEvents.clear()
        bridgeDroppedFlowEvents = 0L
        _flowAnalysis.value = FlowAnalysisState()
        _state.value = current.copy(flowAnalysisApp = normalized)
    }

    @Synchronized
    internal fun applyBridgeFlowEvent(epoch: Long, eventJson: String): FlowAnalysisEvent? {
        if (epoch != bridgeEpoch) return null
        val event = parseFlowAnalysisEvent(eventJson) ?: return null
        val current = _state.value
        if (current.flowAnalysisApp.isBlank() || event.appId != current.flowAnalysisApp) return null
        if (
            event.sessionId < flowSessionId ||
            (event.sessionId == flowSessionId && event.sequence <= flowSequence)
        ) {
            return null
        }
        flowSessionId = event.sessionId
        flowSequence = event.sequence
        flowEvents.append(event)
        bridgeDroppedFlowEvents = maxOf(bridgeDroppedFlowEvents, event.droppedEvents)
        if (
            PowerSavingObservationPolicy.shouldPublish(
                powerSaving = current.diagnostics.powerSavingMode,
                uiVisible = isUiVisible,
            )
        ) {
            scheduleFlowSnapshot()
        }
        return event
    }

    private fun scheduleFlowSnapshot() {
        if (!flowPublishScheduled.compareAndSet(false, true)) return
        flowPublishExecutor.schedule(
            { publishFlowEventsNow() },
            FLOW_PUBLISH_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    internal fun publishFlowEventsNow() {
        _flowAnalysis.value = FlowAnalysisState(
            events = flowEvents.snapshot(),
            droppedEvents = maxOf(bridgeDroppedFlowEvents, flowEvents.droppedCount),
        )
        flowPublishScheduled.set(false)
    }

    @Synchronized
    fun clearFlowEvents() {
        flowEvents.clear()
        bridgeDroppedFlowEvents = 0L
        _flowAnalysis.value = FlowAnalysisState()
    }

    @Synchronized
    fun setStatus(value: VpnStatus) {
        val current = _state.value
        val terminal = value.isTerminal
        val transitioning = value.isTransitioning
        _state.value = current.copy(
            status = value,
            // Bring-up / teardown is never TCPing-ready; only an explicit ready
            // mark after a successful start/update re-enables it.
            connectionsReady = if (terminal || transitioning) false else current.connectionsReady,
            lastError = if (value == VpnStatus.Error) current.lastError else "",
            diagnostics = if (terminal) {
                terminalDiagnostics(current.diagnostics, value, current.lastError)
            } else {
                current.diagnostics.copy(vpnStatus = value.displayName)
            },
            tcping = if (terminal) TcpingProgress() else current.tcping,
            profileHealth = if (terminal) emptyMap() else current.profileHealth,
        )
    }

    @Synchronized
    fun setConnectionsReady(ready: Boolean) {
        val current = _state.value
        if (current.connectionsReady == ready) return
        _state.value = current.copy(connectionsReady = ready)
    }

    @Synchronized
    internal fun errorIfStatus(expectedStatus: VpnStatus, message: String): Boolean {
        if (_state.value.status != expectedStatus) return false
        error(message)
        return true
    }

    @Synchronized
    internal fun restoreCommandStateIfStatus(
        expectedStatus: VpnStatus,
        restoredStatus: VpnStatus,
        restoredConnectionsReady: Boolean,
        restoredLastError: String = "",
    ): Boolean {
        val current = _state.value
        if (current.status != expectedStatus) return false
        _state.value = current.copy(
            status = restoredStatus,
            connectionsReady = restoredConnectionsReady,
            lastError = redactSensitiveText(restoredLastError.take(MAX_STATUS_FIELD_LENGTH)),
            diagnostics = current.diagnostics.copy(vpnStatus = restoredStatus.displayName),
        )
        return true
    }

    @Synchronized
    internal fun restoreConnectionsReadyIfStatus(
        expectedStatus: VpnStatus,
        restoredConnectionsReady: Boolean,
    ): Boolean {
        val current = _state.value
        if (current.status != expectedStatus) return false
        // A previously dispatched update may have completed while the newer
        // command was failing to dispatch. Never downgrade that confirmed ready state.
        _state.value = current.copy(
            connectionsReady = current.connectionsReady || restoredConnectionsReady,
        )
        return true
    }

    /** Call when a connection start/stop/update is requested but not finished. */
    @Synchronized
    fun markConnectionsBusy(reason: String = "") {
        val current = _state.value
        if (!current.connectionsReady && reason.isBlank()) return
        _state.value = current.copy(connectionsReady = false)
        if (reason.isNotBlank()) appendLog(reason)
    }

    @Synchronized
    fun error(message: String) {
        val safeMessage = redactSensitiveText(message.take(MAX_STATUS_FIELD_LENGTH))
            .trim()
            .ifBlank { "Unknown error" }
        val current = _state.value
        _state.value = current.copy(
            status = VpnStatus.Error,
            connectionsReady = false,
            lastError = safeMessage,
            diagnostics = terminalDiagnostics(current.diagnostics, VpnStatus.Error, safeMessage),
            tcping = TcpingProgress(),
            profileHealth = emptyMap(),
        )
        appendLog("error: $safeMessage")
    }

    @Synchronized
    fun updateDiagnostics(update: (TcptunDiagnostics) -> TcptunDiagnostics) {
        val current = _state.value
        _state.value = current.copy(diagnostics = sanitizeDiagnostics(update(current.diagnostics)))
    }

    @Synchronized
    internal fun updateDiagnosticsForBridgeEpoch(
        epoch: Long,
        update: (TcptunDiagnostics) -> TcptunDiagnostics,
    ): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch) return false
        val current = _state.value
        _state.value = current.copy(diagnostics = sanitizeDiagnostics(update(current.diagnostics)))
        return true
    }

    /**
     * Applies an authoritative StatusJSON snapshot and advances the same cursor
     * used by callbacks. This prevents a delayed callback from overwriting a
     * newer pull-to-refresh snapshot.
     */
    @Synchronized
    internal fun reconcileBridgeStatusSnapshotForEpoch(
        epoch: Long,
        sessionId: Long,
        sequence: Long,
        bridgeStatus: String,
        bridgeLastError: String,
        eventState: String = "",
        update: (TcptunDiagnostics) -> TcptunDiagnostics,
    ): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch) return false
        val safeSessionId = sessionId.coerceAtLeast(0L)
        val safeSequence = sequence.coerceAtLeast(0L)
        if (
            safeSessionId < bridgeSessionId ||
            (safeSessionId == bridgeSessionId && safeSequence < bridgeSequence)
        ) {
            return false
        }
        bridgeSessionId = safeSessionId
        bridgeSequence = safeSequence
        val current = _state.value
        val updatedDiagnostics = sanitizeDiagnostics(
            update(current.diagnostics).copy(
                bridgeStatus = bridgeStatus,
                bridgeSessionId = safeSessionId,
                bridgeSequence = safeSequence,
            ),
        )
        _state.value = current.copy(
            lastError = bridgeDisplayError(
                runtimeStatus = current.status,
                currentError = current.lastError,
                bridgeStatus = bridgeStatus,
                bridgeLastError = bridgeLastError,
                eventState = eventState,
            ),
            diagnostics = updatedDiagnostics,
        )
        return true
    }

    @Synchronized
    internal fun applyBridgeStatusEvent(epoch: Long, eventJson: String): BridgeStatusEvent? {
        if (epoch != bridgeEpoch) return null
        if (eventJson.length > MAX_STATUS_EVENT_JSON_LENGTH) {
            appendLog("tcptun status ignored: event is too large")
            return null
        }
        val event = runRecoverableCatching {
            requireSafeJsonNesting(eventJson)
            BridgeStatusJson.parse(eventJson).toEvent()
        }.getOrElse { err ->
            appendLog("tcptun status parse failed: ${err.message}")
            return null
        }
        if (
            event.sessionId < bridgeSessionId ||
            (event.sessionId == bridgeSessionId && event.sequence <= bridgeSequence)
        ) {
            return null
        }
        bridgeSessionId = event.sessionId
        bridgeSequence = event.sequence

        val current = _state.value
        val simpleBridgeStatus = bridgeSimpleStatus(event.state)
        _state.value = current.copy(
            lastError = bridgeDisplayError(
                runtimeStatus = current.status,
                currentError = current.lastError,
                bridgeStatus = simpleBridgeStatus,
                bridgeLastError = event.lastError,
                eventState = event.state,
            ),
            diagnostics = sanitizeDiagnostics(
                current.diagnostics.copy(
                    bridgeStatus = simpleBridgeStatus,
                    bridgeEventState = event.state.ifBlank { "Unknown" },
                    bridgeEventReason = event.reason.ifBlank { "None" },
                    bridgeEventPhase = event.phase.ifBlank { "None" },
                    bridgeListen = event.listen,
                    bridgeOutboundTag = event.outboundTag,
                    // An empty remote explicitly means that no managed outbound is connected.
                    bridgeRemote = event.remote,
                    bridgeActiveConnections = event.activeConnections,
                    bridgeClientIps = event.clientIps,
                    bridgeMuxSources = event.muxSources,
                    bridgeMuxSessions = event.muxSessions,
                    bridgeMuxStreams = event.muxStreams,
                    bridgeRecoverable = event.recoverable,
                    bridgeLastError = event.lastError,
                    bridgeTimestampMs = event.timestampMs,
                    bridgeSessionId = event.sessionId,
                    bridgeSequence = event.sequence,
                ),
            ),
        )
        if (event.shouldLog()) appendLog(event.logLine())
        return event
    }

    @Synchronized
    fun appendLog(line: String) {
        val clean = redactSensitiveText(line.take(MAX_LOG_LENGTH)).trim()
        if (clean.isEmpty()) return
        val current = _state.value
        val publishNow = PowerSavingObservationPolicy.shouldPublish(
            powerSaving = current.diagnostics.powerSavingMode,
            uiVisible = isUiVisible,
        )
        if (!publishNow) {
            if (
                pendingBackgroundLogLast == clean ||
                (pendingBackgroundLogLast == null && current.logs.lastOrNull() == clean)
            ) {
                return
            }
            pendingBackgroundLogs.append(clean)
            pendingBackgroundLogLast = clean
            return
        }

        flushBackgroundLogs()
        // Logcat is still UI-only; disabling power saving only keeps the in-app observation
        // stream live and does not introduce background logcat I/O.
        if (isUiVisible) {
            Log.i(LOG_TAG, clean)
        }
        val refreshed = _state.value
        if (refreshed.logs.lastOrNull() == clean) return
        _state.value = refreshed.copy(logs = (refreshed.logs + clean).takeLast(MAX_LOGS))
    }

    private fun flushBackgroundLogs() {
        val pending = pendingBackgroundLogs.snapshot()
        if (pending.isEmpty()) return
        val merged = _state.value.logs.toMutableList()
        pending.forEach { line ->
            if (merged.lastOrNull() != line) merged += line
        }
        pendingBackgroundLogs.clear()
        pendingBackgroundLogLast = null
        _state.value = _state.value.copy(logs = merged.takeLast(MAX_LOGS))
    }

    @Synchronized
    fun clearLogs() {
        pendingBackgroundLogs.clear()
        pendingBackgroundLogLast = null
        _state.value = _state.value.copy(logs = emptyList())
    }

    @Synchronized
    fun notifyProfileStateChanged() {
        val current = _state.value
        val nextRevision = if (current.profileStateRevision == Long.MAX_VALUE) 1L else current.profileStateRevision + 1L
        _state.value = current.copy(profileStateRevision = nextRevision)
    }

    @Synchronized
    fun initializeProfileHealth(profiles: List<AppConfig>) {
        val current = _state.value
        _state.value = current.copy(
            profileHealth = profiles.associate { profile ->
                profile.id to (current.profileHealth[profile.id] ?: ProfileHealth())
            },
        )
    }

    @Synchronized
    fun resetProfileHealth(profiles: List<AppConfig>) {
        _state.value = _state.value.copy(
            profileHealth = profiles.associate { profile -> profile.id to ProfileHealth() },
        )
    }

    @Synchronized
    internal fun resetProfileHealthForBridgeEpoch(epoch: Long, profiles: List<AppConfig>): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch) return false
        _state.value = _state.value.copy(
            profileHealth = profiles.associate { profile -> profile.id to ProfileHealth() },
        )
        return true
    }

    @Synchronized
    fun setProfileHealth(profileId: String, health: ProfileHealth) {
        if (profileId.isBlank()) return
        val current = _state.value
        _state.value = current.copy(profileHealth = current.profileHealth + (profileId to sanitizeHealth(health)))
    }

    @Synchronized
    internal fun setProfileHealthForBridgeEpoch(
        epoch: Long,
        profileId: String,
        health: ProfileHealth,
    ): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch || profileId.isBlank()) return false
        val current = _state.value
        _state.value = current.copy(profileHealth = current.profileHealth + (profileId to sanitizeHealth(health)))
        return true
    }

    @Synchronized
    fun removeProfileHealth(profileId: String) {
        if (profileId !in _state.value.profileHealth) return
        _state.value = _state.value.copy(profileHealth = _state.value.profileHealth - profileId)
    }

    @Synchronized
    internal fun removeProfileHealthForBridgeEpoch(epoch: Long, profileId: String): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch) return false
        if (profileId !in _state.value.profileHealth) return true
        _state.value = _state.value.copy(profileHealth = _state.value.profileHealth - profileId)
        return true
    }

    @Synchronized
    fun beginTcping(targetLabel: String, total: Int): Long {
        tcpingRequestId = if (tcpingRequestId == Long.MAX_VALUE) 1L else tcpingRequestId + 1L
        val current = _state.value
        _state.value = current.copy(
            tcping = TcpingProgress(
                requestId = tcpingRequestId,
                targetLabel = targetLabel,
                running = true,
                total = total.coerceAtLeast(0),
            ),
        )
        return tcpingRequestId
    }

    @Synchronized
    fun beginTcpingStep(requestId: Long, index: Int, total: Int, profileName: String) {
        val current = _state.value
        if (current.tcping.requestId != requestId) return
        _state.value = current.copy(
            tcping = current.tcping.copy(
                running = true,
                currentIndex = index,
                total = total,
                currentProfileName = profileName,
                error = "",
            ),
        )
    }

    @Synchronized
    fun completeTcpingStep(requestId: Long, result: TcpingLinkResult) {
        val current = _state.value
        if (current.tcping.requestId != requestId) return
        _state.value = current.copy(
            tcping = current.tcping.copy(
                results = current.tcping.results + result.copy(error = sanitizeErrorText(result.error)),
            ),
        )
    }

    @Synchronized
    fun finishTcping(requestId: Long) {
        val current = _state.value
        if (current.tcping.requestId != requestId) return
        _state.value = current.copy(
            tcping = current.tcping.copy(running = false, currentProfileName = ""),
        )
    }

    @Synchronized
    fun failTcping(requestId: Long, error: String) {
        val current = _state.value
        if (current.tcping.requestId != requestId) return
        _state.value = current.copy(
            tcping = current.tcping.copy(
                running = false,
                currentProfileName = "",
                error = sanitizeErrorText(error),
            ),
        )
    }

    @Synchronized
    fun clearTcping() {
        tcpingRequestId = if (tcpingRequestId == Long.MAX_VALUE) 1L else tcpingRequestId + 1L
        _state.value = _state.value.copy(tcping = TcpingProgress())
    }

    @Synchronized
    fun isCurrentTcping(requestId: Long): Boolean {
        return _state.value.tcping.requestId == requestId && _state.value.tcping.running
    }

    internal fun bridgeSimpleStatus(state: String): String {
        return when (state.lowercase()) {
            "starting" -> "Starting"
            "stopping" -> "Stopping"
            "stopped" -> "Stopped"
            "error" -> "Error"
            "core_ready", "running", "upstream_connecting", "upstream_connected", "degraded", "reconnecting",
            "remote_endpoints_changed",
            "outbound_running", "outbound_stopping", "outbound_stopped", "outbound_error" -> "Running"
            else -> "Unknown"
        }
    }

    private fun bridgeDisplayError(
        runtimeStatus: VpnStatus,
        currentError: String,
        bridgeStatus: String,
        bridgeLastError: String,
        eventState: String = "",
    ): String {
        // A bridge snapshot must never erase a terminal service/lifecycle error.
        if (runtimeStatus == VpnStatus.Error) return currentError
        val safeError = redactSensitiveText(bridgeLastError.take(MAX_STATUS_FIELD_LENGTH)).trim()
        if (
            (bridgeStatus == "Error" || eventState.equals("error", ignoreCase = true)) &&
            safeError.isNotBlank()
        ) {
            return safeError
        }
        val healthyEvent = isExplicitlyHealthyBridgeEventState(eventState)
        return if (bridgeStatus == "Running" && (eventState.isBlank() || healthyEvent)) "" else currentError
    }

    private fun terminalDiagnostics(
        diagnostics: TcptunDiagnostics,
        status: VpnStatus,
        error: String,
    ): TcptunDiagnostics = diagnostics.copy(
        vpnStatus = status.displayName,
        bridgeStatus = status.displayName,
        bridgeEventState = status.displayName.lowercase(),
        bridgeEventReason = if (status == VpnStatus.Error) "SERVICE_ERROR" else "None",
        bridgeEventPhase = if (status == VpnStatus.Error) "Runtime stopped after an error" else "None",
        bridgeListen = "",
        bridgeRemote = "",
        bridgeActiveConnections = 0,
        bridgeClientIps = emptyList(),
        bridgeMuxSources = 0,
        bridgeMuxSessions = 0,
        bridgeMuxStreams = 0,
        bridgeRecoverable = false,
        bridgeLastError = if (status == VpnStatus.Error) error else "",
        bridgeTimestampMs = 0,
        bridgeSessionId = 0,
        bridgeSequence = 0,
        localProxyReachable = false,
        socketProtectEnabled = false,
    )

    private fun sanitizeDiagnostics(diagnostics: TcptunDiagnostics): TcptunDiagnostics = diagnostics.copy(
        bridgeEventReason = redactSensitiveText(diagnostics.bridgeEventReason),
        bridgeEventPhase = redactSensitiveText(diagnostics.bridgeEventPhase),
        bridgeListen = redactSensitiveText(diagnostics.bridgeListen),
        bridgeRemote = redactSensitiveText(diagnostics.bridgeRemote),
        bridgeLastError = redactSensitiveText(diagnostics.bridgeLastError),
        lastRestartReason = redactSensitiveText(diagnostics.lastRestartReason),
    )

    private fun sanitizeHealth(health: ProfileHealth): ProfileHealth = health.copy(
        error = sanitizeErrorText(health.error),
    )

    private fun sanitizeErrorText(value: String): String =
        redactSensitiveText(value.take(MAX_STATUS_FIELD_LENGTH)).trim()

}
