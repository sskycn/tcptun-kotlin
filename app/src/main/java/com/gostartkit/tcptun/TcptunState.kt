package com.tcptun.client

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

data class TcptunDiagnostics(
    val vpnStatus: String = "Stopped",
    val bridgeStatus: String = "Unknown",
    val bridgeEventState: String = "Unknown",
    val bridgeEventReason: String = "None",
    val bridgeEventPhase: String = "None",
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
    val status: String = "Stopped",
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
    val flowEvents: List<FlowAnalysisEvent> = emptyList(),
    val flowDroppedEvents: Long = 0,
    val profileStateRevision: Long = 0,
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

internal data class BridgeStatusEvent(
    val sessionId: Long,
    val sequence: Long,
    val state: String,
    val reason: String,
    val phase: String,
    val listen: String,
    val remote: String,
    val activeConnections: Int,
    val clientIps: List<String>,
    val muxSources: Int,
    val muxSessions: Int,
    val muxStreams: Int,
    val recoverable: Boolean,
    val lastError: String,
    val timestampMs: Long,
) {
    fun shouldLog(): Boolean {
        return state.lowercase() in setOf(
            "core_ready",
            "running",
            "degraded",
            "reconnecting",
            "remote_endpoints_changed",
            "error",
            "stopped",
        )
    }

    fun logLine(): String {
        val details = listOfNotNull(
            reason.takeIf { it.isNotBlank() },
            phase.takeIf { it.isNotBlank() },
            remote.takeIf { it.isNotBlank() }?.let { "remote=$it" },
            lastError.takeIf { it.isNotBlank() }?.let { "error=$it" },
        ).joinToString(" ")
        return "tcptun status: ${state.ifBlank { "unknown" }}${details.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
    }
}

object TcptunState {
    private const val MAX_LOGS = 80
    private const val MAX_LOG_LENGTH = 4_096
    private const val MAX_STATUS_EVENT_JSON_LENGTH = 64 * 1024
    private const val MAX_STATUS_FIELD_LENGTH = 4 * 1024
    private const val MAX_FLOW_EVENTS = 256
    private const val LOG_TAG = "TcpTun"

    private val _state = MutableStateFlow(TcptunRuntimeState())
    val state: StateFlow<TcptunRuntimeState> = _state.asStateFlow()

    val status: String get() = state.value.status
    val lastError: String get() = state.value.lastError
    val diagnostics: TcptunDiagnostics get() = state.value.diagnostics
    val logs: List<String> get() = state.value.logs

    private var bridgeEpoch = 0L
    private var bridgeSessionId = -1L
    private var bridgeSequence = -1L
    private var flowSessionId = -1L
    private var flowSequence = -1L
    private var tcpingRequestId = 0L
    private val uiVisibility = UiVisibilityTracker()

    val isUiVisible: Boolean
        get() = uiVisibility.isVisible

    internal fun acquireUiVisibility(): UiVisibilityLease = uiVisibility.acquire()

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
        _state.value = current.copy(
            flowAnalysisApp = normalized,
            flowEvents = emptyList(),
            flowDroppedEvents = 0,
        )
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
        _state.value = current.copy(
            flowEvents = (current.flowEvents + event).takeLast(MAX_FLOW_EVENTS),
            flowDroppedEvents = maxOf(current.flowDroppedEvents, event.droppedEvents),
        )
        return event
    }

    @Synchronized
    fun clearFlowEvents() {
        val current = _state.value
        _state.value = current.copy(flowEvents = emptyList(), flowDroppedEvents = 0)
    }

    @Synchronized
    fun setStatus(value: String) {
        val current = _state.value
        val terminal = value == "Stopped" || value == "Error"
        val transitioning = value == "Starting" || value == "Stopping"
        _state.value = current.copy(
            status = value,
            // Bring-up / teardown is never TCPing-ready; only an explicit ready
            // mark after a successful start/update re-enables it.
            connectionsReady = if (terminal || transitioning) false else current.connectionsReady,
            lastError = if (value == "Error") current.lastError else "",
            diagnostics = if (terminal) {
                terminalDiagnostics(current.diagnostics, value, current.lastError)
            } else {
                current.diagnostics.copy(vpnStatus = value)
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
    internal fun errorIfStatus(expectedStatus: String, message: String): Boolean {
        if (_state.value.status != expectedStatus) return false
        error(message)
        return true
    }

    @Synchronized
    internal fun restoreCommandStateIfStatus(
        expectedStatus: String,
        restoredStatus: String,
        restoredConnectionsReady: Boolean,
        restoredLastError: String = "",
    ): Boolean {
        val current = _state.value
        if (current.status != expectedStatus) return false
        _state.value = current.copy(
            status = restoredStatus,
            connectionsReady = restoredConnectionsReady,
            lastError = restoredLastError,
            diagnostics = current.diagnostics.copy(vpnStatus = restoredStatus),
        )
        return true
    }

    @Synchronized
    internal fun restoreConnectionsReadyIfStatus(
        expectedStatus: String,
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
        val safeMessage = message.take(MAX_STATUS_FIELD_LENGTH).trim().ifBlank { "Unknown error" }
        val current = _state.value
        _state.value = current.copy(
            status = "Error",
            connectionsReady = false,
            lastError = safeMessage,
            diagnostics = terminalDiagnostics(current.diagnostics, "Error", safeMessage),
            tcping = TcpingProgress(),
            profileHealth = emptyMap(),
        )
        appendLog("error: $safeMessage")
    }

    @Synchronized
    fun updateDiagnostics(update: (TcptunDiagnostics) -> TcptunDiagnostics) {
        val current = _state.value
        _state.value = current.copy(diagnostics = update(current.diagnostics))
    }

    @Synchronized
    internal fun updateDiagnosticsForBridgeEpoch(
        epoch: Long,
        update: (TcptunDiagnostics) -> TcptunDiagnostics,
    ): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch) return false
        val current = _state.value
        _state.value = current.copy(diagnostics = update(current.diagnostics))
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
        val updatedDiagnostics = update(current.diagnostics).copy(
            bridgeStatus = bridgeStatus,
            bridgeSessionId = safeSessionId,
            bridgeSequence = safeSequence,
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
            val json = JSONObject(eventJson)
            BridgeStatusEvent(
                sessionId = json.optLong("session_id", 0),
                sequence = json.optLong("sequence", 0),
                state = json.optStatusString("state"),
                reason = json.optStatusString("reason"),
                phase = json.optStatusString("phase"),
                listen = json.optStatusString("listen"),
                remote = json.optStatusString("remote"),
                activeConnections = json.optInt("active_connections", 0).coerceAtLeast(0),
                clientIps = normalizeClientIps(
                    buildList {
                        json.optJSONArray("client_ips")?.let { values ->
                            for (index in 0 until minOf(values.length(), MAX_CLIENT_IP_CANDIDATES)) {
                                add(values.optString(index))
                            }
                        }
                    },
                ),
                muxSources = json.optInt("mux_sources", 0).coerceAtLeast(0),
                muxSessions = json.optInt("mux_sessions", 0).coerceAtLeast(0),
                muxStreams = json.optInt("mux_streams", 0).coerceAtLeast(0),
                recoverable = json.optBoolean("recoverable", false),
                lastError = json.optStatusString("last_error"),
                timestampMs = json.optLong("timestamp_ms", 0),
            )
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
            diagnostics = current.diagnostics.copy(
                bridgeStatus = simpleBridgeStatus,
                bridgeEventState = event.state.ifBlank { "Unknown" },
                bridgeEventReason = event.reason.ifBlank { "None" },
                bridgeEventPhase = event.phase.ifBlank { "None" },
                bridgeListen = event.listen,
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
        )
        if (event.shouldLog()) appendLog(event.logLine())
        return event
    }

    @Synchronized
    fun appendLog(line: String) {
        val clean = line.take(MAX_LOG_LENGTH).trim()
        if (clean.isEmpty()) return
        // Keep in-app log history for later inspection; avoid logcat I/O while hanging
        // in the background with the UI closed.
        if (isUiVisible) {
            Log.i(LOG_TAG, clean)
        }
        val current = _state.value
        if (current.logs.lastOrNull() == clean) return
        _state.value = current.copy(logs = (current.logs + clean).takeLast(MAX_LOGS))
    }

    @Synchronized
    fun clearLogs() {
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
        _state.value = current.copy(profileHealth = current.profileHealth + (profileId to health))
    }

    @Synchronized
    internal fun setProfileHealthForBridgeEpoch(
        epoch: Long,
        profileId: String,
        health: ProfileHealth,
    ): Boolean {
        if (epoch <= 0L || epoch != bridgeEpoch || profileId.isBlank()) return false
        val current = _state.value
        _state.value = current.copy(profileHealth = current.profileHealth + (profileId to health))
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
            tcping = current.tcping.copy(results = current.tcping.results + result),
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
                error = error.trim(),
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
        runtimeStatus: String,
        currentError: String,
        bridgeStatus: String,
        bridgeLastError: String,
        eventState: String = "",
    ): String {
        // A bridge snapshot must never erase a terminal service/lifecycle error.
        if (runtimeStatus == "Error") return currentError
        val safeError = bridgeLastError.take(MAX_STATUS_FIELD_LENGTH).trim()
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
        status: String,
        error: String,
    ): TcptunDiagnostics = diagnostics.copy(
        vpnStatus = status,
        bridgeStatus = status,
        bridgeEventState = status.lowercase(),
        bridgeEventReason = if (status == "Error") "SERVICE_ERROR" else "None",
        bridgeEventPhase = if (status == "Error") "Runtime stopped after an error" else "None",
        bridgeListen = "",
        bridgeRemote = "",
        bridgeActiveConnections = 0,
        bridgeClientIps = emptyList(),
        bridgeMuxSources = 0,
        bridgeMuxSessions = 0,
        bridgeMuxStreams = 0,
        bridgeRecoverable = false,
        bridgeLastError = if (status == "Error") error else "",
        bridgeTimestampMs = 0,
        bridgeSessionId = 0,
        bridgeSequence = 0,
        localProxyReachable = false,
        socketProtectEnabled = false,
    )

    private fun JSONObject.optStatusString(name: String): String {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return ""
        if (value !is String) return ""
        return value.take(MAX_STATUS_FIELD_LENGTH).trim()
    }
}
