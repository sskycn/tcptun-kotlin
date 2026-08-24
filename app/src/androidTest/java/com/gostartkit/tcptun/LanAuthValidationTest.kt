package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanAuthValidationTest {
    @Test
    fun nonLoopbackListenerRequiresConfiguredAuthentication() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("LAN auth validation is opt-in", arguments.getString(EnabledArgument).toBoolean())
        val password = requireNotNull(arguments.getString(PasswordArgument)).also {
            require(it.isNotEmpty()) { "LAN validation password must not be empty" }
            require(hasValidSocksCredentialSize(it)) { "LAN validation password is too long" }
        }
        val port = arguments.getString(PortArgument)?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: error("LAN validation port is invalid")

        VpnRuntimeStressHarness().use { harness ->
            clearAckFiles(harness)
            val initial = TcptunVpnService.readRuntimeSettings(harness.context)
            TcptunVpnService.writeRuntimeSettings(
                harness.context,
                initial.copy(
                    powerSavingMode = false,
                    socksPort = port,
                    localProxyProtocol = "socks5",
                    socksListenAll = false,
                    socksUsername = "",
                    socksPassword = password,
                ),
            )
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=LOOPBACK_ONLY_READY")
            waitForAck(harness, LoopbackAck)

            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            TcptunVpnService.writeRuntimeSettings(
                harness.context,
                TcptunVpnService.readRuntimeSettings(harness.context).copy(socksListenAll = true),
            )
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=AUTH_REQUIRED_READY")
            waitForAck(harness, AuthAck)

            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            val persisted = TcptunVpnService.readRuntimeSettings(harness.context)
            assertTrue(persisted.socksListenAll)
            assertTrue("persisted LAN password changed", persisted.socksPassword == password)
            assertFalse(persisted.socksPassword.isEmpty())
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=PERSISTED_RESTART_READY")
            waitForAck(harness, PersistenceAck)
            harness.assertNoProcessFailureEvidence()
            clearAckFiles(harness)
        }
    }

    private fun waitForAck(harness: VpnRuntimeStressHarness, path: String) {
        harness.waitUntil("host acknowledgement $path", 45_000) {
            harness.runShell("test -f $path && echo ready").trim() == "ready"
        }
    }

    private fun clearAckFiles(harness: VpnRuntimeStressHarness) {
        harness.runShell("rm -f $LoopbackAck $AuthAck $PersistenceAck")
    }

    private companion object {
        const val EnabledArgument = "lanAuthValidationEnabled"
        const val PasswordArgument = "lanAuthValidationPassword"
        const val PortArgument = "lanAuthValidationPort"
        const val LoopbackAck = "/data/local/tmp/tcptun-lan-loopback-ack"
        const val AuthAck = "/data/local/tmp/tcptun-lan-auth-ack"
        const val PersistenceAck = "/data/local/tmp/tcptun-lan-persistence-ack"
    }
}
