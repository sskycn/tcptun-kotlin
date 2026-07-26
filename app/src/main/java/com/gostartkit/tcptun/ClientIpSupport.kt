package com.tcptun.client

internal const val MAX_DISPLAYED_CLIENT_IPS = 256
internal const val MAX_CLIENT_IP_CANDIDATES = 1_024
private const val MAX_CLIENT_IP_INPUT_LENGTH = 128

internal fun normalizeClientIps(values: Iterable<String>): List<String> {
    return runRecoverableCatching {
        values.asSequence()
            .take(MAX_CLIENT_IP_CANDIDATES)
            .filter { it.length <= MAX_CLIENT_IP_INPUT_LENGTH }
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 64 }
            .distinct()
            .take(MAX_DISPLAYED_CLIENT_IPS)
            .sortedWith(compareBy<String>({ it.contains(':') }, { it }))
            .toList()
    }.getOrDefault(emptyList())
}
