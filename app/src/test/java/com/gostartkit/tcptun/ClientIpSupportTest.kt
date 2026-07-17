package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientIpSupportTest {
    @Test
    fun clientIpsAreTrimmedDeduplicatedAndSorted() {
        assertEquals(
            listOf("10.0.0.2", "192.168.43.20", "2001:db8::1"),
            normalizeClientIps(
                listOf(" 192.168.43.20 ", "2001:db8::1", "10.0.0.2", "192.168.43.20", ""),
            ),
        )
    }

    @Test
    fun clientIpListIsBounded() {
        val values = (1..300).map { index -> "10.0.${index / 255}.${index % 255}" }

        assertEquals(MAX_DISPLAYED_CLIENT_IPS, normalizeClientIps(values).size)
    }
}
