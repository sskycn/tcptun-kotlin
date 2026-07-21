package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

private const val MaxManagedRouteRuleValueLength = 4096
private const val MaxManagedRouteRuleIdLength = 256
private const val MaxStoredRouteRulesLength = 2 * 1024 * 1024
private const val MaxStoredRouteRuleCount = 1024

enum class ManagedRouteRuleType(val jsonKey: String) {
    Domain("domains"),
    DomainSuffix("domain_suffixes"),
    DomainRegex("domain_regexes"),
    IP("ips"),
    IPCidr("ip_cidrs"),
    IPRange("ip_ranges"),
    App("app"),
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
        if (normalized.isBlank() || normalized.length > MaxManagedRouteRuleValueLength) return false
        return when (type) {
            ManagedRouteRuleType.Domain,
            ManagedRouteRuleType.DomainSuffix -> normalized.none(Char::isWhitespace) && '.' in normalized
            ManagedRouteRuleType.DomainRegex -> runCatching { Regex(normalized) }.isSuccess
            ManagedRouteRuleType.IP -> isNumericIp(normalized)
            ManagedRouteRuleType.IPCidr -> isValidCidr(normalized)
            ManagedRouteRuleType.IPRange -> isValidIpRange(normalized)
            ManagedRouteRuleType.App -> isValidPackageName(normalized)
        }
    }

    fun putMatchCondition(json: JSONObject): JSONObject {
        return if (type == ManagedRouteRuleType.App) {
            json.put(
                "app",
                JSONObject()
                    .put("platforms", JSONArray().put("android"))
                    .put(
                        "attributes",
                        JSONObject().put("packages", JSONArray().put(value)),
                    ),
            )
        } else {
            json.put(type.jsonKey, JSONArray().put(value))
        }
    }
}

object RouteRuleStore {
    private const val PREFS = "tcptun_routes"
    private const val KEY_RULES = "managedRouteRules"

    fun load(context: Context): List<ManagedRouteRule> {
        return runCatching {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RULES, null)
                ?: return@runCatching emptyList()
            if (raw.length > MaxStoredRouteRulesLength) return@runCatching emptyList()
            requireSafeJsonNesting(raw)
            val array = JSONArray(raw)
            if (array.length() > MaxStoredRouteRuleCount) return@runCatching emptyList()
            val seenIds = mutableSetOf<String>()
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val storedId = json.optString("id").trim()
                    val id = storedId
                        .takeIf { it.isNotBlank() && it.length <= MaxManagedRouteRuleIdLength && seenIds.add(it) }
                        ?: generateUniqueRouteRuleId(seenIds)
                    val rule = ManagedRouteRule(
                        id = id,
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
            require(rules.size <= MaxStoredRouteRuleCount) { "too many route rules" }
            val seenIds = mutableSetOf<String>()
            val normalized = rules.map { rule ->
                val storedId = rule.id.trim()
                val id = storedId
                    .takeIf { it.isNotBlank() && it.length <= MaxManagedRouteRuleIdLength && seenIds.add(it) }
                    ?: generateUniqueRouteRuleId(seenIds)
                rule.copy(id = id).normalized()
            }
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
            val encoded = array.toString()
            require(encoded.length <= MaxStoredRouteRulesLength) { "stored route rule data is too large" }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES, encoded)
                .apply()
        }
    }

    private fun generateUniqueRouteRuleId(seenIds: MutableSet<String>): String {
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (!seenIds.add(id))
        return id
    }
}

private fun isNumericIp(value: String): Boolean {
    if (':' !in value) {
        val octets = value.split('.', limit = 5)
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull() in 0..255
        }
    }
    // A colon makes this an IPv6 literal, so getByName cannot interpret it as a hostname.
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

private fun isValidPackageName(value: String): Boolean {
    return value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
}
