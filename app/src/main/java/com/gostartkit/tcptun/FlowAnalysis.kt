package com.tcptun.client

import org.json.JSONObject
import java.net.InetAddress

internal const val MAX_FLOW_ANALYSIS_EVENT_JSON_LENGTH = 64 * 1024
private const val MAX_FLOW_ANALYSIS_FIELD_LENGTH = 4 * 1024

data class FlowAnalysisEvent(
    val sessionId: Long,
    val sequence: Long,
    val droppedEvents: Long,
    val timestampMs: Long,
    val type: String,
    val network: String,
    val source: String,
    val destination: String,
    val domain: String,
    val ip: String,
    val originalIp: String,
    val port: Int,
    val outboundTag: String,
    val routeReason: String,
    val appId: String,
) {
    val displayDestination: String
        get() = domain.ifBlank { ip.ifBlank { destination } }
}

/**
 * Turns the currently observed destinations into a small, deterministic set of
 * managed route rules. Domain names are only widened when more than one
 * distinct host shares the same registrable domain. Numeric IPs are grouped in
 * the smallest common subnet, but never wider than /24 for IPv4 or /64 for
 * IPv6 so unrelated networks are not accidentally captured.
 */
internal fun buildFlowRouteRuleSuggestions(
    events: List<FlowAnalysisEvent>,
    outbound: ManagedRouteOutbound,
): List<ManagedRouteRule> {
    val domainCandidates = events.mapNotNull(::validFlowDomain).toSortedSet()
    val domainRules = domainCandidates
        .groupBy(::registrableRouteDomain)
        .toSortedMap()
        .map { (registrableDomain, domains) ->
            if (domains.distinct().size > 1) {
                ManagedRouteRule(
                    type = ManagedRouteRuleType.DomainSuffix,
                    value = registrableDomain,
                    outbound = outbound,
                )
            } else {
                ManagedRouteRule(
                    type = ManagedRouteRuleType.Domain,
                    value = domains.single(),
                    outbound = outbound,
                )
            }
        }

    val ipCandidates = events
        .asSequence()
        .filter { validFlowDomain(it) == null }
        .mapNotNull(::flowEventIp)
        .distinctBy { it.bytes.toList() }
        .toList()
    val ipRules = ipCandidates
        .groupBy(::numericRouteIpBucket)
        .values
        .map { subnetCandidates ->
            val sorted = subnetCandidates.sortedWith(::compareNumericRouteIps)
            if (sorted.size == 1) {
                ManagedRouteRule(
                    type = ManagedRouteRuleType.IP,
                    value = sorted.single().canonical,
                    outbound = outbound,
                )
            } else {
                ManagedRouteRule(
                    type = ManagedRouteRuleType.IPCidr,
                    value = smallestContainingRouteCidr(sorted),
                    outbound = outbound,
                )
            }
        }
        .sortedWith(compareBy({ it.type.ordinal }, { it.value }))

    return domainRules + ipRules
}

/** Generated rules take priority while an identical stored matcher keeps its stable id. */
internal fun mergeFlowRouteRuleSuggestions(
    existing: List<ManagedRouteRule>,
    suggestions: List<ManagedRouteRule>,
): List<ManagedRouteRule> {
    val normalizedExisting = existing.map(ManagedRouteRule::normalized)
    val existingByMatcher = normalizedExisting.associateBy(::routeMatcherKey)
    val normalizedSuggestions = suggestions
        .map(ManagedRouteRule::normalized)
        .distinctBy(::routeMatcherKey)
        .map { suggestion ->
            existingByMatcher[routeMatcherKey(suggestion)]
                ?.let { stored -> suggestion.copy(id = stored.id) }
                ?: suggestion
        }
    val generatedMatchers = normalizedSuggestions.mapTo(mutableSetOf(), ::routeMatcherKey)
    return normalizedSuggestions + normalizedExisting.filterNot { routeMatcherKey(it) in generatedMatchers }
}

internal data class NumericRouteIpCandidate(
    val canonical: String,
    val bytes: ByteArray,
)

private fun routeMatcherKey(rule: ManagedRouteRule): Pair<ManagedRouteRuleType, String> =
    rule.type to rule.normalized().value

private fun validFlowDomain(event: FlowAnalysisEvent): String? {
    val normalized = event.domain.trim().trimEnd('.').lowercase()
    if (ManagedRouteRule(type = ManagedRouteRuleType.IP, value = normalized).isValid()) return null
    return normalized.takeIf {
        ManagedRouteRule(type = ManagedRouteRuleType.Domain, value = it).isValid()
    }
}

private fun flowEventIp(event: FlowAnalysisEvent): NumericRouteIpCandidate? {
    val raw = event.ip.ifBlank { destinationHost(event.destination) }.trim().removeSurrounding("[", "]")
    return parseNumericRouteIp(raw)
}

internal fun parseNumericRouteIp(raw: String): NumericRouteIpCandidate? {
    if (!ManagedRouteRule(type = ManagedRouteRuleType.IP, value = raw).isValid()) return null
    val bytes = if (':' in raw) {
        runRecoverableCatching { InetAddress.getByName(raw).address }.getOrNull()
    } else {
        raw.split('.').map { it.toInt().toByte() }.toByteArray()
    } ?: return null
    return NumericRouteIpCandidate(canonicalIp(bytes), bytes)
}

private fun destinationHost(destination: String): String = when {
    destination.startsWith('[') && "]" in destination -> destination.substring(1, destination.indexOf(']'))
    destination.count { it == ':' } == 1 -> destination.substringBeforeLast(':')
    else -> destination
}

private val KnownTwoLabelPublicSuffixes = setOf(
    "ac.jp", "ac.nz", "ac.uk", "appspot.com", "asn.au", "co.jp", "co.kr", "co.nz", "co.uk",
    "com.au", "com.br", "com.cn", "com.hk", "com.mx", "com.sg", "com.tw", "cloudfront.net",
    "edu.au", "edu.cn", "firm.in", "gen.in", "github.io", "go.jp", "gov.au", "gov.cn", "gov.uk",
    "ind.in", "lg.jp", "me.uk", "net.au", "net.br", "net.cn", "net.in", "net.nz", "ne.jp",
    "nic.in", "or.jp", "org.au", "org.br", "org.cn", "org.in", "org.nz", "org.uk", "pages.dev",
    "res.in", "sch.uk", "vercel.app",
)

internal fun registrableRouteDomain(domain: String): String {
    val labels = domain.split('.')
    if (labels.size <= 2) return domain
    val lastTwo = labels.takeLast(2).joinToString(".")
    return if (lastTwo in KnownTwoLabelPublicSuffixes && labels.size >= 3) {
        labels.takeLast(3).joinToString(".")
    } else {
        lastTwo
    }
}

internal fun numericRouteIpBucket(candidate: NumericRouteIpCandidate): List<Byte> {
    val prefixBytes = if (candidate.bytes.size == 4) 3 else 8
    return candidate.bytes.take(prefixBytes)
}

internal fun compareNumericRouteIps(
    left: NumericRouteIpCandidate,
    right: NumericRouteIpCandidate,
): Int {
    left.bytes.indices.forEach { index ->
        val difference = (left.bytes[index].toInt() and 0xff) - (right.bytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return 0
}

internal fun smallestContainingRouteCidr(candidates: List<NumericRouteIpCandidate>): String {
    require(candidates.size >= 2) { "at least two IPs are required for a subnet" }
    val sorted = candidates.sortedWith(::compareNumericRouteIps)
    require(sorted.first().bytes.size == sorted.last().bytes.size) { "IP families must match" }
    val prefix = commonPrefixLength(sorted.first().bytes, sorted.last().bytes)
    val network = networkAddress(sorted.first().bytes, prefix)
    return "${canonicalIp(network)}/$prefix"
}

private fun commonPrefixLength(first: ByteArray, last: ByteArray): Int {
    var prefix = 0
    first.indices.forEach { index ->
        val difference = (first[index].toInt() xor last[index].toInt()) and 0xff
        if (difference == 0) {
            prefix += 8
        } else {
            return prefix + Integer.numberOfLeadingZeros(difference) - 24
        }
    }
    return prefix
}

private fun networkAddress(address: ByteArray, prefix: Int): ByteArray = address.copyOf().also { network ->
    network.indices.forEach { index ->
        val remainingBits = (prefix - index * 8).coerceIn(0, 8)
        val mask = if (remainingBits == 0) 0 else (0xff shl (8 - remainingBits)) and 0xff
        network[index] = ((network[index].toInt() and 0xff) and mask).toByte()
    }
}

private fun canonicalIp(address: ByteArray): String =
    requireNotNull(InetAddress.getByAddress(address).hostAddress) { "numeric IP has no host address" }

internal fun parseFlowAnalysisEvent(eventJson: String): FlowAnalysisEvent? {
    if (eventJson.length > MAX_FLOW_ANALYSIS_EVENT_JSON_LENGTH) return null
    val json = runRecoverableCatching {
        requireSafeJsonNesting(eventJson)
        JSONObject(eventJson)
    }.getOrNull() ?: return null
    val sessionId = json.optLong("session_id", 0)
    val sequence = json.optLong("sequence", 0)
    val type = json.optBoundedString("type")?.lowercase() ?: return null
    val network = json.optBoundedString("network")?.lowercase() ?: return null
    val destination = json.optBoundedString("destination") ?: return null
    val appId = json.optJSONObject("app")?.optBoundedString("id") ?: return null
    if (sessionId <= 0 || sequence <= 0 || type.isBlank() || network !in setOf("tcp", "udp")) return null
    if (destination.isBlank() || appId.isBlank()) return null
    return FlowAnalysisEvent(
        sessionId = sessionId,
        sequence = sequence,
        droppedEvents = json.optLong("dropped_events", 0).coerceAtLeast(0),
        timestampMs = json.optLong("timestamp_ms", 0),
        type = type,
        network = network,
        source = json.optBoundedString("source") ?: return null,
        destination = destination,
        domain = json.optBoundedString("domain") ?: return null,
        ip = json.optBoundedString("ip") ?: return null,
        originalIp = json.optBoundedString("original_ip") ?: return null,
        port = json.optInt("port", 0).coerceIn(0, 65_535),
        outboundTag = json.optBoundedString("outbound_tag") ?: return null,
        routeReason = json.optBoundedString("route_reason") ?: return null,
        appId = appId,
    )
}

private fun JSONObject.optBoundedString(name: String): String? {
    val value = opt(name)
    if (value == null || value === JSONObject.NULL) return ""
    if (value !is String || value.length > MAX_FLOW_ANALYSIS_FIELD_LENGTH) return null
    return value.trim()
}
