package com.tcptun.client

internal data class BridgeSessionStopCallbacks(
    val onNativeStillStopping: (Throwable) -> Unit,
    val onNativeStoppedWithError: (Throwable) -> Unit,
    val onCleanupFailure: (String, Throwable) -> Unit,
)

/**
 * Releases native session and callback ownership in the order required by the
 * bridge contract. The caller must invalidate the active epoch first and
 * serialize this transaction with bridge startup.
 */
internal class BridgeSessionStopController(
    private val bridge: TcptunBridge,
    private val resources: BridgeResourceStateMachine,
) {
    fun stop(
        settleTimeoutMillis: Long,
        callbacks: BridgeSessionStopCallbacks,
    ) {
        val ownership = resources.snapshot
        val shouldStop = ownership.nativeStopRequired
        val shouldClearCallbacks = ownership.callbacksRequireCleanup
        if (!shouldStop && !shouldClearCallbacks) return

        if (shouldStop) {
            val sessionId = ownership.sessionId.takeIf { it > 0L }
                ?: runRecoverableCatching { bridge.sessionId() }.getOrDefault(0L)
            val result = stopAndAwaitBridgeSession(
                bridge = bridge,
                sessionId = sessionId,
                settleTimeoutMillis = settleTimeoutMillis,
            )
            try {
                result.requireSettled()
            } catch (error: Throwable) {
                callbacks.onNativeStillStopping(
                    result.error ?: IllegalStateException("unknown stop failure"),
                )
                throw error
            }
            resources.nativeStopped()
            result.error?.let(callbacks.onNativeStoppedWithError)
        } else {
            resources.nativeStopped()
        }

        if (!shouldClearCallbacks) return
        TcptunBridgeEvents.DefaultRegistered.forEach { event ->
            runCleanupStep("unregister bridge event $event", callbacks) {
                bridge.unregisterEvent(event)
            }
        }
        val callbackFailure = clearCallbacks(callbacks)
        if (callbackFailure == null) {
            resources.callbacksReleased()
        } else {
            throw IllegalStateException(
                "tcptun callbacks were not released cleanly",
                callbackFailure,
            )
        }
    }

    private fun clearCallbacks(callbacks: BridgeSessionStopCallbacks): Throwable? {
        var failure: Throwable? = null
        fun clear(label: String, action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                reportCleanupFailure(label, error, callbacks)
                failure = failure?.apply { addSuppressed(error) } ?: error
            }
        }
        clear("clear flow callback") { bridge.clearFlowCallback() }
        clear("clear app identity callback") { bridge.clearAppIdentityProvider() }
        clear("clear socket protector") { bridge.clearSocketProtector() }
        clear("clear status callback") { bridge.clearStatusCallback() }
        clear("clear log callback") { bridge.clearLogCallback() }
        return failure
    }

    private fun runCleanupStep(
        label: String,
        callbacks: BridgeSessionStopCallbacks,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            reportCleanupFailure(label, error, callbacks)
        }
    }

    private fun reportCleanupFailure(
        label: String,
        error: Throwable,
        callbacks: BridgeSessionStopCallbacks,
    ) {
        try {
            callbacks.onCleanupFailure(label, error)
        } catch (reportingError: Throwable) {
            if (reportingError.isFatalProcessError()) throw reportingError
        }
    }
}
