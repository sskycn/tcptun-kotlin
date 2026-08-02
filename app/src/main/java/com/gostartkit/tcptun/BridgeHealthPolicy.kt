package com.tcptun.client

/**
 * Event-first VPN health policy. There is no routine timer-based health polling
 * in either power-saving or normal mode. The monitor sleeps until an event wakes
 * it (network change, core status, UI refresh, connection membership change).
 *
 * A detected failure still gets one bounded confirmation timer so recovery can
 * reach its restart threshold without waiting for an unrelated event.
 *
 * Pool member health ([shouldProbeMemberHealth]) runs only when explicitly
 * forced by an event path so balance selection can call ProbeOutboundHealth
 * without background sweeps. Aggregate SOCKS/HTTP upstream probes remain
 * UI-only ([shouldRunUpstreamProbe]).
 */
internal object BridgeHealthPolicy {
    /** One-shot retry used to confirm a failure before restarting the bridge. */
    const val FAILURE_CONFIRM_INTERVAL_MS = 15_000L

    /** Let Android finish a handover before rebuilding sockets on the selected network. */
    const val NETWORK_HANDOVER_SETTLE_MS = 1_500L

    private const val BRIDGE_RECOVERY_INITIAL_DELAY_MS = 1_000L
    private const val BRIDGE_RECOVERY_MAX_DELAY_MS = 30_000L

    /**
     * After VPN start / bridge restart, wait before the first member health
     * probe so underlying routing and tunnel dials can settle. Probing too
     * early commonly yields "no route to host" and falsely degrade every pool
     * member right after multi-connection start.
     */
    const val MEMBER_HEALTH_STARTUP_DELAY_MS = 4_000L

    /** Short settle after StartOutbound / StopOutbound pool membership changes. */
    const val MEMBER_HEALTH_MEMBERSHIP_DELAY_MS = 2_000L

    /** Background safety checks only ask engine status via callbacks; loopback TCP is UI-facing. */
    fun shouldProbeLocalProxy(uiVisible: Boolean): Boolean = uiVisible

    /**
     * Aggregate SOCKS/TLS/HTTP upstream probes are never timed. They run only
     * when the UI is visible and a user-driven refresh forced the next probe.
     */
    fun shouldRunUpstreamProbe(uiVisible: Boolean, force: Boolean): Boolean =
        uiVisible && force

    /**
     * StatusJSON reconciliation is only for UI-driven recovery (pull / app
     * visible), not for every event wake. Callbacks already carry live stats.
     */
    fun shouldReconcileStatusJson(uiVisible: Boolean, force: Boolean): Boolean =
        uiVisible && force

    /**
     * Per-member ProbeOutboundHealth updates balance dynamic scores in Go.
     * Only forced probes run (network / runtime issue / membership / UI).
     * [notBeforeMs] still blocks probes during the settle window.
     */
    fun shouldProbeMemberHealth(
        force: Boolean,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Boolean {
        if (notBeforeMs > 0L && nowMs < notBeforeMs) return false
        return force
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
     * No routine timer in any mode. A detected failure still gets one bounded
     * confirmation check so the restart threshold can be reached.
     *
     * [powerSaving] is retained for call-site compatibility and no longer
     * enables a safety poll interval.
     */
    @Suppress("UNUSED_PARAMETER")
    fun nextCheckDelayMs(powerSaving: Boolean, confirmingFailure: Boolean): Long? = when {
        confirmingFailure -> FAILURE_CONFIRM_INTERVAL_MS
        else -> null
    }

    /** Exponential restart retry with a bounded delay; attempts continue while VPN is desired. */
    fun bridgeRecoveryDelayMs(attempt: Int): Long {
        require(attempt > 0) { "bridge recovery attempt must be positive" }
        var delay = BRIDGE_RECOVERY_INITIAL_DELAY_MS
        repeat((attempt - 1).coerceAtMost(30)) {
            if (delay >= BRIDGE_RECOVERY_MAX_DELAY_MS) return BRIDGE_RECOVERY_MAX_DELAY_MS
            delay = (delay * 2).coerceAtMost(BRIDGE_RECOVERY_MAX_DELAY_MS)
        }
        return delay
    }

    fun shouldRestartForNetworkHandover(
        initialSelection: Boolean,
        networkAvailable: Boolean,
        vpnRunning: Boolean,
    ): Boolean = !initialSelection && networkAvailable && vpnRunning

    fun isStructuralRuntimeChange(previous: RuntimeSettings, next: RuntimeSettings): Boolean {
        return previous.mtu != next.mtu ||
            previous.socksPort != next.socksPort ||
            previous.localProxyProtocol != next.localProxyProtocol ||
            previous.socksListenAll != next.socksListenAll ||
            previous.socksUsername != next.socksUsername ||
            previous.socksPassword != next.socksPassword ||
            previous.routeLocalProxyTraffic != next.routeLocalProxyTraffic ||
            previous.defaultOutbound != next.defaultOutbound
    }

    fun requiresRuntimeRestart(
        forceRestart: Boolean,
        previous: RuntimeSettings,
        next: RuntimeSettings,
    ): Boolean = forceRestart || isStructuralRuntimeChange(previous, next)
}
