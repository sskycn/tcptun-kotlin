package com.tcptun.client

/** Effects selected by the reducer and executed outside the actor lane. */
internal sealed interface VpnRuntimeEffect {
    data class StartRuntime(
        val token: VpnRuntimeCommandToken,
        val request: VpnRuntimeStartRequest,
        val replaceExisting: Boolean,
    ) : VpnRuntimeEffect

    data class StopRuntime(
        val token: VpnRuntimeCommandToken,
        val reason: String,
        val options: VpnRuntimeStopOptions,
    ) : VpnRuntimeEffect

    data class RollbackStart(
        val token: VpnRuntimeCommandToken,
        val request: VpnRuntimeStartRequest,
        val error: Throwable,
        val superseded: Boolean,
    ) : VpnRuntimeEffect
}

internal data class VpnRuntimeDecision(
    val state: VpnRuntimeState,
    val effects: List<VpnRuntimeEffect> = emptyList(),
)
