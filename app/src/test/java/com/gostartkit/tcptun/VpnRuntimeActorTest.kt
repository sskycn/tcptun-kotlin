package com.tcptun.client

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeActorTest {
    @Test
    fun concurrentIngressMutatesStateOnOneActorWriter() {
        val actor = VpnRuntimeActor()
        val callers = Executors.newFixedThreadPool(4)
        val writerThreads = Collections.synchronizedSet(mutableSetOf<String>())
        val ready = CountDownLatch(4)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(12)
        try {
            repeat(12) {
                callers.execute {
                    ready.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    actor.compatibilityMutation { state ->
                        writerThreads += Thread.currentThread().name
                        state
                    }
                    finished.countDown()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))

            assertEquals(setOf(actor.actorThreadName()), writerThreads)
        } finally {
            callers.shutdownNow()
            actor.shutdown()
        }
    }

    @Test
    fun orderedMailboxMakesStopAuthoritativeOverLateStartSuccess() {
        val actor = VpnRuntimeActor()
        try {
            val start = token(1)
            val stop = token(generation = 2, persistentGeneration = 1)
            actor.send(VpnRuntimeEvent.StartRequested(start))
            actor.send(VpnRuntimeEvent.StopRequested(stop, "explicit stop"))
            actor.send(VpnRuntimeEvent.StartSucceeded(start, plan("A")))

            assertEquals(VpnRuntimePhase.Stopping(stop, "explicit stop"), actor.state.phase)
            assertTrue(actor.state.explicitStopRequested)
        } finally {
            actor.shutdown()
        }
    }

    @Test
    fun replacementAndOldCleanupCompletionStayOwnerBound() {
        val actor = VpnRuntimeActor()
        try {
            val tokenA = token(1)
            val tokenB = token(2)
            actor.send(VpnRuntimeEvent.StartRequested(tokenA))
            actor.send(
                VpnRuntimeEvent.StartFailed(
                    tokenA,
                    request("A"),
                    IllegalStateException("failed"),
                ),
            )
            actor.send(VpnRuntimeEvent.StartRequested(tokenB))
            actor.send(
                VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.StartRollback(tokenA)),
            )

            assertEquals(VpnRuntimePhase.Starting(tokenB), actor.state.phase)
        } finally {
            actor.shutdown()
        }
    }

    @Test
    fun startStopStartKeepsNewestStartAuthoritative() {
        val actor = VpnRuntimeActor()
        try {
            val startA = token(1)
            val stop = token(generation = 2, persistentGeneration = 1)
            val startB = token(generation = 3, persistentGeneration = 2)
            actor.send(VpnRuntimeEvent.StartRequested(startA))
            actor.send(VpnRuntimeEvent.StopRequested(stop, "explicit stop"))
            actor.send(VpnRuntimeEvent.StartRequested(startB))
            actor.send(VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.Stop(stop)))

            assertEquals(VpnRuntimePhase.Starting(startB), actor.state.phase)
            assertTrue(!actor.state.explicitStopRequested)
        } finally {
            actor.shutdown()
        }
    }

    @Test
    fun duplicateStopCompletionCannotMutateReplacement() {
        val actor = VpnRuntimeActor()
        try {
            val stop = token(generation = 1, persistentGeneration = 0)
            actor.send(VpnRuntimeEvent.StopRequested(stop, "stop"))
            actor.send(VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.Stop(stop)))
            val replacement = token(generation = 2, persistentGeneration = 1)
            actor.send(VpnRuntimeEvent.StartRequested(replacement))
            actor.send(VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.Stop(stop)))

            assertEquals(VpnRuntimePhase.Starting(replacement), actor.state.phase)
        } finally {
            actor.shutdown()
        }
    }

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
