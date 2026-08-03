package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTextTest {
    @Test
    fun redactsStructuredCredentialsAndAuthorizationHeaders() {
        val input = """
            config={"token":"native-secret","password":"escaped\\\"secret","private_key":"key-material"}
            Authorization: Bearer bearer-secret socks_password=proxy-secret
        """.trimIndent()

        val redacted = redactSensitiveText(input)

        listOf("native-secret", "escaped", "key-material", "bearer-secret", "proxy-secret").forEach {
            assertFalse(it, redacted.contains(it))
        }
        assertTrue(redacted.contains("\"token\":\"<redacted>\""))
        assertTrue(redacted.contains("Authorization: <redacted>"))
    }

    @Test
    fun redactsProfileUrisWithoutHidingOrdinaryEndpoints() {
        val input = "native://token-value@example.com:443 vmess://YWJjZGVmZ2hpamts T3:Abcdefghijklmnop"

        val redacted = redactSensitiveText(input)

        assertEquals(
            "native://<redacted>@example.com:443 vmess://<redacted> T3:<redacted>",
            redacted,
        )
    }

    @Test
    fun tcptunStateAppliesRedactionAtLogAndErrorBoundaries() {
        try {
            TcptunState.clearLogs()
            TcptunState.appendLog("bridge failed token=runtime-secret")
            TcptunState.error("start failed password=display-secret")
            TcptunState.updateDiagnostics {
                it.copy(
                    bridgeRemote = "native://remote-secret@example.com:443",
                    bridgeLastError = "private_key=diagnostic-secret",
                )
            }
            TcptunState.setProfileHealth("profile", ProfileHealth(error = "token=health-secret"))
            val requestId = TcptunState.beginTcping("target", 1)
            TcptunState.completeTcpingStep(
                requestId,
                TcpingLinkResult("profile", error = "password=probe-secret"),
            )
            TcptunState.failTcping(requestId, "private_key=tcping-secret")

            assertFalse(TcptunState.logs.any { it.contains("runtime-secret") || it.contains("display-secret") })
            assertFalse(TcptunState.lastError.contains("display-secret"))
            assertTrue(TcptunState.lastError.contains("password=<redacted>"))
            assertEquals("native://<redacted>@example.com:443", TcptunState.diagnostics.bridgeRemote)
            assertEquals("private_key=<redacted>", TcptunState.diagnostics.bridgeLastError)
            assertEquals("token=<redacted>", TcptunState.state.value.profileHealth.getValue("profile").error)
            assertEquals("password=<redacted>", TcptunState.state.value.tcping.results.single().error)
            assertEquals("private_key=<redacted>", TcptunState.state.value.tcping.error)
        } finally {
            TcptunState.setStatus("Stopped")
            TcptunState.clearLogs()
        }
    }
}
