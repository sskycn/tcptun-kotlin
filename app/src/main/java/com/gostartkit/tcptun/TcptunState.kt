package com.tcptun.client

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

data class TcptunDiagnostics(
    val vpnStatus: String = "Stopped",
    val bridgeStatus: String = "Unknown",
    val bridgeEventState: String = "Unknown",
    val bridgeEventPhase: String = "None",
    val bridgeListen: String = "",
    val bridgeRemote: String = "",
    val bridgeActiveConnections: Int = 0,
    val bridgeLastError: String = "",
    val bridgeTimestampMs: Long = 0,
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

object TcptunState {
    private const val MAX_LOGS = 80
    private const val LOG_TAG = "TcpTun"

    val status = mutableStateOf("Stopped")
    val lastError = mutableStateOf("")
    val diagnostics = mutableStateOf(TcptunDiagnostics())
    val logs = mutableStateListOf<String>()

    @Synchronized
    fun setStatus(value: String) {
        status.value = value
        diagnostics.value = diagnostics.value.copy(vpnStatus = value)
        if (value != "Error") {
            lastError.value = ""
        }
    }

    @Synchronized
    fun error(message: String) {
        status.value = "Error"
        lastError.value = message
        diagnostics.value = diagnostics.value.copy(vpnStatus = "Error")
        appendLog("error: $message")
    }

    @Synchronized
    fun updateDiagnostics(update: (TcptunDiagnostics) -> TcptunDiagnostics) {
        diagnostics.value = update(diagnostics.value)
    }

    @Synchronized
    fun applyBridgeStatusEvent(eventJson: String) {
        val event = runCatching {
            val json = JSONObject(eventJson)
            BridgeStatusEvent(
                state = json.optString("state"),
                phase = json.optString("phase"),
                listen = json.optString("listen"),
                remote = json.optString("remote"),
                activeConnections = json.optInt("active_connections", 0),
                lastError = json.optString("last_error"),
                timestampMs = json.optLong("timestamp_ms", 0),
            )
        }.getOrElse { err ->
            appendLog("tcptun status parse failed: ${err.message}")
            return
        }
        val bridgeStatus = bridgeSimpleStatus(event.state)
        diagnostics.value = diagnostics.value.copy(
            bridgeStatus = bridgeStatus,
            bridgeEventState = event.state.ifBlank { "Unknown" },
            bridgeEventPhase = event.phase.ifBlank { "None" },
            bridgeListen = event.listen,
            bridgeRemote = event.remote,
            bridgeActiveConnections = event.activeConnections,
            bridgeLastError = event.lastError,
            bridgeTimestampMs = event.timestampMs,
        )
        if (event.state.equals("error", ignoreCase = true) && event.lastError.isNotBlank()) {
            lastError.value = event.lastError
        }
        if (event.shouldLog()) {
            appendLog(event.logLine())
        }
    }

    @Synchronized
    fun appendLog(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        Log.i(LOG_TAG, clean)
        if (logs.lastOrNull() == clean) return
        logs.add(clean)
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
    }

    @Synchronized
    fun clearLogs() {
        logs.clear()
    }

    private fun bridgeSimpleStatus(state: String): String {
        return when (state.lowercase()) {
            "starting" -> "Starting"
            "stopping" -> "Stopping"
            "stopped" -> "Stopped"
            "error" -> "Error"
            "listening", "running", "upstream_connecting", "upstream_connected", "degraded", "reconnecting" -> "Running"
            else -> "Unknown"
        }
    }
}

private data class BridgeStatusEvent(
    val state: String,
    val phase: String,
    val listen: String,
    val remote: String,
    val activeConnections: Int,
    val lastError: String,
    val timestampMs: Long,
) {
    fun shouldLog(): Boolean {
        return state.lowercase() in setOf("listening", "running", "degraded", "reconnecting", "error", "stopped")
    }

    fun logLine(): String {
        val details = listOfNotNull(
            phase.takeIf { it.isNotBlank() },
            remote.takeIf { it.isNotBlank() }?.let { "remote=$it" },
            lastError.takeIf { it.isNotBlank() }?.let { "error=$it" },
        ).joinToString(" ")
        return "tcptun status: ${state.ifBlank { "unknown" }}${details.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
    }
}
