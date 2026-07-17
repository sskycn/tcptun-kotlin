package com.tcptun.client

internal const val MAX_DISPLAYED_CLIENT_IPS = 256

internal fun normalizeClientIps(values: Iterable<String>): List<String> {
    return values.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 64 }
        .distinct()
        .sortedWith(compareBy<String>({ it.contains(':') }, { it }))
        .take(MAX_DISPLAYED_CLIENT_IPS)
        .toList()
}
