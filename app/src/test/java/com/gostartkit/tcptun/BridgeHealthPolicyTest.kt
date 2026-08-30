package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthPolicyTest {
    @Test
    fun bridgeRecoveryBackoffIsExponentialAndCapped() {
        assertEquals(1_000L, BridgeHealthPolicy.bridgeRecoveryDelayMs(1))
        assertEquals(2_000L, BridgeHealthPolicy.bridgeRecoveryDelayMs(2))
        assertEquals(4_000L, BridgeHealthPolicy.bridgeRecoveryDelayMs(3))
        assertEquals(16_000L, BridgeHealthPolicy.bridgeRecoveryDelayMs(5))
        assertEquals(30_000L, BridgeHealthPolicy.bridgeRecoveryDelayMs(6))
        assertEquals(30_000L, BridgeHealthPolicy.bridgeRecoveryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun networkHandoverWaitsForAReplacementNetworkBeforeRestarting() {
        assertFalse(
            BridgeHealthPolicy.shouldRestartForNetworkHandover(
                initialSelection = true,
                networkAvailable = true,
                vpnRunning = true,
            ),
        )
        assertFalse(
            BridgeHealthPolicy.shouldRestartForNetworkHandover(
                initialSelection = false,
                networkAvailable = false,
                vpnRunning = true,
            ),
        )
        assertTrue(
            BridgeHealthPolicy.shouldRestartForNetworkHandover(
                initialSelection = false,
                networkAvailable = true,
                vpnRunning = true,
            ),
        )
    }

    @Test
    fun localProxyProbeAlsoRunsWhileUiInvisible() {
        assertTrue(BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible = true))
        assertTrue(BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible = false))
    }

    @Test
    fun upstreamProbeOnlyWhenUiVisibleAndForced() {
        assertTrue(
            BridgeHealthPolicy.shouldRunUpstreamProbe(uiVisible = true, force = true),
        )
        assertFalse(
            BridgeHealthPolicy.shouldRunUpstreamProbe(uiVisible = true, force = false),
        )
        assertFalse(
            BridgeHealthPolicy.shouldRunUpstreamProbe(uiVisible = false, force = true),
        )
        assertFalse(
            BridgeHealthPolicy.shouldRunUpstreamProbe(uiVisible = false, force = false),
        )
    }

    @Test
    fun statusJsonReconcileOnlyWhenUiVisibleAndForced() {
        assertTrue(BridgeHealthPolicy.shouldReconcileStatusJson(uiVisible = true, force = true))
        assertFalse(BridgeHealthPolicy.shouldReconcileStatusJson(uiVisible = true, force = false))
        assertFalse(BridgeHealthPolicy.shouldReconcileStatusJson(uiVisible = false, force = true))
    }

    @Test
    fun memberHealthProbeRunsOnlyWhenForcedAfterSettle() {
        val now = 1_000_000L
        assertTrue(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = true,
                nowMs = now,
            ),
        )
        assertFalse(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = false,
                nowMs = now,
            ),
        )
        assertFalse(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = true,
                nowMs = now,
                notBeforeMs = now + 1_000L,
            ),
        )
        assertTrue(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = true,
                nowMs = now + 1_000L,
                notBeforeMs = now + 1_000L,
            ),
        )
    }

    @Test
    fun transientMemberProbeFailuresAreRecognized() {
        assertTrue(BridgeHealthPolicy.isTransientMemberProbeFailure("probe: no route to host"))
        assertTrue(BridgeHealthPolicy.isTransientMemberProbeFailure("Network is unreachable"))
        assertFalse(BridgeHealthPolicy.isTransientMemberProbeFailure("connection refused"))
    }

    @Test
    fun safetyTimerOnlyChangesLocalCheckCadence() {
        assertEquals(
            BridgeHealthPolicy.LOCAL_LISTENER_SAFETY_INTERVAL_MS,
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = true,
                confirmingFailure = false,
            ),
        )
        assertEquals(
            BridgeHealthPolicy.LOCAL_LISTENER_SAFETY_INTERVAL_MS,
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = false,
                confirmingFailure = false,
            ),
        )
        assertEquals(
            BridgeHealthPolicy.FAILURE_CONFIRM_INTERVAL_MS,
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = true,
                confirmingFailure = true,
            ),
        )
        assertEquals(
            BridgeHealthPolicy.FAILURE_CONFIRM_INTERVAL_MS,
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = false,
                confirmingFailure = true,
            ),
        )
    }

    @Test
    fun structuralRuntimeChangeIncludesPowerSavingOnly() {
        val base = RuntimeSettings(powerSavingMode = false)
        val powerOnly = base.copy(powerSavingMode = true)
        val mtuChange = base.copy(mtu = 1280)
        assertTrue(BridgeHealthPolicy.isStructuralRuntimeChange(base, powerOnly))
        assertTrue(BridgeHealthPolicy.isStructuralRuntimeChange(base, mtuChange))
    }

    @Test
    fun structuralRuntimeChangeIncludesRouteLocalProxyTraffic() {
        val base = RuntimeSettings()
        val enabled = base.copy(routeLocalProxyTraffic = true)
        assertTrue(BridgeHealthPolicy.isStructuralRuntimeChange(base, enabled))
    }

    @Test
    fun structuralRuntimeChangeIncludesDefaultOutbound() {
        val base = RuntimeSettings()
        assertTrue(
            BridgeHealthPolicy.isStructuralRuntimeChange(
                base,
                base.copy(defaultOutbound = DefaultOutboundDirect),
            ),
        )
    }

    @Test
    fun forcedRuntimeRestartRebuildsNonSettingsConfiguration() {
        val settings = RuntimeSettings()
        assertTrue(
            BridgeHealthPolicy.requiresRuntimeRestart(
                forceRestart = true,
                previous = settings,
                next = settings,
            ),
        )
        assertFalse(
            BridgeHealthPolicy.requiresRuntimeRestart(
                forceRestart = false,
                previous = settings,
                next = settings,
            ),
        )
    }
}
