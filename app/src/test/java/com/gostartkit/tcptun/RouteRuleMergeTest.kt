package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRuleMergeTest {
    @Test
    fun mergesSiblingDomainsWithSameBehaviorAtEarliestPosition() {
        val rules = listOf(
            ManagedRouteRule(id = "before", value = "before.example"),
            ManagedRouteRule(id = "api", type = ManagedRouteRuleType.Domain, value = "api.example.com"),
            ManagedRouteRule(id = "middle", value = "middle.test"),
            ManagedRouteRule(id = "cdn", type = ManagedRouteRuleType.Domain, value = "cdn.example.com"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertTrue(result.changed)
        assertEquals(1, result.removedRuleCount)
        assertEquals(listOf("before", "api", "middle"), result.rules.map(ManagedRouteRule::id))
        assertEquals(ManagedRouteRuleType.DomainSuffix, result.rules[1].type)
        assertEquals("example.com", result.rules[1].value)
    }

    @Test
    fun mergesNearbyIpsAndAbsorbsEquivalentCidr() {
        val rules = listOf(
            ManagedRouteRule(id = "ip-1", type = ManagedRouteRuleType.IP, value = "203.0.113.4"),
            ManagedRouteRule(id = "cidr", type = ManagedRouteRuleType.IPCidr, value = "203.0.113.4/31"),
            ManagedRouteRule(id = "ip-2", type = ManagedRouteRuleType.IP, value = "203.0.113.5"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertEquals(1, result.rules.size)
        assertEquals("ip-1", result.rules.single().id)
        assertEquals(ManagedRouteRuleType.IPCidr, result.rules.single().type)
        assertEquals("203.0.113.4/31", result.rules.single().value)
        assertEquals(2, result.removedRuleCount)
    }

    @Test
    fun mergesSiblingDomainSuffixRulesUsedByTheDefaultEditorType() {
        val rules = listOf(
            ManagedRouteRule(id = "api", value = "api.example.com"),
            ManagedRouteRule(id = "cdn", value = "cdn.example.com"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertEquals(1, result.rules.size)
        assertEquals("api", result.rules.single().id)
        assertEquals(ManagedRouteRuleType.DomainSuffix, result.rules.single().type)
        assertEquals("example.com", result.rules.single().value)
    }

    @Test
    fun doesNotMergeRulesWithDifferentOutboundsOrProfiles() {
        val rules = listOf(
            ManagedRouteRule(
                id = "proxy",
                type = ManagedRouteRuleType.Domain,
                value = "api.example.com",
                outbound = ManagedRouteOutbound.Proxy,
            ),
            ManagedRouteRule(
                id = "direct",
                type = ManagedRouteRuleType.Domain,
                value = "cdn.example.com",
                outbound = ManagedRouteOutbound.Direct,
            ),
            ManagedRouteRule(
                id = "profile-a",
                type = ManagedRouteRuleType.IP,
                value = "203.0.113.4",
                outboundProfileId = "profile-a",
            ),
            ManagedRouteRule(
                id = "profile-b",
                type = ManagedRouteRuleType.IP,
                value = "203.0.113.5",
                outboundProfileId = "profile-b",
            ),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertFalse(result.changed)
        assertEquals(rules, result.rules)
    }

    @Test
    fun removesDuplicateExactRulesWithoutWidening() {
        val rules = listOf(
            ManagedRouteRule(id = "first", type = ManagedRouteRuleType.Domain, value = "api.example.com"),
            ManagedRouteRule(id = "duplicate", type = ManagedRouteRuleType.Domain, value = "api.example.com"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertEquals(1, result.rules.size)
        assertEquals("first", result.rules.single().id)
        assertEquals(ManagedRouteRuleType.Domain, result.rules.single().type)
        assertEquals("api.example.com", result.rules.single().value)
    }
}
