package com.tcptun.client

/** Serializes health JNI reads and validates their captured runtime ownership. */
internal class LockedHealthBridgePort(
    private val lock: Any,
    private val bridge: () -> TcptunBridge,
    private val isOwnershipCurrent: (VpnRuntimeOwnership) -> Boolean,
    private val hasActiveConfig: () -> Boolean,
    private val log: (String) -> Unit = {},
) : HealthBridgePort {
    internal fun sharesBridgeLock(candidate: Any): Boolean = lock === candidate

    override fun statusJson(ownership: VpnRuntimeOwnership): String = observeBridgeCall(lock, ownership, "status_json", log) {
        check(isOwnershipCurrent(ownership) && hasActiveConfig()) {
            "tcptun session is unavailable"
        }
        bridge().statusJson()
    }

    override fun outboundsStatusJson(ownership: VpnRuntimeOwnership): String = observeBridgeCall(lock, ownership, "outbounds_status_json", log) {
        check(isOwnershipCurrent(ownership)) { "tcptun session is unavailable" }
        bridge().outboundsStatusJson()
    }

    override fun probeOutboundHealth(
        ownership: VpnRuntimeOwnership,
        tag: String,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): Long = observeBridgeCall(lock, ownership, "probe budget_ms=$timeoutMillis", log) {
        checkActive(ownership)
        bridge().probeOutboundHealth(tag, host, port, timeoutMillis).also {
            checkActive(ownership)
        }
    }

    private fun checkActive(ownership: VpnRuntimeOwnership) {
        check(!Thread.currentThread().isInterrupted && isOwnershipCurrent(ownership)) {
            "VPN session changed"
        }
    }
}

/** Serializes outbound TCPing JNI calls and checks ownership on both sides. */
internal class LockedTcpingBridgePort(
    private val lock: Any,
    private val bridge: () -> TcptunBridge,
    private val isOwnershipCurrent: (VpnRuntimeOwnership) -> Boolean,
    private val log: (String) -> Unit = {},
) : TcpingBridgePort {
    internal fun sharesBridgeLock(candidate: Any): Boolean = lock === candidate

    override fun probeOutbound(
        ownership: VpnRuntimeOwnership,
        tag: String,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): Long = observeBridgeCall(lock, ownership, "probe budget_ms=$timeoutMillis", log) {
        checkActive(ownership)
        bridge().probeOutbound(tag, host, port, timeoutMillis).also {
            checkActive(ownership)
        }
    }

    private fun checkActive(ownership: VpnRuntimeOwnership) {
        check(!Thread.currentThread().isInterrupted && isOwnershipCurrent(ownership)) {
            "VPN session changed"
        }
    }
}

internal object TcptunStateOutboundTcpingPort : OutboundTcpingStatePort {
    override fun isCurrent(requestId: Long) = TcptunState.isCurrentTcping(requestId)
    override fun isLatest(requestId: Long) = TcptunState.state.value.tcping.requestId == requestId

    override fun beginStep(requestId: Long, index: Int, total: Int, profileName: String) =
        TcptunState.beginTcpingStep(requestId, index, total, profileName)

    override fun completeStep(requestId: Long, result: TcpingLinkResult) =
        TcptunState.completeTcpingStep(requestId, result)

    override fun finish(requestId: Long) = TcptunState.finishTcping(requestId)
    override fun fail(requestId: Long, error: String) = TcptunState.failTcping(requestId, error)
    override fun log(message: String) = TcptunState.appendLog(message)
}

/** Evidence for lock contention versus a native call that never returns. The
 * timeout passed to native does not bound monitor acquisition or cancel JNI. */
private inline fun <T> observeBridgeCall(
    lock: Any,
    ownership: VpnRuntimeOwnership,
    operation: String,
    log: (String) -> Unit,
    action: () -> T,
): T {
    val started = System.nanoTime()
    val context = "${ownership.diagnosticId()} operation=$operation call_id=$started"
    log("bridge_control $context phase=waiting_lock")
    return synchronized(lock) {
        log("bridge_control $context phase=entered wait_ms=${(System.nanoTime() - started) / 1_000_000}")
        try {
            action()
        } finally {
            log("bridge_control $context phase=returned elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }
}
