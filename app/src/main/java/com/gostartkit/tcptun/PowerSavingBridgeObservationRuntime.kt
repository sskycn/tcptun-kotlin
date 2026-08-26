package com.tcptun.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates UI-only bridge observation without coupling Activity lifecycle to the VPN Service.
 *
 * The active bridge keeps the desired Flow Analysis callback, while this process-level slot asks
 * it to install or remove the actual gomobile callback as UI visibility changes. Hidden traffic can
 * also request an asynchronous reconcile from inside a JNI callback; the asynchronous hop avoids
 * re-entering the gomobile bridge before the current callback returns.
 */
internal object PowerSavingBridgeObservationRuntime {
    private data class Registration(
        val owner: Any,
        val reconcile: () -> Unit,
    )

    private val active = AtomicReference<Registration?>(null)
    private val reconcileScheduled = AtomicBoolean()

    fun install(owner: Any, reconcile: () -> Unit) {
        active.set(Registration(owner, reconcile))
        reconcileNow()
    }

    fun uninstall(owner: Any) {
        while (true) {
            val current = active.get() ?: return
            if (current.owner !== owner) return
            if (active.compareAndSet(current, null)) return
        }
    }

    /** Used by the existing app-visible event; no timer or polling is introduced. */
    fun reconcileNow() {
        val registration = active.get() ?: return
        runRecoverableCatching(registration.reconcile)
            .onFailure { error ->
                TcptunState.appendLog(
                    "flow observation reconcile failed: ${failureDescription(error)}",
                )
            }
    }

    /**
     * Called from Flow/AppIdentity callbacks only after power-saving background work is observed.
     * Coalescing guarantees a burst of callbacks creates at most one short-lived helper thread.
     */
    fun reconcileHiddenAsync() {
        if (
            PowerSavingObservationPolicy.shouldPublish(
                powerSaving = TcptunState.diagnostics.powerSavingMode,
                uiVisible = TcptunState.isUiVisible,
            )
        ) {
            return
        }
        if (!reconcileScheduled.compareAndSet(false, true)) return
        val task = Thread(
            {
                try {
                    reconcileNow()
                } finally {
                    reconcileScheduled.set(false)
                }
            },
            "tcptun-flow-observation",
        ).apply { isDaemon = true }
        try {
            task.start()
        } catch (error: Throwable) {
            reconcileScheduled.set(false)
            if (error.isFatalProcessError()) throw error
            TcptunState.appendLog(
                "flow observation reconcile start failed: ${failureDescription(error)}",
            )
        }
    }
}
