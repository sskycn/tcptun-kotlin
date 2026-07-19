package com.tcptun.client

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
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
    val powerSavingMode: Boolean = false,
    val healthCheckIntervalSeconds: Long = 15,
    val socketProtectEnabled: Boolean = false,
)

data class TcptunRuntimeState(
    val status: String = "Stopped",
    val lastError: String = "",
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
    @Volatile private var uiVisible = false

    val isUiVisible: Boolean
        get() = uiVisible

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    @Synchronized
    fun beginBridgeSession(): Long {
        bridgeEpoch += 1
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

    @Synchronized
    fun setFlowAnalysisApp(packageName: String) {
        val normalized = packageName.trim()
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
        _state.value = current.copy(
            status = value,
            lastError = if (value == "Error") current.lastError else "",
            diagnostics = current.diagnostics.copy(
                vpnStatus = value,
                bridgeClientIps = if (value == "Stopped" || value == "Error") {
                    emptyList()
                } else {
                    current.diagnostics.bridgeClientIps
                },
            ),
            tcping = if (value == "Stopped" || value == "Error") TcpingProgress() else current.tcping,
            profileHealth = if (value == "Stopped" || value == "Error") emptyMap() else current.profileHealth,
        )
    }

    @Synchronized
    fun error(message: String) {
        val current = _state.value
        _state.value = current.copy(
            status = "Error",
            lastError = message,
            diagnostics = current.diagnostics.copy(vpnStatus = "Error", bridgeClientIps = emptyList()),
            tcping = TcpingProgress(),
            profileHealth = emptyMap(),
        )
        appendLog("error: $message")
    }

    @Synchronized
    fun updateDiagnostics(update: (TcptunDiagnostics) -> TcptunDiagnostics) {
        val current = _state.value
        _state.value = current.copy(diagnostics = update(current.diagnostics))
    }

    @Synchronized
    internal fun applyBridgeStatusEvent(epoch: Long, eventJson: String): BridgeStatusEvent? {
        if (epoch != bridgeEpoch) return null
        val event = runCatching {
            val json = JSONObject(eventJson)
            BridgeStatusEvent(
                sessionId = json.optLong("session_id", 0),
                sequence = json.optLong("sequence", 0),
                state = json.optString("state"),
                reason = json.optString("reason"),
                phase = json.optString("phase"),
                listen = json.optString("listen"),
                remote = json.optString("remote"),
                activeConnections = json.optInt("active_connections", 0),
                clientIps = normalizeClientIps(
                    buildList {
                        json.optJSONArray("client_ips")?.let { values ->
                            for (index in 0 until values.length()) add(values.optString(index))
                        }
                    },
                ),
                muxSources = json.optInt("mux_sources", 0),
                muxSessions = json.optInt("mux_sessions", 0),
                muxStreams = json.optInt("mux_streams", 0),
                recoverable = json.optBoolean("recoverable", false),
                lastError = json.optString("last_error"),
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
        _state.value = current.copy(
            lastError = event.lastError.takeIf {
                event.state.equals("error", ignoreCase = true) && it.isNotBlank()
            } ?: current.lastError,
            diagnostics = current.diagnostics.copy(
                bridgeStatus = bridgeSimpleStatus(event.state),
                bridgeEventState = event.state.ifBlank { "Unknown" },
                bridgeEventReason = event.reason.ifBlank { "None" },
                bridgeEventPhase = event.phase.ifBlank { "None" },
                bridgeListen = event.listen,
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
        val clean = line.trim()
        if (clean.isEmpty()) return
        Log.i(LOG_TAG, clean)
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
        _state.value = current.copy(profileStateRevision = current.profileStateRevision + 1)
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
    fun setProfileHealth(profileId: String, health: ProfileHealth) {
        if (profileId.isBlank()) return
        val current = _state.value
        _state.value = current.copy(profileHealth = current.profileHealth + (profileId to health))
    }

    @Synchronized
    fun removeProfileHealth(profileId: String) {
        if (profileId !in _state.value.profileHealth) return
        _state.value = _state.value.copy(profileHealth = _state.value.profileHealth - profileId)
    }

    @Synchronized
    fun beginTcping(targetLabel: String, total: Int): Long {
        tcpingRequestId += 1
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
        tcpingRequestId += 1
        _state.value = _state.value.copy(tcping = TcpingProgress())
    }

    @Synchronized
    fun isCurrentTcping(requestId: Long): Boolean {
        return _state.value.tcping.requestId == requestId && _state.value.tcping.running
    }

    private fun bridgeSimpleStatus(state: String): String {
        return when (state.lowercase()) {
            "starting" -> "Starting"
            "stopping" -> "Stopping"
            "stopped" -> "Stopped"
            "error" -> "Error"
            "core_ready", "running", "upstream_connecting", "upstream_connected", "degraded", "reconnecting",
            "outbound_running", "outbound_stopping", "outbound_stopped", "outbound_error" -> "Running"
            else -> "Unknown"
        }
    }
}
