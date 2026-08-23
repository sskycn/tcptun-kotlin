package com.tcptun.client

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnRuntimeStressTest {
    @Test
    fun rapidLifecycleCommandMatrixPreservesRuntimeOwnership() = withStressHarness { harness ->
        harness.start()
        harness.stop()
        harness.waitForStopped()

        harness.start()
        harness.stop()
        harness.start()
        harness.waitForRunning()

        harness.start(harness.planA)
        harness.start(harness.planB)
        harness.waitForRunning()

        repeat(24) { index ->
            harness.updateConnections(if (index % 2 == 0) harness.planAB else harness.planB)
            Thread.sleep((index % 4 * 25).toLong())
            harness.assertRuntimeInvariants()
        }
        harness.waitForRunning()

        repeat(8) { index ->
            harness.tcping()
            if (index % 2 == 0) harness.applySettings() else harness.refreshClientIps()
            Thread.sleep(25)
            harness.assertRuntimeInvariants()
        }

        harness.updateConnections(harness.planA)
        harness.stop()
        harness.waitForStopped()
        harness.assertNoProcessFailureEvidence()
    }

    @Test
    fun fixedSeedCommandStormPreservesProcessInvariants() = withStressHarness { harness ->
        val arguments = InstrumentationRegistry.getArguments()
        val seed = arguments.getString(SeedArgument)?.toLongOrNull() ?: DefaultSeed
        val transitions = arguments.getString(IterationsArgument)?.toIntOrNull()
            ?.coerceIn(200, 5_000) ?: DefaultTransitions
        val maxDelayMillis = arguments.getString(MaxDelayArgument)?.toLongOrNull()
            ?.coerceIn(0L, 200L) ?: DefaultMaxDelayMillis
        val random = Random(seed)

        repeat(transitions) {
            when (random.nextInt(CommandCount)) {
                0 -> harness.start(if (random.nextBoolean()) harness.planA else harness.planB)
                1 -> harness.stop()
                2 -> harness.updateConnections(if (random.nextBoolean()) harness.planAB else harness.planA)
                3 -> harness.applySettings()
                4 -> harness.tcping()
                5 -> harness.refreshClientIps()
            }
            if (maxDelayMillis > 0L) Thread.sleep(random.nextLong(maxDelayMillis + 1L))
            harness.assertRuntimeInvariants()
        }

        harness.stop()
        harness.waitForStopped(timeoutMillis = 30_000)
        harness.assertNoProcessFailureEvidence()
        assertEquals(VpnStatus.Stopped, TcptunState.status)
    }

    @Test
    fun serviceRecreationCannotOverlapOldNativeOwnership() = withStressHarness { harness ->
        harness.start()
        harness.waitForRunning()
        val originalServiceId = TcptunVpnService.runtimeOwnershipDebugSnapshots()
            .single { it.activeServiceOwner }
            .serviceInstanceId

        harness.context.stopService(Intent(harness.context, TcptunVpnService::class.java))
        harness.waitUntil("old Service enters destroy", 10_000) {
            TcptunVpnService.runtimeOwnershipDebugSnapshots().none { it.activeServiceOwner } ||
                TcptunVpnService.runtimeOwnershipDebugSnapshots().any {
                    it.serviceInstanceId == originalServiceId && it.destroyed
                }
        }
        harness.start(harness.planB)
        harness.waitForRunning(timeoutMillis = 45_000)

        harness.assertRuntimeInvariants()
        val replacementServiceId = TcptunVpnService.runtimeOwnershipDebugSnapshots()
            .single { it.activeServiceOwner }
            .serviceInstanceId
        assumeTrue("Android reused the old Service instance", replacementServiceId != originalServiceId)
        harness.stop()
        harness.waitForStopped()
        harness.assertNoProcessFailureEvidence()
    }

    private fun withStressHarness(block: (VpnRuntimeStressHarness) -> Unit) {
        val enabled = InstrumentationRegistry.getArguments().getString(EnabledArgument).toBoolean()
        assumeTrue("runtime stress is opt-in; use scripts/run-runtime-stress.sh", enabled)
        VpnRuntimeStressHarness().use(block)
    }

    private companion object {
        const val EnabledArgument = "runtimeStressEnabled"
        const val SeedArgument = "runtimeStressSeed"
        const val IterationsArgument = "runtimeStressIterations"
        const val MaxDelayArgument = "runtimeStressMaxDelayMillis"
        const val DefaultSeed = 0x5EED_7C17L
        const val DefaultTransitions = 500
        const val DefaultMaxDelayMillis = 200L
        const val CommandCount = 6
    }
}
