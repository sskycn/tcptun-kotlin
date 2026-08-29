package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal const val DefaultLocalProxyProtocol = "socks5"
internal const val DefaultLogLevel = "info"
internal const val AndroidTunInboundTag = "tun"
internal const val AndroidLocalProxyInboundTag = "local"
internal val LocalProxyProtocols = listOf(DefaultLocalProxyProtocol, "mixed")
internal val LogLevels = listOf("debug", DefaultLogLevel, "warn", "error", "off")
internal val AndroidTunNetworks = listOf("tcp", "udp")
internal const val MaxProfileIdLength = 256
internal const val DefaultEchPorts = "443"
internal val RemovedTunnelProtocols = setOf("vless", "vmess", "trojan")

internal fun unsupportedTunnelProtocolMessage(protocol: String): String =
    "tcptun-go v0.4.1 no longer supports ${protocol.trim().lowercase()}"

/** Inbound tags matched by managed route rules. TUN always; local mixed/SOCKS when enabled. */
internal fun managedRouteInboundTags(routeLocalProxyTraffic: Boolean): JSONArray =
    JSONArray().apply {
        put(AndroidTunInboundTag)
        if (routeLocalProxyTraffic) put(AndroidLocalProxyInboundTag)
    }

private fun JSONObject.putLocalProxyUsers(users: List<LocalProxyUser>) {
    remove("username")
    remove("password")
    remove("users")
    if (users.isNotEmpty()) {
        put("users", JSONArray().apply {
            users.forEach { user ->
                put(JSONObject().put("username", user.username).put("password", user.password))
            }
        })
    }
}

internal fun normalizeStoredServerHost(value: String): String =
    value.trim().removeSurrounding("[", "]")

internal data class MigratedCarrierFields(
    val tunnelSecurity: String,
    val carrierMode: String,
    val carrierUdpMode: String,
)

internal fun migratedCarrierFields(
    tunnelSecurity: String,
    protocol: String,
    mux: Boolean,
    carrierMode: String,
    carrierUdpMode: String,
    legacyMuxSchema: Boolean,
): MigratedCarrierFields {
    val legacySecurity = tunnelSecurity.trim().lowercase()
    val security = when (legacySecurity) {
        "reality-tcp", "reality-quic" -> "reality"
        else -> legacySecurity
    }
    val mode = when {
        legacySecurity == "reality-tcp" -> "tcp"
        legacySecurity == "reality-quic" -> "quic"
        carrierMode.trim().equals("group", ignoreCase = true) ->
            if (protocol == "native" && security == "reality" && mux) "auto" else "tcp"
        legacyMuxSchema &&
            carrierMode.isBlank() &&
            protocol == "native" &&
            security == "reality" &&
            mux -> "auto"
        else -> carrierMode.trim().lowercase()
    }
    val udpMode = carrierUdpMode.trim().lowercase().ifBlank {
        if (legacySecurity == "reality-quic") "auto" else ""
    }
    return MigratedCarrierFields(
        tunnelSecurity = security,
        carrierMode = mode,
        carrierUdpMode = udpMode,
    )
}

internal fun defaultNativeTunDnsConfig(outboundTag: String = ""): JSONObject = JSONObject()
    .put(
        "servers",
        JSONArray()
            .put("1.1.1.1")
            .put("[2606:4700:4700::1111]:53"),
    )
    .put("strategy", "prefer_ipv4")
    .put(
        "fake_ip",
        JSONObject()
            .put("enabled", true)
            .put("ipv4_range", "198.18.0.0/15")
            .put("ipv6_range", "fc00::/18")
            .put("capacity", 65_536)
            .put("ttl", "10m"),
    )
    .apply { outboundTag.trim().takeIf(String::isNotBlank)?.let { put("outbound", it) } }

internal fun normalizeLocalProxyProtocol(value: String): String {
    return value.trim().lowercase().takeIf { it in LocalProxyProtocols }
        ?: DefaultLocalProxyProtocol
}

internal fun normalizeLogLevel(value: String): String {
    val normalized = value.trim().lowercase()
    if (normalized == "none") return "off"
    return normalized.takeIf { it in LogLevels } ?: DefaultLogLevel
}

internal fun effectiveLogLevel(verbose: Boolean, configuredLevel: String?): String {
    return if (verbose) "debug" else normalizeLogLevel(configuredLevel.orEmpty())
}

internal fun parseEchPorts(value: String): List<Int> {
    val tokens = value.trim()
        .takeIf(String::isNotBlank)
        ?.split(Regex("[,\\s]+"))
        ?.filter(String::isNotBlank)
        .orEmpty()
    require(tokens.size <= 32) { "ECH ports must contain at most 32 values" }
    val ports = tokens.map { token ->
        token.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("ECH ports must be numbers between 1 and 65535")
    }
    require(ports.distinct().size == ports.size) { "ECH ports must not contain duplicates" }
    return ports
}

internal fun AppConfig.hasEchClientHelloSettings(): Boolean =
    echEnabled || echPublicName.isNotBlank() || echPublicKey.isNotBlank() || echPorts.isNotBlank()

data class AppConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "proxy",
    val serverHost: String = "",
    val serverPort: String = "9443",
    val protocol: String = "native",
    val transport: String = "raw",
    val token: String = "",
    val sni: String = "",
    val path: String = "/proxy",
    val tls: Boolean = false,
    val tlsInsecure: Boolean = false,
    val tunnelSecurity: String = "",
    val flow: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val echEnabled: Boolean = false,
    val echPublicName: String = "",
    val echPublicKey: String = "",
    val echPorts: String = "",
    val mux: Boolean = true,
    val carrierMode: String = "",
    val carrierPrefer: String = "",
    val carrierUdpMode: String = "",
    val muxResume: Boolean = false,
    val muxResumeTimeoutMillis: Int = 0,
    val muxResumeBufferSize: Int = 0,
    val muxMaxSessions: Int = 0,
    val muxMaxStreamsPerSession: Int = 0,
    val muxWarmSpare: Int = 0,
    val carrierInitialStreamReceiveWindow: Int = 0,
    val carrierMaxStreamReceiveWindow: Int = 0,
    val carrierInitialConnectionReceiveWindow: Int = 0,
    val carrierMaxConnectionReceiveWindow: Int = 0,
    val upstreamProtocol: String = "socks5",
    val rawConfigJson: String = "",
) {
    val serverAddr: String
        get() {
            val host = serverHost.trim()
            val port = serverPort.trim()
            val authorityHost = when {
                host.startsWith("[") && host.endsWith("]") -> host
                host.contains(":") -> "[$host]"
                else -> host
            }
            return "$authorityHost:$port"
        }

    fun validate(): String? = validationError()

    internal fun hasSafeStorageSize(): Boolean {
        if (id.length > MaxProfileIdLength || rawConfigJson.length > MaxProfileImportLength) return false
        if (rawConfigJson.isNotBlank() && runRecoverableCatching {
                requireSafeJsonNesting(rawConfigJson)
            }.isFailure
        ) {
            return false
        }
        return listOf(
            name,
            serverHost,
            serverPort,
            protocol,
            transport,
            token,
            sni,
            path,
            tunnelSecurity,
            flow,
            realityPublicKey,
            realityShortId,
            realitySpiderX,
            echPublicName,
            echPublicKey,
            echPorts,
            carrierMode,
            carrierPrefer,
            carrierUdpMode,
            upstreamProtocol,
        ).all { it.length <= MaxProfileUriLength }
    }

    fun toBridgeJson(
        localListenAddr: String,
        localProxyProtocol: String = upstreamProtocol,
        verbose: Boolean = false,
        logLevel: String? = null,
        localProxyUsers: List<LocalProxyUser> = emptyList(),
        managedRouteRules: List<ManagedRouteRule> = emptyList(),
        routeLocalProxyTraffic: Boolean = false,
    ): String {
        if (rawConfigJson.isNotBlank()) {
            return prepareRawConfigForAndroid(
                localListenAddr = localListenAddr,
                localProxyProtocol = localProxyProtocol,
                localProxyUsers = localProxyUsers,
                verbose = verbose,
                logLevel = logLevel,
                managedRouteRules = managedRouteRules,
                routeLocalProxyTraffic = routeLocalProxyTraffic,
            )
        }
        validationError()?.let { error -> throw IllegalArgumentException(error) }
        require(protocol.trim().equals("native", ignoreCase = true)) {
            if (protocol.trim().lowercase() in RemovedTunnelProtocols) {
                unsupportedTunnelProtocolMessage(protocol)
            } else {
                "unsupported protocol: $protocol"
            }
        }
        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val normalizedListenAddr = joinHostPort(listenHost, listenPort)
        val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(localProxyProtocol)
        val networks = JSONArray().apply { AndroidTunNetworks.forEach(::put) }
        validateLocalProxyUsers(localProxyUsers)
        val inbound = JSONObject()
            .put("tag", AndroidLocalProxyInboundTag)
            .put("type", normalizedLocalProxyProtocol)
            .put("address", JSONArray().put(normalizedListenAddr))
            .put("network", networks)
            .apply { putLocalProxyUsers(localProxyUsers) }

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("type", "native")
            .put("address", JSONArray().put(serverAddr))
            .put("flow", flow.trim())
            .put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
            .put(
                "transport",
                JSONObject()
                    .put("type", transport)
                    .put("path", normalizedPath()),
            )
        if (echEnabled) {
            proxy.put(
                "client_hello",
                JSONObject()
                    .put("type", "ech")
                    .put("public_name", echPublicName.trim())
                    .put("public_key", echPublicKey.trim())
                    .apply {
                        val ports = parseEchPorts(echPorts)
                        if (ports.isNotEmpty()) {
                            put("ports", JSONArray().apply { ports.forEach(::put) })
                        }
                    },
            )
        }
        val normalizedCarrierMode = carrierMode.trim().lowercase()
        val normalizedCarrierPrefer = carrierPrefer.trim().lowercase()
        val normalizedCarrierUdpMode = carrierUdpMode.trim().lowercase()
        if (
            normalizedCarrierMode.isNotBlank() ||
            normalizedCarrierPrefer.isNotBlank() ||
            normalizedCarrierUdpMode.isNotBlank() ||
            carrierInitialStreamReceiveWindow > 0 ||
            carrierMaxStreamReceiveWindow > 0 ||
            carrierInitialConnectionReceiveWindow > 0 ||
            carrierMaxConnectionReceiveWindow > 0
        ) {
            proxy.put(
                "carrier",
                JSONObject().apply {
                    if (normalizedCarrierMode.isNotBlank()) put("mode", normalizedCarrierMode)
                    if (normalizedCarrierPrefer.isNotBlank()) put("prefer", normalizedCarrierPrefer)
                    if (normalizedCarrierUdpMode.isNotBlank()) put("udp_mode", normalizedCarrierUdpMode)
                    if (carrierInitialStreamReceiveWindow > 0) {
                        put("initial_stream_receive_window", carrierInitialStreamReceiveWindow)
                    }
                    if (carrierMaxStreamReceiveWindow > 0) {
                        put("max_stream_receive_window", carrierMaxStreamReceiveWindow)
                    }
                    if (carrierInitialConnectionReceiveWindow > 0) {
                        put("initial_connection_receive_window", carrierInitialConnectionReceiveWindow)
                    }
                    if (carrierMaxConnectionReceiveWindow > 0) {
                        put("max_connection_receive_window", carrierMaxConnectionReceiveWindow)
                    }
                },
            )
        }
        if (mux) {
            proxy.put(
                "mux",
                JSONObject().apply {
                    put("enabled", true)
                    if (muxResume) put("resume", true)
                    if (muxResumeTimeoutMillis > 0) put("resume_timeout", "${muxResumeTimeoutMillis}ms")
                    if (muxResumeBufferSize > 0) put("resume_buffer_size", muxResumeBufferSize)
                    if (muxMaxSessions > 0) put("max_sessions", muxMaxSessions)
                    if (muxMaxStreamsPerSession > 0) put("max_streams_per_session", muxMaxStreamsPerSession)
                    if (muxWarmSpare > 0) put("warm_spares", muxWarmSpare)
                },
            )
        }
        proxy.put("token", token.trim())
        val normalizedSecurity = tunnelSecurity.trim().lowercase()
        if (normalizedSecurity == "reality") {
            proxy.put(
                "security",
                JSONObject().apply {
                    put("type", "reality")
                    .put("server_name", sni.trim())
                    .put("public_key", realityPublicKey.trim())
                    .put("short_id", realityShortId.trim())
                    .put("spider_x", realitySpiderX.trim())
                },
            )
        } else if (tls) {
            proxy.put(
                "security",
                JSONObject()
                    .put("type", "tls")
                    .put("server_name", sni.trim())
                    .put("insecure", tlsInsecure),
            )
        } else if (echEnabled) {
            proxy.put("security", JSONObject().put("type", "none"))
        }

        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("type", "direct"))

        val rules = JSONArray()
        val activeManagedRules = managedRouteRules.map(ManagedRouteRule::normalized)
            .filter { it.enabled && it.isValid() }
        require(activeManagedRules.size <= MaxActiveManagedRouteRuleCount) {
            "at most $MaxActiveManagedRouteRuleCount managed route rules can be enabled"
        }
        if (activeManagedRules.any { it.outbound == ManagedRouteOutbound.Direct }) {
            rules.put(
                JSONObject()
                    .put("inbound", managedRouteInboundTags(routeLocalProxyTraffic))
                    .put("network", JSONArray().put("tcp"))
                    .put(
                        "domains",
                        JSONArray()
                            .put("connectivitycheck.gstatic.com")
                            .put("cp.cloudflare.com"),
                    )
                    .put("outbound", "proxy"),
            )
        }
        activeManagedRules.forEach { rule ->
            rules.put(
                rule.putMatchCondition(
                    JSONObject()
                        .put("inbound", managedRouteInboundTags(routeLocalProxyTraffic))
                        .put("outbound", rule.outbound.tag),
                ),
            )
        }
        val resolvedLogLevel = effectiveLogLevel(verbose, logLevel)
        return JSONObject()
            .put("log", JSONObject().put("level", resolvedLogLevel))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("default_outbound", "proxy").put("rules", rules))
            .put("dns", defaultNativeTunDnsConfig("proxy"))
            .toString()
    }

    private fun prepareRawConfigForAndroid(
        localListenAddr: String,
        localProxyProtocol: String,
        localProxyUsers: List<LocalProxyUser>,
        verbose: Boolean,
        logLevel: String?,
        managedRouteRules: List<ManagedRouteRule>,
        routeLocalProxyTraffic: Boolean,
    ): String {
        requireSafeJsonNesting(rawConfigJson)
        val root = JSONObject(rawConfigJson)
        validateRawCoreCompatibility(root)?.let { throw IllegalArgumentException(it) }
        // tcptun-go removed the top-level discovery config in 30ff0a1 and now
        // rejects it through strict JSON decoding. Keep previously saved full
        // configs usable while preserving every currently supported section.
        root.remove("discovery")
        val dns = root.optJSONObject("dns")
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("outbounds is required")
        require(outbounds.length() > 0) { "outbounds must not be empty" }

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val defaultOutbound = route.optString("default_outbound").trim().ifBlank {
            val existingInbounds = root.optJSONArray("inbounds")
            var inferred = ""
            if (existingInbounds != null) {
                for (index in 0 until existingInbounds.length()) {
                    inferred = existingInbounds.optJSONObject(index)?.optString("outbound")?.trim().orEmpty()
                    if (inferred.isNotBlank()) break
                }
            }
            inferred.ifBlank { outbounds.optJSONObject(0)?.optString("tag")?.trim().orEmpty() }
        }
        require(defaultOutbound.isNotBlank()) { "route.default_outbound or a tagged outbound is required" }
        if (route.optString("default_outbound").trim().isBlank()) {
            route.put("default_outbound", defaultOutbound)
        }

        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val normalizedListenAddr = joinHostPort(listenHost, listenPort)
        val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(localProxyProtocol)
        validateLocalProxyUsers(localProxyUsers)
        val androidInbound = JSONObject()
            .put("tag", AndroidVpnInboundTag)
            .put("type", normalizedLocalProxyProtocol)
            .put("address", JSONArray().put(normalizedListenAddr))
            .put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
            .apply { putLocalProxyUsers(localProxyUsers) }
        val inbounds = JSONArray().put(androidInbound)
        val replacedInboundTags = mutableSetOf(AndroidVpnInboundTag, AndroidTunInboundTag)
        root.optJSONArray("inbounds")?.let { existing ->
            for (index in 0 until existing.length()) {
                val inbound = existing.optJSONObject(index) ?: continue
                val tag = inbound.optString("tag").trim()
                if (
                    tag == AndroidVpnInboundTag ||
                    tag == AndroidTunInboundTag ||
                    inboundConflictsWithAndroidListener(inbound, listenHost, listenPort)
                ) {
                    if (tag.isNotBlank()) replacedInboundTags += tag
                } else {
                    migrateInboundToAddressArray(inbound)
                    inbounds.put(inbound)
                }
            }
        }
        for (index in 0 until outbounds.length()) {
            outbounds.optJSONObject(index)?.let { outbound ->
                migrateOutboundToCurrentSchema(outbound)
                ensureAndroidTunOutboundNetworks(outbound)
            }
        }
        migrateRemovedDirectFirstOutbounds(route, outbounds)
        if (dns == null || dns.length() == 0) {
            root.put("dns", defaultNativeTunDnsConfig(route.optString("default_outbound").trim()))
        }
        root.put("inbounds", inbounds)
        val existingRules = when {
            !route.has("rules") -> JSONArray()
            else -> route.optJSONArray("rules")
                ?: throw IllegalArgumentException("route.rules must be an array")
        }
        remapInboundRules(
            rules = existingRules,
            replacedTags = replacedInboundTags,
            routeLocalProxyTraffic = routeLocalProxyTraffic,
        )

        val activeManagedRules = managedRouteRules.map(ManagedRouteRule::normalized)
            .filter { it.enabled && it.isValid() }
        require(activeManagedRules.size <= MaxActiveManagedRouteRuleCount) {
            "at most $MaxActiveManagedRouteRuleCount managed route rules can be enabled"
        }
        val connectivityRuleCount = if (
            activeManagedRules.any { it.outbound == ManagedRouteOutbound.Direct }
        ) {
            1
        } else {
            0
        }
        val finalRuleCount = existingRules.length() + activeManagedRules.size + connectivityRuleCount
        require(finalRuleCount <= MaxRuntimeRouteRuleCount) {
            "at most $MaxRuntimeRouteRuleCount total route rules can be configured"
        }

        val runtimeDefaultOutbound = route.optString("default_outbound").trim()
        require(runtimeDefaultOutbound.isNotBlank()) { "route.default_outbound is required" }
        val managedDirectOutbound = if (connectivityRuleCount > 0) {
            findOrAddManagedDirectOutbound(outbounds)
        } else {
            null
        }
        val mergedRules = JSONArray()
        if (connectivityRuleCount > 0) {
            mergedRules.put(
                JSONObject()
                    .put("inbound", managedRouteInboundTags(routeLocalProxyTraffic))
                    .put("network", JSONArray().put("tcp"))
                    .put(
                        "domains",
                        JSONArray()
                            .put("connectivitycheck.gstatic.com")
                            .put("cp.cloudflare.com"),
                    )
                    .put("outbound", runtimeDefaultOutbound),
            )
        }
        activeManagedRules.forEach { rule ->
            val targetOutbound = when (rule.outbound) {
                ManagedRouteOutbound.Proxy -> runtimeDefaultOutbound
                ManagedRouteOutbound.Direct -> requireNotNull(managedDirectOutbound)
            }
            mergedRules.put(
                rule.putMatchCondition(
                    JSONObject()
                        .put("inbound", managedRouteInboundTags(routeLocalProxyTraffic))
                        .put("outbound", targetOutbound),
                ),
            )
        }
        for (index in 0 until existingRules.length()) {
            mergedRules.put(existingRules.get(index))
        }
        route.put("rules", mergedRules)
        if (verbose || logLevel != null) {
            val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
            log.put("level", effectiveLogLevel(verbose, logLevel))
        }
        return root.toString()
    }

    private fun inboundConflictsWithAndroidListener(inbound: JSONObject, listenHost: String, listenPort: Int): Boolean {
        val addresses = when (val address = inbound.opt("address")) {
            is JSONArray -> buildList {
                for (index in 0 until address.length()) address.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
            is String -> listOf(address.trim()).filter(String::isNotBlank)
            else -> emptyList()
        }
        if (addresses.any { value ->
                val parsed = runRecoverableCatching { splitHostPort(value) }.getOrNull()
                parsed != null && parsed.second == listenPort && listenerHostsOverlap(parsed.first, listenHost)
            }
        ) {
            return true
        }
        if (inbound.optInt("port", -1) != listenPort) return false
        val hosts = buildList {
            inbound.optString("listen").trim().takeIf { it.isNotBlank() }?.let(::add)
            inbound.optJSONArray("listen_addresses")?.let { values ->
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        return hosts.any { listenerHostsOverlap(it, listenHost) }
    }

    private fun migrateInboundToAddressArray(inbound: JSONObject) {
        val addresses = endpointAddresses(inbound, "listen", "listen_addresses", "port")
        if (addresses.length() > 0) inbound.put("address", addresses)
        inbound.remove("listen")
        inbound.remove("listen_addresses")
        inbound.remove("port")
        inbound.remove("outbound")
        migrateCarrierAndMux(inbound)
        migrateTransportSecurity(inbound)
    }

    private fun migrateOutboundToCurrentSchema(outbound: JSONObject) {
        val addresses = endpointAddresses(outbound, "server", null, "port")
        if (addresses.length() > 0) outbound.put("address", addresses)
        outbound.remove("server")
        outbound.remove("port")
        migrateCarrierAndMux(outbound)
        migrateTransportSecurity(outbound)
    }

    private fun ensureAndroidTunOutboundNetworks(outbound: JSONObject) {
        val configured = outbound.optJSONArray("network") ?: return
        val networks = buildSet {
            for (index in 0 until configured.length()) {
                configured.optString(index).trim().lowercase().takeIf(String::isNotBlank)?.let(::add)
            }
        }
        if (AndroidTunNetworks.all(networks::contains)) return
        outbound.put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
    }

    private fun findOrAddManagedDirectOutbound(outbounds: JSONArray): String {
        val usedTags = mutableSetOf<String>()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag").trim()
            if (tag.isNotBlank()) usedTags += tag
            if (tag.isNotBlank() && outbound.optString("type").trim() == "direct") return tag
        }

        val baseTag = "android-managed-direct"
        var candidate = baseTag
        var suffix = 2
        while (candidate in usedTags) {
            candidate = "$baseTag-$suffix"
            suffix += 1
        }
        outbounds.put(JSONObject().put("tag", candidate).put("type", "direct"))
        return candidate
    }

    private fun migrateRemovedDirectFirstOutbounds(route: JSONObject, outbounds: JSONArray) {
        val fallbackByTag = buildMap<String, String> {
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                if (!outbound.optString("type").trim().equals("direct-first", ignoreCase = true)) continue
                val tag = outbound.optString("tag").trim()
                val fallback = outbound.optString("fallback").trim()
                require(tag.isNotBlank() && fallback.isNotBlank()) {
                    "legacy direct-first outbound requires tag and fallback"
                }
                put(tag, fallback)
            }
        }
        if (fallbackByTag.isEmpty()) return

        fun replacement(tag: String): String {
            var current = tag.trim()
            val visited = mutableSetOf<String>()
            while (current in fallbackByTag) {
                require(visited.add(current)) { "legacy direct-first fallback cycle contains $current" }
                current = fallbackByTag.getValue(current)
            }
            return current
        }

        route.optString("default_outbound").trim().takeIf(String::isNotBlank)?.let {
            route.put("default_outbound", replacement(it))
        }
        route.optJSONArray("rules")?.let { rules ->
            for (index in 0 until rules.length()) {
                val rule = rules.optJSONObject(index) ?: continue
                rule.optString("outbound").trim().takeIf(String::isNotBlank)?.let {
                    rule.put("outbound", replacement(it))
                }
            }
        }
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            outbound.optString("via").trim().takeIf(String::isNotBlank)?.let {
                outbound.put("via", replacement(it))
            }
            outbound.optJSONArray("members")?.let { members ->
                for (memberIndex in 0 until members.length()) {
                    val member = members.optJSONObject(memberIndex) ?: continue
                    member.optString("outbound").trim().takeIf(String::isNotBlank)?.let {
                        member.put("outbound", replacement(it))
                    }
                }
            }
        }
        for (index in outbounds.length() - 1 downTo 0) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("type").trim().equals("direct-first", ignoreCase = true)) {
                outbounds.remove(index)
            }
        }
        require(outbounds.length() > 0) { "legacy direct-first migration left no supported outbounds" }
    }

    private fun endpointAddresses(
        endpoint: JSONObject,
        legacyHostKey: String,
        legacyHostsKey: String?,
        legacyPortKey: String,
    ): JSONArray {
        when (val current = endpoint.opt("address")) {
            is JSONArray -> return current
            is String -> if (current.isNotBlank()) return JSONArray().put(current.trim())
        }
        val port = endpoint.optInt(legacyPortKey, -1)
        if (port !in 0..65535) return JSONArray()
        val hosts = buildList {
            endpoint.optString(legacyHostKey).trim().takeIf(String::isNotBlank)?.let(::add)
            legacyHostsKey?.let { key ->
                endpoint.optJSONArray(key)?.let { values ->
                    for (index in 0 until values.length()) {
                        values.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
        }
        return JSONArray().apply { hosts.distinct().forEach { put(joinHostPort(it, port)) } }
    }

    private fun migrateCarrierAndMux(endpoint: JSONObject) {
        val muxConfig = endpoint.optJSONObject("mux")
        val securityConfig = endpoint.optJSONObject("security")
        val legacySecurity = securityConfig?.optString("type")?.trim()?.lowercase().orEmpty()
        val carrierConfig = endpoint.optJSONObject("carrier")
            ?: JSONObject().also { endpoint.put("carrier", it) }
        val legacyMuxSchema = muxConfig != null && !muxConfig.has("enabled")

        val carrierKeys = listOf(
            "mode",
            "udp_mode",
            "initial_stream_receive_window",
            "max_stream_receive_window",
            "initial_connection_receive_window",
            "max_connection_receive_window",
        )
        muxConfig?.let { mux ->
            carrierKeys.forEach { key ->
                if (!carrierConfig.has(key) && mux.has(key)) {
                    carrierConfig.put(key, mux.get(key))
                }
                mux.remove(key)
            }
        }

        val migrated = migratedCarrierFields(
            tunnelSecurity = legacySecurity,
            protocol = endpoint.optString("type").trim().lowercase(),
            mux = muxConfig != null && (!muxConfig.has("enabled") || muxConfig.optBoolean("enabled")),
            carrierMode = carrierConfig.optString("mode"),
            carrierUdpMode = carrierConfig.optString("udp_mode"),
            legacyMuxSchema = legacyMuxSchema,
        )
        if (legacySecurity == "reality-tcp" || legacySecurity == "reality-quic") {
            securityConfig?.put("type", migrated.tunnelSecurity)
        }
        if (migrated.carrierMode.isNotBlank()) {
            carrierConfig.put("mode", migrated.carrierMode)
        }
        if (migrated.carrierUdpMode.isNotBlank()) {
            carrierConfig.put("udp_mode", migrated.carrierUdpMode)
        }
        if (carrierConfig.length() == 0) endpoint.remove("carrier")

        if (muxConfig != null) {
            if (muxConfig.has("enabled") && !muxConfig.optBoolean("enabled")) {
                endpoint.remove("mux")
            } else {
                muxConfig.put("enabled", true)
            }
        }
    }

    private fun migrateTransportSecurity(endpoint: JSONObject) {
        val transportConfig = endpoint.optJSONObject("transport") ?: return
        val legacyTls = transportConfig.optBoolean("tls", false)
        val legacyValues = listOf("cert", "key", "server_name")
            .associateWith { key -> transportConfig.optString(key).trim() }
            .filterValues(String::isNotBlank)
        val legacyInsecure = transportConfig.optBoolean("insecure", false)
        if (legacyTls || legacyValues.isNotEmpty() || legacyInsecure) {
            val securityConfig = endpoint.optJSONObject("security")
                ?: JSONObject().also { endpoint.put("security", it) }
            if (legacyTls && securityConfig.optString("type").isBlank()) securityConfig.put("type", "tls")
            legacyValues.forEach { (key, value) ->
                if (!securityConfig.has(key)) securityConfig.put(key, value)
            }
            if (legacyInsecure && !securityConfig.has("insecure")) securityConfig.put("insecure", true)
        }
        listOf("tls", "cert", "key", "server_name", "insecure").forEach(transportConfig::remove)
    }

    private fun joinHostPort(host: String, port: Int): String {
        val normalized = host.trim().removeSurrounding("[", "]")
        return if (normalized.contains(':')) "[$normalized]:$port" else "$normalized:$port"
    }

    private fun listenerHostsOverlap(first: String, second: String): Boolean {
        fun normalize(host: String): String = host.trim().removeSurrounding("[", "]").lowercase()
        val left = normalize(first)
        val right = normalize(second)
        if (left == right) return true
        if (left in WildcardHosts || right in WildcardHosts) return true
        return left in LoopbackHosts && right in LoopbackHosts
    }

    private fun remapInboundRules(
        rules: JSONArray?,
        replacedTags: Set<String>,
        routeLocalProxyTraffic: Boolean,
    ) {
        if (rules == null || replacedTags.isEmpty()) return
        for (ruleIndex in 0 until rules.length()) {
            val rule = rules.optJSONObject(ruleIndex) ?: continue
            val tags = rule.optJSONArray("inbound") ?: continue
            val remapped = linkedSetOf<String>()
            for (tagIndex in 0 until tags.length()) {
                val tag = tags.optString(tagIndex).trim()
                if (tag.isBlank()) continue
                remapped += if (tag in replacedTags) AndroidTunInboundTag else tag
            }
            if (routeLocalProxyTraffic && AndroidTunInboundTag in remapped) {
                remapped += AndroidVpnInboundTag
            }
            rule.put("inbound", JSONArray().apply { remapped.forEach(::put) })
        }
    }

    internal fun validateRawConfig(raw: String): String? {
        return runRecoverableCatching {
            requireSafeJsonNesting(raw)
            val root = JSONObject(raw)
            if (root.has("mode")) return@runRecoverableCatching "legacy mode-based configuration is not supported"
            validateRawCoreCompatibility(root)?.let { return@runRecoverableCatching it }
            val outbounds = root.optJSONArray("outbounds")
                ?: return@runRecoverableCatching "outbounds is required"
            if (outbounds.length() == 0) return@runRecoverableCatching "outbounds must not be empty"
            val hasTaggedOutbound = (0 until outbounds.length()).any { index ->
                outbounds.optJSONObject(index)?.optString("tag")?.isNotBlank() == true
            }
            if (hasTaggedOutbound) null else "at least one tagged outbound is required"
        }.getOrElse { it.message ?: "invalid tcptun JSON" }
    }

    private fun validateRawCoreCompatibility(root: JSONObject): String? {
        listOf("inbounds", "outbounds").forEach { section ->
            val entries = root.optJSONArray(section) ?: return@forEach
            for (index in 0 until entries.length()) {
                val endpoint = entries.optJSONObject(index) ?: continue
                val type = endpoint.optString("type").trim().lowercase()
                if (type in RemovedTunnelProtocols) {
                    return "$section[$index]: ${unsupportedTunnelProtocolMessage(type)}"
                }
                if (section == "outbounds" && endpoint.has("uuid")) {
                    return "$section[$index].uuid was removed in tcptun-go v0.4.0"
                }
                if (endpoint.optJSONObject("security")?.has("fingerprint") == true) {
                    return "$section[$index].security.fingerprint was removed in tcptun-go v0.4.0"
                }
                val carrier = endpoint.optJSONObject("carrier") ?: continue
                val preference = carrier.optString("prefer").trim().lowercase()
                if (preference.isBlank()) continue
                if (section == "inbounds") {
                    return "$section[$index].carrier.prefer is outbound-only"
                }
                if (preference !in CarrierPreferences) {
                    return "$section[$index].carrier.prefer has unsupported value: $preference"
                }
                if (!carrier.optString("mode").trim().equals("auto", ignoreCase = true)) {
                    return "$section[$index].carrier.prefer requires carrier.mode=auto"
                }
            }
        }
        return null
    }

    private fun splitHostPort(address: String): Pair<String, Int> {
        val trimmed = address.trim()
        val separator = trimmed.lastIndexOf(':')
        require(separator > 0) { "invalid local listen address: $address" }
        val host = trimmed.substring(0, separator).removeSurrounding("[", "]")
        val port = trimmed.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("invalid local listen port: $address")
        require(port in 1..65535) { "invalid local listen port: $address" }
        return host to port
    }

    private fun normalizedPath(): String {
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    internal fun isValidEchPublicName(value: String): Boolean {
        val name = value.trim().lowercase().removeSuffix(".")
        if (name.isBlank() || name.length > 253 || ':' in name || '/' in name || '*' in name) return false
        if (name.split('.').all { it.toIntOrNull() != null }) return false
        fun Char.isAsciiLetterOrDigit(): Boolean =
            this in 'a'..'z' || this in '0'..'9'
        return name.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isAsciiLetterOrDigit() &&
                label.last().isAsciiLetterOrDigit() &&
                label.all { it.isAsciiLetterOrDigit() || it == '-' }
        }
    }

    companion object {
        val Protocols = listOf("native")
        val Transports = listOf("raw", "ws", "h2", "h3")
        val UpstreamProtocols = LocalProxyProtocols
        val CarrierModes = listOf("", "tcp", "auto", "quic")
        val CarrierPreferences = listOf("", "adaptive", "quic", "tcp")
        val CarrierUdpModes = listOf("", "reliable", "auto", "datagram")
        val SecurityOptions = listOf("none", "tls", "reality")
        val TunnelSecurityTypes = listOf("", "reality")
        val RealitySecurityTypes = setOf("reality")
        private const val AndroidVpnInboundTag = "android-vpn"
        private val WildcardHosts = setOf("0.0.0.0", "::", "*")
        private val LoopbackHosts = setOf("127.0.0.1", "::1", "localhost")
        private val SensitiveStorageFields = listOf(
            "token",
            "realityPublicKey",
            "realityShortId",
            "realitySpiderX",
            "echPublicKey",
            "rawConfigJson",
        )

        fun load(context: Context): AppConfig {
            return context.profileRepository().load(context).profiles.firstOrNull()
                ?: AppConfig()
        }

        fun fromJson(obj: JSONObject): AppConfig {
            val protocol = obj.optString("protocol", "native")
            val mux = obj.optBoolean("mux", true)
            val currentCarrierSchema =
                obj.has("carrierMode") ||
                    obj.has("carrierPrefer") ||
                    obj.has("carrierUdpMode") ||
                    obj.has("carrierInitialStreamReceiveWindow") ||
                    obj.has("carrierMaxStreamReceiveWindow") ||
                    obj.has("carrierInitialConnectionReceiveWindow") ||
                    obj.has("carrierMaxConnectionReceiveWindow")
            val migrated = migratedCarrierFields(
                tunnelSecurity = obj.optString("tunnelSecurity"),
                protocol = protocol,
                mux = mux,
                carrierMode = obj.optString("carrierMode").ifBlank { obj.optString("muxMode") },
                carrierUdpMode = obj.optString("carrierUdpMode").ifBlank { obj.optString("muxUdpMode") },
                legacyMuxSchema = !currentCarrierSchema,
            )
            return AppConfig(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name", "proxy").ifBlank { "proxy" },
                serverHost = normalizeStoredServerHost(obj.optString("serverHost")),
                serverPort = obj.optString("serverPort", "9443"),
                protocol = protocol,
                transport = obj.optString("transport", "raw"),
                token = obj.optString("token"),
                sni = obj.optString("sni"),
                path = obj.optString("path", "/proxy"),
                tls = obj.optBoolean("tls", false),
                tlsInsecure = obj.optBoolean("tlsInsecure", false),
                tunnelSecurity = migrated.tunnelSecurity,
                flow = obj.optString("flow"),
                realityPublicKey = obj.optString("realityPublicKey"),
                realityShortId = obj.optString("realityShortId"),
                realitySpiderX = obj.optString("realitySpiderX"),
                echEnabled = obj.optBoolean("echEnabled", false),
                echPublicName = obj.optString("echPublicName"),
                echPublicKey = obj.optString("echPublicKey"),
                echPorts = obj.optString("echPorts"),
                mux = mux,
                carrierMode = migrated.carrierMode,
                carrierPrefer = obj.optString("carrierPrefer"),
                carrierUdpMode = migrated.carrierUdpMode,
                muxResume = obj.optBoolean("muxResume", false),
                muxResumeTimeoutMillis = obj.optInt("muxResumeTimeoutMillis", 0),
                muxResumeBufferSize = obj.optInt("muxResumeBufferSize", 0),
                muxMaxSessions = obj.optInt("muxMaxSessions", 0),
                muxMaxStreamsPerSession = obj.optInt("muxMaxStreamsPerSession", 0),
                muxWarmSpare = obj.optInt("muxWarmSpare", 0),
                carrierInitialStreamReceiveWindow = obj.optInt(
                    "carrierInitialStreamReceiveWindow",
                    obj.optInt("muxInitialStreamReceiveWindow", 0),
                ),
                carrierMaxStreamReceiveWindow = obj.optInt(
                    "carrierMaxStreamReceiveWindow",
                    obj.optInt("muxMaxStreamReceiveWindow", 0),
                ),
                carrierInitialConnectionReceiveWindow = obj.optInt(
                    "carrierInitialConnectionReceiveWindow",
                    obj.optInt("muxInitialConnectionReceiveWindow", 0),
                ),
                carrierMaxConnectionReceiveWindow = obj.optInt(
                    "carrierMaxConnectionReceiveWindow",
                    obj.optInt("muxMaxConnectionReceiveWindow", 0),
                ),
                upstreamProtocol = obj.optString("upstreamProtocol", "socks5"),
                rawConfigJson = obj.optString("rawConfigJson"),
            )
        }
    }

    fun save(context: Context): Result<Unit> {
        return runRecoverableCatching {
            repeat(4) {
                val repository = context.profileRepository()
                val snapshot = repository.snapshot(context)
                val current = snapshot.requireAuthoritativeState()
                val profiles = current.profiles.toMutableList()
                val index = profiles.indexOfFirst { it.id == id }
                if (index >= 0) {
                    profiles[index] = this
                } else {
                    profiles.add(this)
                }
                val saved = repository.saveIfCurrent(
                    context,
                    snapshot,
                    current.copy(profiles = profiles),
                ).getOrThrow()
                if (saved != null) return@runRecoverableCatching
            }
            error("profile state changed repeatedly; please retry")
        }
    }

    fun label(): String {
        if (rawConfigJson.isNotBlank()) return "TCPTUN / JSON"
        val security = when {
            echEnabled -> "ech"
            tunnelSecurity.isNotBlank() -> tunnelSecurity
            sni.isNotBlank() && tls -> "tls"
            sni.isNotBlank() -> "reality"
            tls -> "tls"
            else -> transport
        }
        return if (security.isBlank()) protocol.uppercase() else "${protocol.uppercase()} / $security"
    }

    fun maskedAddress(): String {
        if (rawConfigJson.isNotBlank()) {
            val root = runRecoverableCatching {
                requireSafeJsonNesting(rawConfigJson)
                JSONObject(rawConfigJson)
            }.getOrNull()
            val inbounds = root?.optJSONArray("inbounds")?.length() ?: 0
            val outbounds = root?.optJSONArray("outbounds")?.length() ?: 0
            return "$inbounds inbounds · $outbounds outbounds"
        }
        val host = serverHost.trim()
        val masked = when {
            host.isIpv4Literal() -> "***.***.***.${host.substringAfterLast('.')}"
            host.contains(":") -> "***:${host.removeSurrounding("[", "]").substringAfterLast(':').ifEmpty { "0" }}"
            host.length <= 8 -> host
            else -> host.take(10) + ".***"
        }
        return "$masked : ${serverPort.trim()}"
    }

    private fun String.isIpv4Literal(): Boolean {
        val octets = split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull() in 0..255
        }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("serverHost", serverHost)
            .put("serverPort", serverPort)
            .put("protocol", protocol)
            .put("transport", transport)
            .put("token", token)
            .put("sni", sni)
            .put("path", path)
            .put("tls", tls)
            .put("tlsInsecure", tlsInsecure)
            .put("tunnelSecurity", tunnelSecurity)
            .put("flow", flow)
            .put("realityPublicKey", realityPublicKey)
            .put("realityShortId", realityShortId)
            .put("realitySpiderX", realitySpiderX)
            .put("echEnabled", echEnabled)
            .put("echPublicName", echPublicName)
            .put("echPublicKey", echPublicKey)
            .put("echPorts", echPorts)
            .put("mux", mux)
            .put("carrierMode", carrierMode)
            .put("carrierPrefer", carrierPrefer)
            .put("carrierUdpMode", carrierUdpMode)
            .put("muxResume", muxResume)
            .put("muxResumeTimeoutMillis", muxResumeTimeoutMillis)
            .put("muxResumeBufferSize", muxResumeBufferSize)
            .put("muxMaxSessions", muxMaxSessions)
            .put("muxMaxStreamsPerSession", muxMaxStreamsPerSession)
            .put("muxWarmSpare", muxWarmSpare)
            .put("carrierInitialStreamReceiveWindow", carrierInitialStreamReceiveWindow)
            .put("carrierMaxStreamReceiveWindow", carrierMaxStreamReceiveWindow)
            .put("carrierInitialConnectionReceiveWindow", carrierInitialConnectionReceiveWindow)
            .put("carrierMaxConnectionReceiveWindow", carrierMaxConnectionReceiveWindow)
            .put("upstreamProtocol", upstreamProtocol)
            .put("rawConfigJson", rawConfigJson)
    }

    /** Durable non-sensitive profile shape. External JSON/URI schemas continue to use [toJson]. */
    internal fun toPublicStorageJson(): JSONObject = toJson().apply {
        SensitiveStorageFields.forEach(::remove)
    }

    internal fun toSecretStorageJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("token", token)
        .put("realityPublicKey", realityPublicKey)
        .put("realityShortId", realityShortId)
        .put("realitySpiderX", realitySpiderX)
        .put("echPublicKey", echPublicKey)
        .put("rawConfigJson", rawConfigJson)

    internal fun withStorageSecrets(secrets: JSONObject?): AppConfig {
        if (secrets == null) return this
        return copy(
            token = secrets.optString("token"),
            realityPublicKey = secrets.optString("realityPublicKey"),
            realityShortId = secrets.optString("realityShortId"),
            realitySpiderX = secrets.optString("realitySpiderX"),
            echPublicKey = secrets.optString("echPublicKey"),
            rawConfigJson = secrets.optString("rawConfigJson"),
        )
    }

    fun shareText(): String {
        return ProfileUriCodec.encode(this).orEmpty()
    }

}
