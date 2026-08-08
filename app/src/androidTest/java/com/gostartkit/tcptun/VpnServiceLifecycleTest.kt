package com.tcptun.client

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val originalProfiles = ProfileStore.load(context)
        val socksPort = availablePort()
        val directTarget = "127.0.0.1"
        val profile = AppConfig(
            id = "vpn-service-lifecycle",
            name = "VPN service lifecycle",
            rawConfigJson = """{
                "outbounds":[{"tag":"direct","type":"direct","network":["tcp"]}],
                "route":{
                    "default_outbound":"direct",
                    "rules":[{
                        "app":{
                            "platforms":["android"],
                            "attributes":{"packages":["${context.packageName}"]}
                        },
                        "outbound":"direct"
                    }]
                }
            }""".trimIndent(),
        )

        runShell("appops set ${context.packageName} ACTIVATE_VPN allow")
        context.stopService(Intent(context, TcptunVpnService::class.java))
        TcptunVpnService.writeRuntimeSettings(
            context,
            originalSettings.copy(
                powerSavingMode = false,
                socksPort = socksPort,
                socksListenAll = false,
                flowAnalysisApp = "",
            ),
        )
        ProfileStore.save(
            context,
            ProfilesState(profiles = listOf(profile), activeIds = setOf(profile.id)),
        ).getOrThrow()
        try {
            assertNull(VpnService.prepare(context))
            assertEquals("", TcptunVpnService.readRuntimeSettings(context).flowAnalysisApp)
            repeat(2) { cycle ->
                TcptunState.clearLogs()
                ContextCompat.startForegroundService(context, TcptunVpnService.startIntent(context, profile))
                waitUntil("VPN reaches Running") { TcptunState.status == "Running" }
                waitUntil("native TUN bridge reaches Running") {
                    TcptunState.diagnostics.bridgeStatus == "Running"
                }
                assertEquals("", TcptunState.state.value.flowAnalysisApp)
                if (cycle == 0) {
                    val sessionId = TcptunState.diagnostics.bridgeSessionId
                    val runningSettings = TcptunVpnService.readRuntimeSettings(context)
                    TcptunVpnService.writeRuntimeSettings(
                        context,
                        runningSettings.copy(flowAnalysisApp = "com.android.settings"),
                    )
                    context.startService(TcptunVpnService.updateFlowAnalysisIntent(context))
                    waitUntil("flow analysis switches without restart") {
                        TcptunState.logs.any {
                            it == "flow analysis switched without VPN restart: com.android.settings"
                        }
                    }
                    assertEquals("Running", TcptunState.status)
                    assertEquals(sessionId, TcptunState.diagnostics.bridgeSessionId)

                    TcptunVpnService.writeRuntimeSettings(
                        context,
                        runningSettings.copy(flowAnalysisApp = ""),
                    )
                    context.startService(TcptunVpnService.updateFlowAnalysisIntent(context))
                    waitUntil("flow analysis disables without restart") {
                        TcptunState.logs.any {
                            it == "flow analysis switched without VPN restart: disabled"
                        }
                    }
                    assertEquals(sessionId, TcptunState.diagnostics.bridgeSessionId)
                }
                ServerSocket(0, 1, InetAddress.getByName(directTarget)).use { directServer ->
                    directServer.soTimeout = 5_000
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort), 2_000)
                        socket.soTimeout = 5_000
                        try {
                            assertSocks5Connect(socket, directTarget, directServer.localPort)
                            directServer.accept().use { accepted ->
                                assertEquals(directTarget, accepted.inetAddress.hostAddress)
                            }
                        } catch (error: Throwable) {
                            throw AssertionError(
                                "SOCKS direct connect failed; status=${TcptunState.status}, " +
                                    "bridge=${TcptunState.diagnostics}, logs=${TcptunState.logs.takeLast(40)}",
                                error,
                            )
                        }
                    }
                }
                context.startService(TcptunVpnService.stopIntent(context))
                waitUntil("VPN reaches Stopped promptly", timeoutMillis = 5_000) {
                    TcptunState.status == "Stopped"
                }
                assertEquals("Stopped", TcptunState.diagnostics.bridgeStatus)
                // After the first cycle, start again as soon as Stopped is
                // published. This covers same-instance reuse and the window
                // where Android replaces a service during native teardown.
                if (cycle != 0) {
                    waitUntil("VPN destroy cleanup completes", timeoutMillis = 10_000) {
                        TcptunState.logs.any { it == "tcptun destroy cleanup completed" }
                    }
                }
            }
        } finally {
            context.startService(TcptunVpnService.stopIntent(context))
            waitUntil("VPN cleanup", timeoutMillis = 10_000) { TcptunState.status != "Stopping" }
            TcptunVpnService.writeRuntimeSettings(context, originalSettings)
            ProfileStore.save(context, originalProfiles)
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
                throw AssertionError(
                    "Timed out waiting for $label; status=${TcptunState.status}, " +
                        "error=${TcptunState.lastError}, logs=${TcptunState.logs.takeLast(40)}",
                )
            }
            Thread.sleep(50)
        }
    }

    private fun availablePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }

    private fun assertSocks5Connect(socket: Socket, host: String, port: Int) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        assertEquals(0x05, input.read())
        assertEquals(0x00, input.read())

        val address = InetAddress.getByName(host).address
        val addressType = if (address.size == 4) 0x01 else 0x04
        output.write(byteArrayOf(0x05, 0x01, 0x00, addressType.toByte()))
        output.write(address)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()

        assertEquals(0x05, input.read())
        assertEquals("SOCKS direct connect failed", 0x00, input.read())
    }
}
