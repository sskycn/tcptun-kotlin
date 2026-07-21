package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRulesTest {
    @Test
    fun numericIpValidationRejectsHostLikeHexWithoutDnsLookup() {
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "deadbeef").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "256.1.1.1").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "1.2.3").isValid())
    }

    @Test
    fun numericIpValidationAcceptsIpv4AndIpv6Literals() {
        assertTrue(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "192.0.2.1").isValid())
        assertTrue(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "2001:db8::1").isValid())
    }
}
