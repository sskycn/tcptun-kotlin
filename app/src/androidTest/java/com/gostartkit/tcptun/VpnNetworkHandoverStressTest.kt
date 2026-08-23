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
            harness.start()
            harness.waitForRunning()
            controls.enableCellular()
            controls.disableWifi()
            waitForTransport(harness, NetworkCapabilities.TRANSPORT_CELLULAR, "cellular handover")
            harness.updateConnections(harness.lifecyclePlanB)
            harness.applySettings()
            harness.tcping()
            harness.assertRuntimeInvariants()

            controls.enableWifi()
            waitForTransport(harness, NetworkCapabilities.TRANSPORT_WIFI, "Wi-Fi handover")
            harness.assertRuntimeInvariants()

            harness.stop()
            controls.disableWifi()
            controls.enableWifi()
            harness.waitForStopped(timeoutMillis = 30_000)
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun recoveryGapCommandsAreSupersededByStopAndReplacement() =
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
            harness.start(harness.lifecyclePlanA)
            harness.waitForRunning(timeoutMillis = 45_000)
            controls.disableWifi()
            controls.disableCellular()
            assumeTrue("device did not enter a second Recovery gap", waitForRecovery(harness))

            harness.applySettings()
            harness.updateFlowAnalysis()
            harness.start(harness.lifecyclePlanB)
            controls.restoreOneNetwork()
            harness.waitForRunning(timeoutMillis = 45_000)
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
        withSystemEventHarness { harness, controls ->
            harness.start()
            harness.waitForRunning()
            controls.revokeVpnPermission()
            harness.waitUntil("permission revoke cleanup", 20_000) {
                TcptunVpnService.runtimeOwnershipDebugSnapshots().none {
                    it.tunOwned || it.bridgeResourcePhase.ownsResources
                }
            }
            harness.assertRuntimeInvariants()
            harness.assertNoProcessFailureEvidence()
        }

    @Test
    fun permissionRevokeDuringRecoveryReleasesOwnership() =
        withNetworkAndSystemEventHarness { harness, controls ->
            harness.start()
            harness.waitForRunning()
            controls.disableWifi()
            controls.disableCellular()
            assumeTrue("device did not enter Recovery before revoke", waitForRecovery(harness))
            controls.revokeVpnPermission()
            harness.waitUntil("Recovery permission revoke cleanup", 30_000) {
                TcptunVpnService.runtimeOwnershipDebugSnapshots().none {
                    it.tunOwned || it.bridgeResourcePhase.ownsResources
                }
            }
            harness.assertRuntimeInvariants()
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

    private class DeviceControls(private val harness: VpnRuntimeStressHarness) : AutoCloseable {
        private val wifiWasEnabled = "enabled" in harness.runShell("cmd wifi status").lowercase()
        private val cellularWasEnabled = harness.runShell("settings get global mobile_data").trim() == "1"

        fun enableWifi() = shell("svc wifi enable")

        fun disableWifi() = shell("svc wifi disable")

        fun enableCellular() = shell("svc data enable")

        fun disableCellular() = shell("svc data disable")

        fun restoreOneNetwork() {
            if (wifiWasEnabled) enableWifi() else enableCellular()
        }

        fun revokeVpnPermission() =
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
        val TaskIdPattern = Regex("Task\\{[^}]*#(\\d+)[^}]*[^}]*(?:com\\.tcptun\\.client[^}]*)}")
    }
}
