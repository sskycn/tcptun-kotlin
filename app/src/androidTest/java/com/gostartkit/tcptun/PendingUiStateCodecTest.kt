package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingUiStateCodecTest {
    @Test
    fun pendingRunPlanAndProfileRoundTripThroughSavedStateCodecs() {
        val profile = AppConfig(
            id = "profile-id",
            name = "Saved profile",
            serverHost = "192.0.2.10",
            serverPort = "443",
            token = "secret",
        )
        val plan = ProfileRunPlan(listOf(profile), setOf(profile.id))

        val encodedPlan = requireNotNull(encodePendingRunPlan(plan))
        val encodedProfile = requireNotNull(encodePendingProfile(profile))

        listOf(profile.token, profile.serverHost, profile.toJson().toString()).forEach { secret ->
            assertFalse(encodedPlan.contains(secret))
            assertFalse(encodedProfile.contains(secret))
        }
        assertEquals(plan, decodePendingRunPlan(encodedPlan))
        assertEquals(profile, decodePendingProfile(encodedProfile))
        assertNull(decodePendingRunPlan(encodedPlan))
        assertNull(decodePendingProfile(encodedProfile))
    }

    @Test
    fun appSpecificDecodersSafelyClearMalformedState() {
        assertNull(decodePendingRunPlan("missing-operation-id"))
        assertNull(decodePendingProfile("missing-operation-id"))
    }

    @Test
    fun deepLinkSavedStateContainsOnlyOpaqueOneTimeId() {
        val profileUri = "native://secret-token@secret.example:443"
        val operationId = encodePendingProfileUri(profileUri)

        assertFalse(operationId.contains("secret-token"))
        assertFalse(operationId.contains("secret.example"))
        assertEquals(profileUri, decodePendingProfileUri(operationId))
        assertNull(decodePendingProfileUri(operationId))
    }
}
