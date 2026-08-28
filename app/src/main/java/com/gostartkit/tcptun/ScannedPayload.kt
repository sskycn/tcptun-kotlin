package com.tcptun.client

internal sealed interface ScannedPayload {
    data class Profile(val config: AppConfig) : ScannedPayload
    data class ProxyAccount(val account: LocalProxyUser) : ScannedPayload
}

/** Dispatches A1 before the profile codec so credentials can never become AppConfig. */
internal fun decodeScannedPayload(raw: String): ScannedPayload {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "scanned payload is empty" }
    return if (trimmed.startsWith("A1:")) {
        ScannedPayload.ProxyAccount(LocalProxyAccountCodec.decode(trimmed))
    } else {
        ScannedPayload.Profile(ProfileUriCodec.decode(trimmed).getOrThrow())
    }
}
