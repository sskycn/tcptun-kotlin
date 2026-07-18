package com.tcptun.client

import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HevAppFlowJniTest {
    @Test
    fun unresolvedFlowReturnsNullWhileTunnelIsStopped() {
        assertNull(
            HevSocks5Tunnel.resolveOriginalFlow(
                protocol = OsConstants.IPPROTO_TCP,
                sourceAddress = byteArrayOf(127, 0, 0, 1),
                sourcePort = 54321,
            ),
        )
    }
}
