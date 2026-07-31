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

/** Inbound tags matched by managed route rules. TUN always; local mixed/SOCKS when enabled. */
internal fun managedRouteInboundTags(routeLocalProxyTraffic: Boolean): JSONArray =
    JSONArray().apply {
        put(AndroidTunInboundTag)
        if (routeLocalProxyTraffic) put(AndroidLocalProxyInboundTag)
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

internal fun normalizeLogLevel(value: String): String {
    val normalized = value.trim().lowercase()
    if (normalized == "none") return "off"
    return normalized.takeIf { it in LogLevels } ?: DefaultLogLevel
}

internal fun effectiveLogLevel(verbose: Boolean, configuredLevel: String?): String {
    return if (verbose) "debug" else normalizeLogLevel(configuredLevel.orEmpty())
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
    val carrierMode: String = "",
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

    fun validate(): String? {
        if (!hasSafeStorageSize()) return "profile data is too large"
        if (name.isBlank()) return "profile name is required"
        if (rawConfigJson.isNotBlank()) return validateRawConfig(rawConfigJson)
        if (serverHost.isBlank()) return "server address is required"
        val port = serverPort.toIntOrNull() ?: return "server port must be a number"
        if (port !in 1..65535) return "server port must be between 1 and 65535"
        if (protocol !in Protocols) return "unsupported protocol: $protocol"
        if (transport !in Transports) return "unsupported transport: $transport"
        if (upstreamProtocol !in UpstreamProtocols) return "unsupported upstream protocol: $upstreamProtocol"
        if (protocol != "native" && token.isBlank()) return "$protocol credential is required"
        val normalizedSecurity = tunnelSecurity.trim().lowercase()
        if (normalizedSecurity !in TunnelSecurityTypes) return "unsupported security: $tunnelSecurity"
        if (normalizedSecurity.isNotBlank() && tls) return "TLS cannot be combined with tunnel security"
        if (normalizedSecurity == "reality" && transport != "raw") {
            return "reality requires raw transport"
        }
        if (normalizedSecurity == "reality") {
            if (sni.isBlank()) return "$normalizedSecurity requires SNI"
            if (realityPublicKey.isBlank()) return "$normalizedSecurity requires a public key"
        }
        val normalizedCarrierMode = carrierMode.trim().lowercase()
        if (normalizedCarrierMode !in CarrierModes) return "unsupported carrier mode: $carrierMode"
        val normalizedCarrierUdpMode = carrierUdpMode.trim().lowercase()
        if (normalizedCarrierUdpMode !in CarrierUdpModes) {
            return "unsupported carrier UDP mode: $carrierUdpMode"
        }
        val carrierReceiveWindows = listOf(
            carrierInitialStreamReceiveWindow,
            carrierMaxStreamReceiveWindow,
            carrierInitialConnectionReceiveWindow,
            carrierMaxConnectionReceiveWindow,
        )
        val resumableSettingsConfigured =
            muxResume || muxResumeTimeoutMillis != 0 || muxResumeBufferSize != 0
        if (!mux && (resumableSettingsConfigured || muxMaxSessions != 0 || muxMaxStreamsPerSession != 0 || muxWarmSpare != 0)) {
            return "mux must be enabled when mux options are configured"
        }
        if (!muxResume && (muxResumeTimeoutMillis != 0 || muxResumeBufferSize != 0)) {
            return "mux resume must be enabled when resume limits are configured"
        }
        if (muxResume) {
            if (protocol != "native") return "mux resume requires native protocol"
            if (transport != "raw") return "mux resume requires raw transport"
            if (normalizedSecurity != "reality") {
                return "mux resume requires reality automatic TCP/QUIC security"
            }
            if (normalizedCarrierMode != "auto") {
                return "mux resume requires automatic carrier mode"
            }
        }
        if (muxResumeTimeoutMillis !in 0..300_000) {
            return "mux resume timeout must be between 100 and 300000 milliseconds when set"
        }
        if (muxResumeTimeoutMillis in 1..99) {
            return "mux resume timeout must be between 100 and 300000 milliseconds when set"
        }
        if (muxResumeBufferSize !in 0..67_108_864) {
            return "mux resume buffer must be between 65536 and 67108864 bytes when set"
        }
        if (muxResumeBufferSize in 1..65_535) {
            return "mux resume buffer must be between 65536 and 67108864 bytes when set"
        }
        if (normalizedCarrierMode !in setOf("quic", "auto") && normalizedCarrierUdpMode.isNotBlank()) {
            return "carrier UDP mode requires QUIC or automatic carrier mode"
        }
        if (normalizedCarrierMode !in setOf("quic", "auto") && carrierReceiveWindows.any { it != 0 }) {
            return "carrier receive windows require QUIC or automatic carrier mode"
        }
        if (carrierInitialStreamReceiveWindow !in 0..16_777_216 || carrierMaxStreamReceiveWindow !in 0..16_777_216) {
            return "carrier stream receive windows must be between 1 and 16777216 bytes when set"
        }
        if (carrierInitialConnectionReceiveWindow !in 0..67_108_864 || carrierMaxConnectionReceiveWindow !in 0..67_108_864) {
            return "carrier connection receive windows must be between 1 and 67108864 bytes when set"
        }
        if (carrierMaxStreamReceiveWindow != 0 && carrierInitialStreamReceiveWindow > carrierMaxStreamReceiveWindow) {
            return "carrier initial stream receive window exceeds maximum"
        }
        if (carrierMaxConnectionReceiveWindow != 0 && carrierInitialConnectionReceiveWindow > carrierMaxConnectionReceiveWindow) {
            return "carrier initial connection receive window exceeds maximum"
        }
        if (muxMaxSessions !in 0..32) return "mux max sessions must be between 1 and 32 when set"
        if (muxMaxStreamsPerSession !in 0..4096) return "mux max streams must be between 1 and 4096 when set"
        val effectiveMuxSessions = muxMaxSessions.takeIf { it > 0 }
            ?: 4
        if (muxWarmSpare !in 0 until effectiveMuxSessions) {
            return "mux warm spares must be between 0 and max sessions minus 1"
        }
        if (normalizedCarrierMode in setOf("quic", "auto")) {
            if (protocol != "native") return "$normalizedCarrierMode carrier requires native protocol"
            if (transport != "raw") return "$normalizedCarrierMode carrier requires raw transport"
            if (!mux) return "$normalizedCarrierMode carrier requires mux"
            if (normalizedCarrierMode == "auto" && normalizedSecurity != "reality") {
                return "automatic carrier requires reality security"
            }
            if (normalizedCarrierMode == "quic" && !tls && normalizedSecurity != "reality") {
                return "QUIC carrier requires TLS or reality security"
            }
            if (normalizedSecurity == "reality") {
                if (realityShortId.isBlank()) return "$normalizedCarrierMode carrier requires a short ID"
                val fingerprint = realityFingerprint.trim().lowercase()
                if (fingerprint.isNotBlank() && fingerprint != "chrome") {
                    return "$normalizedCarrierMode carrier currently supports only the chrome fingerprint"
                }
            }
        }
        if (path.isBlank()) return "path is required"
        return null
    }

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
            realityFingerprint,
            realitySpiderX,
            carrierMode,
            carrierUdpMode,
            upstreamProtocol,
        ).all { it.length <= MaxProfileUriLength }
    }

    fun toBridgeJson(
        localListenAddr: String,
        localProxyProtocol: String = upstreamProtocol,
        verbose: Boolean = false,
        logLevel: String? = null,
        socks5Username: String = "",
        socks5Password: String = "",
        managedRouteRules: List<ManagedRouteRule> = emptyList(),
        routeLocalProxyTraffic: Boolean = false,
    ): String {
        if (rawConfigJson.isNotBlank()) {
            return prepareRawConfigForAndroid(
                localListenAddr = localListenAddr,
                localProxyProtocol = localProxyProtocol,
                socks5Username = socks5Username,
                socks5Password = socks5Password,
                verbose = verbose,
                logLevel = logLevel,
                routeLocalProxyTraffic = routeLocalProxyTraffic,
            )
        }
        val (listenHost, listenPort) = splitHostPort(localListenAddr)
        val normalizedListenAddr = joinHostPort(listenHost, listenPort)
        val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(localProxyProtocol)
        val networks = JSONArray().apply { AndroidTunNetworks.forEach(::put) }
        val inbound = JSONObject()
            .put("tag", AndroidLocalProxyInboundTag)
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
        val normalizedCarrierMode = carrierMode.trim().lowercase()
        val normalizedCarrierUdpMode = carrierUdpMode.trim().lowercase()
        if (
            normalizedCarrierMode.isNotBlank() ||
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
        when (protocol) {
            "vless", "vmess" -> proxy.put("uuid", token.trim())
            "trojan" -> proxy.put("password", token.trim())
            else -> proxy.put("token", token.trim())
        }
        val normalizedSecurity = tunnelSecurity.trim().lowercase()
        if (normalizedSecurity == "reality") {
            proxy.put(
                "security",
                JSONObject().apply {
                    put("type", "reality")
                    .put("server_name", sni.trim())
                    .put("fingerprint", realityFingerprint.trim())
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
            .put("dns", defaultNativeTunDnsConfig())
            .toString()
    }

    private fun prepareRawConfigForAndroid(
        localListenAddr: String,
        localProxyProtocol: String,
        socks5Username: String,
        socks5Password: String,
        verbose: Boolean,
        logLevel: String?,
        routeLocalProxyTraffic: Boolean,
    ): String {
        requireSafeJsonNesting(rawConfigJson)
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
        remapInboundRules(
            rules = route.optJSONArray("rules"),
            replacedTags = replacedInboundTags,
            routeLocalProxyTraffic = routeLocalProxyTraffic,
        )
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

    private fun validateRawConfig(raw: String): String? {
        return runRecoverableCatching {
            requireSafeJsonNesting(raw)
            val root = JSONObject(raw)
            if (root.has("mode")) return@runRecoverableCatching "legacy mode-based configuration is not supported"
            val outbounds = root.optJSONArray("outbounds")
                ?: return@runRecoverableCatching "outbounds is required"
            if (outbounds.length() == 0) return@runRecoverableCatching "outbounds must not be empty"
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
        val CarrierModes = listOf("", "tcp", "auto", "quic")
        val CarrierUdpModes = listOf("", "reliable", "auto", "datagram")
        val SecurityOptions = listOf("none", "tls", "reality")
        val TunnelSecurityTypes = listOf("", "reality")
        val RealitySecurityTypes = setOf("reality")
        private const val AndroidVpnInboundTag = "android-vpn"
        private val WildcardHosts = setOf("0.0.0.0", "::", "*")
        private val LoopbackHosts = setOf("127.0.0.1", "::1", "localhost")

        fun load(context: Context): AppConfig {
            return ProfileStore.load(context).profiles.firstOrNull()
                ?: AppConfig()
        }

        fun fromJson(obj: JSONObject): AppConfig {
            val protocol = obj.optString("protocol", "native")
            val mux = obj.optBoolean("mux", true)
            val currentCarrierSchema =
                obj.has("carrierMode") ||
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
                realityFingerprint = obj.optString("realityFingerprint"),
                realitySpiderX = obj.optString("realitySpiderX"),
                mux = mux,
                carrierMode = migrated.carrierMode,
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
        val current = ProfileStore.load(context)
        val profiles = current.profiles.toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            profiles[index] = this
        } else {
            profiles.add(this)
        }
        return ProfileStore.save(context, current.copy(profiles = profiles))
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
            .put("realityFingerprint", realityFingerprint)
            .put("realitySpiderX", realitySpiderX)
            .put("mux", mux)
            .put("carrierMode", carrierMode)
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

internal data class ProfileStoreSnapshot(
    val state: ProfilesState,
    val mutationRevision: Long,
)

object ProfileStore {
    private data class EncodedState(
        val profiles: String,
        val activeIds: String,
    )

    private const val PREFS = "tcptun"
    private const val KEY_STATE_VERSION = "profileStateVersion"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selectedProfileId"
    private const val KEY_ENABLED = "enabledProfileIds"
    private const val KEY_ACTIVE = "activeProfileIds"
    private const val STATE_VERSION_INDEPENDENT_OUTBOUNDS = 2
    private val mutationRevision = AtomicLong()

    internal fun currentMutationRevision(): Long = mutationRevision.get()

    @Synchronized
    internal fun runIfRevisionCurrent(
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
        action: () -> Unit,
    ): Boolean = synchronized(commitLock) {
        if (mutationRevision.get() != expectedMutationRevision || !canCommit()) {
            false
        } else {
            action()
            true
        }
    }

    @Synchronized
    internal fun snapshot(context: Context): ProfileStoreSnapshot {
        val state = loadRecoveringInternal(context.applicationContext ?: context)
        return ProfileStoreSnapshot(state, mutationRevision.get())
    }

    @Synchronized
    fun load(context: Context): ProfilesState = loadRecoveringInternal(context.applicationContext ?: context)

    private fun loadRecoveringInternal(context: Context): ProfilesState = try {
        loadInternal(context)
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        // A malformed preference must not crash Activity/Service startup. Do not
        // overwrite here: the same boundary also catches transient storage errors,
        // and replacing valid profiles with an empty state would turn recovery into
        // data loss. The next successful explicit save repairs the stored value.
        runRecoverableCatching {
            TcptunState.appendLog("profile storage unavailable: ${failureDescription(error)}")
        }
        ProfilesState(emptyList())
    }

    private fun loadInternal(context: Context): ProfilesState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            if (raw.length > MaxStoredProfilesLength) return ProfilesState(emptyList())
            requireSafeJsonNesting(raw)
            val arr = JSONArray(raw)
            if (arr.length() > MaxStoredProfileCount) return ProfilesState(emptyList())
            var repaired = false
            val seenIds = mutableSetOf<String>()
            val profiles = buildList {
                for (i in 0 until arr.length()) {
                    val json = arr.optJSONObject(i)
                    if (json == null) {
                        repaired = true
                        continue
                    }
                    val decoded = runRecoverableCatching { AppConfig.fromJson(json) }.getOrNull()
                    if (decoded == null || !decoded.hasSafeStorageSize() ||
                        (decoded.serverHost.isBlank() && decoded.rawConfigJson.isBlank())
                    ) {
                        repaired = true
                        continue
                    }
                    val storedId = decoded.id.trim()
                    val normalizedId = storedId
                        .takeIf { it.isNotBlank() && it.length <= MaxProfileIdLength && seenIds.add(it) }
                        ?: generateUniqueProfileId(seenIds).also { repaired = true }
                    if (normalizedId != decoded.id) repaired = true
                    add(decoded.copy(id = normalizedId))
                }
            }
            val stateVersion = runRecoverableCatching { prefs.getInt(KEY_STATE_VERSION, 0) }.getOrDefault(0)
            val storedActive = runRecoverableCatching { prefs.getString(KEY_ACTIVE, null) }.getOrNull()
                ?.takeIf { stateVersion >= STATE_VERSION_INDEPENDENT_OUTBOUNDS }
                ?.let { encoded ->
                    runRecoverableCatching {
                        if (encoded.length > MaxStoredProfilesLength) error("active profile data is too large")
                        requireSafeJsonNesting(encoded)
                        val active = JSONArray(encoded)
                        buildSet {
                            for (index in 0 until active.length()) {
                                active.optString(index)
                                    .trim()
                                    .takeIf { it.isNotBlank() && it.length <= MaxProfileIdLength }
                                    ?.let(::add)
                            }
                        }
                    }.getOrNull()
                }
            val knownIds = profiles.mapTo(mutableSetOf(), AppConfig::id)
            val activeIds = storedActive.orEmpty().filterTo(linkedSetOf()) { it in knownIds }
            val state = ProfilesState(profiles, activeIds)
            if (
                repaired ||
                profiles.size != arr.length() ||
                stateVersion < STATE_VERSION_INDEPENDENT_OUTBOUNDS ||
                !runRecoverableCatching { prefs.contains(KEY_ACTIVE) }.getOrDefault(false)
            ) {
                save(context, state)
            }
            return state
        }
        val migrated = migrateSingleProfile(context)
        save(context, migrated)
        return migrated
    }

    @Synchronized
    fun save(context: Context, state: ProfilesState): Result<Unit> = runRecoverableCatching {
        writeState(context.applicationContext ?: context, state)
    }

    private fun encodeState(state: ProfilesState): EncodedState {
        require(state.profiles.size <= MaxStoredProfileCount) { "too many profiles" }
        require(state.profiles.all(AppConfig::hasSafeStorageSize)) { "profile data is too large" }
        val seenIds = mutableSetOf<String>()
        val normalizedProfiles = state.profiles.map { profile ->
            val storedId = profile.id.trim()
            val normalizedId = storedId
                .takeIf { it.isNotBlank() && it.length <= MaxProfileIdLength && seenIds.add(it) }
                ?: generateUniqueProfileId(seenIds)
            if (profile.id == normalizedId) profile else profile.copy(id = normalizedId)
        }
        val knownIds = normalizedProfiles.mapTo(mutableSetOf(), AppConfig::id)
        val normalizedActiveIds = state.activeIds.filterTo(linkedSetOf()) { it in knownIds }
        val arr = JSONArray()
        normalizedProfiles.forEach { arr.put(it.toJson()) }
        val active = JSONArray()
        normalizedProfiles.filter { it.id in normalizedActiveIds }.forEach { active.put(it.id) }
        val encodedProfiles = arr.toString()
        require(encodedProfiles.length <= MaxStoredProfilesLength) { "stored profile data is too large" }
        return EncodedState(encodedProfiles, active.toString())
    }

    private fun writeState(context: Context, state: ProfilesState) {
        writeEncodedState(context, encodeState(state))
    }

    private fun writeEncodedState(context: Context, encoded: EncodedState) {
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STATE_VERSION, STATE_VERSION_INDEPENDENT_OUTBOUNDS)
            .putString(KEY_PROFILES, encoded.profiles)
            .putString(KEY_ACTIVE, encoded.activeIds)
            .remove(KEY_SELECTED)
            .remove(KEY_ENABLED)
            .commit()
        check(committed) { "failed to persist profile state" }
        mutationRevision.incrementAndGet()
    }

    @Synchronized
    fun clearActive(context: Context): Result<Unit> {
        val state = load(context)
        return if (state.activeIds.isNotEmpty()) {
            save(context, state.copy(activeIds = emptySet()))
        } else {
            Result.success(Unit)
        }
    }

    @Synchronized
    internal fun replaceActiveIdsIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        expectedActiveIds: Set<String>,
        replacementActiveIds: Set<String>,
        commitLock: Any? = null,
        canCommit: () -> Boolean = { true },
    ): Result<Boolean> = runRecoverableCatching {
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        val appContext = context.applicationContext ?: context
        val current = loadRecoveringInternal(appContext)
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        if (current.activeIds != expectedActiveIds) {
            return@runRecoverableCatching false
        }
        val encoded = encodeState(current.copy(activeIds = replacementActiveIds))
        guardedWrite(
            context = appContext,
            encoded = encoded,
            expectedMutationRevision = expectedMutationRevision,
            commitLock = commitLock,
            canCommit = canCommit,
        )
    }

    @Synchronized
    internal fun clearActiveIfCurrent(
        context: Context,
        expectedMutationRevision: Long,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean> = runRecoverableCatching {
        if (mutationRevision.get() != expectedMutationRevision) return@runRecoverableCatching false
        val appContext = context.applicationContext ?: context
        val current = loadRecoveringInternal(appContext)
        if (mutationRevision.get() != expectedMutationRevision) return@runRecoverableCatching false
        if (current.activeIds.isEmpty()) return@runRecoverableCatching true
        val encoded = encodeState(current.copy(activeIds = emptySet()))
        guardedWrite(
            context = appContext,
            encoded = encoded,
            expectedMutationRevision = expectedMutationRevision,
            commitLock = commitLock,
            canCommit = canCommit,
        )
    }

    @Synchronized
    internal fun alignActiveIdsWithPlanIfCurrent(
        context: Context,
        expectedMutationRevision: Long?,
        plan: ProfileRunPlan,
        commitLock: Any,
        canCommit: () -> Boolean,
    ): Result<Boolean> = runRecoverableCatching {
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        val appContext = context.applicationContext ?: context
        val current = loadRecoveringInternal(appContext)
        if (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) {
            return@runRecoverableCatching false
        }
        val currentById = current.profiles.associateBy(AppConfig::id)
        if (
            plan.activeIds.any { it !in currentById } ||
            plan.profiles.any { profile -> currentById[profile.id] != profile }
        ) {
            return@runRecoverableCatching false
        }
        if (current.activeIds == plan.activeIds) return@runRecoverableCatching true
        val encoded = encodeState(current.copy(activeIds = plan.activeIds))
        guardedWrite(
            context = appContext,
            encoded = encoded,
            expectedMutationRevision = expectedMutationRevision,
            commitLock = commitLock,
            canCommit = canCommit,
        )
    }

    private fun guardedWrite(
        context: Context,
        encoded: EncodedState,
        expectedMutationRevision: Long?,
        commitLock: Any?,
        canCommit: () -> Boolean,
    ): Boolean {
        val writeIfCurrent = {
            if (
                (expectedMutationRevision != null && mutationRevision.get() != expectedMutationRevision) ||
                !canCommit()
            ) {
                false
            } else {
                writeEncodedState(context, encoded)
                true
            }
        }
        return if (commitLock == null) writeIfCurrent() else synchronized(commitLock) { writeIfCurrent() }
    }

    @Synchronized
    internal fun saveIfCurrent(
        context: Context,
        expected: ProfileStoreSnapshot,
        next: ProfilesState,
    ): Result<ProfilesState?> = runRecoverableCatching {
        if (mutationRevision.get() != expected.mutationRevision) return@runRecoverableCatching null
        val appContext = context.applicationContext ?: context
        val current = loadRecoveringInternal(appContext)
        if (
            mutationRevision.get() != expected.mutationRevision ||
            current != expected.state
        ) {
            return@runRecoverableCatching null
        }
        writeState(appContext, next)
        loadRecoveringInternal(appContext)
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

    private fun generateUniqueProfileId(seenIds: MutableSet<String>): String {
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (!seenIds.add(id))
        return id
    }
}
