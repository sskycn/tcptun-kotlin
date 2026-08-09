package com.tcptun.client

internal enum class BridgeResourcePhase {
    Idle,
    Preparing,
    TunTransferPending,
    StartPending,
    SessionOwned,
    Stopping,
    CallbacksOwned,
    Closed,
}

internal data class BridgeResourceSnapshot(
    val phase: BridgeResourcePhase = BridgeResourcePhase.Idle,
    val epoch: Long = 0L,
    val sessionId: Long = 0L,
    val configJson: String? = null,
    val callbacksRequireCleanup: Boolean = false,
    val nativeStopRequired: Boolean = false,
)

/** Single-owner slot used for the Android-owned ParcelFileDescriptor. */
internal class ExclusiveResourceOwner<T : Any> {
    @Volatile
    private var current: T? = null

    val resource: T?
        get() = current

    @Synchronized
    fun acquire(resource: T) {
        check(current == null) { "resource already has an owner" }
        current = resource
    }

    @Synchronized
    fun release(): T? {
        val owned = current
        current = null
        return owned
    }
}

internal enum class RuntimeLeaseClaim {
    Acquired,
    AlreadyOwned,
    Cancelled,
    TimedOut,
}

/** Process-wide lease preventing old/new VpnService engines from overlapping. */
internal class BridgeRuntimeLease {
    private val monitor = Object()

    @Volatile
    private var ownerId = 0L

    val owner: Long
        get() = ownerId

    fun acquire(
        requestedOwnerId: Long,
        timeoutMillis: Long,
        canContinue: () -> Boolean = { true },
    ): RuntimeLeaseClaim {
        require(requestedOwnerId > 0L) { "runtime lease owner must be positive" }
        require(timeoutMillis >= 0L) { "runtime lease timeout must not be negative" }
        require(timeoutMillis <= Long.MAX_VALUE / 1_000_000L) { "runtime lease timeout is too large" }
        val timeoutNanos = timeoutMillis * 1_000_000L
        val deadlineNanos = System.nanoTime() + timeoutNanos
        synchronized(monitor) {
            while (ownerId != 0L && ownerId != requestedOwnerId) {
                if (!canContinue()) return RuntimeLeaseClaim.Cancelled
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return RuntimeLeaseClaim.TimedOut
                // Lifecycle ownership can be revoked without releasing the
                // current runtime lease (for example, when the user stops a
                // replacement service while the previous service is still
                // tearing down). Poll the cancellation predicate instead of
                // sleeping for the whole lease timeout so that the serialized
                // lifecycle executor is not held for tens of seconds.
                val waitMillis = (remainingNanos / 1_000_000L)
                    .coerceAtLeast(1L)
                    .coerceAtMost(CANCELLATION_POLL_INTERVAL_MILLIS)
                try {
                    monitor.wait(waitMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return RuntimeLeaseClaim.Cancelled
                }
            }
            if (!canContinue()) return RuntimeLeaseClaim.Cancelled
            if (ownerId == requestedOwnerId) return RuntimeLeaseClaim.AlreadyOwned
            ownerId = requestedOwnerId
            return RuntimeLeaseClaim.Acquired
        }
    }

    fun release(releasingOwnerId: Long): Boolean = synchronized(monitor) {
        if (ownerId != releasingOwnerId) return false
        ownerId = 0L
        monitor.notifyAll()
        true
    }

    private companion object {
        const val CANCELLATION_POLL_INTERVAL_MILLIS = 100L
    }
}

/**
 * Tracks host/native resource ownership independently from tcptun's user-facing
 * Running/Error status. Every mutation is serialized and every published
 * snapshot satisfies the invariants for its [BridgeResourcePhase].
 */
internal class BridgeResourceStateMachine {
    @Volatile
    private var current = BridgeResourceSnapshot()

    val snapshot: BridgeResourceSnapshot
        get() = current

    val activeEpoch: Long
        get() = current.epoch

    val activeConfigJson: String?
        get() = current.configJson

    val hasOwnedResources: Boolean
        get() = current.phase != BridgeResourcePhase.Idle &&
            current.phase != BridgeResourcePhase.Closed

    @Synchronized
    fun beginPreparation(epoch: Long) {
        require(epoch > 0L) { "bridge epoch must be positive" }
        check(current.phase == BridgeResourcePhase.Idle) {
            "cannot prepare tcptun bridge while resources are ${current.phase}"
        }
        transition(
            BridgeResourceSnapshot(
                phase = BridgeResourcePhase.Preparing,
                epoch = epoch,
                callbacksRequireCleanup = true,
            ),
        )
    }

    /** Claim cleanup before SetTun crosses JNI because it can partially succeed. */
    @Synchronized
    fun beginTunTransfer() {
        check(current.phase == BridgeResourcePhase.Preparing) {
            "cannot transfer TUN while resources are ${current.phase}"
        }
        transition(
            current.copy(
                phase = BridgeResourcePhase.TunTransferPending,
                nativeStopRequired = true,
            ),
        )
    }

    /** Claim cleanup before Start crosses JNI because it can start before throwing. */
    @Synchronized
    fun beginStart() {
        check(current.phase == BridgeResourcePhase.TunTransferPending) {
            "cannot start tcptun while resources are ${current.phase}"
        }
        transition(current.copy(phase = BridgeResourcePhase.StartPending))
    }

    @Synchronized
    fun sessionStarted(sessionId: Long, configJson: String) {
        require(sessionId > 0L) { "tcptun session ID must be positive" }
        check(current.phase == BridgeResourcePhase.StartPending) {
            "cannot own tcptun session while resources are ${current.phase}"
        }
        transition(
            current.copy(
                phase = BridgeResourcePhase.SessionOwned,
                sessionId = sessionId,
                configJson = configJson,
            ),
        )
    }

    /**
     * Invalidates the active epoch/config immediately and returns the ownership
     * that must be released. Repeated calls preserve an unsettled stop attempt.
     */
    @Synchronized
    fun beginStop(): BridgeResourceSnapshot {
        val owned = current
        when (owned.phase) {
            BridgeResourcePhase.Idle,
            BridgeResourcePhase.Closed,
            -> return owned

            BridgeResourcePhase.Stopping -> return owned
            BridgeResourcePhase.CallbacksOwned -> {
                transition(
                    owned.copy(
                        phase = BridgeResourcePhase.Stopping,
                        epoch = 0L,
                        sessionId = 0L,
                        configJson = null,
                        nativeStopRequired = false,
                    ),
                )
            }

            else -> {
                transition(
                    owned.copy(
                        phase = BridgeResourcePhase.Stopping,
                        epoch = 0L,
                        configJson = null,
                    ),
                )
            }
        }
        return owned
    }

    @Synchronized
    fun nativeStopped() {
        check(current.phase == BridgeResourcePhase.Stopping) {
            "cannot release native tcptun resources while resources are ${current.phase}"
        }
        transition(
            current.copy(
                phase = BridgeResourcePhase.CallbacksOwned,
                sessionId = 0L,
                configJson = null,
                nativeStopRequired = false,
            ),
        )
    }

    @Synchronized
    fun callbacksReleased() {
        check(current.phase == BridgeResourcePhase.CallbacksOwned) {
            "cannot release tcptun callbacks while resources are ${current.phase}"
        }
        transition(BridgeResourceSnapshot())
    }

    /** A successful Engine.Close releases every native and callback reference. */
    @Synchronized
    fun engineClosed() {
        transition(BridgeResourceSnapshot(phase = BridgeResourcePhase.Closed))
    }

    private fun transition(next: BridgeResourceSnapshot) {
        validate(next)
        current = next
    }

    private fun validate(state: BridgeResourceSnapshot) {
        when (state.phase) {
            BridgeResourcePhase.Idle,
            BridgeResourcePhase.Closed,
            -> check(
                state.epoch == 0L && state.sessionId == 0L && state.configJson == null &&
                    !state.callbacksRequireCleanup && !state.nativeStopRequired,
            ) { "${state.phase} cannot own bridge resources" }

            BridgeResourcePhase.Preparing -> check(
                state.epoch > 0L && state.sessionId == 0L && state.configJson == null &&
                    state.callbacksRequireCleanup && !state.nativeStopRequired,
            ) { "Preparing bridge ownership is invalid" }

            BridgeResourcePhase.TunTransferPending,
            BridgeResourcePhase.StartPending,
            -> check(
                state.epoch > 0L && state.sessionId == 0L && state.configJson == null &&
                    state.callbacksRequireCleanup && state.nativeStopRequired,
            ) { "${state.phase} bridge ownership is invalid" }

            BridgeResourcePhase.SessionOwned -> check(
                state.epoch > 0L && state.sessionId > 0L && state.configJson != null &&
                    state.callbacksRequireCleanup && state.nativeStopRequired,
            ) { "SessionOwned bridge ownership is invalid" }

            BridgeResourcePhase.Stopping -> check(
                state.epoch == 0L && state.configJson == null && state.callbacksRequireCleanup,
            ) { "Stopping bridge ownership is invalid" }

            BridgeResourcePhase.CallbacksOwned -> check(
                state.epoch == 0L && state.sessionId == 0L && state.configJson == null &&
                    state.callbacksRequireCleanup && !state.nativeStopRequired,
            ) { "CallbacksOwned bridge ownership is invalid" }
        }
    }
}

internal data class BridgeStopResult(
    val settled: Boolean,
    val error: Throwable? = null,
) {
    /**
     * A shutdown error is recoverable once the exact native session has
     * settled: the TUN duplicate is no longer owned and a replacement session
     * may safely start. Only an unsettled session must abort bridge reuse.
     */
    fun requireSettled() {
        if (!settled) {
            throw error ?: IllegalStateException("tcptun session did not stop cleanly")
        }
    }
}

/** The Android-owned descriptor is safe to close only after native ownership is gone. */
internal fun canCloseAndroidTun(snapshot: BridgeResourceSnapshot): Boolean =
    !snapshot.nativeStopRequired

private val BridgeStatusReasonPattern = Regex(""""reason"\s*:\s*"([A-Za-z0-9_]+)"""")

private fun bridgeReportsSettled(bridge: TcptunBridge): Boolean {
    return when (runRecoverableCatching { bridge.status() }.getOrNull()?.lowercase()) {
        "stopped" -> true
        "error" -> {
            // STOP_TIMEOUT is the one Error state that tcptun-go publishes
            // while the runtime can still be active. Other Error reasons are
            // terminal runtime/start failures and no longer own the TUN.
            val reason = runRecoverableCatching {
                BridgeStatusReasonPattern.find(bridge.statusJson())?.groupValues?.getOrNull(1)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            reason != null && !reason.equals("STOP_TIMEOUT", ignoreCase = true)
        }
        else -> false
    }
}

/** Cancel one native session and confirm release of the Go-owned TUN duplicate. */
internal fun stopAndAwaitBridgeSession(
    bridge: TcptunBridge,
    sessionId: Long,
    settleTimeoutMillis: Long,
): BridgeStopResult {
    val stopError = runRecoverableCatching { bridge.stop() }.exceptionOrNull()
        ?: return BridgeStopResult(settled = true)
    if (sessionId <= 0L) {
        return BridgeStopResult(settled = bridgeReportsSettled(bridge), error = stopError)
    }
    val waitError = runRecoverableCatching {
        bridge.waitStopped(sessionId, settleTimeoutMillis)
    }.exceptionOrNull()
    if (waitError == null) return BridgeStopResult(settled = true)
    val settled = bridgeReportsSettled(bridge)
    val combined = IllegalStateException(
        if (settled) {
            "tcptun session $sessionId stopped with an error"
        } else {
            "tcptun session $sessionId did not stop cleanly"
        },
        waitError,
    ).apply { addSuppressed(stopError) }
    return BridgeStopResult(settled = settled, error = combined)
}
