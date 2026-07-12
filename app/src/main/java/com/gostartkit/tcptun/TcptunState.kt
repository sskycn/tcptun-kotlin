package com.tcptun.client

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class TcptunDiagnostics(
    val vpnStatus: String = "Stopped",
    val bridgeStatus: String = "Unknown",
    val bridgeEventState: String = "Unknown",
    val bridgeEventReason: String = "None",
    val bridgeEventPhase: String = "None",
    val bridgeListen: String = "",
    val bridgeRemote: String = "",
    val bridgeActiveConnections: Int = 0,
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
    val udpEnabled: Boolean = true,
    val powerSavingMode: Boolean = false,
    val healthCheckIntervalSeconds: Long = 15,
    val socketProtectEnabled: Boolean = false,
)

data class TcptunRuntimeState(
    val status: String = "Stopped",
    val lastError: String = "",
    val diagnostics: TcptunDiagnostics = TcptunDiagnostics(),
    val logs: List<String> = emptyList(),
)

internal data class BridgeStatusEvent(
    val sessionId: Long,
    val sequence: Long,
    val state: String,
    val reason: String,
    val phase: String,
    val listen: String,
    val remote: String,
    val activeConnections: Int,
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

    @Synchronized
    fun beginBridgeSession(): Long {
        bridgeEpoch += 1
        bridgeSessionId = -1L
        bridgeSequence = -1L
        val current = _state.value
        _state.value = current.copy(
            diagnostics = current.diagnostics.copy(
                bridgeStatus = "Starting",
                bridgeEventState = "starting",
                bridgeEventReason = "START_REQUESTED",
                bridgeEventPhase = "Core runtime is starting",
                bridgeRecoverable = false,
                bridgeLastError = "",
                bridgeSessionId = 0,
                bridgeSequence = 0,
            ),
        )
        return bridgeEpoch
    }

    @Synchronized
    fun setStatus(value: String) {
        val current = _state.value
        _state.value = current.copy(
            status = value,
            lastError = if (value == "Error") current.lastError else "",
            diagnostics = current.diagnostics.copy(vpnStatus = value),
        )
    }

    @Synchronized
    fun error(message: String) {
        val current = _state.value
        _state.value = current.copy(
            status = "Error",
            lastError = message,
            diagnostics = current.diagnostics.copy(vpnStatus = "Error"),
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

    private fun bridgeSimpleStatus(state: String): String {
        return when (state.lowercase()) {
            "starting" -> "Starting"
            "stopping" -> "Stopping"
            "stopped" -> "Stopped"
            "error" -> "Error"
            "core_ready", "running", "upstream_connecting", "upstream_connected", "degraded", "reconnecting" -> "Running"
            else -> "Unknown"
        }
    }
}
