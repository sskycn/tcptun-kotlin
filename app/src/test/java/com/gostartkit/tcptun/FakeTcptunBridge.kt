package com.tcptun.client

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small fault-injecting test double for the existing Android bridge seam.
 * Production continues to use ReflectionTcptunBridge; this class deliberately
 * does not mirror androidbridge.Engine or introduce a second production API.
 */
internal class FakeTcptunBridge(
    private val failures: Failures = Failures(),
    private val session: Long = 41L,
    private var currentStatus: String = "Running",
    private var currentStatusReason: String = "",
    private val onStart: (() -> Unit)? = null,
) : TcptunBridge {
    data class Failures(
        val configure: Throwable? = null,
        val setTun: Throwable? = null,
        val start: Throwable? = null,
        val stop: Throwable? = null,
        val waitStopped: Throwable? = null,
        val abort: Throwable? = null,
        val close: Throwable? = null,
    )

    val calls = CopyOnWriteArrayList<String>()
    var configuredJson: String? = null
        private set
    var tunFd: Int? = null
        private set
    var callbacksInstalled = false
        private set
    var callbacksCleared = false
        private set
    var closed = false
        private set
    var abortCalled = false
        private set
    private var statusCallback: ((String) -> Unit)? = null
    private val statusCallbackHistory = mutableListOf<(String) -> Unit>()

    override fun configure(configJson: String) {
        calls += "configure"
        failures.configure?.let { throw it }
        configuredJson = configJson
    }

    override fun setPowerSave(enabled: Boolean) {
        calls += "setPowerSave:$enabled"
    }

    override fun setTun(fd: Int, mtu: Int) {
        calls += "setTun:$fd:$mtu"
        failures.setTun?.let { throw it }
        tunFd = fd
    }

    override fun start(disabledOutboundTags: List<String>): Long {
        calls += "start"
        onStart?.invoke()
        failures.start?.let { throw it }
        currentStatus = "Running"
        return session
    }

    override fun stop() {
        calls += "stop"
        failures.stop?.let { throw it }
        currentStatus = "Stopped"
    }

    override fun waitStopped(sessionId: Long, timeoutMillis: Long) {
        calls += "waitStopped:$sessionId:$timeoutMillis"
        failures.waitStopped?.let { throw it }
        currentStatus = "Stopped"
    }

    override fun abort() {
        calls += "abort"
        abortCalled = true
        failures.abort?.let { throw it }
        currentStatus = "Stopped"
    }

    override fun close() {
        calls += "close"
        failures.close?.let { throw it }
        closed = true
    }

    override fun sessionId(): Long = session
    override fun status(): String = currentStatus
    override fun statusJson(): String = """{"reason":"$currentStatusReason"}"""

    override fun setLogCallback(onLog: (String) -> Unit) {
        calls += "setLogCallback"
        callbacksInstalled = true
    }

    override fun setStatusCallback(onStatus: (String) -> Unit) {
        calls += "setStatusCallback"
        callbacksInstalled = true
        statusCallback = onStatus
        statusCallbackHistory += onStatus
    }

    override fun clearLogCallback() {
        calls += "clearLogCallback"
        callbacksCleared = true
    }

    override fun clearStatusCallback() {
        calls += "clearStatusCallback"
        callbacksCleared = true
        statusCallback = null
    }

    override fun registerEvent(event: String) {
        calls += "register:$event"
    }

    override fun unregisterEvent(event: String) {
        calls += "unregister:$event"
    }

    override fun setSocketProtector(onProtect: (Int) -> Boolean) = Unit
    override fun clearSocketProtector() {
        calls += "clearSocketProtector"
        callbacksCleared = true
    }

    override fun setAppIdentityProvider(onIdentify: (String) -> String?) = Unit
    override fun clearAppIdentityProvider() {
        calls += "clearAppIdentityProvider"
        callbacksCleared = true
    }

    override fun setFlowAnalysisApp(packageName: String) = Unit
    override fun setFlowCallback(onFlow: (String) -> Unit) = Unit
    override fun clearFlowCallback() {
        calls += "clearFlowCallback"
        callbacksCleared = true
    }

    override fun setLogLevel(level: String) = Unit
    override fun logLevel(): String = DefaultLogLevel
    override fun startOutbound(tag: String) = Unit
    override fun stopOutbound(tag: String, force: Boolean, timeoutMillis: Long) = Unit
    override fun switchOutbound(tag: String, stopPrevious: Boolean, timeoutMillis: Long) = Unit
    override fun probeOutbound(tag: String, host: String, port: Int, timeoutMillis: Long): Long = 0L
    override fun probeOutboundHealth(tag: String, host: String, port: Int, timeoutMillis: Long): Long = 0L
    override fun outboundsStatusJson(): String = "[]"

    fun emitStatus(json: String) {
        statusCallback?.invoke(json)
    }

    fun emitStatusFromInstallation(index: Int, json: String) {
        statusCallbackHistory[index](json)
    }
}
