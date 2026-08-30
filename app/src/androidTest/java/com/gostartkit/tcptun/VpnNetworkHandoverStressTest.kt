package com.tcptun.client

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnNetworkHandoverStressTest {
    @Test
    fun wifiCellularRoundTripAndCallbackDuringStopPreserveOwnership() =
        withNetworkHarness { harness, controls ->
            assumeTrue(
                "device has no telephony feature",
                harness.context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            )
            controls.enableWifi()
            waitForTransport(harness, NetworkCapabilities.TRANSPORT_WIFI, "initial Wi-Fi")
            harness.start()
            harness.waitForRunning()
            val wifiEpoch = harness.activeOwnershipSnapshot().bridgeEpoch
            controls.enableCellular()
            controls.disableWifi()
            waitForTransport(harness, NetworkCapabilities.TRANSPORT_CELLULAR, "cellular handover")
            waitForReplacement(harness, wifiEpoch)
            harness.updateConnectionsAndWait(harness.lifecyclePlanB)
            harness.applySettings()
            harness.tcping()
            harness.waitForRunning()
            harness.assertRuntimeInvariants()

            val cellularEpoch = harness.activeOwnershipSnapshot().bridgeEpoch
            controls.enableWifi()
            waitForTransport(harness, NetworkCapabilities.TRANSPORT_WIFI, "Wi-Fi handover")
            waitForReplacement(harness, cellularEpoch)
            harness.assertRuntimeInvariants()

            harness.stop()
            controls.disableWifi()
            controls.enableWifi()
            harness.waitForStopped(timeoutMillis = 30_000)
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun recoveryGapCommandsAreSupersededByStop() =
        withNetworkHarness { harness, controls ->
            harness.start()
            harness.waitForRunning()
            controls.disableWifi()
            controls.disableCellular()
            assumeTrue("device did not enter a Recovery gap", waitForRecovery(harness))

            harness.applySettings()
            harness.updateFlowAnalysis()
            harness.tcping()
            harness.stop()
            harness.waitForStopped(timeoutMillis = 30_000)

            controls.restoreOneNetwork()
            assertRemainsStoppedAfterNetworkRestore(harness)
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun recoveryGapReplacementWinsAfterNetworkRestore() =
        withNetworkHarness { harness, controls ->
            harness.start(harness.lifecyclePlanA)
            harness.waitForRunning()
            controls.disableWifi()
            controls.disableCellular()
            assumeTrue("device did not enter Recovery before replacement", waitForRecovery(harness))

            harness.applySettings()
            harness.updateFlowAnalysis()
            harness.tcping()
            harness.start(harness.lifecyclePlanB)
            controls.restoreOneNetwork()
            harness.waitUntil("replacement plan B Running", 45_000) {
                TcptunState.status == VpnStatus.Running &&
                    ProfileStore.load(harness.context).activeIds == harness.lifecyclePlanB.activeIds
            }
            harness.assertRuntimeInvariants()
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun taskRemovalKeepsRunningVpnOwned() = withSystemEventHarness { harness, _ ->
        harness.start()
        harness.waitForRunning()
        harness.context.startActivity(
            Intent(harness.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(1_000)
        val tasks = harness.runShell("dumpsys activity activities")
        val taskId = TaskIdPattern.findAll(tasks)
            .firstOrNull { harness.context.packageName in it.value }
            ?.groupValues
            ?.getOrNull(1)
        assumeTrue("target app task ID was unavailable", !taskId.isNullOrBlank())

        harness.runShell("am task remove $taskId")
        Thread.sleep(1_000)

        assertEquals(VpnStatus.Running, TcptunState.status)
        harness.assertRuntimeInvariants()
        harness.assertNoProcessFailureEvidence()
    }

    @Test
    fun permissionRevokeDuringRunningReleasesOwnership() =
        withSystemEventHarness { harness, _ ->
            assumeTrue(
                "real system VPN revoke is manual and requires runtimeStressRevokeMode=system",
                revokeMode() == RevokeModeSystem,
            )
            harness.start()
            harness.waitForRunning()
            println("VPN_REVOKE_ACTION_REQUIRED: revoke VPN authorization in system Settings now")
            harness.waitForStopped(timeoutMillis = 120_000)
            harness.assertRuntimeInvariants()
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun permissionRevokeDuringRecoveryReleasesOwnership() =
        withNetworkAndSystemEventHarness { harness, controls ->
            assumeTrue(
                "real system VPN revoke is manual and requires runtimeStressRevokeMode=system",
                revokeMode() == RevokeModeSystem,
            )
            harness.start()
            harness.waitForRunning()
            controls.disableWifi()
            controls.disableCellular()
            assumeTrue("device did not enter Recovery before revoke", waitForRecovery(harness))
            println("VPN_REVOKE_ACTION_REQUIRED: revoke VPN authorization in system Settings now")
            harness.waitForStopped(timeoutMillis = 120_000)
            harness.assertRuntimeInvariants()
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun appOpsAuthorizationChangeRecordsBehaviorWithoutAssumingFrameworkRevoke() =
        withSystemEventHarness { harness, controls ->
            assumeTrue(
                "AppOps diagnostic requires runtimeStressRevokeMode=appops",
                revokeMode() == RevokeModeAppOps,
            )
            harness.start()
            harness.waitForRunning()
            val logCountBefore = TcptunState.logs.size
            controls.setVpnAppOpIgnored()
            Thread.sleep(2_000)
            harness.assertRuntimeInvariants()
            val callbackObserved = TcptunState.logs.drop(logCountBefore).any {
                it.contains("VPN permission revoked")
            }
            val snapshots = TcptunVpnService.runtimeOwnershipDebugSnapshots()
            println(
                "VPN_APPOPS_OBSERVATION callbackObserved=$callbackObserved " +
                    "status=${TcptunState.status} ownership=$snapshots",
            )
            harness.assertNoProcessFailureEvidence()
        }

    private fun withNetworkHarness(block: (VpnRuntimeStressHarness, DeviceControls) -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "network handover stress is opt-in",
            arguments.getString(NetworkControlArgument).toBoolean(),
        )
        VpnRuntimeStressHarness().use { harness ->
            DeviceControls(harness).use { controls -> block(harness, controls) }
        }
    }

    private fun withSystemEventHarness(block: (VpnRuntimeStressHarness, DeviceControls) -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "system event stress is opt-in",
            arguments.getString(SystemEventsArgument).toBoolean(),
        )
        VpnRuntimeStressHarness().use { harness ->
            DeviceControls(harness).use { controls -> block(harness, controls) }
        }
    }

    private fun withNetworkAndSystemEventHarness(
        block: (VpnRuntimeStressHarness, DeviceControls) -> Unit,
    ) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Recovery revoke requires network and system-event controls",
            arguments.getString(NetworkControlArgument).toBoolean() &&
                arguments.getString(SystemEventsArgument).toBoolean(),
        )
        VpnRuntimeStressHarness().use { harness ->
            DeviceControls(harness).use { controls -> block(harness, controls) }
        }
    }

    private fun waitForReplacement(harness: VpnRuntimeStressHarness, previousEpoch: Long) {
        // Includes the production 30-second restart cooldown. Checking only
        // Running could accidentally validate the old listener during settle.
        harness.waitUntil("handover replacement listener", 45_000) {
            TcptunState.status == VpnStatus.Running && TcptunState.state.value.connectionsReady &&
                harness.activeOwnershipSnapshot().bridgeEpoch > previousEpoch
        }
        harness.assertLocalProxyReady()
    }

    private fun waitForTransport(
        harness: VpnRuntimeStressHarness,
        transport: Int,
        label: String,
    ) {
        val connectivity = harness.context.getSystemService(ConnectivityManager::class.java)
        harness.waitUntil(label, 30_000) {
            val network = connectivity?.activeNetwork ?: return@waitUntil false
            connectivity.getNetworkCapabilities(network)?.hasTransport(transport) == true
        }
    }

    private fun waitForRecovery(harness: VpnRuntimeStressHarness): Boolean {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            harness.assertRuntimeInvariants()
            if (TcptunVpnService.runtimeOwnershipDebugSnapshots().any {
                    it.activeServiceOwner && it.runtimePhase == "Recovering"
                }
            ) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun assertRemainsStoppedAfterNetworkRestore(harness: VpnRuntimeStressHarness) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            harness.assertRuntimeInvariants()
            assertEquals(VpnStatus.Stopped, TcptunState.status)
            check(TcptunVpnService.runtimeOwnershipDebugSnapshots().all {
                !it.tunOwned &&
                    !it.bridgeResourcePhase.ownsResources &&
                    !it.teardownPending &&
                    it.leaseOwner == 0L
            })
            Thread.sleep(100)
        }
    }

    private class DeviceControls(private val harness: VpnRuntimeStressHarness) : AutoCloseable {
        private val wifiWasEnabled = "enabled" in harness.runShell("cmd wifi status").lowercase()
        private val cellularWasEnabled = harness.runShell("settings list global")
            .lineSequence()
            .map { it.trim() }
            .any { line ->
                val key = line.substringBefore('=', missingDelimiterValue = "")
                val value = line.substringAfter('=', missingDelimiterValue = "")
                key.matches(Regex("mobile_data[0-9]*")) && value == "1"
            }

        fun enableWifi() {
            shell("svc wifi enable")
            if (InstrumentationRegistry.getArguments().getString("runtimeStressEmulatorWifiReconnect").toBoolean()) {
                check(android.os.Build.PRODUCT.startsWith("sdk_gphone")) {
                    "AndroidWifi reconnect is restricted to the emulator fixture"
                }
                // Some emulator images leave the saved test AP disconnected
                // after svc wifi enable; request association explicitly.
                shell("cmd wifi connect-network AndroidWifi open")
            }
        }

        fun disableWifi() = shell("svc wifi disable")

        fun enableCellular() = shell("svc data enable")

        fun disableCellular() = shell("svc data disable")

        fun restoreOneNetwork() {
            if (wifiWasEnabled) enableWifi() else enableCellular()
        }

        fun setVpnAppOpIgnored() =
            shell("appops set ${harness.context.packageName} ACTIVATE_VPN ignore")

        fun allowVpnPermission() =
            shell("appops set ${harness.context.packageName} ACTIVATE_VPN allow")

        override fun close() {
            if (wifiWasEnabled) enableWifi() else disableWifi()
            if (cellularWasEnabled) enableCellular() else disableCellular()
            allowVpnPermission()
        }

        private fun shell(command: String) {
            harness.runShell(command)
            Thread.sleep(500)
        }
    }

    private companion object {
        const val NetworkControlArgument = "runtimeStressNetworkControl"
        const val SystemEventsArgument = "runtimeStressSystemEvents"
        const val RevokeModeArgument = "runtimeStressRevokeMode"
        const val RevokeModeAppOps = "appops"
        const val RevokeModeSystem = "system"
        val TaskIdPattern = Regex("Task\\{[^}]*#(\\d+)[^}]*com\\.tcptun\\.client[^}]*\\}")
    }

    private fun revokeMode(): String =
        InstrumentationRegistry.getArguments().getString(RevokeModeArgument, RevokeModeAppOps)
}
