package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the same selection/recovery policy as ConnectivityManager callbacks, without Android callbacks. */
class NetworkHandoverTest {
    @Test
    fun wifiToCellularProducesOneRecoveryForOneSelectionChange() {
        val source = FakeNetworkEventSource()
        val harness = NetworkHandoverHarness(source)

        source.emitAvailable("wifi", score = 130)
        source.emitAvailable("cellular", score = 120)
        source.emitAvailable("cellular", score = 120)

        assertEquals("wifi", harness.selectedNetwork)
        assertEquals(0, harness.recoveryRequests)

        source.emitAvailable("cellular", score = 140)

        assertEquals("cellular", harness.selectedNetwork)
        assertEquals(1, harness.recoveryRequests)
    }

    @Test
    fun temporaryNoNetworkDoesNotTriggerStopOrUnnecessaryRebuild() {
        val source = FakeNetworkEventSource()
        val harness = NetworkHandoverHarness(source)

        source.emitAvailable("wifi", score = 130)
        source.emitLost("wifi")
        source.emitAvailable("wifi", score = 130)

        assertEquals("wifi", harness.selectedNetwork)
        assertFalse(harness.vpnStopped)
        assertEquals(0, harness.recoveryRequests)
    }

    @Test
    fun userStopInvalidatesRecoveryScheduledByHandover() {
        val source = FakeNetworkEventSource()
        val harness = NetworkHandoverHarness(source)

        source.emitAvailable("wifi", score = 130)
        source.emitAvailable("cellular", score = 140)
        val pending = harness.pendingRecovery ?: error("handover did not schedule recovery")

        harness.userStop()

        assertFalse(harness.recoveryCoordinator.isCurrent(pending, currentLifecycleGeneration = 1))
        assertTrue(harness.vpnStopped)
        assertEquals(1, harness.recoveryRequests)
    }

    private class FakeNetworkEventSource {
        private var listener: ((NetworkEvent) -> Unit)? = null

        fun register(listener: (NetworkEvent) -> Unit) {
            this.listener = listener
        }

        fun emitAvailable(name: String, score: Int) {
            listener?.invoke(NetworkEvent.Available(name, score))
        }

        fun emitLost(name: String) {
            listener?.invoke(NetworkEvent.Lost(name))
        }
    }

    private sealed interface NetworkEvent {
        data class Available(val name: String, val score: Int) : NetworkEvent
        data class Lost(val name: String) : NetworkEvent
    }

    private class NetworkHandoverHarness(source: FakeNetworkEventSource) {
        val recoveryCoordinator = BridgeRecoveryCoordinator(30_000L) { it * 1_000L }
        private val selection = RankedSelectionTracker<String>()
        private var lifecycleGeneration = 1
        var selectedNetwork: String? = null
            private set
        var recoveryRequests = 0
            private set
        var pendingRecovery: BridgeRestartToken? = null
            private set
        var vpnStopped = false
            private set

        init {
            source.register { event ->
                val next = when (event) {
                    is NetworkEvent.Available -> selection.update(event.name, event.score)
                    is NetworkEvent.Lost -> selection.remove(event.name)
                }
                val claim = selection.claim(next) ?: return@register
                selectedNetwork = claim.value
                if (
                    BridgeHealthPolicy.shouldRestartForNetworkHandover(
                        initialSelection = claim.initial,
                        networkAvailable = claim.value != null,
                        vpnRunning = !vpnStopped,
                        previousNetworkAvailable = claim.previousValue != null,
                    )
                ) {
                    recoveryRequests += 1
                    pendingRecovery = recoveryCoordinator.requestRestart(
                        lifecycleGeneration = lifecycleGeneration,
                        cancelIfHealthy = false,
                    )
                }
            }
        }

        fun userStop() {
            vpnStopped = true
            lifecycleGeneration += 1
            recoveryCoordinator.cancelRestart()
        }
    }
}
