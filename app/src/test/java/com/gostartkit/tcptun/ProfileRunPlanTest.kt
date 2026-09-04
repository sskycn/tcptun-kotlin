package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileRunPlanTest {
    @Test
    fun runPlanDeclaresAllProfilesAndTracksActiveProfilesWithoutDefault() {
        val primary = validProfile("primary", "192.0.2.10")
        val secondary = validProfile("secondary", "192.0.2.20")
        val disabled = validProfile("disabled", "192.0.2.30")
        val state = ProfilesState(
            profiles = listOf(primary, secondary, disabled),
            activeIds = setOf(primary.id, secondary.id),
        )

        val plan = state.runPlan()

        assertEquals(listOf(primary.id, secondary.id, disabled.id), plan.profiles.map(AppConfig::id))
        assertEquals(setOf(primary.id, secondary.id), plan.activeIds)
        assertEquals(listOf(primary.id, secondary.id), plan.activeProfiles.map(AppConfig::id))
    }

    @Test
    fun outboundTagsAreStableAndProfileSpecific() {
        val first = profileOutboundTag("profile-one")

        assertEquals(first, profileOutboundTag("profile-one"))
        assertEquals(32, first.length)
        assertNotEquals(first, profileOutboundTag("profile-two"))
    }

    @Test
    fun defaultOutboundOnlyAcceptsDynamicPoolOrDirect() {
        assertEquals(DefaultOutboundDynamicPool, normalizeDefaultOutboundSelection(""))
        assertEquals(DefaultOutboundDirect, normalizeDefaultOutboundSelection("  $DefaultOutboundDirect  "))
        assertEquals(DefaultOutboundDynamicPool, normalizeDefaultOutboundSelection("profile-id"))
        assertEquals(DefaultOutboundDynamicPool, normalizeDefaultOutboundSelection("unknown"))
    }

    @Test
    fun normalizationRejectsProfileListsAboveTheRuntimeBalanceLimit() {
        val profiles = List(MaxRuntimeProfileCount + 1) { index ->
            validProfile("profile-$index", "192.0.2.10")
        }

        assertThrows(IllegalArgumentException::class.java) {
            ProfileRunPlan(profiles).normalized()
        }
    }

    @Test
    fun normalizationRejectsOversizedOrExcessActiveProfileIds() {
        val profile = validProfile("primary", "192.0.2.10")

        assertThrows(IllegalArgumentException::class.java) {
            ProfileRunPlan(
                profiles = listOf(profile.copy(id = "x".repeat(MaxProfileIdLength + 1))),
            ).normalized()
        }

        assertThrows(IllegalArgumentException::class.java) {
            ProfileRunPlan(
                profiles = listOf(profile),
                activeIds = List(MaxRuntimeProfileCount + 1) { "id-$it" }.toSet(),
            ).normalized()
        }
    }

    @Test
    fun normalizationRejectsAnyRemoteProfileWithoutConfidentiality() {
        val encrypted = validProfile("encrypted", "192.0.2.10")
        val none = validProfile("none", "192.0.2.20").copy(tls = false)

        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileRunPlan(listOf(encrypted, none), setOf(encrypted.id)).normalized()
        }

        assertEquals("none: $VpnTunnelConfidentialityError", error.message)
    }

    private fun validProfile(id: String, host: String) = AppConfig(
        id = id,
        name = id,
        serverHost = host,
        serverPort = "443",
        token = "$id-token",
    )
}
