package com.tcptun.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val DefaultLocalProxyProtocol = "socks5"
internal const val AndroidTunInboundTag = "tun"
internal val LocalProxyProtocols = listOf(DefaultLocalProxyProtocol, "mixed")
internal val AndroidTunNetworks = listOf("tcp", "udp")

internal fun normalizeStoredServerHost(value: String): String =
    value.trim().removeSurrounding("[", "]")

internal fun migratedMuxUdpMode(
    tunnelSecurity: String,
    muxMode: String,
    muxUdpMode: String,
): String = muxUdpMode.trim().lowercase().ifBlank {
    // Older Android builds parsed reality-quic URIs before they persisted
    // mux_udp_mode. Current tcptun-go's generated client configuration uses
    // auto, while an omitted value becomes reliable.
    if (
        tunnelSecurity.trim().equals("reality-quic", ignoreCase = true) &&
        muxMode.trim().equals("quic", ignoreCase = true)
    ) {
        "auto"
    } else {
        ""
    }
}

internal fun defaultNativeTunDnsConfig(): JSONObject = JSONObject()
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

internal fun normalizeLocalProxyProtocol(value: String): String {
    return value.trim().lowercase().takeIf { it in LocalProxyProtocols }
        ?: DefaultLocalProxyProtocol
}

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
    val realityFingerprint: String = "",
    val realitySpiderX: String = "",
    val mux: Boolean = true,
    val muxMode: String = "",
    val muxUdpMode: String = "",
    val muxMaxSessions: Int = 0,
    val muxMaxStreamsPerSession: Int = 0,
    val muxWarmSpare: Int = 0,
    val muxInitialStreamReceiveWindow: Int = 0,
    val muxMaxStreamReceiveWindow: Int = 0,
    val muxInitialConnectionReceiveWindow: Int = 0,
    val muxMaxConnectionReceiveWindow: Int = 0,
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

    fun validate(): String? {
        if (name.isBlank()) return "profile name is required"
        if (rawConfigJson.isNotBlank()) return validateRawConfig(rawConfigJson)
        if (serverHost.isBlank()) return "server address is required"
        val port = serverPort.toIntOrNull() ?: return "server port must be a number"
        if (port !in 1..65535) return "server port must be between 1 and 65535"
        if (protocol !in Protocols) return "unsupported protocol: $protocol"
        if (transport !in Transports) return "unsupported transport: $transport"
        if (upstreamProtocol !in UpstreamProtocols) return "unsupported upstream protocol: $upstreamProtocol"
        val normalizedSecurity = tunnelSecurity.trim().lowercase()
        if (normalizedSecurity !in TunnelSecurityTypes) return "unsupported security: $tunnelSecurity"
        if (normalizedSecurity.isNotBlank() && tls) return "TLS cannot be combined with tunnel security"
        if (normalizedSecurity == "reality" && transport != "raw") {
            return "REALITY requires raw transport"
        }
        if (normalizedSecurity in RealitySecurityTypes) {
            if (sni.isBlank()) return "$normalizedSecurity requires SNI"
            if (realityPublicKey.isBlank()) return "$normalizedSecurity requires a public key"
        }
        if (normalizedSecurity == "reality-quic") {
            if (protocol != "native") return "reality-quic requires native protocol"
            if (transport != "raw") return "reality-quic requires raw transport"
            if (!mux || !muxMode.equals("quic", ignoreCase = true)) {
                return "reality-quic requires QUIC mux"
            }
            if (tlsInsecure) return "reality-quic cannot use TLS insecure"
            if (realityShortId.isBlank()) return "reality-quic requires a short ID"
            if (realitySpiderX.isNotBlank()) return "reality-quic does not use SpiderX"
            val fingerprint = realityFingerprint.trim().lowercase()
            if (fingerprint.isNotBlank() && fingerprint != "chrome") {
                return "reality-quic currently supports only the chrome fingerprint"
            }
        }
        val normalizedMuxMode = muxMode.trim().lowercase()
        if (normalizedMuxMode !in MuxModes) return "unsupported mux mode: $muxMode"
        val normalizedMuxUdpMode = muxUdpMode.trim().lowercase()
        if (normalizedMuxUdpMode !in MuxUdpModes) return "unsupported mux UDP mode: $muxUdpMode"
        val muxReceiveWindows = listOf(
            muxInitialStreamReceiveWindow,
            muxMaxStreamReceiveWindow,
            muxInitialConnectionReceiveWindow,
            muxMaxConnectionReceiveWindow,
        )
        if (!mux && (normalizedMuxMode.isNotBlank() || normalizedMuxUdpMode.isNotBlank() || muxMaxSessions != 0 || muxMaxStreamsPerSession != 0 || muxWarmSpare != 0 || muxReceiveWindows.any { it != 0 })) {
            return "mux must be enabled when mux pool limits are configured"
        }
        if (normalizedMuxMode != "quic" && normalizedMuxUdpMode.isNotBlank()) {
            return "mux UDP mode requires QUIC mux"
        }
        if (normalizedMuxMode != "quic" && muxReceiveWindows.any { it != 0 }) {
            return "mux receive windows require QUIC mux"
        }
        if (muxInitialStreamReceiveWindow !in 0..16_777_216 || muxMaxStreamReceiveWindow !in 0..16_777_216) {
            return "mux stream receive windows must be between 1 and 16777216 bytes when set"
        }
        if (muxInitialConnectionReceiveWindow !in 0..67_108_864 || muxMaxConnectionReceiveWindow !in 0..67_108_864) {
            return "mux connection receive windows must be between 1 and 67108864 bytes when set"
        }
        if (muxMaxStreamReceiveWindow != 0 && muxInitialStreamReceiveWindow > muxMaxStreamReceiveWindow) {
            return "mux initial stream receive window exceeds maximum"
        }
        if (muxMaxConnectionReceiveWindow != 0 && muxInitialConnectionReceiveWindow > muxMaxConnectionReceiveWindow) {
            return "mux initial connection receive window exceeds maximum"
        }
        if (muxMaxSessions !in 0..32) return "mux max sessions must be between 1 and 32 when set"
        if (muxMaxStreamsPerSession !in 0..4096) return "mux max streams must be between 1 and 4096 when set"
        val effectiveMuxSessions = muxMaxSessions.takeIf { it > 0 }
            ?: 4
        if (muxWarmSpare !in 0 until effectiveMuxSessions) {
            return "mux warm spares must be between 0 and max sessions minus 1"
        }
        if (normalizedMuxMode == "quic") {
            if (protocol != "native") return "QUIC mux requires native protocol"
            if (transport != "raw") return "QUIC mux requires raw transport"
            if (!tls && normalizedSecurity != "reality-quic") {
                return "QUIC mux requires TLS or reality-quic security"
            }
            if (normalizedSecurity.isNotBlank() && normalizedSecurity != "reality-quic") {
                return "QUIC mux cannot be combined with $normalizedSecurity security"
            }
        }
        if (path.isBlank()) return "path is required"
        return null
    }

    fun toBridgeJson(
        localListenAddr: String,
        localProxyProtocol: String = upstreamProtocol,
        verbose: Boolean = false,
        socks5Username: String = "",
        socks5Password: String = "",
        managedRouteRules: List<ManagedRouteRule> = emptyList(),
    ): String {
        if (rawConfigJson.isNotBlank()) {
            return prepareRawConfigForAndroid(
                localListenAddr = localListenAddr,
                localProxyProtocol = localProxyProtocol,
                socks5Username = socks5Username,
                socks5Password = socks5Password,
                verbose = verbose,
            )
        }
        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val normalizedListenAddr = joinHostPort(listenHost, listenPort)
        val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(localProxyProtocol)
        val networks = JSONArray().apply { AndroidTunNetworks.forEach(::put) }
        val inbound = JSONObject()
            .put("tag", "local")
            .put("type", normalizedLocalProxyProtocol)
            .put("address", JSONArray().put(normalizedListenAddr))
            .put("network", networks)
            .put("username", socks5Username)
            .put("password", socks5Password)

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("type", protocol)
            .put("address", JSONArray().put(serverAddr))
            .put("flow", flow.trim())
            .put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
            .put(
                "transport",
                JSONObject()
                    .put("type", transport)
                    .put("path", normalizedPath()),
            )
        if (mux) {
            proxy.put(
                "mux",
                JSONObject().apply {
                    muxMode.trim().takeIf { it.isNotBlank() }?.let { put("mode", it.lowercase()) }
                    muxUdpMode.trim().takeIf { it.isNotBlank() }?.let { put("udp_mode", it.lowercase()) }
                    if (muxMaxSessions > 0) put("max_sessions", muxMaxSessions)
                    if (muxMaxStreamsPerSession > 0) put("max_streams_per_session", muxMaxStreamsPerSession)
                    if (muxWarmSpare > 0) put("warm_spares", muxWarmSpare)
                    if (muxInitialStreamReceiveWindow > 0) put("initial_stream_receive_window", muxInitialStreamReceiveWindow)
                    if (muxMaxStreamReceiveWindow > 0) put("max_stream_receive_window", muxMaxStreamReceiveWindow)
                    if (muxInitialConnectionReceiveWindow > 0) {
                        put("initial_connection_receive_window", muxInitialConnectionReceiveWindow)
                    }
                    if (muxMaxConnectionReceiveWindow > 0) {
                        put("max_connection_receive_window", muxMaxConnectionReceiveWindow)
                    }
                },
            )
        }
        when (protocol) {
            "vless", "vmess" -> proxy.put("uuid", token.trim())
            "trojan" -> proxy.put("password", token.trim())
            else -> proxy.put("token", token.trim())
        }
        val normalizedSecurity = tunnelSecurity.trim().lowercase()
        if (normalizedSecurity in RealitySecurityTypes) {
            proxy.put(
                "security",
                JSONObject().apply {
                    put("type", normalizedSecurity)
                    .put("server_name", sni.trim())
                    .put("fingerprint", realityFingerprint.trim())
                    .put("public_key", realityPublicKey.trim())
                    .put("short_id", realityShortId.trim())
                    if (normalizedSecurity == "reality") put("spider_x", realitySpiderX.trim())
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
        }

        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("type", "direct"))

        val rules = JSONArray()
        val activeManagedRules = managedRouteRules.map(ManagedRouteRule::normalized)
            .filter { it.enabled && it.isValid() }
        if (activeManagedRules.any { it.outbound == ManagedRouteOutbound.Direct }) {
            rules.put(
                JSONObject()
                    .put("inbound", JSONArray().put(AndroidTunInboundTag))
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
                        .put("inbound", JSONArray().put(AndroidTunInboundTag))
                        .put("outbound", rule.outbound.tag),
                ),
            )
        }
        return JSONObject()
            .put("log", JSONObject().put("level", if (verbose) "debug" else "info"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("default_outbound", "proxy").put("rules", rules))
            .put("dns", defaultNativeTunDnsConfig())
            .toString()
    }

    private fun prepareRawConfigForAndroid(
        localListenAddr: String,
        localProxyProtocol: String,
        socks5Username: String,
        socks5Password: String,
        verbose: Boolean,
    ): String {
        val root = JSONObject(rawConfigJson)
        // tcptun-go removed the top-level discovery config in 30ff0a1 and now
        // rejects it through strict JSON decoding. Keep previously saved full
        // configs usable while preserving every currently supported section.
        root.remove("discovery")
        val dns = root.optJSONObject("dns")
        if (dns == null || dns.length() == 0) {
            root.put("dns", defaultNativeTunDnsConfig())
        }
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
        if (!route.has("default_outbound")) route.put("default_outbound", defaultOutbound)

        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val normalizedListenAddr = joinHostPort(listenHost, listenPort)
        val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(localProxyProtocol)
        val androidInbound = JSONObject()
            .put("tag", AndroidVpnInboundTag)
            .put("type", normalizedLocalProxyProtocol)
            .put("address", JSONArray().put(normalizedListenAddr))
            .put("network", JSONArray().apply { AndroidTunNetworks.forEach(::put) })
            .put("username", socks5Username)
            .put("password", socks5Password)
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
        root.put("inbounds", inbounds)
        if (!route.has("rules")) route.put("rules", JSONArray())
        remapInboundRules(route.optJSONArray("rules"), replacedInboundTags)
        if (verbose) {
            val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
            log.put("level", "debug")
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
                val parsed = runCatching { splitHostPort(value) }.getOrNull()
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
        migrateMux(inbound)
        migrateTransportSecurity(inbound)
    }

    private fun migrateOutboundToCurrentSchema(outbound: JSONObject) {
        val addresses = endpointAddresses(outbound, "server", null, "port")
        if (addresses.length() > 0) outbound.put("address", addresses)
        outbound.remove("server")
        outbound.remove("port")
        migrateMux(outbound)
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

    private fun migrateMux(endpoint: JSONObject) {
        val muxConfig = endpoint.optJSONObject("mux") ?: return
        if (muxConfig.has("enabled") && !muxConfig.optBoolean("enabled")) {
            endpoint.remove("mux")
        } else {
            muxConfig.remove("enabled")
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

    private fun remapInboundRules(rules: JSONArray?, replacedTags: Set<String>) {
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
            rule.put("inbound", JSONArray().apply { remapped.forEach(::put) })
        }
    }

    private fun validateRawConfig(raw: String): String? {
        return runCatching {
            val root = JSONObject(raw)
            if (root.has("mode")) return@runCatching "legacy mode-based configuration is not supported"
            val outbounds = root.optJSONArray("outbounds")
                ?: return@runCatching "outbounds is required"
            if (outbounds.length() == 0) return@runCatching "outbounds must not be empty"
            val hasTaggedOutbound = (0 until outbounds.length()).any { index ->
                outbounds.optJSONObject(index)?.optString("tag")?.isNotBlank() == true
            }
            if (hasTaggedOutbound) null else "at least one tagged outbound is required"
        }.getOrElse { it.message ?: "invalid tcptun JSON" }
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

    companion object {
        val Protocols = listOf("native", "vless", "vmess", "trojan")
        val Transports = listOf("raw", "ws", "h2", "h3")
        val UpstreamProtocols = LocalProxyProtocols
        val MuxModes = listOf("", "group", "quic")
        val MuxUdpModes = listOf("", "reliable", "auto", "datagram")
        val SecurityOptions = listOf("none", "tls", "reality", "reality-quic")
        val TunnelSecurityTypes = listOf("", "reality", "reality-quic")
        val RealitySecurityTypes = setOf("reality", "reality-quic")
        private const val AndroidVpnInboundTag = "android-vpn"
        private val WildcardHosts = setOf("0.0.0.0", "::", "*")
        private val LoopbackHosts = setOf("127.0.0.1", "::1", "localhost")

        fun load(context: Context): AppConfig {
            return ProfileStore.load(context).profiles.firstOrNull()
                ?: AppConfig()
        }

        fun fromJson(obj: JSONObject): AppConfig {
            val tunnelSecurity = obj.optString("tunnelSecurity").trim().lowercase()
            val muxMode = obj.optString("muxMode").trim().lowercase()
            val muxUdpMode = migratedMuxUdpMode(
                tunnelSecurity = tunnelSecurity,
                muxMode = muxMode,
                muxUdpMode = obj.optString("muxUdpMode"),
            )
            return AppConfig(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name", "proxy").ifBlank { "proxy" },
                serverHost = normalizeStoredServerHost(obj.optString("serverHost")),
                serverPort = obj.optString("serverPort", "9443"),
                protocol = obj.optString("protocol", "native"),
                transport = obj.optString("transport", "raw"),
                token = obj.optString("token"),
                sni = obj.optString("sni"),
                path = obj.optString("path", "/proxy"),
                tls = obj.optBoolean("tls", false),
                tlsInsecure = obj.optBoolean("tlsInsecure", false),
                tunnelSecurity = tunnelSecurity,
                flow = obj.optString("flow"),
                realityPublicKey = obj.optString("realityPublicKey"),
                realityShortId = obj.optString("realityShortId"),
                realityFingerprint = obj.optString("realityFingerprint"),
                realitySpiderX = obj.optString("realitySpiderX"),
                mux = obj.optBoolean("mux", true),
                muxMode = muxMode,
                muxUdpMode = muxUdpMode,
                muxMaxSessions = obj.optInt("muxMaxSessions", 0),
                muxMaxStreamsPerSession = obj.optInt("muxMaxStreamsPerSession", 0),
                muxWarmSpare = obj.optInt("muxWarmSpare", 0),
                muxInitialStreamReceiveWindow = obj.optInt("muxInitialStreamReceiveWindow", 0),
                muxMaxStreamReceiveWindow = obj.optInt("muxMaxStreamReceiveWindow", 0),
                muxInitialConnectionReceiveWindow = obj.optInt("muxInitialConnectionReceiveWindow", 0),
                muxMaxConnectionReceiveWindow = obj.optInt("muxMaxConnectionReceiveWindow", 0),
                upstreamProtocol = obj.optString("upstreamProtocol", "socks5"),
                rawConfigJson = obj.optString("rawConfigJson"),
            )
        }
    }

    fun save(context: Context) {
        val current = ProfileStore.load(context)
        val profiles = current.profiles.toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            profiles[index] = this
        } else {
            profiles.add(this)
        }
        ProfileStore.save(context, current.copy(profiles = profiles))
    }

    fun label(): String {
        if (rawConfigJson.isNotBlank()) return "TCPTUN / JSON"
        val security = when {
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
            val root = runCatching { JSONObject(rawConfigJson) }.getOrNull()
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
            .put("realityFingerprint", realityFingerprint)
            .put("realitySpiderX", realitySpiderX)
            .put("mux", mux)
            .put("muxMode", muxMode)
            .put("muxUdpMode", muxUdpMode)
            .put("muxMaxSessions", muxMaxSessions)
            .put("muxMaxStreamsPerSession", muxMaxStreamsPerSession)
            .put("muxWarmSpare", muxWarmSpare)
            .put("muxInitialStreamReceiveWindow", muxInitialStreamReceiveWindow)
            .put("muxMaxStreamReceiveWindow", muxMaxStreamReceiveWindow)
            .put("muxInitialConnectionReceiveWindow", muxInitialConnectionReceiveWindow)
            .put("muxMaxConnectionReceiveWindow", muxMaxConnectionReceiveWindow)
            .put("upstreamProtocol", upstreamProtocol)
            .put("rawConfigJson", rawConfigJson)
    }

    fun shareText(): String {
        return ProfileUriCodec.encode(this).orEmpty()
    }
}

data class ProfilesState(
    val profiles: List<AppConfig>,
    val activeIds: Set<String> = emptySet(),
) {
    val activeProfiles: List<AppConfig>
        get() = profiles.filter { it.id in activeIds }

    fun runPlan(): ProfileRunPlan {
        val activeRawProfile = activeProfiles.firstOrNull { it.rawConfigJson.isNotBlank() }
        val configuredProfiles = if (activeRawProfile != null) {
            listOf(activeRawProfile)
        } else {
            profiles.filter { it.rawConfigJson.isBlank() }
        }
        return ProfileRunPlan(configuredProfiles, activeIds).normalized()
    }
}

object ProfileStore {
    private const val PREFS = "tcptun"
    private const val KEY_STATE_VERSION = "profileStateVersion"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selectedProfileId"
    private const val KEY_ENABLED = "enabledProfileIds"
    private const val KEY_ACTIVE = "activeProfileIds"
    private const val STATE_VERSION_INDEPENDENT_OUTBOUNDS = 2

    fun load(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            val arr = JSONArray(raw)
            val profiles = buildList {
                for (i in 0 until arr.length()) {
                    val profile = AppConfig.fromJson(arr.getJSONObject(i))
                    if (profile.serverHost.isNotBlank() || profile.rawConfigJson.isNotBlank()) {
                        add(profile)
                    }
                }
            }
            val stateVersion = prefs.getInt(KEY_STATE_VERSION, 0)
            val storedActive = prefs.getString(KEY_ACTIVE, null)
                ?.takeIf { stateVersion >= STATE_VERSION_INDEPENDENT_OUTBOUNDS }
                ?.let { encoded ->
                    runCatching {
                        val active = JSONArray(encoded)
                        buildSet {
                            for (index in 0 until active.length()) add(active.getString(index))
                        }
                    }.getOrNull()
                }
            val knownIds = profiles.mapTo(mutableSetOf(), AppConfig::id)
            val activeIds = storedActive.orEmpty().filterTo(linkedSetOf()) { it in knownIds }
            val state = ProfilesState(profiles, activeIds)
            if (
                profiles.size != arr.length() ||
                stateVersion < STATE_VERSION_INDEPENDENT_OUTBOUNDS ||
                !prefs.contains(KEY_ACTIVE)
            ) {
                save(context, state)
            }
            return state
        }
        val migrated = migrateSingleProfile(context)
        save(context, migrated)
        return migrated
    }

    fun save(context: Context, state: ProfilesState) {
        val knownIds = state.profiles.mapTo(mutableSetOf(), AppConfig::id)
        val normalizedActiveIds = state.activeIds.filterTo(linkedSetOf()) { it in knownIds }
        val arr = JSONArray()
        state.profiles.forEach { arr.put(it.toJson()) }
        val active = JSONArray()
        state.profiles.filter { it.id in normalizedActiveIds }.forEach { active.put(it.id) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STATE_VERSION, STATE_VERSION_INDEPENDENT_OUTBOUNDS)
            .putString(KEY_PROFILES, arr.toString())
            .putString(KEY_ACTIVE, active.toString())
            .remove(KEY_SELECTED)
            .remove(KEY_ENABLED)
            .apply()
    }

    fun clearActive(context: Context) {
        val state = load(context)
        if (state.activeIds.isNotEmpty()) save(context, state.copy(activeIds = emptySet()))
    }

    private fun migrateSingleProfile(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldHost = prefs.getString("serverHost", "") ?: ""
        if (oldHost.isBlank()) {
            return ProfilesState(emptyList())
        }
        val profile = AppConfig(
            name = if (oldHost.isBlank()) "proxy" else "proxy",
            serverHost = oldHost,
            serverPort = prefs.getString("serverPort", "9443") ?: "9443",
            protocol = prefs.getString("protocol", "native") ?: "native",
            transport = prefs.getString("transport", "raw") ?: "raw",
            token = prefs.getString("token", "") ?: "",
            sni = prefs.getString("sni", "") ?: "",
            path = prefs.getString("path", "/proxy") ?: "/proxy",
            tls = prefs.getBoolean("tls", false),
            mux = prefs.getBoolean("mux", true),
        )
        return ProfilesState(listOf(profile))
    }
}
