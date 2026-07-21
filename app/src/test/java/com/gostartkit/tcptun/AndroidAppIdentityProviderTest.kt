package com.tcptun.client

import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAppIdentityProviderTest {
    @Test
    fun parsesTcpIpv4ProxySource() {
        val flow = parseProxyFlowSource("tcp", "127.0.0.1:54321")

        assertEquals(OsConstants.IPPROTO_TCP, flow?.protocol)
        assertEquals("127.0.0.1", flow?.address?.hostAddress)
        assertEquals(54321, flow?.port)
    }

    @Test
    fun parsesUdpIpv6ProxySource() {
        val flow = parseProxyFlowSource("udp", "[::1]:12345")

        assertEquals(OsConstants.IPPROTO_UDP, flow?.protocol)
        assertEquals(12345, flow?.port)
    }

    @Test
    fun rejectsUnsupportedOrMalformedSources() {
        assertNull(parseProxyFlowSource("icmp", "127.0.0.1:1"))
        assertNull(parseProxyFlowSource("tcp", "not-an-endpoint"))
        assertNull(parseProxyFlowSource("tcp", "example.com:443"))
        assertNull(parseProxyFlowSource("tcp", "[]:443"))
        assertNull(parseProxyFlowSource("tcp", "999.0.0.1:443"))
        assertNull(parseProxyFlowSource("tcp", "127.0.0.1:+443"))
        assertNull(parseProxyFlowSource("tcp", "127.0.0.1:65536"))
        assertNull(parseProxyFlowSource("tcp", "[::1]:${"1".repeat(600)}"))
    }
}
