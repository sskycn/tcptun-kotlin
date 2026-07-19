package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigCompatibilityTest {
    @Test
    fun migratesLegacyRealityQuicProfileToCurrentGoMuxDefaults() {
        assertEquals("2001:db8::1", normalizeStoredServerHost("[2001:db8::1]"))
        assertEquals(
            "auto",
            migratedMuxUdpMode(
                tunnelSecurity = "reality-quic",
                muxMode = "quic",
                muxUdpMode = "",
            ),
        )
    }

    @Test
    fun doesNotAddQuicUdpModeToLegacyGroupMux() {
        assertEquals(
            "",
            migratedMuxUdpMode(
                tunnelSecurity = "reality",
                muxMode = "group",
                muxUdpMode = "",
            ),
        )
    }
}
