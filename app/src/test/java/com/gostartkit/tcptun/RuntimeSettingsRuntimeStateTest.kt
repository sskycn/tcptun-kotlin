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
        state.clearApplied()
        assertTrue(state.publishFreshRuntime(replacement, applied(logLevel = "debug"), replacement))
        assertFalse(
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

        assertTrue(state.rebindAppliedOwnership(auxiliaryToken, active))
        assertEquals(AppliedRuntimeState(active, settings), state.applied)
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
        assertFalse(
            state.checkpointHotApplied(oldRuntime, replacement) { it.copy(logLevel = "warn") },
        )
        assertEquals("debug", state.applied?.settings?.logLevel)
        assertTrue(state.pending != null)
    }

    private fun applied(
        logLevel: String = "info",
        flowAnalysisApp: String = "",
    ) = AppliedRuntimeSettings(
        logLevel = logLevel,
        flowAnalysisApp = flowAnalysisApp,
    )

    private fun token(generation: Int) = VpnRuntimeCommandToken(
        serviceInstanceId = 1,
        lifecycleGeneration = generation,
        persistentGeneration = generation,
    )

    private fun ownership(generation: Int, epoch: Long) =
        VpnRuntimeOwnership(token(generation), epoch)
}
