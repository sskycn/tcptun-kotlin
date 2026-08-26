package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcptunStatePowerSavingTest {
    @Test
    fun hiddenLogsStayBoundedDeduplicatedAndFlushOnceOnForeground() {
        var visibility: UiVisibilityLease? = null
        try {
            TcptunState.updateDiagnostics { it.copy(powerSavingMode = true) }
            TcptunState.clearLogs()
            repeat(100) { index -> TcptunState.appendLog("background-$index") }
            TcptunState.appendLog("background-99")

            assertTrue(TcptunState.logs.isEmpty())
            visibility = TcptunState.acquireUiVisibility()

            assertEquals(80, TcptunState.logs.size)
            assertEquals("background-20", TcptunState.logs.first())
            assertEquals("background-99", TcptunState.logs.last())
            assertEquals(1, TcptunState.logs.count { it == "background-99" })
        } finally {
            visibility?.close()
            TcptunState.clearLogs()
        }
    }
}
