package com.tcptun.client

import org.json.JSONObject
import org.json.JSONArray

internal data class BridgeOutboundHealthStatus(
    val tag: String,
    val health: ProfileHealthStatus,
    val latencyMs: Long?,
    val failures: Long,
    val lastObservedAtMs: Long,
    val lastSucceededAtMs: Long,
)

internal data class BridgeStatusEvent(
    val sessionId: Long,
    val sequence: Long,
    val state: String,
    val reason: String,
    val phase: String,
    val listen: String,
    val remote: String,
    val outboundTag: String,
    val activeConnections: Int,
    val clientIps: List<String>,
    val muxSources: Int,
    val muxSessions: Int,
    val muxStreams: Int,
    val recoverable: Boolean,
    val lastError: String,
    val timestampMs: Long,
) {
    fun shouldLog(): Boolean = state.lowercase() in LoggableStates

    fun logLine(): String {
        val details = listOfNotNull(
            reason.takeIf { it.isNotBlank() },
            phase.takeIf { it.isNotBlank() },
            remote.takeIf { it.isNotBlank() }?.let { "remote=$it" },
            outboundTag.takeIf { it.isNotBlank() }?.let { "outbound=$it" },
            lastError.takeIf { it.isNotBlank() }?.let { "error=$it" },
        ).joinToString(" ")
        return "tcptun status: ${state.ifBlank { "unknown" }}" +
            details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    }

    private companion object {
        val LoggableStates = setOf(
            "core_ready",
            "running",
            "degraded",
            "reconnecting",
            "remote_endpoints_changed",
            "error",
            "stopped",
        )
    }
}

/** Parsed bridge status with presence information needed by snapshot reconciliation. */
internal data class BridgeStatusSnapshot(
    val sessionId: Long,
    val sequence: Long,
    val state: String,
    val reason: String,
    val phase: String,
    val listen: String,
    val remote: String?,
    val outboundTag: String,
    val activeConnections: Int?,
    val clientIps: List<String>?,
    val muxSources: Int?,
    val muxSessions: Int?,
    val muxStreams: Int?,
    val recoverable: Boolean?,
    val lastError: String?,
    val timestampMs: Long?,
) {
    fun toEvent(): BridgeStatusEvent = BridgeStatusEvent(
        sessionId = sessionId,
        sequence = sequence,
        state = state,
        reason = reason,
        phase = phase,
        listen = listen,
        remote = remote.orEmpty(),
        outboundTag = outboundTag,
        activeConnections = activeConnections ?: 0,
        clientIps = clientIps.orEmpty(),
        muxSources = muxSources ?: 0,
        muxSessions = muxSessions ?: 0,
        muxStreams = muxStreams ?: 0,
        recoverable = recoverable ?: false,
        lastError = lastError.orEmpty(),
        timestampMs = timestampMs ?: 0L,
    )

    fun applyTo(current: TcptunDiagnostics, simpleStatus: String): TcptunDiagnostics = current.copy(
        bridgeStatus = simpleStatus,
        bridgeEventState = state.ifBlank { current.bridgeEventState },
        bridgeEventReason = reason.ifBlank { current.bridgeEventReason },
        bridgeEventPhase = phase.ifBlank { current.bridgeEventPhase },
        bridgeListen = listen.ifBlank { current.bridgeListen },
        bridgeRemote = remote ?: current.bridgeRemote,
        bridgeActiveConnections = activeConnections ?: current.bridgeActiveConnections,
        bridgeClientIps = clientIps ?: current.bridgeClientIps,
        bridgeMuxSources = muxSources ?: current.bridgeMuxSources,
        bridgeMuxSessions = muxSessions ?: current.bridgeMuxSessions,
        bridgeMuxStreams = muxStreams ?: current.bridgeMuxStreams,
        bridgeRecoverable = recoverable ?: current.bridgeRecoverable,
        bridgeLastError = lastError ?: current.bridgeLastError,
        bridgeTimestampMs = timestampMs ?: current.bridgeTimestampMs,
    )

    fun runtimeSnapshot(epoch: Long): BridgeRuntimeSnapshot = BridgeRuntimeSnapshot(
        epoch = epoch,
        activeConnections = activeConnections ?: 0,
        clientIps = clientIps.orEmpty(),
        muxSources = muxSources ?: 0,
        muxSessions = muxSessions ?: 0,
        muxStreams = muxStreams ?: 0,
    )
}

internal object BridgeStatusJson {
    const val MaxJsonLength = 64 * 1024
    private const val MaxFieldLength = 4 * 1024
    private const val MaxStatusItemCount = 1_024

    fun parse(rawStatus: String): BridgeStatusSnapshot {
        require(rawStatus.length <= MaxJsonLength) { "bridge status JSON is too large" }
        requireSafeJsonNesting(rawStatus)
        val json = JSONObject(rawStatus)
        return BridgeStatusSnapshot(
            sessionId = json.optLong("session_id", 0).coerceAtLeast(0),
            sequence = json.optLong("sequence", 0).coerceAtLeast(0),
            state = json.optStatusString("state"),
            reason = json.optStatusString("reason"),
            phase = json.optStatusString("phase"),
            listen = json.optStatusString("listen"),
            remote = json.optStatusString("remote").takeIf { json.has("remote") },
            outboundTag = json.optStatusString("outbound_tag"),
            activeConnections = json.optNonNegativeInt("active_connections"),
            clientIps = if (json.has("client_ips")) {
                normalizeClientIps(
                    buildList {
                        json.optJSONArray("client_ips")?.let { values ->
                            for (index in 0 until minOf(values.length(), MAX_CLIENT_IP_CANDIDATES)) {
                                add(values.optString(index))
                            }
                        }
                    },
                )
            } else {
                null
            },
            muxSources = json.optNonNegativeInt("mux_sources"),
            muxSessions = json.optNonNegativeInt("mux_sessions"),
            muxStreams = json.optNonNegativeInt("mux_streams"),
            recoverable = json.optBoolean("recoverable").takeIf { json.has("recoverable") },
            lastError = redactSensitiveText(json.optStatusString("last_error"))
                .takeIf { json.has("last_error") },
            timestampMs = json.optLong("timestamp_ms", 0).takeIf { it > 0 },
        )
    }

    fun parseOutboundHealth(rawStatuses: String): List<BridgeOutboundHealthStatus> {
        require(rawStatuses.length <= MaxJsonLength) { "outbound status JSON is too large" }
        requireSafeJsonNesting(rawStatuses)
        val statuses = JSONArray(rawStatuses)
        return buildList {
            for (index in 0 until minOf(statuses.length(), MaxStatusItemCount)) {
                val status = statuses.optJSONObject(index) ?: continue
                val health = when (status.optStatusString("health").lowercase()) {
                    "healthy" -> ProfileHealthStatus.Healthy
                    "degraded" -> ProfileHealthStatus.Degraded
                    else -> continue
                }
                add(
                    BridgeOutboundHealthStatus(
                        tag = status.optStatusString("tag"),
                        health = health,
                        latencyMs = status.optLong("latency_ms").takeIf { it > 0 },
                        failures = status.optLong("failures").coerceAtLeast(0),
                        lastObservedAtMs = status.optLong("last_observed_at_ms"),
                        lastSucceededAtMs = status.optLong("last_succeeded_at_ms"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optStatusString(name: String): String {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL || value !is String) return ""
        return value.take(MaxFieldLength).trim()
    }

    private fun JSONObject.optNonNegativeInt(name: String): Int? =
        optInt(name, 0).coerceAtLeast(0).takeIf { has(name) }
}
