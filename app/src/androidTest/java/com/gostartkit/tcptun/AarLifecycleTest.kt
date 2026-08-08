package com.tcptun.client

import androidbridge.Androidbridge
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class AarLifecycleTest {
    @Test
    fun engineSupportsRepeatedStartStopAndIdempotentClose() {
        val engine = Androidbridge.newEngine()
        try {
            engine.configure(directConfig(availablePort()))
            var previousSessionId = 0L
            repeat(5) {
                val sessionId = engine.startConfiguredSessionWithDisabledOutbounds("[]")
                assertTrue(sessionId > previousSessionId)
                assertEquals(sessionId, engine.sessionID())
                assertTrue(engine.status() in setOf("Starting", "Running"))

                engine.stop()
                engine.waitStopped(sessionId, 2_000)
                assertEquals("Stopped", engine.status())
                previousSessionId = sessionId
            }
        } finally {
            engine.close()
            engine.close()
        }
    }

    @Test
    fun closingOneEngineDoesNotAffectAnotherEngine() {
        val first = Androidbridge.newEngine()
        val second = Androidbridge.newEngine()
        try {
            first.configure(directConfig(availablePort()))
            second.configure(directConfig(availablePort()))
            val firstSession = first.startConfiguredSessionWithDisabledOutbounds("[]")
            val secondSession = second.startConfiguredSessionWithDisabledOutbounds("[]")
            assertTrue(first !== second)
            assertTrue(firstSession > 0)
            assertTrue(secondSession > 0)

            first.close()
            assertEquals("Stopped", first.status())
            assertTrue(second.status() in setOf("Starting", "Running"))
            assertEquals(secondSession, second.sessionID())

            second.stop()
            second.waitStopped(secondSession, 2_000)
            assertEquals("Stopped", second.status())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun abortIsIdempotentAndAllowsEngineReuse() {
        val engine = Androidbridge.newEngine()
        try {
            val port = availablePort()
            engine.configure(directConfig(port))
            val abortedSession = engine.startConfiguredSessionWithDisabledOutbounds("[]")

            engine.abort()
            engine.abort()
            engine.waitStopped(abortedSession, 100)
            assertEquals("Stopped", engine.status())

            // Abort must synchronously release listener ownership even though
            // the old runtime may still be completing full cleanup in the background.
            engine.configure(directConfig(port))
            val replacementSession = engine.startConfiguredSessionWithDisabledOutbounds("[]")
            assertTrue(replacementSession > abortedSession)
            engine.stop()
            engine.waitStopped(replacementSession, 2_000)
        } finally {
            engine.close()
        }
    }

    private fun availablePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }

    private fun directConfig(port: Int): String = """{
        "inbounds":[{
            "tag":"lifecycle-in",
            "type":"mixed",
            "address":["127.0.0.1:$port"],
            "network":["tcp"]
        }],
        "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
        "route":{"default_outbound":"direct"}
    }""".trimIndent()
}
