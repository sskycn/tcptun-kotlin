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
}
