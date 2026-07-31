package com.tcptun.client

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class VpnServiceManifestTest {
    @Test
    fun vpnServiceIsNotStoppedWithTheAppTask() {
        val services = xml("src/main/AndroidManifest.xml").getElementsByTagName("service")
        val vpnService = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull {
                it.getAttribute("android:name") == ".TcptunVpnService"
            }

        assertNotNull("TcptunVpnService is missing from the manifest", vpnService)
        assertEquals("false", vpnService?.getAttribute("android:stopWithTask"))
    }

    private fun xml(relativePath: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(relativePath))
}
