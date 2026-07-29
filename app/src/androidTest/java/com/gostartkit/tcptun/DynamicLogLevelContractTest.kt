package com.tcptun.client

import androidbridge.Androidbridge
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class DynamicLogLevelContractTest {
    @Test
    fun engineExposesCanonicalDynamicLogLevelApi() {
        val engine = Androidbridge.newEngine()
        try {
            assertEquals(DefaultLogLevel, engine.logLevel())

            engine.configure(directConfig(availablePort()))
            engine.setLogLevel("warn")
            assertEquals("warn", engine.logLevel())

            val sessionId = engine.startConfiguredSessionWithDisabledOutbounds("[]")
            assertEquals("warn", engine.logLevel())

            engine.setLogLevel("debug")
            assertEquals("debug", engine.logLevel())

            engine.setLogLevel("none")
            assertEquals("off", engine.logLevel())

            assertThrows(Exception::class.java) { engine.setLogLevel("trace") }
            assertEquals("off", engine.logLevel())

            engine.stop()
            engine.waitStopped(sessionId, 2_000)
        } finally {
            engine.close()
        }
    }

    private fun availablePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }

    private fun directConfig(port: Int): String = """{
        "log":{"level":"error"},
        "inbounds":[{
            "tag":"dynamic-log-in",
            "type":"mixed",
            "address":["127.0.0.1:$port"],
            "network":["tcp"]
        }],
        "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
        "route":{"default_outbound":"direct"}
    }""".trimIndent()
}
