package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class MixedListenerNetworkDisplayTest {
    @Test
    fun loopbackListenerDisplaysLoopbackWithoutGateway() {
        val display = mixedListenerNetworkDisplay(
            listenAddress = "127.0.0.1:1080",
            underlyingIpv4 = "192.168.1.20/24",
            underlyingGatewayIpv4 = "192.168.1.1",
        )

        assertEquals(MixedListenerNetworkDisplay("127.0.0.1", ""), display)
    }

    @Test
    fun wildcardListenerDisplaysDefaultNetworkAddressAndGateway() {
        val display = mixedListenerNetworkDisplay(
            listenAddress = "0.0.0.0:1080",
            underlyingIpv4 = "192.168.1.20/24\n198.51.100.10/32",
            underlyingGatewayIpv4 = "192.168.1.1\n198.51.100.1",
        )

        assertEquals(MixedListenerNetworkDisplay("192.168.1.20", "192.168.1.1"), display)
    }

    @Test
    fun wildcardListenerPrefersHotspotAddress() {
        val display = mixedListenerNetworkDisplay(
            listenAddress = "0.0.0.0:1080",
            underlyingIpv4 = "10.0.2.16/24",
            underlyingGatewayIpv4 = "10.0.2.2",
            hotspotIpv4 = "192.168.43.1",
        )

        assertEquals(MixedListenerNetworkDisplay("192.168.43.1", ""), display)
    }

    @Test
    fun localListenerIsReportedAsUnavailableToHotspotClients() {
        val display = proxyAccessDisplay(
            listenAddress = "127.0.0.1:1080",
            hotspotIpv4 = "192.168.43.1",
            underlyingIpv4 = "10.0.2.16/24",
            proxyRunning = true,
        )

        assertEquals(ProxyAccessDisplay("127.0.0.1:1080", ProxyAccessScope.LocalOnly), display)
    }

    @Test
    fun wildcardProxyAddressUsesHotspotAddressAndPort() {
        val display = proxyAccessDisplay(
            listenAddress = "0.0.0.0:1080",
            hotspotIpv4 = "192.168.43.1",
            underlyingIpv4 = "10.0.2.16/24",
            proxyRunning = true,
        )

        assertEquals(ProxyAccessDisplay("192.168.43.1:1080", ProxyAccessScope.Hotspot), display)
    }

    @Test
    fun stoppedProxyIsNotReportedAsAccessible() {
        val display = proxyAccessDisplay(
            listenAddress = "0.0.0.0:1080",
            hotspotIpv4 = "192.168.43.1",
            underlyingIpv4 = "10.0.2.16/24",
            proxyRunning = false,
        )

        assertEquals(ProxyAccessDisplay("192.168.43.1:1080", ProxyAccessScope.NotRunning), display)
    }

    @Test
    fun explicitNetworkListenerDisplaysItsMatchingGateway() {
        val display = mixedListenerNetworkDisplay(
            listenAddress = "192.168.1.20:1080",
            underlyingIpv4 = "192.168.1.20/24",
            underlyingGatewayIpv4 = "192.168.1.1",
        )

        assertEquals(MixedListenerNetworkDisplay("192.168.1.20", "192.168.1.1"), display)
    }
}
