package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LocalProxyHealthProbeTest {
    @Test
    fun queuedTcpConnectDoesNotProveAcceptLoopIsServing() {
        // Do not accept: the kernel completes connect while the application is stalled.
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val probe = LocalProxyHealthProbe(localConnectTimeoutMs = 100)
            assertTrue(probe.canConnect(server.localPort))
            val result = probe.listener(server.localPort)
            assertEquals("B_handshake", result.layer)
            assertEquals("SocketTimeoutException", result.error)
            assertFalse(result.healthy)
        }
    }

    @Test
    fun absentListenerIsDifferentFromUnresponsiveHandshake() {
        val port = ServerSocket(0).use { it.localPort }
        val result = LocalProxyHealthProbe(localConnectTimeoutMs = 100).listener(port)
        assertEquals("A_listener", result.layer)
        assertEquals("ConnectException", result.error)
    }

    @Test
    fun authenticationProbeDoesNotRequestAnOutbound() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val done = CompletableFuture<Unit>()
            val worker = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        val input = socket.getInputStream()
                        assertEquals(5, input.read())
                        assertEquals(1, input.read())
                        assertEquals(2, input.read())
                        socket.getOutputStream().write(byteArrayOf(5, 2))
                        assertEquals(1, input.read())
                        assertEquals(1, input.read())
                        assertEquals('u'.code, input.read())
                        assertEquals(1, input.read())
                        assertEquals('p'.code, input.read())
                        socket.getOutputStream().write(byteArrayOf(1, 0))
                        val request = ByteArray(10)
                        java.io.DataInputStream(input).readFully(request)
                        assertEquals("probe must not send CONNECT", 9, request[1].toInt())
                        socket.getOutputStream().write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                        assertEquals("probe must close before sending CONNECT", -1, input.read())
                    }
                }.fold({ done.complete(Unit) }, done::completeExceptionally)
            }.apply { isDaemon = true; start() }
            val result = LocalProxyHealthProbe().listener(server.localPort, LocalProxyUser("u", "p"))
            assertTrue(result.summary(), result.healthy)
            done.get(2, TimeUnit.SECONDS)
            worker.join(2_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test
    fun loopbackSuccessDoesNotProveAnotherAddressIsBound() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val probe = LocalProxyHealthProbe(localConnectTimeoutMs = 100)
            assertTrue(probe.canConnect(server.localPort))
            // Synthetic second interface address; real LAN assertions run on Android.
            assertEquals("A_listener", probe.listener(server.localPort, host = "127.0.0.2").layer)
        }
    }

    @Test
    fun upstreamTargetsRotateWithoutChangingPriorityWithinACycle() {
        val first = UpstreamProbeTarget("first", "first.example")
        val second = UpstreamProbeTarget("second", "second.example")
        val third = UpstreamProbeTarget("third", "third.example")
        val probe = LocalProxyHealthProbe(targets = listOf(first, second, third))

        assertEquals(listOf(first, second, third), probe.orderedTargets())
        assertEquals(listOf(second, third, first), probe.orderedTargets())
        assertEquals(listOf(third, first, second), probe.orderedTargets())
        assertEquals(listOf(first, second, third), probe.orderedTargets())
    }

    @Test
    fun connectAddressUsesConfiguredListener() {
        val probe = LocalProxyHealthProbe(
            localHost = "127.0.0.2",
            targets = listOf(UpstreamProbeTarget("target", "example.com")),
        )

        assertEquals("127.0.0.2:1080", probe.connectAddress(1080))
    }
}
