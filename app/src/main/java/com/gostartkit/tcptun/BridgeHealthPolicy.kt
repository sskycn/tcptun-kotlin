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
     * change) bypass this throttle once the settle window has elapsed.
     */
    const val MEMBER_HEALTH_MIN_INTERVAL_MS = 60_000L

    /**
     * After VPN start / bridge restart, wait before the first member health
     * probe so underlying routing and tunnel dials can settle. Probing too
     * early commonly yields "no route to host" and falsely degrades every pool
     * member right after multi-connection start.
     */
    const val MEMBER_HEALTH_STARTUP_DELAY_MS = 4_000L

    /** Short settle after StartOutbound / StopOutbound pool membership changes. */
    const val MEMBER_HEALTH_MEMBERSHIP_DELAY_MS = 2_000L

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
     * [notBeforeMs] blocks all probes (including forced) until the settle
     * window ends. After that, forced probes always run; otherwise the minimum
     * interval applies so event storms do not hammer every pool member.
     */
    fun shouldProbeMemberHealth(
        force: Boolean,
        lastProbeAtMs: Long,
        nowMs: Long,
        notBeforeMs: Long = 0L,
        minIntervalMs: Long = MEMBER_HEALTH_MIN_INTERVAL_MS,
    ): Boolean {
        if (notBeforeMs > 0L && nowMs < notBeforeMs) return false
        if (force) return true
        if (lastProbeAtMs <= 0L) return true
        if (minIntervalMs <= 0L) return true
        return nowMs - lastProbeAtMs >= minIntervalMs
    }

    /** Routing/setup failures that should not stick as user-visible health text. */
    fun isTransientMemberProbeFailure(message: String): Boolean {
        val text = message.lowercase()
        return "no route to host" in text ||
            "network is unreachable" in text ||
            "enetunreach" in text ||
            "ehostunreach" in text ||
            "software caused connection abort" in text ||
            "network down" in text ||
            "enotconn" in text
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
