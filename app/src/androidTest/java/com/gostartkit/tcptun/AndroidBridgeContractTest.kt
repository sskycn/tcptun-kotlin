package com.tcptun.client

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
    fun generatedConfigStartsCurrentGoBridge() {
        val config = AppConfig(
            serverHost = "192.0.2.1",
            serverPort = "9443",
            token = "android-contract-test",
            protocol = "native",
        ).toBridgeJson(
            localListenAddr = "127.0.0.1:18080",
        )

        val root = JSONObject(config)
        assertFalse(root.has("mode"))
        assertTrue(root.has("inbounds"))
        assertTrue(root.has("outbounds"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals("auto", rules.getJSONObject(0).getString("outbound"))

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
            ),
        )
        val routedOutbounds = routedExternal.getJSONArray("outbounds")
        assertEquals("trojan-password", routedOutbounds.getJSONObject(0).getString("password"))
        assertEquals(3, routedOutbounds.length())
        assertEquals("auto", routedExternal.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound"))
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
}
