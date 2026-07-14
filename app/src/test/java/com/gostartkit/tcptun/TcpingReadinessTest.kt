package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpingReadinessTest {
    @Test
    fun runningVpnWithMultipleActiveProfilesEnablesTcping() {
        assertTrue(canStartTcping(status = "Running", activeProfileCount = 2))
    }

    @Test
    fun tcpingRequiresRunningVpnAndAnActiveProfile() {
        assertFalse(canStartTcping(status = "Starting", activeProfileCount = 2))
        assertFalse(canStartTcping(status = "Running", activeProfileCount = 0))
    }
}
