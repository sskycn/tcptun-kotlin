package com.tcptun.client

/**
 * Event-first VPN health policy. Power saving disables routine periodic polling;
 * disabling it enables a low-frequency safety check.
 *
 * Wakes come from:
 * - network change callbacks
 * - core status callbacks (degraded / error / stopped)
 * - pull-to-refresh / app-visible diagnostic refresh
 * - active connection / TCPing side effects that request a check
 *
 * A failed check also gets one bounded confirmation timer so recovery can reach
 * its restart threshold without waiting for an unrelated event.
 *
 * Pool member health ([shouldProbeMemberHealth]) is separate from the expensive
 * aggregate SOCKS/HTTP upstream probe ([shouldRunUpstreamProbe]): members can be
 * probed on background events so balance selection receives observeHealthProbe
 * updates without requiring the UI.
 */
internal object BridgeHealthPolicy {
    /** One-shot retry used to confirm a failure before restarting the bridge. */
    const val FAILURE_CONFIRM_INTERVAL_MS = 15_000L

    /** Safety polling used only when the user disables power saving. */
    const val SAFETY_INTERVAL_MS = 300_000L

    /**
     * Minimum gap between non-forced per-member balance health probes.
     * Forced probes (network change, degraded, UI refresh, pool membership
     * change) bypass this throttle.
     */
    const val MEMBER_HEALTH_MIN_INTERVAL_MS = 60_000L

    /** Background safety checks only ask the engine status; loopback TCP is UI-facing. */
    fun shouldProbeLocalProxy(uiVisible: Boolean): Boolean = uiVisible

    /**
     * Aggregate SOCKS/TLS/HTTP upstream probes are never timed. They run only
     * when the UI is visible and a user-driven refresh forced the next probe.
     */
    fun shouldRunUpstreamProbe(uiVisible: Boolean, force: Boolean): Boolean =
        uiVisible && force

    /**
     * Per-member [ProbeOutboundHealth] updates balance dynamic scores in Go.
     * Forced probes always run; otherwise the minimum interval applies so event
     * storms (connection count churn) do not hammer every pool member.
     */
    fun shouldProbeMemberHealth(
        force: Boolean,
        lastProbeAtMs: Long,
        nowMs: Long,
        minIntervalMs: Long = MEMBER_HEALTH_MIN_INTERVAL_MS,
    ): Boolean {
        if (force) return true
        if (lastProbeAtMs <= 0L) return true
        if (minIntervalMs <= 0L) return true
        return nowMs - lastProbeAtMs >= minIntervalMs
    }

    /**
     * Power saving has no routine timer. A detected failure still gets one
     * bounded confirmation check so the restart threshold can be reached.
     */
    fun nextCheckDelayMs(powerSaving: Boolean, confirmingFailure: Boolean): Long? = when {
        confirmingFailure -> FAILURE_CONFIRM_INTERVAL_MS
        powerSaving -> null
        else -> SAFETY_INTERVAL_MS
    }

    fun isStructuralRuntimeChange(previous: RuntimeSettings, next: RuntimeSettings): Boolean {
        return previous.mtu != next.mtu ||
            previous.socksPort != next.socksPort ||
            previous.localProxyProtocol != next.localProxyProtocol ||
            previous.socksListenAll != next.socksListenAll ||
            previous.socksUsername != next.socksUsername ||
            previous.socksPassword != next.socksPassword ||
            previous.routeLocalProxyTraffic != next.routeLocalProxyTraffic
    }

    fun requiresRuntimeRestart(
        forceRestart: Boolean,
        previous: RuntimeSettings,
        next: RuntimeSettings,
    ): Boolean = forceRestart || isStructuralRuntimeChange(previous, next)
}
