package com.tcptun.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TcptunVpnService : VpnService() {
    private data class RecoveryRollbackCleanup(
        val token: VpnRuntimeRecoveryToken,
        val request: VpnRuntimeRecoveryRequest,
        val failure: Throwable,
    )

    private data class BridgeRestartPreparation(
        val configJson: String,
        val plan: ProfileRunPlan,
        val settings: AppliedRuntimeSettings,
        val reason: String,
    )
    private val serviceInstanceId = nextServiceInstanceId.incrementAndGet()
    private val notificationController by lazy(LazyThreadSafetyMode.NONE) {
        VpnNotificationController(this)
    }
    private val profileRepository: ProfileRepository by lazy(LazyThreadSafetyMode.NONE) {
        applicationContext.profileRepository()
    }
    private val bridgeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ReflectionTcptunBridge() }
    private val bridge: TcptunBridge get() = bridgeDelegate.value
    private val bridgeLock = Any()
    private val bridgeResources = BridgeResourceStateMachine()
    private val bridgeSessionControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeSessionController(bridge, bridgeResources)
    }
    private val bridgeSessionController: BridgeSessionController
        get() = bridgeSessionControllerDelegate.value
    private val bridgeSessionStopControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeSessionStopController(bridge, bridgeResources)
    }
    private val bridgeSessionStopController: BridgeSessionStopController
        get() = bridgeSessionStopControllerDelegate.value
    private val tunOwner = ExclusiveResourceOwner<android.os.ParcelFileDescriptor>()
    private val teardownLock = Any()
    /** Shared with service ownership so old/new instances cannot publish across each other. */
    private val lifecycleCommandLock = serviceOwnerLock
    private val lifecycleExecutor = newLifecycleScheduledExecutor("TcptunLifecycle")
    private val tcpingExecutor = newBoundedLifecycleExecutor(
        threadName = "TcptunTcping",
        queueCapacity = MAX_TCPING_EXECUTOR_QUEUE_CAPACITY,
    )
    // ProbeOutboundHealth is serialized by bridgeLock, so a wider pool only
    // creates cancelled workers waiting on the same native engine.
    private val memberHealthExecutor = newBoundedLifecycleExecutor(
        threadName = "TcptunMemberHealth",
        queueCapacity = MAX_MEMBER_HEALTH_EXECUTOR_QUEUE_CAPACITY,
    )
    private val memberHealthBatchSelector = RoundRobinBatchSelector()
    private val memberHealthProbeScheduler = MemberHealthProbeScheduler(
        executor = lifecycleExecutor,
        canRun = { !destroyed.get() && !stopping && isActiveServiceOwner() },
        markProbeForced = VpnHealthCheckRequests::markMemberProbeForced,
        wakeMonitor = ::wakeBridgeMonitor,
        log = TcptunState::appendLog,
        maxDelayMs = VpnHealthCheckRequests.MaxMemberProbeDelayMs,
    )
    private val runtimeCoordinator = VpnRuntimeCoordinator(lifecycleExecutor) { !destroyed.get() }
    private val latestStartId = AtomicInteger()
    private val connectionUpdateTracker = ConnectionUpdateTracker()
    private val monitorGeneration = AtomicInteger()
    private val monitorWakeGeneration = AtomicInteger()
    private val bridgeRecoveryCoordinator = BridgeRecoveryCoordinator(
        minRestartIntervalMillis = BRIDGE_RESTART_MIN_INTERVAL_MS,
        recoveryDelayMillis = BridgeHealthPolicy::bridgeRecoveryDelayMs,
    )
    private val bridgeHealthMonitor = BridgeHealthMonitorLoop(
        failureLimit = HEALTH_FAILURE_LIMIT,
        nextCheckDelayMillis = { confirmingFailure ->
            BridgeHealthPolicy.nextCheckDelayMs(
                powerSaving = powerSavingMode,
                confirmingFailure = confirmingFailure,
            )
        },
    )
    private val destroyed = AtomicBoolean()
    private val initialized = AtomicBoolean()
    private val bridgeReadyWaiter = AtomicReference<BridgeReadyWaiter?>(null)
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
    @Volatile private var monitorThread: Thread? = null
    private val monitorWaitLock = Object()
    private val monitorWakeCallback: () -> Unit = ::wakeBridgeMonitor
    private val memberHealthProbeCallback: (String, Long) -> Unit = ::scheduleMemberHealthProbe
    private val connectivityDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("ConnectivityManager is unavailable")
    }
    private val connectivity: ConnectivityManager get() = connectivityDelegate.value
    private val appIdentityProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppIdentityProvider(applicationContext, connectivity)
    }
    private val appIdentityProvider: AndroidAppIdentityProvider get() = appIdentityProviderDelegate.value
    private val underlyingNetworkCoordinatorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UnderlyingNetworkCoordinator(
            connectivity = { connectivity },
            canHandleCallback = {
                synchronized(lifecycleCommandLock) {
                    !destroyed.get() && !stopping && isActiveServiceOwner()
                }
            },
            onSelectionChanged = ::applyUnderlyingNetwork,
            log = TcptunState::appendLog,
        )
    }
    private val underlyingNetworkCoordinator: UnderlyingNetworkCoordinator
        get() = underlyingNetworkCoordinatorDelegate.value
    private val underlyingNetworkUpdateGate = UnderlyingNetworkUpdateGate<Network>()
    private val appliedRuntimeState = AppliedRuntimeStateSlot()
    private val appliedSettings: AppliedRuntimeSettings get() = appliedRuntimeState.current?.settings ?: AppliedRuntimeSettings()
    private val tunMtu: Int get() = appliedSettings.mtu
    private val activeSocksPort: Int get() = appliedSettings.socksPort
    private val activeSocksUsername: String get() = appliedSettings.socksUsername
    private val activeSocksPassword: String get() = appliedSettings.socksPassword
    private val powerSavingMode: Boolean get() = appliedSettings.powerSavingMode
    private val localProxyHealthProbe = LocalProxyHealthProbe()
    @Volatile private var lastMemberHealthProbeAtElapsedMs = 0L
    private val runtimeSettingsDesiredGate = RuntimeSettingsDesiredGate()
    private val runtimeSettingsApplyTask = LatestTaskSlot()
    private val bridgeRestartTask = LatestTaskSlot()
    private val bridgeRestartContinuationTask = LatestTaskSlot()
    private val bridgeRestartScheduleLock = Any()
    private val bridgeRecoveryTask = LatestTaskSlot()
    private val bridgeTeardownRetryTask = LatestTaskSlot()
    private val bridgeTeardownRetryCoordinator = BridgeTeardownRetryCoordinator()
    private val deferredServiceStopGate = DeferredServiceStopGate()

    override fun onCreate() {
        super.onCreate()
        synchronized(serviceOwnerLock) {
            activeServiceInstanceId.set(serviceInstanceId)
        }
        try {
            VpnHealthCheckRequests.install(monitorWakeCallback, memberHealthProbeCallback)
            createNotificationChannel()
            initialized.set(true)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            VpnHealthCheckRequests.uninstall(monitorWakeCallback, memberHealthProbeCallback)
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

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val command = VpnServiceCommand.fromAction(action)
        val admittedToken = synchronized(lifecycleCommandLock) {
            latestStartId.updateAndGet { current -> maxOf(current, startId) }
            val replacesRuntime = command.policyKind == ServiceCommandKind.StartOrRestore ||
                (command.policyKind == ServiceCommandKind.UpdateConnections && !explicitStopRequested)
            if (replacesRuntime) {
                // Linearize replacement work before foreground publication or
                // any other blocking operation so stale cleanup cannot stop it.
                if (command.policyKind == ServiceCommandKind.StartOrRestore) {
                    runtimeCoordinator.claimStart(serviceInstanceId, persistent = true).also {
                        appliedRuntimeState.clear()
                    }
                } else {
                    runtimeCoordinator.claimAuxiliaryCommand(serviceInstanceId, persistent = true).also { token ->
                        appliedRuntimeState.current?.settings?.let { settings ->
                            val ownership = VpnRuntimeOwnership(token, bridgeResources.activeEpoch)
                            appliedRuntimeState.publish(AppliedRuntimeState(ownership, settings), ownership)
                        }
                    }
                }
            } else null
        }
        val foregroundStart = command.requiresForegroundStart
        if (foregroundStart) {
            // startForegroundService() creates a per-start deadline. Android can
            // deliver a rapid restart to an instance whose teardown has already
            // revoked command ownership; acknowledge the foreground start before
            // consulting lifecycle ownership so that rejecting the stale command
            // cannot crash the process with ForegroundServiceDidNotStartInTime.
            try {
                startVpnForeground(getString(R.string.vpn_notification_starting))
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
                teardownRetryPending = bridgeTeardownRetryCoordinator.pending,
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
                            state = "Starting",
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
                    if (!publishForegroundIfOwner("Running")) {
                        stopSelfWhenBridgeReleased(startId, "rejected connection update")
                        return START_NOT_STICKY
                    }
                    requestOutboundUpdate(
                        requireNotNull(intent) { "update command is missing its intent" },
                        requireNotNull(admittedToken) { "update command was not admitted" },
                    )
                }
                VpnServiceCommand.Tcping -> requestOutboundTcping(
                    requireNotNull(intent) { "TCPing command is missing its intent" },
                )
                VpnServiceCommand.ApplyRuntimeSettings -> requestRuntimeSettingsApply(
                    reason = if (intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false) == true) {
                        "route rules changed"
                    } else {
                        "runtime settings changed"
                    },
                    forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false) == true,
                )
                VpnServiceCommand.UpdateFlowAnalysis -> requestFlowAnalysisUpdate()
                VpnServiceCommand.RefreshClientIps -> requestBridgeClientIpsRefresh()
                VpnServiceCommand.Restore,
                VpnServiceCommand.Unknown,
                -> {
                    if (!publishForegroundIfOwner(
                            state = "Starting",
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
            val message = "VPN service command ${action ?: "restore"} failed: ${failureDescription(error)}"
            if (command.requiresForegroundStart) {
                val (token, accepted) = synchronized(lifecycleCommandLock) {
                    if (destroyed.get() || !isActiveServiceOwner()) {
                        return START_NOT_STICKY
                    }
                    TcptunState.error(message)
                    val token = runtimeCoordinator.claimStop(serviceInstanceId, "failed command cleanup")
                    appliedRuntimeState.clear()
                    runtimeSettingsDesiredGate.clear()
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
                                coordinatorStopToken = commandToken,
                            )
                        },
                    )
                }
                if (!accepted) {
                    runIfLifecycleCommandOwner(token.lifecycleGeneration) {
                        stopSelfWhenBridgeReleased(reason = "failed command cleanup rejection")
                    }
                }
            } else {
                runIfActiveServiceOwner {
                    TcptunState.appendLog(message)
                    if (tun == null) {
                        stopSelfWhenBridgeReleased(reason = "failed auxiliary command")
                    }
                }
            }
            return START_NOT_STICKY
        }
        return if (command == VpnServiceCommand.Stop) START_NOT_STICKY else START_STICKY
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
                        startVpnForeground(getString(R.string.vpn_notification_cleanup_pending))
                    }
                }
                return
            }
            effectiveStartId?.let(::stopSelf) ?: stopSelf()
        }
    }

    private fun publishForegroundIfOwner(
        state: String,
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

    private fun executeServiceTask(
        executor: Executor,
        taskName: String,
        onFailure: (Throwable) -> Unit,
        task: () -> Unit,
    ): Boolean {
        if (destroyed.get()) return false
        return executeCrashGuarded(executor, taskName, onFailure) {
            if (!destroyed.get()) task()
        }
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
                val desiredSequenceAtPreparation = runtimeSettingsDesiredGate.latestSequence
                val runtimeAppliedSettings = AppliedRuntimeSettings.from(runtimeSettings)
                val desiredPlanJson = startCommand.desiredPlanJson
                val startingPublished = synchronized(lifecycleCommandLock) {
                    if (!commandOwner()) {
                        false
                    } else {
                        TcptunState.setStatus(VpnStatus.Starting)
                        startVpnForeground(getString(R.string.vpn_notification_starting))
                        TcptunState.updateDiagnostics {
                            it.copy(
                                bridgeStatus = "Starting",
                                localProxyReachable = false,
                                mtu = runtimeSettings.mtu,
                                powerSavingMode = runtimeSettings.powerSavingMode,
                                localProxyAddress = localSocksConnectAddr(runtimeSettings),
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
                registerUnderlyingNetworkCallback()
                underlyingNetworkCoordinator.republishCurrent("VPN runtime started")
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
                    startBridgeMonitor()
                    updateNotification(runningNotificationState(plan))
                    // Wait for routing/tunnels to settle before the first
                    // member probe; immediate probes after multi-start often
                    // report "no route to host" and falsely degrade every pool member.
                    requestMemberHealthProbe(
                        reason = "vpn started",
                        delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
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
        startRollbackToken = token,
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
            startRollbackToken = null,
            recoveryRollback = RecoveryRollbackCleanup(
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
            recoveryRollback = RecoveryRollbackCleanup(token, request, error),
        )
    }

    private fun rollbackStartCleanup(
        request: VpnRuntimeStartRequest,
        error: Throwable,
        superseded: Boolean,
        commandOwner: () -> Boolean,
        startRollbackToken: VpnRuntimeCommandToken?,
        recoveryRollback: RecoveryRollbackCleanup? = null,
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
            startRollbackToken = startRollbackToken,
            recoveryRollback = recoveryRollback,
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
                    appliedRuntimeState.clear()
                    runtimeSettingsDesiredGate.clear()
                }
            } else {
                runtimeCoordinator.claimReplacement(serviceInstanceId).also {
                    appliedRuntimeState.clear()
                }
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
                        coordinatorStopToken = commandToken,
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
        startRollbackToken: VpnRuntimeCommandToken? = null,
        recoveryRollback: RecoveryRollbackCleanup? = null,
    ): VpnPlatformStopResult =
        stopVpn(
            setStopped = false,
            clearSavedConfig = false,
            stopSelfService = false,
            globalStateOwner = { false },
            coordinatorStartRollbackToken = startRollbackToken,
            coordinatorRecoveryRollback = recoveryRollback,
        )

    private fun requestOutboundUpdate(intent: Intent, token: VpnRuntimeCommandToken) {
        val nextPlan = intent.getStringExtra(EXTRA_PROFILE_PLAN)
            ?.takeIf { it.length <= DesiredRunningPlanStore.MaxEncodedLength }
            ?.let { raw ->
                runRecoverableCatching {
                    requireSafeJsonNesting(raw)
                    ProfileRunPlan.fromJson(JSONObject(raw))
                }.getOrNull()
            }
            ?: run {
                TcptunState.appendLog("connection update ignored: invalid profile plan")
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
                        requestMemberHealthProbe(
                            reason = "active connections changed",
                            delayMs = BridgeHealthPolicy.MEMBER_HEALTH_MEMBERSHIP_DELAY_MS,
                        )
                        updateNotification(runningNotificationState(plan))
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
                appliedRuntimeState.clear()
                if (failure == null) {
                    TcptunState.appendLog("reloading VPN connection configuration")
                } else {
                    cancelPendingBridgeRestart()
                    TcptunState.setConnectionsReady(false)
                    TcptunState.appendLog("rebuilding VPN after incomplete connection rollback")
                }
                val replacementIntent = if (failure == null) intent else startIntent(this, plan)
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
                    startBridgeMonitor()
                    appliedRuntimeState.current?.ownership?.let { reconcileDesiredSettingsAfterRunning(it) }
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

    private fun requestOutboundTcping(intent: Intent) {
        val requestId = intent.getLongExtra(EXTRA_TCPING_REQUEST_ID, 0)
        val targetLabel = intent.getStringExtra(EXTRA_TCPING_TARGET_LABEL).orEmpty()
        val host = intent.getStringExtra(EXTRA_TCPING_HOST).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_TCPING_PORT, 0)
        if (requestId <= 0 || targetLabel.isBlank() || host.isBlank() || port !in 1..65535) {
            if (requestId > 0) TcptunState.failTcping(requestId, "invalid TCPing request")
            return
        }
        executeServiceTask(
            executor = tcpingExecutor,
            taskName = "TCPing",
            onFailure = { error -> TcptunState.failTcping(requestId, failureDescription(error)) },
        ) tcping@{
            val profiles = runningPlan?.activeProfiles.orEmpty()
            val sessionEpoch = bridgeResources.activeEpoch
            if (
                tun == null ||
                stopping ||
                sessionEpoch <= 0L ||
                profiles.isEmpty() ||
                TcptunState.status != VpnStatus.Running ||
                !TcptunState.state.value.connectionsReady
            ) {
                TcptunState.failTcping(requestId, "connections are still starting")
                return@tcping
            }
            val results = mutableListOf<TcpingLinkResult>()
            val batchDeadlineMs = SystemClock.elapsedRealtime() + TCPING_OUTBOUND_BATCH_TIMEOUT_MS
            profiles.forEachIndexed { index, profile ->
                if (!TcptunState.isCurrentTcping(requestId)) return@tcping
                TcptunState.beginTcpingStep(requestId, index + 1, profiles.size, profile.name)
                val remainingBatchMs = batchDeadlineMs - SystemClock.elapsedRealtime()
                val probe = if (remainingBatchMs <= 0L) {
                    Result.failure(IllegalStateException("overall TCPing deadline elapsed"))
                } else try {
                    runRecoverableCatching {
                        probeOutboundWithTransientQuicRetry(
                            totalTimeoutMillis = minOf(TCPING_OUTBOUND_TOTAL_TIMEOUT_MS, remainingBatchMs),
                            attemptTimeoutMillis = TCPING_OUTBOUND_TIMEOUT_MS,
                            isActive = { TcptunState.isCurrentTcping(requestId) },
                        ) { timeoutMillis ->
                            synchronized(bridgeLock) {
                                if (stopping || tun == null || sessionEpoch != bridgeResources.activeEpoch) {
                                    throw CancellationException("VPN session changed")
                                }
                                bridge.probeOutbound(
                                    tag = profile.runtimeOutboundTag(),
                                    host = host,
                                    port = port,
                                    timeoutMillis = timeoutMillis,
                                ).also {
                                    if (stopping || sessionEpoch != bridgeResources.activeEpoch) {
                                        throw CancellationException("VPN session changed")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    TcptunState.failTcping(requestId, "VPN session changed")
                    return@tcping
                }
                val result = probe.fold(
                    onSuccess = { elapsedMs -> TcpingLinkResult(profile.name, elapsedMs = elapsedMs) },
                    onFailure = { err ->
                        TcpingLinkResult(
                            profileName = profile.name,
                            error = err.message ?: err.javaClass.simpleName,
                        )
                    },
                )
                results += result
                TcptunState.completeTcpingStep(requestId, result)
                val detail = result.elapsedMs?.let { "${it}ms" } ?: "failed: ${result.error}"
                TcptunState.appendLog("TCPing $targetLabel via ${profile.name}: $detail")
            }
            TcptunState.finishTcping(requestId)
            if (results.any { it.elapsedMs == null }) {
                requestMemberHealthProbe(
                    "TCPing failed on ${results.count { it.elapsedMs == null }} connection(s)",
                )
            }
        }
    }

    private fun runningNotificationState(plan: ProfileRunPlan): String {
        return resources.getQuantityString(
            R.plurals.vpn_notification_running,
            plan.activeProfiles.size,
            plan.activeProfiles.size,
        )
    }

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

    private fun registerUnderlyingNetworkCallback() {
        underlyingNetworkCoordinator.register()
    }

    private fun unregisterUnderlyingNetworkCallback(updateGlobalDiagnostics: Boolean = true) {
        if (!underlyingNetworkCoordinatorDelegate.isInitialized()) return
        val unregistered = underlyingNetworkCoordinator.unregister()
        if (unregistered && updateGlobalDiagnostics) updateUnderlyingDiagnostics(null)
    }

    private fun applyUnderlyingNetwork(
        network: Network?,
        selectionClaim: RankedSelectionClaim<Network>,
        reason: String,
    ) {
        val update = synchronized(lifecycleCommandLock) {
            val epoch = bridgeResources.activeEpoch
            if (destroyed.get() || stopping || !isActiveServiceOwner() || epoch <= 0L) return
            underlyingNetworkUpdateGate.request(
                network = network,
                selection = selectionClaim,
                reason = reason,
                ownership = VpnRuntimeOwnership(
                    runtimeToken = runtimeCoordinator.currentToken(serviceInstanceId),
                    bridgeEpoch = epoch,
                ),
            )
        }
        executeLifecycleTask(VpnRuntimeCommand.UpdateUnderlyingNetwork(update.ownership)) {
            applyUnderlyingNetworkUpdate(update)
        }
    }

    private fun applyUnderlyingNetworkUpdate(update: UnderlyingNetworkUpdate<Network>) {
        synchronized(lifecycleCommandLock) {
            if (!underlyingNetworkUpdateGate.isLatest(update) || !ownsRuntime(update.ownership)) return
            updateUnderlyingDiagnostics(update.network)
            TcptunState.appendLog("underlying network selected: ${update.network ?: "none"}")
            try {
                setUnderlyingNetworks(update.network?.let { arrayOf(it) })
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog("set underlying network failed: ${failureDescription(error)}")
            }
            if (!underlyingNetworkUpdateGate.isLatest(update) || !ownsRuntime(update.ownership)) return
            if (BridgeHealthPolicy.shouldRestartForNetworkHandover(
                    initialSelection = update.selection.initial,
                    networkAvailable = update.network != null,
                    vpnRunning = TcptunState.status == VpnStatus.Running,
                    previousNetworkAvailable = update.selection.previousValue != null,
                )
            ) {
                requestBridgeRestart(
                    reason = update.reason,
                    settleDelayMs = BridgeHealthPolicy.NETWORK_HANDOVER_SETTLE_MS,
                    ownership = update.ownership,
                )
            } else {
                requestMemberHealthProbe(
                    reason = update.reason,
                    delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
                )
            }
        }
    }

    private fun ownsRuntime(ownership: VpnRuntimeOwnership): Boolean {
        return !destroyed.get() && !stopping && tun != null && ownership.isCurrent(
            runtimeTokenCurrent = runtimeCoordinator.isCurrent(ownership.runtimeToken),
            activeBridgeEpoch = bridgeResources.activeEpoch,
            activeServiceInstance = isActiveServiceOwner(),
        )
    }

    private fun currentRuntimeOwnership(): VpnRuntimeOwnership? =
        synchronized(lifecycleCommandLock) {
            val epoch = bridgeResources.activeEpoch
            if (
                destroyed.get() || stopping || tun == null || !isActiveServiceOwner() || epoch <= 0L
            ) null else VpnRuntimeOwnership(runtimeCoordinator.currentToken(serviceInstanceId), epoch)
        }

    private fun publishAppliedRuntimeState(
        token: VpnRuntimeCommandToken,
        settings: AppliedRuntimeSettings,
    ): VpnRuntimeOwnership? = synchronized(lifecycleCommandLock) {
        val active = currentRuntimeOwnership() ?: return@synchronized null
        val candidate = AppliedRuntimeState(VpnRuntimeOwnership(token, active.bridgeEpoch), settings)
        candidate.ownership.takeIf { appliedRuntimeState.publish(candidate, active) }
    }

    private fun publishAppliedRuntimeState(
        ownership: VpnRuntimeOwnership,
        settings: AppliedRuntimeSettings,
    ): Boolean = synchronized(lifecycleCommandLock) {
        appliedRuntimeState.publish(
            AppliedRuntimeState(ownership, settings),
            currentRuntimeOwnership(),
        )
    }

    private fun reconcileDesiredSettingsAfterRunning(
        ownership: VpnRuntimeOwnership,
        freshRuntimeDesiredSequence: Long? = null,
    ) {
        val pending = runtimeSettingsDesiredGate.pending ?: return
        if (!ownsRuntime(ownership)) return
        val desired = AppliedRuntimeSettings.from(readRuntimeSettings(this))
        val applied = appliedRuntimeState.current?.takeIf { it.ownership == ownership } ?: return
        if (desiredRuntimeSettingsAction(
                applied.settings,
                desired,
                pending.forceRestart,
                freshRuntimeDesiredSequence == pending.sequence,
            ) == RuntimeSettingsReconciliationAction.Satisfied
        ) {
            runtimeSettingsDesiredGate.acknowledge(pending.sequence)
            return
        }
        runtimeSettingsDesiredGate.bindLatest(ownership)?.let {
            scheduleRuntimeSettingsApply("pending runtime settings reconciliation", it)
        }
    }

    private fun requestStopVpn() {
        cleanupStep("clear TCPing") { TcptunState.clearTcping() }
        val (command, accepted) = synchronized(lifecycleCommandLock) {
            val token = runtimeCoordinator.claimStop(serviceInstanceId, "explicit VPN stop")
            appliedRuntimeState.clear()
            runtimeSettingsDesiredGate.clear()
            runtimeSettingsApplyTask.cancel()
            // Claiming the generation and persisting desired=false share the
            // same lock as successful-start publication, so an older start
            // cannot write desired=true after this stop request.
            cleanupStep("persist requested stopped state") { clearDesiredRunningConfig(this) }
            val profileMutationRevision = profileRepository.currentMutationRevision()
            cleanupStep("set stopping state") { TcptunState.setStatus(VpnStatus.Stopping) }
            cleanupStep("disable connections") { TcptunState.setConnectionsReady(false) }
            cancelPendingBridgeRestart()
            bridgeReadyWaiter.getAndSet(null)?.future?.completeExceptionally(
                IllegalStateException("tcptun stop requested"),
            )
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
                        coordinatorStopToken = commandToken,
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
                    val notificationState = runningPlan?.let(::runningNotificationState)
                        ?: if (bridgeRecoveryCoordinator.recoveryPending) {
                            getString(R.string.vpn_notification_reconnecting)
                        } else {
                            getString(R.string.vpn_notification_running_generic)
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
        coordinatorStopToken: VpnRuntimeCommandToken? = null,
        coordinatorStartRollbackToken: VpnRuntimeCommandToken? = null,
        coordinatorRecoveryRollback: RecoveryRollbackCleanup? = null,
    ): VpnPlatformStopResult {
        check(
            listOfNotNull(
                coordinatorStopToken,
                coordinatorStartRollbackToken,
                coordinatorRecoveryRollback,
            ).size <= 1,
        ) {
            "platform cleanup cannot complete multiple coordinator owners"
        }
        return synchronized(teardownLock) {
            fun cleanupGlobalStep(label: String, action: () -> Unit) {
                if (globalStateOwner == null) {
                    cleanupStep(label, action)
                } else {
                    synchronized(serviceOwnerLock) {
                        if (globalStateCommitLock == null) {
                            if (globalStateOwner()) cleanupStep(label, action)
                        } else {
                            synchronized(globalStateCommitLock) {
                                if (globalStateOwner()) cleanupStep(label, action)
                            }
                        }
                    }
                }
            }

            fun runGlobalStep(action: () -> Unit) {
                if (globalStateOwner == null) {
                    action()
                } else {
                    synchronized(serviceOwnerLock) {
                        if (globalStateCommitLock == null) {
                            if (globalStateOwner()) action()
                        } else {
                            synchronized(globalStateCommitLock) {
                                if (globalStateOwner()) action()
                            }
                        }
                    }
                }
            }

            var bridgeStopFailure: Throwable? = null
            cancelPendingBridgeRestart()
            if (setStopped) {
                cleanupGlobalStep("set stopping state") { TcptunState.setStatus(VpnStatus.Stopping) }
            }
            cleanupStep("stop bridge monitor") { stopBridgeMonitor() }
            cleanupStep("unregister network callback") {
                unregisterUnderlyingNetworkCallback(updateGlobalDiagnostics = false)
            }
            cleanupGlobalStep("reset underlying network diagnostics") {
                updateUnderlyingDiagnostics(null)
            }
            if (clearSavedConfig) {
                cleanupGlobalStep("clear saved VPN config") { clearDesiredRunningConfig(this) }
            }
            cleanupStep("log bridge stop") { TcptunState.appendLog("stopping tcptun bridge") }
            // Engine.Stop closes the Go-owned duplicate and waits for the TUN
            // inbound to finish. Only then may VpnService close its original.
            try {
                stopBridge()
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                bridgeStopFailure = error
                cleanupStep("report tcptun bridge stop failure") { throw error }
            }
            cleanupStep("close VPN TUN") { closeTunAfterBridgeStopAttempt() }
            if (appIdentityProviderDelegate.isInitialized()) {
                cleanupStep("clear app identity cache") { appIdentityProvider.clear() }
            }
            appliedRuntimeState.clear()
            lastMemberHealthProbeAtElapsedMs = 0L
            memberHealthBatchSelector.clear()
            memberHealthProbeScheduler.reset()
            val disposition = bridgeTeardownDisposition(bridgeResources.hasOwnedResources)
            if (disposition.resourcesReleased) {
                bridgeTeardownRetryCoordinator.reset()
                bridgeTeardownRetryTask.cancel()
                cleanupGlobalStep("reset VPN diagnostics") {
                    TcptunState.updateDiagnostics {
                        it.copy(
                            bridgeStatus = "Stopped",
                            bridgeActiveConnections = 0,
                            bridgeClientIps = emptyList(),
                            bridgeMuxSources = 0,
                            bridgeMuxSessions = 0,
                            bridgeMuxStreams = 0,
                            localProxyReachable = false,
                            localProxyAddress = defaultLocalSocksConnectAddr(),
                            localProxyPort = DEFAULT_SOCKS_PORT,
                            healthCheckEventDriven = true,
                            healthCheckIntervalSeconds = 0,
                            socketProtectEnabled = false,
                        )
                    }
                }
                if (setStopped && disposition.mayPublishStopped) {
                    cleanupGlobalStep("set stopped state") { TcptunState.setStatus(VpnStatus.Stopped) }
                }
                if (disposition.mayRemoveForeground) {
                    cleanupGlobalStep("remove VPN foreground notification") {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
                if (stopSelfService && disposition.mayStopService) {
                    cleanupGlobalStep("stop VPN service") {
                        synchronized(lifecycleCommandLock) {
                            latestStartId.get().takeIf { it > 0 }?.let(::stopSelf) ?: stopSelf()
                        }
                    }
                }
                cleanupStep("honor deferred VPN service stop") {
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
            } else {
                val stopDescription = bridgeStopFailure?.let(::failureDescription)
                    ?: "native bridge resources are still owned"
                cleanupGlobalStep("publish incomplete VPN teardown") {
                    TcptunState.error("VPN cleanup is incomplete: $stopDescription")
                }
                if (!destroyed.get()) {
                    cleanupGlobalStep("retain VPN cleanup foreground") {
                        startVpnForeground(getString(R.string.vpn_notification_error_retrying_cleanup))
                    }
                    scheduleBridgeTeardownRetry(
                        setStopped = setStopped,
                        clearSavedConfig = clearSavedConfig,
                        stopSelfService = stopSelfService,
                        globalStateOwner = globalStateOwner,
                        globalStateCommitLock = globalStateCommitLock,
                        coordinatorStopToken = coordinatorStopToken,
                        coordinatorStartRollbackToken = coordinatorStartRollbackToken,
                        coordinatorRecoveryRollback = coordinatorRecoveryRollback,
                    )
                }
            }
            if (propagateBridgeStopFailure) {
                bridgeStopFailure?.let { error ->
                    throw IllegalStateException("VPN reload aborted because the bridge did not stop cleanly", error)
                }
            }
            if (disposition.resourcesReleased) {
                VpnPlatformStopResult.Released
            } else {
                VpnPlatformStopResult.RetainedForRetry
            }
        }
    }

    private fun scheduleBridgeTeardownRetry(
        setStopped: Boolean,
        clearSavedConfig: Boolean,
        stopSelfService: Boolean,
        globalStateOwner: (() -> Boolean)?,
        globalStateCommitLock: Any?,
        coordinatorStopToken: VpnRuntimeCommandToken?,
        coordinatorStartRollbackToken: VpnRuntimeCommandToken?,
        coordinatorRecoveryRollback: RecoveryRollbackCleanup?,
    ) {
        if (completeReleasedBeforeRetry(bridgeResources.hasOwnedResources) {
                completeOwnedPlatformCleanup(
                    result = VpnPlatformStopResult.Released,
                    coordinatorStopToken = coordinatorStopToken,
                    coordinatorStartRollbackToken = coordinatorStartRollbackToken,
                    coordinatorRecoveryRollback = coordinatorRecoveryRollback,
                )
            }
        ) return
        if (destroyed.get()) return
        val retry = bridgeTeardownRetryCoordinator.next()
        if (!retry.shouldSchedule) {
            TcptunState.appendLog(
                "tcptun cleanup remains incomplete after ${retry.completedAttempts} retries; " +
                    "service retained for safe process teardown",
            )
            return
        }
        val future = scheduleCrashGuardedFuture(
            executor = lifecycleExecutor,
            delay = checkNotNull(retry.delayMillis),
            unit = TimeUnit.MILLISECONDS,
            taskName = "tcptun teardown retry",
            onFailure = { error ->
                if (!destroyed.get()) {
                    TcptunState.appendLog("tcptun teardown retry failed: ${failureDescription(error)}")
                }
            },
        ) {
            if (destroyed.get()) return@scheduleCrashGuardedFuture
            if (!bridgeResources.hasOwnedResources) {
                completeOwnedPlatformCleanup(
                    VpnPlatformStopResult.Released,
                    coordinatorStopToken,
                    coordinatorStartRollbackToken,
                    coordinatorRecoveryRollback,
                )
                return@scheduleCrashGuardedFuture
            }
            TcptunState.appendLog(
                "retrying tcptun cleanup (${retry.attempt}/${retry.maxAttempts})",
            )
            executeLifecycleTask(VpnRuntimeCommand.Internal("tcptun teardown retry")) {
                val result = stopVpn(
                    setStopped = setStopped,
                    clearSavedConfig = clearSavedConfig,
                    stopSelfService = stopSelfService,
                    globalStateOwner = globalStateOwner,
                    globalStateCommitLock = globalStateCommitLock,
                    coordinatorStopToken = coordinatorStopToken,
                    coordinatorStartRollbackToken = coordinatorStartRollbackToken,
                    coordinatorRecoveryRollback = coordinatorRecoveryRollback,
                )
                completeOwnedPlatformCleanup(
                    result,
                    coordinatorStopToken,
                    coordinatorStartRollbackToken,
                    coordinatorRecoveryRollback,
                )
            }
        }
        if (future == null) {
            TcptunState.appendLog("tcptun teardown retry could not be scheduled; service retained")
        } else {
            bridgeTeardownRetryTask.replace(future)
        }
    }

    /** Completes only the coordinator owner which retained this platform cleanup. */
    private fun completeOwnedPlatformCleanup(
        result: VpnPlatformStopResult,
        coordinatorStopToken: VpnRuntimeCommandToken?,
        coordinatorStartRollbackToken: VpnRuntimeCommandToken?,
        coordinatorRecoveryRollback: RecoveryRollbackCleanup?,
    ) {
        check(
            listOf(
                coordinatorStopToken != null,
                coordinatorStartRollbackToken != null,
                coordinatorRecoveryRollback != null,
            ).count { it } <= 1,
        ) { "platform cleanup must have at most one coordinator owner" }
        coordinatorStopToken?.let { runtimeCoordinator.completePlatformStop(it, result) }
        coordinatorStartRollbackToken?.let {
            runtimeCoordinator.completeStartRollbackCleanup(it, result)
        }
        coordinatorRecoveryRollback?.let { cleanup ->
            runtimeCoordinator.completeRecoveryRollbackCleanup(cleanup.token, result)?.let { retryToken ->
                scheduleBridgeRecovery(retryToken, cleanup.request, cleanup.failure)
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
        if (!destroyed.compareAndSet(false, true)) {
            super.onDestroy()
            return
        }
        try {
            initialized.set(false)
            VpnHealthCheckRequests.uninstall(monitorWakeCallback, memberHealthProbeCallback)
            synchronized(lifecycleCommandLock) {
                runtimeCoordinator.destroy(serviceInstanceId)
                appliedRuntimeState.clear()
                runtimeSettingsDesiredGate.clear()
                if (isActiveServiceOwner()) TcptunState.clearTcping()
            }
            cancelPendingBridgeRestart()
            bridgeTeardownRetryTask.cancel()
            deferredServiceStopGate.clear()
            bridgeReadyWaiter.getAndSet(null)?.future?.completeExceptionally(
                IllegalStateException("tcptun service destroyed"),
            )
            bridgeRecoveryTask.cancel()
            runtimeSettingsApplyTask.cancel()
            memberHealthProbeScheduler.cancel()
            lifecycleExecutor.shutdownNow()
            tcpingExecutor.shutdownNow()
            memberHealthExecutor.shutdownNow()
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
                return@coordinator
            }

            stopVpn(
                setStopped = TcptunState.status != VpnStatus.Error,
                clearSavedConfig = false,
                stopSelfService = false,
                globalStateOwner = ::isActiveServiceOwner,
            )
            if (!bridgeResources.hasOwnedResources && closeBridgeEngine()) {
                TcptunState.appendLog("tcptun destroy cleanup completed")
            } else {
                TcptunState.appendLog(
                    "tcptun destroy cleanup incomplete; native resources retained for safe process teardown",
                )
            }
        }
        if (coordinator == null) {
            cleanupStep("start VPN destroy coordinator") {
                throw IllegalStateException("destroy coordinator thread could not be started")
            }
        }
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
        startBridgeSession(
            configJson = configJson,
            disabledOutboundTags = initiallyDisabledOutboundTags(plan),
            readyTimeoutMs = BRIDGE_READY_TIMEOUT_MS,
            vpnTun = vpnTun,
            mtu = mtu,
            settings = settings,
            commandOwner = commandOwner,
        )
    }

    private fun initiallyDisabledOutboundTags(plan: ProfileRunPlan): List<String> {
        return plan.profiles
            .filterNot { it.id in plan.activeIds }
            .map(AppConfig::runtimeOutboundTag)
    }

    private fun startBridgeSession(
        configJson: String,
        disabledOutboundTags: List<String>,
        readyTimeoutMs: Long,
        vpnTun: android.os.ParcelFileDescriptor,
        mtu: Int,
        settings: AppliedRuntimeSettings,
        commandOwner: () -> Boolean,
    ) {
        check(bridgeRuntimeLease.owner == serviceInstanceId) {
            "tcptun service does not own the native runtime lease"
        }
        val epoch = synchronized(lifecycleCommandLock) {
            check(commandOwner()) { "tcptun start was superseded" }
            TcptunState.beginBridgeSession().also(bridgeResources::beginPreparation)
        }
        val waiter = BridgeReadyWaiter(epoch)
        bridgeReadyWaiter.getAndSet(waiter)?.future?.completeExceptionally(
            IllegalStateException("superseded by a newer tcptun start"),
        )
        try {
            val sessionId = synchronized(bridgeLock) {
                bridgeSessionController.start(
                    request = BridgeSessionStartRequest(
                        configJson = configJson,
                        disabledOutboundTags = disabledOutboundTags,
                        tunFd = vpnTun.fd,
                        mtu = mtu,
                        logLevel = settings.logLevel,
                    ),
                    callbacks = BridgeSessionCallbacks(
                        onLog = { line ->
                            if (!destroyed.get()) TcptunState.appendLog(line)
                        },
                        onStatus = { eventJson ->
                            if (!destroyed.get()) onBridgeStatusEvent(epoch, eventJson)
                        },
                        protectSocket = { fd -> !destroyed.get() && !stopping && protect(fd) },
                        configureFlowAnalysis = {
                            configureFlowAnalysis(settings.flowAnalysisApp, epoch, configJson)
                        },
                        onInitialStatus = { statusJson ->
                            TcptunState.applyBridgeStatusEvent(epoch, statusJson)
                        },
                        onOptionalEventRegistrationFailure = { event, error ->
                            TcptunState.appendLog(
                                "register bridge event $event failed: ${failureDescription(error)}",
                            )
                        },
                    ),
                    canStart = {
                        commandOwner() &&
                            !stopping &&
                            !destroyed.get() &&
                            epoch == bridgeResources.activeEpoch
                    },
                )
            }
            synchronized(lifecycleCommandLock) {
                check(commandOwner() && epoch == bridgeResources.activeEpoch) {
                    "tcptun start was superseded"
                }
                TcptunState.appendLog("tcptun bridge session started: $sessionId")
            }
            waiter.future.get(readyTimeoutMs, TimeUnit.MILLISECONDS)
            val bridgeStatus = synchronized(bridgeLock) {
                check(
                    commandOwner() &&
                        !stopping &&
                        epoch == bridgeResources.activeEpoch,
                ) { "tcptun session changed during startup" }
                bridge.status()
            }
            synchronized(lifecycleCommandLock) {
                check(commandOwner() && epoch == bridgeResources.activeEpoch) {
                    "tcptun session changed during startup"
                }
                check(
                    TcptunState.updateDiagnosticsForBridgeEpoch(epoch) {
                        it.copy(bridgeStatus = bridgeStatus)
                    },
                ) { "tcptun session changed during startup" }
            }
        } finally {
            bridgeReadyWaiter.compareAndSet(waiter, null)
        }
    }

    private fun stopBridge() {
        bridgeReadyWaiter.getAndSet(null)?.future?.completeExceptionally(
            IllegalStateException("tcptun stopped before core became ready"),
        )
        val ownership = bridgeResources.beginStop()
        val stoppedEpoch = ownership.epoch
        if (stoppedEpoch > 0L) TcptunState.endBridgeSession(stoppedEpoch)
        if (!bridgeDelegate.isInitialized()) {
            if (ownership.callbacksRequireCleanup) {
                bridgeResources.nativeStopped()
                bridgeResources.callbacksReleased()
            }
            return
        }
        synchronized(bridgeLock) {
            bridgeSessionStopController.stop(
                settleTimeoutMillis = BRIDGE_STOP_SETTLE_TIMEOUT_MS,
                callbacks = BridgeSessionStopCallbacks(
                    onNativeStillStopping = { error ->
                        // Stop and Abort both failed. Keep callbacks and the stop
                        // obligation alive; clearing Java proxies would violate
                        // tcptun-go's active-runtime ownership contract.
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
                ),
            )
        }
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
    ): BridgeRestartPreparation {
        val configJson = checkNotNull(bridgeResources.activeConfigJson) { "tcptun bridge is unavailable" }
        val plan = checkNotNull(runningPlan) { "tcptun running plan is unavailable" }
        val settings = checkNotNull(appliedRuntimeState.current?.settings) {
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
        appliedRuntimeState.clear()
        check(commandOwner()) { "tcptun restart was superseded" }
        closeTunAfterBridgeStopAttempt()
        check(commandOwner()) { "tcptun restart was superseded" }
        return BridgeRestartPreparation(configJson, plan, settings, reason)
    }

    private fun continueBridgeRestart(
        preparation: BridgeRestartPreparation,
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
        underlyingNetworkCoordinator.republishCurrent("Bridge runtime replaced")
        val replacementEpoch = bridgeResources.activeEpoch
        synchronized(lifecycleCommandLock) {
            if (
                commandOwner() && replacementEpoch == bridgeResources.activeEpoch &&
                tun === replacementTun
            ) {
                TcptunState.appendLog("tcptun bridge transaction restarted")
                TcptunState.setConnectionsReady(true)
                VpnHealthCheckRequests.clearRuntimeForces()
                startBridgeMonitor()
                // The previous runtime's balance observations were discarded
                // by the restart, so seed health after the replacement settles.
                requestMemberHealthProbe(
                    reason = "bridge restarted: ${preparation.reason}",
                    delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
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
        preparation: BridgeRestartPreparation,
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
                    updateNotification(getString(R.string.vpn_notification_reconnecting_retry, attempt))
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
        val mutation = runtimeSettingsDesiredGate.request(forceRestart)
        TcptunState.appendLog("runtime settings apply requested: $reason")
        val ownership = currentRuntimeOwnership() ?: run {
            TcptunState.appendLog("runtime settings retained until a VPN runtime is available")
            return
        }
        val future = scheduleCrashGuardedFuture(
            executor = lifecycleExecutor,
            delay = RUNTIME_SETTINGS_APPLY_DEBOUNCE_MS,
            unit = TimeUnit.MILLISECONDS,
            taskName = "runtime settings debounce",
            onFailure = { error ->
                if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
        ) runtimeApply@{
            if (destroyed.get()) return@runtimeApply
            val claim = runtimeSettingsDesiredGate.bindLatest(ownership) ?: return@runtimeApply
            if (claim.mutation.sequence != mutation.sequence) return@runtimeApply
            scheduleRuntimeSettingsApply(reason, claim)
        }
        if (future == null) {
            if (!destroyed.get()) TcptunState.appendLog("runtime settings apply could not be scheduled")
            return
        }
        runtimeSettingsApplyTask.replace(future)
    }

    /**
     * Queue the debounced apply behind every lifecycle command already accepted.
     * The snapshots prevent a delayed settings thread from restarting over a
     * newer start/stop/outbound update.
     */
    private fun scheduleRuntimeSettingsApply(
        reason: String,
        request: RuntimeSettingsApplyClaim,
    ) {
        val accepted = synchronized(lifecycleCommandLock) {
            if (
                destroyed.get() ||
                explicitStopRequested ||
                !runtimeSettingsDesiredGate.isLatest(request) ||
                !ownsRuntime(request.ownership)
            ) {
                return@synchronized null
            }
            executeLifecycleTask(
                command = VpnRuntimeCommand.ApplyRuntimeSettings(request),
                onFailure = { error ->
                    if (!destroyed.get() && runtimeSettingsDesiredGate.isLatest(request)) {
                        TcptunState.appendLog(failureDescription(error))
                    }
                },
            ) {
                applyRuntimeSettingsNow(
                    reason = reason,
                    request = request,
                )
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
        if (!runtimeSettingsDesiredGate.isLatest(request) || !ownsRuntime(request.ownership)) return
        val plan = runningPlan ?: readDesiredRunningPlan(this) ?: run {
            TcptunState.appendLog("runtime settings apply skipped: no running profile")
            return
        }
        val settings = readRuntimeSettings(this)
        val previous = appliedRuntimeState.current
            ?.takeIf { it.ownership == request.ownership }
            ?.settings ?: return
        var restartRequired = desiredRuntimeSettingsAction(
            previous,
            AppliedRuntimeSettings.from(settings),
            request.mutation.forceRestart,
            freshRuntimeSatisfiesForce = false,
        ) == RuntimeSettingsReconciliationAction.Replace
        if (!restartRequired) {
            val stillCurrent = synchronized(lifecycleCommandLock) {
                runtimeSettingsDesiredGate.isLatest(request) && ownsRuntime(request.ownership)
            }
            if (!stillCurrent) return
            if (settings.logLevel != previous.logLevel) {
                try {
                    synchronized(bridgeLock) {
                        check(ownsRuntime(request.ownership)) { "runtime settings apply was superseded" }
                        bridge.setLogLevel(settings.logLevel)
                        check(bridge.logLevel() == settings.logLevel) {
                            "tcptun bridge did not apply log.level=${settings.logLevel}"
                        }
                        check(ownsRuntime(request.ownership)) { "runtime settings apply was superseded" }
                    }
                } catch (error: Throwable) {
                    if (error.isFatalProcessError()) throw error
                    restartRequired = true
                    TcptunState.appendLog(
                        "dynamic log.level update unavailable; restarting VPN: ${failureDescription(error)}",
                    )
                }
            }
            if (!restartRequired && settings.flowAnalysisApp != previous.flowAnalysisApp) {
                try {
                    synchronized(bridgeLock) {
                        check(ownsRuntime(request.ownership)) { "flow analysis update was superseded" }
                        configureFlowAnalysis(
                            settings.flowAnalysisApp,
                            request.ownership.bridgeEpoch,
                            requireNotNull(bridgeResources.activeConfigJson),
                        )
                        check(ownsRuntime(request.ownership)) { "flow analysis update was superseded" }
                    }
                } catch (error: Throwable) {
                    if (error.isFatalProcessError()) throw error
                    restartRequired = true
                    TcptunState.appendLog(
                        "dynamic flow analysis update unavailable; restarting VPN: ${failureDescription(error)}",
                    )
                }
            }
        }
        if (!restartRequired) {
            if (!runtimeSettingsDesiredGate.isLatest(request)) return
            if (!publishAppliedRuntimeState(request.ownership, AppliedRuntimeSettings.from(settings))) return
            runtimeSettingsDesiredGate.acknowledge(request.mutation.sequence)
            TcptunState.updateDiagnostics {
                it.copy(
                    mtu = settings.mtu,
                    powerSavingMode = settings.powerSavingMode,
                    localProxyAddress = localSocksConnectAddr(settings),
                    localProxyPort = settings.socksPort,
                )
            }
            TcptunState.appendLog(
                "runtime settings applied without VPN restart: " +
                    "log-level=${settings.logLevel} power-saving=${settings.powerSavingMode}",
            )
            wakeBridgeMonitor()
            return
        }

        val restartIntent = startIntent(this, plan)
        val restartClaim = synchronized(lifecycleCommandLock) {
            if (
                !runtimeSettingsDesiredGate.isLatest(request) ||
                !ownsRuntime(request.ownership)
            ) {
                null
            } else {
                runtimeCoordinator.claimReplacement(serviceInstanceId).also {
                    appliedRuntimeState.clear()
                } to
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

    private fun requestFlowAnalysisUpdate() {
        requestRuntimeSettingsApply("flow analysis changed", forceRestart = false)
    }

    private fun onBridgeStatusEvent(epoch: Long, eventJson: String) {
        synchronized(lifecycleCommandLock) {
            // Fold the callback and apply its lifecycle consequences under one
            // lock. A refresh can therefore never validate an older healthy
            // snapshot after this callback has already published an error.
            val event = TcptunState.applyBridgeStatusEvent(epoch, eventJson) ?: return
            val waiter = bridgeReadyWaiter.get()
            if (waiter?.epoch == epoch) {
                when (event.state.lowercase()) {
                    "core_ready" -> waiter.future.complete(Unit)
                    "error", "stopped" -> waiter.future.completeExceptionally(
                        IllegalStateException(event.lastError.ifBlank { "tcptun ${event.state}" }),
                    )
                }
            }
            handleBridgeStatusEvent(epoch, event)
        }
    }

    private fun handleBridgeStatusEvent(epoch: Long, event: BridgeStatusEvent) {
        synchronized(lifecycleCommandLock) {
            if (
                stopping || bridgeRestarting || tun == null || destroyed.get() ||
                !isActiveServiceOwner() || epoch != bridgeResources.activeEpoch
            ) return
            val eventState = event.state.lowercase()
            // Live remote endpoint updates only refresh diagnostics; never restart.
            if (
                eventState == "remote_endpoints_changed" ||
                event.reason.equals(TcptunBridgeEvents.RemoteEndpointsChanged, ignoreCase = true)
            ) {
                return
            }
            when (eventState) {
                "degraded", "reconnecting" -> {
                    // Refresh per-member balance health so recovered or failing
                    // pool members re-score without waiting for a UI refresh.
                    requestMemberHealthProbe("tcptun reported $eventState")
                }
                "error", "stopped" -> {
                    // The engine is no longer able to serve connection-scoped
                    // actions, even when the actual restart is delayed by the
                    // cooldown. Invalidate those actions immediately so the UI
                    // cannot dispatch probes or membership updates to a dead
                    // native session.
                    TcptunState.setConnectionsReady(false)
                    TcptunState.clearTcping()
                    requestMemberHealthProbe("tcptun reported $eventState")
                    requestBridgeRestart(
                        reason = "tcptun reported $eventState",
                        cancelIfHealthy = true,
                        ownership = VpnRuntimeOwnership(
                            runtimeCoordinator.currentToken(serviceInstanceId),
                            epoch,
                        ),
                    )
                }
            }
        }
    }

    private fun startBridgeMonitor() {
        stopBridgeMonitor()
        val generation = monitorGeneration.incrementAndGet()
        val sessionEpoch = bridgeResources.activeEpoch
        val ownership = VpnRuntimeOwnership(
            runtimeCoordinator.currentToken(serviceInstanceId),
            sessionEpoch,
        )
        val initialHandledWakeGeneration = monitorWakeGeneration.get()
        monitorThread = startCrashGuardedThread(
            threadName = "TcptunBridgeMonitor",
            onFailure = { error ->
                if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
        ) {
            bridgeHealthMonitor.run(
                initialWakeGeneration = initialHandledWakeGeneration,
                isCurrent = {
                    generation == monitorGeneration.get() &&
                        sessionEpoch == bridgeResources.activeEpoch &&
                        runtimeCoordinator.isCurrent(ownership.runtimeToken) && !stopping
                },
                canProbe = {
                    generation == monitorGeneration.get() &&
                        sessionEpoch == bridgeResources.activeEpoch &&
                        runtimeCoordinator.isCurrent(ownership.runtimeToken) &&
                        tun != null &&
                        !stopping
                },
                awaitEvent = ::awaitBridgeHealthEvent,
                probeFailureReason = { vpnHealthFailure(generation, ownership)?.reason },
                onSchedule = scheduleCallback@{ schedule ->
                    if (!ownsRuntime(ownership)) return@scheduleCallback
                    val diagnostics = TcptunState.state.value.diagnostics
                    if (
                        diagnostics.healthCheckEventDriven != schedule.eventDriven ||
                        diagnostics.healthCheckIntervalSeconds != schedule.intervalSeconds
                    ) {
                        TcptunState.updateDiagnosticsForBridgeEpoch(sessionEpoch) {
                            it.copy(
                                healthCheckEventDriven = schedule.eventDriven,
                                healthCheckIntervalSeconds = schedule.intervalSeconds,
                            )
                        }
                    }
                },
                onFailure = { reason ->
                    TcptunState.appendLog("VPN health check failed: $reason")
                },
                onRestartRequired = { reason ->
                    requestBridgeRestart(reason, cancelIfHealthy = true, ownership = ownership)
                },
                onRecoverableError = { error ->
                    TcptunState.appendLog("tcptun bridge monitor error: ${failureDescription(error)}")
                },
            )
        }
    }

    /** Debounces member probes on the service-owned lifecycle executor. */
    private fun scheduleMemberHealthProbe(reason: String, requestedDelayMs: Long) {
        val ownership = synchronized(lifecycleCommandLock) {
            val epoch = bridgeResources.activeEpoch
            if (destroyed.get() || stopping || !isActiveServiceOwner() || tun == null || epoch <= 0L) {
                return
            }
            VpnRuntimeOwnership(runtimeCoordinator.currentToken(serviceInstanceId), epoch)
        }
        memberHealthProbeScheduler.schedule(reason, requestedDelayMs) {
            ownsRuntime(ownership)
        }
    }

    private fun stopBridgeMonitor() {
        monitorGeneration.incrementAndGet()
        wakeBridgeMonitor()
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun awaitBridgeHealthEvent(handledWakeGeneration: Int, timeoutMs: Long?): Int {
        val deadlineMs = timeoutMs?.let { SystemClock.elapsedRealtime() + it }
        synchronized(monitorWaitLock) {
            while (!stopping && !Thread.currentThread().isInterrupted) {
                val currentWakeGeneration = monitorWakeGeneration.get()
                if (handledWakeGeneration != currentWakeGeneration) return currentWakeGeneration
                if (deadlineMs == null) {
                    monitorWaitLock.wait()
                } else {
                    val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0) return handledWakeGeneration
                    monitorWaitLock.wait(remainingMs)
                }
            }
        }
        return handledWakeGeneration
    }

    private fun wakeBridgeMonitor() {
        monitorWakeGeneration.incrementAndGet()
        synchronized(monitorWaitLock) { monitorWaitLock.notifyAll() }
    }

    private fun vpnHealthFailure(
        monitorEpoch: Int,
        ownership: VpnRuntimeOwnership,
    ): HealthFailure? {
        if (!ownsRuntime(ownership)) return null
        val sessionEpoch = ownership.bridgeEpoch
        val uiVisible = TcptunState.isUiVisible
        val previous = TcptunState.state.value.diagnostics
        // Prefer StatusCallback state already folded into TcptunState. Only
        // UI-forced refreshes reconcile against StatusJSON (authoritative snapshot).
        val reconcile = shouldReconcileStatusJson()
        val observedStatus = if (reconcile) {
            reconcileBridgeStatusFromJson(ownership)?.ifBlank { TcptunState.status.displayName }
                ?: run {
                    TcptunState.updateDiagnosticsForBridgeEpoch(sessionEpoch) {
                        it.copy(localProxyReachable = false)
                    }
                    return HealthFailure("status unavailable")
                }
        } else {
            TcptunState.state.value.diagnostics.bridgeStatus
                .ifBlank { TcptunState.status.displayName }
                .ifBlank { "Unknown" }
        }
        val probeLocalProxy = BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible)
        val localProxyReachable = if (probeLocalProxy) canConnectLocalProxy() else true
        if (!ownsRuntime(ownership)) return null
        val localProxyAddress = activeLocalSocksConnectAddr()
        val nextLocalProxyReachable = if (probeLocalProxy) localProxyReachable else previous.localProxyReachable
        if (
            uiVisible ||
            previous.localProxyReachable != nextLocalProxyReachable ||
            previous.localProxyAddress != localProxyAddress ||
            previous.localProxyPort != activeSocksPort
        ) {
            if (!ownsRuntime(ownership)) return null
            TcptunState.updateDiagnosticsForBridgeEpoch(sessionEpoch) {
                it.copy(
                    localProxyReachable = nextLocalProxyReachable,
                    localProxyAddress = localProxyAddress,
                    localProxyPort = activeSocksPort,
                )
            }
        }
        // A callback may have advanced after the snapshot/probe began. Status
        // callbacks and the cursor-aware snapshot reducer are the only writers
        // of bridgeStatus; health probing must consume, never overwrite, them.
        val status = TcptunState.state.value.diagnostics.bridgeStatus
            .ifBlank { observedStatus }
            .ifBlank { "Unknown" }
        if (status != "Running") {
            return HealthFailure("bridge status is $status")
        }
        if (probeLocalProxy && !localProxyReachable) {
            return HealthFailure("local proxy $localProxyAddress is not accepting connections")
        }
        if (reconcile && probeLocalProxy && localProxyReachable) {
            restoreConnectionsReadyAfterHealthySnapshot(ownership)
        }
        // Member probes only when an event forced them (network / RUNTIME_* /
        // membership / UI). Aggregate SOCKS/HTTP stays UI-only.
        if (shouldProbeMemberHealth()) {
            val targets = localProxyHealthProbe.orderedTargets()
            probeActiveMembers(targets, monitorEpoch, ownership)
            if (monitorEpoch != monitorGeneration.get() || !ownsRuntime(ownership)) return null
        }
        if (shouldRunUpstreamProbe()) {
            val targets = localProxyHealthProbe.orderedTargets()
            if (!ownsRuntime(ownership)) return null
            val upstreamFailure = upstreamProbeFailure(targets)
            if (!ownsRuntime(ownership)) return null
            updateRawProfileHealth(upstreamFailure, ownership)
            upstreamFailure?.let { return HealthFailure(it) }
        }
        return null
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

    /**
     * Fold the latest StatusJSON into diagnostics and return the cursor-current
     * simple status. An equal cursor can re-sync a UI pull; an older snapshot
     * rejected after a callback race resolves to the newer callback status.
     */
    private fun reconcileBridgeStatusFromJson(ownership: VpnRuntimeOwnership): String? {
        return runRecoverableCatching {
            val sessionEpoch = ownership.bridgeEpoch
            val rawStatus = synchronized(bridgeLock) {
                check(
                    ownsRuntime(ownership) &&
                            bridgeResources.activeConfigJson != null,
                ) {
                    "tcptun session is unavailable"
                }
                bridge.statusJson()
            }
            val snapshot = BridgeStatusJson.parse(rawStatus)
            if (!ownsRuntime(ownership)) return@runRecoverableCatching null
            val eventState = snapshot.state
            val status = TcptunState.bridgeSimpleStatus(eventState)
            val applied = TcptunState.reconcileBridgeStatusSnapshotForEpoch(
                epoch = sessionEpoch,
                sessionId = snapshot.sessionId,
                sequence = snapshot.sequence,
                bridgeStatus = status,
                bridgeLastError = snapshot.lastError.orEmpty(),
                eventState = eventState,
            ) {
                snapshot.applyTo(it, status)
            }
            when {
                applied -> status
                ownsRuntime(ownership) ->
                    TcptunState.state.value.diagnostics.bridgeStatus.ifBlank { status }
                else -> null
            }
        }.getOrNull()
    }

    private fun probeActiveMembers(
        targets: List<UpstreamProbeTarget>,
        monitorEpoch: Int,
        ownership: VpnRuntimeOwnership,
    ) {
        // A full-JSON profile can use a selector as its default outbound. It is
        // represented as one app profile, so only the aggregate SOCKS/TLS probe
        // can describe its health without guessing at its internal members.
        val candidates = runningPlan?.activeProfiles.orEmpty().filter { it.rawConfigJson.isBlank() }
        val sessionEpoch = ownership.bridgeEpoch
        if (candidates.isEmpty() || targets.isEmpty() || sessionEpoch <= 0L) return
        val worstCaseProfileMs = MEMBER_HEALTH_PROBE_TIMEOUT_MS.toLong() * targets.size
        val maxProfiles = (MEMBER_HEALTH_BATCH_TIMEOUT_MS / worstCaseProfileMs)
            .toInt()
            .coerceAtLeast(1)
        val profiles = memberHealthBatchSelector.select(candidates, maxProfiles)
        val tasks = profiles.map { profile ->
            Callable { probeMember(profile, targets, ownership) }
        }
        val timeoutMs = minOf(
            MEMBER_HEALTH_BATCH_TIMEOUT_MS,
            worstCaseProfileMs * profiles.size,
        ) + MEMBER_HEALTH_PROBE_GRACE_MS
        val futures = try {
            memberHealthExecutor.purge()
            memberHealthExecutor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            // invokeAll cancels unfinished tasks at the deadline, but a blocked
            // single worker cannot dequeue them. Purge immediately releases the
            // profiles, targets, and service references captured by that batch.
            memberHealthExecutor.purge()
        }
        if (monitorEpoch != monitorGeneration.get() || !ownsRuntime(ownership)) return
        val coreRefreshProfiles = mutableListOf<AppConfig>()
        var retryTransientFailure = false
        futures.forEachIndexed { index, future ->
            val profile = profiles[index]
            if (!ownsRuntime(ownership) || profile.id !in runningPlan?.activeIds.orEmpty()) {
                return@forEachIndexed
            }
            val result = runRecoverableCatching {
                if (future.isCancelled) {
                    MemberHealthProbeResult(profile, error = "health probe timed out")
                } else {
                    future.get()
                }
            }.getOrElse { err ->
                MemberHealthProbeResult(profile, error = err.cause?.message ?: err.message ?: err.javaClass.simpleName)
            }
            val previous = TcptunState.state.value.profileHealth[profile.id]
            val now = System.currentTimeMillis()
            val hasNoCompletedProbe = previous == null ||
                (previous.lastSucceededAtMs <= 0L && previous.lastCheckedAtMs <= 0L)
            val health = if (result.elapsedMs != null) {
                ProfileHealth(
                    status = ProfileHealthStatus.Healthy,
                    latencyMs = result.elapsedMs,
                    failures = 0,
                    lastCheckedAtMs = now,
                    lastSucceededAtMs = now,
                )
            } else if (
                BridgeHealthPolicy.isTransientMemberProbeFailure(result.error) &&
                hasNoCompletedProbe
            ) {
                // Keep one first-time setup failure unknown in the UI and retry
                // once after another settle window. A repeated failure falls
                // through to Degraded so it cannot be hidden indefinitely.
                TcptunState.appendLog(
                    "connection ${profile.name} health probe deferred: ${result.error}",
                )
                retryTransientFailure = true
                previous?.copy(lastCheckedAtMs = now) ?: ProfileHealth(lastCheckedAtMs = now)
            } else {
                coreRefreshProfiles += profile
                ProfileHealth(
                    status = ProfileHealthStatus.Degraded,
                    latencyMs = previous?.latencyMs,
                    failures = (previous?.failures ?: 0) + 1,
                    lastCheckedAtMs = now,
                    lastSucceededAtMs = previous?.lastSucceededAtMs ?: 0,
                    error = result.error,
                )
            }
            if (result.elapsedMs != null) {
                coreRefreshProfiles += profile
            }
            if (!ownsRuntime(ownership)) return@forEachIndexed
            TcptunState.setProfileHealthForBridgeEpoch(sessionEpoch, profile.id, health)
            if (previous?.status != health.status) {
                val detail = health.latencyMs?.let { "${it}ms" } ?: health.error.ifBlank { "unknown" }
                TcptunState.appendLog("connection ${profile.name} health: ${health.status.name.lowercase()} $detail")
            }
        }
        if (coreRefreshProfiles.isNotEmpty()) {
            refreshProfileHealthFromCore(coreRefreshProfiles, ownership)
        }
        if (retryTransientFailure && monitorEpoch == monitorGeneration.get() && ownsRuntime(ownership)) {
            requestMemberHealthProbe(
                reason = "retry transient member health failure",
                delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
            )
        }
    }

    private fun updateRawProfileHealth(failure: String?, ownership: VpnRuntimeOwnership) {
        if (!ownsRuntime(ownership)) return
        val sessionEpoch = ownership.bridgeEpoch
        val profile = runningPlan?.activeProfiles?.singleOrNull { it.rawConfigJson.isNotBlank() } ?: return
        val previous = TcptunState.state.value.profileHealth[profile.id]
        val now = System.currentTimeMillis()
        val health = if (failure == null) {
            ProfileHealth(
                status = ProfileHealthStatus.Healthy,
                failures = 0,
                lastCheckedAtMs = now,
                lastSucceededAtMs = now,
            )
        } else {
            ProfileHealth(
                status = ProfileHealthStatus.Degraded,
                latencyMs = previous?.latencyMs,
                failures = (previous?.failures ?: 0) + 1,
                lastCheckedAtMs = now,
                lastSucceededAtMs = previous?.lastSucceededAtMs ?: 0,
                error = failure,
            )
        }
        if (!ownsRuntime(ownership)) return
        TcptunState.setProfileHealthForBridgeEpoch(sessionEpoch, profile.id, health)
    }

    private fun refreshProfileHealthFromCore(
        profiles: List<AppConfig>,
        ownership: VpnRuntimeOwnership,
    ) {
        val profileByTag = profiles.associateBy(AppConfig::runtimeOutboundTag)
        val sessionEpoch = ownership.bridgeEpoch
        runRecoverableCatching {
            val rawStatuses = synchronized(bridgeLock) {
                check(
                    ownsRuntime(ownership),
                ) {
                    "tcptun session is unavailable"
                }
                bridge.outboundsStatusJson()
            }
            BridgeStatusJson.parseOutboundHealth(rawStatuses)
        }
            .onSuccess { statuses ->
                for (status in statuses) {
                    if (!ownsRuntime(ownership)) return@onSuccess
                    val profile = profileByTag[status.tag] ?: continue
                    if (profile.id !in runningPlan?.activeIds.orEmpty()) continue
                    val previous = TcptunState.state.value.profileHealth[profile.id]
                    TcptunState.setProfileHealthForBridgeEpoch(
                        sessionEpoch,
                        profile.id,
                        ProfileHealth(
                            status = status.health,
                            latencyMs = status.latencyMs,
                            failures = status.failures,
                            lastCheckedAtMs = status.lastObservedAtMs,
                            lastSucceededAtMs = status.lastSucceededAtMs,
                            error = previous?.error
                                .takeIf { status.health == ProfileHealthStatus.Degraded }
                                .orEmpty(),
                        ),
                    )
                }
            }
            .onFailure { err -> TcptunState.appendLog("outbound health status unavailable: ${err.message}") }
    }

    private fun probeMember(
        profile: AppConfig,
        targets: List<UpstreamProbeTarget>,
        ownership: VpnRuntimeOwnership,
    ): MemberHealthProbeResult {
        val sessionEpoch = ownership.bridgeEpoch
        val failures = mutableListOf<String>()
        for (target in targets) {
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("health probe cancelled")
            }
            val elapsed = runRecoverableCatching {
                synchronized(bridgeLock) {
                    if (
                        Thread.currentThread().isInterrupted ||
                        !ownsRuntime(ownership)
                    ) {
                        throw CancellationException("VPN session changed")
                    }
                    bridge.probeOutboundHealth(
                        tag = profile.runtimeOutboundTag(),
                        host = target.host,
                        port = target.port,
                        timeoutMillis = MEMBER_HEALTH_PROBE_TIMEOUT_MS,
                    ).also {
                        if (
                            Thread.currentThread().isInterrupted ||
                            !ownsRuntime(ownership)
                        ) {
                            throw CancellationException("VPN session changed")
                        }
                    }
                }
            }
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("health probe cancelled")
            }
            elapsed.getOrNull()?.let { return MemberHealthProbeResult(profile, elapsedMs = it) }
            val err = elapsed.exceptionOrNull()
            failures += "${target.label}: ${err?.message ?: err?.javaClass?.simpleName ?: "failed"}"
        }
        return MemberHealthProbeResult(profile, error = failures.joinToString("; "))
    }

    private fun updateUnderlyingDiagnostics(network: Network?) {
        TcptunState.updateDiagnostics {
            it.copy(underlyingNetwork = network?.toString() ?: "None")
        }
    }

    private fun bridgeRuntimeSnapshot(): BridgeRuntimeSnapshot? {
        return runRecoverableCatching {
            val sessionEpoch = bridgeResources.activeEpoch
            val rawStatus = synchronized(bridgeLock) {
                check(
                    !stopping && sessionEpoch > 0L && sessionEpoch == bridgeResources.activeEpoch,
                ) {
                    "tcptun session is unavailable"
                }
                bridge.statusJson()
            }
            BridgeStatusJson.parse(rawStatus).runtimeSnapshot(sessionEpoch)
        }.getOrNull()
    }

    private fun requestBridgeClientIpsRefresh() {
        executeLifecycleTask(
            command = VpnRuntimeCommand.RefreshDiagnostics,
            onFailure = { error ->
                if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
        ) { refreshBridgeClientIps() }
    }

    private fun refreshBridgeClientIps() {
        if (stopping || tun == null || TcptunState.status != VpnStatus.Running) return
        val snapshot = bridgeRuntimeSnapshot() ?: return
        TcptunState.updateDiagnosticsForBridgeEpoch(snapshot.epoch) {
            it.copy(
                bridgeActiveConnections = snapshot.activeConnections,
                bridgeClientIps = snapshot.clientIps,
            )
        }
    }

    private fun canConnectLocalProxy(): Boolean {
        return localProxyHealthProbe.canConnect(activeSocksPort)
    }

    private fun shouldRunUpstreamProbe(): Boolean {
        val force = VpnHealthCheckRequests.consumeUpstreamProbeForce()
        val allowed = BridgeHealthPolicy.shouldRunUpstreamProbe(
            uiVisible = TcptunState.isUiVisible,
            force = force,
        )
        if (!allowed) {
            // Keep a pending force if this cycle could not run (UI already hidden).
            if (force && !TcptunState.isUiVisible) {
                VpnHealthCheckRequests.restoreUpstreamProbeForce()
            }
            return false
        }
        return true
    }

    private fun shouldReconcileStatusJson(): Boolean {
        val force = VpnHealthCheckRequests.consumeStatusReconcileForce()
        val allowed = BridgeHealthPolicy.shouldReconcileStatusJson(
            uiVisible = TcptunState.isUiVisible,
            force = force,
        )
        if (!allowed) {
            if (force && !TcptunState.isUiVisible) {
                VpnHealthCheckRequests.restoreStatusReconcileForce()
            }
            return false
        }
        return true
    }

    private fun shouldProbeMemberHealth(): Boolean {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val notBeforeMs = memberHealthProbeScheduler.notBeforeMs
        // Peek force without consuming while still inside the settle window so a
        // delayed wake can still run the forced probe afterward.
        if (notBeforeMs > 0L && nowElapsedMs < notBeforeMs) {
            return false
        }
        val force = VpnHealthCheckRequests.consumeMemberProbeForce()
        val allowed = BridgeHealthPolicy.shouldProbeMemberHealth(
            force = force,
            nowMs = nowElapsedMs,
            notBeforeMs = notBeforeMs,
        )
        if (!allowed) return false
        lastMemberHealthProbeAtElapsedMs = nowElapsedMs
        return true
    }

    private fun upstreamProbeFailure(targets: List<UpstreamProbeTarget>): String? {
        return localProxyHealthProbe.upstreamFailure(
            orderedTargets = targets,
            localPort = activeSocksPort,
            username = activeSocksUsername,
            password = activeSocksPassword,
            onSuccess = { target -> TcptunState.appendLog("upstream probe ${target.label} succeeded") },
        )
    }

    private fun activeLocalSocksConnectAddr(): String {
        return localProxyHealthProbe.connectAddress(activeSocksPort)
    }

    private fun startVpnForeground(state: String) = notificationController.startForeground(state)

    private fun updateNotification(state: String) = notificationController.update(state)

    private fun createNotificationChannel() = notificationController.createChannel()

    companion object {
        const val ACTION_START = VpnServiceIntents.ActionStart
        const val ACTION_STOP = VpnServiceIntents.ActionStop
        const val ACTION_UPDATE_OUTBOUNDS = VpnServiceIntents.ActionUpdateOutbounds
        const val ACTION_TCPING_OUTBOUNDS = VpnServiceIntents.ActionTcpingOutbounds
        const val ACTION_APPLY_RUNTIME_SETTINGS = VpnServiceIntents.ActionApplyRuntimeSettings
        const val ACTION_UPDATE_FLOW_ANALYSIS = VpnServiceIntents.ActionUpdateFlowAnalysis
        const val ACTION_REFRESH_CLIENT_IPS = VpnServiceIntents.ActionRefreshClientIps
        const val EXTRA_CONFIG = VpnServiceIntents.ExtraConfig
        private const val EXTRA_PROFILE_PLAN = VpnServiceIntents.ExtraProfilePlan
        private const val EXTRA_TCPING_REQUEST_ID = VpnServiceIntents.ExtraTcpingRequestId
        private const val EXTRA_TCPING_TARGET_LABEL = VpnServiceIntents.ExtraTcpingTargetLabel
        private const val EXTRA_TCPING_HOST = VpnServiceIntents.ExtraTcpingHost
        private const val EXTRA_TCPING_PORT = VpnServiceIntents.ExtraTcpingPort
        private const val EXTRA_FORCE_RUNTIME_RESTART = VpnServiceIntents.ExtraForceRuntimeRestart
        const val LOCAL_SOCKS_HOST = RuntimeSettingsDefaults.LocalSocksHost
        const val DEFAULT_SOCKS_PORT = RuntimeSettingsDefaults.SocksPort
        const val DEFAULT_VPN_MTU = RuntimeSettingsDefaults.VpnMtu
        private const val VPN_DNS_ADDRESS = "10.77.0.1"
        private const val HEALTH_FAILURE_LIMIT = 2
        private const val BRIDGE_RESTART_DELAY_MS = 300L
        private const val BRIDGE_RESTART_MIN_INTERVAL_MS = 30_000L
        private const val BRIDGE_READY_TIMEOUT_MS = 15_000L
        private const val BRIDGE_STOP_SETTLE_TIMEOUT_MS = 5_000L
        private const val OUTBOUND_STOP_TIMEOUT_MS = 15_000L
        private const val TCPING_OUTBOUND_TIMEOUT_MS = 3_000L
        private const val TCPING_OUTBOUND_TOTAL_TIMEOUT_MS = 20_000L
        private const val TCPING_OUTBOUND_BATCH_TIMEOUT_MS = 60_000L
        private const val MEMBER_HEALTH_PROBE_TIMEOUT_MS = 3_000L
        private const val MEMBER_HEALTH_PROBE_GRACE_MS = 1_000L
        private const val MEMBER_HEALTH_BATCH_TIMEOUT_MS = 30_000L
        // One-target batches can contain up to ten profiles. Leave headroom for
        // invokeAll's current batch while still placing a hard ownership bound.
        private const val MAX_MEMBER_HEALTH_EXECUTOR_QUEUE_CAPACITY = 16
        private const val MAX_TCPING_EXECUTOR_QUEUE_CAPACITY = 2
        private const val RUNTIME_SETTINGS_APPLY_DEBOUNCE_MS = 800L
        private const val DESTROY_EXECUTOR_WAIT_MS = 2_000L
        private const val DESTROY_ABORT_SETTLE_WAIT_MS = 5_000L
        private const val DEFERRED_DESTROY_WAIT_MS = 15_000L
        private const val DESTROY_MAX_LIFECYCLE_WAIT_MS = 35_000L
        private const val PREVIOUS_RUNTIME_RELEASE_WAIT_MS = 35_000L
        private val serviceOwnerLock = Any()
        private val nextServiceInstanceId = AtomicLong()
        private val activeServiceInstanceId = AtomicLong()
        private val bridgeRuntimeLease = BridgeRuntimeLease()
        fun startIntent(context: Context, config: AppConfig): Intent {
            return VpnServiceIntents.start(context, config)
        }

        fun refreshClientIpsIntent(context: Context): Intent {
            return VpnServiceIntents.refreshClientIps(context)
        }

        fun startIntent(context: Context, sourcePlan: ProfileRunPlan): Intent {
            return VpnServiceIntents.start(context, sourcePlan)
        }

        /** Validate candidate route rules before they become authoritative storage. */
        internal fun preflightStartPayload(
            context: Context,
            sourcePlan: ProfileRunPlan,
            managedRouteRules: List<ManagedRouteRule>,
        ) {
            VpnServiceIntents.preflightStart(context, sourcePlan, managedRouteRules)
        }

        fun stopIntent(context: Context): Intent {
            return VpnServiceIntents.stop(context)
        }

        fun updateOutboundsIntent(context: Context, plan: ProfileRunPlan): Intent {
            return VpnServiceIntents.updateOutbounds(context, plan)
        }

        fun applyRuntimeSettingsIntent(context: Context, forceRestart: Boolean = false): Intent {
            return VpnServiceIntents.applyRuntimeSettings(context, forceRestart)
        }

        fun updateFlowAnalysisIntent(context: Context): Intent {
            return VpnServiceIntents.updateFlowAnalysis(context)
        }

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
        fun requestHealthCheck(reason: String) {
            VpnHealthCheckRequests.requestHealthCheck(reason)
        }

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

        fun readRuntimeSettings(context: Context): RuntimeSettings = RuntimeSettingsRepository.read(context)

        fun writeRuntimeSettings(context: Context, settings: RuntimeSettings) =
            RuntimeSettingsRepository.write(context, settings)

        fun localSocksListenAddr(settings: RuntimeSettings): String =
            RuntimeSettingsRepository.localSocksListenAddress(settings)

        fun localSocksConnectAddr(settings: RuntimeSettings): String =
            RuntimeSettingsRepository.localSocksConnectAddress(settings)

        fun defaultLocalSocksConnectAddr(): String = RuntimeSettingsRepository.defaultLocalSocksConnectAddress()

        private fun encodeDesiredRunningPlan(plan: ProfileRunPlan): String {
            return DesiredRunningPlanStore.encode(plan)
        }

        private fun publishDesiredRunningPlan(context: Context, rawPlan: String) {
            DesiredRunningPlanStore.publish(context, rawPlan)
        }

        private fun readDesiredRunningPlan(context: Context): ProfileRunPlan? {
            return DesiredRunningPlanStore.read(context)
        }

        private fun clearDesiredRunningConfig(context: Context) {
            DesiredRunningPlanStore.clear(context)
        }

    }
}
