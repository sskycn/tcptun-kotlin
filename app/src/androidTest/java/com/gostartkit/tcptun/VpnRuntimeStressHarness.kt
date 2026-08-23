package com.tcptun.client

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal class VpnRuntimeStressHarness : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    private val originalSettings = TcptunVpnService.readRuntimeSettings(context)
    private val originalProfiles = ProfileStore.load(context)
    private val startedAtMillis = System.currentTimeMillis()
    private var settingsToggle = false

    val profileA = directProfile("runtime-stress-a", "Runtime stress A")
    val profileB = directProfile("runtime-stress-b", "Runtime stress B")
    val planA = ProfileRunPlan(listOf(profileA))
    val planB = ProfileRunPlan(listOf(profileB))
    val planAB = ProfileRunPlan(listOf(profileA, profileB))

    init {
        runShell("logcat -c")
        runShell("appops set ${context.packageName} ACTIVATE_VPN allow")
        context.stopService(Intent(context, TcptunVpnService::class.java))
        TcptunVpnService.writeRuntimeSettings(
            context,
            originalSettings.copy(
                powerSavingMode = false,
                socksPort = availablePort(),
                socksListenAll = false,
                socksUsername = "",
                socksPassword = "",
                flowAnalysisApp = "",
            ),
        )
        ProfileStore.save(
            context,
            ProfilesState(
                profiles = listOf(profileA, profileB),
                activeIds = setOf(profileA.id),
            ),
        ).getOrThrow()
        assertEquals(null, VpnService.prepare(context))
    }

    fun start(plan: ProfileRunPlan = planA) {
        ContextCompat.startForegroundService(context, TcptunVpnService.startIntent(context, plan))
    }

    fun stop() {
        context.startService(TcptunVpnService.stopIntent(context))
    }

    fun updateConnections(plan: ProfileRunPlan) {
        context.startService(TcptunVpnService.updateOutboundsIntent(context, plan))
    }

    fun applySettings() {
        settingsToggle = !settingsToggle
        val current = TcptunVpnService.readRuntimeSettings(context)
        TcptunVpnService.writeRuntimeSettings(
            context,
            current.copy(logLevel = if (settingsToggle) "debug" else "info"),
        )
        context.startService(TcptunVpnService.applyRuntimeSettingsIntent(context))
    }

    fun updateFlowAnalysis() {
        val current = TcptunVpnService.readRuntimeSettings(context)
        val next = if (current.flowAnalysisApp.isBlank()) "com.android.settings" else ""
        TcptunVpnService.writeRuntimeSettings(context, current.copy(flowAnalysisApp = next))
        context.startService(TcptunVpnService.updateFlowAnalysisIntent(context))
    }

    fun tcping() {
        val requestId = TcptunState.beginTcping("runtime stress", 1)
        context.startService(
            TcptunVpnService.tcpingOutboundsIntent(
                context = context,
                requestId = requestId,
                targetLabel = "runtime stress",
                host = "127.0.0.1",
                port = 9,
            ),
        )
    }

    fun refreshClientIps() {
        context.startService(TcptunVpnService.refreshClientIpsIntent(context))
    }

    fun waitForRunning(timeoutMillis: Long = 25_000) = waitUntil("VPN Running", timeoutMillis) {
        TcptunState.status == VpnStatus.Running && TcptunState.state.value.connectionsReady
    }

    fun waitForStopped(timeoutMillis: Long = 15_000) = waitUntil("VPN Stopped", timeoutMillis) {
        TcptunState.status == VpnStatus.Stopped &&
            TcptunVpnService.runtimeOwnershipDebugSnapshots().none {
                it.tunOwned || it.bridgeResourcePhase.ownsResources || it.teardownPending
            }
    }

    fun waitUntil(
        label: String,
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            assertRuntimeInvariants()
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for $label; ${safeSnapshotDescription()}")
            }
            Thread.sleep(50)
        }
        assertRuntimeInvariants()
    }

    fun assertRuntimeInvariants() {
        val deadline = System.currentTimeMillis() + InvariantSettleMillis
        var lastFailure: AssertionError? = null
        do {
            try {
                validateRuntimeInvariants()
                return
            } catch (failure: AssertionError) {
                lastFailure = failure
                Thread.sleep(10)
            }
        } while (System.currentTimeMillis() < deadline)
        throw requireNotNull(lastFailure)
    }

    private fun validateRuntimeInvariants() {
        val snapshots = TcptunVpnService.runtimeOwnershipDebugSnapshots()
        assertEquals(snapshots.size, snapshots.map { it.serviceInstanceId }.distinct().size)
        assertTrue("multiple active Service owners: $snapshots", snapshots.count { it.activeServiceOwner } <= 1)
        assertTrue("multiple Android TUN owners: $snapshots", snapshots.count { it.tunOwned } <= 1)
        assertTrue(
            "multiple native bridge resource owners: $snapshots",
            snapshots.count { it.bridgeResourcePhase.ownsResources } <= 1,
        )
        val leaseOwners = snapshots.map { it.leaseOwner }.filter { it != 0L }.distinct()
        assertTrue("multiple runtime lease owners: $snapshots", leaseOwners.size <= 1)
        snapshots.filter { it.tunOwned || it.bridgeResourcePhase.ownsResources }.forEach { owner ->
            assertEquals("resource owner must hold runtime lease: $snapshots", owner.serviceInstanceId, owner.leaseOwner)
        }
        if (TcptunState.state.value.connectionsReady) {
            val active = snapshots.singleOrNull { it.activeServiceOwner && !it.destroyed }
            assertNotNull("connectionsReady has no active Service owner: $snapshots", active)
            requireNotNull(active)
            assertEquals("Running", active.runtimePhase)
            assertEquals(BridgeResourcePhase.SessionOwned, active.bridgeResourcePhase)
            assertTrue(active.bridgeEpoch > 0L)
            assertTrue(active.tunOwned)
            assertEquals(active.serviceInstanceId, active.leaseOwner)
        }
    }

    fun assertNoProcessFailureEvidence() {
        val logcat = runShell("logcat -d -v brief -t 4000")
        val processCrash = "Process: ${context.packageName}" in logcat && "FATAL EXCEPTION" in logcat
        assertFalse("target process crashed during stress", processCrash)
        assertFalse(
            "foreground service start deadline was missed",
            "ForegroundServiceDidNotStartInTimeException" in logcat,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val failures = activityManager?.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .orEmpty()
                .filter { it.timestamp >= startedAtMillis }
                .filter {
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                        it.reason == ApplicationExitInfo.REASON_CRASH ||
                        it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                }
            assertTrue("process exit failure recorded during stress: $failures", failures.isEmpty())
        }
    }

    fun runShell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }

    override fun close() {
        try {
            stop()
            waitUntil("final VPN cleanup", 20_000) {
                TcptunState.status != VpnStatus.Stopping &&
                    TcptunVpnService.runtimeOwnershipDebugSnapshots().none {
                        it.tunOwned || it.bridgeResourcePhase.ownsResources || it.teardownPending
                    }
            }
        } finally {
            TcptunVpnService.writeRuntimeSettings(context, originalSettings)
            ProfileStore.save(context, originalProfiles)
            runShell("appops set ${context.packageName} ACTIVATE_VPN default")
        }
    }

    private fun safeSnapshotDescription(): String =
        "status=${TcptunState.status}, ownership=${TcptunVpnService.runtimeOwnershipDebugSnapshots()}"

    private fun availablePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun directProfile(id: String, name: String) = AppConfig(
        id = id,
        name = name,
        rawConfigJson = """{
            "outbounds":[{"tag":"direct","type":"direct","network":["tcp","udp"]}],
            "route":{"default_outbound":"direct"}
        }""".trimIndent(),
    )

    private companion object {
        const val InvariantSettleMillis = 500L
    }
}

internal val BridgeResourcePhase.ownsResources: Boolean
    get() = this != BridgeResourcePhase.Idle && this != BridgeResourcePhase.Closed
