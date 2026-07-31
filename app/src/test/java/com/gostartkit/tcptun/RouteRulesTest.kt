package com.tcptun.client

import org.junit.Assert.assertEquals
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
        assertTrue(
            ManagedRouteRule(
                type = ManagedRouteRuleType.IPRange,
                value = "192.0.2.1-192.0.2.10",
            ).isValid(),
        )
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "001.2.3.4").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IPCidr, value = "192.0.2.0/024").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IPCidr, value = "192.0.2.0/+24").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IPCidr, value = "192.0.2.0/-0").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IPCidr, value = "192.0.2.0/١").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IPCidr, value = "192.0.2.0/２").isValid())
        assertFalse(ManagedRouteRule(type = ManagedRouteRuleType.IP, value = "١.٢.٣.٤").isValid())
        assertFalse(
            ManagedRouteRule(
                type = ManagedRouteRuleType.IPRange,
                value = "192.0.2.10-192.0.2.1",
            ).isValid(),
        )
    }

    @Test
    fun historicalEnabledOverflowIsDisabledWithoutDroppingOrReorderingRules() {
        val stored = List(MaxActiveManagedRouteRuleCount + 3) { index ->
            ManagedRouteRule(
                id = "rule-$index",
                value = "host$index.example",
                enabled = index != 100,
            )
        }

        val normalized = disableOverflowEnabledRouteRules(stored)

        assertEquals(stored.map(ManagedRouteRule::id), normalized.rules.map(ManagedRouteRule::id))
        assertEquals(MaxActiveManagedRouteRuleCount, normalized.rules.count(ManagedRouteRule::enabled))
        assertFalse(normalized.rules[100].enabled)
        assertFalse(normalized.rules[MaxActiveManagedRouteRuleCount + 1].enabled)
        assertFalse(normalized.rules[MaxActiveManagedRouteRuleCount + 2].enabled)
        assertEquals(2, normalized.disabledOverflowCount)
        assertEquals(0, disableOverflowEnabledRouteRules(normalized.rules).disabledOverflowCount)
    }

    @Test
    fun historicalRuntimePayloadOverflowDisablesTrailingRulesWithoutDroppingThem() {
        val stored = List(64) { index ->
            ManagedRouteRule(
                id = "large-rule-$index",
                type = ManagedRouteRuleType.DomainRegex,
                value = "a".repeat(MaxManagedRouteRuleValueLength - 20) + index,
            )
        }

        val normalized = disableOverflowEnabledRouteRules(stored)

        assertEquals(stored.map(ManagedRouteRule::id), normalized.rules.map(ManagedRouteRule::id))
        assertTrue(normalized.disabledOverflowCount > 0)
        assertTrue(
            estimatedEnabledRouteRuntimePayloadLength(normalized.rules) <=
                MaxEnabledManagedRouteRuntimePayloadLength,
        )
        assertEquals(0, disableOverflowEnabledRouteRules(normalized.rules).disabledOverflowCount)
    }

    @Test
    fun runtimePayloadEstimateAccountsForJsonEscapeExpansion() {
        val quotes = "\"".repeat(MaxManagedRouteRuleValueLength)
        val controls = "\u0001".repeat(MaxManagedRouteRuleValueLength)

        assertTrue(escapedJsonStringLength(quotes) > quotes.length)
        assertEquals(2L + controls.length * 6L, escapedJsonStringLength(controls))
        val normalized = disableOverflowEnabledRouteRules(
            List(42) { index ->
                ManagedRouteRule(
                    id = "escaped-$index",
                    type = ManagedRouteRuleType.DomainRegex,
                    value = quotes,
                )
            },
        )
        assertTrue(normalized.disabledOverflowCount > 0)
    }

    @Test
    fun domainRegexAcceptsSyntaxSharedByJvmAndGo() {
        val supported = listOf(
            """(?i:^(?:api\.)?(?<label>[a-z]+)\.example\.com$)""",
            """^service-[0-9]+?\.example\.com$""",
            """^\p{L}[\p{L}\p{N}.-]+$""",
            """^\p{Lu}\p{Ll}+$""",
        )

        supported.forEach { pattern ->
            assertTrue(pattern, ManagedRouteRule(type = ManagedRouteRuleType.DomainRegex, value = pattern).isValid())
        }
    }

    @Test
    fun domainRegexRejectsJvmOnlyOrUnsupportedGoSyntax() {
        val unsupported = listOf(
            """foo(?=bar)""",
            """foo(?!bar)""",
            """(?<=foo)bar""",
            """(?<!foo)bar""",
            """^(foo)\1$""",
            """(?<part>foo)\k<part>""",
            """(?>foo)""",
            """foo++""",
            """(?x)foo""",
            """(?U)foo""",
            """[a-z&&[^aeiou]]+""",
            """a{1001}""",
            """\N{LATIN SMALL LETTER A}""",
            """\p{javaLowerCase}+""",
            """\p{IsLatin}+""",
            """\u0061""",
            """[\Q-\E]""",
            """[a[b]]""",
            """[[:alpha:]]+""",
            """a{01}""",
        )

        unsupported.forEach { pattern ->
            assertFalse(pattern, ManagedRouteRule(type = ManagedRouteRuleType.DomainRegex, value = pattern).isValid())
        }
    }
}
