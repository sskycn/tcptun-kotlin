package com.tcptun.client

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ProfileUriCodec {
    fun decode(raw: String): Result<AppConfig> {
        val trimmed = raw.trim()
        return runCatching {
            when {
                isCompactProfilePayload(trimmed) -> decodeCompactProfile(trimmed)
                ProfileDeepLinkCodec.isSupportedLink(trimmed) -> {
                    val profileUri = ProfileDeepLinkCodec.decode(trimmed).getOrThrow()
                    decode(profileUri).getOrThrow()
                }
                trimmed.startsWith("vmess://", ignoreCase = true) -> decodeVMess(trimmed)
                trimmed.startsWith("vless://", ignoreCase = true) -> decodeAuthorityProfile("vless", trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> decodeAuthorityProfile("trojan", trimmed)
                trimmed.startsWith("native://", ignoreCase = true) -> decodeAuthorityProfile("native", trimmed)
                trimmed.startsWith("tcptun://", ignoreCase = true) -> decodeAuthorityProfile("native", trimmed)
                trimmed.contains("{") && trimmed.contains("}") -> decodeJsonProfile(trimmed)
                else -> error("unsupported profile URI")
            }
        }
    }

    fun encode(config: AppConfig): String? {
        if (config.rawConfigJson.isNotBlank()) return null
        return when (config.protocol) {
            "native" -> encodeAuthorityProfile("native", config)
            "vless" -> encodeAuthorityProfile("vless", config)
            "trojan" -> encodeAuthorityProfile("trojan", config)
            "vmess" -> encodeVMess(config)
            else -> null
        }
    }

    /**
     * Compact text encoding for QR codes.
     * Format: t1|&lt;proto&gt;|&lt;token&gt;|&lt;host&gt;|&lt;port&gt;[|&lt;sec&gt;][|opts...][#name]
     *
     * Defaults mirror the fixed fields of `tcptun config &lt;protocol&gt;` client outbounds
     * (raw + reality + chrome + spx=/ + network tcp,udp + mux off; vless also flow vision).
     * Variable fields (credential, host/port, sni, pbk, short_id, …) are always encoded when set.
     * Decode fills omitted defaults; does not depend on runtime zero-value behavior.
     *
     * Falls back to the plain URI when compact is not shorter.
     */
    fun encodeForQr(config: AppConfig): String? {
        val plain = encode(config) ?: return null
        val compact = encodeCompactProfile(config) ?: return plain
        return if (compact.length < plain.length) compact else plain
    }

    private fun decodeAuthorityProfile(protocol: String, raw: String): AppConfig {
        val uri = Uri.parse(raw)
        val encodedUserInfo = uri.encodedUserInfo.orEmpty()
        val encodedCredential = if (encodedUserInfo.contains(':')) {
            if (protocol == "trojan") encodedUserInfo.substringAfter(':') else encodedUserInfo.substringBefore(':')
        } else {
            encodedUserInfo
        }
        val token = Uri.decode(encodedCredential).trim()
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        if (host.isBlank()) error("missing server host")
        if (port !in 1..65535) error("missing or invalid server port")
        if (protocol != "native" && token.isBlank()) error("missing $protocol credential")

        if (protocol == "native") {
            val version = uri.getQueryParameter("v").orEmpty().trim()
            if (version.isNotBlank() && version != TcptunUriVersion) error("unsupported tcptun URI version: $version")
            val legacyProtocol = uri.getQueryParameter("protocol").orEmpty().trim().lowercase()
            if (legacyProtocol.isNotBlank() && legacyProtocol != "native") {
                error("tcptun URI protocol must be native")
            }
        }
        val type = uri.getQueryParameter("type").orEmpty()
            .ifBlank { uri.getQueryParameter("transport").orEmpty() }
            .lowercase()
        val security = uri.getQueryParameter("security").orEmpty().lowercase()
        if (security !in setOf("", "none", "tls", "reality")) error("unsupported security: $security")
        val path = uri.getQueryParameter("path") ?: uri.getQueryParameter("spx") ?: "/proxy"
        val networks = parseNetworks(uri.getQueryParameter("network"))
        val udp = if (networks != null) "udp" in networks else uri.getBooleanParameterCompat("udp", false)
        return AppConfig(
            id = UUID.randomUUID().toString(),
            name = uri.fragment?.ifBlank { null } ?: host,
            serverHost = host,
            serverPort = port.toString(),
            protocol = protocol,
            transport = transportFromType(type),
            token = token,
            sni = uri.getQueryParameter("sni").orEmpty()
                .ifBlank { uri.getQueryParameter("serverName").orEmpty() },
            path = path.ifBlank { "/" },
            tls = security == "tls",
            tlsInsecure = uri.getBooleanParameterCompat("allowInsecure", false) ||
                uri.getBooleanParameterCompat("tlsInsecure", false) ||
                uri.getBooleanParameterCompat("insecure", false),
            tunnelSecurity = if (security == "reality") "reality" else "",
            flow = uri.getQueryParameter("flow").orEmpty(),
            realityPublicKey = uri.getQueryParameter("pbk").orEmpty(),
            realityShortId = uri.getQueryParameter("sid").orEmpty()
                .ifBlank { uri.getQueryParameter("reality_short_id").orEmpty() },
            realityFingerprint = uri.getQueryParameter("fp").orEmpty(),
            realitySpiderX = uri.getQueryParameter("spx").orEmpty(),
            mux = uri.getBooleanParameterCompat("mux", false),
            muxMode = uri.getQueryParameter("mux_mode").orEmpty().trim().lowercase(),
            muxMaxSessions = uri.getIntParameter("mux_max_sessions"),
            muxMaxStreamsPerSession = uri.getIntParameter("mux_max_streams_per_session"),
            muxWarmSpare = uri.getIntParameter("mux_warm_spares"),
            tunnelNetwork = networks?.joinToString(",").orEmpty(),
            udp = udp,
            upstreamProtocol = uri.getQueryParameter("upstream").orEmpty()
                .ifBlank { uri.getQueryParameter("upstream_protocol").orEmpty() }
                .ifBlank { "socks5" },
        )
    }

    private fun encodeAuthorityProfile(protocol: String, config: AppConfig): String? {
        val host = config.serverHost.trim()
        val port = config.serverPort.trim()
        if (host.isBlank() || port.isBlank()) return null
        val token = config.token.trim()
        if (protocol != "native" && token.isBlank()) return null

        val params = commonParams(config)
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encodeComponent(key)}=${encodeComponent(value)}"
        }
        val authorityHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val auth = if (token.isBlank()) authorityHost else "${encodeComponent(token)}@$authorityHost"
        val name = encodeComponent(config.name.ifBlank { host })
        return "$protocol://$auth:$port?$query#$name"
    }

    private fun decodeVMess(raw: String): AppConfig {
        val encoded = raw.substringAfter("://").substringBefore("#").trim()
        val obj = JSONObject(String(decodeBase64(encoded), StandardCharsets.UTF_8))
        val host = obj.optString("add")
        val port = obj.optString("port")
        if (host.isBlank()) error("missing VMess server host")
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) error("missing or invalid VMess server port")
        val tlsValue = obj.optString("tls").trim().lowercase()
        val security = when {
            tlsValue == "reality" -> "reality"
            tlsValue.isBlank() || tlsValue == "none" -> {
                if (obj.optString("security").equals("reality", ignoreCase = true)) "reality" else ""
            }
            else -> "tls"
        }
        val networks = parseNetworks(obj.optString("tcptun_network").ifBlank { obj.optString("network") })
        val mux = when {
            obj.has("tcptun_mux") -> obj.optBoolean("tcptun_mux", false)
            obj.has("mux") -> obj.optBoolean("mux", false)
            else -> false
        }
        return AppConfig(
            id = UUID.randomUUID().toString(),
            name = obj.optString("ps", host).ifBlank { host },
            serverHost = host,
            serverPort = port,
            protocol = "vmess",
            transport = transportFromType(obj.optString("net")),
            token = obj.optString("id"),
            sni = obj.optString("sni").ifBlank { obj.optString("host") },
            path = obj.optString("path", "/proxy").ifBlank { "/proxy" },
            tls = security == "tls",
            tlsInsecure = obj.optBoolean("allowInsecure", false) || obj.optBoolean("tlsInsecure", false),
            tunnelSecurity = if (security == "reality") "reality" else "",
            flow = obj.optString("tcptun_flow").ifBlank { obj.optString("flow") },
            realityPublicKey = obj.optString("pbk"),
            realityShortId = obj.optString("sid").ifBlank { obj.optString("reality_short_id") },
            realityFingerprint = obj.optString("fp"),
            realitySpiderX = obj.optString("spx"),
            mux = mux,
            muxMode = obj.optString("tcptun_mux_mode").ifBlank { obj.optString("mux_mode") }.lowercase(),
            muxMaxSessions = obj.optInt("tcptun_mux_max_sessions", obj.optInt("mux_max_sessions", 0)),
            muxMaxStreamsPerSession = obj.optInt(
                "tcptun_mux_max_streams_per_session",
                obj.optInt("mux_max_streams_per_session", 0),
            ),
            muxWarmSpare = obj.optInt("tcptun_mux_warm_spares", obj.optInt("mux_warm_spares", 0)),
            tunnelNetwork = networks?.joinToString(",").orEmpty(),
            udp = networks?.contains("udp") ?: obj.optBoolean("udp", false),
            upstreamProtocol = obj.optString("upstream").ifBlank {
                obj.optString("upstream_protocol", "socks5")
            },
        )
    }

    private fun decodeJsonProfile(raw: String): AppConfig {
        val obj = JSONObject(extractJsonObject(raw))
        if (obj.has("serverHost") || obj.has("serverPort")) {
            return AppConfig.fromJson(obj).copy(id = UUID.randomUUID().toString())
        }
        if (obj.has("inbounds") && obj.has("outbounds")) {
            val route = obj.optJSONObject("route")?.let { JSONObject(it.toString()) } ?: JSONObject()
            route.optJSONArray("rules")?.let { rules ->
                for (ruleIndex in 0 until rules.length()) {
                    val rule = rules.optJSONObject(ruleIndex) ?: continue
                    val inboundTags = rule.optJSONArray("inbound") ?: continue
                    if (inboundTags.length() > 0) {
                        rule.put("inbound", JSONArray().put(AndroidVpnInboundTag))
                    }
                }
            }
            val imported = JSONObject()
                .put("outbounds", obj.getJSONArray("outbounds"))
                .put("route", route)
            return AppConfig(
                id = UUID.randomUUID().toString(),
                name = "tcptun-json",
                rawConfigJson = imported.toString(2),
            )
        }
        if (!obj.has("server_addr") && !obj.has("tunnel_protocol")) {
            error("unsupported profile JSON")
        }
        return decodeTcptunClientJson(obj)
    }

    private fun decodeTcptunClientJson(obj: JSONObject): AppConfig {
        val serverAddr = obj.optString("server_addr").trim()
        if (serverAddr.isBlank()) error("missing client server_addr")
        val (host, port) = splitHostPort(serverAddr)
        val protocol = obj.optString("tunnel_protocol", "native").trim().lowercase().ifBlank { "native" }
        if (protocol !in AppConfig.Protocols) error("unsupported tunnel_protocol: $protocol")
        val tunnelSecurity = obj.optString("tunnel_security").trim().lowercase()
        val normalizedSecurity = when (tunnelSecurity) {
            "", "none", "tls" -> ""
            else -> tunnelSecurity
        }
        val sni = obj.optString("tunnel_tls_server_name").ifBlank {
            obj.optString("reality_server_name")
        }
        return AppConfig(
            id = UUID.randomUUID().toString(),
            name = obj.optString("name").ifBlank { host },
            serverHost = host,
            serverPort = port,
            protocol = protocol,
            transport = transportFromType(obj.optString("tunnel_transport", "raw")),
            token = obj.optString("token"),
            sni = sni,
            path = obj.optString("tunnel_path", "/proxy").ifBlank { "/proxy" },
            tls = obj.optBoolean("tunnel_tls", false) || tunnelSecurity == "tls",
            tlsInsecure = obj.optBoolean("tunnel_tls_insecure", false),
            tunnelSecurity = normalizedSecurity,
            flow = obj.optString("tunnel_flow"),
            realityPublicKey = obj.optString("reality_public_key"),
            realityShortId = obj.optString("reality_short_id").ifBlank {
                obj.optFirstString("reality_short_ids")
            },
            realityFingerprint = obj.optString("reality_fingerprint"),
            realitySpiderX = obj.optString("reality_spider_x"),
            mux = obj.optBoolean("tunnel_mux", true),
            muxMode = obj.optString("tunnel_mux_mode").lowercase(),
            muxMaxSessions = obj.optInt("tunnel_mux_max_sessions", 0),
            muxMaxStreamsPerSession = obj.optInt("tunnel_mux_max_streams_per_session", 0),
            muxWarmSpare = obj.optInt("tunnel_mux_warm_spares", 0),
            tunnelNetwork = obj.optString("tunnel_network"),
            udp = obj.optBoolean("enable_udp", true),
            upstreamProtocol = obj.optString("upstream_protocol", "socks5").ifBlank { "socks5" },
        )
    }

    private fun encodeVMess(config: AppConfig): String? {
        val host = config.serverHost.trim()
        val port = config.serverPort.trim()
        val token = config.token.trim()
        if (host.isBlank() || port.isBlank() || token.isBlank()) return null
        val obj = JSONObject()
            .put("v", "2")
            .put("ps", config.name.ifBlank { host })
            .put("add", host)
            .put("port", port)
            .put("id", token)
            .put("aid", "0")
            .put("scy", "auto")
            .put("net", typeFromTransport(config.transport))
            .put("type", "none")
            .put("host", config.sni)
            .put("path", config.path)
            .put("tls", if (config.tunnelSecurity == "reality") "reality" else if (config.tls) "tls" else "")
            .put("sni", config.sni)
            .put("allowInsecure", config.tlsInsecure)
            .put("tcptun_mux", config.mux)
            .put("tcptun_network", config.effectiveTunnelNetworks().joinToString(","))
        putJsonIfNotBlank(obj, "tcptun_mux_mode", config.muxMode)
        if (config.muxMaxSessions > 0) obj.put("tcptun_mux_max_sessions", config.muxMaxSessions)
        if (config.muxMaxStreamsPerSession > 0) {
            obj.put("tcptun_mux_max_streams_per_session", config.muxMaxStreamsPerSession)
        }
        if (config.muxWarmSpare > 0) obj.put("tcptun_mux_warm_spares", config.muxWarmSpare)
        putJsonIfNotBlank(obj, "tcptun_flow", config.flow)
        putJsonIfNotBlank(obj, "pbk", config.realityPublicKey)
        putJsonIfNotBlank(obj, "sid", config.realityShortId)
        putJsonIfNotBlank(obj, "fp", config.realityFingerprint)
        putJsonIfNotBlank(obj, "spx", config.realitySpiderX)
        return "vmess://" + Base64.encodeToString(obj.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    private fun commonParams(config: AppConfig): LinkedHashMap<String, String> {
        val params = linkedMapOf<String, String>()
        if (config.protocol == "native") params["v"] = TcptunUriVersion
        val security = when {
            config.tunnelSecurity == "reality" -> "reality"
            config.tls -> "tls"
            else -> "none"
        }
        params["security"] = security
        if (config.protocol == "vless") params["encryption"] = "none"
        if (security == "reality") {
            putIfNotBlank(params, "pbk", config.realityPublicKey)
            putIfNotBlank(params, "sid", config.realityShortId)
            putIfNotBlank(params, "fp", config.realityFingerprint)
            putIfNotBlank(params, "spx", config.realitySpiderX.ifBlank { config.path })
        }
        params["type"] = typeFromTransport(config.transport)
        putIfNotBlank(params, "flow", config.flow)
        putIfNotBlank(params, "sni", config.sni)
        if (config.tlsInsecure) params["insecure"] = "true"
        if (config.transport != "raw") putIfNotBlank(params, "path", config.path)
        params["network"] = config.effectiveTunnelNetworks().joinToString(",")
        params["mux"] = config.mux.toString()
        putIfNotBlank(params, "mux_mode", config.muxMode)
        if (config.muxMaxSessions > 0) params["mux_max_sessions"] = config.muxMaxSessions.toString()
        if (config.muxMaxStreamsPerSession > 0) {
            params["mux_max_streams_per_session"] = config.muxMaxStreamsPerSession.toString()
        }
        if (config.muxWarmSpare > 0) params["mux_warm_spares"] = config.muxWarmSpare.toString()
        return params
    }

    private fun decodeBase64(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            Base64.decode(padded, Base64.URL_SAFE)
        }
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) error("unsupported profile JSON")
        return raw.substring(start, end + 1)
    }

    private fun splitHostPort(value: String): Pair<String, String> {
        val trimmed = value.trim()
        if (trimmed.startsWith("[")) {
            val hostEnd = trimmed.indexOf(']')
            if (hostEnd <= 1 || hostEnd + 1 >= trimmed.length || trimmed[hostEnd + 1] != ':') {
                error("server_addr must be host:port")
            }
            val host = trimmed.substring(1, hostEnd)
            val port = trimmed.substring(hostEnd + 2)
            validatePort(port)
            return host to port
        }
        val portStart = trimmed.lastIndexOf(':')
        if (portStart <= 0 || portStart == trimmed.lastIndex) error("server_addr must be host:port")
        val host = trimmed.substring(0, portStart)
        val port = trimmed.substring(portStart + 1)
        validatePort(port)
        return host to port
    }

    private fun validatePort(port: String) {
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) error("missing or invalid server_addr port")
    }

    private fun transportFromType(type: String): String {
        return when (type.lowercase()) {
            "", "tcp", "raw" -> "raw"
            "ws", "websocket" -> "ws"
            "h2", "http", "httpupgrade" -> "h2"
            "h3", "quic" -> "h3"
            else -> error("unsupported transport: $type")
        }
    }

    private fun typeFromTransport(transport: String): String {
        return when (transport) {
            "raw" -> "raw"
            "ws" -> "ws"
            "h2" -> "h2"
            "h3" -> "h3"
            else -> error("unsupported transport: $transport")
        }
    }

    private fun parseNetworks(value: String?): Set<String>? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.split(',').mapTo(linkedSetOf()) { network ->
            network.trim().lowercase().also {
                if (it !in setOf("tcp", "udp")) error("unsupported network: $it")
            }
        }.also {
            if (it.isEmpty()) error("network must not be empty")
        }
    }

    private fun Uri.getBooleanParameterCompat(name: String, defaultValue: Boolean): Boolean {
        return when (getQueryParameter(name)?.lowercase()) {
            null, "" -> defaultValue
            "1", "t", "true", "yes" -> true
            "0", "f", "false", "no" -> false
            else -> error("invalid boolean parameter: $name")
        }
    }

    private fun Uri.getIntParameter(name: String): Int {
        val value = getQueryParameter(name)?.trim().orEmpty()
        if (value.isBlank()) return 0
        return value.toIntOrNull() ?: error("invalid integer parameter: $name")
    }

    private fun putIfNotBlank(params: MutableMap<String, String>, key: String, value: String) {
        if (value.isNotBlank()) params[key] = value
    }

    private fun putJsonIfNotBlank(obj: JSONObject, key: String, value: String) {
        if (value.isNotBlank()) obj.put(key, value)
    }

    private fun JSONObject.optFirstString(name: String): String {
        val arr = optJSONArray(name) ?: return ""
        return if (arr.length() > 0) arr.optString(0) else ""
    }

    private const val AndroidVpnInboundTag = "android-vpn"
    private const val TcptunUriVersion = "1"
    private const val CompactPayloadVersion = "t1"
    private const val CompactDefaultPath = "/proxy"
    private const val CompactDefaultUpstream = "socks5"
    private const val CompactDefaultNetwork = "tcp,udp"
    /**
     * Fixed fields from `tcptun config <protocol>` client outbounds
     * ([generatedRealityPair] / [tunnelOutboundTemplate] after Mux cleared):
     * raw transport, tcp+udp, reality, chrome, spider_x=/, mux off.
     * short_id / public_key / sni / credential are generated per run and are never defaulted.
     */
    private const val CompactDefaultSecurity = "r"
    private const val CompactDefaultFingerprint = "chrome"
    private const val CompactDefaultSpiderX = "/"
    private const val CompactDefaultVlessFlow = "xtls-rprx-vision"
    private val CompactSecurityCodes = setOf("n", "t", "r")

    private val CompactProtocols = mapOf(
        "native" to "n",
        "vless" to "v",
        "trojan" to "t",
        "vmess" to "m",
    )
    private val CompactProtocolsByCode = CompactProtocols.entries.associate { (protocol, code) -> code to protocol }
    private val CompactTransports = mapOf(
        "ws" to "w",
        "h2" to "h",
        "h3" to "q",
    )
    private val CompactTransportsByCode = CompactTransports.entries.associate { (transport, code) -> code to transport }

    private fun isCompactProfilePayload(value: String): Boolean {
        return value.startsWith("$CompactPayloadVersion|")
    }

    private fun encodeCompactProfile(config: AppConfig): String? {
        if (config.rawConfigJson.isNotBlank()) return null
        val protocol = config.protocol.trim().lowercase()
        val protocolCode = CompactProtocols[protocol] ?: return null
        val host = config.serverHost.trim()
        val port = config.serverPort.trim()
        if (host.isBlank() || port.isBlank()) return null
        val portNumber = port.toIntOrNull() ?: return null
        if (portNumber !in 1..65535) return null
        val token = config.token.trim()
        if (protocol != "native" && token.isBlank()) return null

        val security = when {
            config.tunnelSecurity == "reality" -> "r"
            config.tls -> "t"
            else -> "n"
        }
        val parts = mutableListOf(
            CompactPayloadVersion,
            protocolCode,
            escapeCompactField(token),
            escapeCompactField(host),
            port,
        )
        // security=reality is fixed in generated client outbounds.
        if (security != CompactDefaultSecurity) {
            parts += security
        }

        // transport.type=raw is fixed in the generator template.
        val transport = config.transport.trim().lowercase().ifBlank { "raw" }
        if (transport != "raw") {
            val transportCode = CompactTransports[transport] ?: return null
            parts += "y$transportCode"
            val path = config.path.trim().ifBlank { CompactDefaultPath }
            if (path != CompactDefaultPath) {
                parts += "p${escapeCompactField(path)}"
            }
        }

        putCompactIfNotBlank(parts, "s", config.sni)
        val flow = config.flow.trim()
        if (flow.isNotBlank() && !(protocol == "vless" && flow == CompactDefaultVlessFlow)) {
            parts += "f${escapeCompactField(flow)}"
        }
        if (security == "r") {
            putCompactIfNotBlank(parts, "k", config.realityPublicKey)
            // short_id is random in config generation — always encode when set.
            putCompactIfNotBlank(parts, "d", config.realityShortId)
            val fingerprint = config.realityFingerprint.trim()
            if (fingerprint.isNotBlank() &&
                !fingerprint.equals(CompactDefaultFingerprint, ignoreCase = true)
            ) {
                parts += "g${escapeCompactField(fingerprint)}"
            }
            val spiderX = config.realitySpiderX.trim()
            if (spiderX.isNotBlank() && spiderX != CompactDefaultSpiderX) {
                parts += "x${escapeCompactField(spiderX)}"
            }
        }
        if (config.tlsInsecure) parts += "i"
        // Generated client sets Mux=nil (off). Only encode when enabled.
        if (config.mux) parts += "m1"
        putCompactIfNotBlank(parts, "M", config.muxMode.trim().lowercase())
        if (config.muxMaxSessions > 0) parts += "S${config.muxMaxSessions}"
        if (config.muxMaxStreamsPerSession > 0) parts += "P${config.muxMaxStreamsPerSession}"
        if (config.muxWarmSpare > 0) parts += "W${config.muxWarmSpare}"

        // network=[tcp,udp] is fixed on generated client outbounds.
        val networks = config.effectiveTunnelNetworks().joinToString(",")
        if (networks != CompactDefaultNetwork) {
            parts += "N${escapeCompactField(networks)}"
        }
        val upstream = config.upstreamProtocol.trim().lowercase().ifBlank { CompactDefaultUpstream }
        if (upstream != CompactDefaultUpstream) {
            parts += "u${escapeCompactField(upstream)}"
        }

        val body = parts.joinToString("|")
        val name = config.name.trim().ifBlank { host }
        return if (name == host) body else "$body#${escapeCompactField(name)}"
    }

    private fun decodeCompactProfile(raw: String): AppConfig {
        if (raw.length > MaxProfileUriLength) error("compact profile payload too long")
        val hashIndex = raw.indexOf('#')
        val body = if (hashIndex >= 0) raw.substring(0, hashIndex) else raw
        val encodedName = if (hashIndex >= 0) raw.substring(hashIndex + 1) else ""
        val parts = body.split('|')
        if (parts.size < 5) error("invalid compact profile payload")
        if (parts[0] != CompactPayloadVersion) error("unsupported compact profile version")

        val protocol = CompactProtocolsByCode[parts[1]]
            ?: error("unsupported compact protocol: ${parts[1]}")
        val token = unescapeCompactField(parts[2])
        val host = unescapeCompactField(parts[3]).trim()
        val port = parts[4].trim()
        if (host.isBlank()) error("missing server host")
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) error("missing or invalid server port")
        if (protocol != "native" && token.isBlank()) error("missing $protocol credential")

        var optionIndex = 5
        // Omitting security means reality (generated client outbound default).
        var securityCode = CompactDefaultSecurity
        if (optionIndex < parts.size && parts[optionIndex] in CompactSecurityCodes) {
            securityCode = parts[optionIndex]
            optionIndex += 1
        }

        var transport = "raw"
        var path = CompactDefaultPath
        var sni = ""
        var flow = ""
        var realityPublicKey = ""
        var realityShortId = ""
        var realityFingerprint = ""
        var realitySpiderX = ""
        var tlsInsecure = false
        // Generated client clears Mux → off unless m1 is present.
        var mux = false
        var muxMode = ""
        var muxMaxSessions = 0
        var muxMaxStreamsPerSession = 0
        var muxWarmSpare = 0
        var tunnelNetwork = ""
        var upstreamProtocol = CompactDefaultUpstream

        for (index in optionIndex until parts.size) {
            val part = parts[index]
            if (part.isEmpty()) error("invalid compact profile option")
            when {
                part == "i" -> tlsInsecure = true
                part == "m0" -> mux = false
                part == "m1" || part == "m" -> mux = true
                part.startsWith("y") -> {
                    val code = part.removePrefix("y")
                    transport = CompactTransportsByCode[code]
                        ?: error("unsupported compact transport: $code")
                }
                part.startsWith("p") -> path = unescapeCompactField(part.removePrefix("p")).ifBlank { CompactDefaultPath }
                part.startsWith("s") -> sni = unescapeCompactField(part.removePrefix("s"))
                part.startsWith("f") -> flow = unescapeCompactField(part.removePrefix("f"))
                part.startsWith("k") -> realityPublicKey = unescapeCompactField(part.removePrefix("k"))
                part.startsWith("d") -> realityShortId = unescapeCompactField(part.removePrefix("d"))
                part.startsWith("g") -> realityFingerprint = unescapeCompactField(part.removePrefix("g"))
                part.startsWith("x") -> realitySpiderX = unescapeCompactField(part.removePrefix("x"))
                part.startsWith("M") -> muxMode = unescapeCompactField(part.removePrefix("M")).trim().lowercase()
                part.startsWith("S") -> {
                    muxMaxSessions = part.removePrefix("S").toIntOrNull()
                        ?: error("invalid mux max sessions")
                }
                part.startsWith("P") -> {
                    muxMaxStreamsPerSession = part.removePrefix("P").toIntOrNull()
                        ?: error("invalid mux max streams")
                }
                part.startsWith("W") -> {
                    muxWarmSpare = part.removePrefix("W").toIntOrNull()
                        ?: error("invalid mux warm spares")
                }
                part.startsWith("N") -> {
                    tunnelNetwork = unescapeCompactField(part.removePrefix("N")).trim().lowercase()
                    parseNetworks(tunnelNetwork) // validate
                }
                part.startsWith("u") -> {
                    upstreamProtocol = unescapeCompactField(part.removePrefix("u"))
                        .trim()
                        .lowercase()
                        .ifBlank { CompactDefaultUpstream }
                }
                else -> error("unsupported compact profile option: $part")
            }
        }

        // Fill fixed generated defaults; never invent short_id/public_key/sni.
        if (securityCode == "r") {
            if (realityFingerprint.isBlank()) realityFingerprint = CompactDefaultFingerprint
            if (realitySpiderX.isBlank()) realitySpiderX = CompactDefaultSpiderX
        }
        // Generated vless+reality clients always set vision flow.
        if (protocol == "vless" && securityCode == "r" && flow.isBlank()) {
            flow = CompactDefaultVlessFlow
        }

        val networks = parseNetworks(tunnelNetwork.ifBlank { null })
        val udp = if (networks != null) "udp" in networks else true
        val name = if (encodedName.isNotBlank()) {
            unescapeCompactField(encodedName).ifBlank { host }
        } else {
            host
        }

        return AppConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            serverHost = host,
            serverPort = port,
            protocol = protocol,
            transport = transport,
            token = token,
            sni = sni,
            path = path.ifBlank { CompactDefaultPath },
            tls = securityCode == "t",
            tlsInsecure = tlsInsecure,
            tunnelSecurity = if (securityCode == "r") "reality" else "",
            flow = flow,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            realityFingerprint = realityFingerprint,
            realitySpiderX = realitySpiderX,
            mux = mux,
            muxMode = muxMode,
            muxMaxSessions = muxMaxSessions,
            muxMaxStreamsPerSession = muxMaxStreamsPerSession,
            muxWarmSpare = muxWarmSpare,
            tunnelNetwork = networks?.joinToString(",").orEmpty(),
            udp = udp,
            upstreamProtocol = upstreamProtocol,
        )
    }

    private fun putCompactIfNotBlank(parts: MutableList<String>, prefix: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) parts += prefix + escapeCompactField(trimmed)
    }

    private fun escapeCompactField(value: String): String {
        if (value.none { it == '%' || it == '|' || it == '#' }) return value
        return buildString(value.length + 8) {
            for (ch in value) {
                when (ch) {
                    '%' -> append("%25")
                    '|' -> append("%7C")
                    '#' -> append("%23")
                    else -> append(ch)
                }
            }
        }
    }

    private fun unescapeCompactField(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    out.append(decoded.toChar())
                    index += 3
                    continue
                }
            }
            out.append(ch)
            index += 1
        }
        return out.toString()
    }

    private fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
