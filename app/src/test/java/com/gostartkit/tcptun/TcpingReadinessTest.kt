package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpingReadinessTest {
    @Test
    fun automaticUpstreamAddressKeepsOnlyIpv4Host() {
        assertEquals("192.168.1.20", automaticUpstreamIpv4("192.168.1.20:1080"))
        assertEquals("", automaticUpstreamIpv4("proxy.example.com:1080"))
        assertEquals("", automaticUpstreamIpv4("2001:db8::1"))
        assertEquals("", automaticUpstreamIpv4("192.168.1.999:1080"))
    }

    @Test
    fun coreReadyEnablesFirstLazyUpstreamProbeAfterVpnTransactionCompletes() {
        assertTrue(
            hasServerConnection(
                TcptunDiagnostics(
                    vpnStatus = "Running",
                    bridgeEventState = "core_ready",
                ),
            ),
        )
    }

    @Test
    fun coreReadyDoesNotEnableProbeBeforeVpnTransactionCompletes() {
        assertFalse(
            hasServerConnection(
                TcptunDiagnostics(
                    vpnStatus = "Starting",
                    bridgeEventState = "core_ready",
                ),
            ),
        )
    }
}
