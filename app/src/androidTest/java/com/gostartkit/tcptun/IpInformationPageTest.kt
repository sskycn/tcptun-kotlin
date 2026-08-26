package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject

class IpInformationPageTest {
    @Test
    fun copiedProxyConfigurationIsDirectlyUsableTcptunGoJson() {
        val root = JSONObject(
            tcptunGoProxyConfigurationJson(
                proxyAddress = "192.0.2.1:1088",
                username = "client\"name",
                password = "secret\\value",
            ),
        )

        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("local", inbound.getString("tag"))
        assertEquals("mixed", inbound.getString("type"))
        assertEquals("127.0.0.1:1080", inbound.getJSONArray("address").getString(0))
        assertEquals(listOf("tcp", "udp"), inbound.getJSONArray("network").stringValues())

        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("proxy", outbound.getString("tag"))
        assertEquals("socks5", outbound.getString("type"))
        assertEquals("192.0.2.1:1088", outbound.getJSONArray("address").getString(0))
        assertEquals(listOf("tcp", "udp"), outbound.getJSONArray("network").stringValues())
        assertEquals("client\"name", outbound.getString("username"))
        assertEquals("secret\\value", outbound.getString("password"))

        assertEquals("proxy", root.getJSONObject("route").getString("default_outbound"))
        assertEquals(0, root.getJSONObject("route").getJSONArray("rules").length())
        assertEquals(0, root.getJSONObject("dns").length())
        assertFalse(root.toString().contains("Protocol:"))
    }

    private fun org.json.JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)
}
