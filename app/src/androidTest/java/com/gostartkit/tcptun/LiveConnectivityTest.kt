package com.tcptun.client

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Opt-in end-to-end test for a real profile. Pass the profile through the
 * URL-safe Base64 in the `liveProfileUriBase64` instrumentation argument; it
 * is deliberately never logged or stored by this test.
 */
@RunWith(AndroidJUnit4::class)
class LiveConnectivityTest {
    @Test
    fun profileSupportsNativeTunAndRepeatedVpnLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val encodedProfile = InstrumentationRegistry.getArguments().getString(PROFILE_ARGUMENT).orEmpty()
        val profileUri = runCatching {
            String(Base64.decode(encodedProfile, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault("")
        assumeTrue("live profile argument was not supplied", profileUri.isNotBlank())

        val profile = ProfileUriCodec.decode(profileUri).getOrElse {
            throw AssertionError("live profile could not be decoded")
        }
        assertNull("live profile is invalid", profile.validate())

        val originalSettings = TcptunVpnService.readRuntimeSettings(context)
        val socksPort = availablePort()
        runShell("appops set ${context.packageName} ACTIVATE_VPN allow")
        context.stopService(Intent(context, TcptunVpnService::class.java))
        TcptunVpnService.writeRuntimeSettings(
            context,
            originalSettings.copy(
                powerSavingMode = false,
                socksPort = socksPort,
                socksListenAll = false,
                socksUsername = "",
                socksPassword = "",
            ),
        )

        try {
            assertNull(VpnService.prepare(context))
            repeat(2) { cycle ->
                TcptunState.clearLogs()
                ContextCompat.startForegroundService(context, TcptunVpnService.startIntent(context, profile))
                waitUntil("VPN cycle ${cycle + 1} reaches Running") { TcptunState.status == "Running" }
                waitUntil("native TUN bridge reaches Running") {
                    TcptunState.diagnostics.bridgeStatus == "Running"
                }

                verifyExactOutbound(context)
                verifyTunDnsAndFakeIp()
                assertEquals(
                    204,
                    fetchHttpsStatus(
                        connectThroughSocks(socksPort, "connectivitycheck.gstatic.com", 443),
                        "connectivitycheck.gstatic.com",
                        443,
                        "/generate_204",
                        IO_TIMEOUT_MS,
                    ),
                )
                assertHttpSuccess(
                    fetchHttpsStatus(
                        connectThroughSocks(socksPort, "1.1.1.1", 443),
                        "cloudflare-dns.com",
                        443,
                        "/",
                        IO_TIMEOUT_MS,
                    ),
                )
                completeTlsHandshake(
                    connectThroughSocks(socksPort, "2606:4700:4700::1111", 443),
                    "cloudflare-dns.com",
                    443,
                    IO_TIMEOUT_MS,
                )
                context.startService(TcptunVpnService.stopIntent(context))
                waitUntil("VPN cycle ${cycle + 1} reaches Stopped", 15_000) {
                    TcptunState.status == "Stopped"
                }
                Thread.sleep(300)
            }
        } finally {
            context.startService(TcptunVpnService.stopIntent(context))
            waitUntil("VPN cleanup", 10_000) { TcptunState.status != "Stopping" }
            TcptunVpnService.writeRuntimeSettings(context, originalSettings)
            runShell("appops set ${context.packageName} ACTIVATE_VPN default")
        }
    }

    private fun verifyExactOutbound(context: android.content.Context) {
        val requestId = TcptunState.beginTcping("live connectivity", 1)
        context.startService(
            TcptunVpnService.tcpingOutboundsIntent(
                context,
                requestId,
                "live connectivity",
                "connectivitycheck.gstatic.com",
                443,
            ),
        )
        waitUntil("outbound TCP probe", 20_000) {
            val tcping = TcptunState.state.value.tcping
            tcping.requestId == requestId && !tcping.running
        }
        val tcping = TcptunState.state.value.tcping
        assertTrue("outbound TCP probe failed: ${tcping.error}", tcping.error.isBlank())
        assertEquals(1, tcping.results.size)
        assertTrue(
            "outbound TCP probe failed: ${tcping.results.single().error}",
            tcping.results.single().elapsedMs != null,
        )
    }

    private fun verifyTunDnsAndFakeIp() {
        val result = runShell(
            "/system/bin/curl -4 -sS -o /dev/null --connect-timeout 10 --max-time 20 " +
                "-w '%{remote_ip}|%{http_code}' https://www.cloudflare.com/",
        ).trim()
        val parts = result.substringAfterLast('\n').split('|')
        assertEquals("unexpected curl result: $result", 2, parts.size)
        assertTrue("DNS did not return a fake IPv4 address: $result", parts[0].startsWith("198.18."))
        assertTrue("TUN request failed after fake-IP restoration: $result", parts[1].toIntOrNull() in 200..399)
    }

    private fun connectThroughSocks(port: Int, host: String, targetPort: Int): Socket {
        val socket = Socket()
        socket.soTimeout = IO_TIMEOUT_MS
        socket.connect(InetSocketAddress(loopbackV4(), port), CONNECT_TIMEOUT_MS)
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            val greeting = readExactly(input, 2)
            check(greeting[0].toInt() == 5 && greeting[1].toInt() == 0) { "SOCKS authentication failed" }

            output.write(socksRequest(command = 1, host = host, port = targetPort))
            output.flush()
            readSocksReply(socket)
            return socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun socksRequest(command: Int, host: String, port: Int): ByteArray {
        return byteArrayOf(5, command.toByte(), 0) + socksAddress(host, port)
    }

    private fun socksAddress(host: String, port: Int): ByteArray {
        val portBytes = byteArrayOf((port ushr 8).toByte(), port.toByte())
        if (host.contains(':')) {
            val address = InetAddress.getByName(host)
            require(address is Inet6Address) { "invalid IPv6 target" }
            return byteArrayOf(4) + address.address + portBytes
        }
        if (host.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) {
            return byteArrayOf(1) + InetAddress.getByName(host).address + portBytes
        }
        val encoded = host.toByteArray(Charsets.US_ASCII)
        require(encoded.size in 1..255) { "invalid SOCKS domain target" }
        return byteArrayOf(3, encoded.size.toByte()) + encoded + portBytes
    }

    private fun readSocksReply(socket: Socket): InetSocketAddress {
        val input = socket.getInputStream()
        val header = readExactly(input, 4)
        check(header[0].toInt() == 5) { "invalid SOCKS response version" }
        check(header[1].toInt() == 0) { "SOCKS request failed with code ${header[1].toInt() and 0xff}" }
        val address = when (header[3].toInt() and 0xff) {
            1 -> InetAddress.getByAddress(readExactly(input, 4))
            4 -> InetAddress.getByAddress(readExactly(input, 16))
            3 -> {
                val length = readExactly(input, 1)[0].toInt() and 0xff
                InetAddress.getByName(String(readExactly(input, length), Charsets.US_ASCII))
            }
            else -> error("unsupported SOCKS address type")
        }
        val portBytes = readExactly(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        return InetSocketAddress(address, port)
    }

    private fun readExactly(input: java.io.InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw EOFException("unexpected end of stream")
            offset += count
        }
        return result
    }

    private fun assertHttpSuccess(status: Int) {
        assertFalse("unexpected HTTP status $status", status >= 400)
    }

    private fun runShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for $label; status=${TcptunState.status}, " +
                        "bridge=${TcptunState.diagnostics.bridgeStatus}, error=${TcptunState.lastError}",
                )
            }
            Thread.sleep(50)
        }
    }

    private fun availablePort(): Int = ServerSocket(0, 1, loopbackV4()).use {
        it.localPort
    }

    private fun loopbackV4(): InetAddress = InetAddress.getByName("127.0.0.1")

    private companion object {
        const val PROFILE_ARGUMENT = "liveProfileUriBase64"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_MS = 15_000
    }
}
