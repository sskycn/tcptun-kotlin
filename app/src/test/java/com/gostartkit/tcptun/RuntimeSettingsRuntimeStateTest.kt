package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsRuntimeStateTest {
    @Test
    fun staleLogApplyCheckpointsActualBeforeLatestDesiredRestoresNative() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        assertTrue(state.publishFreshRuntime(runtime, applied(logLevel = "info"), runtime))
        state.requestDesired(false)
        val requestA = requireNotNull(state.bindLatest(runtime))
        var nativeLogLevel = "info"

        val resultA = state.applyHot(
            claim = requestA,
            desired = applied(logLevel = "debug"),
            applyLogLevel = { level ->
                nativeLogLevel = level
                state.requestDesired(false)
            },
            applyFlowAnalysis = { },
            checkpoint = { transform -> state.checkpointHotApplied(runtime, runtime, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(runtime, runtime) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, resultA)
        assertEquals("debug", nativeLogLevel)
        assertEquals("debug", state.applied?.settings?.logLevel)
        val requestB = requireNotNull(state.bindLatest(runtime))
        val resultB = state.applyHot(
            claim = requestB,
            desired = applied(logLevel = "info"),
            applyLogLevel = { level -> nativeLogLevel = level },
            applyFlowAnalysis = { },
            checkpoint = { transform -> state.checkpointHotApplied(runtime, runtime, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(runtime, runtime) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Applied, resultB)
        assertEquals("info", nativeLogLevel)
        assertEquals("info", state.applied?.settings?.logLevel)
        assertNull(state.pending)
    }

    @Test
    fun staleFlowApplyCheckpointsAppBeforeLatestDesiredClearsNative() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        assertTrue(state.publishFreshRuntime(runtime, applied(flowAnalysisApp = ""), runtime))
        state.requestDesired(false)
        val requestA = requireNotNull(state.bindLatest(runtime))
        var nativeFlowApp = ""

        val resultA = state.applyHot(
            claim = requestA,
            desired = applied(flowAnalysisApp = "com.example.appa"),
            applyLogLevel = { },
            applyFlowAnalysis = { packageName ->
                nativeFlowApp = packageName
                state.requestDesired(false)
            },
            checkpoint = { transform -> state.checkpointHotApplied(runtime, runtime, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(runtime, runtime) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, resultA)
        assertEquals("com.example.appa", nativeFlowApp)
        assertEquals("com.example.appa", state.applied?.settings?.flowAnalysisApp)
        val requestB = requireNotNull(state.bindLatest(runtime))
        val resultB = state.applyHot(
            claim = requestB,
            desired = applied(flowAnalysisApp = ""),
            applyLogLevel = { },
            applyFlowAnalysis = { packageName -> nativeFlowApp = packageName },
            checkpoint = { transform -> state.checkpointHotApplied(runtime, runtime, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(runtime, runtime) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Applied, resultB)
        assertEquals("", nativeFlowApp)
        assertEquals("", state.applied?.settings?.flowAnalysisApp)
    }

    @Test
    fun logCheckpointFromStartAIsRejectedAfterStartBPublishes() {
        val state = RuntimeSettingsRuntimeState()
        val startA = ownership(generation = 1, epoch = 10)
        val startB = ownership(generation = 2, epoch = 20)
        assertTrue(state.publishFreshRuntime(startA, applied(logLevel = "info"), startA))
        state.requestDesired(false)
        val requestA = requireNotNull(state.bindLatest(startA))
        var active = startA

        val result = state.applyHot(
            claim = requestA,
            desired = applied(logLevel = "debug"),
            applyLogLevel = {
                active = startB
                state.clearApplied()
                assertTrue(state.publishFreshRuntime(startB, applied(logLevel = "warn"), startB))
            },
            applyFlowAnalysis = { },
            checkpoint = { transform -> state.checkpointHotApplied(startA, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(startA, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, result)
        assertEquals(startB, state.applied?.ownership)
        assertEquals("warn", state.applied?.settings?.logLevel)
    }

    @Test
    fun flowCheckpointFromOldEpochIsRejectedAfterRecoveryPublishesNewEpoch() {
        val state = RuntimeSettingsRuntimeState()
        val epochOne = ownership(generation = 1, epoch = 10)
        val epochTwo = ownership(generation = 1, epoch = 20)
        assertTrue(state.publishFreshRuntime(epochOne, applied(), epochOne))
        state.requestDesired(false)
        val request = requireNotNull(state.bindLatest(epochOne))
        var active = epochOne

        val result = state.applyHot(
            claim = request,
            desired = applied(flowAnalysisApp = "com.example.capture"),
            applyLogLevel = { },
            applyFlowAnalysis = {
                active = epochTwo
                state.clearApplied()
                assertTrue(state.publishFreshRuntime(epochTwo, applied(flowAnalysisApp = "new"), epochTwo))
            },
            checkpoint = { transform -> state.checkpointHotApplied(epochOne, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(epochOne, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, result)
        assertEquals(epochTwo, state.applied?.ownership)
        assertEquals("new", state.applied?.settings?.flowAnalysisApp)
    }

    @Test
    fun partialHotFailureKeepsSuccessfulCheckpointUntilReplacement() {
        val state = RuntimeSettingsRuntimeState()
        val oldRuntime = ownership(generation = 1, epoch = 10)
        val replacement = ownership(generation = 2, epoch = 20)
        assertTrue(state.publishFreshRuntime(oldRuntime, applied(), oldRuntime))
        state.requestDesired(false)
        val request = requireNotNull(state.bindLatest(oldRuntime))
        val flowFailure = IllegalStateException("flow unavailable")
        var nativeLogLevel = "info"

        val result = state.applyHot(
            claim = request,
            desired = applied(logLevel = "debug", flowAnalysisApp = "com.example.capture"),
            applyLogLevel = { nativeLogLevel = it },
            applyFlowAnalysis = { throw flowFailure },
            checkpoint = { transform -> state.checkpointHotApplied(oldRuntime, oldRuntime, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(oldRuntime, oldRuntime) },
        )

        assertEquals(
            RuntimeSettingsHotApplyResult.RestartRequired(
                RuntimeSettingsHotMutation.FlowAnalysis,
                flowFailure,
            ),
            result,
        )
        assertEquals("debug", nativeLogLevel)
        assertEquals("debug", state.applied?.settings?.logLevel)
        assertEquals("", state.applied?.settings?.flowAnalysisApp)
        assertEquals(oldRuntime, state.replacementRequiredFor)
        state.clearApplied()
        assertTrue(state.publishFreshRuntime(replacement, applied(logLevel = "debug"), replacement))
        assertNull(state.replacementRequiredFor)
        assertEquals(
            HotAppliedCheckpointResult.RejectedDifferentRuntime,
            state.checkpointHotApplied(oldRuntime, replacement) {
                it.copy(flowAnalysisApp = "com.example.capture")
            },
        )
        assertEquals(replacement, state.applied?.ownership)
    }

    @Test
    fun auxiliaryClaimDuringEpochZeroGapClearsAppliedWithoutConstructingInvalidOwnership() {
        val state = RuntimeSettingsRuntimeState()
        val previous = ownership(generation = 1, epoch = 10)
        val auxiliaryToken = token(generation = 2)
        assertTrue(state.publishFreshRuntime(previous, applied(), previous))

        assertFalse(state.rebindAppliedOwnership(auxiliaryToken, activeOwnership = null))
        assertNull(state.applied)
    }

    @Test
    fun auxiliaryClaimRebindsSameSettingsWhileEpochRemainsActive() {
        val state = RuntimeSettingsRuntimeState()
        val previous = ownership(generation = 1, epoch = 10)
        val auxiliaryToken = token(generation = 2)
        val active = VpnRuntimeOwnership(auxiliaryToken, bridgeEpoch = 10)
        val settings = applied(logLevel = "debug")
        assertTrue(state.publishFreshRuntime(previous, settings, previous))
        assertEquals(
            HotAppliedCheckpointResult.AppliedToSource,
            state.markHotMutationUncertain(previous, previous),
        )

        assertTrue(state.rebindAppliedOwnership(auxiliaryToken, active))
        assertEquals(AppliedRuntimeState(active, settings), state.applied)
        assertEquals(active, state.replacementRequiredFor)
    }

    @Test
    fun auxiliaryClaimClearsAppliedWhenNativeEpochDoesNotMatch() {
        val state = RuntimeSettingsRuntimeState()
        val previous = ownership(generation = 1, epoch = 10)
        val auxiliaryToken = token(generation = 2)
        val activeEpochTwo = VpnRuntimeOwnership(auxiliaryToken, bridgeEpoch = 20)
        assertTrue(state.publishFreshRuntime(previous, applied(), previous))

        assertFalse(state.rebindAppliedOwnership(auxiliaryToken, activeEpochTwo))
        assertNull(state.applied)
    }

    @Test
    fun recoveryGapAuxiliaryReplacementRejectsOldRuntimePublication() {
        val state = RuntimeSettingsRuntimeState()
        val oldRuntime = ownership(generation = 1, epoch = 10)
        val auxiliaryToken = token(generation = 2)
        val replacement = ownership(generation = 3, epoch = 20)
        assertTrue(state.publishFreshRuntime(oldRuntime, applied(logLevel = "info"), oldRuntime))
        state.requestDesired(false)

        assertFalse(state.rebindAppliedOwnership(auxiliaryToken, activeOwnership = null))
        assertTrue(state.publishFreshRuntime(replacement, applied(logLevel = "debug"), replacement))
        assertFalse(state.publishFreshRuntime(oldRuntime, applied(logLevel = "warn"), replacement))
        assertEquals(
            HotAppliedCheckpointResult.RejectedDifferentRuntime,
            state.checkpointHotApplied(oldRuntime, replacement) { it.copy(logLevel = "warn") },
        )
        assertEquals("debug", state.applied?.settings?.logLevel)
        assertTrue(state.pending != null)
    }

    @Test
    fun sameEpochLogCheckpointTransfersToAuxiliaryOwnerAndLatestDesiredRestoresNative() {
        val state = RuntimeSettingsRuntimeState()
        val source = ownership(generation = 1, epoch = 10)
        var active = source
        assertTrue(state.publishFreshRuntime(source, applied(), source))
        state.requestDesired(false)
        val requestA = requireNotNull(state.bindLatest(source))
        val nativeLogLevels = mutableListOf<String>()
        var flowCalls = 0

        val resultA = state.applyHot(
            claim = requestA,
            desired = applied(logLevel = "debug", flowAnalysisApp = "com.example.capture"),
            applyLogLevel = { level ->
                nativeLogLevels += level
                val auxiliaryToken = token(generation = 2)
                active = VpnRuntimeOwnership(auxiliaryToken, bridgeEpoch = 10)
                assertTrue(state.rebindAppliedOwnership(auxiliaryToken, active))
                state.requestDesired(false)
            },
            applyFlowAnalysis = { flowCalls += 1 },
            checkpoint = { transform -> state.checkpointHotApplied(source, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(source, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, resultA)
        assertEquals(AppliedRuntimeState(active, applied(logLevel = "debug")), state.applied)
        assertEquals(0, flowCalls)
        val requestB = requireNotNull(state.bindLatest(active))
        val resultB = state.applyHot(
            claim = requestB,
            desired = applied(logLevel = "info"),
            applyLogLevel = { nativeLogLevels += it },
            applyFlowAnalysis = { flowCalls += 1 },
            checkpoint = { transform -> state.checkpointHotApplied(active, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(active, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Applied, resultB)
        assertEquals(listOf("debug", "info"), nativeLogLevels)
        assertEquals(applied(logLevel = "info"), state.applied?.settings)
    }

    @Test
    fun sameEpochFlowCheckpointTransfersToAuxiliaryOwnerAndLatestDesiredClearsNative() {
        val state = RuntimeSettingsRuntimeState()
        val source = ownership(generation = 1, epoch = 10)
        var active = source
        assertTrue(state.publishFreshRuntime(source, applied(), source))
        state.requestDesired(false)
        val requestA = requireNotNull(state.bindLatest(source))
        val nativeFlowApps = mutableListOf<String>()

        val resultA = state.applyHot(
            claim = requestA,
            desired = applied(flowAnalysisApp = "com.example.appa"),
            applyLogLevel = { },
            applyFlowAnalysis = { packageName ->
                nativeFlowApps += packageName
                val auxiliaryToken = token(generation = 2)
                active = VpnRuntimeOwnership(auxiliaryToken, bridgeEpoch = 10)
                assertTrue(state.rebindAppliedOwnership(auxiliaryToken, active))
                state.requestDesired(false)
            },
            checkpoint = { transform -> state.checkpointHotApplied(source, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(source, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, resultA)
        assertEquals("com.example.appa", state.applied?.settings?.flowAnalysisApp)
        val requestB = requireNotNull(state.bindLatest(active))
        val resultB = state.applyHot(
            claim = requestB,
            desired = applied(flowAnalysisApp = ""),
            applyLogLevel = { },
            applyFlowAnalysis = { nativeFlowApps += it },
            checkpoint = { transform -> state.checkpointHotApplied(active, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(active, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Applied, resultB)
        assertEquals(listOf("com.example.appa", ""), nativeFlowApps)
        assertEquals("", state.applied?.settings?.flowAnalysisApp)
    }

    @Test
    fun hotCheckpointAfterStopCannotResurrectAppliedState() {
        val state = RuntimeSettingsRuntimeState()
        val source = ownership(generation = 1, epoch = 10)
        var active: VpnRuntimeOwnership? = source
        assertTrue(state.publishFreshRuntime(source, applied(), source))
        state.requestDesired(false)
        val request = requireNotNull(state.bindLatest(source))

        val result = state.applyHot(
            claim = request,
            desired = applied(logLevel = "debug"),
            applyLogLevel = {
                state.clearForStop()
                active = null
            },
            applyFlowAnalysis = { },
            checkpoint = { transform -> state.checkpointHotApplied(source, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(source, active) },
        )

        assertSame(RuntimeSettingsHotApplyResult.Superseded, result)
        assertNull(state.applied)
        assertNull(state.replacementRequiredFor)
    }

    @Test
    fun sameEpochCheckpointRejectsDifferentServiceInstance() {
        val state = RuntimeSettingsRuntimeState()
        val source = ownership(generation = 1, epoch = 10)
        val otherService = ownership(generation = 2, epoch = 10, serviceInstanceId = 2)
        assertTrue(state.publishFreshRuntime(otherService, applied(), otherService))

        assertEquals(
            HotAppliedCheckpointResult.RejectedDifferentRuntime,
            state.checkpointHotApplied(source, otherService) { it.copy(logLevel = "debug") },
        )
        assertEquals("info", state.applied?.settings?.logLevel)
    }

    @Test
    fun failedMutationRequirementFollowsSameEpochAuxiliaryOwnerAndForcesReplacement() {
        val state = RuntimeSettingsRuntimeState()
        val source = ownership(generation = 1, epoch = 10)
        var active = source
        assertTrue(state.publishFreshRuntime(source, applied(), source))
        state.requestDesired(false)
        val requestA = requireNotNull(state.bindLatest(source))

        val result = state.applyHot(
            claim = requestA,
            desired = applied(flowAnalysisApp = "com.example.capture"),
            applyLogLevel = { },
            applyFlowAnalysis = {
                val auxiliaryToken = token(generation = 2)
                active = VpnRuntimeOwnership(auxiliaryToken, bridgeEpoch = 10)
                assertTrue(state.rebindAppliedOwnership(auxiliaryToken, active))
                throw IllegalStateException("partially applied")
            },
            checkpoint = { transform -> state.checkpointHotApplied(source, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(source, active) },
        )

        assertTrue(result is RuntimeSettingsHotApplyResult.RestartRequired)
        assertEquals(active, state.replacementRequiredFor)
        val requestB = requireNotNull(state.bindLatest(active))
        assertEquals(
            RuntimeSettingsReconciliationAction.Replace,
            state.reconciliationAction(requestB, desired = applied(), freshRuntimeSatisfiesForce = false),
        )
    }

    @Test
    fun stopClearsFailedMutationRequirement() {
        val state = RuntimeSettingsRuntimeState()
        val runtime = ownership(generation = 1, epoch = 10)
        var active: VpnRuntimeOwnership? = runtime
        assertTrue(state.publishFreshRuntime(runtime, applied(), runtime))
        state.requestDesired(false)
        val request = requireNotNull(state.bindLatest(runtime))

        val result = state.applyHot(
            claim = request,
            desired = applied(flowAnalysisApp = "com.example.capture"),
            applyLogLevel = { },
            applyFlowAnalysis = {
                state.clearForStop()
                active = null
                throw IllegalStateException("partially applied before stop")
            },
            checkpoint = { transform -> state.checkpointHotApplied(runtime, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(runtime, active) },
        )

        assertTrue(result is RuntimeSettingsHotApplyResult.RestartRequired)
        assertNull(state.replacementRequiredFor)
        assertNull(state.applied)
    }

    @Test
    fun oldEpochFailureCannotMarkAlreadyPublishedNewEpochUncertain() {
        val state = RuntimeSettingsRuntimeState()
        val oldRuntime = ownership(generation = 1, epoch = 10)
        val newRuntime = ownership(generation = 2, epoch = 20)
        var active = oldRuntime
        assertTrue(state.publishFreshRuntime(oldRuntime, applied(), oldRuntime))
        state.requestDesired(false)
        val request = requireNotNull(state.bindLatest(oldRuntime))

        val result = state.applyHot(
            claim = request,
            desired = applied(flowAnalysisApp = "com.example.capture"),
            applyLogLevel = { },
            applyFlowAnalysis = {
                active = newRuntime
                state.clearApplied()
                assertTrue(state.publishFreshRuntime(newRuntime, applied(logLevel = "debug"), newRuntime))
                throw IllegalStateException("old epoch completed late")
            },
            checkpoint = { transform -> state.checkpointHotApplied(oldRuntime, active, transform) },
            markMutationUncertain = { state.markHotMutationUncertain(oldRuntime, active) },
        )

        assertTrue(result is RuntimeSettingsHotApplyResult.RestartRequired)
        assertNull(state.replacementRequiredFor)
        assertEquals(newRuntime, state.applied?.ownership)
    }

    private fun applied(
        logLevel: String = "info",
        flowAnalysisApp: String = "",
    ) = AppliedRuntimeSettings(
        logLevel = logLevel,
        flowAnalysisApp = flowAnalysisApp,
    )

    private fun token(generation: Int, serviceInstanceId: Long = 1) = VpnRuntimeCommandToken(
        serviceInstanceId = serviceInstanceId,
        lifecycleGeneration = generation,
        persistentGeneration = generation,
    )

    private fun ownership(generation: Int, epoch: Long, serviceInstanceId: Long = 1) =
        VpnRuntimeOwnership(token(generation, serviceInstanceId), epoch)
}
