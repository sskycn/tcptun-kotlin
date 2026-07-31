package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToggleStateTest {
    @Test
    fun stoppedVpnTreatsPersistedActiveProfileAsNotRunning() {
        val activeIds = setOf("profile-a")

        val nextActiveIds = nextActiveProfileIds(
            activeIds = activeIds,
            profileId = "profile-a",
            vpnStatus = "Stopped",
        )

        assertEquals(activeIds, nextActiveIds)
    }

    @Test
    fun runningVpnStopsAnActiveProfile() {
        val nextActiveIds = nextActiveProfileIds(
            activeIds = setOf("profile-a", "profile-b"),
            profileId = "profile-a",
            vpnStatus = "Running",
        )

        assertEquals(setOf("profile-b"), nextActiveIds)
    }

    @Test
    fun inactiveProfileIsAddedForStoppedOrRunningVpn() {
        assertEquals(
            setOf("profile-a"),
            nextActiveProfileIds(emptySet(), "profile-a", "Stopped"),
        )
        assertEquals(
            setOf("profile-a", "profile-b"),
            nextActiveProfileIds(setOf("profile-a"), "profile-b", "Running"),
        )
    }

    @Test
    fun commandFailureRollsBackOnlyItsOwnCommittedState() {
        val previous = ProfilesState(emptyList(), activeIds = setOf("profile-a"))
        val committed = previous.copy(activeIds = setOf("profile-b"))

        assertEquals(
            previous,
            rollbackProfileStateIfStillCommitted(
                current = committed,
                currentRevision = 7,
                committed = committed,
                committedRevision = 7,
                previous = previous,
            ),
        )

        val newerState = committed.copy(activeIds = setOf("profile-c"))
        assertEquals(
            null,
            rollbackProfileStateIfStillCommitted(
                current = newerState,
                currentRevision = 8,
                committed = committed,
                committedRevision = 7,
                previous = previous,
            ),
        )

        // Even if the value cycles back to the same content, a newer revision
        // belongs to a later user operation and must not be overwritten.
        assertEquals(
            null,
            rollbackProfileStateIfStillCommitted(
                current = committed,
                currentRevision = 9,
                committed = committed,
                committedRevision = 7,
                previous = previous,
            ),
        )
    }

    @Test
    fun failedInitialStartRollbackRequiresTheExactStillActivePlan() {
        val first = AppConfig(id = "first", serverHost = "192.0.2.1", token = "one")
        val second = AppConfig(id = "second", serverHost = "192.0.2.2", token = "two")
        val committed = ProfilesState(
            profiles = listOf(first, second),
            activeIds = setOf(first.id),
        )
        val failedPlan = committed.runPlan()

        assertTrue(shouldRollbackFailedInitialStart(committed, failedPlan))
        assertFalse(
            shouldRollbackFailedInitialStart(
                committed.copy(activeIds = setOf(second.id)),
                failedPlan,
            ),
        )
        assertFalse(
            shouldRollbackFailedInitialStart(
                committed.copy(activeIds = emptySet()),
                failedPlan,
            ),
        )
    }
}
