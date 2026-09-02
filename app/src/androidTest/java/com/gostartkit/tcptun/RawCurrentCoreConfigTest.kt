package com.tcptun.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCurrentCoreConfigTest {
    @Test
    fun reverseSubnetTopologySurvivesPersistenceAndAndroidPreparation() {
        val profile = AppConfig(name = "reverse subnet", rawConfigJson = reverseSubnetConfig)

        assertEquals(null, profile.validate())
        val restored = AppConfig.fromJson(profile.toJson())
        assertEquals(profile.rawConfigJson, restored.rawConfigJson)
        val planRestored = ProfileRunPlan.fromJson(
            ProfileRunPlan(listOf(restored), setOf(restored.id)).toJson(),
        ).profiles.single()
        assertEquals(profile.rawConfigJson, planRestored.rawConfigJson)

        val root = JSONObject(planRestored.toBridgeJson("127.0.0.1:18080"))
        val server = root.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("rules", server.getString("route_mode"))
        assertEquals("alice", server.getJSONArray("users").getJSONObject(0).getString("principal"))
        val subnet = server.getJSONArray("subnets").getJSONObject(0)
        assertEquals("home-connector", subnet.getJSONArray("principals").getString(0))
        assertEquals("fd12:3456:789a::/64", subnet.getJSONArray("cidrs").getString(1))
        assertEquals("80-81", subnet.getJSONObject("ports").getJSONArray("tcp").getString(1))
        assertEquals(
            "alice",
            root.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
                .getJSONArray("principals").getString(0),
        )
        assertEquals(
            "remote-access",
            root.getJSONArray("outbounds").getJSONObject(1)
                .getJSONObject("reverse_subnet").getString("inbound"),
        )
        assertEquals(8_388_608L, root.getJSONObject("resources").getLong("resumable_buffer_budget"))
    }

    @Test
    fun p2pRemoteFieldsAndEndpointNetworkCapabilityAreNotRewritten() {
        val root = JSONObject(
            AppConfig(name = "p2p remote", rawConfigJson = p2pRemoteConfig)
                .toBridgeJson("127.0.0.1:18081"),
        )
        val outbound = root.getJSONArray("outbounds").getJSONObject(1)
        val p2p = outbound.getJSONObject("p2p")

        assertTrue(p2p.getBoolean("enabled"))
        assertTrue(p2p.getBoolean("host_candidates"))
        assertEquals("203.0.113.10:9555", p2p.getJSONArray("rendezvous").getString(0))
        assertEquals("[2001:db8::10]:9555", p2p.getJSONArray("rendezvous").getString(1))
        assertEquals("stun.example.com:3478", p2p.getJSONArray("stun").getString(0))
        assertEquals(1, outbound.getJSONArray("network").length())
        assertEquals("tcp", outbound.getJSONArray("network").getString(0))
        assertFalse(outbound.getJSONArray("network").toString().contains("udp"))
        assertNotNull(outbound.getJSONArray("export_subnets"))
    }

    private companion object {
        val reverseSubnetConfig = """
            {
              "inbounds": [{
                "tag": "remote-access", "type": "native", "address": ["127.0.0.1:19444"],
                "users": [{"principal": "alice", "id": "REMOTE_TOKEN"}],
                "transport": {"type": "raw"}, "mux": {"enabled": true}, "route_mode": "rules",
                "subnets": [{"name": "home", "principals": ["home-connector"],
                  "cidrs": ["192.168.50.0/24", "fd12:3456:789a::/64"],
                  "network": ["tcp", "udp"], "ports": {"tcp": ["22", "80-81"], "udp": ["53"]}}]
              }],
              "outbounds": [
                {"tag": "deny", "type": "blackhole"},
                {"tag": "home-lan", "type": "reverse_subnet",
                 "reverse_subnet": {"inbound": "remote-access", "site": "home"}}
              ],
              "route": {"default_outbound": "deny", "rules": [{
                "inbound": ["remote-access"], "principals": ["alice"],
                "network": ["tcp", "udp"],
                "ip_cidrs": ["192.168.50.0/24", "fd12:3456:789a::/64"], "outbound": "home-lan"
              }]},
              "resources": {"resumable_buffer_budget": 8388608, "mux_receive_buffer_budget": 16777216}
            }
        """.trimIndent()

        val p2pRemoteConfig = """
            {
              "inbounds": [],
              "outbounds": [
                {"tag": "direct", "type": "direct"},
                {"tag": "remote-edge", "type": "native", "address": ["edge.example.com:9444"],
                 "token": "REMOTE_TOKEN", "network": ["tcp"],
                 "transport": {"type": "raw"},
                 "security": {"type": "tls", "server_name": "edge.example.com"},
                 "mux": {"enabled": true},
                 "export_subnets": [{"name": "home", "cidrs": ["192.168.50.0/24"], "network": ["tcp"]}],
                 "p2p": {"enabled": true,
                   "rendezvous": ["203.0.113.10:9555", "[2001:db8::10]:9555"],
                   "host_candidates": true, "stun": ["stun.example.com:3478"]}}
              ],
              "route": {"default_outbound": "direct", "rules": [{
                "network": ["tcp"], "ip_cidrs": ["192.168.50.0/24"], "outbound": "remote-edge"
              }]}
            }
        """.trimIndent()
    }
}
