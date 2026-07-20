package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpingReadinessTest {
    @Test
    fun runningVpnWithReadyConnectionsEnablesTcping() {
        assertTrue(
            canStartTcping(
                status = "Running",
                activeProfileCount = 2,
                connectionsReady = true,
            ),
        )
    }

    @Test
    fun tcpingRequiresRunningVpnAndAnActiveProfile() {
        assertFalse(canStartTcping(status = "Starting", activeProfileCount = 2, connectionsReady = false))
        assertFalse(canStartTcping(status = "Running", activeProfileCount = 0, connectionsReady = true))
    }

    @Test
    fun tcpingDisabledWhileConnectionsAreStillStarting() {
        assertFalse(
            canStartTcping(
                status = "Running",
                activeProfileCount = 2,
                connectionsReady = false,
            ),
        )
    }

    @Test
    fun onlyLatestQueuedConnectionUpdateCanRestoreReadiness() {
        val tracker = ConnectionUpdateTracker()
        val first = tracker.begin()
        val second = tracker.begin()
        var restored = false

        assertFalse(tracker.isLatest(first))
        assertTrue(tracker.isLatest(second))
        assertFalse(tracker.runIfLatest(first) { restored = true })
        assertFalse(restored)
        assertTrue(tracker.runIfLatest(second) { restored = true })
        assertTrue(restored)
    }
}
