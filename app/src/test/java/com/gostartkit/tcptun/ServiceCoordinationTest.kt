package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask

class ServiceCoordinationTest {
    @Test
    fun rankedSelectionRejectsStaleCallbackAndTracksInitialClaim() {
        val tracker = RankedSelectionTracker<String>()

        val wifiSelection = tracker.update("wifi", 130)
        val ethernetSelection = tracker.update("ethernet", 140)

        assertNull(tracker.claim(wifiSelection))
        val initial = tracker.claim(ethernetSelection)
        assertEquals("ethernet", initial?.value)
        assertTrue(initial?.initial == true)
        assertNull(tracker.claim(ethernetSelection))

        val fallback = tracker.remove("ethernet")
        val changed = tracker.claim(fallback)
        assertEquals("wifi", changed?.value)
        assertFalse(changed?.initial ?: true)
    }

    @Test
    fun rankedSelectionCanClaimNoNetworkAfterLastCandidateIsLost() {
        val tracker = RankedSelectionTracker<String>()
        tracker.claim(tracker.update("cellular", 120))

        val lost = tracker.claim(tracker.remove("cellular"))

        assertNull(lost?.value)
        assertFalse(lost?.initial ?: true)
    }

    @Test
    fun clearingRankedSelectionRestoresInitialState() {
        val tracker = RankedSelectionTracker<String>()
        tracker.claim(tracker.update("wifi", 130))

        tracker.clear()
        val selection = tracker.claim(tracker.update("cellular", 120))

        assertTrue(selection?.initial == true)
    }

    @Test
    fun runtimeSettingsGateCoalescesForceAndRejectsOlderGeneration() {
        val gate = RuntimeSettingsApplyGate()
        val first = gate.request(forceRestart = true)
        val second = gate.request(forceRestart = false)

        assertFalse(gate.isLatest(first))
        assertTrue(gate.isLatest(second))
        assertNull(gate.claim(first))
        assertTrue(gate.claim(second) == true)
        assertFalse(gate.claim(second) ?: true)
    }

    @Test
    fun bridgeRestartCoordinatorRejectsOlderRequestAndLifecycleGeneration() {
        val coordinator = recoveryCoordinator()
        val first = coordinator.requestRestart(lifecycleGeneration = 4, cancelIfHealthy = false)
        val second = coordinator.requestRestart(lifecycleGeneration = 4, cancelIfHealthy = false)

        assertFalse(coordinator.isCurrent(first, currentLifecycleGeneration = 4))
        assertTrue(coordinator.isCurrent(second, currentLifecycleGeneration = 4))
        assertFalse(coordinator.isCurrent(second, currentLifecycleGeneration = 5))
        assertTrue(coordinator.claimRestart(second, currentLifecycleGeneration = 4))
    }

    @Test
    fun healthySnapshotOnlyCancelsExplicitlyRecoverableRestart() {
        val coordinator = recoveryCoordinator()
        val mandatory = coordinator.requestRestart(lifecycleGeneration = 1, cancelIfHealthy = false)

        assertFalse(coordinator.cancelRestartAfterHealthySnapshot())
        assertTrue(coordinator.isCurrent(mandatory, currentLifecycleGeneration = 1))

        val recoverable = coordinator.requestRestart(lifecycleGeneration = 1, cancelIfHealthy = true)
        assertTrue(coordinator.cancelRestartAfterHealthySnapshot())
        assertFalse(coordinator.isCurrent(recoverable, currentLifecycleGeneration = 1))
        assertFalse(coordinator.cancelRestartAfterHealthySnapshot())
    }

    @Test
    fun bridgeRestartDelayCombinesCooldownAndSettleWindow() {
        val coordinator = recoveryCoordinator(minRestartIntervalMillis = 30_000L)
        val first = coordinator.requestRestart(lifecycleGeneration = 2, cancelIfHealthy = false)
        assertEquals(0L, coordinator.beginRestart(nowMillis = 100_000L))

        val second = coordinator.requestRestart(lifecycleGeneration = 2, cancelIfHealthy = false)
        assertEquals(
            25_000L,
            coordinator.scheduleDelayMillis(second, 2, nowMillis = 105_000L, settleDelayMillis = 1_000L),
        )
        assertEquals(
            40_000L,
            coordinator.scheduleDelayMillis(second, 2, nowMillis = 105_000L, settleDelayMillis = 40_000L),
        )
        assertFalse(coordinator.isCurrent(first, currentLifecycleGeneration = 2))
        assertEquals(25_000L, coordinator.beginRestart(nowMillis = 105_000L))
        assertEquals(0L, coordinator.beginRestart(nowMillis = 130_000L))
    }

    @Test
    fun successfulRequestResetsRecoveryBackoffAndCancellationResetsRestart() {
        val delays = mutableListOf<Int>()
        val coordinator = BridgeRecoveryCoordinator(30_000L) { attempt ->
            delays += attempt
            attempt * 100L
        }

        assertEquals(BridgeRecoveryAttempt(1, 100L), coordinator.nextRecoveryAttempt())
        assertEquals(BridgeRecoveryAttempt(2, 200L), coordinator.nextRecoveryAttempt())
        assertTrue(coordinator.recoveryPending)

        val token = coordinator.requestRestart(lifecycleGeneration = 7, cancelIfHealthy = false)
        assertFalse(coordinator.recoveryPending)
        coordinator.cancelRestart()
        assertFalse(coordinator.isCurrent(token, currentLifecycleGeneration = 7))
        assertEquals(listOf(1, 2), delays)
    }

    @Test
    fun latestTaskSlotCancelsSupersededAndOwnedTasks() {
        val slot = LatestTaskSlot()
        val first = FutureTask<Unit> {}
        val second = FutureTask<Unit> {}

        slot.replace(first)
        slot.replace(second)

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)

        slot.cancel()
        assertTrue(second.isCancelled)
        // Component teardown can be repeated without double-cancelling ownership.
        slot.cancel()
    }

    @Test
    fun roundRobinBatchSelectorBoundsWorkAndEventuallyVisitsEveryEntry() {
        val selector = RoundRobinBatchSelector()
        val values = listOf("a", "b", "c", "d", "e")

        assertEquals(listOf("a", "b"), selector.select(values, 2))
        assertEquals(listOf("c", "d"), selector.select(values, 2))
        assertEquals(listOf("e", "a"), selector.select(values, 2))

        selector.clear()
        assertEquals(values, selector.select(values, 99))
    }

    private fun recoveryCoordinator(
        minRestartIntervalMillis: Long = 30_000L,
    ): BridgeRecoveryCoordinator = BridgeRecoveryCoordinator(
        minRestartIntervalMillis = minRestartIntervalMillis,
        recoveryDelayMillis = { attempt -> attempt * 1_000L },
    )
}
