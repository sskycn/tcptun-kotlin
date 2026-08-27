package com.tcptun.client

import android.util.Base64
import androidbridge.Androidbridge
import androidbridge.Engine
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MixedProxyAuthenticationAndroidTest {
    @Test
    fun mixedProxyAcceptsStandardRfc1929AndRejectsInvalidSocksCredentials() = withMixedProxy { port ->
        val unavailableOriginPort = availablePort()
        assertTrue(
            runCatching {
                openProxySocket(port).use { socket ->
                    Socks5Client.connect(socket, Loopback, unavailableOriginPort, "", "")
                }
            }.isFailure,
        )
        assertTrue(
            runCatching {
                openProxySocket(port).use { socket ->
                    Socks5Client.connect(socket, Loopback, unavailableOriginPort, Username, "wrong-password")
                }
            }.isFailure,
        )

        ServerSocket(0, 1, InetAddress.getByName(Loopback)).use { origin ->
            val echoed = CompletableFuture<ByteArray>()
            val thread = Thread {
                runCatching {
                    origin.accept().use { accepted ->
                        accepted.soTimeout = SocketTimeoutMillis
                        val request = accepted.getInputStream().readExact(OpaquePayload.size)
                        accepted.getOutputStream().write(request)
                        accepted.getOutputStream().flush()
                        request
                    }
                }.fold(echoed::complete, echoed::completeExceptionally)
            }.apply { start() }

            openProxySocket(port).use { socket ->
                Socks5Client.connect(socket, Loopback, origin.localPort, Username, Password)
                socket.getOutputStream().write(OpaquePayload)
                socket.getOutputStream().flush()
                assertArrayEquals(OpaquePayload, socket.getInputStream().readExact(OpaquePayload.size))
            }
            assertArrayEquals(OpaquePayload, echoed.get(5, TimeUnit.SECONDS))
            thread.join(5_000)
        }
    }

    @Test
    fun mixedProxyDoesNotForwardProxyAuthorizationToOrigin() = withMixedProxy { port ->
        val unavailableOriginPort = availablePort()
        assertProxyAuthenticationRequired(
            requestHttp(port, unavailableOriginPort, authorization = null),
        )
        assertProxyAuthenticationRequired(
            requestHttp(port, unavailableOriginPort, authorization = basicAuthorization(Username, "wrong-password")),
        )

        ServerSocket(0, 1, InetAddress.getByName(Loopback)).use { origin ->
            val receivedRequest = CompletableFuture<String>()
            val thread = Thread {
                runCatching {
                    origin.accept().use { accepted ->
                        accepted.soTimeout = SocketTimeoutMillis
                        val request = accepted.getInputStream().readHttpHeaders()
                        accepted.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".encodeToByteArray(),
                        )
                        accepted.getOutputStream().flush()
                        request
                    }
                }.fold(receivedRequest::complete, receivedRequest::completeExceptionally)
            }.apply { start() }

            val response = requestHttp(port, origin.localPort, basicAuthorization(Username, Password))
            assertTrue("expected successful origin response, got $response", response.contains(" 204 "))
            val originRequest = receivedRequest.get(5, TimeUnit.SECONDS)
            assertFalse(
                "Proxy-Authorization reached the origin",
                originRequest.lineSequence().any { it.startsWith("Proxy-Authorization:", ignoreCase = true) },
            )
            thread.join(5_000)
        }
    }

    @Test
    fun mixedProxyAuthenticatesConnectBeforeOpeningOpaqueTunnel() = withMixedProxy { port ->
        val unavailableOriginPort = availablePort()
        assertProxyAuthenticationRequired(
            requestConnect(port, unavailableOriginPort, authorization = null),
        )
        assertProxyAuthenticationRequired(
            requestConnect(port, unavailableOriginPort, basicAuthorization(Username, "wrong-password")),
        )

        ServerSocket(0, 1, InetAddress.getByName(Loopback)).use { origin ->
            val echoed = CompletableFuture<ByteArray>()
            val thread = Thread {
                runCatching {
                    origin.accept().use { accepted ->
                        accepted.soTimeout = SocketTimeoutMillis
                        val request = accepted.getInputStream().readExact(OpaquePayload.size)
                        accepted.getOutputStream().write(request)
                        accepted.getOutputStream().flush()
                        request
                    }
                }.fold(echoed::complete, echoed::completeExceptionally)
            }.apply { start() }

            openProxySocket(port).use { socket ->
                socket.getOutputStream().write(
                    connectRequest(origin.localPort, basicAuthorization(Username, Password)).encodeToByteArray(),
                )
                socket.getOutputStream().flush()
                val response = socket.getInputStream().readHttpHeaders()
                assertTrue("expected CONNECT success, got $response", response.contains(" 200 "))
                socket.getOutputStream().write(OpaquePayload)
                socket.getOutputStream().flush()
                assertArrayEquals(OpaquePayload, socket.getInputStream().readExact(OpaquePayload.size))
            }
            assertArrayEquals(OpaquePayload, echoed.get(5, TimeUnit.SECONDS))
            thread.join(5_000)
        }
    }

    private fun withMixedProxy(block: (Int) -> Unit) {
        val port = availablePort()
        val engine = Androidbridge.newEngine()
        var sessionId = 0L
        try {
            engine.configure(mixedConfig(port))
            sessionId = engine.startConfiguredSessionWithDisabledOutbounds("[]")
            waitForListener(engine, port)
            block(port)
        } finally {
            if (sessionId > 0) {
                engine.stop()
                engine.waitStopped(sessionId, 5_000)
            }
            engine.close()
        }
    }

    private fun waitForListener(engine: Engine, port: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress(Loopback, port), 200) }
                return
            } catch (error: Throwable) {
                lastFailure = error
                Thread.sleep(25)
            }
        }
        throw AssertionError("mixed listener did not start; bridge=${engine.status()}", lastFailure)
    }

    private fun openProxySocket(port: Int): Socket = Socket().apply {
        connect(InetSocketAddress(Loopback, port), 2_000)
        soTimeout = SocketTimeoutMillis
    }

    private fun requestHttp(port: Int, originPort: Int, authorization: String?): String =
        openProxySocket(port).use { socket ->
            val authority = "$Loopback:$originPort"
            val request = buildString {
                append("GET http://$authority/probe HTTP/1.1\r\n")
                append("Host: $authority\r\n")
                authorization?.let { append("Proxy-Authorization: $it\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readHttpHeaders()
        }

    private fun requestConnect(port: Int, originPort: Int, authorization: String?): String =
        openProxySocket(port).use { socket ->
            socket.getOutputStream().write(connectRequest(originPort, authorization).encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readHttpHeaders()
        }

    private fun connectRequest(originPort: Int, authorization: String?): String = buildString {
        val authority = "$Loopback:$originPort"
        append("CONNECT $authority HTTP/1.1\r\n")
        append("Host: $authority\r\n")
        authorization?.let { append("Proxy-Authorization: $it\r\n") }
        append("Proxy-Connection: close\r\n\r\n")
    }

    private fun assertProxyAuthenticationRequired(response: String) {
        assertTrue("expected HTTP 407, got $response", response.contains(" 407 "))
    }

    private fun basicAuthorization(username: String, password: String): String =
        "Basic " + Base64.encodeToString("$username:$password".encodeToByteArray(), Base64.NO_WRAP)

    private fun java.io.InputStream.readHttpHeaders(): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < MaxHeaderBytes) {
            val value = read()
            if (value < 0) error("connection closed before HTTP headers completed")
            bytes += value.toByte()
            val size = bytes.size
            if (
                size >= 4 &&
                bytes[size - 4] == '\r'.code.toByte() &&
                bytes[size - 3] == '\n'.code.toByte() &&
                bytes[size - 2] == '\r'.code.toByte() &&
                bytes[size - 1] == '\n'.code.toByte()
            ) {
                return bytes.toByteArray().toString(Charsets.ISO_8859_1)
            }
        }
        error("HTTP headers exceeded $MaxHeaderBytes bytes")
    }

    private fun java.io.InputStream.readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count < 0) error("connection closed")
            offset += count
        }
        return result
    }

    private fun availablePort(): Int = ServerSocket(0, 1, InetAddress.getByName(Loopback)).use {
        it.localPort
    }

    private fun mixedConfig(port: Int): String = """{
        "inbounds":[{
            "tag":"mixed-auth-test",
            "type":"mixed",
            "address":["$Loopback:$port"],
            "network":["tcp"],
            "username":"$Username",
            "password":"$Password"
        }],
        "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
        "route":{"default_outbound":"direct","rules":[]}
    }""".trimIndent()

    private companion object {
        const val Loopback = "127.0.0.1"
        const val Username = "android-mixed-user"
        const val Password = "android-mixed-password"
        const val SocketTimeoutMillis = 5_000
        const val MaxHeaderBytes = 32 * 1_024
        val OpaquePayload = "opaque-connect-payload".encodeToByteArray()
    }
}
