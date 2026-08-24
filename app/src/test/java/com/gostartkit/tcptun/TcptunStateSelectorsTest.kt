package com.tcptun.client

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class TcptunStateSelectorsTest {
    @Test
    fun `log updates do not emit diagnostics settings or profiles state`() {
        val base = runtimeState()
        val states = (0..1_000).map { index ->
            base.copy(logs = listOf("log-$index"))
        }

        assertEquals(1, emissions(states, ::selectDiagnosticsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectSettingsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectProfilesRuntimeUi).size)
        assertEquals(1_001, emissions(states, ::selectLogs).size)
    }

    @Test
    fun `tcping updates only emit tcping related projections`() {
        val base = runtimeState(logs = listOf("ready"))
        val updated = base.copy(
            tcping = TcpingProgress(
                requestId = 7,
                targetLabel = "example.com:443",
                running = true,
                total = 2,
            ),
        )
        val states = listOf(base, updated)

        assertEquals(2, emissions(states, ::selectTcping).size)
        assertEquals(2, emissions(states, ::selectProfilesRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectDiagnosticsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectSettingsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectLogs).size)
    }

    @Test
    fun `diagnostics updates do not emit logs tcping or profile health`() {
        val base = runtimeState(logs = listOf("ready"))
        val updated = base.copy(
            diagnostics = base.diagnostics.copy(
                bridgeTimestampMs = 42,
                bridgeMuxStreams = 3,
            ),
        )
        val states = listOf(base, updated)

        assertEquals(2, emissions(states, ::selectDiagnostics).size)
        assertEquals(2, emissions(states, ::selectDiagnosticsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectLogs).size)
        assertEquals(1, emissions(states, ::selectTcping).size)
        assertEquals(1, emissions(states, ::selectProfileHealth).size)
        assertEquals(1, emissions(states, ::selectProfilesRuntimeUi).size)
    }

    @Test
    fun `profile health updates only emit profile projections`() {
        val base = runtimeState(logs = listOf("ready"))
        val updated = base.copy(
            profileHealth = mapOf(
                "profile-1" to ProfileHealth(
                    status = ProfileHealthStatus.Healthy,
                    latencyMs = 12,
                ),
            ),
        )
        val states = listOf(base, updated)

        assertEquals(2, emissions(states, ::selectProfileHealth).size)
        assertEquals(2, emissions(states, ::selectProfilesRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectDiagnosticsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectSettingsRuntimeUi).size)
        assertEquals(1, emissions(states, ::selectLogs).size)
    }

    @Test
    fun `flow analysis state and selection remain separate from runtime projections`() {
        val runtime = runtimeState()
        val selectionChanged = runtime.copy(flowAnalysisApp = "com.example.app")
        val before = FlowAnalysisState()
        val after = FlowAnalysisState(events = listOf(flowEvent()))

        assertNotSame(TcptunState.state, TcptunState.flowAnalysis)
        assertNotEquals(before, after)
        assertEquals(2, emissions(listOf(runtime, selectionChanged), ::selectFlowAnalysisApp).size)
        assertEquals(1, emissions(listOf(runtime, selectionChanged), ::selectProfilesRuntimeUi).size)
        assertEquals(1, emissions(listOf(runtime, selectionChanged), ::selectDiagnosticsRuntimeUi).size)
    }

    @Test
    fun `profiles composite preserves atomic status readiness and tcping snapshots`() {
        val starting = runtimeState().copy(
            status = VpnStatus.Starting,
            connectionsReady = false,
        )
        val running = starting.copy(
            status = VpnStatus.Running,
            connectionsReady = true,
            tcping = TcpingProgress(requestId = 9, running = true, total = 1),
        )

        val values = emissions(listOf(starting, running), ::selectProfilesRuntimeUi)

        assertEquals(2, values.size)
        assertEquals(VpnStatus.Starting, values[0].status)
        assertEquals(false, values[0].connectionsReady)
        assertEquals(VpnStatus.Running, values[1].status)
        assertEquals(true, values[1].connectionsReady)
        assertEquals(9, values[1].tcping.requestId)
    }

    private fun runtimeState(logs: List<String> = emptyList()) = TcptunRuntimeState(
        status = VpnStatus.Running,
        connectionsReady = true,
        diagnostics = TcptunDiagnostics(
            vpnStatus = VpnStatus.Running.displayName,
            bridgeListen = "127.0.0.1:1080",
            mtu = 1400,
        ),
        logs = logs,
    )

    private fun flowEvent() = FlowAnalysisEvent(
        sessionId = 1,
        sequence = 1,
        droppedEvents = 0,
        timestampMs = 1,
        type = "tcp",
        network = "tcp",
        source = "10.0.0.2:1234",
        destination = "203.0.113.1:443",
        domain = "example.com",
        ip = "203.0.113.1",
        originalIp = "",
        port = 443,
        outboundTag = "proxy",
        routeReason = "default",
        appId = "com.example.app",
    )

    private fun <T> emissions(
        states: List<TcptunRuntimeState>,
        selector: (TcptunRuntimeState) -> T,
    ): List<T> = runBlocking {
        states.asFlow().selectRuntimeState(selector).toList()
    }
}
