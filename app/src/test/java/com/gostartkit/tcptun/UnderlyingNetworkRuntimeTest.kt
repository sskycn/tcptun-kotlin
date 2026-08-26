package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkRuntimeTest {
    @Test
    fun staleSelectionIsRejectedAfterStartClaimsNewToken() {
        val harness = Harness()
        harness.runtime.register()
        harness.source.emit("wifi", initial = true)

        harness.activeOwnership = ownership(generation = 2, epoch = 10)
        harness.runQueued()

        assertTrue(harness.appliedNetworks.isEmpty())
        assertTrue(harness.restartRequests.isEmpty())
    }

    @Test
    fun staleSelectionIsRejectedAfterStopRemovesRuntime() {
        val harness = Harness()
        harness.runtime.register()
        harness.source.emit("wifi", initial = true)

        harness.activeOwnership = null
        harness.runQueued()

        assertTrue(harness.appliedNetworks.isEmpty())
        assertTrue(harness.probeRequests.isEmpty())
    }

    @Test
    fun staleSelectionIsRejectedAfterRecoveryPublishesNewEpoch() {
        val harness = Harness()
        harness.runtime.register()
        harness.source.emit("wifi", initial = true)

        harness.activeOwnership = ownership(generation = 1, epoch = 20)
        harness.runQueued()

        assertTrue(harness.appliedNetworks.isEmpty())
    }

    @Test
    fun latestNetworkWinsBeforePlatformMutation() {
        val harness = Harness()
        harness.runtime.register()
        harness.source.emit("wifi", initial = true)
        harness.source.emit("cellular", initial = false, previous = "wifi")

        harness.runQueued()

        assertEquals(listOf("cellular"), harness.appliedNetworks)
        assertEquals(listOf("cellular"), harness.diagnosticNetworks)
        assertEquals(1, harness.restartRequests.size)
    }

    @Test
    fun networkLossSkipsMemberProbeUntilAnEligibleNetworkReturns() {
        val harness = Harness()
        harness.runtime.register()
        harness.source.emit("wifi", initial = true)
        harness.runQueued()
        assertEquals(1, harness.probeRequests.size)

        harness.source.emit(null, initial = false, previous = "wifi")
        harness.runQueued()

        assertEquals(listOf("wifi", null), harness.appliedNetworks)
        assertEquals(1, harness.probeRequests.size)
        assertTrue(harness.restartRequests.isEmpty())

        harness.source.emit("cellular", initial = false, previous = null)
        harness.runQueued()

        assertEquals(listOf("wifi", null, "cellular"), harness.appliedNetworks)
        assertEquals(2, harness.probeRequests.size)
    }

    @Test
    fun unregisterRejectsLateCallbackFromOldRegistration() {
        val harness = Harness()
        harness.runtime.register()
        harness.runtime.unregister()

        harness.source.emit("wifi", initial = true)
        harness.runQueued()

        assertEquals(1, harness.source.unregisterCalls)
        assertTrue(harness.appliedNetworks.isEmpty())
    }

    @Test
    fun republishBindsSelectedNetworkToNewPhysicalEpoch() {
        val harness = Harness()
        harness.runtime.register()
        harness.source.emit("wifi", initial = true)
        harness.runQueued()
        harness.activeOwnership = ownership(generation = 2, epoch = 20)

        harness.runtime.republishCurrent("runtime replaced")
        harness.runQueued()

        assertEquals(listOf("wifi", "wifi"), harness.appliedNetworks)
        assertEquals(20L, harness.dispatchedOwnerships.last().bridgeEpoch)
        assertEquals(2, harness.probeRequests.size)
    }

    @Test
    fun ownershipChangeDuringPlatformMutationSuppressesOldHandoverDecision() {
        val harness = Harness()
        harness.runtime.register()
        harness.onApply = {
            harness.activeOwnership = ownership(generation = 2, epoch = 10)
        }
        harness.source.emit("cellular", initial = false, previous = "wifi")

        harness.runQueued()

        assertEquals(listOf("cellular"), harness.appliedNetworks)
        assertTrue(harness.restartRequests.isEmpty())
        assertTrue(harness.probeRequests.isEmpty())
    }

    private class Harness {
        val source = FakeSelectionSource()
        var activeOwnership: VpnRuntimeOwnership? = ownership(generation = 1, epoch = 10)
        val queued = mutableListOf<() -> Unit>()
        val dispatchedOwnerships = mutableListOf<VpnRuntimeOwnership>()
        val appliedNetworks = mutableListOf<String?>()
        val diagnosticNetworks = mutableListOf<String?>()
        val restartRequests = mutableListOf<VpnRuntimeOwnership>()
        val probeRequests = mutableListOf<String>()
        var onApply: () -> Unit = {}
        val runtime = UnderlyingNetworkRuntime<String>(
            selectionSourceFactory = { listener -> source.also { it.listener = listener } },
            currentOwnership = { activeOwnership },
            isOwnershipCurrent = { it == activeOwnership },
            dispatchLifecycle = { ownership, task ->
                dispatchedOwnerships += ownership
                queued += task
                true
            },
            applyUnderlyingNetworks = {
                appliedNetworks += it
                onApply()
            },
            updateDiagnostics = { diagnosticNetworks += it },
            vpnRunning = { activeOwnership != null },
            onRestartRequested = { _, _, ownership -> restartRequests += ownership },
            onMemberProbeRequested = { reason, _ -> probeRequests += reason },
            log = {},
        )

        fun runQueued() {
            val tasks = queued.toList()
            queued.clear()
            tasks.forEach { it() }
        }
    }

    private class FakeSelectionSource : UnderlyingNetworkSelectionSource<String> {
        lateinit var listener: (String?, RankedSelectionClaim<String>, String) -> Unit
        var unregisterCalls = 0
        private var current: String? = null

        override fun register() = Unit

        override fun unregister(): Boolean {
            unregisterCalls += 1
            return true
        }

        override fun republishCurrent(reason: String) {
            listener(current, RankedSelectionClaim(current, initial = true, previousValue = current), reason)
        }

        fun emit(network: String?, initial: Boolean, previous: String? = null) {
            current = network
            listener(network, RankedSelectionClaim(network, initial, previous), "selection changed")
        }
    }

    private companion object {
        fun ownership(generation: Int, epoch: Long) = VpnRuntimeOwnership(
            VpnRuntimeCommandToken(1, generation, generation),
            epoch,
        )
    }
}
