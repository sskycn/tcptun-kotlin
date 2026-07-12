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
                trimmed.startsWith("vmess://", ignoreCase = true) -> decodeVMess(trimmed)
                trimmed.startsWith("vless://", ignoreCase = true) -> decodeAuthorityProfile("vless", trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> decodeAuthorityProfile("trojan", trimmed)
                trimmed.startsWith("native://", ignoreCase = true) -> decodeAuthorityProfile("native", trimmed)
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

    private fun decodeAuthorityProfile(protocol: String, raw: String): AppConfig {
        val uri = Uri.parse(raw)
        val token = uri.userInfo?.trim().orEmpty()
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        if (host.isBlank()) error("missing server host")
        if (port !in 1..65535) error("missing or invalid server port")
        if (protocol != "native" && token.isBlank()) error("missing $protocol credential")

        val type = uri.getQueryParameter("type").orEmpty().lowercase()
        val security = uri.getQueryParameter("security").orEmpty().lowercase()
        val path = uri.getQueryParameter("path") ?: uri.getQueryParameter("spx") ?: "/proxy"
        return AppConfig(
            id = UUID.randomUUID().toString(),
            name = uri.fragment?.ifBlank { null } ?: host,
            serverHost = host,
            serverPort = port.toString(),
            protocol = protocol,
            transport = transportFromType(type),
            token = token,
            sni = uri.getQueryParameter("sni").orEmpty(),
            path = path.ifBlank { "/" },
            tls = security == "tls",
            tlsInsecure = uri.getBooleanParameterCompat("allowInsecure", false) ||
                uri.getBooleanParameterCompat("tlsInsecure", false),
            tunnelSecurity = if (security == "reality") "reality" else "",
            flow = uri.getQueryParameter("flow").orEmpty(),
            realityPublicKey = uri.getQueryParameter("pbk").orEmpty(),
            realityShortId = uri.getQueryParameter("sid").orEmpty()
                .ifBlank { uri.getQueryParameter("reality_short_id").orEmpty() },
            realityFingerprint = uri.getQueryParameter("fp").orEmpty(),
            realitySpiderX = uri.getQueryParameter("spx").orEmpty(),
            mux = uri.getBooleanParameterCompat("mux", true),
            udp = uri.getBooleanParameterCompat("udp", true),
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
        val tls = obj.optString("tls").equals("tls", ignoreCase = true)
        val security = obj.optString("security").lowercase()
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
            tls = tls,
            tlsInsecure = obj.optBoolean("allowInsecure", false) || obj.optBoolean("tlsInsecure", false),
            tunnelSecurity = if (security == "reality") "reality" else "",
            flow = obj.optString("flow"),
            realityPublicKey = obj.optString("pbk"),
            realityShortId = obj.optString("sid").ifBlank { obj.optString("reality_short_id") },
            realityFingerprint = obj.optString("fp"),
            realitySpiderX = obj.optString("spx"),
            mux = obj.optBoolean("mux", true),
            udp = obj.optBoolean("udp", true),
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
            .put("tls", if (config.tls) "tls" else "")
            .put("sni", config.sni)
            .put("allowInsecure", config.tlsInsecure)
            .put("mux", config.mux)
            .put("udp", config.udp)
            .put("upstream", config.upstreamProtocol)
        if (config.tunnelSecurity == "reality") obj.put("security", "reality")
        putJsonIfNotBlank(obj, "flow", config.flow)
        putJsonIfNotBlank(obj, "pbk", config.realityPublicKey)
        putJsonIfNotBlank(obj, "sid", config.realityShortId)
        putJsonIfNotBlank(obj, "fp", config.realityFingerprint)
        putJsonIfNotBlank(obj, "spx", config.realitySpiderX)
        return "vmess://" + Base64.encodeToString(obj.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    private fun commonParams(config: AppConfig): LinkedHashMap<String, String> {
        val params = linkedMapOf<String, String>()
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
            params["headerType"] = "none"
            putIfNotBlank(params, "fp", config.realityFingerprint)
            putIfNotBlank(params, "spx", config.realitySpiderX.ifBlank { config.path })
        }
        params["type"] = typeFromTransport(config.transport)
        putIfNotBlank(params, "flow", config.flow)
        putIfNotBlank(params, "sni", config.sni)
        if (config.tlsInsecure) params["allowInsecure"] = "1"
        if (config.transport != "raw") putIfNotBlank(params, "path", config.path)
        params["mux"] = if (config.mux) "1" else "0"
        params["udp"] = if (config.udp) "1" else "0"
        if (config.upstreamProtocol != "socks5") params["upstream"] = config.upstreamProtocol
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
            else -> "raw"
        }
    }

    private fun typeFromTransport(transport: String): String {
        return when (transport) {
            "raw" -> "tcp"
            "ws" -> "ws"
            "h2" -> "h2"
            "h3" -> "h3"
            else -> "tcp"
        }
    }

    private fun Uri.getBooleanParameterCompat(name: String, defaultValue: Boolean): Boolean {
        return when (getQueryParameter(name)?.lowercase()) {
            null, "" -> defaultValue
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> defaultValue
        }
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

    private fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
