package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVpnRoutePlanTest {
    @Test
    fun fullTunnelCompilesDualStackDefaultRoutesAndVpnDns() {
        val compiled = compileAndroidVpnRoutePlan(
            AndroidVpnRoutePlan.FullTunnel,
            coreConfigJson = "{}",
        )

        assertEquals("full", compiled.mode)
        assertEquals(listOf("0.0.0.0/0", "0:0:0:0:0:0:0:0/0"), compiled.routes.map(String::valueOf))
        assertEquals(listOf(AndroidVpnDnsAddress), compiled.dnsServers)
        assertTrue(compiled.fakeIpRoutes.isEmpty())
    }

    @Test
    fun legacySplitPreferenceMigratesToFullTunnel() {
        val legacy = """{"mode":"split","routes":["192.168.50.0/24"],"dnsServers":["192.168.50.1"]}"""

        assertEquals(AndroidVpnRoutePlan.FullTunnel, decodeAndroidVpnRoutePlan(legacy))
        assertEquals("full", JSONObjectCompat.mode(encodeAndroidVpnRoutePlan(decodeAndroidVpnRoutePlan(legacy))))
    }

    @Test
    fun rejectsUnknownPersistedRouteMode() {
        assertTrue(runCatching { decodeAndroidVpnRoutePlan("""{"mode":"future"}""") }.isFailure)
    }

    private object JSONObjectCompat {
        fun mode(json: String): String = org.json.JSONObject(json).getString("mode")
    }
}
