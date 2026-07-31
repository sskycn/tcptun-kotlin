package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {
    @Test
    fun `vpn command payload uses an overflow-safe shared binder budget`() {
        assertTrue(
            isVpnCommandPayloadWithinLimit(
                configLength = MaxVpnCommandPayloadLength - 2,
                planLength = 1,
                settingsPayloadLength = 1,
            ),
        )
        assertFalse(
            isVpnCommandPayloadWithinLimit(
                configLength = MaxVpnCommandPayloadLength - 2,
                planLength = 2,
                settingsPayloadLength = 1,
            ),
        )
        assertFalse(isVpnCommandPayloadWithinLimit(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(isVpnCommandPayloadWithinLimit(-1, 0, 0))
    }

    @Test
    fun `owned bridge resources prohibit every terminal service action`() {
        val disposition = bridgeTeardownDisposition(hasOwnedResources = true)

        assertFalse(disposition.resourcesReleased)
        assertFalse(disposition.mayPublishStopped)
        assertFalse(disposition.mayRemoveForeground)
        assertFalse(disposition.mayStopService)
        assertTrue(disposition.shouldRetry)
    }

    @Test
    fun `released bridge resources permit terminal service actions`() {
        val disposition = bridgeTeardownDisposition(hasOwnedResources = false)

        assertTrue(disposition.resourcesReleased)
        assertTrue(disposition.mayPublishStopped)
        assertTrue(disposition.mayRemoveForeground)
        assertTrue(disposition.mayStopService)
        assertFalse(disposition.shouldRetry)
    }

    @Test
    fun `deferred stop is consumed only after resources release in the same generation`() {
        val gate = DeferredServiceStopGate()
        gate.defer(lifecycleGeneration = 7, persistentCommandGeneration = 3, startId = 41)

        assertNull(
            gate.consumeIfReleased(
                currentLifecycleGeneration = 7,
                currentPersistentCommandGeneration = 3,
                resourcesReleased = false,
                activeServiceOwner = true,
            ),
        )
        assertEquals(
            DeferredServiceStopRequest(
                lifecycleGeneration = 7,
                persistentCommandGeneration = 3,
                startId = 41,
            ),
            gate.consumeIfReleased(
                currentLifecycleGeneration = 7,
                currentPersistentCommandGeneration = 3,
                resourcesReleased = true,
                activeServiceOwner = true,
            ),
        )
    }

    @Test
    fun `replacement generation discards an old deferred stop`() {
        val gate = DeferredServiceStopGate()
        gate.defer(lifecycleGeneration = 7, persistentCommandGeneration = 3, startId = null)

        assertNull(
            gate.consumeIfReleased(
                currentLifecycleGeneration = 8,
                currentPersistentCommandGeneration = 3,
                resourcesReleased = true,
                activeServiceOwner = true,
            ),
        )
        assertNull(
            gate.consumeIfReleased(
                currentLifecycleGeneration = 7,
                currentPersistentCommandGeneration = 3,
                resourcesReleased = true,
                activeServiceOwner = true,
            ),
        )
    }

    @Test
    fun `new persistent command discards deferred stop before its lifecycle task starts`() {
        val gate = DeferredServiceStopGate()
        gate.defer(lifecycleGeneration = 7, persistentCommandGeneration = 3, startId = null)

        assertNull(
            gate.consumeIfReleased(
                currentLifecycleGeneration = 7,
                currentPersistentCommandGeneration = 4,
                resourcesReleased = true,
                activeServiceOwner = true,
            ),
        )
    }

    @Test
    fun `inactive service owner cannot consume a deferred stop`() {
        val gate = DeferredServiceStopGate()
        gate.defer(lifecycleGeneration = 7, persistentCommandGeneration = 3, startId = null)

        assertNull(
            gate.consumeIfReleased(
                currentLifecycleGeneration = 7,
                currentPersistentCommandGeneration = 3,
                resourcesReleased = true,
                activeServiceOwner = false,
            ),
        )
    }

    @Test
    fun `only a cold auxiliary command is rejected`() {
        assertTrue(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.Auxiliary,
                hasRuntimeResources = false,
                lifecycleWorkPending = false,
                bridgeRecoveryPending = false,
                teardownRetryPending = false,
                terminalStopPending = false,
            ),
        )
        assertFalse(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.Auxiliary,
                hasRuntimeResources = false,
                lifecycleWorkPending = true,
                bridgeRecoveryPending = false,
                teardownRetryPending = false,
                terminalStopPending = false,
            ),
        )
        assertFalse(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.Auxiliary,
                hasRuntimeResources = true,
                lifecycleWorkPending = false,
                bridgeRecoveryPending = false,
                teardownRetryPending = false,
                terminalStopPending = false,
            ),
        )
        assertFalse(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.StartOrRestore,
                hasRuntimeResources = false,
                lifecycleWorkPending = false,
                bridgeRecoveryPending = false,
                teardownRetryPending = false,
                terminalStopPending = false,
            ),
        )
    }

    @Test
    fun `delayed recovery and teardown retries keep auxiliary commands attached to service`() {
        assertFalse(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.Auxiliary,
                hasRuntimeResources = false,
                lifecycleWorkPending = false,
                bridgeRecoveryPending = true,
                teardownRetryPending = false,
                terminalStopPending = false,
            ),
        )
        assertFalse(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.Auxiliary,
                hasRuntimeResources = false,
                lifecycleWorkPending = false,
                bridgeRecoveryPending = false,
                teardownRetryPending = true,
                terminalStopPending = false,
            ),
        )
    }

    @Test
    fun `terminal stop rejects auxiliary work even while cleanup is pending`() {
        assertTrue(
            shouldRejectColdAuxiliaryCommand(
                ServiceCommandKind.Auxiliary,
                hasRuntimeResources = true,
                lifecycleWorkPending = true,
                bridgeRecoveryPending = false,
                teardownRetryPending = true,
                terminalStopPending = true,
            ),
        )
    }

    @Test
    fun `healthy authoritative snapshot restores actions only for current live runtime`() {
        val healthy = canRestoreConnectionsReady(
            runtimeStatus = "Running",
            bridgeStatus = "Running",
            bridgeEventState = "upstream_connected",
            localProxyReachable = true,
            sessionCurrent = true,
            hasTun = true,
            hasRunningPlan = true,
            stopping = false,
            bridgeRestarting = false,
            explicitStopRequested = false,
        )
        assertTrue(healthy)

        assertFalse(
            canRestoreConnectionsReady(
                runtimeStatus = "Running",
                bridgeStatus = "Running",
                bridgeEventState = "upstream_connected",
                localProxyReachable = false,
                sessionCurrent = true,
                hasTun = true,
                hasRunningPlan = true,
                stopping = false,
                bridgeRestarting = false,
                explicitStopRequested = false,
            ),
        )
        assertFalse(
            canRestoreConnectionsReady(
                runtimeStatus = "Running",
                bridgeStatus = "Running",
                bridgeEventState = "upstream_connected",
                localProxyReachable = true,
                sessionCurrent = false,
                hasTun = true,
                hasRunningPlan = true,
                stopping = false,
                bridgeRestarting = false,
                explicitStopRequested = false,
            ),
        )
        assertFalse(
            canRestoreConnectionsReady(
                runtimeStatus = "Running",
                bridgeStatus = "Running",
                bridgeEventState = "upstream_connected",
                localProxyReachable = true,
                sessionCurrent = true,
                hasTun = true,
                hasRunningPlan = true,
                stopping = false,
                bridgeRestarting = true,
                explicitStopRequested = false,
            ),
        )
    }

    @Test
    fun `transitional and degraded bridge events cannot restore actions`() {
        listOf("upstream_connecting", "reconnecting", "degraded", "outbound_error", "stopped", "")
            .forEach { eventState ->
                assertFalse(
                    canRestoreConnectionsReady(
                        runtimeStatus = "Running",
                        bridgeStatus = "Running",
                        bridgeEventState = eventState,
                        localProxyReachable = true,
                        sessionCurrent = true,
                        hasTun = true,
                        hasRunningPlan = true,
                        stopping = false,
                        bridgeRestarting = false,
                        explicitStopRequested = false,
                    ),
                )
            }
        listOf("core_ready", "running", "upstream_connected", "remote_endpoints_changed", "outbound_running")
            .forEach { eventState -> assertTrue(isExplicitlyHealthyBridgeEventState(eventState)) }
    }

    @Test
    fun `callback epoch invalidation rejects late delivery and cannot revoke replacement`() {
        val gate = CallbackEpochGate()
        val first = gate.activateNext()
        assertEquals("first", gate.runIfActive(first) { "first" })

        assertTrue(gate.invalidate(first))
        assertNull(gate.runIfActive(first) { "late" })

        val second = gate.activateNext()
        assertFalse(gate.invalidate(first))
        assertTrue(gate.isActive(second))
        assertEquals("second", gate.runIfActive(second) { "second" })
    }
}
