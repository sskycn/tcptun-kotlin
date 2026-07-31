package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

internal const val MaxManagedRouteRuleValueLength = 4096
internal const val MaxRuntimeRouteRuleCount = 256
// The generated runtime may reserve one rule for connectivity checks whenever
// managed routing is enabled.
internal const val MaxActiveManagedRouteRuleCount = MaxRuntimeRouteRuleCount - 1
// Managed rules share one Binder command with the profile plan and generated
// bridge configuration. Bound their estimated runtime footprint so storage can
// never consume the entire command budget before an exact plan preflight.
internal const val MaxEnabledManagedRouteRuntimePayloadLength = 192 * 1024
private const val MaxManagedRouteRuleIdLength = 256
private const val MaxStoredRouteRulesLength = 2 * 1024 * 1024
private const val MaxStoredRouteRuleCount = 1024
private const val EstimatedManagedRouteRuleJsonOverhead = 512
private const val EstimatedManagedRouteConnectivityJsonLength = 1_024

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
        if (outboundProfileId.length > MaxProfileIdLength) return false
        return when (type) {
            ManagedRouteRuleType.Domain,
            ManagedRouteRuleType.DomainSuffix -> normalized.none(Char::isWhitespace) && '.' in normalized
            ManagedRouteRuleType.DomainRegex -> isGoCompatibleDomainRegex(normalized)
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
    @Volatile private var lastKnownGoodRules: List<ManagedRouteRule> = emptyList()

    @Synchronized
    fun load(context: Context): List<ManagedRouteRule> = loadAuthoritative(context).getOrElse { error ->
        runRecoverableCatching {
            TcptunState.appendLog("managed route storage unavailable: ${failureDescription(error)}")
        }
        lastKnownGoodRules
    }

    /** Mutation and runtime-config paths must fail closed rather than treating a read error as no rules. */
    @Synchronized
    internal fun loadAuthoritative(context: Context): Result<List<ManagedRouteRule>> =
        runRecoverableCatching {
            val appContext = context.applicationContext ?: context
            val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RULES, null)
            if (raw == null) {
                lastKnownGoodRules = emptyList()
                return@runRecoverableCatching emptyList()
            }
            require(raw.length <= MaxStoredRouteRulesLength) { "stored route rules are too large" }
            requireSafeJsonNesting(raw)
            val array = JSONArray(raw)
            require(array.length() <= MaxStoredRouteRuleCount) { "too many stored route rules" }
            val seenIds = mutableSetOf<String>()
            var repaired = false
            val loaded = buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index)
                    if (json == null) {
                        repaired = true
                        continue
                    }
                    val storedId = json.optString("id").trim()
                    val id = storedId
                        .takeIf { it.isNotBlank() && it.length <= MaxManagedRouteRuleIdLength && seenIds.add(it) }
                        ?: generateUniqueRouteRuleId(seenIds).also { repaired = true }
                    var supported = true
                    val type = runRecoverableCatching {
                        ManagedRouteRuleType.valueOf(json.optString("type"))
                    }.getOrElse {
                        repaired = true
                        supported = false
                        ManagedRouteRuleType.DomainSuffix
                    }
                    val outbound = runRecoverableCatching {
                        ManagedRouteOutbound.valueOf(json.optString("outbound"))
                    }.getOrElse {
                        repaired = true
                        supported = false
                        ManagedRouteOutbound.Proxy
                    }
                    val storedEnabled = json.opt("enabled")
                    val enabled = when {
                        storedEnabled == null || storedEnabled === JSONObject.NULL -> true // Legacy schema.
                        storedEnabled is Boolean -> storedEnabled
                        else -> {
                            repaired = true
                            false
                        }
                    }
                    val decoded = ManagedRouteRule(
                        id = id,
                        type = type,
                        value = json.optString("value"),
                        outbound = outbound,
                        outboundProfileId = json.optString("outboundProfileId"),
                        enabled = enabled && supported,
                    )
                    val rule = decoded.normalized()
                    if (rule != decoded) repaired = true
                    if (rule.isValid()) {
                        add(rule)
                    } else {
                        repaired = true
                    }
                }
            }
            val capped = disableOverflowEnabledRouteRules(loaded)
            if (repaired || capped.disabledOverflowCount > 0) {
                save(appContext, capped.rules).getOrThrow()
                runRecoverableCatching {
                    if (repaired) {
                        TcptunState.appendLog("repaired stored managed route rules")
                    }
                    if (capped.disabledOverflowCount > 0) {
                        TcptunState.appendLog(
                            "managed route storage exceeded $MaxActiveManagedRouteRuleCount enabled rules; " +
                                "disabled ${capped.disabledOverflowCount} overflow rule(s)",
                        )
                    }
                }
            }
            capped.rules.also { lastKnownGoodRules = it }
        }

    @Synchronized
    fun save(context: Context, rules: List<ManagedRouteRule>): Result<Unit> {
        return runRecoverableCatching {
            val appContext = context.applicationContext ?: context
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
            require(normalized.count(ManagedRouteRule::enabled) <= MaxActiveManagedRouteRuleCount) {
                "at most $MaxActiveManagedRouteRuleCount managed route rules can be enabled"
            }
            require(
                estimatedEnabledRouteRuntimePayloadLength(normalized) <=
                    MaxEnabledManagedRouteRuntimePayloadLength,
            ) {
                "enabled managed route rules are too large"
            }
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
            val committed = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES, encoded)
                .commit()
            check(committed) { "failed to persist route rules" }
            lastKnownGoodRules = normalized
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

internal data class EnabledRouteRuleNormalization(
    val rules: List<ManagedRouteRule>,
    val disabledOverflowCount: Int,
)

/**
 * Keeps every stored rule and its order, but makes legacy data safe for the
 * runtime by disabling enabled rules after the supported boundary.
 */
internal fun disableOverflowEnabledRouteRules(
    rules: List<ManagedRouteRule>,
    maxEnabled: Int = MaxActiveManagedRouteRuleCount,
    maxRuntimePayloadLength: Int = MaxEnabledManagedRouteRuntimePayloadLength,
): EnabledRouteRuleNormalization {
    require(maxEnabled >= 0) { "enabled route rule limit must not be negative" }
    require(maxRuntimePayloadLength >= 0) { "route payload limit must not be negative" }
    var enabledCount = 0
    var runtimePayloadLength = if (rules.any(ManagedRouteRule::enabled)) {
        estimatedManagedRouteConnectivityRuleLength()
    } else {
        0L
    }
    var disabledOverflowCount = 0
    val capped = rules.map { rule ->
        val rulePayloadLength = estimatedRouteRuntimePayloadLength(rule)
        when {
            !rule.enabled -> rule
            enabledCount < maxEnabled &&
                runtimePayloadLength + rulePayloadLength <= maxRuntimePayloadLength.toLong() -> rule.also {
                enabledCount += 1
                runtimePayloadLength += rulePayloadLength
            }
            else -> {
                disabledOverflowCount += 1
                rule.copy(enabled = false)
            }
        }
    }
    return EnabledRouteRuleNormalization(capped, disabledOverflowCount)
}

internal fun estimatedEnabledRouteRuntimePayloadLength(rules: List<ManagedRouteRule>): Long =
    rules.asSequence()
        .filter(ManagedRouteRule::enabled)
        .sumOf(::estimatedRouteRuntimePayloadLength)
        .let { rulesLength ->
            if (rulesLength > 0L) rulesLength + estimatedManagedRouteConnectivityRuleLength()
            else 0L
        }

/**
 * Count the escaped JSON representation rather than source characters. The
 * fixed overhead is deliberately above every generated route wrapper, while
 * quotes, backslashes and controls retain their worst-case expansion.
 */
private fun estimatedRouteRuntimePayloadLength(rule: ManagedRouteRule): Long =
    EstimatedManagedRouteRuleJsonOverhead.toLong() +
        escapedJsonStringLength(rule.value) +
        escapedJsonStringLength(rule.outboundProfileId)

private fun estimatedManagedRouteConnectivityRuleLength(): Long =
    EstimatedManagedRouteConnectivityJsonLength.toLong()

internal fun escapedJsonStringLength(value: String): Long {
    var length = 2L // Opening and closing quotes.
    var index = 0
    while (index < value.length) {
        val char = value[index]
        length += when {
            char == '"' || char == '\\' || char == '/' -> 2L
            char == '\b' || char == '\t' || char == '\n' || char == '\u000c' || char == '\r' -> 2L
            char.code < 0x20 || char == '\u2028' || char == '\u2029' -> 6L
            char.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                index += 1
                2L
            }
            char.isSurrogate() -> 6L
            else -> 1L
        }
        index += 1
    }
    return length
}

/**
 * Kotlin/JVM accepts several backtracking constructs that Go's regexp engine
 * deliberately does not. Reject those constructs before they can be stored.
 */
private val SharedJvmGoUnicodeCategories = setOf(
    "L", "Lu", "Ll", "Lt", "Lm", "Lo",
    "M", "Mn", "Mc", "Me",
    "N", "Nd", "Nl", "No",
    "P", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po",
    "S", "Sm", "Sc", "Sk", "So",
    "Z", "Zs", "Zl", "Zp",
    "C", "Cc", "Cf", "Cs", "Co", "Cn",
)

internal fun isGoCompatibleDomainRegex(pattern: String): Boolean {
    if (runRecoverableCatching { Regex(pattern) }.isFailure) return false

    var index = 0
    var inCharacterClass = false
    while (index < pattern.length) {
        val char = pattern[index]
        if (char == '\\') {
            val escaped = pattern.getOrNull(index + 1) ?: return false
            if (inCharacterClass && escaped in setOf('Q', 'E')) return false
            if (escaped in '1'..'9') return false // JVM backreference; Go treats these differently or rejects them.
            if (escaped == 'p' || escaped == 'P') {
                if (pattern.getOrNull(index + 2) != '{') return false
                val propertyEnd = pattern.indexOf('}', startIndex = index + 3)
                if (propertyEnd < 0) return false
                val property = pattern.substring(index + 3, propertyEnd)
                if (property !in SharedJvmGoUnicodeCategories) return false
                index = propertyEnd + 1
                continue
            }
            if (escaped in setOf('G', 'R', 'X', 'Z', 'e', 'h', 'H', 'V', 'c', 'N', 'u')) {
                return false
            }
            if (escaped == 'k' && pattern.getOrNull(index + 2) == '<') return false
            index += 2
            continue
        }
        if (char == '[') {
            if (inCharacterClass) return false
            inCharacterClass = true
            index += 1
            continue
        }
        if (char == ']' && inCharacterClass) {
            inCharacterClass = false
            index += 1
            continue
        }
        if (inCharacterClass) {
            if (char == '&' && pattern.getOrNull(index + 1) == '&') return false
            index += 1
            continue
        }

        if (char == '(' && pattern.getOrNull(index + 1) == '?') {
            if (
                pattern.startsWith("(?=", index) ||
                pattern.startsWith("(?!", index) ||
                pattern.startsWith("(?<=", index) ||
                pattern.startsWith("(?<!", index) ||
                pattern.startsWith("(?>", index) ||
                pattern.startsWith("(?#", index)
            ) {
                return false
            }
            val flagStart = index + 2
            val firstFlag = pattern.getOrNull(flagStart)
            if (firstFlag != null && (firstFlag.isLetter() || firstFlag == '-')) {
                val flagEnd = (flagStart until pattern.length).firstOrNull { flagIndex ->
                    pattern[flagIndex] == ':' || pattern[flagIndex] == ')'
                }
                if (flagEnd != null) {
                    val flags = pattern.substring(flagStart, flagEnd)
                    // Java's U means Unicode character classes, while RE2's U
                    // means ungreedy; accepting it would silently change meaning.
                    if (flags.any { it !in "ims-" }) return false
                }
            }
        }
        if (char in "*+?}" && pattern.getOrNull(index + 1) == '+') return false
        if (char == '{') {
            val end = pattern.indexOf('}', startIndex = index + 1)
            if (end > index) {
                val bounds = pattern.substring(index + 1, end)
                val numbers = bounds.split(',', limit = 2)
                if (
                    numbers.isNotEmpty() &&
                    numbers.all { it.isEmpty() || it.all { digit -> digit in '0'..'9' } } &&
                    (
                        numbers.filter(String::isNotEmpty).any { it.length > 1 && it.startsWith('0') } ||
                            numbers.filter(String::isNotEmpty)
                                .any { it.toLongOrNull()?.let { value -> value > 1_000 } == true }
                        )
                ) {
                    return false
                }
            }
        }
        index += 1
    }
    return !inCharacterClass
}

private fun isNumericIp(value: String): Boolean {
    if (':' !in value) {
        val octets = value.split('.', limit = 5)
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all { it in '0'..'9' } &&
                (octet.length == 1 || !octet.startsWith('0')) &&
                octet.toIntOrNull() in 0..255
        }
    }
    // A colon makes this an IPv6 literal, so getByName cannot interpret it as a hostname.
    if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return false
    if ('.' in value && !isNumericIp(value.substringAfterLast(':'))) return false
    return runRecoverableCatching { InetAddress.getByName(value) }.isSuccess
}

private fun isValidCidr(value: String): Boolean {
    val parts = value.split('/')
    if (parts.size != 2 || !isNumericIp(parts[0])) return false
    if (parts[1].isEmpty() || parts[1].any { it !in '0'..'9' }) return false
    if (parts[1].length > 1 && parts[1].startsWith('0')) return false
    val prefix = parts[1].toIntOrNull() ?: return false
    val maxPrefix = if (parts[0].contains(':')) 128 else 32
    return prefix in 0..maxPrefix
}

private fun isValidIpRange(value: String): Boolean {
    val separator = value.indexOf('-')
    if (separator <= 0 || separator == value.lastIndex) return false
    val start = value.substring(0, separator).trim()
    val end = value.substring(separator + 1).trim()
    if (value != "$start-$end") return false
    if (!isNumericIp(start) || !isNumericIp(end) || start.contains(':') != end.contains(':')) return false
    val startBytes = runRecoverableCatching { InetAddress.getByName(start).address }.getOrNull() ?: return false
    val endBytes = runRecoverableCatching { InetAddress.getByName(end).address }.getOrNull() ?: return false
    if (startBytes.size != endBytes.size) return false
    for (index in startBytes.indices) {
        val comparison = (startBytes[index].toInt() and 0xff).compareTo(endBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison < 0
    }
    return true
}

private fun isValidPackageName(value: String): Boolean {
    return value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
}
