package com.tcptun.client

import org.json.JSONObject

/** Uses tcptun-go as the single source of truth for versioned share-profile payloads. */
internal object TcptunProfileCodec {
    fun encode(profile: AppConfig): String {
        return invoke("encodeProfile", profile.toBridgeProfileJson().toString())
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("androidbridge.EncodeProfile returned an empty payload")
    }

    fun decode(value: String): AppConfig {
        val profileJson = invoke("decodeProfile", value)
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
