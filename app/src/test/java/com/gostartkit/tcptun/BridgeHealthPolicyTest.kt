package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthPolicyTest {
    @Test
    fun localProxyProbeOnlyWhileUiVisible() {
        assertTrue(BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible = true))
        assertFalse(BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible = false))
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
    fun healthChecksAreAlwaysEventDrivenExceptFailureConfirmation() {
        assertNull(
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = true,
                confirmingFailure = false,
            ),
        )
        assertNull(
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
    fun structuralRuntimeChangeIgnoresPowerSavingOnly() {
        val base = RuntimeSettings(powerSavingMode = false)
        val powerOnly = base.copy(powerSavingMode = true)
        val mtuChange = base.copy(mtu = 1280)
        assertFalse(BridgeHealthPolicy.isStructuralRuntimeChange(base, powerOnly))
        assertTrue(BridgeHealthPolicy.isStructuralRuntimeChange(base, mtuChange))
    }

    @Test
    fun structuralRuntimeChangeIncludesRouteLocalProxyTraffic() {
        val base = RuntimeSettings()
        val enabled = base.copy(routeLocalProxyTraffic = true)
        assertTrue(BridgeHealthPolicy.isStructuralRuntimeChange(base, enabled))
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
