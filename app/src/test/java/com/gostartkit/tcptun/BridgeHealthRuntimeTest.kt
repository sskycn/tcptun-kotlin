package com.tcptun.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthRuntimeTest {
    @After
    fun resetState() {
        VpnHealthCheckRequests.clearRuntimeForces()
        TcptunState.setStatus(VpnStatus.Stopped)
    }

    @Test
    fun sameEpochStatusEventUsesReboundLifecycleOwner() {
        Harness().use { harness ->
            val rebound = ownership(generation = 2, epoch = 10)
            harness.activeOwnership = rebound

            harness.runtime.onStatusEvent(10, event(state = "error"))

            assertEquals(listOf(rebound), harness.restartRequests)
        }
    }

    @Test
    fun oldEpochStatusEventCannotRestartReplacementRuntime() {
        Harness().use { harness ->
            harness.activeOwnership = ownership(generation = 2, epoch = 20)

            harness.runtime.onStatusEvent(10, event(state = "stopped"))

            assertTrue(harness.restartRequests.isEmpty())
        }
    }

    @Test
    fun statusEventCannotRestartWithoutActiveRuntime() {
        Harness().use { harness ->
            harness.activeOwnership = null

            harness.runtime.onStatusEvent(10, event(state = "error"))

            assertTrue(harness.restartRequests.isEmpty())
        }
    }

    @Test
    fun statusEventCannotRestartWhileLifecycleReplacementIsInProgress() {
        Harness().use { harness ->
            harness.acceptStatusEvents = false

            harness.runtime.onStatusEvent(10, event(state = "error"))

            assertTrue(harness.restartRequests.isEmpty())
        }
    }

    @Test
    fun remoteEndpointEventDoesNotRequestLifecycleMutation() {
        Harness().use { harness ->
            harness.runtime.onStatusEvent(
                epoch = 10,
                event = event(
                    state = "remote_endpoints_changed",
                    reason = TcptunBridgeEvents.RemoteEndpointsChanged,
                ),
            )

            assertTrue(harness.restartRequests.isEmpty())
        }
    }

    @Test
    fun statusSnapshotIsRejectedWhenOwnershipChangesDuringBridgeCall() {
        val epoch = TcptunState.beginBridgeSession()
        TcptunState.setStatus(VpnStatus.Running)
        Harness(ownership(generation = 1, epoch = epoch)).use { harness ->
            harness.onStatusJson = {
                harness.activeOwnership = ownership(generation = 2, epoch = epoch)
            }

            harness.runtime.requestClientIpsRefresh()

            assertEquals(1, harness.statusJsonCalls)
            assertEquals(0, TcptunState.diagnostics.bridgeActiveConnections)
            assertTrue(TcptunState.diagnostics.bridgeClientIps.isEmpty())
        }
        TcptunState.endBridgeSession(epoch)
    }

    @Test
    fun currentStatusSnapshotPublishesForItsPhysicalEpoch() {
        val epoch = TcptunState.beginBridgeSession()
        TcptunState.setStatus(VpnStatus.Running)
        Harness(ownership(generation = 1, epoch = epoch)).use { harness ->
            harness.runtime.requestClientIpsRefresh()

            assertEquals(1, harness.statusJsonCalls)
            assertEquals(3, TcptunState.diagnostics.bridgeActiveConnections)
            assertEquals(listOf("10.0.0.2"), TcptunState.diagnostics.bridgeClientIps)
        }
        TcptunState.endBridgeSession(epoch)
    }

    private class Harness(
        initialOwnership: VpnRuntimeOwnership? = ownership(generation = 1, epoch = 10),
    ) : AutoCloseable {
        private val lifecycleExecutor = newLifecycleScheduledExecutor("BridgeHealthRuntimeTest")
        var activeOwnership = initialOwnership
        var acceptStatusEvents = true
        var statusJsonCalls = 0
        var onStatusJson: () -> Unit = {}
        val restartRequests = mutableListOf<VpnRuntimeOwnership>()
        val runtime = BridgeHealthRuntime(
            lifecycleExecutor = lifecycleExecutor,
            bridgePort = object : HealthBridgePort {
                override fun statusJson(ownership: VpnRuntimeOwnership): String {
                    statusJsonCalls += 1
                    onStatusJson()
                    return "status"
                }

                override fun outboundsStatusJson(ownership: VpnRuntimeOwnership): String = "{}"

                override fun probeOutboundHealth(
                    ownership: VpnRuntimeOwnership,
                    tag: String,
                    host: String,
                    port: Int,
                    timeoutMillis: Long,
                ): Long = error("unexpected health probe")
            },
            currentOwnership = { activeOwnership },
            isOwnershipCurrent = { it == activeOwnership },
            currentPlan = { null },
            currentSettings = { AppliedRuntimeSettings() },
            memberProbesAllowed = { true },
            canHandleStatusEvent = { acceptStatusEvents },
            restoreConnectionsReady = {},
            dispatchDiagnostics = { task ->
                task()
                true
            },
            onRestartRequired = { ownership, _, _ -> restartRequests += ownership },
            log = {},
            parseRuntimeSnapshot = { _, epoch ->
                BridgeRuntimeSnapshot(
                    epoch = epoch,
                    activeConnections = 3,
                    clientIps = listOf("10.0.0.2"),
                    muxSources = 0,
                    muxSessions = 0,
                    muxStreams = 0,
                )
            },
        )

        override fun close() {
            runtime.shutdown()
            lifecycleExecutor.shutdownNow()
        }
    }

    private companion object {
        fun ownership(generation: Int, epoch: Long) = VpnRuntimeOwnership(
            VpnRuntimeCommandToken(1, generation, generation),
            epoch,
        )

        fun event(state: String, reason: String = "") = BridgeStatusEvent(
            sessionId = 1,
            sequence = 1,
            state = state,
            reason = reason,
            phase = "",
            listen = "",
            remote = "",
            outboundTag = "",
            activeConnections = 0,
            clientIps = emptyList(),
            muxSources = 0,
            muxSessions = 0,
            muxStreams = 0,
            recoverable = true,
            lastError = "",
            timestampMs = 0,
        )
    }
}
