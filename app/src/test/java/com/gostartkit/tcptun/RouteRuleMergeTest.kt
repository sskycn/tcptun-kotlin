package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RouteRuleMergeTest {
    @Test
    fun doesNotWidenSiblingDomainsWithoutAConflictProof() {
        val rules = listOf(
            ManagedRouteRule(id = "before", value = "before.example"),
            ManagedRouteRule(id = "api", type = ManagedRouteRuleType.Domain, value = "api.example.com"),
            ManagedRouteRule(id = "middle", value = "middle.test"),
            ManagedRouteRule(id = "cdn", type = ManagedRouteRuleType.Domain, value = "cdn.example.com"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertFalse(result.changed)
        assertEquals(rules, result.rules)
    }

    @Test
    fun doesNotMoveCidrAheadOfItsConfiguredPosition() {
        val rules = listOf(
            ManagedRouteRule(id = "ip-1", type = ManagedRouteRuleType.IP, value = "203.0.113.4"),
            ManagedRouteRule(id = "cidr", type = ManagedRouteRuleType.IPCidr, value = "203.0.113.4/31"),
            ManagedRouteRule(id = "ip-2", type = ManagedRouteRuleType.IP, value = "203.0.113.5"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertFalse(result.changed)
        assertEquals(rules, result.rules)
    }

    @Test
    fun doesNotWidenSiblingDomainSuffixRules() {
        val rules = listOf(
            ManagedRouteRule(id = "api", value = "api.example.com"),
            ManagedRouteRule(id = "cdn", value = "cdn.example.com"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertFalse(result.changed)
        assertEquals(rules, result.rules)
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

    @Test
    fun removesDuplicateExactIpsWithoutWidening() {
        val rules = listOf(
            ManagedRouteRule(id = "first", type = ManagedRouteRuleType.IP, value = "203.0.113.7"),
            ManagedRouteRule(id = "duplicate", type = ManagedRouteRuleType.IP, value = "203.0.113.7"),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertEquals(1, result.rules.size)
        assertEquals("first", result.rules.single().id)
        assertEquals(ManagedRouteRuleType.IP, result.rules.single().type)
        assertEquals("203.0.113.7", result.rules.single().value)
    }

    @Test
    fun doesNotWidenSiblingDomainsAcrossNonParticipatingRule() {
        val rules = listOf(
            ManagedRouteRule(
                id = "api-proxy",
                type = ManagedRouteRuleType.Domain,
                value = "api.example.com",
                outbound = ManagedRouteOutbound.Proxy,
            ),
            ManagedRouteRule(
                id = "x-direct",
                type = ManagedRouteRuleType.Domain,
                value = "x.example.com",
                outbound = ManagedRouteOutbound.Direct,
            ),
            ManagedRouteRule(
                id = "cdn-proxy",
                type = ManagedRouteRuleType.Domain,
                value = "cdn.example.com",
                outbound = ManagedRouteOutbound.Proxy,
            ),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertFalse(result.changed)
        assertEquals(rules, result.rules)
    }

    @Test
    fun doesNotWidenExactIpsAcrossNonParticipatingRule() {
        val rules = listOf(
            ManagedRouteRule(
                id = "first-proxy",
                type = ManagedRouteRuleType.IP,
                value = "10.0.0.1",
                outbound = ManagedRouteOutbound.Proxy,
            ),
            ManagedRouteRule(
                id = "middle-direct",
                type = ManagedRouteRuleType.IP,
                value = "10.0.0.100",
                outbound = ManagedRouteOutbound.Direct,
            ),
            ManagedRouteRule(
                id = "last-proxy",
                type = ManagedRouteRuleType.IP,
                value = "10.0.0.200",
                outbound = ManagedRouteOutbound.Proxy,
            ),
        )

        val result = smartMergeManagedRouteRules(rules)

        assertFalse(result.changed)
        assertEquals(rules, result.rules)
    }
}
