package com.tcptun.client

import org.junit.Assert.assertEquals
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
}
