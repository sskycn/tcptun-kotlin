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
