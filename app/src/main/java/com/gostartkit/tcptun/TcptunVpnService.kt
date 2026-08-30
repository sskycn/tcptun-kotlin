package com.tcptun.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TcptunVpnService : VpnService() {
    private val serviceInstanceId = nextServiceInstanceId.incrementAndGet()
    private val foregroundRuntime by lazy(LazyThreadSafetyMode.NONE) {
        VpnForegroundRuntime(AndroidVpnForegroundServicePort(this))
    }
    private val profileRepository: ProfileRepository by lazy(LazyThreadSafetyMode.NONE) {
        applicationContext.profileRepository()
    }
    private val bridgeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ReflectionTcptunBridge() }
    private val bridge: TcptunBridge get() = bridgeDelegate.value
    private val bridgeLock = Any()
    private val bridgeResources = BridgeResourceStateMachine()
    private val bridgeSessionRuntimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeSessionRuntime(
            bridge = { bridge },
            bridgeInitialized = bridgeDelegate::isInitialized,
            bridgeLock = bridgeLock,
            resources = bridgeResources,
        )
    }
    private val bridgeSessionRuntime: BridgeSessionRuntime
        get() = bridgeSessionRuntimeDelegate.value
    private val bridgeSessionServicePort by lazy(LazyThreadSafetyMode.NONE) {
        BridgeSessionServicePort(
            resources = bridgeResources,
            lifecycleLock = lifecycleCommandLock,
            runtimeSettingsState = runtimeSettingsState,
            stopping = { stopping },
            destroyed = destroyed::get,
            onAcceptedStatus = { epoch, event -> bridgeHealthRuntime.onStatusEvent(epoch, event) },
            protectSocket = { fd -> !destroyed.get() && !stopping && protect(fd) },
            configureFlowAnalysis = { epoch, configJson, settings ->
                configureFlowAnalysis(settings.flowAnalysisApp, epoch, configJson)
            },
        )
    }
    private val tunOwner = ExclusiveResourceOwner<android.os.ParcelFileDescriptor>()
    private val teardownLock = Any()
    /** Shared with service ownership so old/new instances cannot publish across each other. */
    private val lifecycleCommandLock = serviceOwnerLock
    private val runtimeControlUnavailable = AtomicBoolean()
    private val lifecycleExecutor = newLifecycleScheduledExecutor("TcptunLifecycle")
    private val runtimeCoordinator = VpnRuntimeCoordinator(lifecycleExecutor) {
        !destroyed.get() && !runtimeControlUnavailable.get()
    }
    private val latestStartId = AtomicInteger()
    private val connectionUpdateTracker = ConnectionUpdateTracker()
    private val bridgeRecoveryCoordinator = BridgeRecoveryCoordinator(
        minRestartIntervalMillis = BRIDGE_RESTART_MIN_INTERVAL_MS,
        recoveryDelayMillis = BridgeHealthPolicy::bridgeRecoveryDelayMs,
    )
    private val destroyed = AtomicBoolean()
    private val initialized = AtomicBoolean()
    private val tun: android.os.ParcelFileDescriptor?
        get() = tunOwner.resource
    private val runtimeSnapshot: VpnRuntimeSnapshot
        get() = runtimeCoordinator.snapshot
    private val runningPlan: ProfileRunPlan?
        get() = runtimeSnapshot.runningPlan
    private val stopping: Boolean
        get() = runtimeSnapshot.stopping
    private val bridgeRestarting: Boolean
        get() = runtimeSnapshot.bridgeRestarting
    private val explicitStopRequested: Boolean
        get() = runtimeSnapshot.explicitStopRequested
    private val connectivityDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("ConnectivityManager is unavailable")
    }
    private val connectivity: ConnectivityManager get() = connectivityDelegate.value
    private val appIdentityProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppIdentityProvider(applicationContext, connectivity)
    }
    private val appIdentityProvider: AndroidAppIdentityProvider get() = appIdentityProviderDelegate.value
    private val underlyingNetworkRuntime = UnderlyingNetworkRuntime<Network>(
        selectionSourceFactory = { onSelectionChanged ->
            UnderlyingNetworkCoordinator(
                connectivity = { connectivity },
                canHandleCallback = {
                    synchronized(lifecycleCommandLock) {
                        !destroyed.get() && !stopping && isActiveServiceOwner()
                    }
                },
                onSelectionChanged = onSelectionChanged,
                log = TcptunState::appendLog,
            )
        },
        currentOwnership = ::currentRuntimeOwnership,
        isOwnershipCurrent = ::ownsRuntime,
        dispatchLifecycle = { ownership, task ->
            executeLifecycleTask(VpnRuntimeCommand.UpdateUnderlyingNetwork(ownership), task = task)
        },
        applyUnderlyingNetworks = { network ->
            setUnderlyingNetworks(network?.let { arrayOf(it) })
        },
        updateDiagnostics = { network ->
            TcptunState.updateDiagnostics {
                it.copy(underlyingNetwork = network?.toString() ?: "None")
            }
        },
        vpnRunning = { TcptunState.status == VpnStatus.Running },
        onRestartRequested = { reason, settleDelayMs, ownership ->
            requestBridgeRestart(reason, settleDelayMs, ownership = ownership)
        },
        onMemberProbeRequested = ::requestMemberHealthProbe,
        onMemberProbeCancelled = { bridgeHealthRuntime.cancelMemberProbe() },
        log = TcptunState::appendLog,
    )
    private val runtimeSettingsState = RuntimeSettingsRuntimeState()
    private val healthBridgePort = LockedHealthBridgePort(
        lock = bridgeLock,
        bridge = { bridge },
        isOwnershipCurrent = ::ownsRuntime,
        hasActiveConfig = { bridgeResources.activeConfigJson != null },
        log = TcptunState::appendProxyDiagnostic,
    )
    private val bridgeHealthRuntime: BridgeHealthRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeHealthRuntime(
            lifecycleExecutor = lifecycleExecutor,
            bridgePort = healthBridgePort,
            currentOwnership = ::currentRuntimeOwnership,
            isOwnershipCurrent = ::ownsRuntime,
            currentPlan = { runningPlan },
            currentSettings = { runtimeSettingsState.effectiveSettings },
            memberProbesAllowed = { underlyingNetworkRuntime.hasEligibleNetwork },
            canHandleStatusEvent = { !bridgeRestarting },
            restoreConnectionsReady = ::restoreConnectionsReadyAfterHealthySnapshot,
            dispatchDiagnostics = { task ->
                executeLifecycleTask(
                    command = VpnRuntimeCommand.RefreshDiagnostics,
                    onFailure = { error ->
                        if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
                    },
                    task = task,
                )
            },
            onRestartRequired = { ownership, reason, cancelIfHealthy ->
                requestBridgeRestart(reason, cancelIfHealthy = cancelIfHealthy, ownership = ownership)
            },
            log = { message ->
                if (!destroyed.get()) {
                    if (message.startsWith("local_proxy ") || message.startsWith("upstream_probe ")) {
                        TcptunState.appendProxyDiagnostic(message)
                    } else TcptunState.appendLog(message)
                }
            },
        )
    }
    private val tcpingBridgePort = LockedTcpingBridgePort(
        bridgeLock, { bridge }, ::ownsRuntime, TcptunState::appendProxyDiagnostic,
    )
    private val outboundTcpingRuntime = OutboundTcpingRuntime(
        bridgePort = tcpingBridgePort,
        currentOwnership = ::currentRuntimeOwnership,
        isOwnershipCurrent = ::ownsRuntime,
        currentPlan = { runningPlan },
        connectionsReady = {
            TcptunState.status == VpnStatus.Running && TcptunState.state.value.connectionsReady
        },
        publishIfOwned = { ownership, publication ->
            synchronized(lifecycleCommandLock) {
                if (!ownsRuntime(ownership)) false else {
                    publication()
                    true
                }
            }
        },
        publishSessionChanged = { ownership, requestId ->
            synchronized(lifecycleCommandLock) {
                if (
                    ownership.runtimeToken.serviceInstanceId == serviceInstanceId &&
                    isActiveServiceOwner() &&
                    !ownsRuntime(ownership) &&
                    TcptunState.isCurrentTcping(requestId)
                ) {
                    TcptunState.failTcping(requestId, "VPN session changed")
                }
            }
        },
        state = TcptunStateOutboundTcpingPort,
        onMemberProbeRequested = bridgeHealthRuntime::scheduleMemberProbe,
    )
    private val bridgeRestartTask = LatestTaskSlot()
    private val bridgeRestartContinuationTask = LatestTaskSlot()
    private val bridgeRestartScheduleLock = Any()
    private val bridgeRecoveryTask = LatestTaskSlot()
    private val platformCleanupAdapter = VpnPlatformCleanupAdapter(
        VpnPlatformCleanupActions(
            cancelBridgeRestart = ::cancelPendingBridgeRestart,
            publishStopping = { TcptunState.setStatus(VpnStatus.Stopping) },
            stopHealth = bridgeHealthRuntime::stop,
            unregisterNetwork = { underlyingNetworkRuntime.unregister(updateDiagnostics = false) },
            resetUnderlyingDiagnostics = underlyingNetworkRuntime::clearDiagnostics,
            clearDesiredConfig = { clearDesiredRunningConfig(this) },
            publishBridgeStopping = { TcptunState.appendLog("stopping tcptun bridge") },
            stopBridgeSession = ::stopBridge,
            closeTunIfSafe = ::closeTunAfterBridgeStopAttempt,
            clearAppIdentity = {
                if (appIdentityProviderDelegate.isInitialized()) appIdentityProvider.clear()
            },
            resetHealth = bridgeHealthRuntime::reset,
            resourcesOwned = { bridgeResources.hasOwnedResources },
            resetDiagnostics = { TcptunState.updateDiagnostics(::releasedVpnDiagnostics) },
            publishStopped = { TcptunState.setStatus(VpnStatus.Stopped) },
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            requestServiceStop = {
                synchronized(lifecycleCommandLock) {
                    latestStartId.get().takeIf { it > 0 }?.let(::stopSelf) ?: stopSelf()
                }
            },
            honorDeferredStopIfReleased = ::honorDeferredStopIfReleased,
            publishIncompleteCleanup = { description ->
                TcptunState.error("VPN cleanup is incomplete: $description")
            },
            retainCleanupForeground = {
                if (!destroyed.get()) {
                    startVpnForeground(VpnForegroundState.Error(retryingCleanup = true))
                }
            },
        ),
    )
    private val platformTeardownRuntime = newVpnPlatformTeardownRuntime(
        executor = lifecycleExecutor,
        performCleanup = { request -> performVpnCleanupAttempt(request).result },
        completeOwner = ::completePlatformCleanupOwner,
        resourcesOwned = { bridgeResources.hasOwnedResources },
        dispatchLifecycleRetry = { task ->
            executeLifecycleTask(VpnRuntimeCommand.Internal("tcptun teardown retry"), task = task)
        },
        isDestroyed = destroyed::get,
        log = TcptunState::appendLog,
    )
    private val deferredServiceStopGate = DeferredServiceStopGate()
    private val runtimeDebugSnapshotProvider = if (BuildConfig.DEBUG) {
        { stableRuntimeOwnershipDebugSnapshot(serviceInstanceId, ::captureRuntimeOwnershipDebugState) }
    } else null

    override fun onCreate() {
        super.onCreate()
        synchronized(serviceOwnerLock) {
            activeServiceInstanceId.set(serviceInstanceId)
        }
        runtimeDebugSnapshotProvider?.let { provider ->
            RuntimeOwnershipDebugRegistry.install(serviceInstanceId, provider)
        }
        try {
            TcptunState.state.value.tcping.takeIf { it.running }?.let { staleRequest ->
                TcptunState.failTcping(staleRequest.requestId, "VPN session changed")
            }
            VpnHealthCheckRequests.install(
                bridgeHealthRuntime.monitorWakeCallback,
                bridgeHealthRuntime.memberHealthProbeCallback,
            )
            createNotificationChannel()
            initialized.set(true)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            VpnHealthCheckRequests.uninstall(
                bridgeHealthRuntime.monitorWakeCallback,
                bridgeHealthRuntime.memberHealthProbeCallback,
            )
            synchronized(lifecycleCommandLock) {
                if (isActiveServiceOwner()) {
                    TcptunState.error("VPN service initialization failed: ${failureDescription(error)}")
                    stopSelfWhenBridgeReleased(reason = "service initialization failure")
                }
            }
        }
    }

    private fun isActiveServiceOwner(): Boolean = activeServiceInstanceId.get() == serviceInstanceId

    private inline fun runIfActiveServiceOwner(action: () -> Unit): Boolean =
        synchronized(lifecycleCommandLock) {
            if (destroyed.get() || !isActiveServiceOwner()) {
                false
            } else {
                action()
                true
            }
        }

    private inline fun runIfLifecycleCommandOwner(
        generation: Int,
        action: () -> Unit,
    ): Boolean = synchronized(lifecycleCommandLock) {
        if (
            destroyed.get() ||
            !isActiveServiceOwner() ||
            generation != runtimeSnapshot.lifecycleGeneration
        ) {
            false
        } else {
            action()
            true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val command = VpnServiceCommand.fromAction(action)
        debugLifecycleMarker("onStartCommand command=${command.policyKind}")
        val admittedToken = try {
            synchronized(lifecycleCommandLock) {
                latestStartId.updateAndGet { current -> maxOf(current, startId) }
                val replacesRuntime = command.policyKind == ServiceCommandKind.StartOrRestore ||
                    (command.policyKind == ServiceCommandKind.UpdateConnections && !explicitStopRequested)
                if (replacesRuntime) {
                    // Linearize replacement work before foreground publication or
                    // any other blocking operation so stale cleanup cannot stop it.
                    if (command.policyKind == ServiceCommandKind.StartOrRestore) {
                        runtimeCoordinator.claimStart(serviceInstanceId, persistent = true)
                    } else {
                        runtimeCoordinator.claimAuxiliaryCommand(serviceInstanceId, persistent = true).also { token ->
                            runtimeSettingsState.rebindAppliedOwnership(token, currentRuntimeOwnership())
                        }
                    }
                } else null
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            if (command.requiresForegroundStart) {
                runRecoverableCatching { startVpnForeground(VpnForegroundState.Error(retryingCleanup = false)) }
            }
            handleServiceCommandFailure(command, action, error)
            return START_NOT_STICKY
        }
        val foregroundStart = command.requiresForegroundStart
        if (foregroundStart) {
            // startForegroundService() creates a per-start deadline. Android can
            // deliver a rapid restart to an instance whose teardown has already
            // revoked command ownership; acknowledge the foreground start before
            // consulting lifecycle ownership so that rejecting the stale command
            // cannot crash the process with ForegroundServiceDidNotStartInTime.
            try {
                startVpnForeground(VpnForegroundState.Starting)
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                runRecoverableCatching {
                    TcptunState.error("VPN foreground start failed: ${failureDescription(error)}")
                }
                stopSelfWhenBridgeReleased(startId, "foreground start failure")
                return START_NOT_STICKY
            }
        }
        if (destroyed.get() || !initialized.get()) {
            stopSelfWhenBridgeReleased(startId, "inactive service command")
            return START_NOT_STICKY
        }
        if (shouldRejectColdAuxiliaryCommand(
                commandKind = command.policyKind,
                hasRuntimeResources = tun != null ||
                    bridgeResources.hasOwnedResources ||
                    runningPlan != null,
                lifecycleWorkPending = runtimeCoordinator.inFlight > 0,
                bridgeRecoveryPending = bridgeRecoveryCoordinator.recoveryPending,
                teardownRetryPending = platformTeardownRuntime.pending,
                terminalStopPending = explicitStopRequested,
            )
        ) {
            rejectColdAuxiliaryCommand(intent, startId)
            return START_NOT_STICKY
        }
        try {
            when (command) {
                VpnServiceCommand.Start -> {
                    if (!publishForegroundIfOwner(
                            state = VpnForegroundState.Starting,
                            publishStartingStatus = true,
                            foregroundAlreadyPublished = foregroundStart,
                        )
                    ) {
                        stopSelfWhenBridgeReleased(startId, "rejected VPN start")
                        return START_NOT_STICKY
                    }
                    startFromIntent(
                        requireNotNull(intent) { "start command is missing its intent" },
                        requireNotNull(admittedToken) { "start command was not admitted" },
                    )
                }
                VpnServiceCommand.Stop -> requestStopVpn()
                VpnServiceCommand.UpdateConnections -> {
                    if (!publishForegroundIfOwner(VpnForegroundState.Running())) {
                        stopSelfWhenBridgeReleased(startId, "rejected connection update")
                        return START_NOT_STICKY
                    }
                    requestOutboundUpdate(
                        requireNotNull(intent) { "update command is missing its intent" },
                        requireNotNull(admittedToken) { "update command was not admitted" },
                    )
                }
                VpnServiceCommand.Tcping -> outboundTcpingRuntime.request(
                    requireNotNull(intent) { "TCPing command is missing its intent" }.toTcpingRequest(),
                )
                VpnServiceCommand.ApplyRuntimeSettings -> requestRuntimeSettingsApply(
                    reason = if (intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false) == true) {
                        "route rules changed"
                    } else {
                        "runtime settings changed"
                    },
                    forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false) == true,
                )
                VpnServiceCommand.UpdateFlowAnalysis ->
                    requestRuntimeSettingsApply("flow analysis changed", forceRestart = false)
                VpnServiceCommand.RefreshClientIps -> bridgeHealthRuntime.requestClientIpsRefresh()
                VpnServiceCommand.Restore,
                VpnServiceCommand.Unknown,
                -> {
                    if (!publishForegroundIfOwner(
                            state = VpnForegroundState.Starting,
                            publishStartingStatus = true,
                            foregroundAlreadyPublished = foregroundStart,
                        )
                    ) {
                        stopSelfWhenBridgeReleased(startId, "rejected VPN restore")
                        return START_NOT_STICKY
                    }
                    requestRestoreLastRunningConfig(
                        requireNotNull(admittedToken) { "restore command was not admitted" },
                    )
                }
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            handleServiceCommandFailure(command, action, error)
            return START_NOT_STICKY
        }
        return if (command == VpnServiceCommand.Stop) START_NOT_STICKY else START_STICKY
    }

    private fun handleServiceCommandFailure(
        command: VpnServiceCommand,
        action: String?,
        error: Throwable,
    ) {
        val message = "VPN service command ${action ?: "restore"} failed: ${failureDescription(error)}"
        if (command == VpnServiceCommand.Stop && error.isRuntimeActorControlFailure()) {
            forcePhysicalStopAfterActorFailure(message)
            return
        }
        if (!command.requiresForegroundStart) {
            runIfActiveServiceOwner {
                TcptunState.appendLog(message)
                if (tun == null) stopSelfWhenBridgeReleased(reason = "failed auxiliary command")
            }
            return
        }
        val cleanup = try {
            synchronized(lifecycleCommandLock) {
                if (destroyed.get() || !isActiveServiceOwner()) return
                TcptunState.error(message)
                val token = runtimeCoordinator.claimStop(serviceInstanceId, "failed command cleanup")
                runtimeSettingsState.clearForStop()
                token to runtimeCoordinator.dispatchStop(
                    token = token,
                    reason = "failed command cleanup",
                    options = VpnRuntimeStopOptions(setStopped = false),
                    onFailure = { cleanupError ->
                        TcptunState.appendLog(
                            "failed command cleanup failed: ${failureDescription(cleanupError)}",
                        )
                    },
                    stopRuntime = { options, commandToken, commandOwner ->
                        stopVpn(
                            setStopped = options.setStopped,
                            clearSavedConfig = options.clearSavedConfig,
                            stopSelfService = options.stopSelfService,
                            globalStateOwner = {
                                commandOwner() && runtimeCoordinator.isCurrent(commandToken) &&
                                    !destroyed.get() && isActiveServiceOwner()
                            },
                            globalStateCommitLock = lifecycleCommandLock,
                            cleanupOwner = VpnPlatformCleanupOwner.Stop(commandToken),
                        )
                    },
                )
            }
        } catch (cleanupError: Throwable) {
            if (cleanupError.isFatalProcessError()) throw cleanupError
            if (cleanupError.isRuntimeActorControlFailure()) {
                forcePhysicalStopAfterActorFailure(message)
                return
            }
            throw cleanupError
        }
        if (!cleanup.second) {
            runIfLifecycleCommandOwner(cleanup.first.lifecycleGeneration) {
                stopSelfWhenBridgeReleased(reason = "failed command cleanup rejection")
            }
        }
    }

    private fun Throwable.isRuntimeActorControlFailure(): Boolean =
        generateSequence(this) { it.cause }.any {
            it is VpnRuntimeActorAdmissionException ||
                (it is IllegalStateException && it.message == "runtime actor did not respond")
        }

    /** Terminal fail-closed path used only when Stop cannot enter the logical control lane. */
    private fun forcePhysicalStopAfterActorFailure(message: String) {
        runtimeControlUnavailable.set(true)
        runtimeCoordinator.closeExternalIngress()
        synchronized(lifecycleCommandLock) {
            runtimeSettingsState.clearForStop()
            cleanupStep("persist requested stopped state") { clearDesiredRunningConfig(this) }
            if (isActiveServiceOwner()) {
                TcptunState.error("$message; forcing physical cleanup")
                TcptunState.setConnectionsReady(false)
            }
        }
        cancelPendingBridgeRestart()
        bridgeSessionRuntime.cancelReadyWaiter("runtime actor unavailable during stop")
        bridgeRecoveryTask.cancel()
        bridgeRecoveryCoordinator.resetRecovery()
        val cleanup = startCrashGuardedThread(
            threadName = "TcptunActorFailureStop",
            onFailure = { failure -> cleanupStep("Actor failure physical cleanup") { throw failure } },
        ) {
            stopVpn(
                globalStateOwner = {
                    !destroyed.get() && isActiveServiceOwner() && runtimeControlUnavailable.get()
                },
                globalStateCommitLock = lifecycleCommandLock,
            )
            stopSelfWhenBridgeReleased(reason = "runtime actor unavailable")
        }
        if (cleanup == null) {
            cleanupStep("start Actor failure physical cleanup") {
                throw IllegalStateException("physical cleanup thread could not be started")
            }
        }
    }

    private fun rejectColdAuxiliaryCommand(intent: Intent?, startId: Int) {
        val action = intent?.action.orEmpty()
        if (action == ACTION_TCPING_OUTBOUNDS) {
            val requestId = intent?.getLongExtra(EXTRA_TCPING_REQUEST_ID, 0L) ?: 0L
            if (requestId > 0L) {
                TcptunState.failTcping(requestId, "VPN is not running")
            }
        }
        TcptunState.appendLog(
            "VPN auxiliary command ${action.ifBlank { "unknown" }} rejected: VPN is not running",
        )
        stopSelfWhenBridgeReleased(startId, "cold auxiliary command")
    }

    private fun stopSelfWhenBridgeReleased(startId: Int? = null, reason: String) {
        synchronized(lifecycleCommandLock) {
            val effectiveStartId = startId ?: latestStartId.get().takeIf { it > 0 }
            if (bridgeResources.hasOwnedResources) {
                deferredServiceStopGate.defer(
                    lifecycleGeneration = runtimeSnapshot.lifecycleGeneration,
                    persistentCommandGeneration = runtimeSnapshot.persistentCommandGeneration,
                    startId = effectiveStartId,
                )
                TcptunState.appendLog(
                    "VPN service stop deferred ($reason): native bridge resources are still owned",
                )
                if (!destroyed.get()) {
                    cleanupStep("retain VPN cleanup foreground") {
                        startVpnForeground(VpnForegroundState.Error(retryingCleanup = false))
                    }
                }
                return
            }
            effectiveStartId?.let(::stopSelf) ?: stopSelf()
        }
    }

    private fun publishForegroundIfOwner(
        state: VpnForegroundState,
        publishStartingStatus: Boolean = false,
        foregroundAlreadyPublished: Boolean = false,
    ): Boolean = synchronized(lifecycleCommandLock) {
        if (destroyed.get() || !initialized.get() || !isActiveServiceOwner()) {
            false
        } else {
            if (publishStartingStatus) TcptunState.setStatus(VpnStatus.Starting)
            if (!foregroundAlreadyPublished) startVpnForeground(state)
            true
        }
    }

    private fun executeLifecycleTask(
        command: VpnRuntimeCommand,
        onFailure: (Throwable) -> Unit = { error ->
            if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
        },
        task: () -> Unit,
    ): Boolean {
        return runtimeCoordinator.dispatch(command, onFailure, task)
    }

    private fun startFromIntent(intent: Intent, token: VpnRuntimeCommandToken) {
        val request = VpnRuntimeStartRequest(
            command = VpnServiceIntents.parseStartCommand(this, intent),
            expectedProfileMutationRevision = profileRepository.currentMutationRevision(),
        )
        dispatchStartRequest(token, request)
    }

    private fun dispatchStartRequest(
        token: VpnRuntimeCommandToken,
        request: VpnRuntimeStartRequest,
        failureHandler: ((Throwable) -> Unit)? = null,
        resetRecoveryState: Boolean = true,
    ): Boolean {
        if (resetRecoveryState) {
            bridgeRecoveryTask.cancel()
            bridgeRecoveryCoordinator.resetRecovery()
        }
        return runtimeCoordinator.dispatchStart(
            token = token,
            request = request,
            onFailure = { error ->
                if (failureHandler != null) {
                    failureHandler(error)
                    return@dispatchStart
                }
                val handled = runIfLifecycleCommandOwner(token.lifecycleGeneration) {
                    TcptunState.error(failureDescription(error))
                    stopSelfWhenBridgeReleased(reason = "VPN start task failure")
                }
                if (!handled && !destroyed.get()) {
                    TcptunState.appendLog("stale VPN start failed: ${failureDescription(error)}")
                }
            },
            hasRuntimeResources = {
                tun != null || bridgeResources.hasOwnedResources
            },
            stopExisting = { commandToken, commandOwner ->
                TcptunState.appendLog("updating active VPN connections")
                val stopResult = stopVpn(
                    setStopped = false,
                    clearSavedConfig = false,
                    stopSelfService = false,
                    propagateBridgeStopFailure = true,
                    globalStateOwner = {
                        commandOwner() && runtimeCoordinator.isCurrent(commandToken) &&
                            isActiveServiceOwner()
                    },
                    globalStateCommitLock = lifecycleCommandLock,
                )
                check(stopResult is VpnPlatformStopResult.Released) {
                    "VPN reload aborted because platform resources are retained for retry"
                }
            },
            startRuntime = ::startRuntimeNow,
            rollbackStart = ::rollbackStart,
        )
    }

    private fun startRuntimeNow(
        request: VpnRuntimeStartRequest,
        token: VpnRuntimeCommandToken,
        coordinatorOwner: () -> Boolean,
        commitRunning: (ProfileRunPlan) -> Boolean,
    ) {
        val commandOwner = {
            coordinatorOwner() &&
                !destroyed.get() &&
                !explicitStopRequested &&
                isActiveServiceOwner()
        }
        if (!commandOwner()) return
        cancelPendingBridgeRestart()
        bridgeRecoveryCoordinator.resetRestartCooldown()
        run {
                if (!commandOwner()) return
                val startCommand = request.command
                val json = startCommand.configJson
                val plan = startCommand.plan
                val runtimeSettings = startCommand.runtimeSettings
                val desiredSequenceAtPreparation = runtimeSettingsState.latestSequence
                val runtimeAppliedSettings = AppliedRuntimeSettings.from(runtimeSettings)
                val desiredPlanJson = startCommand.desiredPlanJson
                val startingPublished = synchronized(lifecycleCommandLock) {
                    if (!commandOwner()) {
                        false
                    } else {
                        TcptunState.setStatus(VpnStatus.Starting)
                        startVpnForeground(VpnForegroundState.Starting)
                        TcptunState.updateDiagnostics {
                            it.copy(
                                bridgeStatus = "Starting",
                                localProxyReachable = false,
                                mtu = runtimeSettings.mtu,
                                powerSavingMode = runtimeSettings.powerSavingMode,
                                localProxyAddress = RuntimeSettingsRepository.localSocksConnectAddress(runtimeSettings),
                                localProxyPort = runtimeSettings.socksPort,
                                socketProtectEnabled = true,
                            )
                        }
                        true
                    }
                }
                if (!startingPublished) return
                claimBridgeRuntimeLease(commandOwner)
                check(commandOwner()) { "tcptun start was superseded" }
                val vpnTun = buildTun(runtimeSettings.mtu)
                ownTun(vpnTun)
                if (!commandOwner()) {
                    releaseSupersededRuntime()
                    return
                }
                startBridge(
                    json,
                    plan,
                    vpnTun,
                    runtimeSettings.mtu,
                    runtimeAppliedSettings,
                    commandOwner,
                )
                if (!commandOwner()) {
                    releaseSupersededRuntime()
                    return
                }
                val appliedOwnership = publishAppliedRuntimeState(token, runtimeAppliedSettings)
                    ?: run {
                        releaseSupersededRuntime()
                        return
                    }
                underlyingNetworkRuntime.register()
                underlyingNetworkRuntime.republishCurrent("VPN runtime started")
                TcptunState.resetProfileHealthForBridgeEpoch(bridgeResources.activeEpoch, plan.activeProfiles)
                val authoritativeSnapshot = reconcileProfilesAfterBridgeStart(
                    plan = plan,
                    expectedProfileMutationRevision = request.expectedProfileMutationRevision,
                    commandOwner = commandOwner,
                ) ?: return
                val committed = profileRepository.runIfRevisionCurrent(
                    expectedMutationRevision = authoritativeSnapshot.mutationRevision,
                    commitLock = lifecycleCommandLock,
                    canCommit = commandOwner,
                ) {
                    check(commitRunning(plan)) { "VPN start was superseded while committing" }
                    deferredServiceStopGate.clear()
                    publishDesiredRunningPlan(this, desiredPlanJson)
                    TcptunState.setStatus(VpnStatus.Running)
                    TcptunState.setConnectionsReady(true)
                    request.restartReason?.let { restartReason ->
                        TcptunState.updateDiagnostics { it.copy(lastRestartReason = restartReason) }
                    }
                    bridgeRecoveryCoordinator.resetRecovery()
                    bridgeRecoveryTask.cancel()
                    // Publish Running and install its monitor as one ownership
                    // commit. A connection update may claim the next lifecycle
                    // generation immediately after this block, but it must not
                    // be able to observe a Running session without a monitor.
                    VpnHealthCheckRequests.clearRuntimeForces()
                    bridgeHealthRuntime.start()
                    updateNotification(VpnForegroundState.Running(plan.activeProfiles.size))
                    // Wait for routing/tunnels to settle before the first
                    // member probe; immediate probes after multi-start often
                    // report "no route to host" and falsely degrade every pool member.
                    bridgeHealthRuntime.scheduleMemberProbe(
                        reason = "vpn started",
                        requestedDelayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
                    )
                }
                if (!committed) {
                    if (commandOwner()) {
                        throw IllegalStateException("profile state changed while VPN startup was committing")
                    }
                    releaseSupersededRuntime()
                    return
                }
                reconcileDesiredSettingsAfterRunning(
                    ownership = appliedOwnership,
                    freshRuntimeDesiredSequence = desiredSequenceAtPreparation,
                )
            }
    }

    private fun rollbackStart(
        request: VpnRuntimeStartRequest,
        token: VpnRuntimeCommandToken,
        error: Throwable,
        superseded: Boolean,
    ): VpnPlatformStopResult = rollbackStartCleanup(
        request = request,
        error = error,
        superseded = superseded,
        commandOwner = {
            runtimeCoordinator.isCurrent(token) &&
                !destroyed.get() &&
                isActiveServiceOwner()
        },
        cleanupOwner = VpnPlatformCleanupOwner.StartRollback(token),
    )

    private fun rollbackRecoveryStart(
        startRequest: VpnRuntimeStartRequest,
        recoveryRequest: VpnRuntimeRecoveryRequest,
        token: VpnRuntimeRecoveryToken,
        error: Throwable,
        superseded: Boolean,
    ): VpnPlatformStopResult =
        rollbackStartCleanup(
            request = startRequest,
            error = error,
            superseded = superseded,
            commandOwner = {
                runtimeCoordinator.isCurrent(token) &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
            },
            cleanupOwner = VpnPlatformCleanupOwner.RecoveryRollback(
                token = token,
                request = recoveryRequest,
                failure = error,
            ),
        )

    private fun rollbackBridgeRecovery(
        request: VpnRuntimeRecoveryRequest,
        token: VpnRuntimeRecoveryToken,
        error: Throwable,
        superseded: Boolean,
    ): VpnPlatformStopResult {
        if (superseded || !runtimeCoordinator.isCurrent(token)) {
            TcptunState.appendLog("VPN recovery cancelled")
            return releaseSupersededRuntime()
        }
        return releaseSupersededRuntime(
            cleanupOwner = VpnPlatformCleanupOwner.RecoveryRollback(token, request, error),
        )
    }

    private fun rollbackStartCleanup(
        request: VpnRuntimeStartRequest,
        error: Throwable,
        superseded: Boolean,
        commandOwner: () -> Boolean,
        cleanupOwner: VpnPlatformCleanupOwner?,
    ): VpnPlatformStopResult {
        if (superseded || !commandOwner()) {
            TcptunState.appendLog("VPN start cancelled")
            return releaseSupersededRuntime()
        }
        val failure = failureDescription(error)
        if (!request.preserveDesiredStateOnFailure) {
            profileRepository.clearActiveIfCurrent(
                context = this,
                expectedMutationRevision = request.expectedProfileMutationRevision,
                commitLock = lifecycleCommandLock,
                canCommit = commandOwner,
            )
                .onFailure { clearError ->
                    TcptunState.appendLog("clear active profiles failed: ${failureDescription(clearError)}")
                }
                .getOrNull()
                ?.takeIf { it }
                ?.let { TcptunState.notifyProfileStateChanged() }
            synchronized(lifecycleCommandLock) {
                if (commandOwner()) {
                    cleanupStep("clear failed VPN desired state") { clearDesiredRunningConfig(this) }
                }
            }
        }
        val cleanupResult = releaseSupersededRuntime(
            cleanupOwner = cleanupOwner,
        )
        if (request.preserveDesiredStateOnFailure) return cleanupResult
        synchronized(lifecycleCommandLock) {
            if (commandOwner()) {
                TcptunState.error(failure)
                if (!bridgeResources.hasOwnedResources) {
                    cleanupStep("remove failed VPN foreground notification") {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
                cleanupStep("stop failed VPN service") {
                    stopSelfWhenBridgeReleased(reason = "VPN startup failure")
                }
            }
        }
        return cleanupResult
    }

    /** Aligns persisted profiles with the started plan or hands off to a newer authoritative plan. */
    private fun reconcileProfilesAfterBridgeStart(
        plan: ProfileRunPlan,
        expectedProfileMutationRevision: Long,
        commandOwner: () -> Boolean,
    ): ProfileStoreSnapshot? {
        val profileStateAligned = profileRepository.alignActiveIdsWithPlanIfCurrent(
            context = this,
            expectedMutationRevision = expectedProfileMutationRevision,
            plan = plan,
            commitLock = lifecycleCommandLock,
            canCommit = commandOwner,
        ).getOrElse { profileError ->
            TcptunState.appendLog(
                "running profile state sync failed: ${failureDescription(profileError)}",
            )
            false
        }
        if (profileStateAligned) TcptunState.notifyProfileStateChanged()
        if (!commandOwner()) {
            releaseSupersededRuntime()
            return null
        }

        val authoritativeSnapshot = profileRepository.snapshot(this)
        val authoritativeState = authoritativeSnapshot.requireAuthoritativeState()
        val authoritativePlan = if (authoritativeState.activeIds.isEmpty()) {
            null
        } else {
            runRecoverableCatching { authoritativeState.runPlan() }
                .getOrElse { profileError ->
                    throw IllegalStateException(
                        "saved profile state changed during VPN startup",
                        profileError,
                    )
                }
        }
        if (authoritativePlan == plan) return authoritativeSnapshot

        // Build before claiming the next generation. A config error must still
        // be handled by the current command owner.
        val replacementIntent = authoritativePlan?.let { startIntent(this, it) }
        val replacementToken = synchronized(lifecycleCommandLock) {
            if (!commandOwner()) {
                null
            } else if (replacementIntent == null) {
                runtimeCoordinator.claimStop(serviceInstanceId, "profile reconciliation removed runtime").also {
                    runtimeSettingsState.clearForStop()
                }
            } else {
                runtimeCoordinator.claimReplacement(serviceInstanceId)
            }
        }
        if (replacementToken == null) {
            releaseSupersededRuntime()
            return null
        }
        if (replacementIntent == null) {
            TcptunState.appendLog("VPN startup cancelled: no profile remains active")
            runtimeCoordinator.dispatchStop(
                token = replacementToken,
                reason = "profile reconciliation removed runtime",
                options = VpnRuntimeStopOptions(),
                onFailure = { stopError ->
                    TcptunState.appendLog(
                        "profile reconciliation cleanup failed: ${failureDescription(stopError)}",
                    )
                },
                stopRuntime = { options, commandToken, commandOwner ->
                    stopVpn(
                        setStopped = options.setStopped,
                        clearSavedConfig = options.clearSavedConfig,
                        stopSelfService = options.stopSelfService,
                        globalStateOwner = {
                            commandOwner() && runtimeCoordinator.isCurrent(commandToken) &&
                                !destroyed.get() && isActiveServiceOwner()
                        },
                        globalStateCommitLock = lifecycleCommandLock,
                        cleanupOwner = VpnPlatformCleanupOwner.Stop(commandToken),
                    )
                },
            )
            return null
        }

        TcptunState.appendLog(
            "profile state changed during VPN startup; queuing the saved configuration",
        )
        val replacementRequest = VpnRuntimeStartRequest(
            command = VpnServiceIntents.parseStartCommand(this, replacementIntent),
            expectedProfileMutationRevision = authoritativeSnapshot.mutationRevision,
        )
        val accepted = dispatchStartRequest(replacementToken, replacementRequest)
        if (!accepted) {
            runIfLifecycleCommandOwner(replacementToken.lifecycleGeneration) {
                TcptunState.error("VPN profile reconciliation could not be queued")
                stopSelfWhenBridgeReleased(reason = "profile reconciliation rejection")
            }
        }
        return null
    }

    private fun requestRestoreLastRunningConfig(token: VpnRuntimeCommandToken) {
        val plan = readDesiredRunningPlan(this) ?: run {
            runtimeCoordinator.completeNoOpStart(token, runningPlan = null)
            runIfLifecycleCommandOwner(token.lifecycleGeneration) {
                stopSelfWhenBridgeReleased(reason = "no VPN state to restore")
            }
            return
        }
        if (runRecoverableCatching { plan.normalized() }.isFailure) {
            runtimeCoordinator.completeNoOpStart(token, runningPlan = null)
            runIfLifecycleCommandOwner(token.lifecycleGeneration) {
                cleanupStep("clear invalid restored VPN state") { clearDesiredRunningConfig(this) }
                stopSelfWhenBridgeReleased(reason = "invalid VPN restore state")
            }
            return
        }
        if (tun != null) {
            runtimeCoordinator.completeNoOpStart(token, runningPlan ?: plan)
            return
        }
        if (!runtimeCoordinator.isCurrent(token)) return
        val intent = startIntent(this, plan)
        if (!runtimeCoordinator.isCurrent(token)) return
        TcptunState.appendLog("restoring VPN after service restart")
        dispatchStartRequest(
            token,
            VpnRuntimeStartRequest(
                command = VpnServiceIntents.parseStartCommand(this, intent),
                expectedProfileMutationRevision = profileRepository.currentMutationRevision(),
            ),
        )
    }

    private fun releaseSupersededRuntime(
        cleanupOwner: VpnPlatformCleanupOwner? = null,
    ): VpnPlatformStopResult =
        stopVpn(
            setStopped = false,
            clearSavedConfig = false,
            stopSelfService = false,
            globalStateOwner = { false },
            cleanupOwner = cleanupOwner,
        )

    private fun requestOutboundUpdate(intent: Intent, token: VpnRuntimeCommandToken) {
        val nextPlan = runRecoverableCatching {
            VpnServiceIntents.parseOutboundsUpdate(this, intent)
        }.getOrElse { error ->
            TcptunState.appendLog("connection update ignored: ${failureDescription(error)}")
            return
        }
        val lifecycleGeneration = token.lifecycleGeneration
        val updateGeneration = connectionUpdateTracker.begin()
        val profileMutationRevision = profileRepository.currentMutationRevision()
        val membershipEpoch = bridgeResources.activeEpoch
        val desiredNextPlanJson = runRecoverableCatching { encodeDesiredRunningPlan(nextPlan) }
            .getOrElse { error ->
                TcptunState.appendLog("connection update ignored: ${failureDescription(error)}")
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
                return
            }
        TcptunState.setConnectionsReady(false)
        val accepted = runtimeCoordinator.dispatchOutboundUpdate(
            token = token,
            request = VpnRuntimeOutboundUpdateRequest(
                nextPlan = nextPlan,
                hasRuntimeResources = tun != null,
            ),
            onFailure = { error ->
                TcptunState.appendLog("connection update task failed: ${failureDescription(error)}")
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
            },
            persistPlan = { plan, coordinatorOwner ->
                val commandOwner = {
                    coordinatorOwner() && connectionUpdateTracker.isLatest(updateGeneration) &&
                        !destroyed.get() && !explicitStopRequested && isActiveServiceOwner()
                }
                val aligned = profileRepository.alignActiveIdsWithPlanIfCurrent(
                    context = this,
                    expectedMutationRevision = null,
                    plan = plan,
                    commitLock = lifecycleCommandLock,
                    canCommit = commandOwner,
                ).getOrThrow()
                if (aligned) TcptunState.notifyProfileStateChanged()
                synchronized(lifecycleCommandLock) {
                    if (!commandOwner()) false else {
                        publishDesiredRunningPlan(this, desiredNextPlanJson)
                        true
                    }
                }
            },
            mutateOutbound = { profile, shouldRun, coordinatorOwner ->
                val commandOwner = {
                    coordinatorOwner() && connectionUpdateTracker.isLatest(updateGeneration) &&
                        !destroyed.get() && !explicitStopRequested && isActiveServiceOwner()
                }
                setOutboundRunning(
                    profile = profile,
                    shouldRun = shouldRun,
                    expectedEpoch = membershipEpoch,
                    commandOwner = commandOwner,
                )
            },
            onCommitted = { plan ->
                synchronized(lifecycleCommandLock) {
                    if (runtimeCoordinator.isCurrent(token)) {
                        TcptunState.initializeProfileHealth(plan.activeProfiles)
                        bridgeHealthRuntime.scheduleMemberProbe(
                            reason = "active connections changed",
                            requestedDelayMs = BridgeHealthPolicy.MEMBER_HEALTH_MEMBERSHIP_DELAY_MS,
                        )
                        updateNotification(VpnForegroundState.Running(plan.activeProfiles.size))
                    }
                }
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
            },
            onRolledBack = { plan, _ ->
                profileRepository.replaceActiveIdsIfCurrent(
                    context = this,
                    expectedMutationRevision = null,
                    expectedActiveIds = nextPlan.activeIds,
                    replacementActiveIds = plan.activeIds,
                    commitLock = lifecycleCommandLock,
                    canCommit = {
                        runtimeCoordinator.isCurrent(token) && !destroyed.get() &&
                            !explicitStopRequested && isActiveServiceOwner()
                    },
                ).onSuccess { reverted ->
                    if (reverted) TcptunState.notifyProfileStateChanged()
                }.onFailure { error ->
                    TcptunState.appendLog(
                        "connection profile rollback failed: ${failureDescription(error)}",
                    )
                }
                runRecoverableCatching {
                    val encoded = encodeDesiredRunningPlan(plan)
                    synchronized(lifecycleCommandLock) {
                        if (runtimeCoordinator.isCurrent(token)) publishDesiredRunningPlan(this, encoded)
                    }
                }.onFailure { error ->
                    TcptunState.appendLog("connection desired-plan rollback failed: ${failureDescription(error)}")
                }
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
            },
            onMutationFailure = { error, attempted ->
                TcptunState.appendLog("connection update failed: ${failureDescription(error)}")
                if (!attempted) markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
            },
            onReplacementRequired = { replacementToken, plan, failure ->
                if (failure == null) {
                    TcptunState.appendLog("reloading VPN connection configuration")
                } else {
                    cancelPendingBridgeRestart()
                    TcptunState.setConnectionsReady(false)
                    TcptunState.appendLog("rebuilding VPN after incomplete connection rollback")
                }
                val replacementIntent = startIntent(this, plan)
                dispatchStartRequest(
                    replacementToken,
                    VpnRuntimeStartRequest(
                        command = VpnServiceIntents.parseStartCommand(this, replacementIntent),
                        expectedProfileMutationRevision = profileMutationRevision,
                    ),
                )
            },
        )
        if (!accepted) markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
    }

    private fun markConnectionsReadyAfterUpdate(
        lifecycleGeneration: Int,
        updateGeneration: Int,
    ) = synchronized(lifecycleCommandLock) {
        if (
            lifecycleGeneration == this.runtimeSnapshot.lifecycleGeneration &&
            !destroyed.get() &&
            !explicitStopRequested &&
            isActiveServiceOwner()
        ) {
            connectionUpdateTracker.runIfLatest(updateGeneration) {
                if (!stopping && tun != null && TcptunState.status == VpnStatus.Running) {
                    TcptunState.setConnectionsReady(true)
                    bridgeHealthRuntime.start()
                    currentRuntimeOwnership()?.let { reconcileDesiredSettingsAfterRunning(it) }
                }
            }
        }
    }

    private fun setOutboundRunning(
        profile: AppConfig,
        shouldRun: Boolean,
        expectedEpoch: Long,
        commandOwner: () -> Boolean,
    ) {
        val tag = profile.runtimeOutboundTag()
        synchronized(bridgeLock) {
            check(
                commandOwner() &&
                    !stopping &&
                    expectedEpoch > 0L &&
                    expectedEpoch == bridgeResources.activeEpoch,
            ) { "connection update was superseded" }
            if (shouldRun) {
                bridge.startOutbound(tag)
            } else {
                bridge.stopOutbound(tag, force = true, timeoutMillis = OUTBOUND_STOP_TIMEOUT_MS)
            }
        }
        synchronized(lifecycleCommandLock) {
            check(commandOwner() && expectedEpoch == bridgeResources.activeEpoch) {
                "connection update was superseded"
            }
            if (shouldRun) {
                TcptunState.setProfileHealthForBridgeEpoch(expectedEpoch, profile.id, ProfileHealth())
            } else {
                TcptunState.removeProfileHealthForBridgeEpoch(expectedEpoch, profile.id)
            }
            TcptunState.appendLog(
                "connection ${profile.name}: ${if (shouldRun) "started" else "stopped"}",
            )
        }
    }

    private fun Intent.toTcpingRequest() = OutboundTcpingRequest(
        requestId = getLongExtra(EXTRA_TCPING_REQUEST_ID, 0L),
        targetLabel = getStringExtra(EXTRA_TCPING_TARGET_LABEL).orEmpty(),
        host = getStringExtra(EXTRA_TCPING_HOST).orEmpty(),
        port = getIntExtra(EXTRA_TCPING_PORT, 0),
    )

    private fun buildTun(mtu: Int): android.os.ParcelFileDescriptor {
        return Builder()
            .setSession(getString(R.string.vpn_notification_title))
            .setMtu(mtu)
            .addAddress("10.77.0.2", 32)
            .addAddress("fd00:7777::2", 128)
            .addDnsServer(VPN_DNS_ADDRESS)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // Some Android builds do not reliably route raw Go sockets around the VPN
            // with protect(fd) alone. Excluding our process keeps bridge upstream sockets
            // on the selected underlying network; protect(fd) remains a second safeguard.
            .addDisallowedApplication(packageName)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(connectivity.isActiveNetworkMetered)
                }
                allowFamily(android.system.OsConstants.AF_INET)
                allowFamily(android.system.OsConstants.AF_INET6)
            }
            .establish() ?: throw IllegalStateException("VpnService establish() returned null")
    }

    private fun ownsRuntime(ownership: VpnRuntimeOwnership): Boolean =
        !destroyed.get() && !stopping && tun != null && ownership.isCurrent(
            runtimeTokenCurrent = runtimeCoordinator.isCurrent(ownership.runtimeToken),
            activeBridgeEpoch = bridgeResources.activeEpoch,
            activeServiceInstance = isActiveServiceOwner(),
        )

    private fun currentRuntimeOwnership(): VpnRuntimeOwnership? =
        synchronized(lifecycleCommandLock) {
            val epoch = bridgeResources.activeEpoch
            if (epoch <= 0L || destroyed.get() || stopping || tun == null || !isActiveServiceOwner()) {
                return@synchronized null
            }
            VpnRuntimeOwnership(runtimeCoordinator.currentToken(serviceInstanceId), epoch)
        }

    private fun publishAppliedRuntimeState(
        token: VpnRuntimeCommandToken,
        settings: AppliedRuntimeSettings,
    ): VpnRuntimeOwnership? = synchronized(lifecycleCommandLock) {
        runtimeSettingsState.publishFreshRuntime(token, settings, currentRuntimeOwnership())
    }

    private fun checkpointHotApplied(
        ownership: VpnRuntimeOwnership,
        transform: (AppliedRuntimeSettings) -> AppliedRuntimeSettings,
    ): HotAppliedCheckpointResult = synchronized(lifecycleCommandLock) {
        runtimeSettingsState.checkpointHotAppliedOrRejectCurrent(ownership, currentRuntimeOwnership(), transform)
    }

    private fun markHotMutationUncertain(ownership: VpnRuntimeOwnership) = synchronized(lifecycleCommandLock) {
        runtimeSettingsState.markHotMutationUncertain(ownership, currentRuntimeOwnership())
    }

    private fun applyRuntimeLogLevel(ownership: VpnRuntimeOwnership, logLevel: String) =
        synchronized(bridgeLock) {
            check(ownsRuntime(ownership)) { "runtime settings apply was superseded" }
            bridge.setLogLevel(logLevel)
            check(bridge.logLevel() == logLevel) {
                "tcptun bridge did not apply log.level=$logLevel"
            }
        }

    private fun applyRuntimeFlowAnalysis(ownership: VpnRuntimeOwnership, packageName: String) =
        synchronized(bridgeLock) {
            check(ownsRuntime(ownership)) { "flow analysis update was superseded" }
            configureFlowAnalysis(
                packageName,
                ownership.bridgeEpoch,
                requireNotNull(bridgeResources.activeConfigJson),
            )
        }

    private fun reconcileDesiredSettingsAfterRunning(
        ownership: VpnRuntimeOwnership,
        freshRuntimeDesiredSequence: Long? = null,
    ) {
        val desired = RuntimeSettingsRepository.read(this) as? RuntimeSettingsRead.Success ?: run {
            TcptunState.appendLog("runtime settings unavailable; keeping current applied settings")
            return
        }
        synchronized(lifecycleCommandLock) {
            runtimeSettingsState.reconcileFreshRuntime(
                ownership,
                AppliedRuntimeSettings.from(desired.settings),
                freshRuntimeDesiredSequence,
                currentRuntimeOwnership(),
            ) { scheduleRuntimeSettingsApply("pending runtime settings reconciliation", it) }
        }
    }

    private fun requestStopVpn() {
        debugLifecycleMarker("cleanup requested")
        cleanupStep("clear TCPing") { TcptunState.clearTcping() }
        val (command, accepted) = synchronized(lifecycleCommandLock) {
            val token = runtimeCoordinator.claimStop(serviceInstanceId, "explicit VPN stop")
            runtimeSettingsState.clearForStop()
            // Claiming the generation and persisting desired=false share the
            // same lock as successful-start publication, so an older start
            // cannot write desired=true after this stop request.
            cleanupStep("persist requested stopped state") { clearDesiredRunningConfig(this) }
            val profileMutationRevision = profileRepository.currentMutationRevision()
            cleanupStep("set stopping state") { TcptunState.setStatus(VpnStatus.Stopping) }
            cleanupStep("disable connections") { TcptunState.setConnectionsReady(false) }
            cancelPendingBridgeRestart()
            bridgeSessionRuntime.cancelReadyWaiter("tcptun stop requested")
            bridgeRecoveryTask.cancel()
            bridgeRecoveryCoordinator.resetRecovery()
            (token to profileMutationRevision) to runtimeCoordinator.dispatchStop(
                token = token,
                reason = "explicit VPN stop",
                options = VpnRuntimeStopOptions(),
                onFailure = { error ->
                    val handled = runIfLifecycleCommandOwner(token.lifecycleGeneration) {
                        TcptunState.error(failureDescription(error))
                        stopSelfWhenBridgeReleased(reason = "VPN stop task failure")
                    }
                    if (!handled) {
                        TcptunState.appendLog("stale VPN stop failed: ${failureDescription(error)}")
                    }
                },
                beforeStop = { commandToken, commandOwner ->
                    profileRepository.clearActiveIfCurrent(
                        context = this,
                        expectedMutationRevision = profileMutationRevision,
                        commitLock = lifecycleCommandLock,
                        canCommit = {
                            commandOwner() && runtimeCoordinator.isCurrent(commandToken) &&
                                !destroyed.get() &&
                                isActiveServiceOwner()
                        },
                    ).onSuccess { cleared ->
                        if (cleared) TcptunState.notifyProfileStateChanged()
                    }.onFailure { error ->
                        TcptunState.appendLog(
                            "clear active profiles failed: ${failureDescription(error)}",
                        )
                    }
                },
                stopRuntime = { options, commandToken, commandOwner ->
                    stopVpn(
                        setStopped = options.setStopped,
                        clearSavedConfig = options.clearSavedConfig,
                        stopSelfService = options.stopSelfService,
                        propagateBridgeStopFailure = options.propagateBridgeStopFailure,
                        globalStateOwner = {
                            commandOwner() && runtimeCoordinator.isCurrent(commandToken) &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                        },
                        globalStateCommitLock = lifecycleCommandLock,
                        cleanupOwner = VpnPlatformCleanupOwner.Stop(commandToken),
                    )
                },
            )
        }
        val (token, profileMutationRevision) = command
        if (!accepted) {
            startCrashGuardedThread(
                threadName = "TcptunStopPersistenceFallback",
                onFailure = { error -> cleanupStep("stop persistence fallback") { throw error } },
            ) {
                profileRepository.clearActiveIfCurrent(
                    context = this,
                    expectedMutationRevision = profileMutationRevision,
                    commitLock = lifecycleCommandLock,
                    canCommit = {
                        runtimeCoordinator.isCurrent(token) &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                ).onSuccess { cleared ->
                    if (cleared) TcptunState.notifyProfileStateChanged()
                }
                synchronized(lifecycleCommandLock) {
                    if (
                        runtimeCoordinator.isCurrent(token) &&
                        !destroyed.get() &&
                        isActiveServiceOwner()
                    ) {
                        cleanupStep("clear desired VPN config") { clearDesiredRunningConfig(this) }
                    }
                }
            }
            runIfLifecycleCommandOwner(token.lifecycleGeneration) {
                stopSelfWhenBridgeReleased(reason = "VPN stop task rejection")
            }
        }
    }

    private fun closeTunAfterBridgeStopAttempt() {
        if (!canCloseAndroidTun(bridgeResources.snapshot)) {
            // Keep the original ParcelFileDescriptor alive while Go may still
            // own its duplicate. A retry or destroy coordinator must finish
            // the native stop before this slot is detached.
            TcptunState.appendLog("retaining VPN TUN until native ownership is released")
            return
        }
        val activeTun = tunOwner.release()
        if (activeTun != null) {
            TcptunState.appendLog("closing VPN TUN")
            runRecoverableCatching { activeTun.close() }
                .onFailure { err -> TcptunState.appendLog("VPN TUN close failed: ${err.message}") }
        }
        if (!bridgeResources.hasOwnedResources) {
            bridgeRuntimeLease.release(serviceInstanceId)
        }
    }

    override fun onRevoke() {
        try {
            debugLifecycleMarker("onRevoke observed")
            TcptunState.appendLog("VPN permission revoked")
            requestStopVpn()
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            synchronized(lifecycleCommandLock) {
                if (isActiveServiceOwner()) {
                    TcptunState.error("VPN revoke cleanup failed: ${failureDescription(error)}")
                    stopSelfWhenBridgeReleased(reason = "VPN revoke cleanup failure")
                }
            }
        } finally {
            super.onRevoke()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            synchronized(lifecycleCommandLock) {
                if (
                    !destroyed.get() &&
                    initialized.get() &&
                    !explicitStopRequested &&
                    isActiveServiceOwner() &&
                    (tun != null || bridgeResources.hasOwnedResources || bridgeRecoveryCoordinator.recoveryPending)
                ) {
                    val notificationState = runningPlan?.let {
                        VpnForegroundState.Running(it.activeProfiles.size)
                    }
                        ?: if (bridgeRecoveryCoordinator.recoveryPending) {
                            VpnForegroundState.Reconnecting()
                        } else {
                            VpnForegroundState.Running()
                        }
                    startVpnForeground(notificationState)
                    TcptunState.appendLog("app task removed; VPN foreground service remains active")
                }
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            cleanupStep("keep VPN foreground after app task removal") { throw error }
        } finally {
            super.onTaskRemoved(rootIntent)
        }
    }

    private fun stopVpn(
        setStopped: Boolean = true,
        clearSavedConfig: Boolean = true,
        stopSelfService: Boolean = true,
        propagateBridgeStopFailure: Boolean = false,
        globalStateOwner: (() -> Boolean)? = null,
        globalStateCommitLock: Any? = null,
        cleanupOwner: VpnPlatformCleanupOwner? = null,
    ): VpnPlatformStopResult {
        val request = VpnPlatformTeardownRequest(
            setStopped = setStopped,
            clearSavedConfig = clearSavedConfig,
            stopSelfService = stopSelfService,
            globalStateOwner = globalStateOwner,
            globalStateCommitLock = globalStateCommitLock,
            cleanupOwner = cleanupOwner,
        )
        val attempt = synchronized(teardownLock) {
            performVpnCleanupAttempt(request).also {
                platformTeardownRuntime.acceptInitialResult(request, it.result)
            }
        }
        if (propagateBridgeStopFailure) {
            attempt.bridgeStopFailure?.let { error ->
                throw IllegalStateException(
                    "VPN reload aborted because the bridge did not stop cleanly",
                    error,
                )
            }
        }
        return attempt.result
    }

    /** Performs exactly one physical cleanup attempt; retry admission belongs to the runtime. */
    private fun performVpnCleanupAttempt(request: VpnPlatformTeardownRequest): VpnCleanupAttempt =
        synchronized(teardownLock) {
            platformCleanupAdapter.perform(
                request,
                VpnCleanupPublicationPort(
                    globalStep = { label, action ->
                        request.runGlobalCleanupStep(serviceOwnerLock, label, ::cleanupStep, action)
                    },
                    localStep = ::cleanupStep,
                ),
            ).also { attempt ->
                debugLifecycleMarker(
                    when (attempt.result) {
                        VpnPlatformStopResult.Released -> "cleanup released"
                        VpnPlatformStopResult.RetainedForRetry -> "cleanup retained"
                    },
                )
            }
        }

    private fun honorDeferredStopIfReleased() {
        synchronized(lifecycleCommandLock) {
            val deferredStop = deferredServiceStopGate.consumeIfReleased(
                currentLifecycleGeneration = runtimeSnapshot.lifecycleGeneration,
                currentPersistentCommandGeneration = runtimeSnapshot.persistentCommandGeneration,
                resourcesReleased = true,
                activeServiceOwner = !destroyed.get() && isActiveServiceOwner(),
            )
            deferredStop?.startId?.let(::stopSelf)
        }
    }

    /** Routes typed completion while keeping coordinator phase authority in the Service. */
    private fun completePlatformCleanupOwner(
        owner: VpnPlatformCleanupOwner,
        result: VpnPlatformStopResult,
    ) {
        when (owner) {
            is VpnPlatformCleanupOwner.Stop ->
                runtimeCoordinator.completePlatformStop(owner.token, result)
            is VpnPlatformCleanupOwner.StartRollback ->
                runtimeCoordinator.completeStartRollbackCleanup(owner.token, result)
            is VpnPlatformCleanupOwner.RecoveryRollback ->
                runtimeCoordinator.completeRecoveryRollbackCleanup(owner.token, result)
                    ?.let { retryToken ->
                        scheduleBridgeRecovery(retryToken, owner.request, owner.failure)
                    }
        }
    }

    private fun cleanupStep(label: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            try {
                TcptunState.appendLog("$label failed: ${failureDescription(error)}")
            } catch (loggingError: Throwable) {
                if (loggingError.isFatalProcessError()) throw loggingError
            }
        }
    }

    override fun onDestroy() {
        debugLifecycleMarker("onDestroy observed")
        if (!destroyed.compareAndSet(false, true)) {
            super.onDestroy()
            return
        }
        try {
            initialized.set(false)
            VpnHealthCheckRequests.uninstall(
                bridgeHealthRuntime.monitorWakeCallback,
                bridgeHealthRuntime.memberHealthProbeCallback,
            )
            synchronized(lifecycleCommandLock) {
                try {
                    runtimeCoordinator.destroy(serviceInstanceId)
                } catch (error: Throwable) {
                    if (error.isFatalProcessError()) throw error
                    runtimeCoordinator.closeExternalIngress()
                    TcptunState.appendLog(
                        "runtime actor destroy admission failed; continuing physical teardown",
                    )
                }
                runtimeSettingsState.clearForStop()
                if (isActiveServiceOwner()) TcptunState.clearTcping()
            }
            cancelPendingBridgeRestart()
            deferredServiceStopGate.clear()
            bridgeSessionRuntime.cancelReadyWaiter("tcptun service destroyed")
            bridgeRecoveryTask.cancel()
            bridgeHealthRuntime.shutdown()
            outboundTcpingRuntime.shutdown()
            launchDestroyCleanup()
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            cleanupStep("VPN service destroy") { throw error }
        } finally {
            super.onDestroy()
        }
    }

    /** Keep JNI stop/close and bridge-lock waits off Android's main service thread. */
    private fun launchDestroyCleanup() {
        val physicalCleanupCompleted = AtomicBoolean()
        val cleanupScheduled = executeCrashGuarded(
            executor = lifecycleExecutor,
            taskName = "VPN destroy physical cleanup",
            onFailure = { error -> cleanupStep("VPN destroy physical cleanup") { throw error } },
        ) {
            // This task is queued after already-admitted lifecycle work. It lets owned
            // completions drain through the Actor before the final physical teardown.
            platformTeardownRuntime.shutdown()
            stopVpn(
                setStopped = TcptunState.status != VpnStatus.Error,
                clearSavedConfig = false,
                stopSelfService = false,
                globalStateOwner = ::isActiveServiceOwner,
            )
            physicalCleanupCompleted.set(true)
        }
        lifecycleExecutor.shutdown()
        val coordinator = startCrashGuardedThread(
            threadName = "TcptunDestroyCoordinator",
            onFailure = { error -> cleanupStep("VPN destroy coordinator") { throw error } },
        ) coordinator@{
            var abortSucceeded = false
            val destroyDeadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(DESTROY_MAX_LIFECYCLE_WAIT_MS)
            var lifecycleStopped = lifecycleExecutor.awaitTermination(
                DESTROY_EXECUTOR_WAIT_MS,
                TimeUnit.MILLISECONDS,
            )
            if (!lifecycleStopped) {
                TcptunState.appendLog("tcptun lifecycle is still exiting; aborting native session")
                abortSucceeded = abortBridgeEngineForDestroy()
                lifecycleExecutor.shutdownNow()
                lifecycleStopped = lifecycleExecutor.awaitTermination(
                    DESTROY_ABORT_SETTLE_WAIT_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
            if (!lifecycleStopped) {
                TcptunState.appendLog(
                    "tcptun lifecycle task did not exit after abort; teardown remains owned off the main thread",
                )
            }

            while (!lifecycleStopped) {
                // Never start a second teardown thread while lifecycle work may
                // still be inside JNI holding bridgeLock. The coordinator keeps
                // ownership and performs cleanup itself as soon as that worker exits.
                val remainingNanos = destroyDeadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                lifecycleStopped = lifecycleExecutor.awaitTermination(
                    minOf(
                        DEFERRED_DESTROY_WAIT_MS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                    ),
                    TimeUnit.MILLISECONDS,
                )
                if (!lifecycleStopped && !abortSucceeded) {
                    abortSucceeded = abortBridgeEngineForDestroy()
                }
            }

            if (!lifecycleStopped) {
                TcptunState.appendLog(
                    "tcptun lifecycle did not stop within the destroy deadline; native ownership retained",
                )
                platformTeardownRuntime.shutdown()
                if (!runtimeCoordinator.shutdownActor()) {
                    TcptunState.appendLog("runtime actor did not terminate cleanly")
                }
                return@coordinator
            }

            platformTeardownRuntime.shutdown()
            if (!cleanupScheduled || !physicalCleanupCompleted.get()) {
                stopVpn(
                    setStopped = TcptunState.status != VpnStatus.Error,
                    clearSavedConfig = false,
                    stopSelfService = false,
                    globalStateOwner = ::isActiveServiceOwner,
                )
            }
            if (!bridgeResources.hasOwnedResources && closeBridgeEngine()) {
                TcptunState.appendLog("tcptun destroy cleanup completed")
                RuntimeOwnershipDebugRegistry.remove(serviceInstanceId)
            } else {
                TcptunState.appendLog(
                    "tcptun destroy cleanup incomplete; native resources retained for safe process teardown",
                )
            }
            if (!runtimeCoordinator.shutdownActor()) {
                TcptunState.appendLog("runtime actor did not terminate cleanly")
            }
        }
        if (coordinator == null) {
            cleanupStep("start VPN destroy coordinator") {
                throw IllegalStateException("destroy coordinator thread could not be started")
            }
        }
    }

    private fun captureRuntimeOwnershipDebugState(): RuntimeOwnershipDebugCapture {
        val coordinator = runtimeSnapshot
        val resources = bridgeResources.snapshot
        val state = TcptunState.state.value
        return RuntimeOwnershipDebugCapture(
            lifecycleGeneration = coordinator.lifecycleGeneration,
            persistentGeneration = coordinator.persistentCommandGeneration,
            recoveryGeneration = coordinator.recoveryGeneration,
            bridgeEpoch = resources.epoch,
            bridgeResourcePhase = resources.phase,
            tunOwned = tun != null,
            leaseOwner = bridgeRuntimeLease.owner,
            teardownPending = platformTeardownRuntime.pending,
            runtimePhase = coordinator.phase.javaClass.simpleName,
            activeServiceOwner = isActiveServiceOwner(),
            destroyed = destroyed.get(),
            vpnStatus = state.status,
            connectionsReady = state.connectionsReady,
            actorPhase = coordinator.phase.javaClass.simpleName,
            actorOwnerServiceId = coordinator.serviceInstanceId,
            actorGeneration = coordinator.lifecycleGeneration,
        )
    }

    /** Abort is deliberately not serialized by bridgeLock: it must release a JNI call holding that lock. */
    private fun abortBridgeEngineForDestroy(): Boolean {
        // Return false so the coordinator retries if bridge initialization is
        // racing this check; do not mark an engine we never observed as aborted.
        if (!bridgeDelegate.isInitialized()) return false
        return try {
            bridge.abort()
            TcptunState.appendLog("tcptun native session aborted for destroy cleanup")
            true
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            cleanupStep("tcptun engine abort") { throw error }
            false
        }
    }

    private fun closeBridgeEngine(): Boolean {
        if (!bridgeDelegate.isInitialized()) {
            bridgeResources.engineClosed()
            bridgeRuntimeLease.release(serviceInstanceId)
            return true
        }
        if (bridgeResources.snapshot.nativeStopRequired) {
            TcptunState.appendLog("tcptun engine close deferred while native session is active")
            return false
        }
        return try {
            synchronized(bridgeLock) {
                bridge.close()
                bridgeResources.engineClosed()
            }
            bridgeRuntimeLease.release(serviceInstanceId)
            true
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            cleanupStep("tcptun engine close") { throw error }
            false
        }
    }

    private fun startBridge(
        configJson: String,
        plan: ProfileRunPlan,
        vpnTun: android.os.ParcelFileDescriptor,
        mtu: Int,
        settings: AppliedRuntimeSettings,
        commandOwner: () -> Boolean,
    ) {
        requireSafeAppliedRuntimeSettings(settings)
        check(bridgeRuntimeLease.owner == serviceInstanceId) {
            "tcptun service does not own the native runtime lease"
        }
        bridgeSessionRuntime.startSession(
            request = BridgeSessionRuntimeStartRequest(
                configJson = configJson,
                disabledOutboundTags = initiallyDisabledOutboundTags(plan),
                tunFd = vpnTun.fd,
                mtu = mtu,
                settings = settings,
                readyTimeoutMillis = BRIDGE_READY_TIMEOUT_MS,
            ),
            callbacks = bridgeSessionServicePort.callbacks(commandOwner),
        )
        // CORE_RUNTIME_READY confirms bind/Serve startup, but not a completed
        // handshake. Validate the configured local path before publishing Running.
        check(commandOwner()) { "tcptun start was superseded" }
        val listener = LocalProxyHealthProbe().listener(settings.socksPort, settings.localProxyUsers.firstOrNull())
        check(commandOwner()) { "tcptun start was superseded" }
        TcptunState.appendProxyDiagnostic(
            "local_proxy startup service_id=$serviceInstanceId bridge_epoch=${bridgeResources.activeEpoch} " +
                "native_session=${bridgeResources.snapshot.sessionId} protocol=${settings.localProxyProtocol} " +
                "listen_all=${settings.socksListenAll} ${listener.summary()}",
        )
        check(listener.healthy) { "local proxy startup ${listener.summary()}" }
    }

    private fun stopBridge() {
        bridgeSessionRuntime.stopSession(
            settleTimeoutMillis = BRIDGE_STOP_SETTLE_TIMEOUT_MS,
            callbacks = bridgeSessionServicePort.callbacks { false },
        )
    }

    private fun configureFlowAnalysis(packageName: String, epoch: Long, configJson: String) {
        val normalized = normalizeFlowAnalysisApp(packageName)
        TcptunState.setFlowAnalysisApp(normalized)
        val identityLookupRequired = configRequiresAppIdentityLookup(configJson, normalized)
        if (identityLookupRequired) {
            appIdentityProvider.setIdentityLookupRequired(true)
            appIdentityProvider.setFlowAnalysisApp(normalized)
            bridge.setAppIdentityProvider { flow ->
                if (destroyed.get() || stopping) null else appIdentityProvider.identify(flow)
            }
        } else {
            if (appIdentityProviderDelegate.isInitialized()) {
                appIdentityProvider.setIdentityLookupRequired(false)
                appIdentityProvider.setFlowAnalysisApp("")
            }
            bridge.clearAppIdentityProvider()
        }
        if (normalized.isBlank()) {
            bridge.setFlowAnalysisApp("")
            bridge.clearFlowCallback()
        } else {
            bridge.setFlowCallback { eventJson -> TcptunState.applyBridgeFlowEvent(epoch, eventJson) }
            bridge.setFlowAnalysisApp(normalized)
        }
    }

    private fun prepareBridgeRestart(
        reason: String,
        commandOwner: () -> Boolean,
    ): RuntimeSettingsRecoveryInput {
        val configJson = checkNotNull(bridgeResources.activeConfigJson) { "tcptun bridge is unavailable" }
        val plan = checkNotNull(runningPlan) { "tcptun running plan is unavailable" }
        val settings = checkNotNull(runtimeSettingsState.applied?.settings) {
            "tcptun applied runtime settings are unavailable"
        }
        check(tun != null && commandOwner()) { "tcptun restart was superseded" }
        synchronized(lifecycleCommandLock) {
            check(commandOwner()) { "tcptun restart was superseded" }
            TcptunState.setConnectionsReady(false)
            TcptunState.appendLog("restarting tcptun bridge transaction: $reason")
            TcptunState.updateDiagnostics {
                it.copy(lastRestartReason = reason, bridgeStatus = "Restarting")
            }
        }
        stopBridge()
        check(commandOwner()) { "tcptun restart was superseded" }
        closeTunAfterBridgeStopAttempt()
        check(commandOwner()) { "tcptun restart was superseded" }
        return RuntimeSettingsRecoveryInput(configJson, plan, settings, reason)
    }

    private fun continueBridgeRestart(
        preparation: RuntimeSettingsRecoveryInput,
        lifecycleToken: VpnRuntimeCommandToken,
        commandOwner: () -> Boolean,
    ): VpnRuntimeOwnership {
        check(commandOwner()) { "tcptun restart continuation was superseded" }
        claimBridgeRuntimeLease(commandOwner)
        check(commandOwner()) { "tcptun restart continuation was superseded" }
        val replacementTun = buildTun(preparation.settings.mtu)
        ownTun(replacementTun)
        startBridge(
            preparation.configJson,
            preparation.plan,
            replacementTun,
            preparation.settings.mtu,
            preparation.settings,
            commandOwner,
        )
        val appliedOwnership = publishAppliedRuntimeState(lifecycleToken, preparation.settings)
            ?: error("tcptun restart continuation was superseded while publishing")
        underlyingNetworkRuntime.republishCurrent("Bridge runtime replaced")
        val replacementEpoch = bridgeResources.activeEpoch
        synchronized(lifecycleCommandLock) {
            if (
                commandOwner() && replacementEpoch == bridgeResources.activeEpoch &&
                tun === replacementTun
            ) {
                TcptunState.appendLog("tcptun bridge transaction restarted")
                TcptunState.setConnectionsReady(true)
                VpnHealthCheckRequests.clearRuntimeForces()
                bridgeHealthRuntime.start()
                // The previous runtime's balance observations were discarded
                // by the restart, so seed health after the replacement settles.
                bridgeHealthRuntime.scheduleMemberProbe(
                    reason = "bridge restarted: ${preparation.reason}",
                    requestedDelayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
                )
            }
        }
        return appliedOwnership
    }

    private fun ownTun(descriptor: android.os.ParcelFileDescriptor) {
        try {
            tunOwner.acquire(descriptor)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            runRecoverableCatching { descriptor.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun claimBridgeRuntimeLease(commandOwner: () -> Boolean) {
        val previousOwner = bridgeRuntimeLease.owner
        if (previousOwner != 0L && previousOwner != serviceInstanceId) {
            TcptunState.appendLog("waiting for previous tcptun service runtime to close")
        }
        when (
            bridgeRuntimeLease.acquire(
                requestedOwnerId = serviceInstanceId,
                timeoutMillis = PREVIOUS_RUNTIME_RELEASE_WAIT_MS,
                canContinue = commandOwner,
            )
        ) {
            RuntimeLeaseClaim.Acquired,
            RuntimeLeaseClaim.AlreadyOwned,
            -> Unit

            RuntimeLeaseClaim.Cancelled -> error("tcptun start was superseded")
            RuntimeLeaseClaim.TimedOut -> error(
                "previous tcptun service did not release its runtime",
            )
        }
        if (previousOwner != 0L && previousOwner != serviceInstanceId) {
            TcptunState.appendLog("previous tcptun service runtime released")
        }
    }

    private fun cancelPendingBridgeRestart() {
        synchronized(bridgeRestartScheduleLock) {
            bridgeRecoveryCoordinator.cancelRestart()
            bridgeRestartTask.cancel()
            bridgeRestartContinuationTask.cancel()
        }
    }

    private fun requestBridgeRestart(
        reason: String,
        settleDelayMs: Long = 0L,
        cancelIfHealthy: Boolean = false,
        ownership: VpnRuntimeOwnership,
    ) {
        synchronized(bridgeRestartScheduleLock) {
            if (stopping || destroyed.get() || !ownsRuntime(ownership)) return
            bridgeRecoveryTask.cancel()
            bridgeRestartContinuationTask.cancel()
            val plan = runningPlan ?: return
            val lifecycleToken = ownership.runtimeToken
            val recoveryToken = runtimeCoordinator.claimRecovery(lifecycleToken) ?: return
            val bridgeToken = bridgeRecoveryCoordinator.requestRestart(
                lifecycleGeneration = runtimeSnapshot.lifecycleGeneration,
                cancelIfHealthy = cancelIfHealthy,
            )
            TcptunState.appendProxyDiagnostic(
                "bridge_restart ${ownership.diagnosticId()} restart_token=$bridgeToken " +
                    "recovery_generation=${recoveryToken.recoveryGeneration} reason=$reason",
            )
            scheduleBridgeRestart(
                request = VpnRuntimeRecoveryRequest(plan, reason),
                bridgeToken = bridgeToken,
                recoveryToken = recoveryToken,
                settleDelayMs = settleDelayMs,
            )
        }
    }

    private fun scheduleBridgeRestart(
        request: VpnRuntimeRecoveryRequest,
        bridgeToken: BridgeRestartToken,
        recoveryToken: VpnRuntimeRecoveryToken,
        settleDelayMs: Long = 0L,
    ): Unit = synchronized(bridgeRestartScheduleLock) {
        if (stopping || destroyed.get()) return@synchronized
        val scheduleDelayMs = bridgeRecoveryCoordinator.scheduleDelayMillis(
            token = bridgeToken,
            currentLifecycleGeneration = runtimeSnapshot.lifecycleGeneration,
            nowMillis = System.currentTimeMillis(),
            settleDelayMillis = settleDelayMs,
        ) ?: return@synchronized
        bridgeRestartTask.cancel()
        val future = scheduleCrashGuardedFuture(
            executor = lifecycleExecutor,
            delay = scheduleDelayMs,
            unit = TimeUnit.MILLISECONDS,
            taskName = "tcptun bridge restart",
            onFailure = { error ->
                if (!stopping && !destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
        ) restart@{
                // A zero-delay task may be picked up before schedule() returns.
                // Passing through the scheduling lock guarantees its Future is
                // installed in bridgeRestartTask before the body can continue.
                synchronized(bridgeRestartScheduleLock) {
                    if (
                        !bridgeRecoveryCoordinator.isCurrent(
                            bridgeToken,
                            runtimeSnapshot.lifecycleGeneration,
                        ) ||
                        !runtimeCoordinator.isCurrent(recoveryToken) ||
                        stopping || destroyed.get()
                    ) return@restart
                }
                if (
                    !bridgeRecoveryCoordinator.isCurrent(bridgeToken, runtimeSnapshot.lifecycleGeneration) ||
                    !runtimeCoordinator.isCurrent(recoveryToken) ||
                    stopping || destroyed.get() || tun == null
                ) return@restart
                val remainingMs = bridgeRecoveryCoordinator.remainingCooldownMillis(
                    System.currentTimeMillis(),
                )
                if (remainingMs > 0) {
                    scheduleBridgeRestart(request, bridgeToken, recoveryToken)
                    return@restart
                }
                val recoveryClaimed = synchronized(bridgeRestartScheduleLock) {
                    if (
                        stopping || destroyed.get() ||
                        !bridgeRecoveryCoordinator.claimRestart(
                            bridgeToken,
                            runtimeSnapshot.lifecycleGeneration,
                        ) || !runtimeCoordinator.isCurrent(recoveryToken)
                    ) {
                        false
                    } else {
                        bridgeRecoveryCoordinator.beginRestart(System.currentTimeMillis()) == 0L
                    }
                }
                if (!recoveryClaimed) return@restart
                runtimeCoordinator.dispatchRecoveryPreparation(
                    token = recoveryToken,
                    request = request,
                    onFailure = { error ->
                        if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
                    },
                    prepareRuntime = { recoveryRequest, _, coordinatorOwner ->
                        val commandOwner = {
                            coordinatorOwner() && !explicitStopRequested &&
                                !destroyed.get() && isActiveServiceOwner()
                        }
                        val preparation = prepareBridgeRestart(recoveryRequest.reason, commandOwner)
                        check(
                            scheduleBridgeRestartContinuation(
                                preparation,
                                recoveryRequest,
                                bridgeToken,
                                recoveryToken,
                            ),
                        ) { "bridge restart continuation could not be scheduled" }
                    },
                    rollbackRecovery = ::rollbackBridgeRecovery,
                    onRetryRequired = ::scheduleBridgeRecovery,
                )
        }
        if (future != null) bridgeRestartTask.replace(future)
    }

    private fun scheduleBridgeRestartContinuation(
        preparation: RuntimeSettingsRecoveryInput,
        request: VpnRuntimeRecoveryRequest,
        bridgeToken: BridgeRestartToken,
        recoveryToken: VpnRuntimeRecoveryToken,
    ): Boolean {
        val future = scheduleCrashGuardedFuture(
            executor = lifecycleExecutor,
            delay = BRIDGE_RESTART_DELAY_MS,
            unit = TimeUnit.MILLISECONDS,
            taskName = "tcptun bridge restart continuation",
            onFailure = { error ->
                if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
        ) continuation@{
            if (
                !bridgeRecoveryCoordinator.isCurrent(
                    bridgeToken,
                    runtimeSnapshot.lifecycleGeneration,
                ) || !runtimeCoordinator.isCurrent(recoveryToken) ||
                explicitStopRequested || destroyed.get()
            ) return@continuation
            runtimeCoordinator.dispatchRecoveryContinuation(
                token = recoveryToken,
                request = request,
                onFailure = { error ->
                    if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
                },
                recoverRuntime = { recoveryRequest, _, coordinatorOwner, commitRunning ->
                    val commandOwner = {
                        coordinatorOwner() && !explicitStopRequested &&
                            !destroyed.get() && isActiveServiceOwner()
                    }
                    val appliedOwnership = continueBridgeRestart(
                        preparation,
                        recoveryToken.lifecycleToken,
                        commandOwner,
                    )
                    check(commitRunning(recoveryRequest.plan)) {
                        "bridge restart continuation was superseded while committing"
                    }
                    reconcileDesiredSettingsAfterRunning(appliedOwnership)
                },
                rollbackRecovery = ::rollbackBridgeRecovery,
                onRetryRequired = ::scheduleBridgeRecovery,
            )
        } ?: return false
        bridgeRestartContinuationTask.replace(future)
        return true
    }

    private fun scheduleBridgeRecovery(
        recoveryToken: VpnRuntimeRecoveryToken,
        request: VpnRuntimeRecoveryRequest,
        failure: Throwable,
    ) {
        val recoveryAttempt = bridgeRecoveryCoordinator.nextRecoveryAttempt()
        val attempt = recoveryAttempt.number
        val delayMs = recoveryAttempt.delayMillis
        val failureText = failureDescription(failure)
        val owned = synchronized(lifecycleCommandLock) {
            if (
                !runtimeCoordinator.isCurrent(recoveryToken) ||
                explicitStopRequested ||
                destroyed.get() ||
                !isActiveServiceOwner()
            ) {
                false
            } else {
                TcptunState.setStatus(VpnStatus.Starting)
                TcptunState.setConnectionsReady(false)
                TcptunState.updateDiagnostics {
                    it.copy(
                        bridgeStatus = "Reconnecting",
                        lastRestartReason = request.reason,
                        bridgeLastError = failureText,
                    )
                }
                cleanupStep("update bridge recovery notification") {
                    updateNotification(VpnForegroundState.Reconnecting(attempt))
                }
                TcptunState.appendLog(
                    "tcptun bridge restart failed; retry $attempt in ${delayMs}ms: $failureText",
                )
                true
            }
        }
        if (!owned) return

        val future = scheduleCrashGuardedFuture(
            executor = lifecycleExecutor,
            delay = delayMs,
            unit = TimeUnit.MILLISECONDS,
            taskName = "tcptun bridge recovery",
            onFailure = { retryError ->
                if (
                    runtimeCoordinator.isCurrent(recoveryToken) &&
                    !explicitStopRequested &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
                ) {
                    runtimeCoordinator.claimRecoveryRetry(recoveryToken)?.let { retryToken ->
                        scheduleBridgeRecovery(
                            recoveryToken = retryToken,
                            request = request,
                            failure = retryError,
                        )
                    }
                }
            },
        ) recovery@{
            if (!runtimeCoordinator.isCurrent(recoveryToken)) return@recovery
            val startRequest = VpnRuntimeStartRequest(
                command = VpnServiceIntents.parseStartCommand(this, startIntent(this, request.plan)),
                expectedProfileMutationRevision = profileRepository.currentMutationRevision(),
                preserveDesiredStateOnFailure = true,
                restartReason = request.reason,
            )
            runtimeCoordinator.dispatchRecovery(
                token = recoveryToken,
                request = request,
                onFailure = { retryError ->
                    if (!destroyed.get()) TcptunState.appendLog(failureDescription(retryError))
                },
                recoverRuntime = { _, commandToken, coordinatorOwner, commitRunning ->
                    startRuntimeNow(
                        startRequest,
                        commandToken.lifecycleToken,
                        coordinatorOwner,
                        commitRunning,
                    )
                },
                rollbackRecovery = { recoveryRequest, commandToken, retryError, superseded ->
                    rollbackRecoveryStart(
                        startRequest,
                        recoveryRequest,
                        commandToken,
                        retryError,
                        superseded,
                    )
                },
                onRetryRequired = ::scheduleBridgeRecovery,
            )
        }
        if (future == null) {
            synchronized(lifecycleCommandLock) {
                if (
                    runtimeCoordinator.isCurrent(recoveryToken) &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
                ) {
                    TcptunState.error("tcptun bridge recovery could not be scheduled")
                    stopSelfWhenBridgeReleased(reason = "bridge recovery scheduling failure")
                }
            }
            return
        }
        bridgeRecoveryTask.replace(future)
    }

    private fun requestRuntimeSettingsApply(reason: String, forceRestart: Boolean) {
        TcptunState.appendLog("runtime settings apply requested: $reason")
        runtimeSettingsState.requestDesiredDebounced(
            forceRestart = forceRestart,
            ownership = currentRuntimeOwnership(),
            executor = lifecycleExecutor,
            delayMillis = RUNTIME_SETTINGS_APPLY_DEBOUNCE_MS,
            canRun = { !destroyed.get() },
            onFailure = { error ->
                if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
            onReady = { scheduleRuntimeSettingsApply(reason, it) },
            onUnavailable = { retained ->
                val message = if (retained) "retained until a VPN runtime is available" else "could not be scheduled"
                TcptunState.appendLog("runtime settings $message")
            },
        )
    }

    private fun scheduleRuntimeSettingsApply(
        reason: String,
        request: RuntimeSettingsApplyClaim,
    ) {
        val accepted = synchronized(lifecycleCommandLock) {
            runtimeSettingsState.dispatchLatest(
                request,
                currentRuntimeOwnership(),
                blocked = destroyed.get() || explicitStopRequested,
            ) {
                executeLifecycleTask(
                    command = VpnRuntimeCommand.ApplyRuntimeSettings(request),
                    onFailure = { error ->
                        if (!destroyed.get() && runtimeSettingsState.isLatest(request)) {
                            TcptunState.appendLog(failureDescription(error))
                        }
                    },
                ) { applyRuntimeSettingsNow(reason, request) }
            }
        }
        if (accepted == false && !destroyed.get()) {
            TcptunState.appendLog("runtime settings apply could not be scheduled")
        }
    }

    private fun applyRuntimeSettingsNow(
        reason: String,
        request: RuntimeSettingsApplyClaim,
    ) {
        if (!runtimeSettingsState.isLatest(request) || !ownsRuntime(request.ownership)) return
        val plan = runningPlan ?: readDesiredRunningPlan(this) ?: run {
            TcptunState.appendLog("runtime settings apply skipped: no running profile")
            return
        }
        val settings = (RuntimeSettingsRepository.read(this) as? RuntimeSettingsRead.Success)?.settings
            ?: run {
                TcptunState.appendLog("runtime settings unavailable; settings apply rejected")
                return
            }
        runtimeSettingsState.reconcile(
            claim = request,
            desired = AppliedRuntimeSettings.from(settings),
            applyLogLevel = { applyRuntimeLogLevel(request.ownership, it) },
            applyFlowAnalysis = { applyRuntimeFlowAnalysis(request.ownership, it) },
            checkpoint = { checkpointHotApplied(request.ownership, it) },
            markMutationUncertain = { markHotMutationUncertain(request.ownership) },
            onApplied = {
                RuntimeSettingsRepository.publishHotApplied(settings)
                bridgeHealthRuntime.wake()
            },
            onReplacementRequired = {
                requestRuntimeSettingsReplacement(reason, request, plan, it)
            },
        )
    }

    private fun requestRuntimeSettingsReplacement(
        reason: String,
        request: RuntimeSettingsApplyClaim,
        plan: ProfileRunPlan,
        hotFailure: RuntimeSettingsHotApplyResult.RestartRequired?,
    ) {
        hotFailure?.let {
            TcptunState.appendLog(
                "dynamic ${it.mutation.description} update unavailable; restarting VPN: " +
                    failureDescription(it.failure),
            )
        }
        val restartIntent = startIntent(this, plan)
        val restartClaim = synchronized(lifecycleCommandLock) {
            runtimeSettingsState.runIfLatestOwned(request, currentRuntimeOwnership()) {
                runtimeCoordinator.claimReplacement(serviceInstanceId) to
                    profileRepository.currentMutationRevision()
            }
        }
        if (restartClaim == null) return
        val (restartToken, profileMutationRevision) = restartClaim
        val restartReason = if (request.mutation.forceRestart) "route rules changed" else reason
        TcptunState.appendLog("restarting VPN to apply runtime settings")
        dispatchStartRequest(
            restartToken,
            VpnRuntimeStartRequest(
                command = VpnServiceIntents.parseStartCommand(this, restartIntent),
                expectedProfileMutationRevision = profileMutationRevision,
                restartReason = restartReason,
            ),
        )
    }

    private fun restoreConnectionsReadyAfterHealthySnapshot(
        ownership: VpnRuntimeOwnership,
    ): Boolean = synchronized(lifecycleCommandLock) {
        // Never trust values read before acquiring the lifecycle lock. Status
        // callbacks use this same lock, so this is the latest ordered state.
        val current = TcptunState.state.value
        if (!canRestoreConnectionsReady(
                runtimeStatus = current.status,
                bridgeStatus = current.diagnostics.bridgeStatus,
                bridgeEventState = current.diagnostics.bridgeEventState,
                localProxyReachable = current.diagnostics.localProxyReachable,
                sessionCurrent = ownsRuntime(ownership),
                hasTun = tun != null,
                hasRunningPlan = runningPlan != null && bridgeResources.activeConfigJson != null,
                stopping = stopping,
                bridgeRestarting = bridgeRestarting,
                explicitStopRequested = explicitStopRequested,
            )
        ) {
            return@synchronized false
        }
        val wasReady = current.connectionsReady
        val cancelledRecoverableRestart = cancelRecoverableBridgeRestartAfterHealthySnapshot()
        if (!wasReady && !cancelledRecoverableRestart) {
            // Busy can also mean an intentional network-handover restart or a
            // connection update; a healthy old snapshot must not cancel those.
            return@synchronized false
        }
        TcptunState.setConnectionsReady(true)
        if (!wasReady) {
            TcptunState.appendLog("healthy bridge snapshot restored connection actions")
        }
        true
    }

    private fun cancelRecoverableBridgeRestartAfterHealthySnapshot(): Boolean =
        synchronized(bridgeRestartScheduleLock) {
            if (!bridgeRecoveryCoordinator.cancelRestartAfterHealthySnapshot()) {
                return@synchronized false
            }
            bridgeRestartTask.cancel()
            true
        }

    private fun startVpnForeground(state: VpnForegroundState) = foregroundRuntime.start(state)

    private fun updateNotification(state: VpnForegroundState) = foregroundRuntime.update(state)

    private fun createNotificationChannel() = foregroundRuntime.createChannel()

    private fun debugLifecycleMarker(event: String) {
        if (BuildConfig.DEBUG) {
            Log.i(DEBUG_LIFECYCLE_TAG, "$event service=$serviceInstanceId")
        }
    }

    companion object {
        const val ACTION_START = VpnServiceIntents.ActionStart
        const val ACTION_STOP = VpnServiceIntents.ActionStop
        const val ACTION_UPDATE_OUTBOUNDS = VpnServiceIntents.ActionUpdateOutbounds
        const val ACTION_TCPING_OUTBOUNDS = VpnServiceIntents.ActionTcpingOutbounds
        const val ACTION_APPLY_RUNTIME_SETTINGS = VpnServiceIntents.ActionApplyRuntimeSettings
        const val ACTION_UPDATE_FLOW_ANALYSIS = VpnServiceIntents.ActionUpdateFlowAnalysis
        const val ACTION_REFRESH_CLIENT_IPS = VpnServiceIntents.ActionRefreshClientIps
        const val EXTRA_COMMAND_ID = VpnServiceIntents.ExtraCommandId
        const val EXTRA_COMMAND_VERSION = VpnServiceIntents.ExtraCommandVersion
        private const val EXTRA_TCPING_REQUEST_ID = VpnServiceIntents.ExtraTcpingRequestId
        private const val EXTRA_TCPING_TARGET_LABEL = VpnServiceIntents.ExtraTcpingTargetLabel
        private const val EXTRA_TCPING_HOST = VpnServiceIntents.ExtraTcpingHost
        private const val EXTRA_TCPING_PORT = VpnServiceIntents.ExtraTcpingPort
        private const val EXTRA_FORCE_RUNTIME_RESTART = VpnServiceIntents.ExtraForceRuntimeRestart
        const val LOCAL_SOCKS_HOST = RuntimeSettingsDefaults.LocalSocksHost
        const val DEFAULT_SOCKS_PORT = RuntimeSettingsDefaults.SocksPort
        const val DEFAULT_VPN_MTU = RuntimeSettingsDefaults.VpnMtu
        private const val VPN_DNS_ADDRESS = "10.77.0.1"
        private const val BRIDGE_RESTART_DELAY_MS = 300L
        private const val BRIDGE_RESTART_MIN_INTERVAL_MS = 30_000L
        private const val BRIDGE_READY_TIMEOUT_MS = 15_000L
        private const val BRIDGE_STOP_SETTLE_TIMEOUT_MS = 5_000L
        private const val OUTBOUND_STOP_TIMEOUT_MS = 15_000L
        private const val RUNTIME_SETTINGS_APPLY_DEBOUNCE_MS = 800L
        private const val DESTROY_EXECUTOR_WAIT_MS = 2_000L
        private const val DESTROY_ABORT_SETTLE_WAIT_MS = 5_000L
        private const val DEFERRED_DESTROY_WAIT_MS = 15_000L
        private const val DESTROY_MAX_LIFECYCLE_WAIT_MS = 35_000L
        private const val PREVIOUS_RUNTIME_RELEASE_WAIT_MS = 35_000L
        internal const val DEBUG_LIFECYCLE_TAG = "TcptunVpnLifecycle"
        private val serviceOwnerLock = Any()
        private val nextServiceInstanceId = AtomicLong()
        private val activeServiceInstanceId = AtomicLong()
        private val bridgeRuntimeLease = BridgeRuntimeLease()
        internal fun runtimeOwnershipDebugSnapshots(): List<RuntimeOwnershipDebugSnapshot> =
            RuntimeOwnershipDebugRegistry.snapshots()
        fun startIntent(context: Context, config: AppConfig): Intent =
            VpnServiceIntents.start(context, config)
        fun refreshClientIpsIntent(context: Context): Intent =
            VpnServiceIntents.refreshClientIps(context)
        fun startIntent(context: Context, sourcePlan: ProfileRunPlan): Intent =
            VpnServiceIntents.start(context, sourcePlan)
        /** Validate candidate route rules before they become authoritative storage. */
        internal fun preflightStartPayload(
            context: Context,
            sourcePlan: ProfileRunPlan,
            managedRouteRules: List<ManagedRouteRule>,
        ) {
            VpnServiceIntents.preflightStart(context, sourcePlan, managedRouteRules)
        }

        fun stopIntent(context: Context): Intent = VpnServiceIntents.stop(context)
        fun updateOutboundsIntent(context: Context, plan: ProfileRunPlan): Intent =
            VpnServiceIntents.updateOutbounds(context, plan)
        fun applyRuntimeSettingsIntent(context: Context, forceRestart: Boolean = false): Intent =
            VpnServiceIntents.applyRuntimeSettings(context, forceRestart)
        fun updateFlowAnalysisIntent(context: Context): Intent =
            VpnServiceIntents.updateFlowAnalysis(context)
        fun tcpingOutboundsIntent(
            context: Context,
            requestId: Long,
            targetLabel: String,
            host: String,
            port: Int,
        ): Intent {
            return VpnServiceIntents.tcpingOutbounds(context, requestId, targetLabel, host, port)
        }

        /** One event-driven health check (network change, core status, user refresh). */
        fun requestHealthCheck(reason: String) = VpnHealthCheckRequests.requestHealthCheck(reason)
        /**
         * Event-driven health check that also forces per-member balance probes
         * ([ProbeOutboundHealth]), bypassing the member-probe throttle so pool
         * selection can recover without a UI refresh.
         *
         * @param delayMs settle window before the probe may run. Rapid multi-start
         *   / membership changes are debounced to the latest schedule so only one
         *   probe runs after the last event.
         */
        fun requestMemberHealthProbe(
            reason: String,
            delayMs: Long = 0L,
        ) {
            VpnHealthCheckRequests.requestMemberProbe(reason, delayMs)
        }

        @Deprecated("Use requestHealthCheck", ReplaceWith("requestHealthCheck(reason)"))
        fun requestDenseHealthCheck(reason: String) = requestHealthCheck(reason)

        fun requestUiVisibleHealthCheck() {
            // Full UI-driven refresh: StatusJSON reconcile + local proxy + member
            // balance health + single aggregate upstream probe through the pool.
            // Immediate; do not extend the settle window so pull-to-refresh stays responsive.
            VpnHealthCheckRequests.requestUiVisibleHealthCheck()
        }

        fun readRuntimeSettings(context: Context): RuntimeSettings =
            RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        fun writeRuntimeSettings(context: Context, settings: RuntimeSettings) {
            RuntimeSettingsRepository.write(context, settings)
        }
        private fun encodeDesiredRunningPlan(plan: ProfileRunPlan): String =
            DesiredRunningPlanStore.encode(plan)

        private fun publishDesiredRunningPlan(context: Context, rawPlan: String) =
            DesiredRunningPlanStore.publish(context, rawPlan)

        private fun readDesiredRunningPlan(context: Context): ProfileRunPlan? =
            DesiredRunningPlanStore.read(context)

        private fun clearDesiredRunningConfig(context: Context) = DesiredRunningPlanStore.clear(context)

    }
}
