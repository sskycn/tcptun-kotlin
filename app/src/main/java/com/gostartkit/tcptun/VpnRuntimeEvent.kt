package com.tcptun.client

internal sealed interface VpnRuntimeEvent {
    data class StartRequested(
        val token: VpnRuntimeCommandToken,
    ) : VpnRuntimeEvent

    data class StartExecutionRequested(
        val token: VpnRuntimeCommandToken,
        val request: VpnRuntimeStartRequest,
        val hasRuntimeResources: Boolean,
    ) : VpnRuntimeEvent

    data class ReplacementStopStarted(
        val token: VpnRuntimeCommandToken,
    ) : VpnRuntimeEvent

    data class ReplacementStopCompleted(
        val token: VpnRuntimeCommandToken,
    ) : VpnRuntimeEvent

    data class StartSucceeded(
        val token: VpnRuntimeCommandToken,
        val plan: ProfileRunPlan,
    ) : VpnRuntimeEvent

    data class StartFailed(
        val token: VpnRuntimeCommandToken,
        val request: VpnRuntimeStartRequest,
        val error: Throwable,
    ) : VpnRuntimeEvent

    data class StopRequested(
        val token: VpnRuntimeCommandToken,
        val reason: String,
    ) : VpnRuntimeEvent

    data class StopExecutionRequested(
        val token: VpnRuntimeCommandToken,
        val reason: String,
        val options: VpnRuntimeStopOptions,
    ) : VpnRuntimeEvent

    data class CleanupReleased(
        val owner: VpnRuntimeCleanupOwner,
    ) : VpnRuntimeEvent

    data class CleanupRetained(
        val owner: VpnRuntimeCleanupOwner,
    ) : VpnRuntimeEvent

    data class NoOpStartCompleted(
        val token: VpnRuntimeCommandToken,
        val runningPlan: ProfileRunPlan?,
    ) : VpnRuntimeEvent

    data class Destroyed(
        val token: VpnRuntimeCommandToken,
    ) : VpnRuntimeEvent
}
