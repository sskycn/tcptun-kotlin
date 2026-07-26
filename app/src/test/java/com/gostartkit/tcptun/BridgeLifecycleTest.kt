package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLifecycleTest {
    @Test
    fun resourceStateMachineTracksACompleteReusableSession() {
        val resources = BridgeResourceStateMachine()

        resources.beginPreparation(epoch = 3L)
        assertEquals(BridgeResourcePhase.Preparing, resources.snapshot.phase)
        assertTrue(resources.snapshot.callbacksRequireCleanup)

        resources.beginTunTransfer()
        assertEquals(BridgeResourcePhase.TunTransferPending, resources.snapshot.phase)
        assertTrue(resources.snapshot.nativeStopRequired)

        resources.beginStart()
        resources.sessionStarted(sessionId = 17L, configJson = "config")
        assertEquals(BridgeResourcePhase.SessionOwned, resources.snapshot.phase)
        assertEquals(3L, resources.activeEpoch)
        assertEquals("config", resources.activeConfigJson)

        val stoppedOwnership = resources.beginStop()
        assertEquals(BridgeResourcePhase.SessionOwned, stoppedOwnership.phase)
        assertEquals(17L, stoppedOwnership.sessionId)
        assertEquals(0L, resources.activeEpoch)
        assertNull(resources.activeConfigJson)
        assertEquals(BridgeResourcePhase.Stopping, resources.snapshot.phase)

        resources.nativeStopped()
        assertEquals(BridgeResourcePhase.CallbacksOwned, resources.snapshot.phase)
        resources.callbacksReleased()
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)

        resources.beginPreparation(epoch = 4L)
        assertEquals(4L, resources.activeEpoch)
    }

    @Test
    fun partialPreparationReleasesCallbacksWithoutClaimingNativeTun() {
        val resources = BridgeResourceStateMachine()
        resources.beginPreparation(epoch = 8L)

        val ownership = resources.beginStop()

        assertFalse(ownership.nativeStopRequired)
        assertTrue(ownership.callbacksRequireCleanup)
        assertFalse(resources.snapshot.nativeStopRequired)
        resources.nativeStopped()
        resources.callbacksReleased()
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
    }

    @Test
    fun unsettledStopRetainsNativeAndCallbackOwnershipForRetry() {
        val resources = BridgeResourceStateMachine()
        resources.beginPreparation(epoch = 9L)
        resources.beginTunTransfer()
        resources.beginStart()

        resources.beginStop()
        val retryOwnership = resources.beginStop()

        assertEquals(BridgeResourcePhase.Stopping, retryOwnership.phase)
        assertTrue(retryOwnership.nativeStopRequired)
        assertTrue(retryOwnership.callbacksRequireCleanup)
    }

    @Test
    fun stateMachineRejectsIllegalOwnershipTransitionsAndCloseIsTerminal() {
        val resources = BridgeResourceStateMachine()

        assertThrows(IllegalStateException::class.java) { resources.beginStart() }
        resources.beginPreparation(epoch = 1L)
        assertThrows(IllegalStateException::class.java) {
            resources.sessionStarted(sessionId = 1L, configJson = "config")
        }

        resources.engineClosed()
        assertEquals(BridgeResourcePhase.Closed, resources.snapshot.phase)
        assertThrows(IllegalStateException::class.java) { resources.beginPreparation(epoch = 2L) }
    }

    @Test
    fun exclusiveResourceOwnerNeverSilentlyOverwritesAResource() {
        val owner = ExclusiveResourceOwner<Any>()
        val first = Any()
        val second = Any()

        owner.acquire(first)
        assertSame(first, owner.resource)
        assertThrows(IllegalStateException::class.java) { owner.acquire(second) }
        assertSame(first, owner.release())
        assertNull(owner.resource)
        owner.acquire(second)
        assertSame(second, owner.resource)
    }

    @Test
    fun runtimeLeasePreventsTwoServiceInstancesFromOwningNativeRuntime() {
        val lease = BridgeRuntimeLease()

        assertEquals(RuntimeLeaseClaim.Acquired, lease.acquire(1L, timeoutMillis = 0L))
        assertEquals(RuntimeLeaseClaim.AlreadyOwned, lease.acquire(1L, timeoutMillis = 0L))
        assertEquals(RuntimeLeaseClaim.TimedOut, lease.acquire(2L, timeoutMillis = 0L))
        assertEquals(1L, lease.owner)

        assertFalse(lease.release(2L))
        assertTrue(lease.release(1L))
        assertEquals(RuntimeLeaseClaim.Acquired, lease.acquire(2L, timeoutMillis = 0L))
        assertEquals(2L, lease.owner)
    }

    @Test
    fun runtimeLeaseWaitCanBeCancelledWithoutChangingOwner() {
        val lease = BridgeRuntimeLease()
        lease.acquire(1L, timeoutMillis = 0L)

        val claim = lease.acquire(2L, timeoutMillis = 1_000L, canContinue = { false })

        assertEquals(RuntimeLeaseClaim.Cancelled, claim)
        assertEquals(1L, lease.owner)
    }

    @Test
    fun successfulStopIsImmediatelySettled() {
        val bridge = FakeLifecycleBridge()

        val result = stopAndAwaitBridgeSession(bridge, sessionId = 7L, settleTimeoutMillis = 500L)

        assertTrue(result.settled)
        assertTrue(result.error == null)
        assertTrue(bridge.stopCalled)
        assertFalse(bridge.waitCalled)
    }

    @Test
    fun stopTimeoutWaitsForTheExactSessionToSettle() {
        val stopError = IllegalStateException("stop timeout")
        val bridge = FakeLifecycleBridge(stopError = stopError)

        val result = stopAndAwaitBridgeSession(bridge, sessionId = 23L, settleTimeoutMillis = 900L)

        assertTrue(result.settled)
        assertTrue(result.error == null)
        assertTrue(bridge.waitCalled)
        assertTrue(bridge.waitSessionId == 23L)
        assertTrue(bridge.waitTimeoutMillis == 900L)
    }

    @Test
    fun activeSessionRemainsUnsettledWhenStopAndWaitBothTimeout() {
        val stopError = IllegalStateException("stop timeout")
        val waitError = IllegalStateException("wait timeout")
        val bridge = FakeLifecycleBridge(
            stopError = stopError,
            waitError = waitError,
            currentStatus = "Error",
            currentStatusReason = "STOP_TIMEOUT",
        )

        val result = stopAndAwaitBridgeSession(bridge, sessionId = 11L, settleTimeoutMillis = 500L)

        assertFalse(result.settled)
        assertSame(waitError, result.error?.cause)
        assertTrue(result.error?.suppressed?.contains(stopError) == true)
    }

    @Test
    fun completedSessionCanReportShutdownErrorWithoutRemainingActive() {
        val stopError = IllegalStateException("stop timeout")
        val waitError = IllegalStateException("graceful shutdown deadline exceeded")
        val bridge = FakeLifecycleBridge(
            stopError = stopError,
            waitError = waitError,
            currentStatus = "Error",
            currentStatusReason = "CORE_STOP_FAILED",
        )

        val result = stopAndAwaitBridgeSession(bridge, sessionId = 5L, settleTimeoutMillis = 500L)

        assertTrue(result.settled)
        assertSame(waitError, result.error?.cause)
    }
}

private class FakeLifecycleBridge(
    private val stopError: Throwable? = null,
    private val waitError: Throwable? = null,
    private val currentStatus: String = "Running",
    private val currentStatusReason: String = "",
) : TcptunBridge {
    var stopCalled = false
    var waitCalled = false
    var waitSessionId = 0L
    var waitTimeoutMillis = 0L

    override fun stop() {
        stopCalled = true
        stopError?.let { throw it }
    }

    override fun waitStopped(sessionId: Long, timeoutMillis: Long) {
        waitCalled = true
        waitSessionId = sessionId
        waitTimeoutMillis = timeoutMillis
        waitError?.let { throw it }
    }

    override fun sessionId(): Long = 1L
    override fun status(): String = currentStatus
    override fun configure(configJson: String) = Unit
    override fun setTun(fd: Int, mtu: Int) = Unit
    override fun start(disabledOutboundTags: List<String>): Long = 1L
    override fun startOutbound(tag: String) = Unit
    override fun stopOutbound(tag: String, force: Boolean, timeoutMillis: Long) = Unit
    override fun probeOutbound(tag: String, host: String, port: Int, timeoutMillis: Long): Long = 0L
    override fun probeOutboundHealth(tag: String, host: String, port: Int, timeoutMillis: Long): Long = 0L
    override fun outboundsStatusJson(): String = "[]"
    override fun close() = Unit
    override fun statusJson(): String = """{"reason":"$currentStatusReason"}"""
    override fun setLogCallback(onLog: (String) -> Unit) = Unit
    override fun clearLogCallback() = Unit
    override fun setStatusCallback(onStatus: (String) -> Unit) = Unit
    override fun clearStatusCallback() = Unit
    override fun registerEvent(event: String) = Unit
    override fun unregisterEvent(event: String) = Unit
    override fun setSocketProtector(onProtect: (Int) -> Boolean) = Unit
    override fun clearSocketProtector() = Unit
    override fun setAppIdentityProvider(onIdentify: (String) -> String?) = Unit
    override fun clearAppIdentityProvider() = Unit
    override fun setFlowAnalysisApp(packageName: String) = Unit
    override fun setFlowCallback(onFlow: (String) -> Unit) = Unit
    override fun clearFlowCallback() = Unit
}
