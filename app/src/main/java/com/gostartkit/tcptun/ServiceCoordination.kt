package com.tcptun.client

import java.util.concurrent.Future

internal data class RankedSelectionClaim<K>(
    val value: K?,
    val initial: Boolean,
    val previousValue: K? = null,
)

/** Identifies one concrete native runtime, not merely one Service instance. */
internal data class VpnRuntimeOwnership(
    val runtimeToken: VpnRuntimeCommandToken,
    val bridgeEpoch: Long,
) {
    init {
        require(bridgeEpoch > 0L) { "bridge epoch must be positive" }
    }
}

internal fun VpnRuntimeOwnership.isCurrent(
    runtimeTokenCurrent: Boolean,
    activeBridgeEpoch: Long,
    activeServiceInstance: Boolean,
): Boolean = runtimeTokenCurrent && activeServiceInstance && bridgeEpoch == activeBridgeEpoch

/**
 * Owns ranked candidate state and rejects stale selections computed by an
 * earlier callback. All mutations are serialized behind one private monitor.
 */
internal class RankedSelectionTracker<K> {
    private val scores = linkedMapOf<K, Int>()
    private var current: K? = null
    private var initialized = false

    @Synchronized
    fun update(value: K, score: Int): K? {
        scores[value] = score
        return selectedLocked()
    }

    @Synchronized
    fun remove(value: K): K? {
        scores.remove(value)
        return selectedLocked()
    }

    @Synchronized
    fun claim(selection: K?): RankedSelectionClaim<K>? {
        if (selection != selectedLocked() || current == selection) return null
        val claim = RankedSelectionClaim(
            value = selection,
            initial = !initialized,
            previousValue = current,
        )
        initialized = true
        current = selection
        return claim
    }

    @Synchronized
    fun clear() {
        scores.clear()
        current = null
        initialized = false
    }

    @Synchronized
    fun currentClaim(): RankedSelectionClaim<K>? {
        if (!initialized) return null
        return RankedSelectionClaim(value = current, initial = true, previousValue = current)
    }

    private fun selectedLocked(): K? = scores.maxByOrNull { it.value }?.key
}

internal data class BridgeRestartToken(
    val requestGeneration: Long,
    val lifecycleGeneration: Int,
)

internal data class BridgeRecoveryAttempt(
    val number: Int,
    val delayMillis: Long,
)

/**
 * Owns restart generations, cooldown admission, healthy-snapshot cancellation,
 * and failed-recovery backoff independently from Android task scheduling.
 */
internal class BridgeRecoveryCoordinator(
    private val minRestartIntervalMillis: Long,
    private val recoveryDelayMillis: (Int) -> Long,
) {
    private var restartGeneration = 0L
    private var healthySnapshotMayCancel = false
    private var lastRestartAtMillis = 0L
    private var recoveryAttempt = 0

    init {
        require(minRestartIntervalMillis >= 0L) {
            "minimum bridge restart interval must not be negative"
        }
    }

    val recoveryPending: Boolean
        @Synchronized get() = recoveryAttempt > 0

    @Synchronized
    fun requestRestart(
        lifecycleGeneration: Int,
        cancelIfHealthy: Boolean,
    ): BridgeRestartToken {
        recoveryAttempt = 0
        healthySnapshotMayCancel = cancelIfHealthy
        restartGeneration += 1L
        return BridgeRestartToken(restartGeneration, lifecycleGeneration)
    }

    @Synchronized
    fun cancelRestart() {
        restartGeneration += 1L
        healthySnapshotMayCancel = false
    }

    @Synchronized
    fun isCurrent(token: BridgeRestartToken, currentLifecycleGeneration: Int): Boolean =
        token.requestGeneration == restartGeneration &&
            token.lifecycleGeneration == currentLifecycleGeneration

    @Synchronized
    fun claimRestart(token: BridgeRestartToken, currentLifecycleGeneration: Int): Boolean {
        if (!isCurrent(token, currentLifecycleGeneration)) return false
        healthySnapshotMayCancel = false
        return true
    }

    @Synchronized
    fun cancelRestartAfterHealthySnapshot(): Boolean {
        if (!healthySnapshotMayCancel) return false
        restartGeneration += 1L
        healthySnapshotMayCancel = false
        return true
    }

    @Synchronized
    fun scheduleDelayMillis(
        token: BridgeRestartToken,
        currentLifecycleGeneration: Int,
        nowMillis: Long,
        settleDelayMillis: Long,
    ): Long? {
        if (!isCurrent(token, currentLifecycleGeneration)) return null
        return maxOf(
            remainingCooldownMillis(nowMillis),
            settleDelayMillis.coerceAtLeast(0L),
        )
    }

    /** Returns remaining cooldown, or records the admitted restart and returns zero. */
    @Synchronized
    fun beginRestart(nowMillis: Long): Long {
        val remaining = remainingCooldownMillis(nowMillis)
        if (remaining > 0L) return remaining
        lastRestartAtMillis = nowMillis
        return 0L
    }

    @Synchronized
    fun remainingCooldownMillis(nowMillis: Long): Long =
        (minRestartIntervalMillis - (nowMillis - lastRestartAtMillis)).coerceAtLeast(0L)

    @Synchronized
    fun resetRestartCooldown() {
        lastRestartAtMillis = 0L
    }

    @Synchronized
    fun nextRecoveryAttempt(): BridgeRecoveryAttempt {
        recoveryAttempt = if (recoveryAttempt == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            recoveryAttempt + 1
        }
        return BridgeRecoveryAttempt(
            number = recoveryAttempt,
            delayMillis = recoveryDelayMillis(recoveryAttempt),
        )
    }

    @Synchronized
    fun resetRecovery() {
        recoveryAttempt = 0
    }
}

/**
 * Owns the latest deferred task and cancels the task it supersedes.
 *
 * Keeping this ownership explicit prevents debounce work from outliving the
 * Android component that scheduled it.
 */
internal class LatestTaskSlot {
    private var current: Future<*>? = null

    @Synchronized
    fun replace(next: Future<*>) {
        if (current === next) return
        current?.cancel(false)
        current = next
    }

    @Synchronized
    fun cancel() {
        current?.cancel(false)
        current = null
    }
}

/** Closes the release-before-Future race at teardown retry admission. */
internal inline fun completeReleasedBeforeRetry(
    resourcesOwned: Boolean,
    completeReleasedOwner: () -> Unit,
): Boolean {
    if (resourcesOwned) return false
    completeReleasedOwner()
    return true
}

/** Selects a bounded rotating slice so expensive serial probes cannot starve later entries. */
internal class RoundRobinBatchSelector {
    private var nextIndex = 0

    @Synchronized
    fun <T> select(values: List<T>, maxCount: Int): List<T> {
        if (values.isEmpty() || maxCount <= 0) return emptyList()
        val count = minOf(values.size, maxCount)
        val start = nextIndex.mod(values.size)
        val selected = List(count) { offset -> values[(start + offset) % values.size] }
        nextIndex = (start + count) % values.size
        return selected
    }

    @Synchronized
    fun clear() {
        nextIndex = 0
    }
}
