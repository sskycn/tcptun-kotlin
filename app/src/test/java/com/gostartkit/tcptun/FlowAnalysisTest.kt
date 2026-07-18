package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowAnalysisTest {
    @Test
    fun displayDestinationPrefersDomainThenIp() {
        val base = FlowAnalysisEvent(
            sessionId = 1,
            sequence = 1,
            droppedEvents = 0,
            timestampMs = 1,
            type = "connected",
            network = "tcp",
            source = "10.0.0.2:1234",
            destination = "example.com:443",
            domain = "example.com",
            ip = "",
            originalIp = "198.18.0.1",
            port = 443,
            outboundTag = "direct",
            routeReason = "rule[0]",
            appId = "com.example.target",
        )

        assertEquals("example.com", base.displayDestination)
        assertEquals("203.0.113.4", base.copy(domain = "", ip = "203.0.113.4").displayDestination)
    }

    @Test
    fun normalizesAnalysisPackageNames() {
        assertEquals("com.example.app", normalizeFlowAnalysisApp(" com.example.app "))
        assertEquals("", normalizeFlowAnalysisApp("bad package"))
        assertEquals("", normalizeFlowAnalysisApp(""))
    }

}
