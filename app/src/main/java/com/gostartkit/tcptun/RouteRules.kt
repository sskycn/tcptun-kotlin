package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

enum class ManagedRouteRuleType(val jsonKey: String) {
    Domain("domains"),
    DomainSuffix("domain_suffixes"),
    DomainRegex("domain_regexes"),
    IP("ips"),
    IPCidr("ip_cidrs"),
    IPRange("ip_ranges"),
}

enum class ManagedRouteOutbound(val tag: String) {
    Proxy("proxy"),
    Direct("direct"),
}

data class ManagedRouteRule(
    val id: String = UUID.randomUUID().toString(),
    val type: ManagedRouteRuleType = ManagedRouteRuleType.DomainSuffix,
    val value: String = "",
    val outbound: ManagedRouteOutbound = ManagedRouteOutbound.Proxy,
    val outboundProfileId: String = "",
    val enabled: Boolean = true,
) {
    fun normalized(): ManagedRouteRule {
        val normalizedValue = when (type) {
            ManagedRouteRuleType.Domain -> value.trim().trimEnd('.').lowercase()
            ManagedRouteRuleType.DomainSuffix -> value.trim().trimStart('.').trimEnd('.').lowercase()
            else -> value.trim()
        }
        return copy(
            value = normalizedValue,
            outboundProfileId = if (outbound == ManagedRouteOutbound.Direct) "" else outboundProfileId.trim(),
        )
    }

    fun isValid(): Boolean {
        val normalized = normalized().value
        if (normalized.isBlank()) return false
        return when (type) {
            ManagedRouteRuleType.Domain,
            ManagedRouteRuleType.DomainSuffix -> normalized.none(Char::isWhitespace) && '.' in normalized
            ManagedRouteRuleType.DomainRegex -> runCatching { Regex(normalized) }.isSuccess
            ManagedRouteRuleType.IP -> isNumericIp(normalized)
            ManagedRouteRuleType.IPCidr -> isValidCidr(normalized)
            ManagedRouteRuleType.IPRange -> isValidIpRange(normalized)
        }
    }
}

object RouteRuleStore {
    private const val PREFS = "tcptun_routes"
    private const val KEY_RULES = "managedRouteRules"

    fun load(context: Context): List<ManagedRouteRule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RULES, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val rule = ManagedRouteRule(
                        id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                        type = runCatching { ManagedRouteRuleType.valueOf(json.optString("type")) }
                            .getOrDefault(ManagedRouteRuleType.DomainSuffix),
                        value = json.optString("value"),
                        outbound = runCatching { ManagedRouteOutbound.valueOf(json.optString("outbound")) }
                            .getOrDefault(ManagedRouteOutbound.Proxy),
                        outboundProfileId = json.optString("outboundProfileId"),
                        enabled = json.optBoolean("enabled", true),
                    ).normalized()
                    if (rule.isValid()) add(rule)
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, rules: List<ManagedRouteRule>): Result<Unit> {
        return runCatching {
            val normalized = rules.map(ManagedRouteRule::normalized)
            require(normalized.all(ManagedRouteRule::isValid)) { "invalid route rule" }
            val array = JSONArray()
            normalized.forEach { rule ->
                array.put(
                    JSONObject()
                        .put("id", rule.id)
                        .put("type", rule.type.name)
                        .put("value", rule.value)
                        .put("outbound", rule.outbound.name)
                        .put("outboundProfileId", rule.outboundProfileId)
                        .put("enabled", rule.enabled),
                )
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES, array.toString())
                .apply()
        }
    }
}

private fun isNumericIp(value: String): Boolean {
    if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return false
    return runCatching { InetAddress.getByName(value) }.isSuccess
}

private fun isValidCidr(value: String): Boolean {
    val parts = value.split('/')
    if (parts.size != 2 || !isNumericIp(parts[0])) return false
    val prefix = parts[1].toIntOrNull() ?: return false
    val maxPrefix = if (parts[0].contains(':')) 128 else 32
    return prefix in 0..maxPrefix
}

private fun isValidIpRange(value: String): Boolean {
    val separator = value.indexOf('-')
    if (separator <= 0 || separator == value.lastIndex) return false
    val start = value.substring(0, separator).trim()
    val end = value.substring(separator + 1).trim()
    return isNumericIp(start) && isNumericIp(end) && start.contains(':') == end.contains(':')
}
