package com.tcptun.client

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ProfileUriCodec {
    fun decode(raw: String): Result<AppConfig> {
        val trimmed = raw.trim()
        return runCatching {
            when {
                trimmed.startsWith(CompactPayloadPrefix) -> decodeCompactProfile(trimmed)
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
     * Binary QR encoding wrapped in Base45. The resulting `T2:` payload stays entirely inside
     * QR's alphanumeric character set, while UUIDs, Reality keys, short IDs, flags, ports, and
    * common host suffixes are stored in their compact binary form.
     */
    fun encodeForQr(config: AppConfig): String? {
        return encodeCompactProfile(config) ?: encode(config)
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
                        rule.put("inbound", JSONArray().put(AndroidTunInboundTag))
                    }
                }
            }
            val imported = JSONObject()
                .put("outbounds", obj.getJSONArray("outbounds"))
                .put("route", route)
            obj.optJSONObject("dns")?.let { dns ->
                imported.put("dns", JSONObject(dns.toString()))
            }
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

    private const val TcptunUriVersion = "1"
    private const val CompactPayloadPrefix = "T2:"
    private const val CompactDefaultPath = "/proxy"
    private const val CompactDefaultUpstream = "socks5"
    private const val CompactDefaultFingerprint = "chrome"
    private const val CompactDefaultSpiderX = "/"
    private const val CompactDefaultVlessFlow = "xtls-rprx-vision"
    private val CompactProtocols = listOf("native", "vless", "vmess", "trojan")
    private val CompactTransports = listOf("raw", "ws", "h2", "h3")
    private val CompactHostSuffixes = listOf(".com", ".net", ".org", ".cn", ".io", ".dev")
    private const val CompactMaxBinaryLength = 43_000

    private fun encodeCompactProfile(config: AppConfig): String? {
        if (config.rawConfigJson.isNotBlank()) return null
        val protocol = config.protocol.trim().lowercase()
        val protocolCode = CompactProtocols.indexOf(protocol).takeIf { it >= 0 } ?: return null
        val transport = config.transport.trim().lowercase().ifBlank { "raw" }
        val transportCode = CompactTransports.indexOf(transport).takeIf { it >= 0 } ?: return null
        val host = config.serverHost.trim()
        val port = config.serverPort.trim()
        if (host.isBlank() || port.isBlank()) return null
        val portNumber = port.toIntOrNull() ?: return null
        if (portNumber !in 1..65535) return null
        val token = config.token.trim()
        if (protocol != "native" && token.isBlank()) return null

        val securityCode = when {
            config.tunnelSecurity.equals("reality", ignoreCase = true) -> 2
            config.tls -> 1
            else -> 0
        }
        val networks = runCatching { config.effectiveTunnelNetworks() }.getOrNull() ?: return null
        val networkCode = when (networks) {
            listOf("tcp", "udp"), listOf("udp", "tcp") -> 0
            listOf("tcp") -> 1
            listOf("udp") -> 2
            else -> return null
        }
        val muxModeCode = when (config.muxMode.trim().lowercase()) {
            "" -> 0
            "group" -> 1
            "quic" -> 2
            else -> return null
        }
        val path = config.path.trim().ifBlank { CompactDefaultPath }
        val hasPath = transport != "raw" && path != CompactDefaultPath
        val sni = config.sni.trim()
        val hasCustomSni = sni.isNotBlank() && sni != host
        val flow = config.flow.trim()
        val hasCustomFlow = flow.isNotBlank() &&
            !(protocol == "vless" && securityCode == 2 && flow == CompactDefaultVlessFlow)
        val publicKey = config.realityPublicKey.trim().takeIf { securityCode == 2 }.orEmpty()
        val shortId = config.realityShortId.trim().takeIf { securityCode == 2 }.orEmpty()
        val fingerprint = config.realityFingerprint.trim().takeIf { securityCode == 2 }.orEmpty()
        val hasCustomFingerprint = fingerprint.isNotBlank() &&
            !fingerprint.equals(CompactDefaultFingerprint, ignoreCase = true)
        val spiderX = config.realitySpiderX.trim().takeIf { securityCode == 2 }.orEmpty()
        val hasCustomSpiderX = spiderX.isNotBlank() && spiderX != CompactDefaultSpiderX
        val upstream = config.upstreamProtocol.trim().lowercase().ifBlank { CompactDefaultUpstream }
        if (upstream !in AppConfig.UpstreamProtocols) return null

        val name = config.name.trim().ifBlank { host }
        val hasCustomName = name != host
        val header0 = protocolCode or
            (transportCode shl 2) or
            (securityCode shl 4) or
            (if (config.tlsInsecure) 0x40 else 0) or
            (if (config.mux) 0x80 else 0)
        val header1 = networkCode or
            (if (upstream == "mixed") 0x04 else 0) or
            (muxModeCode shl 3) or
            (if (hasPath) 0x20 else 0) or
            (if (hasCustomSni) 0x40 else 0) or
            (if (hasCustomName) 0x80 else 0)
        val header2 = (if (hasCustomFlow) 0x01 else 0) or
            (if (publicKey.isNotBlank()) 0x02 else 0) or
            (if (shortId.isNotBlank()) 0x04 else 0) or
            (if (hasCustomFingerprint) 0x08 else 0) or
            (if (hasCustomSpiderX) 0x10 else 0) or
            (if (config.muxMaxSessions > 0) 0x20 else 0) or
            (if (config.muxMaxStreamsPerSession > 0) 0x40 else 0) or
            (if (config.muxWarmSpare > 0) 0x80 else 0)

        val writer = CompactWriter()
        writer.writeByte(header0)
        writer.writeByte(header1)
        writer.writeByte(header2)
        writer.writePort(portNumber)
        writer.writeCredential(token)
        writer.writeHost(host)
        if (hasPath) writer.writeString(path)
        if (hasCustomSni) writer.writeString(sni)
        if (hasCustomFlow) writer.writeString(flow)
        if (publicKey.isNotBlank()) writer.writeRealityKey(publicKey)
        if (shortId.isNotBlank()) writer.writeShortId(shortId)
        if (hasCustomFingerprint) writer.writeString(fingerprint)
        if (hasCustomSpiderX) writer.writeString(spiderX)
        if (config.muxMaxSessions > 0) writer.writeVarUInt(config.muxMaxSessions)
        if (config.muxMaxStreamsPerSession > 0) writer.writeVarUInt(config.muxMaxStreamsPerSession)
        if (config.muxWarmSpare > 0) writer.writeVarUInt(config.muxWarmSpare)
        if (hasCustomName) writer.writeString(name)
        val bytes = writer.toByteArray()
        if (bytes.size > CompactMaxBinaryLength) return null
        // The final sentinel prevents String.trim()/clipboard normalization from removing a
        // legitimate trailing Base45 space (space is part of QR's alphanumeric alphabet).
        return CompactPayloadPrefix + encodeBase45(bytes) + CompactPayloadSentinel
    }

    private fun decodeCompactProfile(raw: String): AppConfig {
        if (raw.length > MaxProfileUriLength) error("compact profile payload too long")
        if (!raw.endsWith(CompactPayloadSentinel)) error("invalid compact profile terminator")
        val bytes = decodeBase45(raw.removePrefix(CompactPayloadPrefix).dropLast(1))
        if (bytes.size > CompactMaxBinaryLength) error("compact profile payload too long")
        val reader = CompactReader(bytes)
        val header0 = reader.readByte()
        val header1 = reader.readByte()
        val header2 = reader.readByte()
        val protocol = CompactProtocols.getOrNull(header0 and 0x03)
            ?: error("unsupported compact protocol")
        val transport = CompactTransports.getOrNull((header0 ushr 2) and 0x03)
            ?: error("unsupported compact transport")
        val securityCode = (header0 ushr 4) and 0x03
        if (securityCode == 3) error("unsupported compact security")
        val networkCode = header1 and 0x03
        val networks = when (networkCode) {
            0 -> listOf("tcp", "udp")
            1 -> listOf("tcp")
            2 -> listOf("udp")
            else -> error("unsupported compact network")
        }
        val muxMode = when ((header1 ushr 3) and 0x03) {
            0 -> ""
            1 -> "group"
            2 -> "quic"
            else -> error("unsupported compact mux mode")
        }
        val port = reader.readPort().toString()
        val token = reader.readCredential()
        val host = reader.readHost().trim()
        if (host.isBlank()) error("missing server host")
        if (protocol != "native" && token.isBlank()) error("missing $protocol credential")
        val path = if (header1 and 0x20 != 0) reader.readString() else CompactDefaultPath
        val sni = when {
            header1 and 0x40 != 0 -> reader.readString()
            securityCode != 0 -> host
            else -> ""
        }
        val flow = if (header2 and 0x01 != 0) {
            reader.readString()
        } else if (protocol == "vless" && securityCode == 2) {
            CompactDefaultVlessFlow
        } else {
            ""
        }
        val realityPublicKey = if (header2 and 0x02 != 0) reader.readRealityKey() else ""
        val realityShortId = if (header2 and 0x04 != 0) reader.readShortId() else ""
        val realityFingerprint = if (securityCode == 2) {
            if (header2 and 0x08 != 0) reader.readString() else CompactDefaultFingerprint
        } else {
            ""
        }
        val realitySpiderX = if (securityCode == 2) {
            if (header2 and 0x10 != 0) reader.readString() else CompactDefaultSpiderX
        } else {
            ""
        }
        val muxMaxSessions = if (header2 and 0x20 != 0) reader.readVarUInt() else 0
        val muxMaxStreamsPerSession = if (header2 and 0x40 != 0) reader.readVarUInt() else 0
        val muxWarmSpare = if (header2 and 0x80 != 0) reader.readVarUInt() else 0
        val name = if (header1 and 0x80 != 0) reader.readString().ifBlank { host } else host
        reader.requireEnd()

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
            tls = securityCode == 1,
            tlsInsecure = header0 and 0x40 != 0,
            tunnelSecurity = if (securityCode == 2) "reality" else "",
            flow = flow,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            realityFingerprint = realityFingerprint,
            realitySpiderX = realitySpiderX,
            mux = header0 and 0x80 != 0,
            muxMode = muxMode,
            muxMaxSessions = muxMaxSessions,
            muxMaxStreamsPerSession = muxMaxStreamsPerSession,
            muxWarmSpare = muxWarmSpare,
            tunnelNetwork = networks.joinToString(","),
            udp = "udp" in networks,
            upstreamProtocol = if (header1 and 0x04 != 0) "mixed" else CompactDefaultUpstream,
        )
    }

    private class CompactWriter {
        private val output = ByteArrayOutputStream()

        fun writeByte(value: Int) = output.write(value)

        fun writeVarUInt(value: Int) {
            require(value >= 0) { "negative compact integer" }
            var remaining = value
            do {
                val chunk = remaining and 0x7f
                remaining = remaining ushr 7
                writeByte(chunk or if (remaining != 0) 0x80 else 0)
            } while (remaining != 0)
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeVarUInt(bytes.size)
            output.write(bytes)
        }

        fun writePort(port: Int) {
            when (port) {
                443 -> writeByte(0)
                9443 -> writeByte(1)
                else -> {
                    writeByte(2)
                    writeByte(port ushr 8)
                    writeByte(port)
                }
            }
        }

        fun writeCredential(value: String) {
            val uuid = runCatching { UUID.fromString(value) }.getOrNull()
            if (uuid != null && uuid.toString().equals(value, ignoreCase = true)) {
                writeByte(0)
                writeLong(uuid.mostSignificantBits)
                writeLong(uuid.leastSignificantBits)
            } else {
                writeByte(1)
                writeString(value)
            }
        }

        fun writeHost(value: String) {
            val ipv4 = value.split('.').takeIf { parts ->
                parts.size == 4 && parts.all {
                    it.isNotEmpty() && (it == "0" || !it.startsWith('0')) && it.toIntOrNull() in 0..255
                }
            }
            if (ipv4 != null) {
                writeByte(0)
                ipv4.forEach { writeByte(it.toInt()) }
                return
            }
            val ipv6 = if (value.contains(':')) {
                runCatching { InetAddress.getByName(value.removeSurrounding("[", "]")) as? Inet6Address }
                    .getOrNull()
            } else {
                null
            }
            if (ipv6 != null) {
                writeByte(1)
                output.write(ipv6.address)
                return
            }
            val suffixIndex = CompactHostSuffixes.indexOfFirst { value.endsWith(it, ignoreCase = true) }
            if (suffixIndex >= 0) {
                writeByte(2 + suffixIndex)
                writeString(value.dropLast(CompactHostSuffixes[suffixIndex].length))
            } else {
                writeByte(2 + CompactHostSuffixes.size)
                writeString(value)
            }
        }

        fun writeRealityKey(value: String) {
            val decoded = runCatching { decodeBase64(value) }.getOrNull()
            if (decoded?.size == 32) {
                writeByte(0)
                output.write(decoded)
            } else {
                writeByte(1)
                writeString(value)
            }
        }

        fun writeShortId(value: String) {
            val packed = value.takeIf {
                it.length in 2..32 && it.length % 2 == 0 && it.all { ch -> ch.digitToIntOrNull(16) != null }
            }
                ?.chunked(2)
                ?.map { it.toInt(16) }
            if (packed != null) {
                writeByte(packed.size)
                packed.forEach(::writeByte)
            } else {
                writeByte(0)
                writeString(value)
            }
        }

        private fun writeLong(value: Long) {
            for (shift in 56 downTo 0 step 8) writeByte((value ushr shift).toInt())
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private class CompactReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readByte(): Int {
            if (offset >= bytes.size) error("truncated compact profile")
            return bytes[offset++].toInt() and 0xff
        }

        fun readVarUInt(): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                val byte = readByte()
                if (shift == 28 && byte and 0xf0 != 0) error("compact integer is too large")
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
            error("compact integer is too large")
        }

        fun readString(): String {
            val length = readVarUInt()
            if (length > bytes.size - offset) error("truncated compact string")
            val value = String(bytes, offset, length, StandardCharsets.UTF_8)
            offset += length
            return value
        }

        fun readPort(): Int = when (readByte()) {
            0 -> 443
            1 -> 9443
            2 -> (readByte() shl 8) or readByte()
            else -> error("invalid compact port")
        }.also { if (it !in 1..65535) error("invalid compact port") }

        fun readCredential(): String = when (readByte()) {
            0 -> UUID(readLong(), readLong()).toString()
            1 -> readString()
            else -> error("invalid compact credential")
        }

        fun readHost(): String = when (val kind = readByte()) {
            0 -> List(4) { readByte() }.joinToString(".")
            1 -> InetAddress.getByAddress(readBytes(16)).hostAddress.orEmpty()
            in 2 until 2 + CompactHostSuffixes.size ->
                readString() + CompactHostSuffixes[kind - 2]
            2 + CompactHostSuffixes.size -> readString()
            else -> error("invalid compact host")
        }

        fun readRealityKey(): String = when (readByte()) {
            0 -> Base64.encodeToString(readBytes(32), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            1 -> readString()
            else -> error("invalid compact Reality key")
        }

        fun readShortId(): String {
            val byteLength = readByte()
            if (byteLength == 0) return readString()
            if (byteLength > 16) error("invalid compact short ID")
            return readBytes(byteLength).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun readLong(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or readByte().toLong() }
            return value
        }

        private fun readBytes(length: Int): ByteArray {
            if (length > bytes.size - offset) error("truncated compact profile")
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun requireEnd() {
            if (offset != bytes.size) error("unexpected compact profile data")
        }
    }

    private fun encodeBase45(bytes: ByteArray): String = buildString((bytes.size * 3 + 1) / 2) {
        var index = 0
        while (index + 1 < bytes.size) {
            val value = ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)
            append(Base45Alphabet[value % 45])
            append(Base45Alphabet[(value / 45) % 45])
            append(Base45Alphabet[value / (45 * 45)])
            index += 2
        }
        if (index < bytes.size) {
            val value = bytes[index].toInt() and 0xff
            append(Base45Alphabet[value % 45])
            append(Base45Alphabet[value / 45])
        }
    }

    private fun decodeBase45(value: String): ByteArray {
        if (value.isEmpty() || value.length % 3 == 1) error("invalid Base45 payload")
        val output = ByteArrayOutputStream(value.length * 2 / 3)
        var index = 0
        while (index < value.length) {
            val remaining = value.length - index
            val first = Base45Alphabet.indexOf(value[index]).takeIf { it >= 0 }
                ?: error("invalid Base45 character")
            val second = Base45Alphabet.indexOf(value[index + 1]).takeIf { it >= 0 }
                ?: error("invalid Base45 character")
            if (remaining >= 3) {
                val third = Base45Alphabet.indexOf(value[index + 2]).takeIf { it >= 0 }
                    ?: error("invalid Base45 character")
                val decoded = first + second * 45 + third * 45 * 45
                if (decoded > 0xffff) error("invalid Base45 group")
                output.write(decoded ushr 8)
                output.write(decoded)
                index += 3
            } else {
                val decoded = first + second * 45
                if (decoded > 0xff) error("invalid Base45 tail")
                output.write(decoded)
                index += 2
            }
        }
        return output.toByteArray()
    }

    private const val Base45Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private const val CompactPayloadSentinel = ":"

    private fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
