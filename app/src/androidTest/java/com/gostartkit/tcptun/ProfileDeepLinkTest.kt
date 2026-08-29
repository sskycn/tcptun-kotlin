package com.tcptun.client

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileDeepLinkTest {
    @Test
    fun manifestHandlesEveryTcptunGoUriScheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(setOf("native"), SupportedProfileUriSchemes)

        SupportedProfileUriSchemes.forEach { scheme ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://credential@example.com:443"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName)
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("manifest does not handle $scheme://", matches.any { it.activityInfo.name == MainActivity::class.java.name })
        }
    }

    @Test
    fun manifestHandlesVersionedHttpsProfileLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = ProfileDeepLinkCodec.encode("native://credential@example.com:443")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
    }

    @Test
    fun manifestDoesNotHandleOtherHttpsPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("/v2", "/x/v1").forEach { path ->
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://x.tcptun.com$path#p=YWJj"),
            )
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName)
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("manifest unexpectedly handles $path", matches.none { it.activityInfo.name == MainActivity::class.java.name })
        }
    }

    @Test
    fun viewIntentAcceptsSupportedUrisOnly() {
        SupportedProfileUriSchemes.forEach { scheme ->
            val uri = "$scheme://credential@example.com:443"
            assertEquals(uri, profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri))))
        }
        val httpsLink = ProfileDeepLinkCodec.encode("native://token@example.com:443")
        assertEquals(httpsLink, profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(httpsLink))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_SEND, Uri.parse("native://token@example.com:443"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/profile"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("tcptun://credential@example.com:443"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.tcptun.com/v2#p=YWJj"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.tcptun.com/x/v1#p=YWJj"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://x.tcptun.com/v1#p=YWJj"))))
        val oversized = "native://token@example.com:443#" + "a".repeat(MaxProfileUriLength)
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(oversized))))
    }

    @Test
    fun versionedHttpsLinkRoundTripsExistingProfileUri() {
        val profileUri = "native://native-token@example.com:443" +
            "?security=tls&type=raw#edge"
        val link = ProfileDeepLinkCodec.encode(profileUri)

        assertTrue(link.startsWith("https://x.tcptun.com/v1#p="))
        assertFalse(link.substringAfter("#p=").contains('='))
        assertEquals(profileUri, ProfileDeepLinkCodec.decode(link).getOrThrow())
        val profile = ProfileUriCodec.decode(link).getOrThrow()
        assertEquals("native", profile.protocol)
        assertEquals("example.com", profile.serverHost)
        assertEquals("edge", profile.name)
    }

    @Test
    fun removedProtocolUrisFailWithoutConvertingCredentialsToNative() {
        RemovedTunnelProtocols.forEach { protocol ->
            val result = ProfileUriCodec.decode("$protocol://legacy-credential@example.com:443")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no longer supports $protocol"))
        }
    }

    @Test
    fun versionedHttpsLinkRejectsNonCanonicalOrUnsupportedLinks() {
        val link = ProfileDeepLinkCodec.encode("native://token@example.com:443")
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("/v1", "/v2")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("/v1", "/x/v1")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("x.tcptun.com", "example.com")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("#p=", "?p=")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode("$link&extra=value").isFailure)
    }

    @Test
    fun oversizedExternalProfilePayloadsAreRejectedBeforeParsing() {
        val oversizedUri = "native://token@example.com:443#" + "x".repeat(MaxProfileUriLength)
        val oversizedJson = "{" + " ".repeat(MaxProfileImportLength) + "}"

        assertTrue(ProfileUriCodec.decode(oversizedUri).isFailure)
        assertTrue(ProfileUriCodec.decode(oversizedJson).isFailure)
    }

    @Test
    fun invalidStoredProfileCannotThrowDuringShareabilityCheck() {
        val invalid = AppConfig(
            name = "invalid",
            serverHost = "example.com",
            serverPort = "443",
            protocol = "native",
            transport = "unsupported",
        )

        assertNull(ProfileUriCodec.encode(invalid))
    }

    @Test
    fun legacyTcptunUriIsRejected() {
        val uri = "tcptun://secret@example.com:443" +
            "?v=1&protocol=native&type=raw&network=tcp%2Cudp&path=%2Ftunnel" +
            "&security=tls&sni=edge.example.com&insecure=true&mux=true&mux_mode=group" +
            "&mux_max_sessions=6&mux_max_streams_per_session=256&mux_warm_spares=2#edge"

        assertTrue(ProfileUriCodec.decode(uri).isFailure)
    }

    @Test
    fun nativeResumableMuxUriRoundTripsCurrentGoFields() {
        val uri = "native://secret@edge.example.com:443" +
            "?v=1&type=raw&security=reality&sni=example.com&fp=chrome" +
            "&pbk=BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY" +
            "&sid=a65f93c1dbc5d54a&mux=true&carrier_mode=auto&mux_resume=true" +
            "&mux_resume_timeout=17s&mux_resume_buffer_size=2097152#resumable"

        val profile = ProfileUriCodec.decode(uri).getOrThrow()

        assertTrue(profile.muxResume)
        assertEquals(17_000, profile.muxResumeTimeoutMillis)
        assertEquals(2_097_152, profile.muxResumeBufferSize)
        assertNull(profile.validate())
        assertNull(ProfileUriCodec.encodeForQr(profile))

        val encoded = requireNotNull(ProfileUriCodec.encode(profile))
        assertTrue(encoded.contains("mux_resume=true"))
        assertTrue(encoded.contains("mux_resume_timeout=17000ms"))
        assertTrue(encoded.contains("mux_resume_buffer_size=2097152"))

        val restored = ProfileUriCodec.decode(encoded).getOrThrow()
        assertTrue(restored.muxResume)
        assertEquals(profile.muxResumeTimeoutMillis, restored.muxResumeTimeoutMillis)
        assertEquals(profile.muxResumeBufferSize, restored.muxResumeBufferSize)
    }

    @Test
    fun nativeUriAndT3RoundTripAllAutoCarrierPreferencesForTlsAndReality() {
        val tls = AppConfig(
            name = "tls-auto",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            sni = "edge.example.com",
            tls = true,
            mux = true,
            carrierMode = "auto",
            carrierUdpMode = "auto",
        )
        val reality = tls.copy(
            name = "reality-auto",
            tls = false,
            tunnelSecurity = "reality",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1dbc5d54a",
        )

        listOf(tls, reality).forEach { source ->
            listOf("adaptive", "quic", "tcp").forEach { preference ->
                val profile = source.copy(carrierPrefer = preference)
                assertNull(profile.validate())

                val uri = requireNotNull(ProfileUriCodec.encode(profile))
                assertTrue(uri.contains("carrier_mode=auto"))
                assertTrue(uri.contains("carrier_prefer=$preference"))
                assertEquals(preference, ProfileUriCodec.decode(uri).getOrThrow().carrierPrefer)

                val t3 = requireNotNull(ProfileUriCodec.encodeForQr(profile))
                assertTrue(t3.startsWith("T3:"))
                val decoded = ProfileUriCodec.decode(t3).getOrThrow()
                assertEquals("auto", decoded.carrierMode)
                assertEquals(preference, decoded.carrierPrefer)
                assertEquals(profile.tls, decoded.tls)
                assertEquals(profile.tunnelSecurity, decoded.tunnelSecurity)
                assertNull(decoded.validate())
            }
        }
    }

    @Test
    fun nativeUriRejectsUnknownCarrierPreferenceAliases() {
        listOf("fastest", "prefer_quic", "prefer_tcp").forEach { preference ->
            val result = ProfileUriCodec.decode(
                "native://secret@edge.example.com:443" +
                    "?v=1&type=raw&security=tls&sni=edge.example.com&mux=true" +
                    "&carrier_mode=auto&carrier_prefer=$preference",
            )
            assertTrue(preference, result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unsupported carrier preference"))
        }
    }

    @Test
    fun legacyRealityTcpUriMigratesToRealityWithTcpCarrier() {
        val uri = "native://tcp-token@edge.example.com:443" +
            "?v=1&type=raw&security=reality-tcp&sni=example.com&fp=chrome" +
            "&pbk=BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY&sid=a65f93c1dbc5d54a" +
            "&spx=%2F&mux=true&mux_mode=group#tcp-reality"

        val profile = ProfileUriCodec.decode(uri).getOrThrow()
        assertEquals("reality", profile.tunnelSecurity)
        assertEquals("native", profile.protocol)
        assertEquals("raw", profile.transport)
        assertEquals("tcp", profile.carrierMode)
        assertEquals("/", profile.realitySpiderX)
        assertNull(profile.validate())

        val encoded = requireNotNull(ProfileUriCodec.encode(profile))
        assertTrue(encoded.contains("security=reality"))
        assertTrue(encoded.contains("carrier_mode=tcp"))
        assertTrue(encoded.contains("spx="))

        val security = JSONObject(profile.toBridgeJson("127.0.0.1:1080"))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("security")
        assertEquals("reality", security.getString("type"))
        assertEquals("/", security.getString("spider_x"))
        assertEquals("example.com", security.getString("server_name"))
    }

    @Test
    fun legacyRealityQuicUriMigratesToRealityWithQuicCarrier() {
        val uri = "native://quic-token@edge.example.com:443" +
            "?v=1&type=raw&security=reality-quic&sni=example.com&fp=chrome" +
            "&pbk=BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY&sid=a65f93c1dbc5d54a" +
            "&mux=true&mux_mode=quic&mux_udp_mode=auto&mux_max_sessions=4&mux_warm_spares=1#quic"

        val profile = ProfileUriCodec.decode(uri).getOrThrow()
        assertEquals("reality", profile.tunnelSecurity)
        assertEquals("native", profile.protocol)
        assertEquals("raw", profile.transport)
        assertEquals("quic", profile.carrierMode)
        assertEquals("auto", profile.carrierUdpMode)
        assertEquals("", profile.realitySpiderX)
        assertNull(profile.validate())

        val encoded = requireNotNull(ProfileUriCodec.encode(profile))
        assertTrue(encoded.contains("security=reality"))
        assertTrue(encoded.contains("carrier_mode=quic"))
        assertTrue(encoded.contains("carrier_udp_mode=auto"))
        assertFalse(encoded.contains("spx="))

        val bridgeCarrier = JSONObject(profile.toBridgeJson("127.0.0.1:1080"))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("carrier")
        assertEquals("auto", bridgeCarrier.getString("udp_mode"))

        val qrSource = profile.copy(
            carrierInitialStreamReceiveWindow = 2 shl 20,
            carrierMaxStreamReceiveWindow = 8 shl 20,
            carrierInitialConnectionReceiveWindow = 8 shl 20,
            carrierMaxConnectionReceiveWindow = 32 shl 20,
        )
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(qrSource))
        assertTrue(qrPayload.startsWith("T3:"))
        val qrProfile = ProfileUriCodec.decode(qrPayload).getOrThrow()
        assertEquals("reality", qrProfile.tunnelSecurity)
        assertEquals(profile.realityPublicKey, qrProfile.realityPublicKey)
        assertEquals(profile.realityShortId, qrProfile.realityShortId)
        assertEquals("", qrProfile.realitySpiderX)
        assertEquals("quic", qrProfile.carrierMode)
        assertEquals("auto", qrProfile.carrierUdpMode)
        assertEquals(qrSource.carrierInitialStreamReceiveWindow, qrProfile.carrierInitialStreamReceiveWindow)
        assertEquals(qrSource.carrierMaxStreamReceiveWindow, qrProfile.carrierMaxStreamReceiveWindow)
        assertEquals(qrSource.carrierInitialConnectionReceiveWindow, qrProfile.carrierInitialConnectionReceiveWindow)
        assertEquals(qrSource.carrierMaxConnectionReceiveWindow, qrProfile.carrierMaxConnectionReceiveWindow)
        assertNull(qrProfile.validate())
    }

    @Test
    fun compactQrPayloadRoundTripsAdvancedFieldsAndStaysShorter() {
        AppConfig.Protocols.forEach { protocol ->
            val profile = AppConfig(
                id = "source-$protocol",
                name = "$protocol compact edge",
                serverHost = "edge.example.com",
                serverPort = "443",
                protocol = protocol,
                transport = "raw",
                token = "00000000-0000-4000-8000-000000000000",
                tls = true,
                sni = "edge.example.com",
                path = "/proxy",
                mux = true,
                carrierMode = "tcp",
                muxMaxSessions = 4,
                muxMaxStreamsPerSession = 128,
                muxWarmSpare = 1,
            )
            val plain = requireNotNull(ProfileUriCodec.encode(profile))
            val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
            assertTrue("$protocol should use binary QR payload", qrPayload.startsWith("T3:"))
            assertTrue(qrPayload.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
            assertTrue(
                "$protocol compact payload should be shorter than plain URI",
                qrPayload.length < plain.length,
            )

            val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals(profile.serverHost, decoded.serverHost)
            assertEquals(profile.serverPort, decoded.serverPort)
            assertEquals(profile.token, decoded.token)
            assertEquals(profile.sni, decoded.sni)
            assertEquals(profile.transport, decoded.transport)
            assertEquals(profile.mux, decoded.mux)
            // T3 omits the generated TCP carrier default for non-REALITY
            // profiles, so compare the effective mode rather than its presence.
            assertEquals(profile.carrierMode, decoded.carrierMode.ifBlank { "tcp" })
            assertEquals(profile.muxMaxSessions, decoded.muxMaxSessions)
            assertEquals(profile.muxMaxStreamsPerSession, decoded.muxMaxStreamsPerSession)
            assertEquals(profile.muxWarmSpare, decoded.muxWarmSpare)
            assertEquals(profile.name, decoded.name)
            assertNull(decoded.validate())
        }
    }

    @Test
    fun compactQrOmitsTcptunConfigGeneratedDefaults() {
        // Mirrors fixed fields from `tcptun config <protocol>` client outbounds.
        AppConfig.Protocols.forEach { protocol ->
            val profile = AppConfig(
                name = "$protocol-reality",
                serverHost = "edge.example.com",
                serverPort = "9443",
                protocol = protocol,
                transport = "raw",
                token = "00000000-0000-4000-8000-000000000000",
                tunnelSecurity = "reality",
                sni = "example.com",
                realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
                realityShortId = "a65f93c1dbc5d54a",
                realitySpiderX = "/",
                flow = "xtls-rprx-vision",
                mux = false,
            )
            val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
            assertTrue("$protocol payload", qrPayload.startsWith("T3:"))
            assertTrue(qrPayload.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
            assertTrue(qrPayload.length < requireNotNull(ProfileUriCodec.encode(profile)).length)

            val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals("reality", decoded.tunnelSecurity)
            assertEquals("a65f93c1dbc5d54a", decoded.realityShortId)
            assertEquals("/", decoded.realitySpiderX)
            assertEquals(profile.realityPublicKey, decoded.realityPublicKey)
            assertEquals(profile.sni, decoded.sni)
            assertFalse(decoded.mux)
            assertEquals("raw", decoded.transport)
            assertEquals("xtls-rprx-vision", decoded.flow)
        }
    }

    @Test
    fun compactQrKeepsExplicitNonDefaults() {
        val profile = AppConfig(
            name = "native-tls",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "ws",
            token = "00000000-0000-4000-8000-000000000000",
            tls = true,
            sni = "edge.example.com",
            path = "/tunnel",
            mux = true,
        )
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
        assertTrue(qrPayload.startsWith("T3:"))

        val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
        assertEquals("native", decoded.protocol)
        assertEquals("", decoded.tunnelSecurity)
        assertTrue(decoded.tls)
        assertEquals("ws", decoded.transport)
        assertEquals("/tunnel", decoded.path)
        assertTrue(decoded.mux)
    }

    @Test
    fun compactQrPayloadRoundTripsRealityAndEscapedName() {
        val profile = AppConfig(
            name = "edge|prod #1",
            serverHost = "www.microsoft.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "00000000-0000-4000-8000-000000000000",
            tunnelSecurity = "reality",
            sni = "www.microsoft.com",
            flow = "xtls-rprx-vision",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "abcd1234",
            realitySpiderX = "/crawl",
            mux = false,
            tlsInsecure = false,
        )
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
        assertTrue(qrPayload.startsWith("T3:"))
        assertTrue(qrPayload.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })

        val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
        assertEquals(profile.name, decoded.name)
        assertEquals("reality", decoded.tunnelSecurity)
        assertEquals(profile.realityPublicKey, decoded.realityPublicKey)
        assertEquals(profile.realityShortId, decoded.realityShortId)
        assertEquals(profile.realitySpiderX, decoded.realitySpiderX)
        assertEquals(profile.flow, decoded.flow)
        assertFalse(decoded.mux)
    }

    @Test
    fun everyGoProtocolUriRoundTripsAdvancedFields() {
        AppConfig.Protocols.forEach { protocol ->
            val profile = AppConfig(
                id = "source-$protocol",
                name = "$protocol edge",
                serverHost = "example.com",
                serverPort = "443",
                protocol = protocol,
                transport = "raw",
                token = "00000000-0000-4000-8000-000000000000",
                tls = true,
                sni = "edge.example.com",
                mux = true,
                carrierMode = "tcp",
                muxMaxSessions = 4,
                muxMaxStreamsPerSession = 128,
                muxWarmSpare = 1,
            )
            val encoded = ProfileUriCodec.encode(profile)
            assertNotNull("failed to encode $protocol", encoded)
            val decoded = ProfileUriCodec.decode(requireNotNull(encoded)).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals("tcp", decoded.carrierMode)
            assertEquals(4, decoded.muxMaxSessions)
            assertEquals(128, decoded.muxMaxStreamsPerSession)
            assertEquals(1, decoded.muxWarmSpare)
            assertNull(decoded.validate())
        }
    }

    @Test
    fun legacyT2RemovedProtocolPayloadIsRejected() {
        val legacy = "T2:+1REESK007MO4UU1V2TDWL%53+UKDNS6ONP18VCRZCB\$CBECP9EXYC/:52%E1/DXTDJPC -DB\$CBECP9ERZCUPCAZDA8GLB0  C93D:"

        assertTrue(ProfileUriCodec.decode(legacy).isFailure)
    }

    @Test
    fun currentBridgeStillDecodesNativeT2Golden() {
        val nativeT2 = "T2:*FMG:K-50KFEOEDQX5%3E1\$CTB0  C93D.%5\$9FQ\$DTVD\$J1\$9FQ\$DTVD+%5+3E400I1LN*4*M93RR\$ZJE627A9UBW7DH2K4-GGTLEFQLFM604JG 4L1LPUIBZRH/QZM0OK2IEC/EDDZCZKEAEC-ED3EFKFEOED:"

        val decoded = ProfileUriCodec.decode(nativeT2).getOrThrow()

        assertEquals("native", decoded.protocol)
        assertEquals("edge.example.com", decoded.serverHost)
        assertEquals("quic", decoded.carrierMode)
        assertEquals("reality", decoded.tunnelSecurity)
    }

    @Test
    fun nativeQrRoundTripsIpv4Ipv6AndHostnameEndpoints() {
        listOf("192.0.2.10", "2001:db8::10", "edge.example.com").forEach { host ->
            val source = AppConfig(
                name = host,
                serverHost = host,
                serverPort = "443",
                token = "native-token",
                tls = true,
                sni = "edge.example.com",
            )

            val decoded = ProfileUriCodec.decode(requireNotNull(ProfileUriCodec.encodeForQr(source))).getOrThrow()

            assertEquals(host, decoded.serverHost)
            assertEquals("native", decoded.protocol)
            assertEquals("native-token", decoded.token)
        }
    }

    @Test
    fun encodeForQrDoesNotThrowOnCustomRawTransportPath() {
        // Legacy T2-era / URI imports often stored SpiderX as path ("/").
        // Compact T3 rejects non-default raw paths; QR encoding must still work.
        val profile = AppConfig(
            name = "raw-path-reality",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            path = "/",
            tunnelSecurity = "reality",
            sni = "example.com",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1dbc5d54a",
            realitySpiderX = "/",
            mux = true,
        )
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
        assertTrue(qrPayload.isNotBlank())
        assertTrue(qrPayload.startsWith("T3:"))
        val bitmap = decodeQrCodeBitmap(requireNotNull(ProfileUriCodec.encodeQrCode(profile)))
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
        assertEquals(profile.serverHost, decoded.serverHost)
        assertEquals(profile.token, decoded.token)
        assertEquals("reality", decoded.tunnelSecurity)
        assertEquals("/proxy", decoded.path)
    }

    @Test
    fun legacyT2RealityProfileCanBeShownAsQr() {
        // Mirrors the crash path: decode old T2, pollute raw path as older clients
        // did with SpiderX="/", then open the QR dialog encoder.
        val outbound = AppConfig(
            name = "edge",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            path = "/proxy",
            tunnelSecurity = "reality",
            sni = "example.com",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1dbc5d54a",
            realitySpiderX = "/",
            mux = true,
            carrierMode = "auto",
        )
        val t3 = requireNotNull(ProfileUriCodec.encodeForQr(outbound))
        assertTrue(t3.startsWith("T3:"))
        val fromT3 = ProfileUriCodec.decode(t3).getOrThrow()
        // Stored the way older Android builds did after URI/T2 import.
        val legacyStored = fromT3.copy(path = "/")
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(legacyStored))
        assertTrue(qrPayload.startsWith("T3:"))
        decodeQrCodeBitmap(requireNotNull(ProfileUriCodec.encodeQrCode(legacyStored)))
        val roundTrip = ProfileUriCodec.decode(qrPayload).getOrThrow()
        assertEquals("reality", roundTrip.tunnelSecurity)
        assertEquals("/", roundTrip.realitySpiderX)
        assertEquals("/proxy", roundTrip.path)
    }

    @Test
    fun realityUriImportDoesNotCopySpiderXIntoRawPath() {
        val uri = "native://secret@edge.example.com:443" +
            "?v=1&type=raw&security=reality&sni=example.com" +
            "&pbk=BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY&sid=a65f93c1dbc5d54a" +
            "&fp=chrome&spx=/#edge"
        val profile = ProfileUriCodec.decode(uri).getOrThrow()
        assertEquals("/proxy", profile.path)
        assertEquals("/", profile.realitySpiderX)
        val exported = requireNotNull(ProfileUriCodec.encode(profile))
        assertFalse(exported.contains("fp="))
        assertTrue(requireNotNull(ProfileUriCodec.encodeForQr(profile)).startsWith("T3:"))
    }

    @Test
    fun compactQrPreservesMixedUpstreamWithCurrentGoCodec() {
        val profile = AppConfig(
            name = "mixed-upstream",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            upstreamProtocol = "mixed",
        )

        val encoded = requireNotNull(ProfileUriCodec.encodeForQr(profile))
        val decoded = ProfileUriCodec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("T3:"))
        assertEquals("mixed", decoded.upstreamProtocol)
    }

    @Test
    fun connectionIdentityIgnoresProfileIdAndDisplayName() {
        val first = AppConfig(
            id = "one",
            name = "first",
            serverHost = "example.com",
            serverPort = "443",
            token = "secret",
        )
        val renamed = first.copy(id = "two", name = "renamed")
        val differentPool = first.copy(id = "three", muxMaxSessions = 8)

        assertEquals(profileConnectionIdentity(first), profileConnectionIdentity(renamed))
        assertFalse(profileConnectionIdentity(first) == profileConnectionIdentity(differentPool))
    }

    @Test
    fun fullJsonConnectionIdentityIsCanonicalAndNonNull() {
        val first = AppConfig(
            id = "one",
            name = "first",
            rawConfigJson = """
                {
                  "outbounds": [{"tag": "native", "type": "native", "token": "secret"}],
                  "route": {"default_outbound": "native", "rules": []}
                }
            """.trimIndent(),
        )
        val reformattedAndRenamed = first.copy(
            id = "two",
            name = "renamed",
            rawConfigJson = """{"route":{"rules":[],"default_outbound":"native"},"outbounds":[{"token":"secret","type":"native","tag":"native"}]}""",
        )
        val different = first.copy(
            id = "three",
            rawConfigJson = first.rawConfigJson.replace("secret", "other-secret"),
        )

        assertNotNull(profileConnectionIdentity(first))
        assertEquals(profileConnectionIdentity(first), profileConnectionIdentity(reformattedAndRenamed))
        assertFalse(profileConnectionIdentity(first) == profileConnectionIdentity(different))
    }
}
