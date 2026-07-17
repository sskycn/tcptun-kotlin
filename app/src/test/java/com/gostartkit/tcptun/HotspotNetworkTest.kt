package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotNetworkTest {
    @Test
    fun explicitTetheringInterfaceIsPreferred() {
        val selected = selectHotspotIpv4Address(
            addresses = listOf(
                InterfaceIpv4Address("wlan0", "10.0.2.16"),
                InterfaceIpv4Address("vendor_ap42", "192.168.43.1"),
            ),
            tetheredInterfaceNames = setOf("vendor_ap42"),
            excludedInterfaceNames = setOf("wlan0"),
        )

        assertEquals(InterfaceIpv4Address("vendor_ap42", "192.168.43.1"), selected)
    }

    @Test
    fun legacyFallbackExcludesUpstreamAndFindsWifiHotspot() {
        val selected = selectHotspotIpv4Address(
            addresses = listOf(
                InterfaceIpv4Address("rmnet_data0", "10.10.20.30"),
                InterfaceIpv4Address("wlan0", "192.168.43.1"),
                InterfaceIpv4Address("dummy0", "192.168.0.4"),
            ),
            tetheredInterfaceNames = null,
            excludedInterfaceNames = setOf("rmnet_data0"),
        )

        assertEquals(InterfaceIpv4Address("wlan0", "192.168.43.1"), selected)
    }

    @Test
    fun knownEmptyTetheringSetDoesNotGuess() {
        val selected = selectHotspotIpv4Address(
            addresses = listOf(InterfaceIpv4Address("wlan0", "192.168.43.1")),
            tetheredInterfaceNames = emptySet(),
            excludedInterfaceNames = emptySet(),
        )

        assertNull(selected)
    }
}
