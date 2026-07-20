package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManagedRouteInboundTagsTest {
    @Test
    fun runtimeSettingsDefaultDisablesRouteLocalProxy() {
        assertFalse(RuntimeSettings().routeLocalProxyTraffic)
    }

    @Test
    fun localProxyInboundTagIsStable() {
        assertEquals("local", AndroidLocalProxyInboundTag)
        assertEquals("tun", AndroidTunInboundTag)
    }
}
