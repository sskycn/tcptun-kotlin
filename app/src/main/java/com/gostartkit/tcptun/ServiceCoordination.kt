package com.tcptun.client

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
