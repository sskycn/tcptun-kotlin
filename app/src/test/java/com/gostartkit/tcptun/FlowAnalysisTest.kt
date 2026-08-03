package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun suggestionsMergeSiblingSubdomainsButKeepSingleDomainsExact() {
        val events = listOf(
            flowEvent(domain = "api.example.com"),
            flowEvent(domain = "cdn.example.com"),
            flowEvent(domain = "only.example.net"),
            flowEvent(domain = "a.service.co.uk"),
            flowEvent(domain = "b.service.co.uk"),
        )

        val suggestions = buildFlowRouteRuleSuggestions(events, ManagedRouteOutbound.Proxy)

        assertEquals(
            listOf(
                ManagedRouteRuleType.DomainSuffix to "example.com",
                ManagedRouteRuleType.Domain to "only.example.net",
                ManagedRouteRuleType.DomainSuffix to "service.co.uk",
            ),
            suggestions.map { it.type to it.value },
        )
        assertTrue(suggestions.all { it.outbound == ManagedRouteOutbound.Proxy })
    }

    @Test
    fun suggestionsMergeIpsIntoConservativeSubnets() {
        val events = listOf(
            flowEvent(ip = "203.0.113.4"),
            flowEvent(ip = "203.0.113.5"),
            flowEvent(ip = "203.0.114.8"),
            flowEvent(ip = "2001:db8::10"),
            flowEvent(ip = "2001:db8::11"),
        )

        val suggestions = buildFlowRouteRuleSuggestions(events, ManagedRouteOutbound.Direct)

        assertEquals(
            listOf(
                ManagedRouteRuleType.IP to "203.0.114.8",
                ManagedRouteRuleType.IPCidr to "2001:db8:0:0:0:0:0:10/127",
                ManagedRouteRuleType.IPCidr to "203.0.113.4/31",
            ).sortedWith(compareBy({ it.first.ordinal }, { it.second })),
            suggestions.map { it.type to it.value },
        )
        assertTrue(suggestions.all { it.outbound == ManagedRouteOutbound.Direct })
    }

    @Test
    fun mergedSuggestionsMoveToTopAndReuseStoredIds() {
        val stored = listOf(
            ManagedRouteRule(id = "other", value = "other.example"),
            ManagedRouteRule(
                id = "existing",
                type = ManagedRouteRuleType.DomainSuffix,
                value = "example.com",
                outbound = ManagedRouteOutbound.Proxy,
            ),
        )
        val generated = listOf(
            ManagedRouteRule(
                type = ManagedRouteRuleType.DomainSuffix,
                value = "example.com",
                outbound = ManagedRouteOutbound.Direct,
            ),
        )

        val merged = mergeFlowRouteRuleSuggestions(stored, generated)

        assertEquals(listOf("existing", "other"), merged.map(ManagedRouteRule::id))
        assertEquals(ManagedRouteOutbound.Direct, merged.first().outbound)
        assertTrue(merged.first().enabled)
    }

    private fun flowEvent(domain: String = "", ip: String = ""): FlowAnalysisEvent = FlowAnalysisEvent(
        sessionId = 1,
        sequence = 1,
        droppedEvents = 0,
        timestampMs = 1,
        type = "connected",
        network = "tcp",
        source = "10.0.0.2:1234",
        destination = if (domain.isNotBlank()) "$domain:443" else "$ip:443",
        domain = domain,
        ip = ip,
        originalIp = "",
        port = 443,
        outboundTag = "direct",
        routeReason = "",
        appId = "com.example.target",
    )

}
