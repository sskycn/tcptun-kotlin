package com.tcptun.client

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorShutdownTest {
    @Test
    fun destroyWaitsForMailboxCapacityAndRejectsFutureExternalMutations() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actor = VpnRuntimeActor(
            mailboxCapacity = 1,
            admissionTimeoutMillis = 1_000L,
            responseTimeoutMillis = 2_000L,
        )
        val callers = Executors.newFixedThreadPool(3)
        try {
            val blocker = callers.submit {
                actor.compatibilityMutation { state ->
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    state
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val filler = callers.submit { actor.compatibilityMutation { it } }
            awaitMailboxFull(actor)
            val destroy = callers.submit {
                actor.beginDestroy { VpnRuntimeEvent.Destroyed(token(1)) }
            }

            awaitExternalIngressClosed(actor)
            assertFalse(actor.isAcceptingExternal())
            release.countDown()
            blocker.get(2, TimeUnit.SECONDS)
            filler.get(2, TimeUnit.SECONDS)
            destroy.get(2, TimeUnit.SECONDS)

            assertTrue(actor.state.phase is VpnRuntimePhase.Destroyed)
            assertThrows(VpnRuntimeActorAdmissionException::class.java) {
                actor.send(VpnRuntimeEvent.StartRequested(token(2)))
            }
            assertTrue(actor.shutdown())
            assertTrue(actor.isTerminated())
        } finally {
            release.countDown()
            callers.shutdownNow()
            actor.shutdown()
        }
    }

    @Test
    fun lateStartCompletionAfterDestroyCannotResurrectRunning() {
        val start = token(1)
        val actor = VpnRuntimeActor()
        try {
            actor.send(VpnRuntimeEvent.StartRequested(start))
            actor.beginDestroy { VpnRuntimeEvent.Destroyed(token(2)) }
            actor.sendInternal(VpnRuntimeEvent.StartSucceeded(start, plan("late")))

            assertTrue(actor.state.phase is VpnRuntimePhase.Destroyed)
        } finally {
            assertTrue(actor.shutdown())
        }
    }

    @Test
    fun destroyedActorDrainsCleanupPendingCompletionBeforeShutdown() {
        val start = token(1)
        val owner = VpnRuntimeCleanupOwner.StartRollback(start)
        val actor = VpnRuntimeActor(
            initialState = VpnRuntimeState(
                phase = VpnRuntimePhase.CleaningUp(owner, "rollback"),
                lifecycleGeneration = start.lifecycleGeneration,
                persistentCommandGeneration = start.persistentGeneration,
                serviceInstanceId = start.serviceInstanceId,
                stopping = true,
            ),
        )

        actor.beginDestroy { VpnRuntimeEvent.Destroyed(token(2)) }
        actor.sendInternal(VpnRuntimeEvent.CleanupReleased(owner))

        assertTrue(actor.state.phase is VpnRuntimePhase.Destroyed)
        assertTrue(actor.shutdown())
        assertTrue(actor.isTerminated())
    }

    @Test
    fun destroyDuringStopDrainsLateStopCompletionWithoutReturningIdle() {
        val stop = token(1)
        val actor = VpnRuntimeActor(
            initialState = VpnRuntimeState(
                phase = VpnRuntimePhase.Stopping(stop, "stop"),
                lifecycleGeneration = stop.lifecycleGeneration,
                persistentCommandGeneration = stop.persistentGeneration,
                serviceInstanceId = stop.serviceInstanceId,
                stopping = true,
            ),
        )

        actor.beginDestroy { VpnRuntimeEvent.Destroyed(token(2)) }
        actor.sendInternal(VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.Stop(stop)))

        assertTrue(actor.state.phase is VpnRuntimePhase.Destroyed)
        assertTrue(actor.shutdown())
    }

    @Test
    fun repeatedActorLifetimesTerminateEveryCreatedThread() {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        repeat(24) { index ->
            val actor = VpnRuntimeActor(
                threadName = "ActorShutdownTest-$index",
                threadFactory = { runnable, name -> Thread(runnable, name).also(threads::add) },
            )
            assertTrue(actor.shutdown())
        }

        assertTrue(threads.all { !it.isAlive })
    }

    private fun awaitMailboxFull(actor: VpnRuntimeActor) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (actor.queuedCommandCount() != 1 && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(actor.queuedCommandCount() == 1)
    }

    private fun awaitExternalIngressClosed(actor: VpnRuntimeActor) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (actor.isAcceptingExternal() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertFalse(actor.isAcceptingExternal())
    }

    private fun token(generation: Int) = VpnRuntimeCommandToken(7L, generation, generation)

    private fun plan(id: String) = ProfileRunPlan(listOf(AppConfig(id = id, name = id)))
}
