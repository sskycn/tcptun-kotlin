package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpingReadinessTest {
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
