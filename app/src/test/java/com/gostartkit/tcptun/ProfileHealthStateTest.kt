package com.tcptun.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHealthStateTest {
    @After
    fun resetState() {
        TcptunState.setStatus("Stopped")
    }

    @Test
    fun initializationKeepsActiveHealthAndDropsInactiveProfiles() {
        val first = AppConfig(id = "first", name = "First")
        val second = AppConfig(id = "second", name = "Second")
        TcptunState.initializeProfileHealth(listOf(first, second))
        TcptunState.setProfileHealth(
            first.id,
            ProfileHealth(
                status = ProfileHealthStatus.Healthy,
                latencyMs = 18,
                lastCheckedAtMs = 100,
                lastSucceededAtMs = 100,
            ),
        )

        TcptunState.initializeProfileHealth(listOf(first))

        val health = TcptunState.state.value.profileHealth
        assertEquals(ProfileHealthStatus.Healthy, health.getValue(first.id).status)
        assertEquals(18L, health.getValue(first.id).latencyMs)
        assertFalse(second.id in health)
    }

    @Test
    fun degradedHealthIsClearedWhenVpnStops() {
        TcptunState.setProfileHealth(
            "profile",
            ProfileHealth(status = ProfileHealthStatus.Degraded, failures = 2, error = "timeout"),
        )
        assertTrue(TcptunState.state.value.profileHealth.isNotEmpty())

        TcptunState.setStatus("Stopped")

        assertTrue(TcptunState.state.value.profileHealth.isEmpty())
    }
}
