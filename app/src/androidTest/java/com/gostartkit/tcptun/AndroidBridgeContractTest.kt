package com.tcptun.client

import android.content.Intent
import android.net.Uri
import androidbridge.Androidbridge
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBridgeContractTest {
    @After
    fun stopBridge() {
        runCatching { Androidbridge.stop() }
    }

    @Test
    fun appUsesCurrentApplicationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tcptun.client.debug", context.packageName)
    }

    @Test
    fun currentBridgeExposesOptionalApplicationIdentityProvider() {
        Androidbridge.setAppIdentityProvider(null)
    }

    @Test
    fun underlyingNetworkSelectionPrefersValidationBeforeTransport() {
        val validatedCellular = underlyingNetworkScore(
            validated = true,
            ethernet = false,
            wifi = false,
            cellular = true,
        )
        val unvalidatedWifi = underlyingNetworkScore(
            validated = false,
            ethernet = false,
            wifi = true,
            cellular = false,
        )
        val validatedWifi = underlyingNetworkScore(
            validated = true,
            ethernet = false,
            wifi = true,
            cellular = false,
        )

        assertTrue(validatedCellular > unvalidatedWifi)
        assertTrue(validatedWifi > validatedCellular)
    }

    @Test
    fun profileShareContainsOnlyUriText() {
        val profile = AppConfig(
            name = "share-test",
            serverHost = "192.0.2.1",
            serverPort = "9443",
            token = "share-secret",
            protocol = "native",
        )
        val expectedUri = ProfileUriCodec.encode(profile)
        val intent = createProfileShareIntent(profile)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(expectedUri, intent.getStringExtra(Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        assertEquals(null, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun fullConfigCannotBeShared() {
        val raw = """
            {
              "outbounds": [{"tag": "direct", "type": "direct"}],
              "route": {"default_outbound": "direct", "rules": []}
            }
        """.trimIndent()
        val profile = AppConfig(name = "json", rawConfigJson = raw)
        assertEquals(null, ProfileUriCodec.encode(profile))
        assertTrue(runCatching { createProfileShareIntent(profile) }.isFailure)
    }

    @Test
    fun generatedConfigStartsCurrentGoBridge() {
        val config = AppConfig(
            serverHost = "192.0.2.1",
            serverPort = "9443",
            token = "android-contract-test",
            protocol = "native",
        ).toBridgeJson(
            localListenAddr = "127.0.0.1:18080",
            directFirst = true,
        )

        val root = JSONObject(config)
        assertFalse(root.has("mode"))
        assertTrue(root.has("inbounds"))
        assertTrue(root.has("outbounds"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))
        assertEquals("auto", rules.getJSONObject(1).getString("outbound"))

        Androidbridge.start(config)
        assertTrue(Androidbridge.status() in setOf("Starting", "Running"))
    }

    @Test
    fun strictConfigMapsProtocolCredentialsAndExternalRouting() {
        val uuid = "00000000-0000-4000-8000-000000000000"
        val localOnly = JSONObject(
            AppConfig(
                serverHost = "2001:db8::1",
                serverPort = "443",
                token = uuid,
                protocol = "vless",
            ).toBridgeJson(
                localListenAddr = "0.0.0.0:18081",
                routeExternalSources = false,
            ),
        )
        val localOnlyOutbounds = localOnly.getJSONArray("outbounds")
        assertEquals(uuid, localOnlyOutbounds.getJSONObject(0).getString("uuid"))
        assertEquals("2001:db8::1", localOnlyOutbounds.getJSONObject(0).getString("server"))
        assertEquals(2, localOnlyOutbounds.length())

        val routedExternal = JSONObject(
            AppConfig(
                serverHost = "192.0.2.1",
                serverPort = "443",
                token = "trojan-password",
                protocol = "trojan",
            ).toBridgeJson(
                localListenAddr = "0.0.0.0:18082",
                routeExternalSources = true,
                directFirst = true,
            ),
        )
        val routedOutbounds = routedExternal.getJSONArray("outbounds")
        assertEquals("trojan-password", routedOutbounds.getJSONObject(0).getString("password"))
        assertEquals(3, routedOutbounds.length())
        val routedRules = routedExternal.getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy", routedRules.getJSONObject(0).getString("outbound"))
        assertEquals("auto", routedRules.getJSONObject(1).getString("outbound"))
    }

    @Test
    fun fullConfigPreservesTopologyAndInjectsAndroidVpnInbound() {
        val raw = """
            {
              "log": {"level": "warn"},
              "inbounds": [
                {"tag": "existing", "type": "socks5", "listen": "127.0.0.1", "port": 18091, "outbound": "direct"}
              ],
              "outbounds": [
                {"tag": "direct", "type": "direct"},
                {"tag": "blocked", "type": "blackhole"}
              ],
              "route": {
                "default_outbound": "direct",
                "rules": [{"domain_suffixes": ["example.invalid"], "outbound": "blocked"}]
              },
              "dns": {"servers": ["1.1.1.1"], "strategy": "prefer_ipv4"},
              "discovery": {}
            }
        """.trimIndent()
        val config = AppConfig(name = "full", rawConfigJson = raw)
            .toBridgeJson(
                localListenAddr = "127.0.0.1:18090",
                socks5Username = "android",
                socks5Password = "secret",
            )

        val root = JSONObject(config)
        val inbounds = root.getJSONArray("inbounds")
        assertEquals("android-vpn", inbounds.getJSONObject(0).getString("tag"))
        assertEquals("direct", inbounds.getJSONObject(0).getString("outbound"))
        assertEquals("existing", inbounds.getJSONObject(1).getString("tag"))
        assertEquals("blocked", root.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound"))
        assertEquals("prefer_ipv4", root.getJSONObject("dns").getString("strategy"))

        Androidbridge.start(config)
        assertTrue(Androidbridge.status() in setOf("Starting", "Running"))
    }

    @Test
    fun tcptunGoConfigImportKeepsOnlyOutboundsAndRoute() {
        val raw = """
            {
              "log": {"level": "info"},
              "inbounds": [
                {"tag": "local", "type": "mixed", "listen": "127.0.0.1", "port": 1080,
                 "network": ["tcp", "udp"], "outbound": "proxy"}
              ],
              "outbounds": [
                {"tag": "proxy", "type": "native", "server": "192.0.2.1", "port": 9443,
                 "token": "android-import-test", "transport": {"type": "raw"}, "mux": {"enabled": true}}
              ],
              "route": {
                "default_outbound": "proxy",
                "rules": [{"inbound": ["local"], "network": ["tcp"], "outbound": "proxy"}]
              },
              "dns": {},
              "discovery": {}
            }
        """.trimIndent()

        val imported = ProfileUriCodec.decode(raw).getOrThrow()
        assertTrue(imported.rawConfigJson.isNotBlank())
        val stored = JSONObject(imported.rawConfigJson)
        assertFalse(stored.has("inbounds"))
        assertFalse(stored.has("log"))
        assertFalse(stored.has("dns"))
        assertFalse(stored.has("discovery"))
        assertTrue(stored.has("outbounds"))
        assertTrue(stored.has("route"))
        assertEquals(
            "android-vpn",
            stored.getJSONObject("route").getJSONArray("rules")
                .getJSONObject(0).getJSONArray("inbound").getString(0),
        )
        val prepared = JSONObject(imported.toBridgeJson(localListenAddr = "127.0.0.1:1080"))
        val inbounds = prepared.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        assertEquals("android-vpn", inbounds.getJSONObject(0).getString("tag"))
        val ruleInbound = prepared.getJSONObject("route").getJSONArray("rules")
            .getJSONObject(0).getJSONArray("inbound")
        assertEquals("android-vpn", ruleInbound.getString(0))

        Androidbridge.start(prepared.toString())
        assertTrue(Androidbridge.status() in setOf("Starting", "Running"))
    }
}
