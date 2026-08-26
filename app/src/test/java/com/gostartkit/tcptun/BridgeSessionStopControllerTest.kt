package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSessionStopControllerTest {
    @Test
    fun releasesNativeSessionBeforeCallbacks() {
        val resources = startedResources()
        resources.beginStop()
        val bridge = RecordingStopBridge()

        BridgeSessionStopController(bridge, resources).stop(
            settleTimeoutMillis = 700L,
            callbacks = callbacks(),
        )

        assertEquals(
            listOf(
                "stop",
                "unregister:${TcptunBridgeEvents.RemoteEndpointsChanged}",
                "unregister:${TcptunBridgeEvents.RuntimeReconnecting}",
                "unregister:${TcptunBridgeEvents.RuntimeConnectionIssue}",
                "clearFlowCallback",
                "clearAppIdentityProvider",
                "clearSocketProtector",
                "clearStatusCallback",
                "clearLogCallback",
            ),
            bridge.calls,
        )
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
    }

    @Test
    fun partialStartResolvesAndWaitsForExactNativeSession() {
        val resources = BridgeResourceStateMachine().apply {
            beginPreparation(epoch = 8L)
            beginTunTransfer()
            beginStart()
            beginStop()
        }
        val bridge = RecordingStopBridge(
            stopError = IllegalStateException("stop timeout"),
            nativeSessionId = 53L,
        )

        BridgeSessionStopController(bridge, resources).stop(
            settleTimeoutMillis = 900L,
            callbacks = callbacks(),
        )

        assertEquals(listOf("sessionId", "stop", "waitStopped:53:900"), bridge.calls.take(3))
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
    }

    @Test
    fun unsettledSessionUsesAbortThenReleasesNativeAndCallbackOwnership() {
        val resources = startedResources()
        resources.beginStop()
        val waitError = IllegalStateException("wait timeout")
        val bridge = RecordingStopBridge(
            stopError = IllegalStateException("stop timeout"),
            waitError = waitError,
            currentStatus = "Error",
            currentStatusReason = "STOP_TIMEOUT",
        )
        var stoppedWithError: Throwable? = null

        BridgeSessionStopController(bridge, resources).stop(
            settleTimeoutMillis = 500L,
            callbacks = callbacks(onStoppedWithError = { stoppedWithError = it }),
        )

        assertTrue(bridge.calls.contains("abort"))
        assertTrue(stoppedWithError?.cause === waitError)
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
        assertTrue(bridge.calls.any { it.startsWith("clear") })
    }

    @Test
    fun abortFailureRetainsNativeAndCallbackOwnership() {
        val resources = startedResources()
        resources.beginStop()
        val abortError = IllegalStateException("abort failed")
        val bridge = RecordingStopBridge(
            stopError = IllegalStateException("stop timeout"),
            waitError = IllegalStateException("wait timeout"),
            abortError = abortError,
            currentStatus = "Error",
            currentStatusReason = "STOP_TIMEOUT",
        )
        var reported: Throwable? = null

        val thrown = assertThrows(IllegalStateException::class.java) {
            BridgeSessionStopController(bridge, resources).stop(
                settleTimeoutMillis = 500L,
                callbacks = callbacks(onStillStopping = { reported = it }),
            )
        }

        assertSame(thrown, reported)
        assertSame(abortError, thrown.cause)
        assertEquals(BridgeResourcePhase.Stopping, resources.snapshot.phase)
        assertTrue(resources.snapshot.nativeStopRequired)
        assertTrue(resources.snapshot.callbacksRequireCleanup)
        assertFalse(bridge.calls.any { it.startsWith("clear") })
    }

    @Test
    fun callbackFailureKeepsCleanupOwnershipAndRetryDoesNotStopAgain() {
        val resources = startedResources()
        resources.beginStop()
        val bridge = RecordingStopBridge(failStatusClearOnce = true)
        val failures = mutableListOf<String>()
        val controller = BridgeSessionStopController(bridge, resources)

        assertThrows(IllegalStateException::class.java) {
            controller.stop(
                settleTimeoutMillis = 500L,
                callbacks = callbacks(
                    onCleanupFailure = { label, error -> failures += "$label:${error.message}" },
                ),
            )
        }

        assertEquals(BridgeResourcePhase.CallbacksOwned, resources.snapshot.phase)
        assertEquals(listOf("clear status callback:status clear failed"), failures)
        resources.beginStop()
        controller.stop(settleTimeoutMillis = 500L, callbacks = callbacks())

        assertEquals(1, bridge.calls.count { it == "stop" })
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
    }

    @Test
    fun eventUnregisterFailureDoesNotBlockCallbackRelease() {
        val resources = startedResources()
        resources.beginStop()
        val bridge = RecordingStopBridge(
            failingEvent = TcptunBridgeEvents.RuntimeReconnecting,
        )
        val failures = mutableListOf<String>()

        BridgeSessionStopController(bridge, resources).stop(
            settleTimeoutMillis = 500L,
            callbacks = callbacks(onCleanupFailure = { label, _ -> failures += label }),
        )

        assertEquals(
            listOf("unregister bridge event ${TcptunBridgeEvents.RuntimeReconnecting}"),
            failures,
        )
        assertEquals(BridgeResourcePhase.Idle, resources.snapshot.phase)
    }

    private fun startedResources(): BridgeResourceStateMachine = BridgeResourceStateMachine().apply {
        beginPreparation(epoch = 7L)
        beginTunTransfer()
        beginStart()
        sessionStarted(sessionId = 41L, configJson = "config-json")
    }

    private fun callbacks(
        onCleanupFailure: (String, Throwable) -> Unit = { _, _ -> },
        onStillStopping: (Throwable) -> Unit = {},
        onStoppedWithError: (Throwable) -> Unit = {},
    ): BridgeSessionStopCallbacks = BridgeSessionStopCallbacks(
        onNativeStillStopping = onStillStopping,
        onNativeStoppedWithError = onStoppedWithError,
        onCleanupFailure = onCleanupFailure,
    )
}

private class RecordingStopBridge(
    private val stopError: Throwable? = null,
    private val waitError: Throwable? = null,
    private val abortError: Throwable? = null,
    private val currentStatus: String = "Stopped",
    private val currentStatusReason: String = "",
    private val nativeSessionId: Long = 41L,
    private val failStatusClearOnce: Boolean = false,
    private val failingEvent: String? = null,
) : TcptunBridge {
    val calls = mutableListOf<String>()
    private var statusClearFailures = 0

    override fun setPowerSave(enabled: Boolean) = unexpected("setPowerSave")

    override fun stop() {
        calls += "stop"
        stopError?.let { throw it }
    }

    override fun waitStopped(sessionId: Long, timeoutMillis: Long) {
        calls += "waitStopped:$sessionId:$timeoutMillis"
        waitError?.let { throw it }
    }

    override fun abort() {
        calls += "abort"
        abortError?.let { throw it }
    }

    override fun sessionId(): Long {
        calls += "sessionId"
        return nativeSessionId
    }

    override fun status(): String = currentStatus
    override fun statusJson(): String = """{"reason":"$currentStatusReason"}"""

    override fun unregisterEvent(event: String) {
        calls += "unregister:$event"
        if (event == failingEvent) throw IllegalStateException("unregister failed")
    }

    override fun clearFlowCallback() {
        calls += "clearFlowCallback"
    }

    override fun clearAppIdentityProvider() {
        calls += "clearAppIdentityProvider"
    }

    override fun clearSocketProtector() {
        calls += "clearSocketProtector"
    }

    override fun clearStatusCallback() {
        calls += "clearStatusCallback"
        if (failStatusClearOnce && statusClearFailures++ == 0) {
            throw IllegalStateException("status clear failed")
        }
    }

    override fun clearLogCallback() {
        calls += "clearLogCallback"
    }

    override fun configure(configJson: String) = unexpected("configure")
    override fun setTun(fd: Int, mtu: Int) = unexpected("setTun")
    override fun start(disabledOutboundTags: List<String>): Long = unexpected("start")
    override fun startOutbound(tag: String) = unexpected("startOutbound")
    override fun stopOutbound(tag: String, force: Boolean, timeoutMillis: Long) = unexpected("stopOutbound")
    override fun switchOutbound(tag: String, stopPrevious: Boolean, timeoutMillis: Long) = unexpected("switchOutbound")
    override fun probeOutbound(tag: String, host: String, port: Int, timeoutMillis: Long): Long =
        unexpected("probeOutbound")
    override fun probeOutboundHealth(tag: String, host: String, port: Int, timeoutMillis: Long): Long =
        unexpected("probeOutboundHealth")
    override fun outboundsStatusJson(): String = unexpected("outboundsStatusJson")
    override fun close() = unexpected("close")
    override fun setLogLevel(level: String) = unexpected("setLogLevel")
    override fun logLevel(): String = unexpected("logLevel")
    override fun setLogCallback(onLog: (String) -> Unit) = unexpected("setLogCallback")
    override fun setStatusCallback(onStatus: (String) -> Unit) = unexpected("setStatusCallback")
    override fun registerEvent(event: String) = unexpected("registerEvent")
    override fun setSocketProtector(onProtect: (Int) -> Boolean) = unexpected("setSocketProtector")
    override fun setAppIdentityProvider(onIdentify: (String) -> String?) = unexpected("setAppIdentityProvider")
    override fun setFlowAnalysisApp(packageName: String) = unexpected("setFlowAnalysisApp")
    override fun setFlowCallback(onFlow: (String) -> Unit) = unexpected("setFlowCallback")

    private fun unexpected(method: String): Nothing = error("unexpected bridge call: $method")
}
