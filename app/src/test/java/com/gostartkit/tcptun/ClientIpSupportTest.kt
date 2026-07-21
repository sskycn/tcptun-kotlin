package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun callbackInputIsBoundedBeforeSorting() {
        var inspected = 0
        val values = Iterable {
            object : Iterator<String> {
                override fun hasNext(): Boolean = inspected < MAX_CLIENT_IP_CANDIDATES + 100

                override fun next(): String {
                    inspected += 1
                    return if (inspected <= MAX_CLIENT_IP_CANDIDATES) "x".repeat(129) else "10.1.0.1"
                }
            }
        }

        val normalized = normalizeClientIps(values)

        assertEquals(MAX_CLIENT_IP_CANDIDATES, inspected)
        assertEquals(emptyList<String>(), normalized)
    }

    @Test
    fun malformedOrFailingCallbackInputIsDiscarded() {
        assertFalse(normalizeClientIps(listOf("1".repeat(129))).isNotEmpty())
        val failing = Iterable<String> { error("callback failed") }

        assertEquals(emptyList<String>(), normalizeClientIps(failing))
    }
}
