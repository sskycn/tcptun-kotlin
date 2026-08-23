package com.tcptun.client

/** Serializes health JNI reads and validates their captured runtime ownership. */
internal class LockedHealthBridgePort(
    private val lock: Any,
    private val bridge: () -> TcptunBridge,
    private val isOwnershipCurrent: (VpnRuntimeOwnership) -> Boolean,
    private val hasActiveConfig: () -> Boolean,
) : HealthBridgePort {
    override fun statusJson(ownership: VpnRuntimeOwnership): String = synchronized(lock) {
        check(isOwnershipCurrent(ownership) && hasActiveConfig()) {
            "tcptun session is unavailable"
        }
        bridge().statusJson()
    }

    override fun outboundsStatusJson(ownership: VpnRuntimeOwnership): String = synchronized(lock) {
        check(isOwnershipCurrent(ownership)) { "tcptun session is unavailable" }
        bridge().outboundsStatusJson()
    }

    override fun probeOutboundHealth(
        ownership: VpnRuntimeOwnership,
        tag: String,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): Long = synchronized(lock) {
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
) : TcpingBridgePort {
    override fun probeOutbound(
        ownership: VpnRuntimeOwnership,
        tag: String,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): Long = synchronized(lock) {
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
