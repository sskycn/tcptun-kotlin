package com.tcptun.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Desired/native-installed two-level state. Callers serialize access around one or more slots. */
internal class NativeObservationCallbackSlot(
    private val allowed: () -> Boolean,
    private val installNative: (Any?) -> Unit,
) {
    var desired: Any? = null
        private set
    var installed: Any? = null
        private set

    val hasState: Boolean
        get() = desired != null || installed != null

    fun set(callback: Any?) {
        desired = callback
        reconcile()
    }

    fun reconcile() {
        val target = desired.takeIf { allowed() }
        if (installed === target) return
        installNative(target)
        installed = target
    }

    /** Native Close has released callbacks; no setter call is valid after this point. */
    fun releaseAfterNativeClose() {
        desired = null
        installed = null
    }
}

/**
 * Coordinates UI-only native bridge observation without coupling Activity lifecycle to the VPN
 * Service.
 *
 * The active bridge keeps desired diagnostic callbacks while this process-level slot asks it to
 * install or remove the actual gomobile callbacks as UI visibility changes. Flow/AppIdentity
 * callbacks can also request an asynchronous reconcile as a race fallback; the asynchronous hop
 * avoids re-entering the gomobile bridge before the current JNI callback returns.
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

    /** Used by existing visibility events; no timer or polling is introduced. */
    fun reconcileNow() {
        val registration = active.get() ?: return
        runRecoverableCatching(registration.reconcile)
            .onFailure { error ->
                TcptunState.appendLog(
                    "bridge observation reconcile failed: ${failureDescription(error)}",
                )
            }
    }

    /**
     * Called from Flow/AppIdentity callbacks only after a hidden-state race is observed. Coalescing
     * guarantees a burst of callbacks creates at most one short-lived helper thread.
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
            "tcptun-bridge-observation",
        ).apply { isDaemon = true }
        try {
            task.start()
        } catch (error: Throwable) {
            reconcileScheduled.set(false)
            if (error.isFatalProcessError()) throw error
            TcptunState.appendLog(
                "bridge observation reconcile start failed: ${failureDescription(error)}",
            )
        }
    }
}
