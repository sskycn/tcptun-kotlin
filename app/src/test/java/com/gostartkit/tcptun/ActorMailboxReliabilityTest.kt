package com.tcptun.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorMailboxReliabilityTest {
    @Test
    fun fullMailboxRejectsStopExplicitlyWhenActorCannotProgress() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actor = VpnRuntimeActor(
            mailboxCapacity = 1,
            admissionTimeoutMillis = 50L,
            responseTimeoutMillis = 2_000L,
        )
        val callers = Executors.newFixedThreadPool(2)
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

            assertThrows(VpnRuntimeActorAdmissionException::class.java) {
                actor.send(VpnRuntimeEvent.StopRequested(token(1), "explicit stop"))
            }

            release.countDown()
            blocker.get(2, TimeUnit.SECONDS)
            filler.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            callers.shutdownNow()
            actor.shutdown()
        }
    }

    @Test
    fun cleanupReleasedWaitsForCapacityAndCannotDisappear() {
        val token = token(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actor = VpnRuntimeActor(
            initialState = VpnRuntimeState(
                phase = VpnRuntimePhase.Stopping(token, "stop"),
                lifecycleGeneration = token.lifecycleGeneration,
                persistentCommandGeneration = token.persistentGeneration,
                serviceInstanceId = token.serviceInstanceId,
                stopping = true,
            ),
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
            val completion = callers.submit {
                actor.sendInternal(VpnRuntimeEvent.CleanupReleased(VpnRuntimeCleanupOwner.Stop(token)))
            }

            release.countDown()
            blocker.get(2, TimeUnit.SECONDS)
            filler.get(2, TimeUnit.SECONDS)
            completion.get(2, TimeUnit.SECONDS)

            assertTrue(actor.state.phase is VpnRuntimePhase.Idle)
            assertFalse(actor.state.stopping)
        } finally {
            release.countDown()
            callers.shutdownNow()
            actor.shutdown()
        }
    }

    private fun awaitMailboxFull(actor: VpnRuntimeActor) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (actor.queuedCommandCount() != 1 && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(1, actor.queuedCommandCount())
    }

    private fun token(generation: Int) = VpnRuntimeCommandToken(7L, generation, generation)
}
