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
        val alicePassword = requireNotNull(arguments.getString(AlicePasswordArgument)).also {
            require(it.isNotEmpty()) { "LAN validation password must not be empty" }
            require(hasValidSocksCredentialSize(it)) { "LAN validation password is too long" }
        }
        val bobPassword = requireNotNull(arguments.getString(BobPasswordArgument)).also {
            require(it.isNotEmpty()) { "LAN validation password must not be empty" }
            require(hasValidSocksCredentialSize(it)) { "LAN validation password is too long" }
        }
        val users = listOf(LocalProxyUser("alice", alicePassword), LocalProxyUser("bob", bobPassword))
        val port = arguments.getString(PortArgument)?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: error("LAN validation port is invalid")

        VpnRuntimeStressHarness().use { harness ->
            clearAckProperties(harness)
            val initial = TcptunVpnService.readRuntimeSettings(harness.context)
            TcptunVpnService.writeRuntimeSettings(
                harness.context,
                initial.copy(
                    powerSavingMode = false,
                    socksPort = port,
                    localProxyProtocol = "socks5",
                    socksListenAll = false,
                    localProxyUsers = users,
                ),
            )
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=LOOPBACK_ONLY_READY")
            waitForAck(harness, LoopbackAckProperty)

            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            TcptunVpnService.writeRuntimeSettings(
                harness.context,
                TcptunVpnService.readRuntimeSettings(harness.context).copy(socksListenAll = true),
            )
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=AUTH_REQUIRED_READY")
            waitForAck(harness, AuthAckProperty)

            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            val persisted = TcptunVpnService.readRuntimeSettings(harness.context)
            assertTrue(persisted.socksListenAll)
            assertTrue("persisted LAN accounts changed", persisted.localProxyUsers == users)
            assertFalse(persisted.localProxyUsers.isEmpty())
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=PERSISTED_RESTART_READY")
            waitForAck(harness, PersistenceAckProperty)

            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            TcptunVpnService.writeRuntimeSettings(
                harness.context,
                TcptunVpnService.readRuntimeSettings(harness.context).copy(localProxyProtocol = "mixed"),
            )
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=MIXED_AUTH_REQUIRED_READY")
            waitForAck(harness, MixedAuthAckProperty)

            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)
            val mixedPersisted = TcptunVpnService.readRuntimeSettings(harness.context)
            assertTrue(mixedPersisted.localProxyProtocol == "mixed")
            assertTrue(mixedPersisted.socksListenAll)
            assertTrue("persisted mixed accounts changed", mixedPersisted.localProxyUsers == users)
            harness.start()
            harness.waitForRunning(timeoutMillis = 30_000)
            println("LAN_PHASE=MIXED_PERSISTED_RESTART_READY")
            waitForAck(harness, MixedPersistenceAckProperty)
            harness.assertNoProcessFailureEvidence()
            clearAckProperties(harness)
        }
    }

    private fun waitForAck(harness: VpnRuntimeStressHarness, property: String) {
        harness.waitUntil("host acknowledgement $property", 45_000) {
            harness.runShell("getprop $property").trim() == AckReady
        }
    }

    private fun clearAckProperties(harness: VpnRuntimeStressHarness) {
        listOf(
            LoopbackAckProperty,
            AuthAckProperty,
            PersistenceAckProperty,
            MixedAuthAckProperty,
            MixedPersistenceAckProperty,
        ).forEach {
            harness.runShell("setprop $it $AckCleared")
        }
    }

    private companion object {
        const val EnabledArgument = "lanAuthValidationEnabled"
        const val AlicePasswordArgument = "lanAuthValidationAlicePassword"
        const val BobPasswordArgument = "lanAuthValidationBobPassword"
        const val PortArgument = "lanAuthValidationPort"
        const val LoopbackAckProperty = "debug.tcptun.lan.lb"
        const val AuthAckProperty = "debug.tcptun.lan.auth"
        const val PersistenceAckProperty = "debug.tcptun.lan.persist"
        const val MixedAuthAckProperty = "debug.tcptun.lan.mixed"
        const val MixedPersistenceAckProperty = "debug.tcptun.lan.mixpersist"
        const val AckReady = "ready"
        const val AckCleared = "none"
    }
}
