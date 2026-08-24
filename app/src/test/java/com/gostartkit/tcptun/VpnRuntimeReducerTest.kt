package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeReducerTest {
    @Test
    fun idleStopRemainsSafeAfterCompletion() {
        val stop = token(generation = 1, persistentGeneration = 0)
        val stopping = reduceRuntime(
            VpnRuntimeState(),
            VpnRuntimeEvent.StopRequested(stop, "idle stop"),
        ).state

        val stopped = reduceRuntime(
            stopping,
            VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.Stop(stop)),
        ).state

        assertTrue(stopped.phase is VpnRuntimePhase.Idle)
        assertTrue(stopped.explicitStopRequested)
    }

    @Test
    fun idleStartBecomesStartingAndSelectsNonBlockingEffect() {
        val token = token(1)
        val starting = reduceRuntime(
            VpnRuntimeState(),
            VpnRuntimeEvent.StartRequested(token),
        ).state
        val decision = reduceRuntime(
            starting,
            VpnRuntimeEvent.StartExecutionRequested(token, request("A"), false),
        )

        assertEquals(VpnRuntimePhase.Starting(token), decision.state.phase)
        assertEquals(
            VpnRuntimeEffect.StartRuntime(token, request("A"), replaceExisting = false),
            decision.effects.single(),
        )
    }

    @Test
    fun sameOwnerSuccessCommitsRunning() {
        val token = token(1)
        val plan = plan("A")
        val starting = start(VpnRuntimeState(), token)

        val running = reduceRuntime(
            starting,
            VpnRuntimeEvent.StartSucceeded(token, plan),
        ).state

        assertEquals(VpnRuntimePhase.Running(token), running.phase)
        assertSame(plan, running.runningPlan)
    }

    @Test
    fun runningStopBecomesStopping() {
        val start = token(1)
        val running = reduceRuntime(
            start(VpnRuntimeState(), start),
            VpnRuntimeEvent.StartSucceeded(start, plan("A")),
        ).state
        val stop = token(generation = 2, persistentGeneration = 1)

        val stopping = reduceRuntime(
            running,
            VpnRuntimeEvent.StopRequested(stop, "explicit stop"),
        ).state

        assertEquals(VpnRuntimePhase.Stopping(stop, "explicit stop"), stopping.phase)
        assertSame(running.runningPlan, stopping.runningPlan)
    }

    @Test
    fun startReplacementSupersedesStoppingOwner() {
        val stop = token(generation = 1, persistentGeneration = 0)
        val stopping = reduceRuntime(
            VpnRuntimeState(),
            VpnRuntimeEvent.StopRequested(stop, "old stop"),
        ).state
        val replacement = token(generation = 2, persistentGeneration = 1)

        val starting = reduceRuntime(
            stopping,
            VpnRuntimeEvent.StartRequested(replacement),
        ).state

        assertEquals(VpnRuntimePhase.Starting(replacement), starting.phase)
        assertTrue(!starting.explicitStopRequested)
    }

    @Test
    fun stopDuringCleanupClaimsNewAuthoritativeOwner() {
        val start = token(1)
        val cleanup = reduceRuntime(
            start(VpnRuntimeState(), start),
            VpnRuntimeEvent.StartFailed(start, request("A"), IllegalStateException("failed")),
        ).state
        val stop = token(generation = 2, persistentGeneration = 1)

        val stopping = reduceRuntime(
            cleanup,
            VpnRuntimeEvent.StopRequested(stop, "stop cleanup"),
        ).state

        assertEquals(VpnRuntimePhase.Stopping(stop, "stop cleanup"), stopping.phase)
        assertTrue(stopping.explicitStopRequested)
    }

    @Test
    fun replacementRejectsStaleStartSuccess() {
        val tokenA = token(1)
        val tokenB = token(2)
        val startingB = start(start(VpnRuntimeState(), tokenA), tokenB)

        val afterStaleA = reduceRuntime(
            startingB,
            VpnRuntimeEvent.StartSucceeded(tokenA, plan("A")),
        ).state

        assertEquals(VpnRuntimePhase.Starting(tokenB), afterStaleA.phase)
        assertEquals(null, afterStaleA.runningPlan)
    }

    @Test
    fun stopSupersedesPendingStartCompletion() {
        val start = token(1)
        val stop = token(generation = 2, persistentGeneration = 1)
        val stopping = reduceRuntime(
            start(VpnRuntimeState(), start),
            VpnRuntimeEvent.StopRequested(stop, "explicit stop"),
        ).state

        val afterLateStart = reduceRuntime(
            stopping,
            VpnRuntimeEvent.StartSucceeded(start, plan("A")),
        ).state

        assertEquals(VpnRuntimePhase.Stopping(stop, "explicit stop"), afterLateStart.phase)
        assertTrue(afterLateStart.explicitStopRequested)
    }

    @Test
    fun oldCleanupCannotOverwriteReplacement() {
        val tokenA = token(1)
        val failedA = reduceRuntime(
            start(VpnRuntimeState(), tokenA),
            VpnRuntimeEvent.StartFailed(tokenA, request("A"), IllegalStateException("failed")),
        ).state
        val tokenB = token(2)
        val startingB = start(failedA, tokenB)

        val afterOldCleanup = reduceRuntime(
            startingB,
            VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.StartRollback(tokenA)),
        ).state

        assertEquals(VpnRuntimePhase.Starting(tokenB), afterOldCleanup.phase)
    }

    @Test
    fun destroyRejectsAllFutureMutations() {
        val start = token(1)
        val destroyed = reduceRuntime(
            start(VpnRuntimeState(), start),
            VpnRuntimeEvent.Destroyed(token(2)),
        ).state

        val afterStart = reduceRuntime(
            destroyed,
            VpnRuntimeEvent.StartRequested(token(3)),
        ).state
        val afterStop = reduceRuntime(
            afterStart,
            VpnRuntimeEvent.StopRequested(token(4), "late stop"),
        ).state

        assertSame(destroyed, afterStart)
        assertSame(destroyed, afterStop)
    }

    @Test
    fun destroyDuringPendingStartStillSelectsSupersededPhysicalRollback() {
        val start = token(1)
        val destroyed = reduceRuntime(
            start(VpnRuntimeState(), start),
            VpnRuntimeEvent.Destroyed(token(generation = 2, persistentGeneration = 1)),
        ).state
        val failure = IllegalStateException("late JNI failure")

        val decision = reduceRuntime(
            destroyed,
            VpnRuntimeEvent.StartFailed(start, request("A"), failure),
        )

        assertSame(destroyed, decision.state)
        val rollback = decision.effects.single() as VpnRuntimeEffect.RollbackStart
        assertTrue(rollback.superseded)
        assertSame(failure, rollback.error)
    }

    @Test
    fun duplicateCompletionIsIdempotent() {
        val token = token(1)
        val owner = VpnRuntimeCleanupOwner.StartRollback(token)
        val cleanup = reduceRuntime(
            start(VpnRuntimeState(), token),
            VpnRuntimeEvent.StartFailed(token, request("A"), IllegalStateException("failed")),
        ).state
        val released = reduceRuntime(cleanup, VpnRuntimeEvent.CleanupReleased(owner)).state

        val duplicate = reduceRuntime(released, VpnRuntimeEvent.CleanupReleased(owner)).state

        assertSame(released, duplicate)
    }

    private fun start(state: VpnRuntimeState, token: VpnRuntimeCommandToken) =
        reduceRuntime(state, VpnRuntimeEvent.StartRequested(token)).state

    private fun token(
        generation: Int,
        persistentGeneration: Int = generation,
    ) = VpnRuntimeCommandToken(7L, generation, persistentGeneration)

    private fun plan(id: String) = ProfileRunPlan(listOf(AppConfig(id = id, name = id)))

    private fun request(id: String) = VpnRuntimeStartRequest(
        command = VpnStartCommand(
            configJson = "{}",
            plan = plan(id),
            runtimeSettings = RuntimeSettings(),
            desiredPlanJson = "{}",
        ),
        expectedProfileMutationRevision = 1L,
    )
}
