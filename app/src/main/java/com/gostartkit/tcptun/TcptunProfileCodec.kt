package com.tcptun.client

import org.json.JSONObject

/** Uses tcptun-go as the single source of truth for versioned share-profile payloads. */
internal object TcptunProfileCodec {
    fun encode(profile: AppConfig): String {
        require(!profile.muxResume && profile.muxResumeTimeoutMillis == 0 && profile.muxResumeBufferSize == 0) {
            "T3 cannot represent resumable mux settings"
        }
        require(!profile.hasEchClientHelloSettings()) {
            "T3 cannot represent ECH ClientHello protection"
        }
        val encoded = invoke("encodeProfile", profile.toBridgeProfileJson().toString())
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("androidbridge.EncodeProfile returned an empty payload")
        require(encoded.length <= MaxProfileUriLength) { "encoded profile is too large" }
        return encoded
    }

    /**
     * Renders the same T3 profile payload as tcptun-go's native QR encoder.
     *
     * moduleSize is a Go int, which gomobile exposes as a Java long. Zero and
     * an empty recovery level select the bridge defaults (8 pixels and medium).
     */
    fun encodeQrCode(
        profile: AppConfig,
        recoveryLevel: String = "",
        moduleSize: Int = 0,
        compact: Boolean = false,
    ): ByteArray {
        require(moduleSize >= 0) { "QR module size must not be negative" }
        val png = invokeQrCode(
            profile.toBridgeProfileJson().toString(),
            recoveryLevel,
            moduleSize,
            compact,
        )
        require(png.isNotEmpty()) { "androidbridge.EncodeProfileQRCode returned an empty image" }
        return png
    }

    fun decode(value: String): AppConfig {
        val profileJson = invoke("decodeProfile", value)
        require(profileJson.length <= MaxProfileImportLength) { "decoded profile is too large" }
        requireSafeJsonNesting(profileJson)
        return AppConfig.fromJson(JSONObject(profileJson))
    }

    private fun invoke(methodName: String, value: String): String {
        return try {
            val bridgeClass = Class.forName("androidbridge.Androidbridge")
            bridgeClass.getMethod(methodName, String::class.java).invoke(null, value) as? String
                ?: throw IllegalStateException("androidbridge.$methodName returned no text")
        } catch (err: ReflectiveOperationException) {
            val cause = err.cause ?: err
            throw IllegalArgumentException(cause.message ?: cause.javaClass.name, cause)
        } catch (err: LinkageError) {
            throw IllegalStateException(
                "androidbridge profile codec is unavailable. Rebuild app/libs/androidbridge.aar.",
                err,
            )
        }
    }

    private fun invokeQrCode(
        profileJson: String,
        recoveryLevel: String,
        moduleSize: Int,
        compact: Boolean,
    ): ByteArray {
        return try {
            val bridgeClass = Class.forName("androidbridge.Androidbridge")
            bridgeClass.getMethod(
                "encodeProfileQRCode",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, profileJson, recoveryLevel, moduleSize.toLong(), compact) as? ByteArray
                ?: throw IllegalStateException("androidbridge.EncodeProfileQRCode returned no image")
        } catch (err: ReflectiveOperationException) {
            val cause = err.cause ?: err
            throw IllegalArgumentException(cause.message ?: cause.javaClass.name, cause)
        } catch (err: LinkageError) {
            throw IllegalStateException(
                "androidbridge profile QR codec is unavailable. Rebuild app/libs/androidbridge.aar.",
                err,
            )
        }
    }
}

/** Dedicated JSON boundary documented by tcptun-go's Android profile bridge. */
private fun AppConfig.toBridgeProfileJson(): JSONObject {
    return JSONObject()
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
