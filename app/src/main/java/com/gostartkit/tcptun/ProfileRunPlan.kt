package com.tcptun.client

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class ProfileRunPlan(
    val profiles: List<AppConfig>,
    val activeIds: Set<String> = profiles.mapTo(linkedSetOf(), AppConfig::id),
) {
    val activeProfiles: List<AppConfig>
        get() = profiles.filter { it.id in activeIds }

    fun normalized(): ProfileRunPlan {
        require(profiles.isNotEmpty()) { "at least one profile must be configured" }
        require(profiles.size <= MaxRuntimeProfileCount) {
            "at most $MaxRuntimeProfileCount profiles can be configured in one runtime"
        }
        require(activeIds.size <= MaxRuntimeProfileCount) {
            "at most $MaxRuntimeProfileCount profiles can be active in one runtime"
        }
        require(profiles.all { it.id.isNotBlank() && it.id.length <= MaxProfileIdLength }) {
            "configured profile ID is invalid"
        }
        require(activeIds.all { it.isNotBlank() && it.length <= MaxProfileIdLength }) {
            "active profile ID is invalid"
        }
        require(profiles.map(AppConfig::id).distinct().size == profiles.size) { "configured profiles must be unique" }
        require(activeIds.isNotEmpty()) { "at least one profile must be running" }
        require(activeIds.all { activeId -> profiles.any { it.id == activeId } }) { "running profile is not configured" }
        if (profiles.size > 1) {
            require(profiles.none { it.rawConfigJson.isNotBlank() }) {
                "full JSON profiles cannot run with other profiles"
            }
        }
        profiles.forEach { profile ->
            profile.validate()?.let { throw IllegalArgumentException("${profile.name}: $it") }
        }
        return this
    }

    fun toJson(): JSONObject = JSONObject()
        .put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
        .put("activeIds", JSONArray().apply { profiles.filter { it.id in activeIds }.forEach { put(it.id) } })

    companion object {
        fun fromJson(json: JSONObject): ProfileRunPlan {
            val values = json.getJSONArray("profiles")
            require(values.length() in 1..MaxStoredProfileCount) { "invalid configured profile count" }
            val profiles = buildList {
                for (index in 0 until values.length()) {
                    val profile = AppConfig.fromJson(values.getJSONObject(index))
                    require(profile.id.isNotBlank() && profile.id.length <= MaxProfileIdLength) {
                        "invalid profile ID"
                    }
                    require(profile.hasSafeStorageSize()) { "profile data is too large" }
                    add(profile)
                }
            }
            val activeIds = json.optJSONArray("activeIds")?.let { valuesArray ->
                require(valuesArray.length() <= MaxStoredProfileCount) { "too many active profiles" }
                buildSet {
                    for (index in 0 until valuesArray.length()) {
                        val id = valuesArray.getString(index)
                        require(id.isNotBlank() && id.length <= MaxProfileIdLength) { "invalid active profile ID" }
                        add(id)
                    }
                }
            } ?: profiles.mapTo(linkedSetOf(), AppConfig::id)
            return ProfileRunPlan(profiles, activeIds).normalized()
        }
    }
}

internal fun profileOutboundTag(profileId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(profileId.encodeToByteArray())
    return "profile-" + digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
}

private const val BalancedOutboundTag = "profile-pool"
internal const val DefaultOutboundDynamicPool = ""
internal const val DefaultOutboundDirect = "__direct__"

internal fun normalizeDefaultOutboundSelection(value: String): String {
    val normalized = value.trim()
    return if (normalized == DefaultOutboundDirect) DefaultOutboundDirect else DefaultOutboundDynamicPool
}

internal fun AppConfig.runtimeOutboundTag(): String {
    if (rawConfigJson.isBlank()) return profileOutboundTag(id)

    requireSafeJsonNesting(rawConfigJson)
    val root = JSONObject(rawConfigJson)
    val configuredDefault = root.optJSONObject("route")
        ?.optString("default_outbound")
        ?.trim()
        .orEmpty()
    if (configuredDefault.isNotBlank()) return configuredDefault

    root.optJSONArray("inbounds")?.let { inbounds ->
        for (index in 0 until inbounds.length()) {
            val outbound = inbounds.optJSONObject(index)?.optString("outbound")?.trim().orEmpty()
            if (outbound.isNotBlank()) return outbound
        }
    }
    val firstOutbound = root.optJSONArray("outbounds")
        ?.optJSONObject(0)
        ?.optString("tag")
        ?.trim()
        .orEmpty()
    require(firstOutbound.isNotBlank()) { "raw profile has no controllable outbound tag" }
    return firstOutbound
}

internal fun ProfileRunPlan.toBridgeJson(
    localListenAddr: String,
    localProxyProtocol: String = profiles.firstOrNull()?.upstreamProtocol ?: DefaultLocalProxyProtocol,
    verbose: Boolean = false,
    logLevel: String? = null,
    socks5Username: String = "",
    socks5Password: String = "",
    managedRouteRules: List<ManagedRouteRule> = emptyList(),
    routeLocalProxyTraffic: Boolean = false,
    defaultOutbound: String = DefaultOutboundDynamicPool,
): String {
    val plan = normalized()
    val rawProfile = plan.profiles.singleOrNull()?.takeIf { it.rawConfigJson.isNotBlank() }
    if (rawProfile != null) {
        return rawProfile.toBridgeJson(
            localListenAddr = localListenAddr,
            localProxyProtocol = localProxyProtocol,
            verbose = verbose,
            logLevel = logLevel,
            socks5Username = socks5Username,
            socks5Password = socks5Password,
            managedRouteRules = managedRouteRules,
            routeLocalProxyTraffic = routeLocalProxyTraffic,
        )
    }

    val tags = plan.profiles.associate { it.id to profileOutboundTag(it.id) }
    require(tags.values.distinct().size == tags.size) { "running profiles generated duplicate outbound tags" }
    val singleProfileRoots = plan.profiles.associate { profile ->
        profile.id to JSONObject(
            profile.toBridgeJson(
                localListenAddr = localListenAddr,
                localProxyProtocol = localProxyProtocol,
                verbose = verbose,
                logLevel = logLevel,
                socks5Username = socks5Username,
                socks5Password = socks5Password,
                routeLocalProxyTraffic = routeLocalProxyTraffic,
            ),
        )
    }
    val inbound = JSONObject(
        singleProfileRoots.getValue(plan.profiles.first().id).getJSONArray("inbounds").getJSONObject(0).toString(),
    )
        .put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
    val outbounds = JSONArray()
    plan.profiles.forEach { profile ->
        val outbound = JSONObject(
            singleProfileRoots.getValue(profile.id).getJSONArray("outbounds").getJSONObject(0).toString(),
        ).put("tag", tags.getValue(profile.id))
        outbounds.put(outbound)
    }
    outbounds.put(JSONObject().put("tag", "direct").put("type", "direct"))
    outbounds.put(
        JSONObject()
            .put("tag", BalancedOutboundTag)
            .put("type", "balance")
            .put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
            .put("affinity_ttl", "10m")
            .put(
                "members",
                JSONArray().apply {
                    plan.profiles.forEach { profile ->
                        put(JSONObject().put("outbound", tags.getValue(profile.id)).put("weight", 100))
                    }
                },
            ),
    )

    val activeRules = managedRouteRules.map(ManagedRouteRule::normalized)
        .filter { it.enabled && it.isValid() }
    require(activeRules.size <= MaxActiveManagedRouteRuleCount) {
        "at most $MaxActiveManagedRouteRuleCount managed route rules can be enabled"
    }
    val normalizedDefaultOutbound = normalizeDefaultOutboundSelection(defaultOutbound)
    val defaultOutboundTag = when {
        normalizedDefaultOutbound == DefaultOutboundDirect -> "direct"
        normalizedDefaultOutbound.isBlank() -> BalancedOutboundTag
        else -> tags[normalizedDefaultOutbound] ?: BalancedOutboundTag
    }
    val rules = JSONArray()
    if (activeRules.isNotEmpty()) {
        rules.put(
            JSONObject()
                .put("inbound", managedRouteInboundTags(routeLocalProxyTraffic))
                .put("network", JSONArray().put("tcp"))
                .put("domains", JSONArray().put("connectivitycheck.gstatic.com").put("cp.cloudflare.com"))
                .put("outbound", BalancedOutboundTag),
        )
    }
    activeRules.forEach { rule ->
        val targetProfile = rule.outboundProfileId.takeIf(String::isNotBlank)
            ?.let { profileId -> plan.profiles.firstOrNull { it.id == profileId } }
        val targetTag = when {
            rule.outbound == ManagedRouteOutbound.Direct -> "direct"
            rule.outboundProfileId.isBlank() -> BalancedOutboundTag
            targetProfile != null -> tags.getValue(targetProfile.id)
            // Profile deletion or legacy ID repair can leave a stale reference.
            // The route editor presents that state as the dynamic pool, so the
            // generated runtime must use the same fallback instead of silently
            // dropping the rule.
            else -> BalancedOutboundTag
        }
        val route = rule.putMatchCondition(
            JSONObject()
                .put("inbound", managedRouteInboundTags(routeLocalProxyTraffic))
                .put("outbound", targetTag),
        )
        if (targetProfile != null) {
            route.put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
        }
        rules.put(route)
    }
    val resolvedLogLevel = effectiveLogLevel(verbose, logLevel)
    return JSONObject()
        .put("log", JSONObject().put("level", resolvedLogLevel))
        .put("inbounds", JSONArray().put(inbound))
        .put("outbounds", outbounds)
        .put("route", JSONObject().put("default_outbound", defaultOutboundTag).put("rules", rules))
        .put("dns", defaultNativeTunDnsConfig(defaultOutboundTag))
        .toString()
}
