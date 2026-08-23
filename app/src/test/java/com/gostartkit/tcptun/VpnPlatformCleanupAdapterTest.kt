package com.tcptun.client

import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPlatformCleanupAdapterTest {
    @Test
    fun `released cleanup executes ordered stages`() {
        val harness = Harness(resourcesOwned = false)

        val attempt = harness.perform()

        assertEquals(VpnPlatformStopResult.Released, attempt.result)
        assertEquals(
            listOf(
                "cancel-restart",
                "publish-stopping",
                "stop-health",
                "unregister-network",
                "reset-underlying",
                "clear-desired",
                "publish-bridge-stopping",
                "stop-bridge",
                "close-tun",
                "clear-identity",
                "reset-health",
                "inspect-resources",
                "reset-diagnostics",
                "publish-stopped",
                "remove-foreground",
                "request-service-stop",
                "honor-deferred-stop",
            ),
            harness.events,
        )
    }

    @Test
    fun `retained cleanup does not remove foreground`() {
        val harness = Harness(resourcesOwned = true)

        assertEquals(VpnPlatformStopResult.RetainedForRetry, harness.perform().result)

        assertFalse("remove-foreground" in harness.events)
        assertTrue("retain-cleanup-foreground" in harness.events)
    }

    @Test
    fun `retained cleanup does not stop Service`() {
        val harness = Harness(resourcesOwned = true)

        harness.perform()

        assertFalse("request-service-stop" in harness.events)
        assertFalse("honor-deferred-stop" in harness.events)
    }

    @Test
    fun `native stop failure with owned resources is retained`() {
        val failure = IllegalStateException("native stop failed")
        val harness = Harness(resourcesOwned = true, bridgeFailure = failure)

        val attempt = harness.perform()

        assertEquals(VpnPlatformStopResult.RetainedForRetry, attempt.result)
        assertTrue("publish-incomplete:native stop failed" in harness.events)
    }

    @Test
    fun `native stop failure with released resources is released`() {
        val harness = Harness(
            resourcesOwned = false,
            bridgeFailure = IllegalStateException("native stop failed"),
        )

        val attempt = harness.perform()

        assertEquals(VpnPlatformStopResult.Released, attempt.result)
        assertTrue("remove-foreground" in harness.events)
        assertFalse(harness.events.any { it.startsWith("publish-incomplete:") })
    }

    @Test
    fun `Android TUN close occurs after Bridge stop`() {
        val harness = Harness(resourcesOwned = false)

        harness.perform()

        assertTrue(harness.events.indexOf("close-tun") > harness.events.indexOf("stop-bridge"))
    }

    @Test
    fun `app identity cleanup follows Bridge and TUN cleanup`() {
        val harness = Harness(resourcesOwned = false)

        harness.perform()

        val identity = harness.events.indexOf("clear-identity")
        assertTrue(identity > harness.events.indexOf("stop-bridge"))
        assertTrue(identity > harness.events.indexOf("close-tun"))
    }

    @Test
    fun `desired config clear obeys request flag`() {
        val clearing = Harness(resourcesOwned = false)
        val preserving = Harness(resourcesOwned = false)

        clearing.perform(VpnPlatformTeardownRequest(clearSavedConfig = true))
        preserving.perform(VpnPlatformTeardownRequest(clearSavedConfig = false))

        assertTrue("clear-desired" in clearing.events)
        assertFalse("clear-desired" in preserving.events)
    }

    @Test
    fun `setStopped false does not publish Stopped`() {
        val harness = Harness(resourcesOwned = false)

        harness.perform(VpnPlatformTeardownRequest(setStopped = false))

        assertFalse("publish-stopped" in harness.events)
        assertFalse("publish-stopping" in harness.events)
    }

    @Test
    fun `stopSelfService false does not request Service stop`() {
        val harness = Harness(resourcesOwned = false)

        harness.perform(VpnPlatformTeardownRequest(stopSelfService = false))

        assertFalse("request-service-stop" in harness.events)
        assertTrue("honor-deferred-stop" in harness.events)
    }

    @Test
    fun `stale global publication port rejects state writes`() {
        val harness = Harness(resourcesOwned = false)
        val request = VpnPlatformTeardownRequest(
            globalStateOwner = { false },
            globalStateCommitLock = Any(),
        )
        val serviceOwnerLock = Any()
        val stalePort = VpnCleanupPublicationPort(
            globalStep = { label, action ->
                request.runGlobalCleanupStep(serviceOwnerLock, label, harness::localStep, action)
            },
            localStep = harness::localStep,
        )

        harness.perform(request, stalePort)

        assertFalse("publish-stopping" in harness.events)
        assertFalse("reset-underlying" in harness.events)
        assertFalse("clear-desired" in harness.events)
        assertFalse("reset-diagnostics" in harness.events)
        assertFalse("publish-stopped" in harness.events)
        assertFalse("remove-foreground" in harness.events)
        assertFalse("request-service-stop" in harness.events)
        assertTrue("stop-bridge" in harness.events)
        assertTrue("close-tun" in harness.events)
    }

    @Test
    fun `retained retry invokes same one-shot adapter without registering another retry`() {
        val harness = Harness(resourcesOwned = true)
        val tasks = mutableListOf<() -> Unit>()
        val completed = mutableListOf<VpnPlatformCleanupOwner>()
        val owner = VpnPlatformCleanupOwner.Stop(VpnRuntimeCommandToken(1L, 1, 1))
        val request = VpnPlatformTeardownRequest(cleanupOwner = owner)
        val runtime = VpnPlatformTeardownRuntime(
            retryDelaysMillis = listOf(0L),
            performCleanup = { retryRequest -> harness.perform(retryRequest).result },
            completeOwner = { cleanupOwner, _ -> completed += cleanupOwner },
            resourcesOwned = { harness.resourcesOwned },
            scheduleRetry = { _, task ->
                tasks += task
                FutureTask<Unit>({}, Unit)
            },
            dispatchLifecycleRetry = { task -> task(); true },
            isDestroyed = { false },
            log = {},
        )

        runtime.acceptInitialResult(request, harness.perform(request).result)
        assertEquals(1, tasks.size)
        val firstAttemptPrefix = harness.events.take(12)
        harness.releaseResourcesOnBridgeStop = true

        tasks.single().invoke()

        assertEquals(1, tasks.size)
        assertEquals(firstAttemptPrefix, harness.events.drop(14).take(12))
        assertEquals(listOf(owner), completed)
    }

    @Test
    fun `bridge failure is returned for outer propagate policy`() {
        val failure = IllegalArgumentException("bridge failure")
        val harness = Harness(resourcesOwned = false, bridgeFailure = failure)

        val attempt = harness.perform()

        assertSame(failure, attempt.bridgeStopFailure)
        assertEquals(VpnPlatformStopResult.Released, attempt.result)
    }

    private class Harness(
        var resourcesOwned: Boolean,
        private val bridgeFailure: Throwable? = null,
    ) {
        val events = mutableListOf<String>()
        var releaseResourcesOnBridgeStop = false
        private fun event(name: String) = { events += name }
        private val adapter = VpnPlatformCleanupAdapter(
            VpnPlatformCleanupActions(
                cancelBridgeRestart = event("cancel-restart"),
                publishStopping = event("publish-stopping"),
                stopHealth = event("stop-health"),
                unregisterNetwork = event("unregister-network"),
                resetUnderlyingDiagnostics = event("reset-underlying"),
                clearDesiredConfig = event("clear-desired"),
                publishBridgeStopping = event("publish-bridge-stopping"),
                stopBridgeSession = {
                    events += "stop-bridge"
                    bridgeFailure?.let { throw it }
                    if (releaseResourcesOnBridgeStop) resourcesOwned = false
                },
                closeTunIfSafe = event("close-tun"),
                clearAppIdentity = event("clear-identity"),
                resetHealth = event("reset-health"),
                resourcesOwned = {
                    events += "inspect-resources"
                    resourcesOwned
                },
                resetDiagnostics = event("reset-diagnostics"),
                publishStopped = event("publish-stopped"),
                removeForeground = event("remove-foreground"),
                requestServiceStop = event("request-service-stop"),
                honorDeferredStopIfReleased = event("honor-deferred-stop"),
                publishIncompleteCleanup = { events += "publish-incomplete:$it" },
                retainCleanupForeground = event("retain-cleanup-foreground"),
            ),
        )

        fun perform(
            request: VpnPlatformTeardownRequest = VpnPlatformTeardownRequest(),
            publication: VpnCleanupPublicationPort = VpnCleanupPublicationPort(
                globalStep = ::globalStep,
                localStep = ::localStep,
            ),
        ): VpnCleanupAttempt = adapter.perform(request, publication)

        private fun globalStep(@Suppress("UNUSED_PARAMETER") label: String, action: () -> Unit) {
            action()
        }

        fun localStep(@Suppress("UNUSED_PARAMETER") label: String, action: () -> Unit) {
            try {
                action()
            } catch (_: Throwable) {
                // Production cleanupStep records and continues after recoverable failures.
            }
        }
    }
}
