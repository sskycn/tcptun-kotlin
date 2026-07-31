package com.tcptun.client

import java.util.concurrent.Future

internal data class RankedSelectionClaim<K>(
    val value: K?,
    val initial: Boolean,
)

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
        val claim = RankedSelectionClaim(selection, initial = !initialized)
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

    private fun selectedLocked(): K? = scores.maxByOrNull { it.value }?.key
}

/** Coalesces debounced runtime-setting requests without exposing its lock. */
internal class RuntimeSettingsApplyGate {
    private var generation = 0
    private var forceRestartPending = false

    @Synchronized
    fun request(forceRestart: Boolean): Int {
        generation += 1
        forceRestartPending = forceRestartPending || forceRestart
        return generation
    }

    @Synchronized
    fun claim(requestGeneration: Int): Boolean? {
        if (requestGeneration != generation) return null
        return forceRestartPending.also { forceRestartPending = false }
    }

    @Synchronized
    fun isLatest(requestGeneration: Int): Boolean = requestGeneration == generation
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
