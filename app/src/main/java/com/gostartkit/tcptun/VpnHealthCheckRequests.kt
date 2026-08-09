package com.tcptun.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Process-wide request bus that hands health refreshes to the active Service instance. */
internal object VpnHealthCheckRequests {
    const val MaxMemberProbeDelayMs = 86_400_000L

    private val forceUpstreamProbe = AtomicBoolean(false)
    private val forceMemberProbe = AtomicBoolean(false)
    private val forceStatusReconcile = AtomicBoolean(false)
    private val monitorWakeCallback = AtomicReference<(() -> Unit)?>(null)
    private val memberProbeCallback = AtomicReference<((String, Long) -> Unit)?>(null)

    fun install(
        wakeMonitor: () -> Unit,
        scheduleMemberProbe: (String, Long) -> Unit,
    ) {
        monitorWakeCallback.set(wakeMonitor)
        memberProbeCallback.set(scheduleMemberProbe)
    }

    fun uninstall(
        wakeMonitor: () -> Unit,
        scheduleMemberProbe: (String, Long) -> Unit,
    ) {
        monitorWakeCallback.compareAndSet(wakeMonitor, null)
        memberProbeCallback.compareAndSet(scheduleMemberProbe, null)
    }

    fun requestHealthCheck(reason: String) {
        TcptunState.appendLog("bridge health check requested: $reason")
        monitorWakeCallback.get()?.invoke()
    }

    fun requestMemberProbe(reason: String, delayMs: Long = 0L) {
        val delay = delayMs.coerceIn(0L, MaxMemberProbeDelayMs)
        forceMemberProbe.set(true)
        val requester = memberProbeCallback.get()
        if (requester != null) requester(reason, delay) else requestHealthCheck(reason)
    }

    fun requestUiVisibleHealthCheck() {
        forceUpstreamProbe.set(true)
        forceMemberProbe.set(true)
        forceStatusReconcile.set(true)
        requestMemberProbe("app visible")
    }

    fun markMemberProbeForced() {
        forceMemberProbe.set(true)
    }

    fun consumeUpstreamProbeForce(): Boolean = forceUpstreamProbe.compareAndSet(true, false)

    fun restoreUpstreamProbeForce() {
        forceUpstreamProbe.set(true)
    }

    fun consumeMemberProbeForce(): Boolean = forceMemberProbe.compareAndSet(true, false)

    fun consumeStatusReconcileForce(): Boolean = forceStatusReconcile.compareAndSet(true, false)

    fun restoreStatusReconcileForce() {
        forceStatusReconcile.set(true)
    }
}
