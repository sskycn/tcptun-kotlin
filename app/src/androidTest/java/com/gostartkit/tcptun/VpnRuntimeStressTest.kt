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

        harness.start(harness.lifecyclePlanA)
        harness.start(harness.lifecyclePlanB)
        harness.waitForRunning()

        repeat(24) { index ->
            harness.updateConnections(
                if (index % 2 == 0) harness.lifecyclePlanA else harness.lifecyclePlanB,
            )
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

        harness.updateConnections(harness.lifecyclePlanA)
        harness.stop()
        harness.waitForStopped()
        harness.assertNoProcessFailureEvidence()
        println("RUNTIME_STRESS_RAPID_MATRIX_EXECUTED")
    }

    @Test
    fun inPlaceMembershipMutationKeepsBridgeEpoch() = withStressHarness { harness ->
        val fixture = harness.membershipFixture
        assumeTrue(
            "membership stress requires two structured device-lab profiles",
            fixture != null,
        )
        requireNotNull(fixture)

        harness.start(fixture.planA)
        harness.waitForRunning()
        val before = harness.activeOwnershipSnapshot()
        val bridgeEpoch = before.bridgeEpoch

        val afterAB = harness.updateConnectionsAndWait(fixture.planAB)
        assertEquals("plan A -> plan AB replaced the bridge", bridgeEpoch, afterAB.bridgeEpoch)

        val afterB = harness.updateConnectionsAndWait(fixture.planB)
        assertEquals("plan AB -> plan B replaced the bridge", bridgeEpoch, afterB.bridgeEpoch)

        harness.stop()
        harness.waitForStopped()
        harness.assertNoProcessFailureEvidence()
        println("RUNTIME_STRESS_MEMBERSHIP_EPOCH_UNCHANGED=$bridgeEpoch")
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
                0 -> harness.start(
                    if (random.nextBoolean()) harness.lifecyclePlanA else harness.lifecyclePlanB,
                )
                1 -> harness.stop()
                2 -> harness.updateConnections(
                    if (random.nextBoolean()) harness.lifecyclePlanA else harness.lifecyclePlanB,
                )
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
        println("RUNTIME_STRESS_SEEDED_STORM_EXECUTED seed=$seed transitions=$transitions")
    }

    @Test
    fun structuralRoutePlanMatrixRebuildsWithoutOwnershipOverlap() = withStressHarness { harness ->
        val splitA = AndroidVpnRoutePlan.SplitTunnel(
            routes = listOf(
                IpPrefix.parse("192.168.50.0/24"),
                IpPrefix.parse("fd12:3456:789a::/64"),
            ),
        )
        val splitB = AndroidVpnRoutePlan.SplitTunnel(
            routes = listOf(
                IpPrefix.parse("192.168.60.0/24"),
                IpPrefix.parse("fd12:3456:789b::/64"),
            ),
        )

        harness.start()
        harness.waitForRunning()
        assertEquals("full", TcptunState.diagnostics.vpnRouteMode)

        harness.applyRoutePlanAndWait(splitA)
        assertEquals(2, TcptunState.diagnostics.vpnIpv4RouteCount)
        assertEquals(2, TcptunState.diagnostics.vpnIpv6RouteCount)
        assertEquals(2, TcptunState.diagnostics.vpnFakeIpRouteCount)

        harness.applyRoutePlanAndWait(splitB)
        assertEquals("split", TcptunState.diagnostics.vpnRouteMode)

        harness.applyRoutePlanAndWait(AndroidVpnRoutePlan.FullTunnel)
        assertEquals(1, TcptunState.diagnostics.vpnIpv4RouteCount)
        assertEquals(1, TcptunState.diagnostics.vpnIpv6RouteCount)
        assertEquals(0, TcptunState.diagnostics.vpnFakeIpRouteCount)

        harness.stop()
        harness.waitForStopped()
        harness.assertNoProcessFailureEvidence()
        println("RUNTIME_STRESS_ROUTE_PLAN_MATRIX_EXECUTED full-splitA-splitB-full")
    }

    @Test
    fun serviceRecreationCannotOverlapOldNativeOwnership() = withStressHarness { harness ->
        harness.start()
        harness.waitForRunning()
        val originalServiceId = TcptunVpnService.runtimeOwnershipDebugSnapshots()
            .single { it.activeServiceOwner }
            .serviceInstanceId

        val stopAccepted = harness.context.stopService(
            Intent(harness.context, TcptunVpnService::class.java),
        )
        assumeTrue("platform rejected the Service recreation trigger", stopAccepted)
        val destroyDeadline = System.currentTimeMillis() + 10_000L
        var oldServiceDestroyed = false
        while (System.currentTimeMillis() < destroyDeadline && !oldServiceDestroyed) {
            harness.assertRuntimeInvariants()
            val snapshots = TcptunVpnService.runtimeOwnershipDebugSnapshots()
            oldServiceDestroyed = snapshots.none { it.activeServiceOwner } || snapshots.any {
                it.serviceInstanceId == originalServiceId && it.destroyed
            }
            if (!oldServiceDestroyed) Thread.sleep(50)
        }
        assumeTrue("platform kept the Running Service instead of recreating it", oldServiceDestroyed)
        harness.start(harness.lifecyclePlanB)
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
