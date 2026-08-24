package com.tcptun.client

internal data class VpnRuntimeCommandToken(
    val serviceInstanceId: Long,
    val lifecycleGeneration: Int,
    val persistentGeneration: Int,
)

internal data class VpnRuntimeRecoveryToken(
    val lifecycleToken: VpnRuntimeCommandToken,
    val recoveryGeneration: Long,
)

internal sealed interface VpnRuntimeCleanupOwner {
    val lifecycleToken: VpnRuntimeCommandToken

    data class Stop(
        override val lifecycleToken: VpnRuntimeCommandToken,
    ) : VpnRuntimeCleanupOwner

    data class StartRollback(
        override val lifecycleToken: VpnRuntimeCommandToken,
    ) : VpnRuntimeCleanupOwner

    data class RecoveryRollback(
        val recoveryToken: VpnRuntimeRecoveryToken,
    ) : VpnRuntimeCleanupOwner {
        override val lifecycleToken: VpnRuntimeCommandToken
            get() = recoveryToken.lifecycleToken
    }
}

/** Logical lifecycle only. Physical JNI ownership remains in BridgeResourceStateMachine. */
internal sealed interface VpnRuntimePhase {
    data object Idle : VpnRuntimePhase
    data class Starting(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data class Running(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data class Stopping(
        val token: VpnRuntimeCommandToken,
        val reason: String,
    ) : VpnRuntimePhase
    data class CleaningUp(
        val owner: VpnRuntimeCleanupOwner,
        val reason: String,
    ) : VpnRuntimePhase {
        val token: VpnRuntimeCommandToken
            get() = owner.lifecycleToken
    }
    data class Recovering(val token: VpnRuntimeCommandToken) : VpnRuntimePhase
    data object Destroyed : VpnRuntimePhase
}

/** Immutable logical state published by the single-writer [VpnRuntimeActor]. */
internal data class VpnRuntimeState(
    val phase: VpnRuntimePhase = VpnRuntimePhase.Idle,
    val lifecycleGeneration: Int = 0,
    val persistentCommandGeneration: Int = 0,
    val recoveryGeneration: Long = 0L,
    val serviceInstanceId: Long = 0L,
    val runningPlan: ProfileRunPlan? = null,
    val explicitStopRequested: Boolean = false,
    val stopping: Boolean = false,
    val bridgeRestarting: Boolean = false,
)

/** Compatibility name retained while callers migrate from VpnRuntimeCoordinator. */
internal typealias VpnRuntimeSnapshot = VpnRuntimeState
