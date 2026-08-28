package com.tcptun.client

import org.json.JSONObject

/** Uses tcptun-go as the single source of truth for the A1 account wire format. */
internal object LocalProxyAccountCodec {
    private const val A1Prefix = "A1:"
    private const val MaxA1PayloadLength = 771
    private const val MaxBridgeJsonLength = 4 * 1024

    fun encode(account: LocalProxyUser): String {
        validateLocalProxyUsers(listOf(account))
        val encoded = invokeText("encodeProxyAccount", account.toBridgeJson().toString())
        require(encoded.startsWith(A1Prefix)) { "androidbridge returned a non-A1 proxy account payload" }
        require(encoded.length <= MaxA1PayloadLength) { "encoded proxy account is too large" }
        return encoded
    }

    fun decode(raw: String): LocalProxyUser {
        val value = raw.trim()
        require(value.startsWith(A1Prefix)) { "A1 proxy account prefix is required" }
        require(value.length <= MaxA1PayloadLength) { "proxy account payload is too large" }
        val accountJson = invokeText("decodeProxyAccount", value)
        require(accountJson.length <= MaxBridgeJsonLength) { "decoded proxy account is too large" }
        requireSafeJsonNesting(accountJson)
        val json = JSONObject(accountJson)
        require(json.length() == 2 && json.has("username") && json.has("password")) {
            "androidbridge returned an invalid proxy account DTO"
        }
        return LocalProxyUser(
            username = json.getString("username"),
            password = json.getString("password"),
        ).also { validateLocalProxyUsers(listOf(it)) }
    }

    fun encodeQrCode(
        account: LocalProxyUser,
        recoveryLevel: String = "",
        moduleSize: Int = 0,
    ): ByteArray {
        require(moduleSize >= 0) { "QR module size must not be negative" }
        validateLocalProxyUsers(listOf(account))
        val png = try {
            val bridgeClass = Class.forName("androidbridge.Androidbridge")
            bridgeClass.getMethod(
                "encodeProxyAccountQRCode",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
            ).invoke(
                null,
                account.toBridgeJson().toString(),
                recoveryLevel,
                moduleSize.toLong(),
            ) as? ByteArray ?: throw IllegalStateException(
                "androidbridge.EncodeProxyAccountQRCode returned no image",
            )
        } catch (error: ReflectiveOperationException) {
            val cause = error.cause ?: error
            throw IllegalArgumentException(cause.message ?: cause.javaClass.name, cause)
        } catch (error: LinkageError) {
            throw IllegalStateException(
                "androidbridge proxy account QR codec is unavailable. Rebuild app/libs/androidbridge.aar.",
                error,
            )
        }
        require(png.isNotEmpty()) { "androidbridge.EncodeProxyAccountQRCode returned an empty image" }
        return png
    }

    private fun invokeText(methodName: String, value: String): String {
        return try {
            val bridgeClass = Class.forName("androidbridge.Androidbridge")
            bridgeClass.getMethod(methodName, String::class.java).invoke(null, value) as? String
                ?: throw IllegalStateException("androidbridge.$methodName returned no text")
        } catch (error: ReflectiveOperationException) {
            val cause = error.cause ?: error
            throw IllegalArgumentException(cause.message ?: cause.javaClass.name, cause)
        } catch (error: LinkageError) {
            throw IllegalStateException(
                "androidbridge proxy account codec is unavailable. Rebuild app/libs/androidbridge.aar.",
                error,
            )
        }
    }
}

private fun LocalProxyUser.toBridgeJson(): JSONObject = JSONObject()
    .put("username", username)
    .put("password", password)
