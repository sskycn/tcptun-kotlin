package com.tcptun.client

import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal const val AndroidVpnDnsAddress = "10.77.0.1"
internal const val MaxAndroidVpnRoutes = 128
internal const val MaxAndroidVpnDnsServers = 8
internal const val MaxAndroidVpnFakeIpRoutes = 8

class IpPrefix private constructor(
    val address: String,
    val prefixLength: Int,
) {
    val isIpv4: Boolean get() = parseNumericAddress(address) is Inet4Address

    fun contains(address: String): Boolean {
        val candidate = parseNumericAddress(address)
        val network = parseNumericAddress(this.address)
        if (candidate.address.size != network.address.size) return false
        return prefixMatches(network.address, candidate.address, prefixLength)
    }

    override fun toString(): String = "$address/$prefixLength"

    override fun equals(other: Any?): Boolean =
        other is IpPrefix && address == other.address && prefixLength == other.prefixLength

    override fun hashCode(): Int = 31 * address.hashCode() + prefixLength

    companion object {
        fun parse(value: String): IpPrefix {
            val normalized = value.trim()
            val slash = normalized.lastIndexOf('/')
            require(slash > 0 && slash < normalized.lastIndex) { "invalid IP prefix: $value" }
            val inputAddress = parseNumericAddress(normalized.substring(0, slash))
            val bits = inputAddress.address.size * 8
            val prefix = normalized.substring(slash + 1).toIntOrNull()
                ?: throw IllegalArgumentException("invalid IP prefix length: $value")
            require(prefix in 0..bits) { "invalid IP prefix length: $value" }
            val masked = inputAddress.address.copyOf()
            maskHostBits(masked, prefix)
            val canonical = requireNotNull(InetAddress.getByAddress(masked).hostAddress).substringBefore('%')
            return IpPrefix(canonical, prefix)
        }

        fun of(address: String, prefixLength: Int): IpPrefix = parse("$address/$prefixLength")
    }
}

sealed interface AndroidVpnRoutePlan {
    data object FullTunnel : AndroidVpnRoutePlan

    data class SplitTunnel(
        val routes: List<IpPrefix>,
        val dnsServers: List<String> = emptyList(),
    ) : AndroidVpnRoutePlan
}

data class CompiledAndroidVpnRoutePlan(
    val mode: String,
    val routes: List<IpPrefix>,
    val dnsServers: List<String>,
    val fakeIpRoutes: List<IpPrefix>,
)

internal fun normalizeAndroidVpnRoutePlan(plan: AndroidVpnRoutePlan): AndroidVpnRoutePlan = when (plan) {
    AndroidVpnRoutePlan.FullTunnel -> plan
    is AndroidVpnRoutePlan.SplitTunnel -> {
        require(plan.routes.size <= MaxAndroidVpnRoutes) {
            "at most $MaxAndroidVpnRoutes Android VPN routes are allowed"
        }
        require(plan.dnsServers.size <= MaxAndroidVpnDnsServers) {
            "at most $MaxAndroidVpnDnsServers Android VPN DNS servers are allowed"
        }
        val routes = plan.routes.distinct()
        require(routes.isNotEmpty()) { "split tunnel requires at least one route" }
        require(routes.none { it.prefixLength == 0 }) {
            "split tunnel cannot contain an implicit default route"
        }
        val dns = plan.dnsServers.map {
            requireNotNull(parseNumericAddress(it).hostAddress).substringBefore('%')
        }.distinct()
        AndroidVpnRoutePlan.SplitTunnel(routes, dns)
    }
}

data class AndroidVpnRouteDraft(
    val ipv4Cidrs: String = "",
    val ipv6Cidrs: String = "",
    val dnsServers: String = "",
)

internal fun androidVpnRouteDraft(plan: AndroidVpnRoutePlan): AndroidVpnRouteDraft {
    val split = plan as? AndroidVpnRoutePlan.SplitTunnel ?: return AndroidVpnRouteDraft()
    return AndroidVpnRouteDraft(
        ipv4Cidrs = split.routes.filter(IpPrefix::isIpv4).joinToString(", "),
        ipv6Cidrs = split.routes.filterNot(IpPrefix::isIpv4).joinToString(", "),
        dnsServers = split.dnsServers.joinToString(", "),
    )
}

internal fun parseSplitTunnelRoutePlan(
    ipv4Cidrs: String,
    ipv6Cidrs: String,
    dnsServers: String,
): AndroidVpnRoutePlan.SplitTunnel {
    fun tokens(value: String): List<String> {
        require(value.length <= 8_192) { "Android VPN route input is too large" }
        return value.split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
    val ipv4 = tokens(ipv4Cidrs).map(IpPrefix::parse).also { routes ->
        require(routes.all(IpPrefix::isIpv4)) { "IPv4 routes must contain only IPv4 CIDRs" }
    }
    val ipv6 = tokens(ipv6Cidrs).map(IpPrefix::parse).also { routes ->
        require(routes.none(IpPrefix::isIpv4)) { "IPv6 routes must contain only IPv6 CIDRs" }
    }
    val normalized = normalizeAndroidVpnRoutePlan(
        AndroidVpnRoutePlan.SplitTunnel(ipv4 + ipv6, tokens(dnsServers)),
    ) as AndroidVpnRoutePlan.SplitTunnel
    normalized.dnsServers.forEach { dns ->
        require(normalized.routes.any { route -> route.contains(dns) }) {
            "VPN DNS server $dns is outside the split-tunnel routes"
        }
    }
    return normalized
}

data class AndroidCoreFeatureSummary(
    val profileName: String = "",
    val p2pEnabled: Boolean = false,
    val hostCandidatesEnabled: Boolean = false,
    val stunServerCount: Int = 0,
)

data class CoreP2pConfigState(
    val enabled: Boolean = false,
    val hostCandidatesEnabled: Boolean = false,
    val stunServerCount: Int = 0,
)

internal fun coreP2pConfigState(configJson: String): CoreP2pConfigState = runRecoverableCatching {
    requireSafeJsonNesting(configJson)
    val outbounds = JSONObject(configJson).optJSONArray("outbounds")
    var enabled = false
    var hostCandidates = false
    var stunCount = 0
    if (outbounds != null) {
        for (index in 0 until outbounds.length()) {
            val p2p = outbounds.optJSONObject(index)?.optJSONObject("p2p") ?: continue
            enabled = enabled || p2p.optBoolean("enabled", false)
            hostCandidates = hostCandidates || p2p.optBoolean("host_candidates", false)
            stunCount += p2p.optJSONArray("stun")?.length() ?: 0
        }
    }
    CoreP2pConfigState(enabled, hostCandidates, stunCount.coerceAtMost(MaxAndroidVpnDnsServers))
}.getOrDefault(CoreP2pConfigState())

internal fun androidCoreFeatureSummary(profile: AppConfig?): AndroidCoreFeatureSummary {
    if (profile == null) return AndroidCoreFeatureSummary()
    if (profile.rawConfigJson.isBlank()) return AndroidCoreFeatureSummary(profileName = profile.name)
    return runRecoverableCatching {
        requireSafeJsonNesting(profile.rawConfigJson)
        val p2p = coreP2pConfigState(profile.rawConfigJson)
        AndroidCoreFeatureSummary(
            profileName = profile.name,
            p2pEnabled = p2p.enabled,
            hostCandidatesEnabled = p2p.hostCandidatesEnabled,
            stunServerCount = p2p.stunServerCount,
        )
    }.getOrDefault(AndroidCoreFeatureSummary(profileName = profile.name))
}

internal fun compileAndroidVpnRoutePlan(
    plan: AndroidVpnRoutePlan,
    coreConfigJson: String,
): CompiledAndroidVpnRoutePlan = when (val normalized = normalizeAndroidVpnRoutePlan(plan)) {
    AndroidVpnRoutePlan.FullTunnel -> CompiledAndroidVpnRoutePlan(
        mode = "full",
        routes = listOf(IpPrefix.of("0.0.0.0", 0), IpPrefix.of("::", 0)),
        dnsServers = listOf(AndroidVpnDnsAddress),
        fakeIpRoutes = emptyList(),
    )
    is AndroidVpnRoutePlan.SplitTunnel -> {
        val fakeRoutes = configuredFakeIpRoutes(coreConfigJson)
        val routes = (normalized.routes + fakeRoutes).distinct()
        require(routes.size <= MaxAndroidVpnRoutes + MaxAndroidVpnFakeIpRoutes) {
            "compiled Android VPN route plan is too large"
        }
        normalized.dnsServers.forEach { dns ->
            require(routes.any { it.contains(dns) }) {
                "VPN DNS server $dns is outside the split-tunnel routes"
            }
        }
        CompiledAndroidVpnRoutePlan(
            mode = "split",
            routes = routes,
            dnsServers = normalized.dnsServers,
            fakeIpRoutes = fakeRoutes,
        )
    }
}

internal fun encodeAndroidVpnRoutePlan(plan: AndroidVpnRoutePlan): String = when (
    val normalized = normalizeAndroidVpnRoutePlan(plan)
) {
    AndroidVpnRoutePlan.FullTunnel -> JSONObject().put("mode", "full").toString()
    is AndroidVpnRoutePlan.SplitTunnel -> JSONObject()
        .put("mode", "split")
        .put("routes", org.json.JSONArray().apply { normalized.routes.forEach { put(it.toString()) } })
        .put("dnsServers", org.json.JSONArray().apply { normalized.dnsServers.forEach(::put) })
        .toString()
}

internal fun decodeAndroidVpnRoutePlan(encoded: String?): AndroidVpnRoutePlan {
    if (encoded.isNullOrBlank()) return AndroidVpnRoutePlan.FullTunnel
    requireSafeJsonNesting(encoded)
    val root = JSONObject(encoded)
    return when (root.optString("mode").trim().lowercase()) {
        "", "full" -> AndroidVpnRoutePlan.FullTunnel
        "split" -> {
            val routeValues = root.optJSONArray("routes")
                ?: throw IllegalArgumentException("split tunnel routes are missing")
            val dnsValues = root.optJSONArray("dnsServers")
            val routes = buildList(routeValues.length()) {
                for (index in 0 until routeValues.length()) add(IpPrefix.parse(routeValues.getString(index)))
            }
            val dns = buildList(dnsValues?.length() ?: 0) {
                if (dnsValues != null) {
                    for (index in 0 until dnsValues.length()) add(dnsValues.getString(index))
                }
            }
            normalizeAndroidVpnRoutePlan(AndroidVpnRoutePlan.SplitTunnel(routes, dns))
        }
        else -> throw IllegalArgumentException("unsupported Android VPN route mode")
    }
}

private fun configuredFakeIpRoutes(coreConfigJson: String): List<IpPrefix> {
    requireSafeJsonNesting(coreConfigJson)
    val fakeIp = JSONObject(coreConfigJson).optJSONObject("dns")?.optJSONObject("fake_ip")
        ?: return emptyList()
    if (!fakeIp.optBoolean("enabled", false)) return emptyList()
    val routes = buildList {
        fakeIp.optString("ipv4_range").trim().takeIf(String::isNotBlank)?.let { add(IpPrefix.parse(it)) }
        fakeIp.optString("ipv6_range").trim().takeIf(String::isNotBlank)?.let { add(IpPrefix.parse(it)) }
    }.distinct()
    require(routes.size <= MaxAndroidVpnFakeIpRoutes) { "too many fake-IP routes" }
    return routes
}

private fun parseNumericAddress(value: String): InetAddress {
    val normalized = value.trim().removeSurrounding("[", "]")
    require(normalized.isNotEmpty()) { "IP address is empty" }
    if (':' !in normalized) {
        val octets = normalized.split('.')
        require(octets.size == 4) { "IP address must be numeric: $value" }
        val bytes = ByteArray(4)
        octets.forEachIndexed { index, octet ->
            require(octet.isNotEmpty() && octet.all(Char::isDigit)) { "invalid IPv4 address: $value" }
            val parsed = octet.toIntOrNull()
                ?: throw IllegalArgumentException("invalid IPv4 address: $value")
            require(parsed in 0..255) { "invalid IPv4 address: $value" }
            bytes[index] = parsed.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }
    val parsed = InetAddress.getByName(normalized)
    require(parsed is Inet6Address) { "invalid IPv6 address: $value" }
    return parsed
}

private fun maskHostBits(bytes: ByteArray, prefixLength: Int) {
    var remaining = prefixLength
    for (index in bytes.indices) {
        val keep = remaining.coerceIn(0, 8)
        val mask = if (keep == 0) 0 else (0xff shl (8 - keep)) and 0xff
        bytes[index] = (bytes[index].toInt() and mask).toByte()
        remaining -= keep
    }
}

private fun prefixMatches(network: ByteArray, candidate: ByteArray, prefixLength: Int): Boolean {
    val fullBytes = prefixLength / 8
    for (index in 0 until fullBytes) if (network[index] != candidate[index]) return false
    val remaining = prefixLength % 8
    if (remaining == 0) return true
    val mask = (0xff shl (8 - remaining)) and 0xff
    return (network[fullBytes].toInt() and mask) == (candidate[fullBytes].toInt() and mask)
}
