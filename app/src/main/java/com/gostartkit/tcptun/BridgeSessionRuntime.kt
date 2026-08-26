package com.tcptun.client

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun initiallyDisabledOutboundTags(plan: ProfileRunPlan): List<String> =
    plan.profiles
        .filterNot { it.id in plan.activeIds }
        .map(AppConfig::runtimeOutboundTag)

internal data class BridgeSessionRuntimeStartRequest(
    val configJson: String,
    val disabledOutboundTags: List<String>,
    val tunFd: Int,
    val mtu: Int,
    val settings: AppliedRuntimeSettings,
    val readyTimeoutMillis: Long,
)

internal data class BridgeSessionRuntimeCallbacks(
    val commandOwner: () -> Boolean,
    val beginBridgeEpoch: () -> Long,
    val endBridgeEpoch: (Long) -> Unit,
    val onLog: (String) -> Unit,
    val onStatusEvent: (Long, String) -> BridgeStatusEvent?,
    val protectSocket: (Int) -> Boolean,
    val configureFlowAnalysis: (Long, String, AppliedRuntimeSettings) -> Unit,
    val onInitialStatus: (Long, String) -> Unit,
    val onSessionStarted: (Long, Long) -> Unit,
    val onVerifiedStatus: (Long, String) -> Boolean,
    val onOptionalEventRegistrationFailure: (String, Throwable) -> Unit,
    val onNativeStillStopping: (Throwable) -> Unit,
    val onNativeStoppedWithError: (Throwable) -> Unit,
    val onCleanupFailure: (String, Throwable) -> Unit,
)

/** Service-owned policy callbacks exposed to the session runtime as a narrow port. */
internal class BridgeSessionServicePort(
    private val resources: BridgeResourceStateMachine,
    private val lifecycleLock: Any,
    private val runtimeSettingsState: RuntimeSettingsRuntimeState,
    private val stopping: () -> Boolean,
    private val destroyed: () -> Boolean,
    private val onAcceptedStatus: (Long, BridgeStatusEvent) -> Unit,
    private val protectSocket: (Int) -> Boolean,
    private val configureFlowAnalysis: (Long, String, AppliedRuntimeSettings) -> Unit,
) {
    fun callbacks(commandOwner: () -> Boolean) = BridgeSessionRuntimeCallbacks(
        commandOwner = { commandOwner() && !stopping() && !destroyed() },
        beginBridgeEpoch = {
            synchronized(lifecycleLock) {
                check(commandOwner() && !stopping() && !destroyed()) {
                    "tcptun start was superseded"
                }
                TcptunState.beginBridgeSession().also(resources::beginPreparation)
            }
        },
        endBridgeEpoch = { epoch ->
            synchronized(lifecycleLock) {
                runtimeSettingsState.clearPhysicalRuntimeApplied()
                if (epoch > 0L) TcptunState.endBridgeSession(epoch)
            }
        },
        onLog = { line -> if (!destroyed()) TcptunState.appendLog(line) },
        onStatusEvent = ::applyStatusEvent,
        protectSocket = protectSocket,
        configureFlowAnalysis = configureFlowAnalysis,
        onInitialStatus = { epoch, json -> TcptunState.applyBridgeStatusEvent(epoch, json) },
        onSessionStarted = { epoch, sessionId ->
            synchronized(lifecycleLock) {
                check(commandOwner() && epoch == resources.activeEpoch) {
                    "tcptun start was superseded"
                }
                TcptunState.appendLog("tcptun bridge session started: $sessionId")
            }
        },
        onVerifiedStatus = { epoch, status ->
            synchronized(lifecycleLock) {
                commandOwner() && epoch == resources.activeEpoch &&
                    TcptunState.updateDiagnosticsForBridgeEpoch(epoch) {
                        it.copy(bridgeStatus = status)
                    }
            }
        },
        onOptionalEventRegistrationFailure = { event, error ->
            TcptunState.appendLog(
                "register bridge event $event failed: ${failureDescription(error)}",
            )
        },
        onNativeStillStopping = { error ->
            // Stop and Abort both failed. Retain callbacks and the native stop
            // obligation; clearing Java proxies could strand an active runtime.
            TcptunState.appendLog(
                "tcptun engine is still stopping: ${failureDescription(error)}",
            )
        },
        onNativeStoppedWithError = { error ->
            TcptunState.appendLog(
                "tcptun engine stopped with error: ${failureDescription(error)}",
            )
        },
        onCleanupFailure = { label, error ->
            TcptunState.appendLog("$label failed: ${failureDescription(error)}")
        },
    )

    private fun applyStatusEvent(epoch: Long, eventJson: String): BridgeStatusEvent? =
        synchronized(lifecycleLock) {
            // Status publication and health consequences share the lifecycle
            // lock, so an old healthy event cannot race a replacement epoch.
            if (destroyed()) return@synchronized null
            val event = TcptunState.applyBridgeStatusEvent(epoch, eventJson)
                ?: return@synchronized null
            onAcceptedStatus(epoch, event)
            event
        }
}

/**
 * Owns only the native bridge session transaction and its readiness waiter.
 * The service remains the lifecycle authority and owns rollback, the runtime
 * lease, and the Android TUN descriptor passed here as a borrowed integer fd.
 */
internal class BridgeSessionRuntime(
    private val bridge: () -> TcptunBridge,
    private val bridgeInitialized: () -> Boolean,
    private val bridgeLock: Any,
    private val resources: BridgeResourceStateMachine,
) {
    private data class ReadyWaiter(
        val epoch: Long,
        val future: CompletableFuture<Unit> = CompletableFuture(),
    )

    private val readyWaiter = AtomicReference<ReadyWaiter?>()

    fun startSession(
        request: BridgeSessionRuntimeStartRequest,
        callbacks: BridgeSessionRuntimeCallbacks,
    ) {
        check(callbacks.commandOwner()) { "tcptun start was superseded" }
        val epoch = callbacks.beginBridgeEpoch()
        val waiter = ReadyWaiter(epoch)
        readyWaiter.getAndSet(waiter)?.future?.completeExceptionally(
            IllegalStateException("superseded by a newer tcptun start"),
        )
        try {
            val sessionId = startNativeSession(request, callbacks, epoch)
            callbacks.onSessionStarted(epoch, sessionId)
            waiter.future.get(request.readyTimeoutMillis, TimeUnit.MILLISECONDS)
            val status = synchronized(bridgeLock) {
                checkCurrent(callbacks, epoch, "tcptun session changed during startup")
                bridge().status()
            }
            check(callbacks.commandOwner() && resources.activeEpoch == epoch) {
                "tcptun session changed during startup"
            }
            check(callbacks.onVerifiedStatus(epoch, status)) {
                "tcptun session changed during startup"
            }
        } finally {
            readyWaiter.compareAndSet(waiter, null)
        }
    }

    fun stopSession(
        settleTimeoutMillis: Long,
        callbacks: BridgeSessionRuntimeCallbacks,
        cancellationReason: String = "tcptun stopped before core became ready",
    ) {
        cancelReadyWaiter(cancellationReason)
        val ownership = resources.beginStop()
        callbacks.endBridgeEpoch(ownership.epoch)
        if (!bridgeInitialized()) {
            if (ownership.callbacksRequireCleanup) {
                resources.nativeStopped()
                resources.callbacksReleased()
            }
            return
        }
        synchronized(bridgeLock) {
            BridgeSessionStopController(bridge(), resources).stop(
                settleTimeoutMillis = settleTimeoutMillis,
                callbacks = BridgeSessionStopCallbacks(
                    onNativeStillStopping = callbacks.onNativeStillStopping,
                    onNativeStoppedWithError = callbacks.onNativeStoppedWithError,
                    onCleanupFailure = callbacks.onCleanupFailure,
                ),
            )
        }
    }

    fun cancelReadyWaiter(reason: String) {
        readyWaiter.getAndSet(null)?.future?.completeExceptionally(IllegalStateException(reason))
    }

    internal fun sharesBridgeLock(lock: Any): Boolean = bridgeLock === lock

    private fun startNativeSession(
        request: BridgeSessionRuntimeStartRequest,
        callbacks: BridgeSessionRuntimeCallbacks,
        epoch: Long,
    ): Long = synchronized(bridgeLock) {
        BridgeSessionController(bridge(), resources).start(
            request = BridgeSessionStartRequest(
                configJson = request.configJson,
                disabledOutboundTags = request.disabledOutboundTags,
                tunFd = request.tunFd,
                mtu = request.mtu,
                powerSavingMode = request.settings.powerSavingMode,
                logLevel = request.settings.logLevel,
            ),
            callbacks = BridgeSessionCallbacks(
                onLog = callbacks.onLog,
                onStatus = { json -> handleStatusEvent(epoch, json, callbacks) },
                protectSocket = callbacks.protectSocket,
                configureFlowAnalysis = {
                    callbacks.configureFlowAnalysis(epoch, request.configJson, request.settings)
                },
                onInitialStatus = { callbacks.onInitialStatus(epoch, it) },
                onOptionalEventRegistrationFailure = callbacks.onOptionalEventRegistrationFailure,
            ),
            canStart = {
                callbacks.commandOwner() && resources.activeEpoch == epoch
            },
        )
    }

    private fun handleStatusEvent(
        epoch: Long,
        eventJson: String,
        callbacks: BridgeSessionRuntimeCallbacks,
    ) {
        val event = callbacks.onStatusEvent(epoch, eventJson) ?: return
        val waiter = readyWaiter.get()
        if (waiter?.epoch != epoch) return
        when (event.state.lowercase()) {
            "core_ready" -> waiter.future.complete(Unit)
            "error", "stopped" -> waiter.future.completeExceptionally(
                IllegalStateException(event.lastError.ifBlank { "tcptun ${event.state}" }),
            )
        }
    }

    private fun checkCurrent(
        callbacks: BridgeSessionRuntimeCallbacks,
        epoch: Long,
        message: String,
    ) {
        check(callbacks.commandOwner() && resources.activeEpoch == epoch) { message }
    }
}
