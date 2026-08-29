package com.tcptun.client

internal fun AppConfig.validationError(): String? {
    if (!hasSafeStorageSize()) return "profile data is too large"
    if (name.isBlank()) return "profile name is required"
    if (rawConfigJson.isNotBlank()) return validateRawConfig(rawConfigJson)
    if (serverHost.isBlank()) return "server address is required"
    val port = serverPort.toIntOrNull() ?: return "server port must be a number"
    if (port !in 1..65535) return "server port must be between 1 and 65535"
    if (protocol != "native") {
        return if (protocol.trim().lowercase() in RemovedTunnelProtocols) {
            unsupportedTunnelProtocolMessage(protocol)
        } else {
            "unsupported protocol: $protocol"
        }
    }
    if (transport !in AppConfig.Transports) return "unsupported transport: $transport"
    if (upstreamProtocol !in AppConfig.UpstreamProtocols) return "unsupported upstream protocol: $upstreamProtocol"
    if (token.isBlank()) return "native token is required"
    val normalizedSecurity = tunnelSecurity.trim().lowercase()
    if (normalizedSecurity !in AppConfig.TunnelSecurityTypes) return "unsupported security: $tunnelSecurity"
    if (normalizedSecurity.isNotBlank() && tls) return "TLS cannot be combined with tunnel security"
    if (normalizedSecurity == "reality" && transport != "raw") {
        return "reality requires raw transport"
    }
    if (normalizedSecurity == "reality") {
        if (sni.isBlank()) return "$normalizedSecurity requires SNI"
        if (realityPublicKey.isBlank()) return "$normalizedSecurity requires a public key"
    }
    val echFieldsConfigured =
        echPublicName.isNotBlank() || echPublicKey.isNotBlank() || echPorts.isNotBlank()
    if (!echEnabled && echFieldsConfigured) {
        return "ECH must be enabled when ClientHello protection fields are configured"
    }
    val normalizedCarrierMode = carrierMode.trim().lowercase()
    if (normalizedCarrierMode !in AppConfig.CarrierModes) return "unsupported carrier mode: $carrierMode"
    val normalizedCarrierPrefer = carrierPrefer.trim().lowercase()
    if (normalizedCarrierPrefer !in AppConfig.CarrierPreferences) {
        return "unsupported carrier preference: $carrierPrefer"
    }
    if (normalizedCarrierPrefer.isNotBlank() && normalizedCarrierMode != "auto") {
        return "carrier preference requires automatic carrier mode"
    }
    val normalizedCarrierUdpMode = carrierUdpMode.trim().lowercase()
    if (normalizedCarrierUdpMode !in AppConfig.CarrierUdpModes) {
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
    val carrierSettingsConfigured =
        normalizedCarrierMode.isNotBlank() ||
            normalizedCarrierPrefer.isNotBlank() ||
            normalizedCarrierUdpMode.isNotBlank() ||
            carrierReceiveWindows.any { it != 0 }
    if (
        !mux &&
        (carrierSettingsConfigured || resumableSettingsConfigured || muxMaxSessions != 0 ||
            muxMaxStreamsPerSession != 0 || muxWarmSpare != 0)
    ) {
        return "mux must be enabled when carrier or mux options are configured"
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
    if (echEnabled) {
        if (protocol != "native") return "ECH requires native protocol"
        if (transport != "raw") return "ECH requires raw transport"
        if (tls || normalizedSecurity.isNotBlank()) return "ECH requires security none"
        if (normalizedCarrierMode !in setOf("", "tcp")) return "ECH requires TCP carrier mode"
        if (muxResume) return "ECH does not support resumable mux"
        if (!isValidEchPublicName(echPublicName)) {
            return "ECH public name must be a valid DNS name"
        }
        if (echPublicKey.isBlank()) return "ECH public key is required"
        runRecoverableCatching { parseEchPorts(echPorts) }
            .exceptionOrNull()
            ?.let { return it.message ?: "invalid ECH ports" }
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
        if (normalizedCarrierMode == "auto" && !tls && normalizedSecurity != "reality") {
            return "automatic carrier requires TLS or reality security"
        }
        if (normalizedCarrierMode == "quic" && !tls && normalizedSecurity != "reality") {
            return "QUIC carrier requires TLS or reality security"
        }
        if (normalizedSecurity == "reality") {
            if (realityShortId.isBlank()) return "$normalizedCarrierMode carrier requires a short ID"
        }
    }
    if (path.isBlank()) return "path is required"
    return null
}
