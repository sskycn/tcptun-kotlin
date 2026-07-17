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
        assertTrue(profile.udp)
        assertTrue(profile.mux)
        assertEquals("group", profile.muxMode)
        assertEquals(6, profile.muxMaxSessions)
        assertEquals(256, profile.muxMaxStreamsPerSession)
        assertEquals(2, profile.muxWarmSpare)
        assertEquals("tcp,udp", profile.tunnelNetwork)
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
                tunnelNetwork = "tcp,udp",
                udp = true,
            )
            val encoded = ProfileUriCodec.encode(profile)
            assertNotNull("failed to encode $protocol", encoded)
            val decoded = ProfileUriCodec.decode(requireNotNull(encoded)).getOrThrow()
            assertEquals(protocol, decoded.protocol)
            assertEquals("group", decoded.muxMode)
            assertEquals(4, decoded.muxMaxSessions)
            assertEquals(128, decoded.muxMaxStreamsPerSession)
            assertEquals(1, decoded.muxWarmSpare)
            assertEquals("tcp,udp", decoded.tunnelNetwork)
            assertTrue(decoded.udp)
            assertNull(decoded.validate())
        }
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
