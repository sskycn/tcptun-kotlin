package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProxyProtocolTest {
    @Test
    fun socks5IsTheDefaultLocalProxyProtocol() {
        assertEquals("socks5", DefaultLocalProxyProtocol)
        assertEquals(listOf("socks5", "mixed"), LocalProxyProtocols)
    }

    @Test
    fun localProxyProtocolIsNormalizedAndInvalidValuesFallBackToSocks5() {
        assertEquals("mixed", normalizeLocalProxyProtocol(" MIXED "))
        assertEquals("socks5", normalizeLocalProxyProtocol("socks5"))
        assertEquals("socks5", normalizeLocalProxyProtocol("http"))
        assertEquals("socks5", normalizeLocalProxyProtocol(""))
    }
}
