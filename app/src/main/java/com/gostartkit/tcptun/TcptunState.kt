package com.tcptun.client

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

object TcptunState {
    val status = mutableStateOf("Stopped")
    val lastError = mutableStateOf("")
    val logs = mutableStateListOf<String>()

    @Synchronized
    fun setStatus(value: String) {
        status.value = value
        if (value != "Error") {
            lastError.value = ""
        }
    }

    @Synchronized
    fun error(message: String) {
        status.value = "Error"
        lastError.value = message
        appendLog("error: $message")
    }

    @Synchronized
    fun appendLog(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        logs.add(clean)
        while (logs.size > 200) {
            logs.removeAt(0)
        }
    }

    @Synchronized
    fun clearLogs() {
        logs.clear()
    }
}
