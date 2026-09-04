package com.tcptun.client

import android.net.Uri
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
            if (looksLikeJson) error("JSON profile import is no longer supported")
            val config = when {
                ProfileDeepLinkCodec.isSupportedLink(trimmed) -> {
                    val profileUri = ProfileDeepLinkCodec.decode(trimmed).getOrThrow()
                    decode(profileUri).getOrThrow()
                }
                RemovedTunnelProtocols.any { trimmed.startsWith("$it://", ignoreCase = true) } -> {
                    val protocol = trimmed.substringBefore(":").lowercase()
                    error(unsupportedTunnelProtocolMessage(protocol))
                }
                trimmed.startsWith("native://", ignoreCase = true) -> decodeAuthorityProfile(trimmed)
                else -> TcptunProfileCodec.decode(trimmed)
            }
            config.validate()?.let { error(it) }
            config
        }
    }

    fun encode(config: AppConfig): String? {
        return runRecoverableCatching {
            val validationConfig = if (config.name.isBlank()) config.copy(name = "profile") else config
            if (validationConfig.validate() != null) return@runRecoverableCatching null
            val encoded = config.takeIf { it.protocol == "native" }
                ?.let(::encodeAuthorityProfile)
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
        if (config.hasResumableMuxSettings()) {
            return null
        }
        return runRecoverableCatching {
            // Normalize first so re-showing QR for legacy T2-imported profiles
            // with polluted raw paths (path="/") does not throw from the Go codec.
            val normalized = normalizeForCompactQr(config)
            normalized.validate()?.let { error(it) }
            TcptunProfileCodec.encode(normalized)
        }.getOrNull()
    }

    /** Renders a profile QR code through tcptun-go and returns its PNG bytes. */
    fun encodeQrCode(config: AppConfig): ByteArray? {
        if (config.hasResumableMuxSettings()) {
            return null
        }
        return runRecoverableCatching {
            val normalized = normalizeForCompactQr(config)
            normalized.validate()?.let { error(it) }
            TcptunProfileCodec.encodeQrCode(
                profile = normalized,
                recoveryLevel = "",
                moduleSize = 0,
                compact = false,
            )
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

    private fun decodeAuthorityProfile(raw: String): AppConfig {
        val uri = Uri.parse(raw)
        val encodedUserInfo = uri.encodedUserInfo.orEmpty()
        val encodedCredential = encodedUserInfo.substringBefore(':')
        val token = Uri.decode(encodedCredential).trim()
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        if (host.isBlank()) error("missing server host")
        if (port !in 1..65535) error("missing or invalid server port")
        val version = uri.getQueryParameter("v").orEmpty().trim()
        if (version.isNotBlank() && version != TcptunUriVersion) error("unsupported tcptun URI version: $version")
        val legacyProtocol = uri.getQueryParameter("protocol").orEmpty().trim().lowercase()
        if (legacyProtocol.isNotBlank() && legacyProtocol != "native") {
            if (legacyProtocol in RemovedTunnelProtocols) error(unsupportedTunnelProtocolMessage(legacyProtocol))
            error("tcptun URI protocol must be native")
        }
        val type = uri.getQueryParameter("type").orEmpty()
            .ifBlank { uri.getQueryParameter("transport").orEmpty() }
            .lowercase()
        val security = uri.getQueryParameter("security").orEmpty().lowercase()
        if (security.isBlank() || security == "none") error(VpnTunnelConfidentialityError)
        if (security !in setOf("tls", "reality", "reality-tcp", "reality-quic")) {
            error("unsupported security: $security")
        }
        val mux = uri.getBooleanParameterCompat("mux", false)
        val currentCarrierMode = uri.getQueryParameter("carrier_mode").orEmpty()
        val currentCarrierPrefer = uri.getQueryParameter("carrier_prefer").orEmpty()
        requireSupportedCarrierPreference(currentCarrierPrefer)
        val currentCarrierUdpMode = uri.getQueryParameter("carrier_udp_mode").orEmpty()
        val legacyCarrierMode = uri.getQueryParameter("mux_mode").orEmpty()
        val legacyCarrierUdpMode = uri.getQueryParameter("mux_udp_mode").orEmpty()
        val migrated = migratedCarrierFields(
            tunnelSecurity = security,
            protocol = "native",
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
            protocol = "native",
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
            realitySpiderX = uri.getQueryParameter("spx").orEmpty(),
            mux = mux,
            carrierMode = migrated.carrierMode,
            carrierPrefer = currentCarrierPrefer.trim().lowercase(),
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

    private fun encodeAuthorityProfile(config: AppConfig): String? {
        val host = config.serverHost.trim()
        val port = config.serverPort.trim()
        if (host.isBlank() || port.isBlank()) return null
        val token = config.token.trim()
        if (token.isBlank()) return null

        val params = commonParams(config)
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encodeComponent(key)}=${encodeComponent(value)}"
        }
        val authorityHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val auth = if (token.isBlank()) authorityHost else "${encodeComponent(token)}@$authorityHost"
        val name = encodeComponent(config.name.ifBlank { host })
        return "native://$auth:$port?$query#$name"
    }

    private fun commonParams(config: AppConfig): LinkedHashMap<String, String> {
        val params = linkedMapOf<String, String>()
        params["v"] = TcptunUriVersion
        val tunnelSecurity = config.tunnelSecurity.trim().lowercase()
        val security = when {
            tunnelSecurity in AppConfig.RealitySecurityTypes -> tunnelSecurity
            config.tls -> "tls"
            else -> error(VpnTunnelConfidentialityError)
        }
        params["security"] = security
        if (security in AppConfig.RealitySecurityTypes) {
            putIfNotBlank(params, "pbk", config.realityPublicKey)
            putIfNotBlank(params, "sid", config.realityShortId)
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
        putIfNotBlank(params, "carrier_prefer", config.carrierPrefer.trim().lowercase())
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

    private fun requireSupportedCarrierPreference(value: String) {
        val normalized = value.trim().lowercase()
        if (normalized !in AppConfig.CarrierPreferences) {
            error("unsupported carrier preference: $value")
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

    private const val TcptunUriVersion = "1"
    private const val DefaultRawTransportPath = "/proxy"
    private val GoDurationPart = Regex("""(\d+(?:\.\d*)?|\.\d+)(ns|us|µs|μs|ms|s|m|h)""")

    private fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
