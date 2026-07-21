package com.tcptun.client

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
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
        assertEquals(setOf("native", "tcptun", "vless", "vmess", "trojan"), SupportedProfileUriSchemes)

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
    fun viewIntentAcceptsSupportedUrisOnly() {
        SupportedProfileUriSchemes.forEach { scheme ->
            val uri = "$scheme://credential@example.com:443"
            assertEquals(uri, profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri))))
        }
        val httpsLink = ProfileDeepLinkCodec.encode("native://token@example.com:443")
        assertEquals(httpsLink, profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(httpsLink))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_SEND, Uri.parse("native://token@example.com:443"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/profile"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://tcptun.com/x/v2#p=YWJj"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://tcptun.com/y/v1#p=YWJj"))))
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://tcptun.com/x/v1#p=YWJj"))))
        val oversized = "native://token@example.com:443#" + "a".repeat(MaxProfileUriLength)
        assertNull(profileUriFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse(oversized))))
    }

    @Test
    fun versionedHttpsLinkRoundTripsExistingProfileUri() {
        val profileUri = "vless://00000000-0000-4000-8000-000000000000@example.com:443" +
            "?security=tls&type=raw#edge"
        val link = ProfileDeepLinkCodec.encode(profileUri)

        assertTrue(link.startsWith("https://tcptun.com/x/v1#p="))
        assertFalse(link.substringAfter("#p=").contains('='))
        assertEquals(profileUri, ProfileDeepLinkCodec.decode(link).getOrThrow())
        val profile = ProfileUriCodec.decode(link).getOrThrow()
        assertEquals("vless", profile.protocol)
        assertEquals("example.com", profile.serverHost)
        assertEquals("edge", profile.name)
    }

    @Test
    fun versionedHttpsLinkRejectsNonCanonicalOrUnsupportedLinks() {
        val link = ProfileDeepLinkCodec.encode("native://token@example.com:443")
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("/x/v1", "/x/v2")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("/x/v1", "/y/v1")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("tcptun.com", "example.com")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode(link.replace("#p=", "?p=")).isFailure)
        assertTrue(ProfileDeepLinkCodec.decode("$link&extra=value").isFailure)
    }

    @Test
    fun legacyTcptunUriPreservesAllGoMuxParameters() {
        val uri = "tcptun://secret@example.com:443" +
            "?v=1&protocol=native&type=raw&network=tcp%2Cudp&path=%2Ftunnel" +
            "&security=tls&sni=edge.example.com&insecure=true&mux=true&mux_mode=group" +
            "&mux_max_sessions=6&mux_max_streams_per_session=256&mux_warm_spares=2#edge"

        val profile = ProfileUriCodec.decode(uri).getOrThrow()
        assertEquals("native", profile.protocol)
        assertEquals("raw", profile.transport)
        assertEquals("edge.example.com", profile.sni)
        assertTrue(profile.tls)
        assertTrue(profile.tlsInsecure)
        assertTrue(profile.mux)
        assertEquals("group", profile.muxMode)
        assertEquals(6, profile.muxMaxSessions)
        assertEquals(256, profile.muxMaxStreamsPerSession)
        assertEquals(2, profile.muxWarmSpare)
        assertNull(profile.validate())

        val bridge = JSONObject(profile.toBridgeJson("127.0.0.1:1080"))
        val mux = bridge.getJSONArray("outbounds").getJSONObject(0).getJSONObject("mux")
        assertEquals("group", mux.getString("mode"))
        assertEquals(6, mux.getInt("max_sessions"))
        assertEquals(256, mux.getInt("max_streams_per_session"))
        assertEquals(2, mux.getInt("warm_spares"))
        val network = bridge.getJSONArray("outbounds").getJSONObject(0).getJSONArray("network")
        assertEquals("tcp", network.getString(0))
        assertEquals("udp", network.getString(1))
    }

    @Test
    fun nativeRealityTcpUriRoundTrips() {
        val uri = "native://tcp-token@edge.example.com:443" +
            "?v=1&type=raw&security=reality-tcp&sni=example.com&fp=chrome" +
            "&pbk=BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY&sid=a65f93c1dbc5d54a" +
            "&spx=%2F&mux=true&mux_mode=group#tcp-reality"

        val profile = ProfileUriCodec.decode(uri).getOrThrow()
        assertEquals("reality-tcp", profile.tunnelSecurity)
        assertEquals("native", profile.protocol)
        assertEquals("raw", profile.transport)
        assertEquals("group", profile.muxMode)
        assertEquals("/", profile.realitySpiderX)
        assertNull(profile.validate())

        val encoded = requireNotNull(ProfileUriCodec.encode(profile))
        assertTrue(encoded.contains("security=reality-tcp"))
        assertTrue(encoded.contains("spx="))

        val security = JSONObject(profile.toBridgeJson("127.0.0.1:1080"))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("security")
        assertEquals("reality-tcp", security.getString("type"))
        assertEquals("/", security.getString("spider_x"))
        assertEquals("example.com", security.getString("server_name"))
    }

    @Test
    fun nativeRealityQuicUriRoundTrips() {
        val uri = "native://quic-token@edge.example.com:443" +
            "?v=1&type=raw&security=reality-quic&sni=example.com&fp=chrome" +
            "&pbk=BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY&sid=a65f93c1dbc5d54a" +
            "&mux=true&mux_mode=quic&mux_udp_mode=auto&mux_max_sessions=4&mux_warm_spares=1#quic"

        val profile = ProfileUriCodec.decode(uri).getOrThrow()
        assertEquals("reality-quic", profile.tunnelSecurity)
        assertEquals("native", profile.protocol)
        assertEquals("raw", profile.transport)
        assertEquals("quic", profile.muxMode)
        assertEquals("auto", profile.muxUdpMode)
        assertEquals("", profile.realitySpiderX)
        assertNull(profile.validate())

        val encoded = requireNotNull(ProfileUriCodec.encode(profile))
        assertTrue(encoded.contains("security=reality-quic"))
        assertTrue(encoded.contains("mux_mode=quic"))
        assertTrue(encoded.contains("mux_udp_mode=auto"))
        assertFalse(encoded.contains("spx="))

        val bridgeMux = JSONObject(profile.toBridgeJson("127.0.0.1:1080"))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("mux")
        assertEquals("auto", bridgeMux.getString("udp_mode"))

        val qrSource = profile.copy(
            muxInitialStreamReceiveWindow = 2 shl 20,
            muxMaxStreamReceiveWindow = 8 shl 20,
            muxInitialConnectionReceiveWindow = 8 shl 20,
            muxMaxConnectionReceiveWindow = 32 shl 20,
        )
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(qrSource))
        assertTrue(qrPayload.startsWith("T3:"))
        val qrProfile = ProfileUriCodec.decode(qrPayload).getOrThrow()
        assertEquals("reality-quic", qrProfile.tunnelSecurity)
        assertEquals(profile.realityPublicKey, qrProfile.realityPublicKey)
        assertEquals(profile.realityShortId, qrProfile.realityShortId)
        assertEquals("chrome", qrProfile.realityFingerprint)
        assertEquals("", qrProfile.realitySpiderX)
        assertEquals("quic", qrProfile.muxMode)
        assertEquals("auto", qrProfile.muxUdpMode)
        assertEquals(qrSource.muxInitialStreamReceiveWindow, qrProfile.muxInitialStreamReceiveWindow)
        assertEquals(qrSource.muxMaxStreamReceiveWindow, qrProfile.muxMaxStreamReceiveWindow)
        assertEquals(qrSource.muxInitialConnectionReceiveWindow, qrProfile.muxInitialConnectionReceiveWindow)
        assertEquals(qrSource.muxMaxConnectionReceiveWindow, qrProfile.muxMaxConnectionReceiveWindow)
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
                transport = if (protocol == "vmess") "ws" else "raw",
                token = "00000000-0000-4000-8000-000000000000",
                tls = true,
                sni = "edge.example.com",
                path = if (protocol == "vmess") "/ray" else "/proxy",
                mux = true,
                muxMode = "group",
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
            assertTrue(
                "$protocol compact payload should use a lower QR version",
                Encoder.encode(qrPayload, ErrorCorrectionLevel.M).version.versionNumber <
                    Encoder.encode(plain, ErrorCorrectionLevel.M).version.versionNumber,
            )

            val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals(profile.serverHost, decoded.serverHost)
            assertEquals(profile.serverPort, decoded.serverPort)
            assertEquals(profile.token, decoded.token)
            assertEquals(profile.sni, decoded.sni)
            assertEquals(profile.transport, decoded.transport)
            assertEquals(profile.mux, decoded.mux)
            assertEquals(profile.muxMode, decoded.muxMode)
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
                realityFingerprint = "chrome",
                realitySpiderX = "/",
                flow = if (protocol == "vless") "xtls-rprx-vision" else "",
                mux = false,
            )
            val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
            assertTrue("$protocol payload", qrPayload.startsWith("T3:"))
            assertTrue(qrPayload.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
            assertTrue(qrPayload.length < requireNotNull(ProfileUriCodec.encode(profile)).length)

            val decoded = ProfileUriCodec.decode(qrPayload).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals("reality", decoded.tunnelSecurity)
            assertEquals("chrome", decoded.realityFingerprint)
            assertEquals("a65f93c1dbc5d54a", decoded.realityShortId)
            assertEquals("/", decoded.realitySpiderX)
            assertEquals(profile.realityPublicKey, decoded.realityPublicKey)
            assertEquals(profile.sni, decoded.sni)
            assertFalse(decoded.mux)
            assertEquals("raw", decoded.transport)
            if (protocol == "vless") {
                assertEquals("xtls-rprx-vision", decoded.flow)
            }
        }
    }

    @Test
    fun compactQrKeepsExplicitNonDefaults() {
        val profile = AppConfig(
            name = "vless-tls",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "vless",
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
        assertEquals("vless", decoded.protocol)
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
            protocol = "vless",
            transport = "raw",
            token = "00000000-0000-4000-8000-000000000000",
            tunnelSecurity = "reality",
            sni = "www.microsoft.com",
            flow = "xtls-rprx-vision",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "abcd1234",
            realityFingerprint = "firefox",
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
        assertEquals(profile.realityFingerprint, decoded.realityFingerprint)
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
                muxMode = "group",
                muxMaxSessions = 4,
                muxMaxStreamsPerSession = 128,
                muxWarmSpare = 1,
            )
            val encoded = ProfileUriCodec.encode(profile)
            assertNotNull("failed to encode $protocol", encoded)
            val decoded = ProfileUriCodec.decode(requireNotNull(encoded)).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals("group", decoded.muxMode)
            assertEquals(4, decoded.muxMaxSessions)
            assertEquals(128, decoded.muxMaxStreamsPerSession)
            assertEquals(1, decoded.muxWarmSpare)
            assertNull(decoded.validate())
        }
    }

    @Test
    fun legacyT2PayloadStillDecodesAndReencodesAsT3() {
        val legacy = "T2:+1REESK007MO4UU1V2TDWL%53+UKDNS6ONP18VCRZCB\$CBECP9EXYC/:52%E1/DXTDJPC -DB\$CBECP9ERZCUPCAZDA8GLB0  C93D:"

        val decoded = ProfileUriCodec.decode(legacy).getOrThrow()
        assertEquals("vless", decoded.protocol)
        assertTrue(requireNotNull(ProfileUriCodec.encodeForQr(decoded)).startsWith("T3:"))
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
            realityFingerprint = "chrome",
            realitySpiderX = "/",
            mux = true,
        )
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(profile))
        assertTrue(qrPayload.isNotBlank())
        assertTrue(qrPayload.startsWith("T3:"))
        val bitmap = generateQrCodeBitmap(qrPayload, 512)
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
            realityFingerprint = "chrome",
            realitySpiderX = "/",
            mux = true,
            muxMode = "group",
        )
        val t3 = requireNotNull(ProfileUriCodec.encodeForQr(outbound))
        assertTrue(t3.startsWith("T3:"))
        val fromT3 = ProfileUriCodec.decode(t3).getOrThrow()
        // Stored the way older Android builds did after URI/T2 import.
        val legacyStored = fromT3.copy(path = "/")
        val qrPayload = requireNotNull(ProfileUriCodec.encodeForQr(legacyStored))
        assertTrue(qrPayload.startsWith("T3:"))
        generateQrCodeBitmap(qrPayload, 512)
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
        assertTrue(requireNotNull(ProfileUriCodec.encodeForQr(profile)).startsWith("T3:"))
    }

    @Test
    fun qrEncodingDoesNotFallBackToALossyProtocolUri() {
        val profile = AppConfig(
            name = "mixed-upstream",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            upstreamProtocol = "mixed",
        )

        // T3 cannot preserve a mixed upstream protocol. Returning null is safer
        // than emitting an authority URI that silently decodes as SOCKS5.
        assertNull(ProfileUriCodec.encodeForQr(profile))
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
