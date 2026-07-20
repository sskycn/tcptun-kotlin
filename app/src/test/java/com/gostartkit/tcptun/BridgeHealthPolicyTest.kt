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
    fun memberHealthProbeRunsWhenForcedRegardlessOfInterval() {
        val now = 1_000_000L
        assertTrue(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = true,
                lastProbeAtMs = now - 1_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun memberHealthProbeBlockedUntilSettleWindowElapses() {
        val now = 50_000L
        assertFalse(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = true,
                lastProbeAtMs = 0L,
                nowMs = now,
                notBeforeMs = now + 1_000L,
            ),
        )
        assertTrue(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = true,
                lastProbeAtMs = 0L,
                nowMs = now + 1_000L,
                notBeforeMs = now + 1_000L,
            ),
        )
    }

    @Test
    fun memberHealthProbeRunsOnFirstCheckWithoutForce() {
        assertTrue(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = false,
                lastProbeAtMs = 0L,
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun memberHealthProbeRespectsMinimumIntervalWithoutForce() {
        val last = 100_000L
        val tooSoon = last + BridgeHealthPolicy.MEMBER_HEALTH_MIN_INTERVAL_MS - 1
        val ready = last + BridgeHealthPolicy.MEMBER_HEALTH_MIN_INTERVAL_MS
        assertFalse(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = false,
                lastProbeAtMs = last,
                nowMs = tooSoon,
            ),
        )
        assertTrue(
            BridgeHealthPolicy.shouldProbeMemberHealth(
                force = false,
                lastProbeAtMs = last,
                nowMs = ready,
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
    fun powerSavingHasNoRoutineTimer() {
        assertNull(
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = true,
                confirmingFailure = false,
            ),
        )
    }

    @Test
    fun disablingPowerSavingEnablesSafetyChecks() {
        assertEquals(
            BridgeHealthPolicy.SAFETY_INTERVAL_MS,
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = false,
                confirmingFailure = false,
            ),
        )
    }

    @Test
    fun failureConfirmationOverridesPowerSaving() {
        assertEquals(
            BridgeHealthPolicy.FAILURE_CONFIRM_INTERVAL_MS,
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = true,
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
