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
        assertEquals(2, compiled.routes.size)
        assertTrue(compiled.routes.any { it.isIpv4 && it.prefixLength == 0 })
        assertTrue(compiled.routes.any { !it.isIpv4 && it.prefixLength == 0 })
        assertEquals(listOf(AndroidVpnDnsAddress), compiled.dnsServers)
        assertTrue(compiled.fakeIpRoutes.isEmpty())
    }

    @Test
    fun legacySplitPreferenceMigratesToFullTunnel() {
        assertEquals(AndroidVpnRoutePlan.FullTunnel, decodeAndroidVpnRouteMode("split"))
        assertEquals(AndroidVpnRoutePlan.FullTunnel, decodeAndroidVpnRouteMode("full"))
    }

    @Test
    fun rejectsUnknownPersistedRouteMode() {
        assertTrue(runCatching { decodeAndroidVpnRouteMode("future") }.isFailure)
    }
}
