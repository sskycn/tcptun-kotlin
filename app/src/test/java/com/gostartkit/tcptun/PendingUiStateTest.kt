package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PendingUiStateTest {
    @Test
    fun processLocalOperationIdContainsNoProfileFieldsAndIsOneTime() {
        val profile = AppConfig(
            id = "pending-profile",
            serverHost = "pending-secret.example",
            token = "pending-secret-token",
        )

        val operationId = requireNotNull(encodePendingProfile(profile))

        listOf(profile.serverHost, profile.token).forEach { marker ->
            assertFalse(operationId.contains(marker))
        }
        assertEquals(profile, decodePendingProfile(operationId))
        assertNull(decodePendingProfile(operationId))
    }

    @Test
    fun invalidOrExpiredOperationSafelyClearsState() {
        PendingUiOperationStore.clearForTest()

        assertNull(decodePendingProfile("not-a-live-operation"))
        assertNull(decodePendingRunPlan("not-a-live-operation"))
        assertNull(decodePendingProfileUri("not-a-live-operation"))
    }
}
