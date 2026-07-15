package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigMaskedAddressTest {
    @Test
    fun ipv4AddressKeepsOnlyLastOctet() {
        assertEquals("***.***.***.42 : 443", profile("192.168.1.42").maskedAddress())
        assertEquals("***.***.***.1 : 443", profile("1.1.1.1").maskedAddress())
    }

    @Test
    fun ipv6AddressKeepsOnlyLastSegment() {
        assertEquals("***:42 : 443", profile("2001:db8:abcd::42").maskedAddress())
        assertEquals("***:42 : 443", profile("[2001:db8:abcd::42]").maskedAddress())
        assertEquals("***:0 : 443", profile("2001:db8::").maskedAddress())
    }

    @Test
    fun hostnameKeepsExistingMaskingBehavior() {
        assertEquals("proxy.exam.*** : 443", profile("proxy.example.com").maskedAddress())
        assertEquals("localhost.*** : 443", profile("localhost").maskedAddress())
    }

    private fun profile(host: String) = AppConfig(
        serverHost = host,
        serverPort = "443",
    )
}
