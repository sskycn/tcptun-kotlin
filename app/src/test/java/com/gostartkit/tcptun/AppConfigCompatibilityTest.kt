package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun acceptsNativeRealityTcpSecurity() {
        val profile = AppConfig(
            name = "native-tcp",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            sni = "example.com",
            tunnelSecurity = "reality-tcp",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1",
            realityFingerprint = "chrome",
            realitySpiderX = "/",
            mux = true,
            muxMode = "group",
        )
        assertNull(profile.validate())
        assertTrue("reality-tcp" in AppConfig.SecurityOptions)
        assertTrue("reality-tcp" in AppConfig.TunnelSecurityTypes)
    }

    @Test
    fun nativeRealityAllowsGroupMuxWhileRealityTcpIsTcpOnly() {
        val reality = AppConfig(
            name = "native-auto",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            sni = "example.com",
            tunnelSecurity = "reality",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            mux = true,
            muxMode = "group",
        )
        assertNull(reality.validate())
        assertNull(
            reality.copy(tunnelSecurity = "reality-tcp", realitySpiderX = "/").validate(),
        )
        assertEquals(
            "QUIC mux requires TLS or reality-quic security",
            reality.copy(
                tunnelSecurity = "reality-tcp",
                muxMode = "quic",
                muxUdpMode = "auto",
                tls = false,
            ).validate(),
        )
    }

    @Test
    fun rejectsOversizedProfileFieldsBeforeEncodingOrPersistence() {
        val profile = AppConfig(
            name = "oversized",
            serverHost = "x".repeat(MaxProfileUriLength + 1),
            serverPort = "443",
        )

        assertEquals("profile data is too large", profile.validate())
    }

    @Test
    fun nonNativeProfilesRequireCredentials() {
        listOf("vless", "vmess", "trojan").forEach { protocol ->
            val profile = AppConfig(
                name = protocol,
                serverHost = "edge.example.com",
                serverPort = "443",
                protocol = protocol,
                token = "",
            )

            assertEquals("$protocol credential is required", profile.validate())
        }
    }

    @Test
    fun acceptsResumableNativeRealityGroupMuxWithinGoLimits() {
        val profile = resumableRealityProfile()

        assertNull(profile.validate())
    }

    @Test
    fun rejectsIncompatibleOrOutOfRangeResumableMuxSettings() {
        val profile = resumableRealityProfile()

        assertEquals(
            "mux resume requires native protocol",
            profile.copy(protocol = "vless").validate(),
        )
        assertEquals(
            "mux resume requires reality automatic TCP/QUIC security",
            profile.copy(tunnelSecurity = "reality-tcp").validate(),
        )
        assertEquals(
            "mux resume requires group mux mode",
            profile.copy(muxMode = "quic").validate(),
        )
        assertEquals(
            "mux resume timeout must be between 100 and 300000 milliseconds when set",
            profile.copy(muxResumeTimeoutMillis = 99).validate(),
        )
        assertEquals(
            "mux resume buffer must be between 65536 and 67108864 bytes when set",
            profile.copy(muxResumeBufferSize = 65_535).validate(),
        )
        assertEquals(
            "mux resume must be enabled when resume limits are configured",
            profile.copy(muxResume = false).validate(),
        )
    }

    private fun resumableRealityProfile() = AppConfig(
        name = "resumable",
        serverHost = "edge.example.com",
        serverPort = "443",
        protocol = "native",
        transport = "raw",
        token = "secret",
        sni = "example.com",
        tunnelSecurity = "reality",
        realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
        mux = true,
        muxMode = "group",
        muxResume = true,
        muxResumeTimeoutMillis = 15_000,
        muxResumeBufferSize = 4_194_304,
    )
}
