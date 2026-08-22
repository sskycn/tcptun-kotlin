package com.tcptun.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeCoordinatorTest {
    @Test
    fun runtimeMutationsExecuteSeriallyInAdmissionOrder() {
        val executor = newLifecycleScheduledExecutor("coordinator-test")
        val coordinator = VpnRuntimeCoordinator(executor) { true }
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = mutableListOf<Int>()
        try {
            assertTrue(coordinator.dispatch(VpnRuntimeCommand.Start, { throw AssertionError(it) }) {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                order += 1
                completed.countDown()
            })
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertTrue(coordinator.dispatch(VpnRuntimeCommand.Stop, { throw AssertionError(it) }) {
                order += 2
                completed.countDown()
            })
            assertEquals(2, coordinator.inFlight)

            releaseFirst.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(1, 2), order)
            assertEquals(0, coordinator.inFlight)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun inactiveCoordinatorRejectsMutation() {
        val executor = newLifecycleScheduledExecutor("coordinator-rejection-test")
        try {
            val coordinator = VpnRuntimeCoordinator(executor) { false }
            assertFalse(coordinator.dispatch(VpnRuntimeCommand.Start, { throw AssertionError(it) }) {})
            assertEquals(0, coordinator.inFlight)
        } finally {
            executor.shutdownNow()
        }
    }
}
