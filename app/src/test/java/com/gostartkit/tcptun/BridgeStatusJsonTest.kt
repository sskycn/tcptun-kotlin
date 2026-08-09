package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStatusJsonTest {
    @Test
    fun eventConversionAppliesCallbackDefaults() {
        val snapshot = bridgeStatusSnapshot(
            sessionId = 7,
            sequence = 9,
            state = "running",
            remote = "",
            activeConnections = 0,
            clientIps = listOf("10.0.0.2"),
            muxSources = 2,
            lastError = "",
        )
        val event = snapshot.toEvent()

        assertEquals(7L, event.sessionId)
        assertEquals(9L, event.sequence)
        assertEquals("running", event.state)
        assertEquals("", event.remote)
        assertEquals(0, event.activeConnections)
        assertEquals(listOf("10.0.0.2"), event.clientIps)
        assertEquals(2, event.muxSources)
        assertTrue(event.shouldLog())
    }

    @Test
    fun snapshotReconciliationPreservesMissingFieldsAndAppliesExplicitClears() {
        val current = TcptunDiagnostics(
            bridgeEventReason = "existing reason",
            bridgeRemote = "old.example:443",
            bridgeActiveConnections = 4,
            bridgeLastError = "old failure",
            bridgeTimestampMs = 123L,
        )
        val missing = bridgeStatusSnapshot(state = "running")
        val explicitClear = bridgeStatusSnapshot(state = "running", remote = "", lastError = "")

        val preserved = missing.applyTo(current, "Running")
        assertEquals("existing reason", preserved.bridgeEventReason)
        assertEquals("old.example:443", preserved.bridgeRemote)
        assertEquals(4, preserved.bridgeActiveConnections)
        assertEquals("old failure", preserved.bridgeLastError)
        assertEquals(123L, preserved.bridgeTimestampMs)
        assertNull(missing.remote)

        val cleared = explicitClear.applyTo(current, "Running")
        assertEquals("", cleared.bridgeRemote)
        assertEquals("", cleared.bridgeLastError)
        assertEquals(123L, cleared.bridgeTimestampMs)
        assertFalse(explicitClear.toEvent().recoverable)
    }

    @Test
    fun parserRejectsOversizedStatusDocuments() {
        val oversized = "{" + " ".repeat(BridgeStatusJson.MaxJsonLength) + "}"

        assertThrows(IllegalArgumentException::class.java) {
            BridgeStatusJson.parse(oversized)
        }
    }

    private fun bridgeStatusSnapshot(
        sessionId: Long = 0,
        sequence: Long = 0,
        state: String = "",
        reason: String = "",
        phase: String = "",
        listen: String = "",
        remote: String? = null,
        outboundTag: String = "",
        activeConnections: Int? = null,
        clientIps: List<String>? = null,
        muxSources: Int? = null,
        muxSessions: Int? = null,
        muxStreams: Int? = null,
        recoverable: Boolean? = null,
        lastError: String? = null,
        timestampMs: Long? = null,
    ) = BridgeStatusSnapshot(
        sessionId = sessionId,
        sequence = sequence,
        state = state,
        reason = reason,
        phase = phase,
        listen = listen,
        remote = remote,
        outboundTag = outboundTag,
        activeConnections = activeConnections,
        clientIps = clientIps,
        muxSources = muxSources,
        muxSessions = muxSessions,
        muxStreams = muxStreams,
        recoverable = recoverable,
        lastError = lastError,
        timestampMs = timestampMs,
    )
}
