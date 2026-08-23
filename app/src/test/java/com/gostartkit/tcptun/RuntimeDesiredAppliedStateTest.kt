package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDesiredAppliedStateTest {
    @Test
    fun settingsUpdateDuringRecoveryGapIsRetainedWithoutRuntimeOwnership() {
        val gate = RuntimeSettingsDesiredGate()

        val mutation = gate.request(forceRestart = false)

        assertEquals(mutation, gate.pending)
    }

    @Test
    fun multipleGapUpdatesCoalesceToLatest() {
        val gate = RuntimeSettingsDesiredGate()
        val first = gate.request(false)
        gate.request(false)
        val latest = gate.request(false)

        assertTrue(latest.sequence > first.sequence)
        assertEquals(latest, gate.pending)
    }

    @Test
    fun forceRestartSurvivesGapCoalescing() {
        val gate = RuntimeSettingsDesiredGate()
        gate.request(true)

        val latest = gate.request(false)

        assertTrue(latest.forceRestart)
    }

    @Test
    fun newRunningRuntimeDrainsPendingExactlyOnce() {
        val gate = RuntimeSettingsDesiredGate()
        val mutation = gate.request(false)
        val claim = requireNotNull(gate.bindLatest(ownership(2, 20)))

        assertTrue(gate.isLatest(claim))
        assertTrue(gate.acknowledge(mutation.sequence))
        assertFalse(gate.acknowledge(mutation.sequence))
        assertNull(gate.pending)
    }

    @Test
    fun pendingBoundToRecoveryACannotPublishOverStartB() {
        val gate = RuntimeSettingsDesiredGate()
        val slot = AppliedRuntimeStateSlot()
        gate.request(false)
        val recoveryA = requireNotNull(gate.bindLatest(ownership(1, 10)))
        val startB = ownership(2, 20)
        val candidateA = AppliedRuntimeState(recoveryA.ownership, applied(mtu = 1280))

        assertFalse(slot.publish(candidateA, activeOwnership = startB))
        val rebound = requireNotNull(gate.bindLatest(startB))
        assertTrue(slot.publish(AppliedRuntimeState(rebound.ownership, candidateA.settings), startB))
    }

    @Test
    fun structuralPendingCausesAtMostOneReplacement() {
        val gate = RuntimeSettingsDesiredGate()
        val mutation = gate.request(false)
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
            gate.acknowledge(mutation.sequence)
        }

        assertEquals(1, replacements)
        assertNull(gate.pending)
    }

    @Test
    fun hotPendingDoesNotReplaceRuntime() {
        val old = applied(logLevel = "info", powerSaving = true)
        val desired = applied(logLevel = "debug", powerSaving = false)

        assertEquals(
            RuntimeSettingsReconciliationAction.ApplyHot,
            desiredRuntimeSettingsAction(old, desired, false, false),
        )
    }

    @Test
    fun flowPendingPublishesLatestPackageOnNewEpoch() {
        val slot = AppliedRuntimeStateSlot()
        val epochTwo = ownership(1, 20)
        val old = applied(flowAnalysisApp = "")
        val desired = applied(flowAnalysisApp = "com.example.capture")

        assertEquals(
            RuntimeSettingsReconciliationAction.ApplyHot,
            desiredRuntimeSettingsAction(old, desired, false, false),
        )
        assertTrue(slot.publish(AppliedRuntimeState(epochTwo, desired), epochTwo))
        assertEquals("com.example.capture", slot.current?.settings?.flowAnalysisApp)
        assertEquals(20L, slot.current?.ownership?.bridgeEpoch)
    }

    @Test
    fun stopClearsPendingRuntimeApplication() {
        val gate = RuntimeSettingsDesiredGate()
        gate.request(true)

        gate.clear()

        assertNull(gate.pending)
        assertNull(gate.bindLatest(ownership(2, 20)))
    }

    @Test
    fun destroyRejectsPendingDrainAndAppliedPublication() {
        val gate = RuntimeSettingsDesiredGate()
        val slot = AppliedRuntimeStateSlot()
        val runtime = ownership(1, 10)
        gate.request(false)
        gate.clear()

        assertNull(gate.bindLatest(runtime))
        assertFalse(slot.publish(AppliedRuntimeState(runtime, applied()), activeOwnership = null))
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
        val slot = AppliedRuntimeStateSlot()
        val stale = ownership(1, 10)
        assertTrue(slot.publish(AppliedRuntimeState(stale, applied()), stale))
        slot.clear()

        assertFalse(slot.publish(AppliedRuntimeState(stale, applied()), activeOwnership = null))
        assertNull(slot.current)
    }

    private fun assertStalePublicationRejected(
        stale: VpnRuntimeOwnership,
        active: VpnRuntimeOwnership,
    ) {
        val slot = AppliedRuntimeStateSlot()
        val activeState = AppliedRuntimeState(active, applied(mtu = 1280))
        assertTrue(slot.publish(activeState, active))

        assertFalse(slot.publish(AppliedRuntimeState(stale, applied()), active))
        assertEquals(activeState, slot.current)
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
