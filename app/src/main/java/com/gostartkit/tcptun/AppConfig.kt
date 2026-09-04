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
internal val RemovedTunnelProtocols = setOf("vless", "vmess", "trojan")

internal fun unsupportedTunnelProtocolMessage(protocol: String): String =
    "tcptun-go v0.5.0 no longer supports ${protocol.trim().lowercase()}"

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
    val tls: Boolean = true,
    val tlsInsecure: Boolean = false,
    val tunnelSecurity: String = "",
    val flow: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
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
        if (id.length > MaxProfileIdLength) return false
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
                    .put("server_name", sni.trim().ifBlank { serverHost.trim() })
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
                    .put("server_name", sni.trim().ifBlank { serverHost.trim() })
                    .put("insecure", tlsInsecure),
            )
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

    private fun joinHostPort(host: String, port: Int): String {
        val normalized = host.trim().removeSurrounding("[", "]")
        return if (normalized.contains(':')) "[$normalized]:$port" else "$normalized:$port"
    }

    private fun normalizedPath(): String {
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    companion object {
        val Protocols = listOf("native")
        val Transports = listOf("raw", "ws", "h2", "h3")
        val UpstreamProtocols = LocalProxyProtocols
        val CarrierModes = listOf("", "tcp", "auto", "quic")
        val CarrierPreferences = listOf("", "adaptive", "quic", "tcp")
        val CarrierUdpModes = listOf("", "reliable", "auto", "datagram")
        val SecurityOptions = listOf("tls", "reality")
        val TunnelSecurityTypes = listOf("", "reality")
        val RealitySecurityTypes = setOf("reality")
        private val SensitiveStorageFields = listOf(
            "token",
            "realityPublicKey",
            "realityShortId",
            "realitySpiderX",
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

    internal fun withStorageSecrets(secrets: JSONObject?): AppConfig {
        if (secrets == null) return this
        return copy(
            token = secrets.optString("token"),
            realityPublicKey = secrets.optString("realityPublicKey"),
            realityShortId = secrets.optString("realityShortId"),
            realitySpiderX = secrets.optString("realitySpiderX"),
        )
    }

    fun shareText(): String {
        return ProfileUriCodec.encode(this).orEmpty()
    }

}
