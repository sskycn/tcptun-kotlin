package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavingObservationPolicyTest {
    @Test
    fun powerSavingBackgroundDefersUiObservationPublication() {
        assertFalse(PowerSavingObservationPolicy.shouldPublish(powerSaving = true, uiVisible = false))
    }

    @Test
    fun foregroundAlwaysPublishesUiObservations() {
        assertTrue(PowerSavingObservationPolicy.shouldPublish(powerSaving = true, uiVisible = true))
        assertTrue(PowerSavingObservationPolicy.shouldPublish(powerSaving = false, uiVisible = true))
    }

    @Test
    fun disablingPowerSavingKeepsBackgroundObservationsLive() {
        assertTrue(PowerSavingObservationPolicy.shouldPublish(powerSaving = false, uiVisible = false))
    }
}
