package com.tcptun.client

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object ProfileUriCodec {
    fun decode(raw: String): Result<AppConfig> {
        return runRecoverableCatching {
            if (raw.length > MaxProfileImportLength) error("profile payload is too large")
            val trimmed = raw.trim()
            if (trimmed.isBlank()) error("profile payload is empty")
            val looksLikeJson = trimmed.indexOf('{') >= 0 && trimmed.lastIndexOf('}') > trimmed.indexOf('{')
            if (!looksLikeJson && trimmed.length > MaxProfileUriLength) {
                error("profile URI is too large")
            }
            if (looksLikeJson) requireSafeJsonNesting(trimmed)
            when {
                ProfileDeepLinkCodec.isSupportedLink(trimmed) -> {
                    val profileUri = ProfileDeepLinkCodec.decode(trimmed).getOrThrow()
                    decode(profileUri).getOrThrow()
                }
                trimmed.startsWith("vmess://", ignoreCase = true) -> decodeVMess(trimmed)
                trimmed.startsWith("vless://", ignoreCase = true) -> decodeAuthorityProfile("vless", trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> decodeAuthorityProfile("trojan", trimmed)
                trimmed.startsWith("native://", ignoreCase = true) -> decodeAuthorityProfile("native", trimmed)
                trimmed.contains("{") && trimmed.contains("}") -> decodeJsonProfile(trimmed)
                else -> TcptunProfileCodec.decode(trimmed)
            }
        }
    }

    fun encode(config: AppConfig): String? {
        return runRecoverableCatching {
            if (config.rawConfigJson.isNotBlank()) return@runRecoverableCatching null
            if (config.hasEchClientHelloSettings()) return@runRecoverableCatching null
            val validationConfig = if (config.name.isBlank()) config.copy(name = "profile") else config
            if (validationConfig.validate() != null) return@runRecoverableCatching null
            val encoded = when (config.protocol) {
                "native" -> encodeAuthorityProfile("native", config)
                "vless" -> encodeAuthorityProfile("vless", config)
                "trojan" -> encodeAuthorityProfile("trojan", config)
                "vmess" -> encodeVMess(config)
                else -> null
            }
            encoded?.takeIf { it.length <= MaxProfileUriLength }
        }.getOrNull()
    }

    /**
     * Encodes a scannable QR payload for [config].
     *
     * Prefers the compact T3 form from tcptun-go. Older T2-era / URI-imported
     * profiles often store a non-default raw path (for example `"/"` copied from
     * REALITY SpiderX). Compact T2/T3 reject that, so this normalizes the raw
     * path before encoding. It deliberately does not fall back to a protocol
     * URI because that representation cannot preserve every current profile
     * field. Returns null when T3 cannot represent the profile.
     */
    fun encodeForQr(config: AppConfig): String? {
        if (
            config.rawConfigJson.isNotBlank() ||
            config.hasResumableMuxSettings() ||
            config.hasEchClientHelloSettings()
        ) {
            return null
        }
        return runRecoverableCatching {
            // Normalize first so re-showing QR for legacy T2-imported profiles
            // with polluted raw paths (path="/") does not throw from the Go codec.
            TcptunProfileCodec.encode(normalizeForCompactQr(config))
        }.getOrNull()
    }

    private fun AppConfig.hasResumableMuxSettings(): Boolean =
        muxResume || muxResumeTimeoutMillis != 0 || muxResumeBufferSize != 0

    /** Compact T2/T3 only allow the default transport path on raw. */
    private fun normalizeForCompactQr(config: AppConfig): AppConfig {
        if (!config.transport.trim().equals("raw", ignoreCase = true)) return config
        val path = config.path.trim()
        if (path.isEmpty() || path == DefaultRawTransportPath) return config
        return config.copy(path = DefaultRawTransportPath)
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
        if (security !in setOf("", "none", "tls", "reality", "reality-tcp", "reality-quic")) {
            error("unsupported security: $security")
        }
        if (security == "reality-quic" && protocol != "native") {
            error("reality-quic requires native protocol")
        }
        val mux = uri.getBooleanParameterCompat("mux", false)
        val currentCarrierMode = uri.getQueryParameter("carrier_mode").orEmpty()
        val currentCarrierUdpMode = uri.getQueryParameter("carrier_udp_mode").orEmpty()
        val legacyCarrierMode = uri.getQueryParameter("mux_mode").orEmpty()
        val legacyCarrierUdpMode = uri.getQueryParameter("mux_udp_mode").orEmpty()
        val migrated = migratedCarrierFields(
            tunnelSecurity = security,
            protocol = protocol,
            mux = mux,
            carrierMode = currentCarrierMode.ifBlank { legacyCarrierMode },
            carrierUdpMode = currentCarrierUdpMode.ifBlank { legacyCarrierUdpMode },
            legacyMuxSchema =
                currentCarrierMode.isBlank() &&
                    currentCarrierUdpMode.isBlank() &&
                    (legacyCarrierMode.isNotBlank() || legacyCarrierUdpMode.isNotBlank()),
        )
        val transport = transportFromType(type)
        // Keep transport path independent of REALITY SpiderX. Falling back to
        // spx for raw profiles produced non-default paths (e.g. "/") that break
        // compact QR encoding even though raw ignores custom paths.
        val path = uri.getQueryParameter("path")?.takeIf { it.isNotBlank() }
            ?: if (transport != "raw") {
                uri.getQueryParameter("spx")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            ?: DefaultRawTransportPath
        validateNetworks(uri.getQueryParameter("network"))
        return AppConfig(
            id = UUID.randomUUID().toString(),
            name = uri.fragment?.ifBlank { null } ?: host,
            serverHost = host,
            serverPort = port.toString(),
            protocol = protocol,
            transport = transport,
            token = token,
            sni = uri.getQueryParameter("sni").orEmpty()
                .ifBlank { uri.getQueryParameter("serverName").orEmpty() },
            path = path,
            tls = security == "tls",
            tlsInsecure = uri.getBooleanParameterCompat("allowInsecure", false) ||
                uri.getBooleanParameterCompat("tlsInsecure", false) ||
                uri.getBooleanParameterCompat("insecure", false),
            tunnelSecurity = migrated.tunnelSecurity.takeIf {
                it in AppConfig.RealitySecurityTypes
            }.orEmpty(),
            flow = uri.getQueryParameter("flow").orEmpty(),
            realityPublicKey = uri.getQueryParameter("pbk").orEmpty(),
            realityShortId = uri.getQueryParameter("sid").orEmpty()
                .ifBlank { uri.getQueryParameter("reality_short_id").orEmpty() },
            realityFingerprint = uri.getQueryParameter("fp").orEmpty(),
            realitySpiderX = uri.getQueryParameter("spx").orEmpty(),
            mux = mux,
            carrierMode = migrated.carrierMode,
            carrierUdpMode = migrated.carrierUdpMode,
            muxResume = uri.getBooleanParameterCompat("mux_resume", false),
            muxResumeTimeoutMillis = uri.getDurationMillisParameter("mux_resume_timeout"),
            muxResumeBufferSize = uri.getIntParameter("mux_resume_buffer_size"),
            muxMaxSessions = uri.getIntParameter("mux_max_sessions"),
            muxMaxStreamsPerSession = uri.getIntParameter("mux_max_streams_per_session"),
            muxWarmSpare = uri.getIntParameter("mux_warm_spares"),
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
        val decodedJson = String(decodeBase64(encoded), StandardCharsets.UTF_8)
        require(decodedJson.length <= MaxProfileImportLength) { "VMess profile is too large" }
        requireSafeJsonNesting(decodedJson)
        val obj = JSONObject(decodedJson)
        val host = obj.optString("add")
        val port = obj.optString("port")
        if (host.isBlank()) error("missing VMess server host")
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) error("missing or invalid VMess server port")
        val tlsValue = obj.optString("tls").trim().lowercase()
        val securityField = obj.optString("security").trim().lowercase()
        if (tlsValue == "reality-quic" || securityField == "reality-quic") {
            error("reality-quic requires native protocol")
        }
        val security = when {
            tlsValue in setOf("reality", "reality-tcp") -> tlsValue
            securityField in setOf("reality", "reality-tcp") -> securityField
            tlsValue.isBlank() || tlsValue == "none" -> {
                if (securityField == "reality") "reality" else ""
            }
            else -> "tls"
        }
        validateNetworks(obj.optString("tcptun_network").ifBlank { obj.optString("network") })
        val mux = when {
            obj.has("tcptun_mux") -> obj.optBoolean("tcptun_mux", false)
            obj.has("mux") -> obj.optBoolean("mux", false)
            else -> false
        }
        val currentCarrierMode = obj.optString("tcptun_carrier_mode")
            .ifBlank { obj.optString("carrier_mode") }
        val currentCarrierUdpMode = obj.optString("tcptun_carrier_udp_mode")
            .ifBlank { obj.optString("carrier_udp_mode") }
        val legacyCarrierMode = obj.optString("tcptun_mux_mode")
            .ifBlank { obj.optString("mux_mode") }
        val legacyCarrierUdpMode = obj.optString("tcptun_mux_udp_mode")
            .ifBlank { obj.optString("mux_udp_mode") }
        val migrated = migratedCarrierFields(
            tunnelSecurity = security,
            protocol = "vmess",
            mux = mux,
            carrierMode = currentCarrierMode.ifBlank { legacyCarrierMode },
            carrierUdpMode = currentCarrierUdpMode.ifBlank { legacyCarrierUdpMode },
            legacyMuxSchema =
                currentCarrierMode.isBlank() &&
                    currentCarrierUdpMode.isBlank() &&
                    (legacyCarrierMode.isNotBlank() || legacyCarrierUdpMode.isNotBlank()),
        )
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
            tunnelSecurity = migrated.tunnelSecurity.takeIf {
                it in AppConfig.RealitySecurityTypes
            }.orEmpty(),
            flow = obj.optString("tcptun_flow").ifBlank { obj.optString("flow") },
            realityPublicKey = obj.optString("pbk"),
            realityShortId = obj.optString("sid").ifBlank { obj.optString("reality_short_id") },
            realityFingerprint = obj.optString("fp"),
            realitySpiderX = obj.optString("spx"),
            mux = mux,
            carrierMode = migrated.carrierMode,
            carrierUdpMode = migrated.carrierUdpMode,
            muxResume = obj.optBoolean("tcptun_mux_resume", obj.optBoolean("mux_resume", false)),
            muxResumeTimeoutMillis = obj.optDurationMillis(
                "tcptun_mux_resume_timeout",
                "mux_resume_timeout",
            ),
            muxResumeBufferSize = obj.optInt(
                "tcptun_mux_resume_buffer_size",
                obj.optInt("mux_resume_buffer_size", 0),
            ),
            muxMaxSessions = obj.optInt("tcptun_mux_max_sessions", obj.optInt("mux_max_sessions", 0)),
            muxMaxStreamsPerSession = obj.optInt(
                "tcptun_mux_max_streams_per_session",
                obj.optInt("mux_max_streams_per_session", 0),
            ),
            muxWarmSpare = obj.optInt("tcptun_mux_warm_spares", obj.optInt("mux_warm_spares", 0)),
            upstreamProtocol = obj.optString("upstream").ifBlank {
                obj.optString("upstream_protocol", "socks5")
            },
        )
    }

    private fun decodeJsonProfile(raw: String): AppConfig {
        val extracted = extractJsonObject(raw)
        requireSafeJsonNesting(extracted)
        val obj = JSONObject(extracted)
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
        val mux = obj.optBoolean("tunnel_mux", true)
        val currentCarrierMode = obj.optString("tunnel_carrier_mode")
        val currentCarrierUdpMode = obj.optString("tunnel_carrier_udp_mode")
        val legacyCarrierMode = obj.optString("tunnel_mux_mode")
        val legacyCarrierUdpMode = obj.optString("tunnel_mux_udp_mode")
        val migrated = migratedCarrierFields(
            tunnelSecurity = normalizedSecurity,
            protocol = protocol,
            mux = mux,
            carrierMode = currentCarrierMode.ifBlank { legacyCarrierMode },
            carrierUdpMode = currentCarrierUdpMode.ifBlank { legacyCarrierUdpMode },
            legacyMuxSchema =
                currentCarrierMode.isBlank() &&
                    currentCarrierUdpMode.isBlank() &&
                    (legacyCarrierMode.isNotBlank() || legacyCarrierUdpMode.isNotBlank()),
        )
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
            tunnelSecurity = migrated.tunnelSecurity,
            flow = obj.optString("tunnel_flow"),
            realityPublicKey = obj.optString("reality_public_key"),
            realityShortId = obj.optString("reality_short_id").ifBlank {
                obj.optFirstString("reality_short_ids")
            },
            realityFingerprint = obj.optString("reality_fingerprint"),
            realitySpiderX = obj.optString("reality_spider_x"),
            mux = mux,
            carrierMode = migrated.carrierMode,
            carrierUdpMode = migrated.carrierUdpMode,
            muxResume = obj.optBoolean("tunnel_mux_resume", false),
            muxResumeTimeoutMillis = obj.optDurationMillis("tunnel_mux_resume_timeout"),
            muxResumeBufferSize = obj.optInt("tunnel_mux_resume_buffer_size", 0),
            muxMaxSessions = obj.optInt("tunnel_mux_max_sessions", 0),
            muxMaxStreamsPerSession = obj.optInt("tunnel_mux_max_streams_per_session", 0),
            muxWarmSpare = obj.optInt("tunnel_mux_warm_spares", 0),
            upstreamProtocol = obj.optString("upstream_protocol", "socks5").ifBlank { "socks5" },
        )
    }

    private fun encodeVMess(config: AppConfig): String? {
        val tunnelSecurity = config.tunnelSecurity.trim().lowercase()
        val host = config.serverHost.trim()
        val port = config.serverPort.trim()
        val token = config.token.trim()
        if (host.isBlank() || port.isBlank() || token.isBlank()) return null
        val tlsField = when {
            tunnelSecurity == "reality" -> "reality"
            config.tls -> "tls"
            else -> ""
        }
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
            .put("tls", tlsField)
            .put("sni", config.sni)
            .put("allowInsecure", config.tlsInsecure)
            .put("tcptun_mux", config.mux)
            .put("tcptun_network", AndroidTunNetworks.joinToString(","))
        putJsonIfNotBlank(obj, "tcptun_carrier_mode", config.carrierMode)
        putJsonIfNotBlank(obj, "tcptun_carrier_udp_mode", config.carrierUdpMode)
        if (config.muxResume) obj.put("tcptun_mux_resume", true)
        if (config.muxResumeTimeoutMillis > 0) {
            obj.put("tcptun_mux_resume_timeout", "${config.muxResumeTimeoutMillis}ms")
        }
        if (config.muxResumeBufferSize > 0) {
            obj.put("tcptun_mux_resume_buffer_size", config.muxResumeBufferSize)
        }
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
        val tunnelSecurity = config.tunnelSecurity.trim().lowercase()
        val security = when {
            tunnelSecurity in AppConfig.RealitySecurityTypes -> tunnelSecurity
            config.tls -> "tls"
            else -> "none"
        }
        params["security"] = security
        if (config.protocol == "vless") params["encryption"] = "none"
        if (security in AppConfig.RealitySecurityTypes) {
            putIfNotBlank(params, "pbk", config.realityPublicKey)
            putIfNotBlank(params, "sid", config.realityShortId)
            putIfNotBlank(params, "fp", config.realityFingerprint)
            putIfNotBlank(params, "spx", config.realitySpiderX)
        }
        params["type"] = typeFromTransport(config.transport)
        putIfNotBlank(params, "flow", config.flow)
        putIfNotBlank(params, "sni", config.sni)
        if (config.tlsInsecure) params["insecure"] = "true"
        if (config.transport != "raw") putIfNotBlank(params, "path", config.path)
        params["network"] = AndroidTunNetworks.joinToString(",")
        params["mux"] = config.mux.toString()
        putIfNotBlank(params, "carrier_mode", config.carrierMode)
        putIfNotBlank(params, "carrier_udp_mode", config.carrierUdpMode)
        if (config.muxResume) params["mux_resume"] = "true"
        if (config.muxResumeTimeoutMillis > 0) {
            params["mux_resume_timeout"] = "${config.muxResumeTimeoutMillis}ms"
        }
        if (config.muxResumeBufferSize > 0) {
            params["mux_resume_buffer_size"] = config.muxResumeBufferSize.toString()
        }
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

    private fun validateNetworks(value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return
        text.split(',').forEach { value ->
            val network = value.trim().lowercase()
            if (network !in setOf("tcp", "udp")) {
                error("unsupported network: $network")
            }
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

    private fun Uri.getDurationMillisParameter(name: String): Int {
        val value = getQueryParameter(name)?.trim().orEmpty()
        return if (value.isBlank()) 0 else parseGoDurationMillis(value, name)
    }

    private fun JSONObject.optDurationMillis(primaryName: String, fallbackName: String = ""): Int {
        val value = optString(primaryName).ifBlank {
            fallbackName.takeIf(String::isNotBlank)?.let(::optString).orEmpty()
        }
        return if (value.isBlank()) 0 else parseGoDurationMillis(value, primaryName)
    }

    private fun parseGoDurationMillis(value: String, fieldName: String): Int {
        val text = value.trim()
        if (text == "0" || text == "+0" || text == "-0") return 0
        val negative = text.startsWith("-")
        val unsigned = text.removePrefix("+").removePrefix("-")
        if (unsigned.isBlank()) error("invalid duration parameter: $fieldName")

        var cursor = 0
        var totalNanos = BigDecimal.ZERO
        GoDurationPart.findAll(unsigned).forEach { match ->
            if (match.range.first != cursor) error("invalid duration parameter: $fieldName")
            val amount = match.groupValues[1].toBigDecimal()
            val nanosPerUnit = when (match.groupValues[2]) {
                "h" -> 3_600_000_000_000L
                "m" -> 60_000_000_000L
                "s" -> 1_000_000_000L
                "ms" -> 1_000_000L
                "us", "µs", "μs" -> 1_000L
                "ns" -> 1L
                else -> error("invalid duration parameter: $fieldName")
            }
            totalNanos = totalNanos.add(amount.multiply(BigDecimal.valueOf(nanosPerUnit)))
            cursor = match.range.last + 1
        }
        if (cursor != unsigned.length) error("invalid duration parameter: $fieldName")
        if (negative) totalNanos = totalNanos.negate()
        return try {
            val millis = totalNanos.divide(BigDecimal.valueOf(1_000_000L))
            if (
                millis.stripTrailingZeros().scale() > 0 ||
                millis < BigDecimal.valueOf(Int.MIN_VALUE.toLong()) ||
                millis > BigDecimal.valueOf(Int.MAX_VALUE.toLong())
            ) {
                throw ArithmeticException()
            }
            millis.toInt()
        } catch (_: ArithmeticException) {
            error("$fieldName must resolve to whole milliseconds")
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

    private const val TcptunUriVersion = "1"
    private const val DefaultRawTransportPath = "/proxy"
    private val GoDurationPart = Regex("""(\d+(?:\.\d*)?|\.\d+)(ns|us|µs|μs|ms|s|m|h)""")

    private fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
