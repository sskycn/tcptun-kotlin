package com.tcptun.client

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

data class TcptunDiagnostics(
    val vpnStatus: String = "Stopped",
    val bridgeStatus: String = "Unknown",
    val underlyingNetwork: String = "None",
    val localProxyReachable: Boolean = false,
    val lastRestartReason: String = "None",
    val mtu: Int = 1400,
    val udpEnabled: Boolean = true,
    val socketProtectEnabled: Boolean = false,
)

object TcptunState {
    private const val MAX_LOGS = 80

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
    fun appendLog(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
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
}
