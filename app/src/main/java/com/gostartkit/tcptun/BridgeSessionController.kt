package com.tcptun.client

internal data class BridgeSessionStartRequest(
    val configJson: String,
    val disabledOutboundTags: List<String>,
    val tunFd: Int,
    val mtu: Int,
    val powerSavingMode: Boolean,
    val logLevel: String,
)

internal data class BridgeSessionCallbacks(
    val onLog: (String) -> Unit,
    val onStatus: (String) -> Unit,
    val protectSocket: (Int) -> Boolean,
    val configureFlowAnalysis: () -> Unit,
    val onInitialStatus: (String) -> Unit,
    val onOptionalEventRegistrationFailure: (String, Throwable) -> Unit,
)

/**
 * Executes the ordered JNI transaction that transfers one configured session
 * to the native Engine. Callers remain responsible for serialization, runtime
 * lease ownership, readiness waiting, and cleanup after a failed transaction.
 */
internal class BridgeSessionController(
    private val bridge: TcptunBridge,
    private val resources: BridgeResourceStateMachine,
) {
    fun start(
        request: BridgeSessionStartRequest,
        callbacks: BridgeSessionCallbacks,
        canStart: () -> Boolean,
    ): Long {
        check(canStart()) { "tcptun start was cancelled" }
        bridge.setPowerSave(request.powerSavingMode)
        bridge.setLogCallback(callbacks.onLog)
        bridge.setStatusCallback(callbacks.onStatus)
        TcptunBridgeEvents.DefaultRegistered.forEach { event ->
            try {
                bridge.registerEvent(event)
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                callbacks.onOptionalEventRegistrationFailure(event, error)
            }
        }
        bridge.setSocketProtector(callbacks.protectSocket)
        // App-aware configuration owns the optional identity callback as one unit.
        // Leaving the provider unset when app metadata is unused avoids one
        // gomobile/JNI round trip and a flow JSON allocation per connection.
        callbacks.configureFlowAnalysis()
        callbacks.onInitialStatus(bridge.statusJson())
        bridge.configure(request.configJson)
        bridge.setLogLevel(request.logLevel)
        check(bridge.logLevel() == request.logLevel) {
            "tcptun bridge did not apply log.level=${request.logLevel}"
        }
        resources.beginTunTransfer()
        bridge.setTun(request.tunFd, request.mtu)
        resources.beginStart()
        val sessionId = bridge.start(request.disabledOutboundTags)
        resources.sessionStarted(sessionId, request.configJson)
        return sessionId
    }
}
