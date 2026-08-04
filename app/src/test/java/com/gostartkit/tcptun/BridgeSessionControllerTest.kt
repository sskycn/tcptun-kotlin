package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSessionControllerTest {
    @Test
    fun startsConfiguredSessionInNativeOwnershipOrder() {
        val resources = preparedResources()
        val bridge = RecordingSessionBridge()
        val controller = BridgeSessionController(bridge, resources)

        val sessionId = controller.start(
            request = startRequest(),
            callbacks = callbacks(bridge.calls),
            canStart = { true },
        )

        assertEquals(41L, sessionId)
        assertEquals(
            listOf(
                "setLogCallback",
                "setStatusCallback",
                "register:${TcptunBridgeEvents.RemoteEndpointsChanged}",
                "register:${TcptunBridgeEvents.RuntimeReconnecting}",
                "register:${TcptunBridgeEvents.RuntimeConnectionIssue}",
                "setSocketProtector",
                "setAppIdentityProvider",
                "configureFlowAnalysis",
                "statusJson",
                "initialStatus:{\"state\":\"starting\"}",
                "configure:config-json",
                "setLogLevel:debug",
                "logLevel",
                "setTun:37:1400",
                "start:profile-b",
            ),
            bridge.calls,
        )
        assertEquals(BridgeResourcePhase.SessionOwned, resources.snapshot.phase)
        assertEquals(41L, resources.snapshot.sessionId)
        assertEquals("config-json", resources.snapshot.configJson)
    }

    @Test
    fun optionalEventRegistrationFailureDoesNotAbortSession() {
        val resources = preparedResources()
        val bridge = RecordingSessionBridge(
            failingEvent = TcptunBridgeEvents.RuntimeReconnecting,
        )
        val failures = mutableListOf<String>()

        val sessionId = BridgeSessionController(bridge, resources).start(
            request = startRequest(),
            callbacks = callbacks(bridge.calls) { event, error ->
                failures += "$event:${error.message}"
            },
            canStart = { true },
        )

        assertEquals(41L, sessionId)
        assertEquals(
            listOf("${TcptunBridgeEvents.RuntimeReconnecting}:registration failed"),
            failures,
        )
        assertTrue(bridge.calls.contains("register:${TcptunBridgeEvents.RuntimeConnectionIssue}"))
        assertEquals(BridgeResourcePhase.SessionOwned, resources.snapshot.phase)
    }

    @Test
    fun cancellationBeforeCallbackInstallationDoesNotCrossBridgeBoundary() {
        val resources = preparedResources()
        val bridge = RecordingSessionBridge()

        assertThrows(IllegalStateException::class.java) {
            BridgeSessionController(bridge, resources).start(
                request = startRequest(),
                callbacks = callbacks(bridge.calls),
                canStart = { false },
            )
        }

        assertTrue(bridge.calls.isEmpty())
        assertEquals(BridgeResourcePhase.Preparing, resources.snapshot.phase)
        assertTrue(resources.snapshot.callbacksRequireCleanup)
    }

    @Test
    fun tunFailureRetainsNativeStopAndCallbackObligations() {
        val resources = preparedResources()
        val bridge = RecordingSessionBridge(failSetTun = true)

        assertThrows(IllegalStateException::class.java) {
            BridgeSessionController(bridge, resources).start(
                request = startRequest(),
                callbacks = callbacks(bridge.calls),
                canStart = { true },
            )
        }

        assertEquals(BridgeResourcePhase.TunTransferPending, resources.snapshot.phase)
        assertTrue(resources.snapshot.nativeStopRequired)
        assertTrue(resources.snapshot.callbacksRequireCleanup)
    }

    @Test
    fun startFailureRemainsRetryableWithoutInventingSessionOwnership() {
        val resources = preparedResources()
        val bridge = RecordingSessionBridge(failStart = true)

        assertThrows(IllegalStateException::class.java) {
            BridgeSessionController(bridge, resources).start(
                request = startRequest(),
                callbacks = callbacks(bridge.calls),
                canStart = { true },
            )
        }

        assertEquals(BridgeResourcePhase.StartPending, resources.snapshot.phase)
        assertEquals(0L, resources.snapshot.sessionId)
        assertTrue(resources.snapshot.nativeStopRequired)
        assertTrue(resources.snapshot.callbacksRequireCleanup)
    }

    private fun preparedResources(): BridgeResourceStateMachine = BridgeResourceStateMachine().apply {
        beginPreparation(epoch = 7)
    }

    private fun startRequest(): BridgeSessionStartRequest = BridgeSessionStartRequest(
        configJson = "config-json",
        disabledOutboundTags = listOf("profile-b"),
        tunFd = 37,
        mtu = 1400,
        logLevel = "debug",
    )

    private fun callbacks(
        calls: MutableList<String>,
        onRegistrationFailure: (String, Throwable) -> Unit = { event, error ->
            calls += "eventFailure:$event:${error.message}"
        },
    ): BridgeSessionCallbacks = BridgeSessionCallbacks(
        onLog = {},
        onStatus = {},
        protectSocket = { true },
        identifyApp = { null },
        configureFlowAnalysis = { calls += "configureFlowAnalysis" },
        onInitialStatus = { calls += "initialStatus:$it" },
        onOptionalEventRegistrationFailure = onRegistrationFailure,
    )
}

private class RecordingSessionBridge(
    private val failingEvent: String? = null,
    private val failSetTun: Boolean = false,
    private val failStart: Boolean = false,
) : TcptunBridge {
    val calls = mutableListOf<String>()
    private var currentLogLevel = "info"

    override fun configure(configJson: String) {
        calls += "configure:$configJson"
    }

    override fun setTun(fd: Int, mtu: Int) {
        calls += "setTun:$fd:$mtu"
        if (failSetTun) throw IllegalStateException("set tun failed")
    }

    override fun start(disabledOutboundTags: List<String>): Long {
        calls += "start:${disabledOutboundTags.joinToString()}"
        if (failStart) throw IllegalStateException("start failed")
        return 41L
    }

    override fun statusJson(): String {
        calls += "statusJson"
        return "{\"state\":\"starting\"}"
    }

    override fun setLogLevel(level: String) {
        calls += "setLogLevel:$level"
        currentLogLevel = level
    }

    override fun logLevel(): String {
        calls += "logLevel"
        return currentLogLevel
    }

    override fun setLogCallback(onLog: (String) -> Unit) {
        calls += "setLogCallback"
    }

    override fun setStatusCallback(onStatus: (String) -> Unit) {
        calls += "setStatusCallback"
    }

    override fun registerEvent(event: String) {
        calls += "register:$event"
        if (event == failingEvent) throw IllegalStateException("registration failed")
    }

    override fun setSocketProtector(onProtect: (Int) -> Boolean) {
        calls += "setSocketProtector"
    }

    override fun setAppIdentityProvider(onIdentify: (String) -> String?) {
        calls += "setAppIdentityProvider"
    }

    override fun startOutbound(tag: String) = unexpected("startOutbound")
    override fun stopOutbound(tag: String, force: Boolean, timeoutMillis: Long) = unexpected("stopOutbound")
    override fun switchOutbound(tag: String, stopPrevious: Boolean, timeoutMillis: Long) = unexpected("switchOutbound")
    override fun probeOutbound(tag: String, host: String, port: Int, timeoutMillis: Long): Long =
        unexpected("probeOutbound")
    override fun probeOutboundHealth(tag: String, host: String, port: Int, timeoutMillis: Long): Long =
        unexpected("probeOutboundHealth")
    override fun outboundsStatusJson(): String = unexpected("outboundsStatusJson")
    override fun stop() = unexpected("stop")
    override fun sessionId(): Long = unexpected("sessionId")
    override fun waitStopped(sessionId: Long, timeoutMillis: Long) = unexpected("waitStopped")
    override fun close() = unexpected("close")
    override fun status(): String = unexpected("status")
    override fun clearLogCallback() = unexpected("clearLogCallback")
    override fun clearStatusCallback() = unexpected("clearStatusCallback")
    override fun unregisterEvent(event: String) = unexpected("unregisterEvent")
    override fun clearSocketProtector() = unexpected("clearSocketProtector")
    override fun clearAppIdentityProvider() = unexpected("clearAppIdentityProvider")
    override fun setFlowAnalysisApp(packageName: String) = unexpected("setFlowAnalysisApp")
    override fun setFlowCallback(onFlow: (String) -> Unit) = unexpected("setFlowCallback")
    override fun clearFlowCallback() = unexpected("clearFlowCallback")

    private fun unexpected(method: String): Nothing = error("unexpected bridge call: $method")
}
