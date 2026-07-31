package com.tcptun.client

/** SOCKS5 username/password sub-negotiation uses one unsigned byte for each length. */
internal const val MaxSocksCredentialUtf8Bytes = 255

internal fun hasValidSocksCredentialSize(value: String): Boolean {
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        byteCount += utf8Width(codePoint)
        if (byteCount > MaxSocksCredentialUtf8Bytes) return false
        index += Character.charCount(codePoint)
    }
    return true
}

/** Returns the longest code-point-safe prefix that fits the SOCKS5 byte-length field. */
internal fun truncateSocksCredential(value: String): String {
    val result = StringBuilder(value.length.coerceAtMost(MaxSocksCredentialUtf8Bytes))
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val width = utf8Width(codePoint)
        if (byteCount + width > MaxSocksCredentialUtf8Bytes) break
        result.appendCodePoint(codePoint)
        byteCount += width
        index += Character.charCount(codePoint)
    }
    return if (index == value.length) value else result.toString()
}

/** Java's UTF-8 encoder replaces an isolated surrogate with one replacement byte. */
private fun utf8Width(codePoint: Int): Int = when {
    codePoint <= 0x7f -> 1
    codePoint <= 0x7ff -> 2
    codePoint in 0xd800..0xdfff -> 1
    codePoint <= 0xffff -> 3
    else -> 4
}
