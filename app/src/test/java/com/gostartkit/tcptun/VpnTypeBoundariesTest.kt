package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTypeBoundariesTest {
    @Test
    fun `VPN lifecycle properties reject illegal boolean combinations`() {
        assertTrue(VpnStatus.Starting.isActive)
        assertTrue(VpnStatus.Starting.isTransitioning)
        assertTrue(VpnStatus.Running.isActive)
        assertFalse(VpnStatus.Running.isTransitioning)
        assertTrue(VpnStatus.Stopped.isTerminal)
        assertTrue(VpnStatus.Error.isTerminal)
        assertEquals("Running", VpnStatus.Running.displayName)
    }

    @Test
    fun `service actions map to typed commands and policy kinds`() {
        assertEquals(VpnServiceCommand.Start, VpnServiceCommand.fromAction(TcptunVpnService.ACTION_START))
        assertEquals(VpnServiceCommand.Stop, VpnServiceCommand.fromAction(TcptunVpnService.ACTION_STOP))
        assertEquals(
            ServiceCommandKind.UpdateConnections,
            VpnServiceCommand.fromAction(TcptunVpnService.ACTION_UPDATE_OUTBOUNDS).policyKind,
        )
        assertEquals(
            ServiceCommandKind.Auxiliary,
            VpnServiceCommand.fromAction(TcptunVpnService.ACTION_REFRESH_CLIENT_IPS).policyKind,
        )
        assertEquals(VpnServiceCommand.Restore, VpnServiceCommand.fromAction(null))
        assertEquals(VpnServiceCommand.Unknown, VpnServiceCommand.fromAction("unknown"))
    }
}
