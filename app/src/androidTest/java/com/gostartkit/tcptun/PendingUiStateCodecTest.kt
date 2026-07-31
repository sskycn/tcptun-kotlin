package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

        assertEquals(plan, decodePendingRunPlan(encodePendingRunPlan(plan)))
        assertEquals(profile, decodePendingProfile(encodePendingProfile(profile)))
    }

    @Test
    fun appSpecificDecodersSafelyClearMalformedState() {
        assertNull(decodePendingRunPlan("not-json"))
        assertNull(decodePendingProfile("{}"))
    }
}
