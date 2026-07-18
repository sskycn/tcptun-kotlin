package com.tcptun.client

import org.json.JSONObject

data class FlowAnalysisEvent(
    val sessionId: Long,
    val sequence: Long,
    val droppedEvents: Long,
    val timestampMs: Long,
    val type: String,
    val network: String,
    val source: String,
    val destination: String,
    val domain: String,
    val ip: String,
    val originalIp: String,
    val port: Int,
    val outboundTag: String,
    val routeReason: String,
    val appId: String,
) {
    val displayDestination: String
        get() = domain.ifBlank { ip.ifBlank { destination } }
}

internal fun parseFlowAnalysisEvent(eventJson: String): FlowAnalysisEvent? {
    val json = runCatching { JSONObject(eventJson) }.getOrNull() ?: return null
    val sessionId = json.optLong("session_id", 0)
    val sequence = json.optLong("sequence", 0)
    val type = json.optString("type").trim().lowercase()
    val network = json.optString("network").trim().lowercase()
    val destination = json.optString("destination").trim()
    val appId = json.optJSONObject("app")?.optString("id").orEmpty().trim()
    if (sessionId <= 0 || sequence <= 0 || type.isBlank() || network !in setOf("tcp", "udp")) return null
    if (destination.isBlank() || appId.isBlank()) return null
    return FlowAnalysisEvent(
        sessionId = sessionId,
        sequence = sequence,
        droppedEvents = json.optLong("dropped_events", 0).coerceAtLeast(0),
        timestampMs = json.optLong("timestamp_ms", 0),
        type = type,
        network = network,
        source = json.optString("source").trim(),
        destination = destination,
        domain = json.optString("domain").trim(),
        ip = json.optString("ip").trim(),
        originalIp = json.optString("original_ip").trim(),
        port = json.optInt("port", 0).coerceIn(0, 65_535),
        outboundTag = json.optString("outbound_tag").trim(),
        routeReason = json.optString("route_reason").trim(),
        appId = appId,
    )
}
