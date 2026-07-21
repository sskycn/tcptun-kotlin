package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowAnalysisContractTest {
    @Test
    fun parsesBridgeFlowEnvelope() {
        val event = parseFlowAnalysisEvent(
            """{
                "session_id":3,
                "sequence":12,
                "dropped_events":2,
                "timestamp_ms":1784380000000,
                "type":"connected",
                "network":"tcp",
                "source":"10.0.0.2:43120",
                "destination":"api.example.com:443",
                "domain":"api.example.com",
                "original_ip":"198.18.0.7",
                "port":443,
                "outbound_tag":"direct",
                "route_reason":"rule[0]",
                "app":{"id":"com.example.target","platform":"android"}
            }""".trimIndent(),
        )

        assertEquals("api.example.com", event?.displayDestination)
        assertEquals("198.18.0.7", event?.originalIp)
        assertEquals(2L, event?.droppedEvents)
        assertEquals("com.example.target", event?.appId)
    }

    @Test
    fun rejectsMalformedOrUnattributedEvents() {
        assertNull(parseFlowAnalysisEvent("not-json"))
        assertNull(parseFlowAnalysisEvent("""{"session_id":1,"sequence":1,"type":"sent","network":"udp","destination":"1.1.1.1:53"}"""))
        assertNull(
            parseFlowAnalysisEvent(
                """{"session_id":1,"sequence":1,"type":"sent","network":"udp","destination":53,"app":{"id":"com.example.target"}}""",
            ),
        )
        assertNull(parseFlowAnalysisEvent(" ".repeat(MAX_FLOW_ANALYSIS_EVENT_JSON_LENGTH + 1)))
        assertNull(
            parseFlowAnalysisEvent(
                eventJson(sequence = 1).replace("example.com:443", "x".repeat(4 * 1024 + 1)),
            ),
        )
    }

    @Test
    fun stateDropsStaleAndOtherApplicationEvents() {
        TcptunState.setFlowAnalysisApp("")
        TcptunState.setFlowAnalysisApp("com.example.target")
        TcptunState.clearFlowEvents()
        val epoch = TcptunState.beginBridgeSession()
        val accepted = TcptunState.applyBridgeFlowEvent(epoch, eventJson(sequence = 2))
        val stale = TcptunState.applyBridgeFlowEvent(epoch, eventJson(sequence = 1))
        val other = TcptunState.applyBridgeFlowEvent(
            epoch,
            eventJson(sequence = 3, appId = "com.example.other"),
        )

        assertEquals(2L, accepted?.sequence)
        assertNull(stale)
        assertNull(other)
        assertEquals(listOf(2L), TcptunState.state.value.flowEvents.map(FlowAnalysisEvent::sequence))
        TcptunState.clearFlowEvents()
    }

    @Test
    fun selectedSharedUidPackageBecomesObserverIdentity() {
        val selected = JSONObject(
            requireNotNull(
                androidAppIdentityJson(
                    uid = 10123,
                    packages = listOf("com.example.sibling", "com.example.target"),
                    flowAnalysisApp = "com.example.target",
                ),
            ),
        )
        val unmatched = JSONObject(
            requireNotNull(
                androidAppIdentityJson(
                    uid = 10123,
                    packages = listOf("com.example.sibling", "com.example.target"),
                    flowAnalysisApp = "com.example.other",
                ),
            ),
        )

        assertEquals("com.example.target", selected.getString("id"))
        assertEquals(2, selected.getJSONObject("attributes").getJSONArray("packages").length())
        assertTrue(!unmatched.has("id"))
    }

    private fun eventJson(sequence: Long, appId: String = "com.example.target"): String =
        """{
            "session_id":4,
            "sequence":$sequence,
            "timestamp_ms":1000,
            "type":"connected",
            "network":"tcp",
            "destination":"example.com:443",
            "domain":"example.com",
            "port":443,
            "app":{"id":"$appId","platform":"android"}
        }""".trimIndent()
}
