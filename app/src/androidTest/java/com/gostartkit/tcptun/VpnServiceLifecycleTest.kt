package com.tcptun.client

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class VpnServiceLifecycleTest {
    @Test
    fun serviceCanStartStopAndCreateFreshAarEngineRepeatedly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalSettings = TcptunVpnService.readRuntimeSettings(context)
        val socksPort = availablePort()
        val profile = AppConfig(
            id = "vpn-service-lifecycle",
            name = "VPN service lifecycle",
            udp = false,
            rawConfigJson = """{
                "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
                "route":{"default_outbound":"direct"}
            }""".trimIndent(),
        )

        runShell("appops set ${context.packageName} ACTIVATE_VPN allow")
        context.stopService(Intent(context, TcptunVpnService::class.java))
        TcptunVpnService.writeRuntimeSettings(
            context,
            originalSettings.copy(
                udpEnabled = false,
                powerSavingMode = false,
                socksPort = socksPort,
                socksListenAll = false,
                routeExternalSources = false,
                directFirst = false,
            ),
        )
        try {
            assertNull(VpnService.prepare(context))
            repeat(2) {
                TcptunState.clearLogs()
                ContextCompat.startForegroundService(context, TcptunVpnService.startIntent(context, profile))
                waitUntil("VPN reaches Running") { TcptunState.status == "Running" }
                assertTrue(HevSocks5Tunnel.isRunning())
                assertTrue(TcptunState.logs.none { it.contains("restarting tcptun bridge transaction: underlying network") })
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort), 2_000)
                }

                context.startService(TcptunVpnService.stopIntent(context))
                waitUntil("VPN reaches Stopped") {
                    TcptunState.status == "Stopped" && !HevSocks5Tunnel.isRunning()
                }
                assertEquals("Stopped", TcptunState.diagnostics.bridgeStatus)
                Thread.sleep(300)
            }
        } finally {
            context.startService(TcptunVpnService.stopIntent(context))
            waitUntil("VPN cleanup", timeoutMillis = 10_000) { !HevSocks5Tunnel.isRunning() }
            TcptunVpnService.writeRuntimeSettings(context, originalSettings)
            runShell("appops set ${context.packageName} ACTIVATE_VPN default")
        }
    }

    private fun runShell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 25_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for $label; status=${TcptunState.status}, error=${TcptunState.lastError}")
            }
            Thread.sleep(50)
        }
    }

    private fun availablePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }
}
