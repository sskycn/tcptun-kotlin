package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnForegroundRuntimeTest {
    @Test
    fun foregroundServiceTypeStartsAtAndroid14() {
        assertEquals(VpnForegroundStartMode.Legacy, foregroundStartMode(33))
        assertEquals(VpnForegroundStartMode.SpecialUse, foregroundStartMode(34))
    }

    @Test
    fun notificationPermissionOnlyGatesUpdatesFromAndroid13() {
        assertTrue(foregroundNotificationUpdateAllowed(32, notificationPermissionGranted = false))
        assertFalse(foregroundNotificationUpdateAllowed(33, notificationPermissionGranted = false))
        assertTrue(foregroundNotificationUpdateAllowed(33, notificationPermissionGranted = true))
    }

    @Test
    fun foregroundStatesSelectExplicitNotificationTextKinds() {
        assertEquals(VpnForegroundTextKind.Starting, VpnForegroundState.Starting.textKind())
        assertEquals(VpnForegroundTextKind.Running, VpnForegroundState.Running().textKind())
        assertEquals(VpnForegroundTextKind.RunningCount, VpnForegroundState.Running(2).textKind())
        assertEquals(VpnForegroundTextKind.Reconnecting, VpnForegroundState.Reconnecting().textKind())
        assertEquals(
            VpnForegroundTextKind.ReconnectingAttempt,
            VpnForegroundState.Reconnecting(3).textKind(),
        )
        assertEquals(
            VpnForegroundTextKind.CleanupPending,
            VpnForegroundState.Error(retryingCleanup = false).textKind(),
        )
        assertEquals(
            VpnForegroundTextKind.CleanupRetrying,
            VpnForegroundState.Error(retryingCleanup = true).textKind(),
        )
    }
}
