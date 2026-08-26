package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDesiredAppliedStateTest {
    @Test
    fun settingsUpdateDuringRecoveryGapIsRetainedWithoutRuntimeOwnership() {
        val state = RuntimeSettingsRuntimeState()

        val mutation = state.requestDesired(forceRestart = false)

        assertEquals(mutation, state.pending)
    }

    @Test
    fun multipleGapUpdatesCoalesceToLatest() {
        val state = RuntimeSettingsRuntimeState()
        val first = state.requestDesired(false)
        state.requestDesired(false)
        val latest = state.requestDesired(false)

        assertTrue(latest.sequence > first.sequence)
        assertEquals(latest, state.pending)
    }

    @Test
    fun forceRestartSurvivesGapCoalescing() {
        val state = RuntimeSettingsRuntimeState()
        state.requestDesired(true)

        val latest = state.requestDesired(false)

        assertTrue(latest.forceRestart)
    }

    @Test
    fun newRunningRuntimeDrainsPendingExactlyOnce() {
        val state = RuntimeSettingsRuntimeState()
        val mutation = state.requestDesired(false)
        val claim = requireNotNull(state.bindLatest(ownership(2, 20)))

        assertTrue(state.isLatest(claim))
        assertTrue(state.acknowledge(mutation.sequence))
        assertFalse(state.acknowledge(mutation.sequence))
        assertNull(state.pending)
    }

    @Test
    fun pendingBoundToRecoveryACannotPublishOverStartB() {
        val state = RuntimeSettingsRuntimeState()
        state.requestDesired(false)
        val recoveryA = requireNotNull(state.bindLatest(ownership(1, 10)))
        val startB = ownership(2, 20)
        val desired = applied(mtu = 1280)

        assertFalse(state.publishFreshRuntime(recoveryA.ownership, desired, activeOwnership = startB))
        val rebound = requireNotNull(state.bindLatest(startB))
        assertTrue(state.publishFreshRuntime(rebound.ownership, desired, startB))
    }

    @Test
    fun structuralPendingCausesAtMostOneReplacement() {
        val state = RuntimeSettingsRuntimeState()
        val mutation = state.requestDesired(false)
        val old = applied(mtu = 1400)
        val desired = applied(mtu = 1280)
        var replacements = 0

        if (desiredRuntimeSettingsAction(old, desired, false, false) ==
            RuntimeSettingsReconciliationAction.Replace
        ) replacements += 1
        val afterReplacement = desiredRuntimeSettingsAction(
            desired,
            desired,
            mutation.forceRestart,
            freshRuntimeSatisfiesForce = true,
        )
        if (afterReplacement == RuntimeSettingsReconciliationAction.Satisfied) {
            state.acknowledge(mutation.sequence)
        }

        assertEquals(1, replacements)
        assertNull(state.pending)
    }

    @Test
    fun hotPendingDoesNotReplaceRuntime() {
        val old = applied(logLevel = "info")
        val desired = applied(logLevel = "debug")

        assertEquals(
            RuntimeSettingsReconciliationAction.ApplyHot,
            desiredRuntimeSettingsAction(old, desired, false, false),
        )
    }

    @Test
    fun powerPolicyPendingReplacesRuntime() {
        val old = applied(powerSaving = true)
        val desired = applied(powerSaving = false)

        assertEquals(
            RuntimeSettingsReconciliationAction.Replace,
            desiredRuntimeSettingsAction(old, desired, false, false),
        )
    }

    @Test
    fun flowPendingPublishesLatestPackageOnNewEpoch() {
        val state = RuntimeSettingsRuntimeState()
        val epochTwo = ownership(1, 20)
        val old = applied(flowAnalysisApp = "")
        val desired = applied(flowAnalysisApp = "com.example.capture")

        assertEquals(
            RuntimeSettingsReconciliationAction.ApplyHot,
            desiredRuntimeSettingsAction(old, desired, false, false),
        )
        assertTrue(state.publishFreshRuntime(epochTwo, desired, epochTwo))
        assertEquals("com.example.capture", state.applied?.settings?.flowAnalysisApp)
        assertEquals(20L, state.applied?.ownership?.bridgeEpoch)
    }

    @Test
    fun stopClearsPendingRuntimeApplication() {
        val state = RuntimeSettingsRuntimeState()
        state.requestDesired(true)

        state.clearForStop()

        assertNull(state.pending)
        assertNull(state.bindLatest(ownership(2, 20)))
    }

    @Test
    fun destroyRejectsPendingDrainAndAppliedPublication() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(1, 10)
        state.requestDesired(false)
        state.clearForStop()

        assertNull(state.bindLatest(runtime))
        assertFalse(state.publishFreshRuntime(runtime, applied(), activeOwnership = null))
    }

    @Test
    fun startAPublicationIsRejectedAfterStartBClaimsOwnership() {
        assertStalePublicationRejected(ownership(1, 10), ownership(2, 10))
    }

    @Test
    fun recoveryAPublicationIsRejectedAfterStartBClaimsOwnership() {
        assertStalePublicationRejected(ownership(1, 20), ownership(2, 20))
    }

    @Test
    fun epochOneWriterIsRejectedAfterRecoveryCreatesEpochTwo() {
        assertStalePublicationRejected(ownership(1, 10), ownership(1, 20))
    }

    @Test
    fun stopRejectsLateAppliedRuntimePublication() {
        val state = RuntimeSettingsRuntimeState()
        val stale = ownership(1, 10)
        assertTrue(state.publishFreshRuntime(stale, applied(), stale))
        state.clearForStop()

        assertFalse(state.publishFreshRuntime(stale, applied(), activeOwnership = null))
        assertNull(state.applied)
    }

    private fun assertStalePublicationRejected(
        stale: VpnRuntimeOwnership,
        active: VpnRuntimeOwnership,
    ) {
        val state = RuntimeSettingsRuntimeState()
        val activeState = AppliedRuntimeState(active, applied(mtu = 1280))
        assertTrue(state.publishFreshRuntime(active, activeState.settings, active))

        assertFalse(state.publishFreshRuntime(stale, applied(), active))
        assertEquals(activeState, state.applied)
    }

    private fun applied(
        mtu: Int = 1400,
        logLevel: String = "info",
        powerSaving: Boolean = true,
        flowAnalysisApp: String = "",
    ) = AppliedRuntimeSettings(
        mtu = mtu,
        logLevel = logLevel,
        powerSavingMode = powerSaving,
        flowAnalysisApp = flowAnalysisApp,
    )

    private fun ownership(generation: Int, epoch: Long) = VpnRuntimeOwnership(
        VpnRuntimeCommandToken(1, generation, generation),
        epoch,
    )
}
