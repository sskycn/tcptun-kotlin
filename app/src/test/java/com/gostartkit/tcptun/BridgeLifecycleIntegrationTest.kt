package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production lifecycle controllers with a bridge independent of androidbridge.aar. */
class BridgeLifecycleIntegrationTest {
    @Test
    fun configureFailureReleasesCallbacksAndAndroidTunOwnership() {
        val bridge = FakeTcptunBridge(
            FakeTcptunBridge.Failures(configure = IllegalStateException("configure failed")),
        )
        val resources = BridgeResourceStateMachine().apply { beginPreparation(1L) }
        val tunOwner = ExclusiveResourceOwner<Int>().apply { acquire(77) }

        assertThrows(IllegalStateException::class.java) {
            BridgeSessionController(bridge, resources).start(
                request = request(),
                callbacks = callbacks(),
                canStart = { true },
            )
        }
        stopAndRelease(bridge, resources, tunOwner)

        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
        assertTrue(bridge.callbacksCleared)
        assertTrue(tunOwner.resource == null)
        assertFalse(bridge.calls.contains("setTun:77:1400"))
    }

    @Test
    fun setTunFailureKeepsNativeObligationUntilStopControllerSettlesIt() {
        val bridge = FakeTcptunBridge(
            FakeTcptunBridge.Failures(setTun = IllegalStateException("setTun failed")),
        )
        val resources = BridgeResourceStateMachine().apply { beginPreparation(2L) }
        val tunOwner = ExclusiveResourceOwner<Int>().apply { acquire(78) }

        assertThrows(IllegalStateException::class.java) {
            BridgeSessionController(bridge, resources).start(request(), callbacks()) { true }
        }
        assertEquals(BridgeResourcePhase.TunTransferPending, resources.snapshot.phase)
        assertTrue(resources.snapshot.nativeStopRequired)

        stopAndRelease(bridge, resources, tunOwner)

        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
        assertTrue(bridge.calls.contains("stop"))
        assertTrue(tunOwner.resource == null)
    }

    @Test
    fun startFailureDoesNotPublishSessionAndStillCleansNativeOwnership() {
        val bridge = FakeTcptunBridge(
            FakeTcptunBridge.Failures(start = IllegalStateException("start failed")),
        )
        val resources = BridgeResourceStateMachine().apply { beginPreparation(3L) }
        val tunOwner = ExclusiveResourceOwner<Int>().apply { acquire(79) }

        assertThrows(IllegalStateException::class.java) {
            BridgeSessionController(bridge, resources).start(request(), callbacks()) { true }
        }
        assertEquals(BridgeResourcePhase.StartPending, resources.snapshot.phase)
        assertEquals(0L, resources.snapshot.sessionId)

        stopAndRelease(bridge, resources, tunOwner)

        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
        assertTrue(tunOwner.resource == null)
    }

    @Test
    fun stopTimeoutAbortSuccessReleasesAllOwnershipInOrder() {
        val bridge = FakeTcptunBridge(
            failures = FakeTcptunBridge.Failures(
                stop = IllegalStateException("stop timeout"),
                waitStopped = IllegalStateException("wait timeout"),
            ),
            currentStatus = "Error",
            currentStatusReason = "STOP_TIMEOUT",
        )
        val resources = startedResources()
        val tunOwner = ExclusiveResourceOwner<Int>().apply { acquire(80) }

        stopAndRelease(bridge, resources, tunOwner)

        assertTrue(bridge.abortCalled)
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
        assertTrue(bridge.callbacksCleared)
        assertTrue(tunOwner.resource == null)
        assertTrue(bridge.calls.indexOf("abort") < bridge.calls.indexOf("clearLogCallback"))
    }

    @Test
    fun abortFailureRetainsEngineCallbacksTunAndLeaseObligations() {
        val abortError = IllegalStateException("abort failed")
        val bridge = FakeTcptunBridge(
            failures = FakeTcptunBridge.Failures(
                stop = IllegalStateException("stop timeout"),
                waitStopped = IllegalStateException("wait timeout"),
                abort = abortError,
            ),
            currentStatus = "Error",
            currentStatusReason = "STOP_TIMEOUT",
        )
        val resources = startedResources()
        val tunOwner = ExclusiveResourceOwner<Int>().apply { acquire(81) }
        var reported: Throwable? = null
        bridge.setLogCallback {}
        bridge.setStatusCallback {}

        resources.beginStop()
        val thrown = assertThrows(IllegalStateException::class.java) {
            BridgeSessionStopController(bridge, resources).stop(
                settleTimeoutMillis = 10L,
                callbacks = BridgeSessionStopCallbacks(
                    onNativeStillStopping = { reported = it },
                    onNativeStoppedWithError = {},
                    onCleanupFailure = { _, _ -> },
                ),
            )
        }

        assertSame(thrown, reported)
        assertSame(abortError, thrown.cause)
        assertEquals(BridgeResourcePhase.Stopping, resources.snapshot.phase)
        assertTrue(resources.snapshot.nativeStopRequired)
        assertTrue(bridge.callbacksInstalled)
        assertFalse(bridge.callbacksCleared)
        assertEquals(81, tunOwner.resource)
    }

    @Test
    fun oldSessionStatusIsIgnoredAfterReplacementEpoch() {
        val firstEpoch = TcptunState.beginBridgeSession()
        TcptunState.applyBridgeStatusEvent(
            firstEpoch,
            """{"session_id":1,"sequence":1,"state":"running"}""",
        )
        TcptunState.endBridgeSession(firstEpoch)
        val replacementEpoch = TcptunState.beginBridgeSession()

        assertTrue(
            TcptunState.applyBridgeStatusEvent(
                firstEpoch,
                """{"session_id":1,"sequence":2,"state":"error","last_error":"stale"}""",
            ) == null,
        )
        assertEquals("starting", TcptunState.diagnostics.bridgeEventState)
        assertTrue(replacementEpoch > firstEpoch)
        TcptunState.endBridgeSession(replacementEpoch)
    }

    private fun startedResources() = BridgeResourceStateMachine().apply {
        beginPreparation(4L)
        beginTunTransfer()
        beginStart()
        sessionStarted(41L, "config-json")
    }

    private fun request() = BridgeSessionStartRequest(
        configJson = "config-json",
        disabledOutboundTags = listOf("inactive"),
        tunFd = 77,
        mtu = 1400,
        powerSavingMode = true,
        logLevel = DefaultLogLevel,
    )

    private fun callbacks() = BridgeSessionCallbacks(
        onLog = {},
        onStatus = {},
        protectSocket = { true },
        configureFlowAnalysis = {},
        onInitialStatus = {},
        onOptionalEventRegistrationFailure = { _, _ -> },
    )

    private fun stopAndRelease(
        bridge: FakeTcptunBridge,
        resources: BridgeResourceStateMachine,
        tunOwner: ExclusiveResourceOwner<Int>,
    ) {
        resources.beginStop()
        BridgeSessionStopController(bridge, resources).stop(
            settleTimeoutMillis = 10L,
            callbacks = BridgeSessionStopCallbacks(
                onNativeStillStopping = { error -> throw error },
                onNativeStoppedWithError = {},
                onCleanupFailure = { _, error -> throw error },
            ),
        )
        check(canCloseAndroidTun(resources.snapshot))
        tunOwner.release()
    }
}
