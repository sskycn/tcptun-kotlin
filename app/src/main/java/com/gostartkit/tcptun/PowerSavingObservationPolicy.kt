package com.tcptun.client

/**
 * Keeps UI-only observation work quiet while a power-saving VPN is backgrounded.
 * Runtime state that affects tunnel correctness must not be gated through this policy.
 */
internal object PowerSavingObservationPolicy {
    fun shouldPublish(powerSaving: Boolean, uiVisible: Boolean): Boolean =
        !powerSaving || uiVisible
}
