package com.tcptun.client

import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal const val AndroidVpnDnsAddress = "10.77.0.1"
private const val MaxDisplayedStunServers = 8

class IpPrefix private constructor(
    val address: String,
    val prefixLength: Int,
) {
    val isIpv4: Boolean get() = parseNumericAddress(address) is Inet4Address

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

/** Android always installs IPv4 and IPv6 default routes into VpnService. */
sealed interface AndroidVpnRoutePlan {
    data object FullTunnel : AndroidVpnRoutePlan
}

data class CompiledAndroidVpnRoutePlan(
    val mode: String,
    val routes: List<IpPrefix>,
    val dnsServers: List<String>,
    val fakeIpRoutes: List<IpPrefix>,
)

internal fun normalizeAndroidVpnRoutePlan(plan: AndroidVpnRoutePlan): AndroidVpnRoutePlan = when (plan) {
    AndroidVpnRoutePlan.FullTunnel -> AndroidVpnRoutePlan.FullTunnel
}

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
    CoreP2pConfigState(enabled, hostCandidates, stunCount.coerceAtMost(MaxDisplayedStunServers))
}.getOrDefault(CoreP2pConfigState())

@Suppress("UNUSED_PARAMETER")
internal fun compileAndroidVpnRoutePlan(
    plan: AndroidVpnRoutePlan,
    coreConfigJson: String,
): CompiledAndroidVpnRoutePlan {
    normalizeAndroidVpnRoutePlan(plan)
    return CompiledAndroidVpnRoutePlan(
        mode = "full",
        routes = listOf(IpPrefix.of("0.0.0.0", 0), IpPrefix.of("::", 0)),
        dnsServers = listOf(AndroidVpnDnsAddress),
        fakeIpRoutes = emptyList(),
    )
}

internal fun encodeAndroidVpnRoutePlan(plan: AndroidVpnRoutePlan): String {
    normalizeAndroidVpnRoutePlan(plan)
    return JSONObject().put("mode", "full").toString()
}

/**
 * Reads the legacy preference only for upgrade compatibility. Old split-tunnel values deliberately
 * collapse to Full Tunnel; Android no longer exposes or restores platform split routing.
 */
internal fun decodeAndroidVpnRoutePlan(encoded: String?): AndroidVpnRoutePlan {
    if (encoded.isNullOrBlank()) return AndroidVpnRoutePlan.FullTunnel
    requireSafeJsonNesting(encoded)
    return decodeAndroidVpnRouteMode(JSONObject(encoded).optString("mode"))
}

internal fun decodeAndroidVpnRouteMode(mode: String?): AndroidVpnRoutePlan =
    when (mode.orEmpty().trim().lowercase()) {
        "", "full", "split" -> AndroidVpnRoutePlan.FullTunnel
        else -> throw IllegalArgumentException("unsupported Android VPN route mode")
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
