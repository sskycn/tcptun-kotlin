package com.tcptun.client

import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPlatformTeardownRuntimeTest {
    @Test
    fun `retained retries preserve production backoff`() {
        val harness = Harness(retryDelaysMillis = DefaultVpnPlatformTeardownRetryDelaysMillis)
        repeat(DefaultVpnPlatformTeardownRetryDelaysMillis.size) {
            harness.results += VpnPlatformStopResult.RetainedForRetry
        }

        harness.retain(stopOwner(0))
        DefaultVpnPlatformTeardownRetryDelaysMillis.indices.forEach(harness.scheduler::run)

        assertEquals(DefaultVpnPlatformTeardownRetryDelaysMillis, harness.scheduler.delays)
        assertTrue(harness.runtime.pending)
    }

    @Test
    fun `stop retained then released completes once`() {
        val harness = Harness()
        harness.results += VpnPlatformStopResult.Released

        harness.retain(stopOwner(1))
        harness.scheduler.run(0)
        harness.scheduler.run(0)

        assertEquals(listOf(stopOwner(1)), harness.completedOwners)
        assertEquals(1, harness.cleanupAttempts)
        assertFalse(harness.runtime.pending)
    }

    @Test
    fun `start rollback retained then released completes typed owner`() {
        val harness = Harness()
        val owner = startRollbackOwner(2)
        harness.results += VpnPlatformStopResult.Released

        harness.retain(owner)
        harness.scheduler.run(0)

        assertEquals(listOf(owner), harness.completedOwners)
    }

    @Test
    fun `recovery rollback waits through retained retry before fresh recovery completion`() {
        val harness = Harness()
        val owner = recoveryOwner(3, recoveryGeneration = 1)
        harness.results += VpnPlatformStopResult.RetainedForRetry
        harness.results += VpnPlatformStopResult.Released

        harness.retain(owner)
        harness.scheduler.run(0)
        assertTrue(harness.completedOwners.isEmpty())
        assertEquals(2, harness.scheduler.taskCount)

        harness.scheduler.run(1)
        assertEquals(listOf(owner), harness.completedOwners)
        assertEquals(1, harness.freshRecoverySchedules)
    }

    @Test
    fun `release before Future admission completes immediately`() {
        val harness = Harness()
        harness.scheduler.afterSchedule = { harness.resourcesOwned = false }
        val owner = stopOwner(4)

        harness.retain(owner)

        assertEquals(listOf(owner), harness.completedOwners)
        assertTrue(harness.scheduler.futures.single().isCancelled)
        assertEquals(0, harness.cleanupAttempts)
    }

    @Test
    fun `older attempt admission cannot cancel newer Future scheduled by inline callback`() {
        val harness = Harness()
        val owner = stopOwner(40)
        harness.results += VpnPlatformStopResult.RetainedForRetry
        harness.results += VpnPlatformStopResult.Released
        harness.scheduler.beforeScheduleReturn = { index ->
            if (index == 0) harness.scheduler.run(index)
        }

        harness.retain(owner)

        assertEquals(2, harness.scheduler.taskCount)
        assertTrue(harness.scheduler.futures[0].isCancelled)
        assertFalse(harness.scheduler.futures[1].isCancelled)
        assertTrue(harness.completedOwners.isEmpty())

        harness.scheduler.run(1)

        assertEquals(listOf(owner), harness.completedOwners)
        assertEquals(2, harness.cleanupAttempts)
        assertFalse(harness.runtime.pending)
    }

    @Test
    fun `duplicate retry callback only completes current owner once`() {
        val harness = Harness()
        val owner = stopOwner(5)
        harness.results += VpnPlatformStopResult.Released

        harness.retain(owner)
        repeat(3) { harness.scheduler.run(0) }

        assertEquals(listOf(owner), harness.completedOwners)
        assertEquals(1, harness.cleanupAttempts)
    }

    @Test
    fun `stale stop retry after newer start cleanup is rejected by generation`() {
        val harness = Harness()
        harness.results += VpnPlatformStopResult.Released
        harness.retain(stopOwner(6))
        harness.retain(startRollbackOwner(7))

        harness.scheduler.run(0)

        assertTrue(harness.completedOwners.isEmpty())
        assertEquals(0, harness.cleanupAttempts)
    }

    @Test
    fun `stale start rollback retry after newer stop is rejected by generation`() {
        val harness = Harness()
        harness.results += VpnPlatformStopResult.Released
        harness.retain(startRollbackOwner(8))
        harness.retain(stopOwner(9))

        harness.scheduler.run(0)

        assertTrue(harness.completedOwners.isEmpty())
        assertEquals(0, harness.cleanupAttempts)
    }

    @Test
    fun `stale recovery A retry after recovery B is rejected by generation`() {
        val harness = Harness()
        harness.results += VpnPlatformStopResult.Released
        harness.retain(recoveryOwner(10, recoveryGeneration = 1))
        harness.retain(recoveryOwner(10, recoveryGeneration = 2))

        harness.scheduler.run(0)

        assertTrue(harness.completedOwners.isEmpty())
        assertEquals(0, harness.freshRecoverySchedules)
    }

    @Test
    fun `retry scheduling rejection retains ownership and does not fake release`() {
        val harness = Harness(scheduleAccepted = false)
        val owner = stopOwner(11)

        harness.retain(owner)

        assertTrue(harness.runtime.pending)
        assertTrue(harness.completedOwners.isEmpty())
        assertTrue(harness.logs.any { "could not be scheduled" in it })
    }

    @Test
    fun `retry exhaustion keeps retained owner`() {
        val harness = Harness(retryDelaysMillis = listOf(0L, 0L))
        harness.results += VpnPlatformStopResult.RetainedForRetry
        harness.results += VpnPlatformStopResult.RetainedForRetry
        harness.retain(stopOwner(12))

        harness.scheduler.run(0)
        harness.scheduler.run(1)

        assertTrue(harness.runtime.pending)
        assertTrue(harness.completedOwners.isEmpty())
        assertTrue(harness.logs.any { "incomplete after 2 retries" in it })
    }

    @Test
    fun `destroy cancels pending retry and blocks callback`() {
        val harness = Harness()
        harness.results += VpnPlatformStopResult.Released
        harness.retain(stopOwner(13))

        harness.runtime.shutdown()
        harness.scheduler.run(0)

        assertTrue(harness.scheduler.futures.single().isCancelled)
        assertFalse(harness.runtime.pending)
        assertTrue(harness.completedOwners.isEmpty())
        assertEquals(0, harness.cleanupAttempts)
    }

    @Test
    fun `recovery retained never schedules fresh recovery early`() {
        val harness = Harness()
        harness.results += VpnPlatformStopResult.RetainedForRetry
        harness.retain(recoveryOwner(14, recoveryGeneration = 1))

        harness.scheduler.run(0)

        assertTrue(harness.runtime.pending)
        assertEquals(0, harness.freshRecoverySchedules)
        assertTrue(harness.completedOwners.isEmpty())
    }

    private class Harness(
        retryDelaysMillis: List<Long> = listOf(0L, 0L, 0L),
        scheduleAccepted: Boolean = true,
    ) {
        var resourcesOwned = true
        var destroyed = false
        var cleanupAttempts = 0
        var freshRecoverySchedules = 0
        val results = ArrayDeque<VpnPlatformStopResult>()
        val completedOwners = mutableListOf<VpnPlatformCleanupOwner>()
        val logs = mutableListOf<String>()
        val scheduler = ManualScheduler(scheduleAccepted)
        val runtime = VpnPlatformTeardownRuntime(
            retryDelaysMillis = retryDelaysMillis,
            performCleanup = {
                cleanupAttempts += 1
                results.removeFirst()
            },
            completeOwner = { owner, result ->
                assertEquals(VpnPlatformStopResult.Released, result)
                completedOwners += owner
                if (owner is VpnPlatformCleanupOwner.RecoveryRollback) {
                    freshRecoverySchedules += 1
                }
            },
            resourcesOwned = { resourcesOwned },
            scheduleRetry = scheduler::schedule,
            dispatchLifecycleRetry = { task -> task(); true },
            isDestroyed = { destroyed },
            log = logs::add,
        )

        fun retain(owner: VpnPlatformCleanupOwner) {
            runtime.acceptInitialResult(
                VpnPlatformTeardownRequest(cleanupOwner = owner),
                VpnPlatformStopResult.RetainedForRetry,
            )
        }
    }

    private class ManualScheduler(private val accepted: Boolean) {
        val tasks = mutableListOf<() -> Unit>()
        val futures = mutableListOf<FutureTask<Unit>>()
        val delays = mutableListOf<Long>()
        var afterSchedule: () -> Unit = {}
        var beforeScheduleReturn: (index: Int) -> Unit = {}
        val taskCount: Int get() = tasks.size

        fun schedule(delayMillis: Long, task: () -> Unit): FutureTask<Unit>? {
            assertTrue(delayMillis >= 0L)
            if (!accepted) return null
            tasks += task
            delays += delayMillis
            val future = FutureTask<Unit>({}, Unit)
            futures += future
            afterSchedule()
            beforeScheduleReturn(tasks.lastIndex)
            return future
        }

        fun run(index: Int) = tasks[index].invoke()
    }

    private companion object {
        fun token(generation: Int) = VpnRuntimeCommandToken(1L, generation, generation)

        fun stopOwner(generation: Int) = VpnPlatformCleanupOwner.Stop(token(generation))

        fun startRollbackOwner(generation: Int) =
            VpnPlatformCleanupOwner.StartRollback(token(generation))

        fun recoveryOwner(generation: Int, recoveryGeneration: Long) =
            VpnPlatformCleanupOwner.RecoveryRollback(
                token = VpnRuntimeRecoveryToken(token(generation), recoveryGeneration),
                request = VpnRuntimeRecoveryRequest(
                    plan = ProfileRunPlan(listOf(AppConfig(id = "A", name = "A"))),
                    reason = "test recovery",
                ),
                failure = IllegalStateException("test failure"),
            )
    }
}
