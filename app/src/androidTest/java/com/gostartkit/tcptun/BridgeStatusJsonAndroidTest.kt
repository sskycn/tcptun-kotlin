package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeStatusJsonAndroidTest {
    @Test
    fun parserNormalizesFieldsAndTracksPresence() {
        val snapshot = BridgeStatusJson.parse(
            """{
                "session_id":7,
                "sequence":9,
                "state":" running ",
                "remote":"",
                "active_connections":-3,
                "client_ips":[" 10.0.0.2 ","10.0.0.2"],
                "mux_sources":2,
                "last_error":""
            }""".trimIndent(),
        )

        assertEquals(7L, snapshot.sessionId)
        assertEquals(9L, snapshot.sequence)
        assertEquals("running", snapshot.state)
        assertEquals("", snapshot.remote)
        assertEquals(0, snapshot.activeConnections)
        assertEquals(listOf("10.0.0.2"), snapshot.clientIps)
        assertEquals(2, snapshot.muxSources)
        assertTrue(snapshot.toEvent().shouldLog())
    }

    @Test
    fun parserLeavesAbsentSnapshotFieldsUnset() {
        val snapshot = BridgeStatusJson.parse("""{"state":"running"}""")

        assertNull(snapshot.remote)
        assertNull(snapshot.activeConnections)
        assertNull(snapshot.clientIps)
        assertNull(snapshot.lastError)
        assertNull(snapshot.timestampMs)
    }

    @Test
    fun outboundParserKeepsOnlyRecognizedBoundedHealthRecords() {
        val statuses = BridgeStatusJson.parseOutboundHealth(
            """[
                {"tag":"a","health":"healthy","latency_ms":17,"failures":-2},
                {"tag":"b","health":"degraded","last_observed_at_ms":20},
                {"tag":"c","health":"unknown"}
            ]""".trimIndent(),
        )

        assertEquals(2, statuses.size)
        assertEquals(ProfileHealthStatus.Healthy, statuses[0].health)
        assertEquals(17L, statuses[0].latencyMs)
        assertEquals(0L, statuses[0].failures)
        assertEquals(ProfileHealthStatus.Degraded, statuses[1].health)
        assertNull(statuses[1].latencyMs)
    }
}
