package com.tcptun.client

import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal const val MaxProfileUriLength = 64 * 1024

internal val SupportedProfileUriSchemes: Set<String> =
    (AppConfig.Protocols + "tcptun").toSet()

internal const val ProfileDeepLinkScheme = "https"
internal const val ProfileDeepLinkHost = "tcptun.com"
internal const val ProfileDeepLinkNamespace = "x"
internal const val ProfileDeepLinkVersion = "v1"

internal object ProfileDeepLinkCodec {
    fun encode(profileUri: String): String {
        val value = profileUri.trim()
        require(value.isNotBlank() && value.length <= MaxProfileUriLength) { "invalid profile URI length" }
        require(Uri.parse(value).scheme?.lowercase(Locale.ROOT) in SupportedProfileUriSchemes) {
            "unsupported profile URI"
        }
        val payload = Base64.encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$ProfileDeepLinkScheme://$ProfileDeepLinkHost/$ProfileDeepLinkNamespace/$ProfileDeepLinkVersion#p=$payload".also {
            require(it.length <= MaxProfileUriLength) { "profile link is too long" }
        }
    }

    fun decode(raw: String): Result<String> = runCatching {
        val value = raw.trim()
        if (value.isBlank() || value.length > MaxProfileUriLength) error("invalid profile link length")
        val uri = Uri.parse(value)
        if (!uri.scheme.equals(ProfileDeepLinkScheme, ignoreCase = true)) error("unsupported profile link scheme")
        if (!uri.host.equals(ProfileDeepLinkHost, ignoreCase = true)) error("unsupported profile link host")
        if (uri.encodedUserInfo != null) error("profile link must not contain user info")
        if (uri.port != -1) error("profile link must not specify a port")
        if (uri.encodedPath != "/$ProfileDeepLinkNamespace/$ProfileDeepLinkVersion") {
            error("unsupported profile link path or version")
        }
        if (uri.encodedQuery != null) error("profile link must not contain a query")
        val fragment = uri.encodedFragment.orEmpty()
        if (!fragment.startsWith("p=") || fragment.indexOf('&') >= 0) error("missing profile payload")
        val encodedPayload = fragment.removePrefix("p=")
        if (encodedPayload.isBlank() || !encodedPayload.matches(Base64UrlPattern)) error("invalid profile payload")
        val decoded = Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP)
        val canonicalPayload = Base64.encodeToString(
            decoded,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        if (encodedPayload != canonicalPayload) error("non-canonical profile payload")
        val profileUri = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decoded))
            .toString()
        if (profileUri != profileUri.trim()) error("non-canonical profile URI")
        if (profileUri.isBlank() || profileUri.length > MaxProfileUriLength) error("invalid profile payload length")
        if (Uri.parse(profileUri).scheme?.lowercase(Locale.ROOT) !in SupportedProfileUriSchemes) {
            error("unsupported profile URI")
        }
        profileUri
    }

    fun isSupportedLink(raw: String): Boolean = decode(raw).isSuccess

    private val Base64UrlPattern = Regex("^[A-Za-z0-9_-]+$")
}

internal data class PendingProfileUri(
    val sequence: Long,
    val value: String,
)

internal fun profileUriFromIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val value = uri.toString().trim()
    if (value.isBlank() || value.length > MaxProfileUriLength) return null
    return when {
        scheme in SupportedProfileUriSchemes -> value
        scheme == ProfileDeepLinkScheme && ProfileDeepLinkCodec.isSupportedLink(value) -> value
        else -> null
    }
}

internal fun profileConnectionIdentity(config: AppConfig): String? {
    if (config.rawConfigJson.isNotBlank()) {
        return runCatching {
            "json:" + canonicalJsonValue(JSONObject(config.rawConfigJson))
        }.getOrNull()
    }
    return ProfileUriCodec.encode(config.copy(id = "", name = ""))?.let { "uri:$it" }
}

private fun canonicalJsonValue(value: Any?): String {
    return when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
        ) { key -> "${JSONObject.quote(key)}:${canonicalJsonValue(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
        ) { index -> canonicalJsonValue(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}

/**
 * Applies both Android model validation and the Go core's complete
 * non-listening runtime construction before an imported profile is persisted.
 */
internal fun validateImportedProfile(config: AppConfig) {
    config.validate()?.let { throw IllegalArgumentException(it) }
    val bridgeConfig = try {
        config.toBridgeJson(localListenAddr = "127.0.0.1:1080")
    } catch (err: Exception) {
        throw IllegalArgumentException(err.message ?: "profile cannot be converted to runtime config", err)
    }
    validateTcptunConfig(bridgeConfig)
}
