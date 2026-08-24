package com.tcptun.client

/** Pure logical transition function. It never touches Android, JNI, persistence, or executors. */
internal fun reduceRuntime(
    state: VpnRuntimeState,
    event: VpnRuntimeEvent,
): VpnRuntimeDecision {
    if (state.phase is VpnRuntimePhase.Destroyed) {
        return if (event is VpnRuntimeEvent.StartFailed) {
            VpnRuntimeDecision(
                state,
                listOf(
                    VpnRuntimeEffect.RollbackStart(
                        event.token,
                        event.request,
                        event.error,
                        superseded = true,
                    ),
                ),
            )
        } else {
            VpnRuntimeDecision(state)
        }
    }
    return when (event) {
        is VpnRuntimeEvent.StartRequested -> reduceStartRequested(state, event)
        is VpnRuntimeEvent.StartExecutionRequested -> {
            if (!state.isStarting(event.token)) VpnRuntimeDecision(state) else {
                VpnRuntimeDecision(
                    state,
                    listOf(
                        VpnRuntimeEffect.StartRuntime(
                            event.token,
                            event.request,
                            event.hasRuntimeResources,
                        ),
                    ),
                )
            }
        }
        is VpnRuntimeEvent.ReplacementStopStarted -> {
            if (!state.isStarting(event.token)) VpnRuntimeDecision(state) else {
                VpnRuntimeDecision(
                    state.copy(
                        phase = VpnRuntimePhase.Stopping(event.token, "runtime replacement"),
                        stopping = true,
                    ),
                )
            }
        }
        is VpnRuntimeEvent.ReplacementStopCompleted -> {
            val phase = state.phase as? VpnRuntimePhase.Stopping
            if (phase?.token != event.token || !state.isCurrent(event.token)) {
                VpnRuntimeDecision(state)
            } else {
                VpnRuntimeDecision(
                    state.copy(
                        phase = VpnRuntimePhase.Starting(event.token),
                        stopping = false,
                    ),
                )
            }
        }
        is VpnRuntimeEvent.StartSucceeded -> {
            if (!state.isStarting(event.token)) VpnRuntimeDecision(state) else {
                VpnRuntimeDecision(
                    state.copy(
                        phase = VpnRuntimePhase.Running(event.token),
                        runningPlan = event.plan,
                        explicitStopRequested = false,
                        stopping = false,
                        bridgeRestarting = false,
                    ),
                )
            }
        }
        is VpnRuntimeEvent.StartFailed -> reduceStartFailed(state, event)
        is VpnRuntimeEvent.StopRequested -> reduceStopRequested(state, event)
        is VpnRuntimeEvent.StopExecutionRequested -> {
            val phase = state.phase as? VpnRuntimePhase.Stopping
            if (phase?.token != event.token || !state.isCurrent(event.token)) {
                VpnRuntimeDecision(state)
            } else {
                VpnRuntimeDecision(
                    state,
                    listOf(VpnRuntimeEffect.StopRuntime(event.token, event.reason, event.options)),
                )
            }
        }
        is VpnRuntimeEvent.CleanupReleased -> reduceCleanupResult(state, event.owner, true)
        is VpnRuntimeEvent.CleanupRetained -> reduceCleanupResult(state, event.owner, false)
        is VpnRuntimeEvent.NoOpStartCompleted -> reduceNoOpStart(state, event)
        is VpnRuntimeEvent.Destroyed -> VpnRuntimeDecision(
            state.copy(
                phase = VpnRuntimePhase.Destroyed,
                lifecycleGeneration = event.token.lifecycleGeneration,
                serviceInstanceId = event.token.serviceInstanceId,
                recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
                explicitStopRequested = true,
                stopping = true,
                bridgeRestarting = false,
            ),
        )
    }
}

private fun reduceStartRequested(
    state: VpnRuntimeState,
    event: VpnRuntimeEvent.StartRequested,
): VpnRuntimeDecision {
    if (!event.token.isNewerThan(state)) return VpnRuntimeDecision(state)
    return VpnRuntimeDecision(
        state.copy(
            phase = VpnRuntimePhase.Starting(event.token),
            lifecycleGeneration = event.token.lifecycleGeneration,
            persistentCommandGeneration = event.token.persistentGeneration,
            serviceInstanceId = event.token.serviceInstanceId,
            recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
            explicitStopRequested = false,
            stopping = false,
            bridgeRestarting = false,
        ),
    )
}

private fun reduceStopRequested(
    state: VpnRuntimeState,
    event: VpnRuntimeEvent.StopRequested,
): VpnRuntimeDecision {
    if (!event.token.isNewerThan(state)) return VpnRuntimeDecision(state)
    return VpnRuntimeDecision(
        state.copy(
            phase = VpnRuntimePhase.Stopping(event.token, event.reason),
            lifecycleGeneration = event.token.lifecycleGeneration,
            serviceInstanceId = event.token.serviceInstanceId,
            recoveryGeneration = nextRuntimeGeneration(state.recoveryGeneration),
            explicitStopRequested = true,
            stopping = true,
            bridgeRestarting = false,
        ),
    )
}

private fun reduceStartFailed(
    state: VpnRuntimeState,
    event: VpnRuntimeEvent.StartFailed,
): VpnRuntimeDecision {
    val current = state.isCurrent(event.token)
    val next = if (current) {
        state.copy(
            phase = VpnRuntimePhase.CleaningUp(
                VpnRuntimeCleanupOwner.StartRollback(event.token),
                "failed start rollback",
            ),
            runningPlan = null,
            stopping = true,
            bridgeRestarting = false,
        )
    } else {
        state
    }
    return VpnRuntimeDecision(
        next,
        listOf(
            VpnRuntimeEffect.RollbackStart(
                event.token,
                event.request,
                event.error,
                superseded = !current,
            ),
        ),
    )
}

private fun reduceCleanupResult(
    state: VpnRuntimeState,
    owner: VpnRuntimeCleanupOwner,
    released: Boolean,
): VpnRuntimeDecision {
    val ownerIsCurrent = when (owner) {
        is VpnRuntimeCleanupOwner.Stop ->
            (state.phase as? VpnRuntimePhase.Stopping)?.token == owner.lifecycleToken
        is VpnRuntimeCleanupOwner.StartRollback,
        is VpnRuntimeCleanupOwner.RecoveryRollback,
        -> (state.phase as? VpnRuntimePhase.CleaningUp)?.owner == owner
    }
    if (!ownerIsCurrent || !state.isCurrent(owner.lifecycleToken)) {
        return VpnRuntimeDecision(state)
    }
    return if (!released) {
        VpnRuntimeDecision(state)
    } else {
        VpnRuntimeDecision(
            state.copy(
                phase = VpnRuntimePhase.Idle,
                runningPlan = null,
                stopping = false,
                bridgeRestarting = false,
            ),
        )
    }
}

private fun reduceNoOpStart(
    state: VpnRuntimeState,
    event: VpnRuntimeEvent.NoOpStartCompleted,
): VpnRuntimeDecision {
    if (!state.isStarting(event.token)) return VpnRuntimeDecision(state)
    return VpnRuntimeDecision(
        if (event.runningPlan == null) {
            state.copy(
                phase = VpnRuntimePhase.Idle,
                runningPlan = null,
                stopping = false,
                bridgeRestarting = false,
            )
        } else {
            state.copy(
                phase = VpnRuntimePhase.Running(event.token),
                runningPlan = event.runningPlan,
                explicitStopRequested = false,
                stopping = false,
                bridgeRestarting = false,
            )
        },
    )
}

internal fun VpnRuntimeState.isCurrent(token: VpnRuntimeCommandToken): Boolean =
    phase !is VpnRuntimePhase.Destroyed &&
        token.serviceInstanceId == serviceInstanceId &&
        token.lifecycleGeneration == lifecycleGeneration &&
        token.persistentGeneration == persistentCommandGeneration

private fun VpnRuntimeState.isStarting(token: VpnRuntimeCommandToken): Boolean =
    (phase as? VpnRuntimePhase.Starting)?.token == token && isCurrent(token)

private fun VpnRuntimeCommandToken.isNewerThan(state: VpnRuntimeState): Boolean =
    lifecycleGeneration > state.lifecycleGeneration &&
        persistentGeneration >= state.persistentCommandGeneration

internal fun nextRuntimeGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L
