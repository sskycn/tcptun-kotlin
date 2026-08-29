package com.tcptun.client

import android.content.Intent
import android.net.Uri
import androidbridge.Androidbridge
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AndroidBridgeContractTest {
    @Test
    fun appUsesCurrentApplicationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tcptun.client.debug", context.packageName)
    }

    @Test
    fun currentBridgeReportsVersionedCoreIdentity() {
        assertEquals("v0.4.0", Androidbridge.coreVersion())
        assertTrue(Androidbridge.coreBuildID().matches(Regex("[0-9a-f]{12,40}(?:-dirty)?")))
    }

    @Test
    fun reflectionBridgeResolvesTheCompleteEngineApiAtFirstUse() {
        val bridge = ReflectionTcptunBridge()
        try {
            assertEquals("Stopped", bridge.status())
        } finally {
            bridge.close()
        }
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
    fun currentBridgeExposesFlowAnalysisContract() {
        val engine = Androidbridge.newEngine()
        try {
            engine.javaClass.getMethod("setFlowAnalysisApp", String::class.java)
            engine.javaClass.getMethod("flowAnalysisApp")
            engine.javaClass.getMethod("setFlowCallback", Class.forName("androidbridge.FlowCallback"))
            engine.setFlowAnalysisApp("com.example.target")
            assertEquals("com.example.target", engine.flowAnalysisApp())
            engine.setFlowCallback(null)
            assertTrue(runCatching { engine.setFlowAnalysisApp("bad package") }.isFailure)
        } finally {
            engine.close()
        }
    }

    @Test
    fun currentBridgeExposesPerServiceEngineLifecycle() {
        val engine = Androidbridge.newEngine()
        try {
            engine.javaClass.getMethod("startOutbound", String::class.java)
            engine.javaClass.getMethod("configure", String::class.java)
            engine.javaClass.getMethod(
                "startConfiguredSessionWithDisabledOutbounds",
                String::class.java,
            )
            engine.javaClass.getMethod(
                "stopOutbound",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            engine.javaClass.getMethod(
                "switchOutbound",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            engine.javaClass.getMethod(
                "probeOutbound",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            engine.javaClass.getMethod(
                "probeOutboundHealth",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            engine.javaClass.getMethod("outboundsStatusJSON")
            engine.javaClass.getMethod("registerEvent", String::class.java)
            engine.javaClass.getMethod("unregisterEvent", String::class.java)
            engine.javaClass.getMethod("abort")
            assertEquals("Stopped", engine.status())
            assertEquals("stopped", JSONObject(engine.statusJSON()).getString("state"))
            assertEquals(0L, engine.sessionID())
            assertTrue(runCatching { engine.waitStopped(1, 1) }.isFailure)
        } finally {
            engine.close()
        }
    }

    @Test
    fun currentBridgeAcceptsOptionalStatusEventRegistration() {
        val engine = Androidbridge.newEngine()
        try {
            TcptunBridgeEvents.DefaultRegistered.forEach { event ->
                engine.registerEvent(event)
            }
            assertTrue(runCatching { engine.registerEvent("UNKNOWN_EVENT") }.isFailure)
            TcptunBridgeEvents.DefaultRegistered.forEach { event ->
                engine.unregisterEvent(event)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun currentBridgeValidatesWithoutStartingRuntime() {
        Androidbridge.validateConfig(
            """{
                "inbounds":[{"tag":"local","type":"mixed","address":["127.0.0.1:18080"],"network":["tcp"]}],
                "outbounds":[{"tag":"proxy","type":"native","address":["203.0.113.10:9443"],"token":"secret","network":["tcp"],"transport":{"type":"raw"}}],
                "route":{"default_outbound":"proxy"}
            }""".trimIndent(),
        )
    }

    @Test
    fun legacyStoredRealityFingerprintIsDiscardedFromCurrentModelAndRuntime() {
        val legacy = JSONObject()
            .put("name", "legacy reality")
            .put("serverHost", "edge.example.com")
            .put("serverPort", "443")
            .put("protocol", "native")
            .put("token", "native-secret")
            .put("sni", "example.com")
            .put("tunnelSecurity", "reality")
            .put("realityPublicKey", "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY")
            .put("realityShortId", "a65f93c1")
            .put("realityFingerprint", "chrome")
        val profile = AppConfig.fromJson(legacy)

        assertFalse(profile.toJson().has("realityFingerprint"))
        val security = JSONObject(profile.toBridgeJson("127.0.0.1:1080"))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("security")
        assertFalse(security.has("fingerprint"))
        Androidbridge.validateConfig(JSONObject(profile.toBridgeJson("127.0.0.1:1080")).toString())
    }

    @Test
    fun rawProfilesRejectRemovedTunnelProtocolsAndRealityFingerprint() {
        RemovedTunnelProtocols.forEach { protocol ->
            val profile = AppConfig(
                name = "legacy $protocol",
                rawConfigJson = """{"outbounds":[{"tag":"proxy","type":"$protocol"}]}""",
            )
            assertTrue(profile.validate().orEmpty().contains("no longer supports $protocol"))
            assertTrue(runCatching { profile.toBridgeJson("127.0.0.1:1080") }.isFailure)
        }
        val fingerprint = AppConfig(
            name = "legacy fingerprint",
            rawConfigJson = """{"outbounds":[{"tag":"proxy","type":"native","token":"secret","security":{"fingerprint":"chrome"}}]}""",
        )
        assertTrue(fingerprint.validate().orEmpty().contains("security.fingerprint was removed"))
        assertTrue(runCatching { fingerprint.toBridgeJson("127.0.0.1:1080") }.isFailure)
    }

    @Test
    fun structuredResumableProfileRoundTripsAndValidatesInCurrentBridge() {
        val profile = AppConfig(
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

        val restored = AppConfig.fromJson(profile.toJson())
        val config = JSONObject(restored.toBridgeJson("127.0.0.1:1080"))
        val mux = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("mux")

        assertTrue(mux.getBoolean("resume"))
        assertEquals("15000ms", mux.getString("resume_timeout"))
        assertEquals(4_194_304, mux.getInt("resume_buffer_size"))
        assertNull(ProfileUriCodec.encodeForQr(restored))
        Androidbridge.validateConfig(config.toString())
    }

    @Test
    fun structuredEchProfileEmitsCurrentClientHelloSchema() {
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
        val restored = AppConfig.fromJson(profile.toJson())
        val config = JSONObject(restored.toBridgeJson("127.0.0.1:1080"))
        val proxy = config.getJSONArray("outbounds").getJSONObject(0)
        val clientHello = proxy.getJSONObject("client_hello")

        assertEquals("ech", clientHello.getString("type"))
        assertEquals("public.example", clientHello.getString("public_name"))
        assertEquals(profile.echPublicKey, clientHello.getString("public_key"))
        assertEquals(443, clientHello.getJSONArray("ports").getInt(0))
        assertEquals(8443, clientHello.getJSONArray("ports").getInt(1))
        assertEquals("none", proxy.getJSONObject("security").getString("type"))
        assertNull(ProfileUriCodec.encode(restored))
        assertNull(ProfileUriCodec.encodeForQr(restored))
        assertTrue(profileConnectionIdentity(restored) != null)
        Androidbridge.validateConfig(config.toString())
    }

    @Test
    fun fullJsonPreservesServerOnlyTlsPassthroughFallback() {
        val raw = """
            {
              "inbounds": [{
                "tag": "server",
                "type": "native",
                "address": ["127.0.0.1:19443"],
                "network": ["tcp", "udp"],
                "users": [{"id": "secret"}],
                "transport": {"type": "raw"},
                "security": {"type": "none"},
                "carrier": {"mode": "tcp"},
                "mux": {"enabled": true},
                "fallback": {
                  "type": "tls_passthrough",
                  "dest": "127.0.0.1:20443",
                  "server_names": ["www.example.com"],
                  "handshake_timeout": "3s"
                }
              }],
              "outbounds": [{"tag": "direct", "type": "direct"}],
              "route": {"default_outbound": "direct", "rules": []},
              "dns": {}
            }
        """.trimIndent()

        val prepared = JSONObject(
            AppConfig(name = "fallback", rawConfigJson = raw)
                .toBridgeJson(localListenAddr = "127.0.0.1:1080"),
        )
        val inbounds = prepared.getJSONArray("inbounds")
        val server = (0 until inbounds.length())
            .map(inbounds::getJSONObject)
            .first { it.getString("tag") == "server" }
        val fallback = server.getJSONObject("fallback")

        assertEquals("tls_passthrough", fallback.getString("type"))
        assertEquals("127.0.0.1:20443", fallback.getString("dest"))
        assertEquals("www.example.com", fallback.getJSONArray("server_names").getString(0))
        assertEquals("3s", fallback.getString("handshake_timeout"))
        Androidbridge.validateConfig(prepared.toString())
    }

    @Test
    fun sharedUidIdentityIsBoundedToTheGoAttributeValueLimit() {
        val packages = List(300) { index -> "com.example.app$index" }

        val identity = JSONObject(androidAppIdentityJson(12345, packages, "").orEmpty())

        assertEquals(256, identity.getJSONObject("attributes").getJSONArray("packages").length())
    }

    @Test
    fun managedRoutesReserveOneGoRuleForConnectivityChecks() {
        val profile = AppConfig(
            id = "route-limit",
            name = "route-limit",
            serverHost = "192.0.2.1",
            serverPort = "443",
            token = "secret",
        )
        val acceptedRules = List(MaxActiveManagedRouteRuleCount) { index ->
            ManagedRouteRule(id = "rule-$index", value = "example$index.com")
        }
        val plan = ProfileRunPlan(listOf(profile))

        Androidbridge.validateConfig(
            plan.toBridgeJson(
                localListenAddr = "127.0.0.1:1080",
                managedRouteRules = acceptedRules,
            ),
        )

        val rejected = runCatching {
            plan.toBridgeJson(
                localListenAddr = "127.0.0.1:1080",
                managedRouteRules = acceptedRules +
                    ManagedRouteRule(id = "rule-overflow", value = "overflow.example.com"),
            )
        }
        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("255 managed route rules"))
    }

    @Test
    fun currentBridgeRejectsInvalidRuntimeConfig() {
        val result = runCatching {
            Androidbridge.validateConfig(
                """{
                    "inbounds":[{"tag":"local","type":"mixed","address":["127.0.0.1:18080"],"network":["tcp"]}],
                    "outbounds":[],
                    "route":{"default_outbound":"missing"}
                }""".trimIndent(),
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun profileRunPlanJsonPreservesActiveSubsetAndMigratesLegacyPlans() {
        val first = AppConfig(id = "first", name = "first", serverHost = "192.0.2.10", token = "one")
        val second = AppConfig(id = "second", name = "second", serverHost = "192.0.2.20", token = "two")
        val plan = ProfileRunPlan(listOf(first, second), setOf(second.id))

        assertEquals(setOf(second.id), ProfileRunPlan.fromJson(plan.toJson()).activeIds)

        val legacy = plan.toJson().apply { remove("activeIds") }
        assertEquals(setOf(first.id, second.id), ProfileRunPlan.fromJson(legacy).activeIds)
    }

    @Test
    fun legacyCheckboxSelectionIsNotMigratedAsRunningConnections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tcptun", 0)
        val first = AppConfig(id = "legacy-first", name = "first", serverHost = "192.0.2.10", token = "one")
        val second = AppConfig(id = "legacy-second", name = "second", serverHost = "192.0.2.20", token = "two")
        prefs.edit().clear().commit()
        try {
            prefs.edit()
                .putString("profiles", JSONArray().put(first.toJson()).put(second.toJson()).toString())
                .putString("enabledProfileIds", JSONArray().put(first.id).put(second.id).toString())
                .putString("activeProfileIds", JSONArray().put(first.id).put(second.id).toString())
                .commit()

            val migrated = ProfileStore.load(context)

            assertEquals(emptySet<String>(), migrated.activeIds)
            assertFalse(prefs.contains("enabledProfileIds"))
            assertEquals(0, JSONArray(prefs.getString("activeProfileIds", "[]")).length())

            ProfileStore.save(context, migrated.copy(activeIds = setOf(second.id)))
            assertEquals(setOf(second.id), ProfileStore.load(context).activeIds)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun corruptOrDuplicateStoredProfilesCannotCrashUiStateLoading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tcptun", 0)
        prefs.edit().clear().commit()
        try {
            prefs.edit().putInt("profiles", 7).commit()
            assertEquals(emptyList<AppConfig>(), ProfileStore.load(context).profiles)
            assertFalse(ProfileStore.snapshot(context).isAuthoritative)

            val first = AppConfig(id = "duplicate", name = "first", serverHost = "192.0.2.10", token = "one")
            val second = AppConfig(id = "duplicate", name = "second", serverHost = "192.0.2.20", token = "two")
            val blankId = AppConfig(id = "temporary", name = "third", serverHost = "192.0.2.30", token = "three")
            prefs.edit()
                .putInt("profileStateVersion", 2)
                .putString(
                    "profiles",
                    JSONArray()
                        .put(first.toJson())
                        .put(JSONObject.NULL)
                        .put(second.toJson())
                        .put(blankId.toJson().put("id", " "))
                        .toString(),
                )
                .putString("activeProfileIds", JSONArray().put("duplicate").toString())
                .commit()

            val loaded = ProfileStore.load(context)

            assertEquals(3, loaded.profiles.size)
            assertEquals(3, loaded.profiles.map(AppConfig::id).toSet().size)
            assertTrue(loaded.profiles.all { it.id.isNotBlank() })
            assertEquals(setOf("duplicate"), loaded.activeIds)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun corruptOrDuplicateStoredRouteRulesLoadSafelyWithUniqueKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tcptun_routes", 0)
        prefs.edit().clear().commit()
        try {
            RouteRuleStore.save(context, emptyList()).getOrThrow()
            prefs.edit().putBoolean("managedRouteRules", true).commit()
            assertEquals(emptyList<ManagedRouteRule>(), RouteRuleStore.load(context))

            val encoded = JSONArray()
                .put(
                    JSONObject()
                        .put("id", "duplicate")
                        .put("type", ManagedRouteRuleType.DomainSuffix.name)
                        .put("value", "example.com")
                        .put("outbound", ManagedRouteOutbound.Proxy.name),
                )
                .put(JSONObject.NULL)
                .put(
                    JSONObject()
                        .put("id", "duplicate")
                        .put("type", ManagedRouteRuleType.DomainSuffix.name)
                        .put("value", "example.org")
                        .put("outbound", ManagedRouteOutbound.Direct.name),
                )
                .toString()
            prefs.edit().putString("managedRouteRules", encoded).commit()

            val loaded = RouteRuleStore.load(context)
            val reloaded = RouteRuleStore.load(context)

            assertEquals(2, loaded.size)
            assertEquals(2, loaded.map(ManagedRouteRule::id).toSet().size)
            assertEquals(loaded.map(ManagedRouteRule::id), reloaded.map(ManagedRouteRule::id))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun routeRuleStoreRejectsNewOverflowAndMigratesHistoricalOverflowInPlace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tcptun_routes", 0)
        prefs.edit().clear().commit()
        try {
            val accepted = List(MaxActiveManagedRouteRuleCount) { index ->
                ManagedRouteRule(id = "accepted-$index", value = "accepted$index.example")
            }
            RouteRuleStore.save(context, accepted).getOrThrow()

            val rejected = RouteRuleStore.save(
                context,
                accepted + ManagedRouteRule(id = "overflow", value = "overflow.example"),
            )
            assertTrue(rejected.isFailure)
            assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("255 managed route rules"))
            assertEquals(accepted.map(ManagedRouteRule::id), RouteRuleStore.load(context).map(ManagedRouteRule::id))

            val historical = JSONArray().apply {
                repeat(MaxActiveManagedRouteRuleCount + 2) { index ->
                    put(
                        JSONObject()
                            .put("id", "historical-$index")
                            .put("type", ManagedRouteRuleType.DomainSuffix.name)
                            .put("value", "historical$index.example")
                            .put("outbound", ManagedRouteOutbound.Proxy.name)
                            .put("enabled", true),
                    )
                }
            }
            prefs.edit().putString("managedRouteRules", historical.toString()).commit()

            val migrated = RouteRuleStore.load(context)

            assertEquals(MaxActiveManagedRouteRuleCount + 2, migrated.size)
            assertEquals(MaxActiveManagedRouteRuleCount, migrated.count(ManagedRouteRule::enabled))
            assertFalse(migrated[MaxActiveManagedRouteRuleCount].enabled)
            assertFalse(migrated[MaxActiveManagedRouteRuleCount + 1].enabled)
            val persisted = JSONArray(prefs.getString("managedRouteRules", "[]"))
            assertFalse(persisted.getJSONObject(MaxActiveManagedRouteRuleCount).getBoolean("enabled"))
            assertFalse(persisted.getJSONObject(MaxActiveManagedRouteRuleCount + 1).getBoolean("enabled"))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun routeRuleStoreRejectsRuntimePayloadOverflowBeforeReplacingStoredRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tcptun_routes", 0)
        prefs.edit().clear().commit()
        try {
            val baseline = listOf(ManagedRouteRule(id = "baseline", value = "baseline.example"))
            RouteRuleStore.save(context, baseline).getOrThrow()
            val oversized = List(96) { index ->
                ManagedRouteRule(
                    id = "large-$index",
                    value = "a".repeat(MaxManagedRouteRuleValueLength - 2) + ".x",
                )
            }

            val rejected = RouteRuleStore.save(context, oversized)

            assertTrue(rejected.isFailure)
            assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("too large"))
            assertEquals(baseline, RouteRuleStore.load(context))

            val plan = ProfileRunPlan(
                listOf(
                    AppConfig(
                        id = "route-payload",
                        name = "route-payload",
                        serverHost = "192.0.2.1",
                        token = "secret",
                    ),
                ),
            )
            assertTrue(
                runCatching {
                    TcptunVpnService.preflightStartPayload(context, plan, oversized)
                }.isFailure,
            )
        } finally {
            prefs.edit().clear().commit()
        }
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
    fun stateFlowAcceptsAnExplicitlyClearedRemote() {
        val epoch = TcptunState.beginBridgeSession()
        TcptunState.applyBridgeStatusEvent(
            epoch,
            """{"session_id":1,"sequence":1,"state":"running","remote":"203.0.113.10:443"}""",
        )
        TcptunState.applyBridgeStatusEvent(
            epoch,
            """{"session_id":1,"sequence":2,"state":"outbound_stopped","remote":""}""",
        )

        assertEquals("", TcptunState.state.value.diagnostics.bridgeRemote)
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
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.App,
                        value = "com.android.chrome",
                        outbound = ManagedRouteOutbound.Direct,
                    ),
                ),
            ),
        )

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))
        assertEquals("direct", rules.getJSONObject(1).getString("outbound"))
        assertEquals("example.com", rules.getJSONObject(1).getJSONArray("domain_suffixes").getString(0))
        assertEquals("direct", rules.getJSONObject(2).getString("outbound"))
        val app = rules.getJSONObject(2).getJSONObject("app")
        assertEquals("android", app.getJSONArray("platforms").getString(0))
        assertEquals(
            "com.android.chrome",
            app.getJSONObject("attributes").getJSONArray("packages").getString(0),
        )
        assertEquals(3, rules.length())
        for (index in 0 until rules.length()) {
            val inbound = rules.getJSONObject(index).getJSONArray("inbound")
            assertEquals(1, inbound.length())
            assertEquals(AndroidTunInboundTag, inbound.getString(0))
        }
        assertFalse(config.has("discovery"))

        assertEngineStarts(config.toString())
    }

    @Test
    fun managedRouteRulesCanIncludeLocalProxyInbound() {
        val config = JSONObject(
            AppConfig(
                serverHost = "192.0.2.1",
                serverPort = "443",
                token = "route-local-proxy-test",
                protocol = "native",
            ).toBridgeJson(
                localListenAddr = "127.0.0.1:18080",
                managedRouteRules = listOf(
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = "example.com",
                        outbound = ManagedRouteOutbound.Direct,
                    ),
                ),
                routeLocalProxyTraffic = true,
            ),
        )

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertTrue(rules.length() >= 1)
        for (index in 0 until rules.length()) {
            val inbound = rules.getJSONObject(index).getJSONArray("inbound")
            assertEquals(2, inbound.length())
            assertEquals(AndroidTunInboundTag, inbound.getString(0))
            assertEquals(AndroidLocalProxyInboundTag, inbound.getString(1))
        }
        assertEquals(AndroidLocalProxyInboundTag, config.getJSONArray("inbounds").getJSONObject(0).getString("tag"))
        assertEngineStarts(config.toString())
    }

    @Test
    fun runtimeLocalProxyProtocolControlsStructuredAndRawAndroidInbounds() {
        val structured = AppConfig(
            serverHost = "192.0.2.1",
            serverPort = "443",
            token = "local-protocol-test",
            protocol = "native",
            upstreamProtocol = "socks5",
        )
        val raw = AppConfig(
            rawConfigJson = """{
                "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
                "route":{"default_outbound":"direct"}
            }""".trimIndent(),
        )

        listOf(structured, raw).forEach { profile ->
            LocalProxyProtocols.forEach { protocol ->
                val noAuthInbound = JSONObject(
                    profile.toBridgeJson(
                        localListenAddr = "127.0.0.1:1080",
                        localProxyProtocol = protocol,
                    ),
                ).getJSONArray("inbounds").getJSONObject(0)
                assertFalse(noAuthInbound.has("users"))
                assertFalse(noAuthInbound.has("username"))
                assertFalse(noAuthInbound.has("password"))
                val config = JSONObject(
                    profile.toBridgeJson(
                        localListenAddr = "127.0.0.1:1080",
                        localProxyProtocol = protocol,
                        localProxyUsers = listOf(
                            LocalProxyUser("android-user", "android-password"),
                            LocalProxyUser("second-user", "second-password"),
                        ),
                    ),
                )
                val inbound = config.getJSONArray("inbounds").getJSONObject(0)
                assertEquals(
                    protocol,
                    inbound.getString("type"),
                )
                assertFalse(inbound.has("username"))
                assertFalse(inbound.has("password"))
                val users = inbound.getJSONArray("users")
                assertEquals("android-user", users.getJSONObject(0).getString("username"))
                assertEquals("android-password", users.getJSONObject(0).getString("password"))
                assertEquals("second-user", users.getJSONObject(1).getString("username"))
                assertEquals("second-password", users.getJSONObject(1).getString("password"))
                assertEngineStarts(config.toString())
            }
        }
    }

    @Test
    fun rawAndroidRewritePreservesMixedAndSocksProxyCredentials() {
        val raw = """{
            "inbounds":[{
                "tag":"preserved-mixed",
                "type":"mixed",
                "address":["127.0.0.1:19123"],
                "network":["tcp"],
                "username":"raw-inbound-user",
                "password":"raw-inbound-password"
            }],
            "outbounds":[{
                "tag":"proxy",
                "type":"socks5",
                "address":["127.0.0.1:19124"],
                "network":["tcp","udp"],
                "username":"raw-outbound-user",
                "password":"raw-outbound-password",
                "auth_mode":"secure"
            }],
            "route":{"default_outbound":"proxy","rules":[]},
            "dns":{}
        }""".trimIndent()

        val prepared = JSONObject(
            AppConfig(name = "raw-proxy-auth", rawConfigJson = raw).toBridgeJson(
                localListenAddr = "127.0.0.1:19125",
                localProxyProtocol = "mixed",
                localProxyUsers = listOf(LocalProxyUser("android-user", "android-password")),
            ),
        )
        val androidInbound = prepared.getJSONArray("inbounds").getJSONObject(0)
        val preservedInbound = prepared.getJSONArray("inbounds").getJSONObject(1)
        val outbound = prepared.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("mixed", androidInbound.getString("type"))
        assertFalse(androidInbound.has("username"))
        assertFalse(androidInbound.has("password"))
        assertEquals("android-user", androidInbound.getJSONArray("users").getJSONObject(0).getString("username"))
        assertEquals("android-password", androidInbound.getJSONArray("users").getJSONObject(0).getString("password"))
        assertEquals("raw-inbound-user", preservedInbound.getString("username"))
        assertEquals("raw-inbound-password", preservedInbound.getString("password"))
        assertEquals("raw-outbound-user", outbound.getString("username"))
        assertEquals("raw-outbound-password", outbound.getString("password"))
        assertEquals("secure", outbound.getString("auth_mode"))
        Androidbridge.validateConfig(prepared.toString())
    }

    @Test
    fun localProxyUsersGenerateNewSchemaAndRawAuthenticatedUsersRemainUntouched() {
        val raw = """{
            "inbounds":[
                {"tag":"native-users","type":"native","address":["127.0.0.1:19201"],"network":["tcp"],"users":[{"id":"native-a"},{"id":"native-b"}],"transport":{"type":"raw"}},
                {"tag":"mixed-users","type":"mixed","address":["127.0.0.1:19205"],"network":["tcp","udp"],"users":[{"username":"raw-a","password":"a"},{"username":"raw-b","password":"b"}]},
                {"tag":"socks-users","type":"socks5","address":["127.0.0.1:19206"],"network":["tcp","udp"],"users":[{"username":"raw-c","password":"c"},{"username":"raw-d","password":"d"}]}
            ],
            "outbounds":[{"tag":"direct","type":"direct"}],
            "route":{"default_outbound":"direct","rules":[]}
        }""".trimIndent()
        val expectedUsers = JSONObject(raw).getJSONArray("inbounds").let { inbounds ->
            (0 until inbounds.length()).associate { index ->
                val inbound = inbounds.getJSONObject(index)
                inbound.getString("tag") to inbound.getJSONArray("users").toString()
            }
        }
        val prepared = JSONObject(
            AppConfig(name = "raw-multi-user", rawConfigJson = raw).toBridgeJson(
                localListenAddr = "127.0.0.1:19200",
                localProxyProtocol = "mixed",
                localProxyUsers = listOf(
                    LocalProxyUser("alice", "secret-a"),
                    LocalProxyUser("bob", "secret-b"),
                ),
            ),
        )

        val androidInbound = prepared.getJSONArray("inbounds").getJSONObject(0)
        assertFalse(androidInbound.has("username"))
        assertFalse(androidInbound.has("password"))
        assertEquals(2, androidInbound.getJSONArray("users").length())
        assertEquals("bob", androidInbound.getJSONArray("users").getJSONObject(1).getString("username"))
        for (index in 1 until prepared.getJSONArray("inbounds").length()) {
            val inbound = prepared.getJSONArray("inbounds").getJSONObject(index)
            assertEquals(expectedUsers.getValue(inbound.getString("tag")), inbound.getJSONArray("users").toString())
        }
        assertFalse(prepared.getJSONArray("outbounds").getJSONObject(0).has("users"))
        Androidbridge.validateConfig(prepared.toString())
    }

    @Test
    fun startIntentUsesPersistedLocalProxyProtocol() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalSettings = TcptunVpnService.readRuntimeSettings(context)
        val profile = AppConfig(
            rawConfigJson = """{
                "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
                "route":{"default_outbound":"direct"}
            }""".trimIndent(),
        )

        try {
            LocalProxyProtocols.forEach { protocol ->
                TcptunVpnService.writeRuntimeSettings(
                    context,
                    originalSettings.copy(localProxyProtocol = protocol),
                )
                val intent = TcptunVpnService.startIntent(context, profile)
                val config = JSONObject(VpnServiceIntents.parseStartCommand(context, intent).configJson)
                assertEquals(
                    protocol,
                    config.getJSONArray("inbounds").getJSONObject(0).getString("type"),
                )
            }
        } finally {
            TcptunVpnService.writeRuntimeSettings(context, originalSettings)
        }
    }

    @Test
    fun startIntentUsesPersistedPoolOrDirectDefaultOutbound() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalSettings = TcptunVpnService.readRuntimeSettings(context)
        val profile = AppConfig(
            id = "persisted-default-outbound",
            name = "persisted default",
            serverHost = "192.0.2.10",
            token = "token",
        )

        try {
            TcptunVpnService.writeRuntimeSettings(
                context,
                originalSettings.copy(defaultOutbound = DefaultOutboundDirect),
            )
            val directIntent = TcptunVpnService.startIntent(context, profile)
            val direct = JSONObject(VpnServiceIntents.parseStartCommand(context, directIntent).configJson)
            assertEquals("direct", direct.getJSONObject("route").getString("default_outbound"))

            TcptunVpnService.writeRuntimeSettings(
                context,
                originalSettings.copy(defaultOutbound = profile.id),
            )
            val selectedIntent = TcptunVpnService.startIntent(context, profile)
            val selectedFallsBackToPool = JSONObject(
                VpnServiceIntents.parseStartCommand(context, selectedIntent).configJson,
            )
            assertEquals(
                "profile-pool",
                selectedFallsBackToPool.getJSONObject("route").getString("default_outbound"),
            )
        } finally {
            TcptunVpnService.writeRuntimeSettings(context, originalSettings)
        }
    }

    @Test
    fun generatedMultiProfileConfigRoutesToStableOutboundTags() {
        val primary = AppConfig(
            id = "00000000-0000-4000-8000-000000000001",
            name = "primary",
            serverHost = "192.0.2.10",
            serverPort = "443",
            token = "primary-token",
            protocol = "native",
        )
        val secondary = AppConfig(
            id = "00000000-0000-4000-8000-000000000002",
            name = "secondary",
            serverHost = "192.0.2.20",
            serverPort = "443",
            token = "secondary-token",
            protocol = "native",
        )
        val config = JSONObject(
            ProfileRunPlan(listOf(primary, secondary), activeIds = setOf(primary.id)).toBridgeJson(
                localListenAddr = "127.0.0.1:18083",
                managedRouteRules = listOf(
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = "example.com",
                        outboundProfileId = secondary.id,
                    ),
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = "pool.example",
                    ),
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.IPCidr,
                        value = "198.51.100.0/24",
                        outbound = ManagedRouteOutbound.Direct,
                    ),
                ),
            ),
        )

        val primaryTag = profileOutboundTag(primary.id)
        val secondaryTag = profileOutboundTag(secondary.id)
        val outbounds = config.getJSONArray("outbounds")
        assertEquals(primaryTag, outbounds.getJSONObject(0).getString("tag"))
        assertEquals(secondaryTag, outbounds.getJSONObject(1).getString("tag"))
        assertEquals("direct", outbounds.getJSONObject(2).getString("tag"))
        assertEquals("profile-pool", outbounds.getJSONObject(3).getString("tag"))
        assertEquals("balance", outbounds.getJSONObject(3).getString("type"))
        assertEquals(primaryTag, outbounds.getJSONObject(3).getJSONArray("members").getJSONObject(0).getString("outbound"))
        assertEquals(secondaryTag, outbounds.getJSONObject(3).getJSONArray("members").getJSONObject(1).getString("outbound"))
        assertEquals("profile-pool", config.getJSONObject("route").getString("default_outbound"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("profile-pool", rules.getJSONObject(0).getString("outbound"))
        assertEquals(secondaryTag, rules.getJSONObject(1).getString("outbound"))
        assertEquals("tcp", rules.getJSONObject(1).getJSONArray("network").getString(0))
        assertEquals("profile-pool", rules.getJSONObject(2).getString("outbound"))
        assertEquals("direct", rules.getJSONObject(3).getString("outbound"))
        assertFalse(config.has("discovery"))

        assertEngineStarts(config.toString())
        assertEngineHotSwitchesOutbound(config.toString(), secondaryTag)
    }

    @Test
    fun missingManagedRouteProfileFallsBackToDynamicPoolWithoutDroppingRule() {
        val profile = AppConfig(
            id = "remaining-profile",
            name = "remaining",
            serverHost = "192.0.2.10",
            serverPort = "443",
            token = "remaining-token",
        )
        val config = JSONObject(
            ProfileRunPlan(listOf(profile)).toBridgeJson(
                localListenAddr = "127.0.0.1:18088",
                managedRouteRules = listOf(
                    ManagedRouteRule(
                        id = "stale-profile-route",
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = "stale.example",
                        outboundProfileId = "deleted-profile",
                    ),
                ),
            ),
        )

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals(2, rules.length())
        val staleRule = rules.getJSONObject(1)
        assertEquals("stale.example", staleRule.getJSONArray("domain_suffixes").getString(0))
        assertEquals("profile-pool", staleRule.getString("outbound"))
        assertFalse(staleRule.has("network"))
        Androidbridge.validateConfig(config.toString())
    }

    @Test
    fun healthTargetsStayOnPoolAheadOfProfileSpecificRules() {
        val first = AppConfig(id = "health-a", name = "A", serverHost = "192.0.2.10", token = "a")
        val second = AppConfig(id = "health-b", name = "B", serverHost = "192.0.2.20", token = "b")
        val config = JSONObject(
            ProfileRunPlan(listOf(first, second)).toBridgeJson(
                localListenAddr = "127.0.0.1:18089",
                managedRouteRules = listOf(
                    ManagedRouteRule(
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = "connectivitycheck.gstatic.com",
                        outboundProfileId = second.id,
                    ),
                ),
            ),
        )

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("profile-pool", rules.getJSONObject(0).getString("outbound"))
        assertEquals(
            profileOutboundTag(second.id),
            rules.getJSONObject(1).getString("outbound"),
        )
    }

    @Test
    fun singleProfileUsesTheSameStablePoolStructure() {
        val profile = AppConfig(
            id = "00000000-0000-4000-8000-000000000010",
            name = "single",
            serverHost = "192.0.2.10",
            serverPort = "443",
            token = "single-token",
        )

        val config = JSONObject(
            ProfileRunPlan(listOf(profile), activeIds = setOf(profile.id)).toBridgeJson("127.0.0.1:18084"),
        )
        val outbounds = config.getJSONArray("outbounds")
        assertEquals(profileOutboundTag(profile.id), outbounds.getJSONObject(0).getString("tag"))
        assertEquals("direct", outbounds.getJSONObject(1).getString("tag"))
        assertEquals("profile-pool", outbounds.getJSONObject(2).getString("tag"))
        assertEquals("profile-pool", config.getJSONObject("route").getString("default_outbound"))
    }

    @Test
    fun generatedConfigAllowsDirectAndFallsBackFromSpecificProfileToPool() {
        val first = AppConfig(
            id = "default-outbound-a",
            name = "first",
            serverHost = "192.0.2.10",
            token = "first-token",
        )
        val second = AppConfig(
            id = "default-outbound-b",
            name = "second",
            serverHost = "192.0.2.20",
            token = "second-token",
        )
        val plan = ProfileRunPlan(listOf(first, second))

        val direct = JSONObject(
            plan.toBridgeJson(
                localListenAddr = "127.0.0.1:18093",
                defaultOutbound = DefaultOutboundDirect,
            ),
        )
        assertEquals("direct", direct.getJSONObject("route").getString("default_outbound"))

        val selected = JSONObject(
            plan.toBridgeJson(
                localListenAddr = "127.0.0.1:18094",
                defaultOutbound = second.id,
            ),
        )
        assertEquals(
            "profile-pool",
            selected.getJSONObject("route").getString("default_outbound"),
        )

        val deleted = JSONObject(
            plan.toBridgeJson(
                localListenAddr = "127.0.0.1:18095",
                defaultOutbound = "deleted-profile",
            ),
        )
        assertEquals("profile-pool", deleted.getJSONObject("route").getString("default_outbound"))

        Androidbridge.validateConfig(direct.toString())
        Androidbridge.validateConfig(selected.toString())
        Androidbridge.validateConfig(deleted.toString())
    }

    @Test
    fun fullJsonProfileKeepsItsOwnDefaultOutboundSetting() {
        val profile = AppConfig(
            id = "raw-default-outbound",
            rawConfigJson = """{
                "outbounds":[
                    {"tag":"raw-default","type":"blackhole"},
                    {"tag":"direct","type":"direct"}
                ],
                "route":{"default_outbound":"raw-default"}
            }""".trimIndent(),
        )
        val config = JSONObject(
            ProfileRunPlan(listOf(profile)).toBridgeJson(
                localListenAddr = "127.0.0.1:18096",
                defaultOutbound = DefaultOutboundDirect,
            ),
        )

        assertEquals("raw-default", config.getJSONObject("route").getString("default_outbound"))
        Androidbridge.validateConfig(config.toString())
    }

    @Test
    fun twoProfilesStartAndStopIndependentlyWithoutReplacingSession() {
        val first = AppConfig(
            id = "00000000-0000-4000-8000-000000000021",
            name = "first",
            serverHost = "192.0.2.10",
            serverPort = "443",
            token = "first-token",
        )
        val second = AppConfig(
            id = "00000000-0000-4000-8000-000000000022",
            name = "second",
            serverHost = "192.0.2.20",
            serverPort = "443",
            token = "second-token",
        )
        val firstTag = profileOutboundTag(first.id)
        val secondTag = profileOutboundTag(second.id)
        val config = ProfileRunPlan(listOf(first, second), activeIds = setOf(first.id))
            .toBridgeJson("127.0.0.1:18085")
        val engine = Androidbridge.newEngine()
        try {
            engine.configure(withAvailableInboundPorts(config))
            val sessionId = engine.startConfiguredSessionWithDisabledOutbounds(
                org.json.JSONArray().put(firstTag).put(secondTag).toString(),
            )
            assertNotEquals(0L, sessionId)
            assertOutboundStates(engine.outboundsStatusJSON(), firstTag to false, secondTag to false, "profile-pool" to true)

            engine.startOutbound(firstTag)
            assertEquals(sessionId, engine.sessionID())
            assertOutboundStates(engine.outboundsStatusJSON(), firstTag to true, secondTag to false, "profile-pool" to true)

            engine.startOutbound(secondTag)
            assertEquals(sessionId, engine.sessionID())
            assertOutboundStates(engine.outboundsStatusJSON(), firstTag to true, secondTag to true, "profile-pool" to true)

            engine.stopOutbound(firstTag, true, 1_000)
            assertEquals(sessionId, engine.sessionID())
            assertOutboundStates(engine.outboundsStatusJSON(), firstTag to false, secondTag to true, "profile-pool" to true)

            engine.stopOutbound(secondTag, true, 1_000)
            assertEquals(sessionId, engine.sessionID())
            assertOutboundStates(engine.outboundsStatusJSON(), firstTag to false, secondTag to false, "profile-pool" to true)

            engine.startOutbound(firstTag)
            assertEquals(sessionId, engine.sessionID())
            assertOutboundStates(engine.outboundsStatusJSON(), firstTag to true, secondTag to false, "profile-pool" to true)
        } finally {
            engine.close()
        }
    }

    @Test
    fun probeOutboundMeasuresEachExactRunningLinkWithoutChangingSession() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { target ->
            val accepted = CompletableFuture<Unit>()
            thread(name = "tcping-contract-listener", isDaemon = true) {
                runCatching {
                    repeat(2) { target.accept().use { } }
                }.fold(accepted::complete, accepted::completeExceptionally)
            }
            val config = """{
                "inbounds":[{"tag":"local","type":"mixed","address":["127.0.0.1:18086"],"network":["tcp"]}],
                "outbounds":[
                    {"tag":"profile-a","type":"direct","network":["tcp"]},
                    {"tag":"profile-b","type":"direct","network":["tcp"]},
                    {"tag":"profile-pool","type":"balance","network":["tcp"],"members":[{"outbound":"profile-a"},{"outbound":"profile-b"}]}
                ],
                "route":{"default_outbound":"profile-pool"}
            }""".trimIndent()
            val engine = Androidbridge.newEngine()
            try {
                engine.configure(withAvailableInboundPorts(config))
                val sessionId = engine.startConfiguredSessionWithDisabledOutbounds("[\"profile-b\"]")

                assertTrue(engine.probeOutbound("profile-a", "127.0.0.1", target.localPort.toLong(), 1_000) >= 0)
                assertTrue(runCatching {
                    engine.probeOutbound("profile-b", "127.0.0.1", target.localPort.toLong(), 1_000)
                }.exceptionOrNull()?.message.orEmpty().contains("stopped", ignoreCase = true))
                assertTrue(runCatching {
                    engine.probeOutbound("profile-pool", "127.0.0.1", target.localPort.toLong(), 1_000)
                }.exceptionOrNull()?.message.orEmpty().contains("selector", ignoreCase = true))

                engine.startOutbound("profile-b")
                assertTrue(engine.probeOutbound("profile-b", "127.0.0.1", target.localPort.toLong(), 1_000) >= 0)
                accepted.get(3, TimeUnit.SECONDS)
                assertEquals(sessionId, engine.sessionID())
                assertOutboundStates(
                    engine.outboundsStatusJSON(),
                    "profile-a" to true,
                    "profile-b" to true,
                    "profile-pool" to true,
                )
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun healthProbeTracksThreeMembersAndRecoversOneFailedMember() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { target ->
            val accepted = CompletableFuture<Unit>()
            thread(name = "member-health-contract-listener", isDaemon = true) {
                runCatching {
                    repeat(4) { target.accept().use { } }
                }.fold(accepted::complete, accepted::completeExceptionally)
            }
            val unavailablePort = ServerSocket(0).use { it.localPort }
            val config = """{
                "inbounds":[{"tag":"local","type":"mixed","address":["127.0.0.1:18090"],"network":["tcp"]}],
                "outbounds":[
                    {"tag":"a","type":"direct","network":["tcp"]},
                    {"tag":"b","type":"direct","network":["tcp"]},
                    {"tag":"c","type":"direct","network":["tcp"]},
                    {"tag":"pool","type":"balance","network":["tcp"],"members":[{"outbound":"a"},{"outbound":"b"},{"outbound":"c"}]}
                ],
                "route":{"default_outbound":"pool"}
            }""".trimIndent()
            val engine = Androidbridge.newEngine()
            try {
                engine.configure(withAvailableInboundPorts(config))
                engine.startConfiguredSessionWithDisabledOutbounds("[]")
                listOf("a", "b", "c").forEach { tag ->
                    assertTrue(engine.probeOutboundHealth(tag, "127.0.0.1", target.localPort.toLong(), 1_000) >= 0)
                }
                var statuses = JSONArray(engine.outboundsStatusJSON())
                listOf("a", "b", "c").forEach { tag ->
                    val status = (0 until statuses.length()).map(statuses::getJSONObject)
                        .first { it.getString("tag") == tag }
                    assertEquals("healthy", status.getString("health"))
                    assertEquals(0L, status.getLong("failures"))
                }

                assertTrue(runCatching {
                    engine.probeOutboundHealth("b", "127.0.0.1", unavailablePort.toLong(), 300)
                }.isFailure)
                statuses = JSONArray(engine.outboundsStatusJSON())
                var memberB = (0 until statuses.length()).map(statuses::getJSONObject)
                    .first { it.getString("tag") == "b" }
                assertEquals("degraded", memberB.getString("health"))
                assertTrue(memberB.getLong("failures") > 0)

                assertTrue(engine.probeOutboundHealth("b", "127.0.0.1", target.localPort.toLong(), 1_000) >= 0)
                statuses = JSONArray(engine.outboundsStatusJSON())
                memberB = (0 until statuses.length()).map(statuses::getJSONObject)
                    .first { it.getString("tag") == "b" }
                assertEquals("healthy", memberB.getString("health"))
                assertEquals(0L, memberB.getLong("failures"))
                accepted.get(3, TimeUnit.SECONDS)
            } finally {
                engine.close()
            }
        }
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
    fun mlKitScannerDecodesGeneratedProfileQrCode() {
        val profile = AppConfig(
            name = "mlkit-qr-test",
            serverHost = "192.0.2.1",
            serverPort = "9443",
            token = "mlkit-qr-secret",
            protocol = "native",
        )
        val qrPng = requireNotNull(ProfileUriCodec.encodeQrCode(profile))
        val bitmap = decodeQrCodeBitmap(qrPng)

        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val scanned = try {
            Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)))
                .firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
        } finally {
            scanner.close()
        }
        val qrPayload = requireNotNull(scanned)
        assertTrue(qrPayload.startsWith("T3:"))
        val decoded = ProfileUriCodec.decode(requireNotNull(scanned)).getOrThrow()
        assertEquals(profile.serverHost, decoded.serverHost)
        assertEquals(profile.token, decoded.token)
        assertEquals(profile.protocol, decoded.protocol)
        assertEquals(profile.name, decoded.name)
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
        )

        val root = JSONObject(config)
        assertFalse(root.has("mode"))
        assertTrue(root.has("inbounds"))
        assertTrue(root.has("outbounds"))
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1:18080", inbound.getJSONArray("address").getString(0))
        assertFalse(inbound.has("listen"))
        assertFalse(inbound.has("port"))
        assertFalse(inbound.has("outbound"))
        val proxy = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("192.0.2.1:9443", proxy.getJSONArray("address").getString(0))
        assertFalse(proxy.has("server"))
        assertFalse(proxy.has("port"))
        assertTrue(proxy.getJSONObject("mux").getBoolean("enabled"))
        assertEquals(2, root.getJSONArray("outbounds").length())
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals(0, rules.length())
        val dns = root.getJSONObject("dns")
        assertEquals("1.1.1.1", dns.getJSONArray("servers").getString(0))
        assertEquals("prefer_ipv4", dns.getString("strategy"))
        assertEquals("proxy", dns.getString("outbound"))
        assertTrue(dns.getJSONObject("fake_ip").getBoolean("enabled"))
        assertEquals("198.18.0.0/15", dns.getJSONObject("fake_ip").getString("ipv4_range"))

        assertEngineStarts(config)
    }

    @Test
    fun generatedRealityWithQuicCarrierStartsCurrentGoBridge() {
        val profile = AppConfig(
            name = "reality-quic",
            serverHost = "192.0.2.1",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "android-reality-quic-test",
            sni = "example.com",
            tunnelSecurity = "reality",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1dbc5d54a",
            mux = true,
            carrierMode = "quic",
            carrierUdpMode = "auto",
            muxMaxSessions = 4,
            muxWarmSpare = 1,
        )
        assertNull(profile.validate())

        val config = profile.toBridgeJson(localListenAddr = "127.0.0.1:18086")
        val proxy = JSONObject(config).getJSONArray("outbounds").getJSONObject(0)
        val security = proxy.getJSONObject("security")
        assertEquals("reality", security.getString("type"))
        assertEquals("example.com", security.getString("server_name"))
        assertFalse(security.has("fingerprint"))
        assertFalse(security.has("insecure"))
        val carrier = proxy.getJSONObject("carrier")
        assertEquals("quic", carrier.getString("mode"))
        assertEquals("auto", carrier.getString("udp_mode"))
        val mux = proxy.getJSONObject("mux")
        assertTrue(mux.getBoolean("enabled"))
        assertFalse(mux.has("mode"))
        assertFalse(mux.has("udp_mode"))
        assertEquals(4, mux.getInt("max_sessions"))
        assertEquals(1, mux.getInt("warm_spares"))

        assertEngineStarts(config)
    }

    @Test
    fun strictConfigUsesNativeTokenAndRejectsRemovedProtocols() {
        val token = "native-token"
        val localOnly = JSONObject(
            AppConfig(
                serverHost = "2001:db8::1",
                serverPort = "443",
                token = token,
                protocol = "native",
            ).toBridgeJson(localListenAddr = "0.0.0.0:18081"),
        )
        val localOnlyOutbounds = localOnly.getJSONArray("outbounds")
        assertEquals(token, localOnlyOutbounds.getJSONObject(0).getString("token"))
        assertFalse(localOnlyOutbounds.getJSONObject(0).has("uuid"))
        assertFalse(localOnlyOutbounds.getJSONObject(0).has("password"))
        assertEquals("[2001:db8::1]:443", localOnlyOutbounds.getJSONObject(0).getJSONArray("address").getString(0))
        assertEquals(2, localOnlyOutbounds.length())

        RemovedTunnelProtocols.forEach { removed ->
            assertTrue(runCatching {
                AppConfig(
                serverHost = "192.0.2.1",
                serverPort = "443",
                    token = "legacy-credential",
                    protocol = removed,
                ).toBridgeJson(localListenAddr = "0.0.0.0:18082")
            }.isFailure)
        }
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
                localProxyUsers = listOf(LocalProxyUser("android", "secret")),
            )

        val root = JSONObject(config)
        val inbounds = root.getJSONArray("inbounds")
        assertEquals("android-vpn", inbounds.getJSONObject(0).getString("tag"))
        assertEquals("127.0.0.1:18090", inbounds.getJSONObject(0).getJSONArray("address").getString(0))
        assertFalse(inbounds.getJSONObject(0).has("outbound"))
        assertEquals("existing", inbounds.getJSONObject(1).getString("tag"))
        assertEquals("127.0.0.1:18091", inbounds.getJSONObject(1).getJSONArray("address").getString(0))
        assertEquals("blocked", root.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound"))
        assertEquals("prefer_ipv4", root.getJSONObject("dns").getString("strategy"))
        assertFalse(root.has("discovery"))

        assertEngineStarts(config)
    }

    @Test
    fun fullConfigPrependsManagedRulesUsingRawDefaultAndAddsUniqueDirectOutbound() {
        val raw = """
            {
              "outbounds": [
                {"tag": "raw-default", "type": "blackhole"},
                {"tag": "android-managed-direct", "type": "blackhole"}
              ],
              "route": {
                "default_outbound": "raw-default",
                "rules": [
                  {"domain_suffixes": ["preserved.example"], "outbound": "raw-default"}
                ]
              }
            }
        """.trimIndent()
        val config = JSONObject(
            AppConfig(name = "raw-managed", rawConfigJson = raw).toBridgeJson(
                localListenAddr = "127.0.0.1:18096",
                managedRouteRules = listOf(
                    ManagedRouteRule(
                        id = "raw-direct",
                        type = ManagedRouteRuleType.DomainSuffix,
                        value = "direct.example",
                        outbound = ManagedRouteOutbound.Direct,
                    ),
                    ManagedRouteRule(
                        id = "raw-proxy",
                        type = ManagedRouteRuleType.IPCidr,
                        value = "203.0.113.0/24",
                        outbound = ManagedRouteOutbound.Proxy,
                    ),
                ),
            ),
        )

        val outbounds = config.getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }
        assertEquals(tags.size, tags.toSet().size)
        val directOutbounds = (0 until outbounds.length())
            .map(outbounds::getJSONObject)
            .filter { it.getString("type") == "direct" }
        assertEquals(1, directOutbounds.size)
        assertEquals("android-managed-direct-2", directOutbounds.single().getString("tag"))

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals(4, rules.length())
        assertEquals("raw-default", rules.getJSONObject(0).getString("outbound"))
        assertEquals("android-managed-direct-2", rules.getJSONObject(1).getString("outbound"))
        assertEquals("direct.example", rules.getJSONObject(1).getJSONArray("domain_suffixes").getString(0))
        assertEquals("raw-default", rules.getJSONObject(2).getString("outbound"))
        assertEquals("203.0.113.0/24", rules.getJSONObject(2).getJSONArray("ip_cidrs").getString(0))
        assertEquals("raw-default", rules.getJSONObject(3).getString("outbound"))
        assertEquals("preserved.example", rules.getJSONObject(3).getJSONArray("domain_suffixes").getString(0))
        Androidbridge.validateConfig(config.toString())
    }

    @Test
    fun fullConfigManagedRulesRespectCombinedRuntimeRuleLimit() {
        val existingRules = JSONArray().apply {
            repeat(2) { index ->
                put(
                    JSONObject()
                        .put("domain_suffixes", JSONArray().put("existing$index.example"))
                        .put("outbound", "raw-default"),
                )
            }
        }
        val raw = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("tag", "raw-default").put("type", "blackhole")))
            .put("route", JSONObject().put("default_outbound", "raw-default").put("rules", existingRules))
            .toString()
        val managed = List(MaxActiveManagedRouteRuleCount) { index ->
            ManagedRouteRule(id = "managed-$index", value = "managed$index.example")
        }

        val rejected = runCatching {
            AppConfig(name = "raw-overflow", rawConfigJson = raw).toBridgeJson(
                localListenAddr = "127.0.0.1:18097",
                managedRouteRules = managed,
            )
        }

        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("256 total route rules"))
    }

    @Test
    fun fullConfigMigratesRemovedDirectFirstToFallback() {
        val prepared = JSONObject(
            AppConfig(
                name = "direct-first",
                rawConfigJson = """{
                    "outbounds":[
                        {"tag":"direct","type":"direct","network":["tcp"]},
                        {"tag":"proxy","type":"direct","network":["tcp"]},
                        {"tag":"auto","type":"direct-first","primary":"direct","fallback":"proxy","network":["tcp"]}
                    ],
                    "route":{
                        "default_outbound":"auto",
                        "rules":[{"domain_suffixes":["example.com"],"outbound":"auto"}]
                    }
                }""".trimIndent(),
            ).toBridgeJson(localListenAddr = "127.0.0.1:18092"),
        )

        assertEquals("proxy", prepared.getJSONObject("route").getString("default_outbound"))
        val outbounds = prepared.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        assertEquals(2, outbounds.getJSONObject(0).getJSONArray("network").length())
        assertEquals(2, outbounds.getJSONObject(1).getJSONArray("network").length())
        val rules = prepared.getJSONObject("route").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))

        assertEngineStarts(prepared.toString())
    }

    @Test
    fun tcptunGoConfigImportKeepsOutboundsRouteAndDns() {
        val raw = """
            {
              "log": {"level": "info"},
              "inbounds": [
                {"tag": "local", "type": "mixed", "listen": "127.0.0.1", "port": 1080,
                 "network": ["tcp", "udp"], "outbound": "proxy"}
              ],
              "outbounds": [
                {"tag": "proxy", "type": "native", "server": "192.0.2.1", "port": 9443,
                 "token": "android-import-test",
                 "transport": {"type": "raw", "tls": true, "server_name": "example.com", "insecure": true},
                 "mux": {"enabled": true}}
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
        assertTrue(profileConnectionIdentity(imported) != null)
        val stored = JSONObject(imported.rawConfigJson)
        assertFalse(stored.has("inbounds"))
        assertFalse(stored.has("log"))
        assertTrue(stored.has("dns"))
        assertFalse(stored.has("discovery"))
        assertTrue(stored.has("outbounds"))
        assertTrue(stored.has("route"))
        assertEquals(
            "tun",
            stored.getJSONObject("route").getJSONArray("rules")
                .getJSONObject(0).getJSONArray("inbound").getString(0),
        )
        val prepared = JSONObject(imported.toBridgeJson(localListenAddr = "127.0.0.1:1080"))
        assertTrue(prepared.getJSONObject("dns").getJSONObject("fake_ip").getBoolean("enabled"))
        val inbounds = prepared.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        assertEquals("android-vpn", inbounds.getJSONObject(0).getString("tag"))
        assertEquals("127.0.0.1:1080", inbounds.getJSONObject(0).getJSONArray("address").getString(0))
        val proxy = prepared.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("192.0.2.1:9443", proxy.getJSONArray("address").getString(0))
        assertFalse(proxy.has("server"))
        assertFalse(proxy.has("port"))
        assertTrue(proxy.getJSONObject("mux").getBoolean("enabled"))
        assertEquals("tls", proxy.getJSONObject("security").getString("type"))
        assertEquals("example.com", proxy.getJSONObject("security").getString("server_name"))
        assertTrue(proxy.getJSONObject("security").getBoolean("insecure"))
        assertFalse(proxy.getJSONObject("transport").has("tls"))
        val ruleInbound = prepared.getJSONObject("route").getJSONArray("rules")
            .getJSONObject(0).getJSONArray("inbound")
        assertEquals("tun", ruleInbound.getString(0))

        assertEngineStarts(prepared.toString())

        val preparedWithLocalRouting = JSONObject(
            imported.toBridgeJson(
                localListenAddr = "127.0.0.1:1080",
                routeLocalProxyTraffic = true,
            ),
        )
        val localRuleInbound = preparedWithLocalRouting.getJSONObject("route").getJSONArray("rules")
            .getJSONObject(0).getJSONArray("inbound")
        assertEquals(2, localRuleInbound.length())
        assertEquals(AndroidTunInboundTag, localRuleInbound.getString(0))
        assertEquals("android-vpn", localRuleInbound.getString(1))
        assertEngineStarts(preparedWithLocalRouting.toString())
    }

    @Test
    fun currentSchemaRealityConfigCanBeImported() {
        val raw = """
            {
              "log": {"level": "info"},
              "inbounds": [{
                "tag": "local-mixed",
                "type": "mixed",
                "address": ["127.0.0.1:1080"],
                "network": ["tcp", "udp"]
              }],
              "outbounds": [{
                "tag": "native",
                "type": "native",
                "address": ["[2001:db8::1]:443"],
                "token": "android-import-test",
                "network": ["tcp", "udp"],
                "transport": {"type": "raw"},
                "security": {
                  "type": "reality",
                  "server_name": "example.com",
                  "public_key": "3HNAKQ6cNuB2YDXVmwtMRLKpfGhBnykI2rXDmW9CKT4",
                  "short_id": "00",
                  "spider_x": "/"
                },
                "mux": {"enabled": true}
              }],
              "route": {"default_outbound": "native", "rules": []},
              "dns": {}
            }
        """.trimIndent()

        val imported = ProfileUriCodec.decode(raw).getOrThrow()

        validateImportedProfile(imported)
        assertTrue(profileConnectionIdentity(imported) != null)
        val prepared = JSONObject(imported.toBridgeJson(localListenAddr = "127.0.0.1:1080"))
        val outbound = prepared.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("[2001:db8::1]:443", outbound.getJSONArray("address").getString(0))
        assertEquals("reality", outbound.getJSONObject("security").getString("type"))
        assertFalse(outbound.getJSONObject("security").has("fingerprint"))
    }

    private fun assertEngineStarts(config: String) {
        val engine = Androidbridge.newEngine()
        try {
            engine.configure(withAvailableInboundPorts(config))
            engine.startConfiguredSessionWithDisabledOutbounds("[]")
            assertTrue(engine.status() in setOf("Starting", "Running"))
        } finally {
            engine.close()
        }
    }

    private fun assertEngineHotSwitchesOutbound(config: String, tag: String) {
        val engine = Androidbridge.newEngine()
        try {
            engine.configure(withAvailableInboundPorts(config))
            assertEquals("Stopped", engine.status())
            assertEquals(0L, engine.sessionID())
            val configured = org.json.JSONArray(engine.outboundsStatusJSON())
            assertTrue(configured.length() > 0)
            assertTrue(
                (0 until configured.length())
                    .map(configured::getJSONObject)
                    .all { !it.getBoolean("running") },
            )
            engine.startConfiguredSessionWithDisabledOutbounds(org.json.JSONArray().put(tag).toString())
            val stopped = org.json.JSONArray(engine.outboundsStatusJSON())
            val stoppedStatus = (0 until stopped.length())
                .map(stopped::getJSONObject)
                .first { it.getString("tag") == tag }
            assertFalse(stoppedStatus.getBoolean("running"))

            engine.startOutbound(tag)
            val started = org.json.JSONArray(engine.outboundsStatusJSON())
            val startedStatus = (0 until started.length())
                .map(started::getJSONObject)
                .first { it.getString("tag") == tag }
            assertTrue(startedStatus.getBoolean("running"))

            engine.switchOutbound(tag, false, 1_000)
            assertTrue(engine.status() in setOf("Starting", "Running"))
        } finally {
            engine.close()
        }
    }

    private fun assertOutboundStates(statusJson: String, vararg expected: Pair<String, Boolean>) {
        val statuses = org.json.JSONArray(statusJson)
        val runningByTag = (0 until statuses.length())
            .map(statuses::getJSONObject)
            .associate { it.getString("tag") to it.getBoolean("running") }
        expected.forEach { (tag, running) -> assertEquals("outbound $tag", running, runningByTag[tag]) }
    }

    private fun withAvailableInboundPorts(config: String): String {
        val root = JSONObject(config)
        val inbounds = root.getJSONArray("inbounds")
        val standaloneInboundTag = inbounds.getJSONObject(0).getString("tag")
        root.optJSONObject("route")?.optJSONArray("rules")?.let { rules ->
            for (ruleIndex in 0 until rules.length()) {
                val tags = rules.optJSONObject(ruleIndex)?.optJSONArray("inbound") ?: continue
                for (tagIndex in 0 until tags.length()) {
                    if (tags.optString(tagIndex) == AndroidTunInboundTag) {
                        tags.put(tagIndex, standaloneInboundTag)
                    }
                }
            }
        }
        val reservations = List(inbounds.length()) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
        try {
            reservations.forEachIndexed { index, reservation ->
                val inbound = inbounds.getJSONObject(index)
                inbound.put("address", JSONArray().put("127.0.0.1:${reservation.localPort}"))
            }
            return root.toString()
        } finally {
            reservations.forEach(ServerSocket::close)
        }
    }
}
