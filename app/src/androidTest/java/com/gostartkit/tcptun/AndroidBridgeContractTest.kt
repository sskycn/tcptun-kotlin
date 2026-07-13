package com.tcptun.client

import android.content.Intent
import android.net.Uri
import androidbridge.Androidbridge
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.king.wechat.qrcode.WeChatQRCodeDetector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class AndroidBridgeContractTest {
    @Test
    fun appUsesCurrentApplicationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tcptun.client.debug", context.packageName)
    }

    @Test
    fun currentBridgeExposesOptionalApplicationIdentityProvider() {
        val engine = Androidbridge.newEngine()
        try {
            engine.setAppIdentityProvider(null)
        } finally {
            engine.close()
        }
    }

    @Test
    fun currentBridgeExposesPerServiceEngineLifecycle() {
        val engine = Androidbridge.newEngine()
        try {
            assertEquals("Stopped", engine.status())
            assertEquals("stopped", JSONObject(engine.statusJSON()).getString("state"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun currentBridgeValidatesWithoutStartingRuntime() {
        Androidbridge.validateConfig(
            """{
                "inbounds":[{"tag":"local","type":"mixed","listen":"127.0.0.1","port":18080,"network":["tcp"],"outbound":"proxy"}],
                "outbounds":[{"tag":"proxy","type":"native","server":"203.0.113.10","port":9443,"token":"secret","network":["tcp"],"transport":{"type":"raw"}}],
                "route":{"default_outbound":"proxy"}
            }""".trimIndent(),
        )
    }

    @Test
    fun currentBridgeRejectsInvalidRuntimeConfig() {
        val result = runCatching {
            Androidbridge.validateConfig(
                """{
                    "inbounds":[{"tag":"local","type":"mixed","listen":"127.0.0.1","port":18080,"network":["tcp"],"outbound":"missing"}],
                    "outbounds":[],
                    "route":{"default_outbound":"missing"}
                }""".trimIndent(),
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun stateFlowDropsStaleEngineAndSequenceEvents() {
        val firstEpoch = TcptunState.beginBridgeSession()
        val accepted = TcptunState.applyBridgeStatusEvent(
            firstEpoch,
            """{"session_id":1,"sequence":2,"state":"starting","phase":"accepted"}""",
        )
        val staleSequence = TcptunState.applyBridgeStatusEvent(
            firstEpoch,
            """{"session_id":1,"sequence":1,"state":"error","phase":"stale"}""",
        )
        val secondEpoch = TcptunState.beginBridgeSession()
        val staleEpoch = TcptunState.applyBridgeStatusEvent(
            firstEpoch,
            """{"session_id":2,"sequence":3,"state":"error","phase":"old engine"}""",
        )

        assertEquals("accepted", accepted?.phase)
        assertEquals(null, staleSequence)
        assertEquals(null, staleEpoch)
        assertEquals(0, TcptunState.state.value.diagnostics.bridgeSequence)
        assertTrue(secondEpoch > firstEpoch)
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
    fun generatedConfigIncludesEnabledManagedRouteRules() {
        val config = JSONObject(
            AppConfig(
                serverHost = "192.0.2.1",
                serverPort = "443",
                token = "route-test",
                protocol = "native",
            ).toBridgeJson(
                localListenAddr = "127.0.0.1:18080",
                managedRouteRules = listOf(
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = ".example.com",
                        outbound = ManagedRouteOutbound.Direct,
                    ),
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.IPCidr,
                        value = "203.0.113.0/24",
                        outbound = ManagedRouteOutbound.Proxy,
                        enabled = false,
                    ),
                ),
            ),
        )

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))
        assertEquals("direct", rules.getJSONObject(1).getString("outbound"))
        assertEquals("example.com", rules.getJSONObject(1).getJSONArray("domain_suffixes").getString(0))
        assertEquals(2, rules.length())

        assertEngineStarts(config.toString())
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
        val expectedUri = ProfileDeepLinkCodec.encode(requireNotNull(ProfileUriCodec.encode(profile)))
        val intent = createProfileShareIntent(profile)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(expectedUri, intent.getStringExtra(Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        assertEquals(null, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun weChatScannerDecodesGeneratedProfileQrCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = AppConfig(
            name = "wechat-qr-test",
            serverHost = "192.0.2.1",
            serverPort = "9443",
            token = "wechat-qr-secret",
            protocol = "native",
        )
        val expectedUri = ProfileDeepLinkCodec.encode(requireNotNull(ProfileUriCodec.encode(profile)))
        val logo = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher))
        val bitmap = generateQrCodeBitmap(expectedUri, 768, logo)

        WeChatQRCodeDetector.init(context)
        assertEquals(expectedUri, WeChatQRCodeDetector.detectAndDecode(bitmap).firstOrNull())
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
            probeTimeout = "1250ms",
            failureThreshold = 3,
            positiveTtl = "45m",
            negativeTtl = "15m",
        )

        val root = JSONObject(config)
        assertFalse(root.has("mode"))
        assertTrue(root.has("inbounds"))
        assertTrue(root.has("outbounds"))
        val autoOutbound = root.getJSONArray("outbounds").getJSONObject(2)
        assertEquals("1250ms", autoOutbound.getString("probe_timeout"))
        assertEquals(3, autoOutbound.getInt("failure_threshold"))
        assertEquals("45m", autoOutbound.getString("positive_ttl"))
        assertEquals("15m", autoOutbound.getString("negative_ttl"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))
        assertEquals("auto", rules.getJSONObject(1).getString("outbound"))

        assertEngineStarts(config)
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

        assertEngineStarts(config)
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

        assertEngineStarts(prepared.toString())
    }

    private fun assertEngineStarts(config: String) {
        val engine = Androidbridge.newEngine()
        try {
            engine.start(withAvailableInboundPorts(config))
            assertTrue(engine.status() in setOf("Starting", "Running"))
        } finally {
            engine.close()
        }
    }

    private fun withAvailableInboundPorts(config: String): String {
        val root = JSONObject(config)
        val inbounds = root.getJSONArray("inbounds")
        val reservations = List(inbounds.length()) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
        try {
            reservations.forEachIndexed { index, reservation ->
                val inbound = inbounds.getJSONObject(index)
                inbound.remove("address")
                inbound.remove("listen_addresses")
                inbound.put("listen", "127.0.0.1")
                inbound.put("port", reservation.localPort)
            }
            return root.toString()
        } finally {
            reservations.forEach(ServerSocket::close)
        }
    }
}
