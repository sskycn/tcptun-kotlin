package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProxyHealthProbeTest {
    @Test
    fun upstreamTargetsRotateWithoutChangingPriorityWithinACycle() {
        val first = UpstreamProbeTarget("first", "first.example")
        val second = UpstreamProbeTarget("second", "second.example")
        val third = UpstreamProbeTarget("third", "third.example")
        val probe = LocalProxyHealthProbe(targets = listOf(first, second, third))

        assertEquals(listOf(first, second, third), probe.orderedTargets())
        assertEquals(listOf(second, third, first), probe.orderedTargets())
        assertEquals(listOf(third, first, second), probe.orderedTargets())
        assertEquals(listOf(first, second, third), probe.orderedTargets())
    }

    @Test
    fun connectAddressUsesConfiguredListener() {
        val probe = LocalProxyHealthProbe(
            localHost = "127.0.0.2",
            targets = listOf(UpstreamProbeTarget("target", "example.com")),
        )

        assertEquals("127.0.0.2:1080", probe.connectAddress(1080))
    }
}
