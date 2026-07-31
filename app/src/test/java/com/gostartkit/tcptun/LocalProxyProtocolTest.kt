package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyProtocolTest {
    @Test
    fun socksCredentialsAreBoundedByUtf8BytesWithoutSplittingCodePoints() {
        assertTrue(hasValidSocksCredentialSize("a".repeat(MaxSocksCredentialUtf8Bytes)))
        assertFalse(hasValidSocksCredentialSize("a".repeat(MaxSocksCredentialUtf8Bytes + 1)))

        val emoji = "😀".repeat(100)
        val truncated = truncateSocksCredential(emoji)
        assertEquals(63, truncated.codePointCount(0, truncated.length))
        assertEquals(252, truncated.toByteArray(Charsets.UTF_8).size)
        assertTrue(hasValidSocksCredentialSize(truncated))
    }

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
