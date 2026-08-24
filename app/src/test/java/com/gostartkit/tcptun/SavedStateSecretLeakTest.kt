package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedStateSecretLeakTest {
    @Test
    fun profileAndRunPlanSavedStateEncodesOnlyOpaqueIds() {
        val profile = AppConfig(
            id = "saved-state-profile",
            serverHost = "saved-state-host.example",
            serverPort = "443",
            protocol = "native",
            token = "saved-state-token",
            rawConfigJson = "{\"password\":\"saved-state-raw-password\"}",
        )
        val planProfile = profile.copy(rawConfigJson = "")
        val plan = ProfileRunPlan(listOf(planProfile), setOf(planProfile.id))

        val profileState = requireNotNull(encodePendingProfile(profile))
        val planState = requireNotNull(encodePendingRunPlan(plan))

        listOf(profile.token, profile.serverHost, profile.rawConfigJson, "password").forEach { marker ->
            assertFalse(profileState.contains(marker))
        }
        listOf(planProfile.token, planProfile.serverHost).forEach { marker ->
            assertFalse(planState.contains(marker))
        }
        assertTrue(profileState.matches(Regex("^[0-9a-f-]{36}$")))
        assertTrue(planState.matches(Regex("^[0-9a-f-]{36}$")))
    }

    @Test
    fun missingProcessLocalOperationSafelyDropsTransientState() {
        PendingUiOperationStore.clearForTest()

        assertNull(decodePendingProfile("expired-profile-operation"))
        assertNull(decodePendingRunPlan("expired-plan-operation"))
        assertNull(decodePendingProfileUri("expired-uri-operation"))
    }

}
