package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVpnRoutePlanTest {
    @Test
    fun canonicalizesAndDeduplicatesDualStackRoutes() {
        val plan = normalizeAndroidVpnRoutePlan(
            AndroidVpnRoutePlan.SplitTunnel(
                routes = listOf(
                    IpPrefix.parse("192.168.50.99/24"),
                    IpPrefix.parse("192.168.50.0/24"),
                    IpPrefix.parse("fd12:3456:789a::1234/64"),
                ),
                dnsServers = listOf("192.168.50.1", "fd12:3456:789a::1"),
            ),
        ) as AndroidVpnRoutePlan.SplitTunnel

        assertEquals(2, plan.routes.size)
        assertEquals("192.168.50.0/24", plan.routes[0].toString())
        assertTrue(plan.routes[1].contains("fd12:3456:789a::beef"))
        assertEquals(2, plan.dnsServers.size)
    }

    @Test
    fun rejectsInvalidAndImplicitDefaultSplitRoutes() {
        listOf("192.168.1.1", "192.168.1.0/33", "example.com/24", "fd00::/129").forEach { value ->
            assertTrue(value, runCatching { IpPrefix.parse(value) }.isFailure)
        }
        listOf("0.0.0.0/0", "::/0").forEach { value ->
            assertTrue(
                runCatching {
                    normalizeAndroidVpnRoutePlan(
                        AndroidVpnRoutePlan.SplitTunnel(listOf(IpPrefix.parse(value))),
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun prefixContainmentHandlesIpv4AndIpv6Families() {
        val ipv4 = IpPrefix.parse("192.168.50.17/24")
        val ipv6 = IpPrefix.parse("fd12:3456:789a::17/64")

        assertTrue(ipv4.contains("192.168.50.200"))
        assertFalse(ipv4.contains("192.168.51.1"))
        assertFalse(ipv4.contains("fd12:3456:789a::1"))
        assertTrue(ipv6.contains("fd12:3456:789a::ffff"))
        assertFalse(ipv6.contains("fd12:3456:789b::1"))
    }

    @Test
    fun splitDraftParsesFamiliesAndRemainsBounded() {
        val plan = parseSplitTunnelRoutePlan(
            ipv4Cidrs = "192.168.50.99/24, 10.20.0.0/16",
            ipv6Cidrs = "fd12:3456:789a::1/64",
            dnsServers = "192.168.50.1 fd12:3456:789a::53",
        )

        assertEquals(3, plan.routes.size)
        assertEquals("192.168.50.0/24", plan.routes[0].toString())
        assertEquals(2, plan.dnsServers.size)
        assertTrue(runCatching { parseSplitTunnelRoutePlan("fd00::/64", "", "") }.isFailure)
        assertTrue(runCatching { parseSplitTunnelRoutePlan("", "192.168.1.0/24", "") }.isFailure)
        assertTrue(
            runCatching {
                parseSplitTunnelRoutePlan("192.168.50.0/24", "", "192.168.60.1")
            }.isFailure,
        )
    }
}
