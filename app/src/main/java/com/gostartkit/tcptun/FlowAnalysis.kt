package com.tcptun.client

import org.json.JSONObject

internal const val MAX_FLOW_ANALYSIS_EVENT_JSON_LENGTH = 64 * 1024
private const val MAX_FLOW_ANALYSIS_FIELD_LENGTH = 4 * 1024

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
    if (eventJson.length > MAX_FLOW_ANALYSIS_EVENT_JSON_LENGTH) return null
    val json = runRecoverableCatching {
        requireSafeJsonNesting(eventJson)
        JSONObject(eventJson)
    }.getOrNull() ?: return null
    val sessionId = json.optLong("session_id", 0)
    val sequence = json.optLong("sequence", 0)
    val type = json.optBoundedString("type")?.lowercase() ?: return null
    val network = json.optBoundedString("network")?.lowercase() ?: return null
    val destination = json.optBoundedString("destination") ?: return null
    val appId = json.optJSONObject("app")?.optBoundedString("id") ?: return null
    if (sessionId <= 0 || sequence <= 0 || type.isBlank() || network !in setOf("tcp", "udp")) return null
    if (destination.isBlank() || appId.isBlank()) return null
    return FlowAnalysisEvent(
        sessionId = sessionId,
        sequence = sequence,
        droppedEvents = json.optLong("dropped_events", 0).coerceAtLeast(0),
        timestampMs = json.optLong("timestamp_ms", 0),
        type = type,
        network = network,
        source = json.optBoundedString("source") ?: return null,
        destination = destination,
        domain = json.optBoundedString("domain") ?: return null,
        ip = json.optBoundedString("ip") ?: return null,
        originalIp = json.optBoundedString("original_ip") ?: return null,
        port = json.optInt("port", 0).coerceIn(0, 65_535),
        outboundTag = json.optBoundedString("outbound_tag") ?: return null,
        routeReason = json.optBoundedString("route_reason") ?: return null,
        appId = appId,
    )
}

private fun JSONObject.optBoundedString(name: String): String? {
    val value = opt(name)
    if (value == null || value === JSONObject.NULL) return ""
    if (value !is String || value.length > MAX_FLOW_ANALYSIS_FIELD_LENGTH) return null
    return value.trim()
}
