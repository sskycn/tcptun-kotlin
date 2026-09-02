package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigCompatibilityTest {
    @Test
    fun migratesLegacyRealityQuicProfileToCurrentCarrierSchema() {
        assertEquals("2001:db8::1", normalizeStoredServerHost("[2001:db8::1]"))
        assertEquals(
            MigratedCarrierFields(
                tunnelSecurity = "reality",
                carrierMode = "quic",
                carrierUdpMode = "auto",
            ),
            migratedCarrierFields(
                tunnelSecurity = "reality-quic",
                protocol = "native",
                mux = true,
                carrierMode = "quic",
                carrierUdpMode = "",
                legacyMuxSchema = true,
            ),
        )
    }

    @Test
    fun migratesLegacyRealityGroupMuxToAutomaticCarrier() {
        assertEquals(
            MigratedCarrierFields(
                tunnelSecurity = "reality",
                carrierMode = "auto",
                carrierUdpMode = "",
            ),
            migratedCarrierFields(
                tunnelSecurity = "reality",
                protocol = "native",
                mux = true,
                carrierMode = "group",
                carrierUdpMode = "",
                legacyMuxSchema = true,
            ),
        )
    }

    @Test
    fun acceptsNativeRealityWithTcpCarrier() {
        val profile = AppConfig(
            name = "native-tcp",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            sni = "example.com",
            tunnelSecurity = "reality",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1",
            realitySpiderX = "/",
            mux = true,
            carrierMode = "tcp",
        )
        assertNull(profile.validate())
        assertTrue("reality" in AppConfig.SecurityOptions)
        assertEquals(listOf("", "reality"), AppConfig.TunnelSecurityTypes)
    }

    @Test
    fun nativeRealitySupportsIndependentCarrierSelection() {
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
            carrierMode = "auto",
            realityShortId = "a65f93c1",
        )
        assertNull(reality.validate())
        assertNull(reality.copy(carrierMode = "tcp").validate())
        assertNull(reality.copy(carrierMode = "quic", carrierUdpMode = "auto").validate())
    }

    @Test
    fun nativeAutoSupportsAllCarrierPreferencesWithTlsAndReality() {
        val tls = nativeAutoProfile(tls = true)
        val reality = nativeAutoProfile(tls = false).copy(
            tunnelSecurity = "reality",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1",
        )

        listOf(tls, reality).forEach { profile ->
            listOf("", "adaptive", "quic", "tcp").forEach { preference ->
                assertNull("${profile.tunnelSecurity.ifBlank { "tls" }}/$preference", profile.copy(carrierPrefer = preference).validate())
            }
        }
    }

    @Test
    fun carrierPreferenceValidationMatchesCoreBoundary() {
        val profile = nativeAutoProfile(tls = true)

        assertEquals(
            "carrier preference requires automatic carrier mode",
            profile.copy(carrierMode = "tcp", carrierPrefer = "tcp").validate(),
        )
        assertEquals(
            "carrier preference requires automatic carrier mode",
            profile.copy(carrierMode = "quic", carrierPrefer = "quic").validate(),
        )
        assertEquals(
            "mux must be enabled when carrier or mux options are configured",
            profile.copy(mux = false, carrierPrefer = "adaptive").validate(),
        )
        assertEquals(
            "unsupported carrier preference: fastest",
            profile.copy(carrierPrefer = "fastest").validate(),
        )
        assertEquals(
            "automatic carrier requires TLS or reality security",
            profile.copy(tls = false).validate(),
        )
    }

    @Test
    fun oldAutoDefaultsToEmptyPreferenceAndCopiesExactCurrentValues() {
        val oldAuto = nativeAutoProfile(tls = true)
        assertEquals("", oldAuto.carrierPrefer)

        listOf("adaptive", "quic", "tcp").forEach { preference ->
            assertEquals(preference, oldAuto.copy(carrierPrefer = preference).carrierPrefer)
        }
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
    fun removedStructuredProtocolsRemainReadableButAreRejected() {
        listOf("vless", "vmess", "trojan").forEach { protocol ->
            val profile = AppConfig(
                name = protocol,
                serverHost = "edge.example.com",
                serverPort = "443",
                protocol = protocol,
                token = "legacy-credential",
            )

            assertEquals(protocol, profile.protocol)
            assertEquals("legacy-credential", profile.token)
            assertEquals("tcptun-go v0.5.0 no longer supports $protocol", profile.validate())
        }
    }

    @Test
    fun acceptsResumableNativeRealityAutomaticCarrierWithinGoLimits() {
        val profile = resumableRealityProfile()

        assertNull(profile.validate())
    }

    @Test
    fun rejectsIncompatibleOrOutOfRangeResumableMuxSettings() {
        val profile = resumableRealityProfile()

        assertEquals(
            "tcptun-go v0.5.0 no longer supports vless",
            profile.copy(protocol = "vless").validate(),
        )
        assertEquals(
            "mux resume requires reality automatic TCP/QUIC security",
            profile.copy(tunnelSecurity = "").validate(),
        )
        assertEquals(
            "mux resume requires automatic carrier mode",
            profile.copy(carrierMode = "quic").validate(),
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

    @Test
    fun validatesNativeEchClientHelloProtection() {
        val profile = AppConfig(
            name = "ech",
            serverHost = "edge.example.com",
            serverPort = "9443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            echEnabled = true,
            echPublicName = "public.example",
            echPublicKey = "gzFwIcNk5Ez3GIzKErsb8_BLzAvzRyxZlmno-tkYeSY",
            echPorts = "443, 8443",
            mux = true,
            carrierMode = "tcp",
        )

        assertNull(profile.validate())
        assertEquals(listOf(443, 8443), parseEchPorts(profile.echPorts))
        assertEquals(
            "tcptun-go v0.5.0 no longer supports vless",
            profile.copy(protocol = "vless").validate(),
        )
        assertEquals(
            "ECH requires security none",
            profile.copy(tls = true).validate(),
        )
        assertEquals(
            "ECH requires TCP carrier mode",
            profile.copy(carrierMode = "quic").validate(),
        )
        assertEquals(
            "ECH ports must not contain duplicates",
            profile.copy(echPorts = "443,443").validate(),
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
        realityShortId = "a65f93c1",
        mux = true,
        carrierMode = "auto",
        muxResume = true,
        muxResumeTimeoutMillis = 15_000,
        muxResumeBufferSize = 4_194_304,
    )

    private fun nativeAutoProfile(tls: Boolean) = AppConfig(
        name = "native-auto",
        serverHost = "edge.example.com",
        serverPort = "443",
        protocol = "native",
        transport = "raw",
        token = "secret",
        sni = "edge.example.com",
        tls = tls,
        mux = true,
        carrierMode = "auto",
    )
}
