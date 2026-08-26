package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerSavingBridgeObservationRuntimeTest {
    @Test
    fun activeRegistrationReceivesExplicitReconcile() {
        val owner = Any()
        var reconciles = 0
        try {
            PowerSavingBridgeObservationRuntime.install(owner) { reconciles += 1 }
            assertEquals(1, reconciles)

            PowerSavingBridgeObservationRuntime.reconcileNow()
            assertEquals(2, reconciles)
        } finally {
            PowerSavingBridgeObservationRuntime.uninstall(owner)
        }

        PowerSavingBridgeObservationRuntime.reconcileNow()
        assertEquals(2, reconciles)
    }

    @Test
    fun staleOwnerCannotUninstallReplacementBridge() {
        val oldOwner = Any()
        val newOwner = Any()
        var oldReconciles = 0
        var newReconciles = 0
        try {
            PowerSavingBridgeObservationRuntime.install(oldOwner) { oldReconciles += 1 }
            PowerSavingBridgeObservationRuntime.install(newOwner) { newReconciles += 1 }
            assertEquals(1, oldReconciles)
            assertEquals(1, newReconciles)

            PowerSavingBridgeObservationRuntime.uninstall(oldOwner)
            PowerSavingBridgeObservationRuntime.reconcileNow()
            assertEquals(1, oldReconciles)
            assertEquals(2, newReconciles)
        } finally {
            PowerSavingBridgeObservationRuntime.uninstall(newOwner)
            PowerSavingBridgeObservationRuntime.uninstall(oldOwner)
        }
    }
}
