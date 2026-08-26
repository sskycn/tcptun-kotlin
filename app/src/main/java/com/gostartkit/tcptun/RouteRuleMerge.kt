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
 * Removes equivalent duplicate domain and exact IP rules. Smart merge must not
 * widen a matcher: moving a wider suffix or CIDR to the earliest source
 * position can capture a non-participating rule and change first-match routing.
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
    val type: ManagedRouteRuleType,
    val domain: String,
    val behavior: RouteBehavior,
)

private data class IpMergeKey(
    val ip: String,
    val behavior: RouteBehavior,
)

private data class IndexedMergeGroup(
    val sources: List<IndexedRouteRule>,
    val mergedRule: ManagedRouteRule,
)

private fun buildDomainMergeGroups(rules: List<IndexedRouteRule>): List<IndexedMergeGroup> = rules
    .filter { it.rule.type == ManagedRouteRuleType.Domain || it.rule.type == ManagedRouteRuleType.DomainSuffix }
    .groupBy { indexed ->
        DomainMergeKey(
            type = indexed.rule.type,
            domain = indexed.rule.value,
            behavior = indexed.rule.routeBehavior(),
        )
    }
    .mapNotNull { (_, domainRules) ->
        if (domainRules.size < 2) return@mapNotNull null
        val sources = domainRules.distinctBy(IndexedRouteRule::index)
        val earliest = sources.minBy(IndexedRouteRule::index).rule
        IndexedMergeGroup(sources, earliest)
    }

private fun buildIpMergeGroups(rules: List<IndexedRouteRule>): List<IndexedMergeGroup> {
    val exactIps = rules.mapNotNull { indexed ->
        if (indexed.rule.type != ManagedRouteRuleType.IP) return@mapNotNull null
        parseNumericRouteIp(indexed.rule.value)?.let { ip -> indexed to ip.canonical }
    }
    return exactIps
        .groupBy { (indexed, canonicalIp) ->
            IpMergeKey(
                ip = canonicalIp,
                behavior = indexed.rule.routeBehavior(),
            )
        }
        .mapNotNull { (_, candidates) ->
            if (candidates.size < 2) return@mapNotNull null
            val sources = candidates.map { it.first }
                .distinctBy(IndexedRouteRule::index)
            val earliest = sources.minBy(IndexedRouteRule::index).rule
            IndexedMergeGroup(sources, earliest)
        }
}

private fun ManagedRouteRule.routeBehavior(): RouteBehavior = RouteBehavior(
    outbound = outbound,
    outboundProfileId = outboundProfileId,
    enabled = enabled,
)
