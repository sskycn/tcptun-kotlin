package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsPhysicalLifetimeTest {
    @Test
    fun replacementClaimDoesNotClearAppliedWhileOldPhysicalEpochStillRuns() {
        val state = RuntimeSettingsRuntimeState()
        val runningA = ownership(generation = 1, epoch = 10)
        val replacementB = ownership(generation = 2, epoch = 10)
        val baseline = applied(mtu = 1400)
        assertTrue(state.publishFreshRuntime(runningA, baseline, runningA))

        val activeAfterClaim = replacementB

        assertEquals(replacementB, activeAfterClaim)
        assertEquals(AppliedRuntimeState(runningA, baseline), state.applied)
    }

    @Test
    fun auxiliaryClaimRebindsStaleReplacementBaselineToCurrentOwner() {
        val state = RuntimeSettingsRuntimeState()
        val runningA = ownership(generation = 1, epoch = 10)
        val replacementB = ownership(generation = 2, epoch = 10)
        val auxiliaryC = ownership(generation = 3, epoch = 10)
        val baseline = applied(mtu = 1400)
        assertTrue(state.publishFreshRuntime(runningA, baseline, runningA))

        val activeAfterReplacementClaim = replacementB
        assertFalse(activeAfterReplacementClaim == state.applied?.ownership)
        assertTrue(state.rebindAppliedOwnership(auxiliaryC.runtimeToken, auxiliaryC))

        assertEquals(AppliedRuntimeState(auxiliaryC, baseline), state.applied)
    }

    @Test
    fun hotUncertaintySurvivesReplacementClaimAndFollowsAuxiliaryOwner() {
        val state = RuntimeSettingsRuntimeState()
        val runningA = ownership(generation = 1, epoch = 10)
        val replacementB = ownership(generation = 2, epoch = 10)
        val auxiliaryC = ownership(generation = 3, epoch = 10)
        val baseline = applied()
        assertTrue(state.publishFreshRuntime(runningA, baseline, runningA))
        state.requestDesired(false)
        assertEquals(
            HotAppliedCheckpointResult.AppliedToSource,
            state.markHotMutationUncertain(runningA, runningA),
        )

        val activeAfterReplacementClaim = replacementB
        assertFalse(activeAfterReplacementClaim == state.applied?.ownership)
        assertTrue(state.rebindAppliedOwnership(auxiliaryC.runtimeToken, auxiliaryC))
        val scheduled = mutableListOf<RuntimeSettingsApplyClaim>()
        state.reconcileFreshRuntime(auxiliaryC, baseline, null, auxiliaryC, scheduled::add)
        val claimC = scheduled.single()

        assertEquals(auxiliaryC, state.replacementRequiredFor)
        assertEquals(
            RuntimeSettingsReconciliationAction.Replace,
            state.reconciliationAction(claimC, baseline, freshRuntimeSatisfiesForce = false),
        )
    }

    @Test
    fun structuralPendingSurvivesSupersededReplacementAndTriggersOneNewReplacement() {
        val state = RuntimeSettingsRuntimeState()
        val runningA = ownership(generation = 1, epoch = 10)
        val replacementB = ownership(generation = 2, epoch = 10)
        val auxiliaryC = ownership(generation = 3, epoch = 10)
        assertTrue(state.publishFreshRuntime(runningA, applied(mtu = 1400), runningA))
        val pending = state.requestDesired(false)

        val activeAfterReplacementClaim = replacementB
        assertFalse(activeAfterReplacementClaim == state.applied?.ownership)
        assertTrue(state.rebindAppliedOwnership(auxiliaryC.runtimeToken, auxiliaryC))
        val scheduled = mutableListOf<RuntimeSettingsApplyClaim>()
        state.reconcileFreshRuntime(auxiliaryC, applied(mtu = 1280), null, auxiliaryC, scheduled::add)
        val claimC = scheduled.single()
        val replacements = reconcileAndCountReplacements(state, claimC, applied(mtu = 1280))

        assertEquals(1, replacements)
        assertEquals(pending, state.pending)
        assertEquals(1400, state.applied?.settings?.mtu)

        state.clearPhysicalRuntimeApplied()
        val replacementD = ownership(generation = 4, epoch = 20)
        val replacementSettings = applied(mtu = 1280)
        assertTrue(state.publishFreshRuntime(replacementD, replacementSettings, replacementD))
        state.reconcileFreshRuntime(
            replacementD,
            replacementSettings,
            pending.sequence,
            replacementD,
        ) { error("fresh replacement must satisfy structural pending") }

        assertNull(state.pending)
        assertEquals(1280, state.applied?.settings?.mtu)
    }

    @Test
    fun forceRestartSurvivesSupersededReplacementAndTriggersOneNewReplacement() {
        val state = RuntimeSettingsRuntimeState()
        val runningA = ownership(generation = 1, epoch = 10)
        val replacementB = ownership(generation = 2, epoch = 10)
        val auxiliaryC = ownership(generation = 3, epoch = 10)
        val baseline = applied()
        assertTrue(state.publishFreshRuntime(runningA, baseline, runningA))
        val pending = state.requestDesired(true)

        val activeAfterReplacementClaim = replacementB
        assertFalse(activeAfterReplacementClaim == state.applied?.ownership)
        assertTrue(state.rebindAppliedOwnership(auxiliaryC.runtimeToken, auxiliaryC))
        val scheduled = mutableListOf<RuntimeSettingsApplyClaim>()
        state.reconcileFreshRuntime(auxiliaryC, baseline, null, auxiliaryC, scheduled::add)
        val claimC = scheduled.single()
        val replacements = reconcileAndCountReplacements(state, claimC, baseline)

        assertEquals(1, replacements)
        assertTrue(state.pending?.forceRestart == true)
        assertEquals(pending, state.pending)
    }

    @Test
    fun physicalTeardownClearsStaleTokenAppliedWhenEpochBecomesUnavailable() {
        val state = RuntimeSettingsRuntimeState()
        val runningA = ownership(generation = 1, epoch = 10)
        val replacementB = ownership(generation = 2, epoch = 10)
        assertTrue(state.publishFreshRuntime(runningA, applied(), runningA))

        val activeBeforePhysicalStop: VpnRuntimeOwnership? = replacementB
        assertFalse(activeBeforePhysicalStop == state.applied?.ownership)
        state.clearPhysicalRuntimeApplied()
        val activeOwnership: VpnRuntimeOwnership? = null

        assertNull(activeOwnership)
        assertNull(state.applied)
    }

    @Test
    fun newEpochPublicationReplacesAppliedAndClearsOldUncertainty() {
        val state = RuntimeSettingsRuntimeState()
        val oldRuntime = ownership(generation = 1, epoch = 10)
        val newRuntime = ownership(generation = 2, epoch = 20)
        val newSettings = applied(mtu = 1280, logLevel = "debug")
        assertTrue(state.publishFreshRuntime(oldRuntime, applied(mtu = 1400), oldRuntime))
        state.markHotMutationUncertain(oldRuntime, oldRuntime)

        assertTrue(state.publishFreshRuntime(newRuntime, newSettings, newRuntime))

        assertEquals(AppliedRuntimeState(newRuntime, newSettings), state.applied)
        assertNull(state.replacementRequiredFor)
    }

    @Test
    fun terminalStopClearsAppliedPendingAndUncertainty() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        assertTrue(state.publishFreshRuntime(runtime, applied(), runtime))
        state.requestDesired(true)
        state.markHotMutationUncertain(runtime, runtime)

        state.clearForStop()

        assertNull(state.applied)
        assertNull(state.pending)
        assertNull(state.replacementRequiredFor)
    }

    @Test
    fun currentReplacementRequirementForcesReplaceWithoutAppliedBaseline() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        assertTrue(state.publishFreshRuntime(runtime, applied(), runtime))
        state.requestDesired(false)
        state.markHotMutationUncertain(runtime, runtime)
        state.clearPhysicalRuntimeApplied()
        val claim = requireNotNull(state.bindLatest(runtime))
        val scheduled = mutableListOf<RuntimeSettingsApplyClaim>()

        assertEquals(
            RuntimeSettingsReconciliationAction.Replace,
            state.reconciliationAction(claim, applied(), freshRuntimeSatisfiesForce = false),
        )
        state.reconcileFreshRuntime(runtime, applied(), null, runtime, scheduled::add)

        assertEquals(listOf(claim), scheduled)
    }

    @Test
    fun missingAppliedWithoutReplacementRequirementDoesNotInventHotState() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        state.requestDesired(false)
        val claim = requireNotNull(state.bindLatest(runtime))
        var hotMutations = 0
        var replacements = 0

        assertNull(state.reconciliationAction(claim, applied(), freshRuntimeSatisfiesForce = false))
        state.reconcileFreshRuntime(runtime, applied(), null, runtime) { replacements += 1 }
        state.reconcile(
            claim = claim,
            desired = applied(logLevel = "debug"),
            applyLogLevel = { hotMutations += 1 },
            applyFlowAnalysis = { hotMutations += 1 },
            checkpoint = { error("missing Applied must not checkpoint") },
            markMutationUncertain = { error("missing Applied must not become uncertain") },
            onApplied = { error("missing Applied must not publish") },
            onReplacementRequired = { replacements += 1 },
        )

        assertEquals(0, hotMutations)
        assertEquals(0, replacements)
        assertEquals(claim.mutation, state.pending)
    }

    private fun reconcileAndCountReplacements(
        state: RuntimeSettingsRuntimeState,
        claim: RuntimeSettingsApplyClaim,
        desired: AppliedRuntimeSettings,
    ): Int {
        var replacements = 0
        state.reconcile(
            claim = claim,
            desired = desired,
            applyLogLevel = { error("replacement reconciliation must not hot-apply log level") },
            applyFlowAnalysis = { error("replacement reconciliation must not hot-apply flow analysis") },
            checkpoint = { error("replacement reconciliation must not checkpoint") },
            markMutationUncertain = { error("replacement reconciliation must not mark uncertainty") },
            onApplied = { error("replacement reconciliation must not publish hot settings") },
            onReplacementRequired = { replacements += 1 },
        )
        return replacements
    }

    private fun applied(
        mtu: Int = 1400,
        logLevel: String = "info",
    ) = AppliedRuntimeSettings(mtu = mtu, logLevel = logLevel)

    private fun ownership(generation: Int, epoch: Long) = VpnRuntimeOwnership(
        VpnRuntimeCommandToken(
            serviceInstanceId = 1,
            lifecycleGeneration = generation,
            persistentGeneration = generation,
        ),
        epoch,
    )
}
