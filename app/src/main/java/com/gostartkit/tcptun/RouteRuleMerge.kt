package com.tcptun.client

data class SmartRouteMergeGroup(
    val sourceRules: List<ManagedRouteRule>,
    val mergedRule: ManagedRouteRule,
)

data class SmartRouteMergeResult(
    val rules: List<ManagedRouteRule>,
    val groups: List<SmartRouteMergeGroup>,
) {
    val removedRuleCount: Int
        get() = groups.sumOf { it.sourceRules.size - 1 }

    val changed: Boolean
        get() = groups.isNotEmpty()
}

/**
 * Conservatively combines domain and exact IP rules that have identical route
 * behavior. Domain rules are widened only across sibling hosts under the same
 * registrable domain. IP rules are widened only inside one /24 (IPv4) or /64
 * (IPv6) bucket. The replacement stays at the earliest source position so the
 * rest of the user-defined priority order remains stable.
 */
internal fun smartMergeManagedRouteRules(rules: List<ManagedRouteRule>): SmartRouteMergeResult {
    val indexedRules = rules.mapIndexed { index, rule -> IndexedRouteRule(index, rule.normalized()) }
    val domainGroups = buildDomainMergeGroups(indexedRules)
    val ipGroups = buildIpMergeGroups(indexedRules)
    val groups = (domainGroups + ipGroups)
        .sortedBy { group -> group.sources.minOf(IndexedRouteRule::index) }
    if (groups.isEmpty()) return SmartRouteMergeResult(indexedRules.map(IndexedRouteRule::rule), emptyList())

    val consumedIndices = groups.flatMapTo(mutableSetOf()) { group -> group.sources.map(IndexedRouteRule::index) }
    val groupByFirstIndex = groups.associateBy { group -> group.sources.minOf(IndexedRouteRule::index) }
    val mergedRules = buildList {
        indexedRules.forEach { indexed ->
            val group = groupByFirstIndex[indexed.index]
            when {
                group != null -> add(group.mergedRule)
                indexed.index !in consumedIndices -> add(indexed.rule)
            }
        }
    }
    return SmartRouteMergeResult(
        rules = mergedRules,
        groups = groups.map { group ->
            SmartRouteMergeGroup(
                sourceRules = group.sources.sortedBy(IndexedRouteRule::index).map(IndexedRouteRule::rule),
                mergedRule = group.mergedRule,
            )
        },
    )
}

private data class IndexedRouteRule(
    val index: Int,
    val rule: ManagedRouteRule,
)

private data class RouteBehavior(
    val outbound: ManagedRouteOutbound,
    val outboundProfileId: String,
    val enabled: Boolean,
)

private data class DomainMergeKey(
    val registrableDomain: String,
    val behavior: RouteBehavior,
)

private data class IpMergeKey(
    val bucket: List<Byte>,
    val behavior: RouteBehavior,
)

private data class IpMergeCandidate(
    val indexedRule: IndexedRouteRule,
    val ip: NumericRouteIpCandidate,
)

private data class IndexedMergeGroup(
    val sources: List<IndexedRouteRule>,
    val mergedRule: ManagedRouteRule,
)

private fun buildDomainMergeGroups(rules: List<IndexedRouteRule>): List<IndexedMergeGroup> = rules
    .filter { it.rule.type == ManagedRouteRuleType.Domain || it.rule.type == ManagedRouteRuleType.DomainSuffix }
    .groupBy { indexed ->
        DomainMergeKey(
            registrableDomain = registrableRouteDomain(indexed.rule.value),
            behavior = indexed.rule.routeBehavior(),
        )
    }
    .mapNotNull { (key, domainRules) ->
        if (domainRules.size < 2) return@mapNotNull null
        val distinctDomains = domainRules.map { it.rule.value }.distinct()
        val widened = distinctDomains.size > 1
        val sources = domainRules.distinctBy(IndexedRouteRule::index)
        val earliest = sources.minBy(IndexedRouteRule::index).rule
        val merged = earliest.copy(
            type = if (widened || sources.any { it.rule.type == ManagedRouteRuleType.DomainSuffix }) {
                ManagedRouteRuleType.DomainSuffix
            } else {
                ManagedRouteRuleType.Domain
            },
            value = if (widened) key.registrableDomain else distinctDomains.single(),
        ).normalized()
        IndexedMergeGroup(sources, merged)
    }

private fun buildIpMergeGroups(rules: List<IndexedRouteRule>): List<IndexedMergeGroup> {
    val exactIps = rules.mapNotNull { indexed ->
        if (indexed.rule.type != ManagedRouteRuleType.IP) return@mapNotNull null
        parseNumericRouteIp(indexed.rule.value)?.let { ip -> IpMergeCandidate(indexed, ip) }
    }
    return exactIps
        .groupBy { candidate ->
            IpMergeKey(
                bucket = numericRouteIpBucket(candidate.ip),
                behavior = candidate.indexedRule.rule.routeBehavior(),
            )
        }
        .mapNotNull { (key, candidates) ->
            if (candidates.size < 2) return@mapNotNull null
            val distinctIps = candidates.distinctBy { it.ip.bytes.toList() }
            val widened = distinctIps.size > 1
            val cidr = if (widened) smallestContainingRouteCidr(distinctIps.map(IpMergeCandidate::ip)) else ""
            val matchingCidrs = if (widened) {
                rules.filter { indexed ->
                    indexed.rule.type == ManagedRouteRuleType.IPCidr &&
                        indexed.rule.value == cidr &&
                        indexed.rule.routeBehavior() == key.behavior
                }
            } else {
                emptyList()
            }
            val sources = (candidates.map(IpMergeCandidate::indexedRule) + matchingCidrs)
                .distinctBy(IndexedRouteRule::index)
            val earliest = sources.minBy(IndexedRouteRule::index).rule
            val merged = earliest.copy(
                type = if (widened) ManagedRouteRuleType.IPCidr else ManagedRouteRuleType.IP,
                value = if (widened) cidr else distinctIps.single().ip.canonical,
            ).normalized()
            IndexedMergeGroup(sources, merged)
        }
}

private fun ManagedRouteRule.routeBehavior(): RouteBehavior = RouteBehavior(
    outbound = outbound,
    outboundProfileId = outboundProfileId,
    enabled = enabled,
)
