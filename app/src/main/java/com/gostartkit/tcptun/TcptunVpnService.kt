package com.tcptun.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun executeCrashGuarded(
    executor: Executor,
    taskName: String,
    onFailure: (Throwable) -> Unit = {},
    task: () -> Unit,
): Boolean {
    fun report(error: Throwable) {
        try {
            onFailure(error)
        } catch (reportingError: Throwable) {
            if (reportingError.isFatalProcessError()) throw reportingError
        }
    }
    return try {
        executor.execute {
            try {
                task()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: CancellationException) {
                // Cancellation is an expected lifecycle outcome, not a task failure.
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                report(IllegalStateException("$taskName failed: ${failureDescription(error)}", error))
            }
        }
        true
    } catch (error: RejectedExecutionException) {
        report(IllegalStateException("$taskName rejected: ${failureDescription(error)}", error))
        false
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        report(IllegalStateException("$taskName submission failed: ${failureDescription(error)}", error))
        false
    }
}

internal fun scheduleCrashGuardedFuture(
    executor: ScheduledExecutorService,
    delay: Long,
    unit: TimeUnit,
    taskName: String,
    onFailure: (Throwable) -> Unit = {},
    task: () -> Unit,
): ScheduledFuture<*>? {
    fun report(error: Throwable) {
        try {
            onFailure(error)
        } catch (reportingError: Throwable) {
            if (reportingError.isFatalProcessError()) throw reportingError
        }
    }
    return try {
        executor.schedule({
            try {
                task()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: CancellationException) {
                // Cancellation is an expected lifecycle outcome, not a task failure.
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                report(IllegalStateException("$taskName failed: ${failureDescription(error)}", error))
            }
        }, delay.coerceAtLeast(0L), unit)
    } catch (error: RejectedExecutionException) {
        report(IllegalStateException("$taskName rejected: ${failureDescription(error)}", error))
        null
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        report(IllegalStateException("$taskName scheduling failed: ${failureDescription(error)}", error))
        null
    }
}

internal fun startCrashGuardedThread(
    threadName: String,
    onFailure: (Throwable) -> Unit = {},
    task: () -> Unit,
): Thread? {
    fun report(error: Throwable) {
        try {
            onFailure(error)
        } catch (reportingError: Throwable) {
            if (reportingError.isFatalProcessError()) throw reportingError
        }
    }
    val thread = try {
        Thread({
            try {
                task()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: CancellationException) {
                // Cancellation is an expected lifecycle outcome, not a thread failure.
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                report(IllegalStateException("$threadName failed: ${failureDescription(error)}", error))
            }
        }, threadName).apply { isDaemon = true }
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        report(IllegalStateException("$threadName creation failed: ${failureDescription(error)}", error))
        return null
    }
    return try {
        thread.start()
        thread
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        report(IllegalStateException("$threadName start failed: ${failureDescription(error)}", error))
        null
    }
}

private fun <T> SharedPreferences.readOrDefault(
    key: String,
    defaultValue: T,
    read: SharedPreferences.() -> T,
): T = try {
    read()
} catch (error: Throwable) {
    if (error.isFatalProcessError()) throw error
    try {
        TcptunState.appendLog("runtime setting $key is invalid; using default")
    } catch (loggingError: Throwable) {
        if (loggingError.isFatalProcessError()) throw loggingError
    }
    defaultValue
}

data class RuntimeSettings(
    val mtu: Int = TcptunVpnService.DEFAULT_VPN_MTU,
    val powerSavingMode: Boolean = true,
    val logLevel: String = DefaultLogLevel,
    val socksPort: Int = TcptunVpnService.DEFAULT_SOCKS_PORT,
    val localProxyProtocol: String = DefaultLocalProxyProtocol,
    val socksListenAll: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
    /** When true, managed route rules also match mixed/SOCKS local proxy traffic. Default off. */
    val routeLocalProxyTraffic: Boolean = false,
    /** Empty selects the dynamic pool; __direct__ selects direct; any other value is a profile ID. */
    val defaultOutbound: String = DefaultOutboundDynamicPool,
    val flowAnalysisApp: String = "",
)

private val AndroidPackageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private const val MAX_FLOW_ANALYSIS_APP_LENGTH = 255

internal fun normalizeFlowAnalysisApp(value: String): String {
    if (value.length > MAX_FLOW_ANALYSIS_APP_LENGTH) return ""
    return value.trim().takeIf(AndroidPackageNamePattern::matches).orEmpty()
}

private data class UpstreamProbeTarget(
    val label: String,
    val host: String,
    val port: Int = 443,
    val path: String = "/",
    val expectedStatus: Int? = null,
)

private data class HealthFailure(
    val reason: String,
)

internal class ConnectionUpdateTracker {
    private val latestGeneration = AtomicInteger()

    @Synchronized
    fun begin(): Int = latestGeneration.incrementAndGet()

    fun current(): Int = latestGeneration.get()

    fun isLatest(generation: Int): Boolean = generation == latestGeneration.get()

    @Synchronized
    fun runIfLatest(generation: Int, action: () -> Unit): Boolean {
        if (!isLatest(generation)) return false
        action()
        return true
    }
}

private data class MemberHealthProbeResult(
    val profile: AppConfig,
    val elapsedMs: Long? = null,
    val error: String = "",
)

private data class BridgeReadyWaiter(
    val epoch: Long,
    val future: CompletableFuture<Unit> = CompletableFuture(),
)

private data class BridgeRuntimeSnapshot(
    val epoch: Long,
    val activeConnections: Int,
    val clientIps: List<String>,
    val muxSources: Int,
    val muxSessions: Int,
    val muxStreams: Int,
)

internal fun underlyingNetworkScore(
    validated: Boolean,
    ethernet: Boolean,
    wifi: Boolean,
    cellular: Boolean,
): Int {
    var score = 0
    if (validated) score += 100
    if (ethernet) score += 40
    if (wifi) score += 30
    if (cellular) score += 20
    return score
}

class TcptunVpnService : VpnService() {
    private val serviceInstanceId = nextServiceInstanceId.incrementAndGet()
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
    private val lifecycleExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TcptunLifecycle").apply { isDaemon = true }
    }
    private val tcpingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TcptunTcping").apply { isDaemon = true }
    }
    // ProbeOutboundHealth is serialized by bridgeLock, so a wider pool only
    // creates cancelled workers waiting on the same native engine.
    private val memberHealthExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TcptunMemberHealth").apply { isDaemon = true }
    }
    private val memberHealthBatchSelector = RoundRobinBatchSelector()
    private val lifecycleGeneration = AtomicInteger()
    private val persistentCommandGeneration = AtomicInteger()
    private val lifecycleWorkInFlight = AtomicInteger()
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
    private val explicitStopRequested = AtomicBoolean()
    private val bridgeReadyWaiter = AtomicReference<BridgeReadyWaiter?>(null)
    private val tun: android.os.ParcelFileDescriptor?
        get() = tunOwner.resource
    @Volatile private var runningPlan: ProfileRunPlan? = null
    @Volatile private var monitorThread: Thread? = null
    private val monitorWaitLock = Object()
    private val monitorWakeCallback: () -> Unit = ::wakeBridgeMonitor
    private val connectivityDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("ConnectivityManager is unavailable")
    }
    private val connectivity: ConnectivityManager get() = connectivityDelegate.value
    private val appIdentityProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppIdentityProvider(applicationContext, connectivity)
    }
    private val appIdentityProvider: AndroidAppIdentityProvider get() = appIdentityProviderDelegate.value
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetworkCallbackEpoch = 0L
    private var underlyingNetworkCallbackRegistered = false
    private val underlyingNetworkRegistrationLock = Any()
    private val underlyingNetworkCallbackEpochGate = CallbackEpochGate()
    private val underlyingNetworkSelection = RankedSelectionTracker<Network>()
    @Volatile private var stopping = false
    @Volatile private var bridgeRestarting = false
    @Volatile private var tunMtu = DEFAULT_VPN_MTU
    @Volatile private var activeSocksPort = DEFAULT_SOCKS_PORT
    @Volatile private var activeSocksUsername = ""
    @Volatile private var activeSocksPassword = ""
    @Volatile private var activeLocalProxyProtocol = DefaultLocalProxyProtocol
    @Volatile private var activeSocksListenAll = false
    @Volatile private var activeRouteLocalProxyTraffic = false
    @Volatile private var activeDefaultOutbound = DefaultOutboundDynamicPool
    @Volatile private var activeFlowAnalysisApp = ""
    @Volatile private var activeLogLevel = DefaultLogLevel
    @Volatile private var powerSavingMode = true
    @Volatile private var upstreamProbeIndex = 0
    @Volatile private var lastMemberHealthProbeAtElapsedMs = 0L
    private val runtimeSettingsApplyGate = RuntimeSettingsApplyGate()
    private val runtimeSettingsApplyTask = LatestTaskSlot()
    private val bridgeRestartTask = LatestTaskSlot()
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
            activeMonitorWakeCallback.set(monitorWakeCallback)
            createNotificationChannel()
            initialized.set(true)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            activeMonitorWakeCallback.compareAndSet(monitorWakeCallback, null)
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
            generation != lifecycleGeneration.get()
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
        val commandKind = serviceCommandKind(action)
        synchronized(lifecycleCommandLock) {
            latestStartId.updateAndGet { current -> maxOf(current, startId) }
            val replacesRuntime = commandKind == ServiceCommandKind.StartOrRestore ||
                (commandKind == ServiceCommandKind.UpdateConnections && !explicitStopRequested.get())
            if (replacesRuntime) {
                // Linearize replacement work before foreground publication or
                // any other blocking operation so stale cleanup cannot stop it.
                persistentCommandGeneration.incrementAndGet()
                lifecycleGeneration.incrementAndGet()
            }
        }
        val foregroundStart = action == ACTION_START || action == null
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
                commandKind = commandKind,
                hasRuntimeResources = tun != null ||
                    bridgeResources.hasOwnedResources ||
                    runningPlan != null,
                lifecycleWorkPending = lifecycleWorkInFlight.get() > 0,
                bridgeRecoveryPending = bridgeRecoveryCoordinator.recoveryPending,
                teardownRetryPending = bridgeTeardownRetryCoordinator.pending,
                terminalStopPending = explicitStopRequested.get(),
            )
        ) {
            rejectColdAuxiliaryCommand(intent, startId)
            return START_NOT_STICKY
        }
        try {
            when (action) {
                ACTION_START -> {
                    if (!publishForegroundIfOwner(
                            state = "Starting",
                            publishStartingStatus = true,
                            foregroundAlreadyPublished = foregroundStart,
                        )
                    ) {
                        stopSelfWhenBridgeReleased(startId, "rejected VPN start")
                        return START_NOT_STICKY
                    }
                    startFromIntent(intent)
                }
                ACTION_STOP -> requestStopVpn()
                ACTION_UPDATE_OUTBOUNDS -> {
                    if (!publishForegroundIfOwner("Running")) {
                        stopSelfWhenBridgeReleased(startId, "rejected connection update")
                        return START_NOT_STICKY
                    }
                    requestOutboundUpdate(intent)
                }
                ACTION_TCPING_OUTBOUNDS -> requestOutboundTcping(intent)
                ACTION_APPLY_RUNTIME_SETTINGS -> requestRuntimeSettingsApply(
                    reason = if (intent.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false)) {
                        "route rules changed"
                    } else {
                        "runtime settings changed"
                    },
                    forceRestart = intent.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false),
                )
                ACTION_UPDATE_FLOW_ANALYSIS -> requestFlowAnalysisUpdate()
                ACTION_REFRESH_CLIENT_IPS -> requestBridgeClientIpsRefresh()
                else -> {
                    if (!publishForegroundIfOwner(
                            state = "Starting",
                            publishStartingStatus = true,
                            foregroundAlreadyPublished = foregroundStart,
                        )
                    ) {
                        stopSelfWhenBridgeReleased(startId, "rejected VPN restore")
                        return START_NOT_STICKY
                    }
                    requestRestoreLastRunningConfig()
                }
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val message = "VPN service command ${action ?: "restore"} failed: ${failureDescription(error)}"
            if (action == ACTION_START || action == null) {
                val (generation, accepted) = synchronized(lifecycleCommandLock) {
                    if (destroyed.get() || !isActiveServiceOwner()) {
                        return START_NOT_STICKY
                    }
                    TcptunState.error(message)
                    stopping = true
                    val generation = lifecycleGeneration.incrementAndGet()
                    generation to executeLifecycleTask("failed command cleanup") {
                        stopVpn(
                            setStopped = false,
                            globalStateOwner = {
                                generation == lifecycleGeneration.get() &&
                                    !destroyed.get() &&
                                    isActiveServiceOwner()
                            },
                            globalStateCommitLock = lifecycleCommandLock,
                        )
                    }
                }
                if (!accepted) {
                    runIfLifecycleCommandOwner(generation) {
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
        return if (action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun serviceCommandKind(action: String?): ServiceCommandKind = when (action) {
        ACTION_STOP -> ServiceCommandKind.Stop
        ACTION_UPDATE_OUTBOUNDS -> ServiceCommandKind.UpdateConnections
        ACTION_TCPING_OUTBOUNDS,
        ACTION_APPLY_RUNTIME_SETTINGS,
        ACTION_UPDATE_FLOW_ANALYSIS,
        ACTION_REFRESH_CLIENT_IPS,
        -> ServiceCommandKind.Auxiliary
        else -> ServiceCommandKind.StartOrRestore
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
                    lifecycleGeneration = lifecycleGeneration.get(),
                    persistentCommandGeneration = persistentCommandGeneration.get(),
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
            if (publishStartingStatus) TcptunState.setStatus("Starting")
            if (!foregroundAlreadyPublished) startVpnForeground(state)
            true
        }
    }

    private fun executeLifecycleTask(
        taskName: String,
        onFailure: (Throwable) -> Unit = { error ->
            if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
        },
        task: () -> Unit,
    ): Boolean {
        if (destroyed.get()) return false
        lifecycleWorkInFlight.incrementAndGet()
        val accepted = executeCrashGuarded(lifecycleExecutor, taskName, onFailure) {
            try {
                if (!destroyed.get()) task()
            } finally {
                lifecycleWorkInFlight.decrementAndGet()
            }
        }
        if (!accepted) lifecycleWorkInFlight.decrementAndGet()
        return accepted
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

    private fun startFromIntent(intent: Intent) {
        synchronized(lifecycleCommandLock) {
            explicitStopRequested.set(false)
            bridgeRecoveryTask.cancel()
            bridgeRecoveryCoordinator.resetRecovery()
            val generation = lifecycleGeneration.incrementAndGet()
            val profileMutationRevision = ProfileStore.currentMutationRevision()
            executeLifecycleTask(
                taskName = "VPN start",
                onFailure = { error ->
                    val handled = runIfLifecycleCommandOwner(generation) {
                        TcptunState.error(failureDescription(error))
                        stopping = true
                        stopSelfWhenBridgeReleased(reason = "VPN start task failure")
                    }
                    if (!handled && !destroyed.get()) {
                        TcptunState.appendLog("stale VPN start failed: ${failureDescription(error)}")
                    }
                },
            ) {
                if (generation != lifecycleGeneration.get()) return@executeLifecycleTask
                startFromIntentNow(
                    intent,
                    generation,
                    expectedProfileMutationRevision = profileMutationRevision,
                )
            }
        }
    }

    private fun startFromIntentNow(
        intent: Intent,
        generation: Int,
        expectedProfileMutationRevision: Long,
        preserveDesiredStateOnFailure: Boolean = false,
        commandOwner: () -> Boolean = {
            generation == lifecycleGeneration.get() &&
                !destroyed.get() &&
                !explicitStopRequested.get() &&
                isActiveServiceOwner()
        },
    ) {
        if (!commandOwner()) return
        try {
            if (tun != null || bridgeResources.hasOwnedResources) {
                TcptunState.appendLog("updating active VPN connections")
                stopVpn(
                    setStopped = false,
                    clearSavedConfig = false,
                    stopSelfService = false,
                    propagateBridgeStopFailure = true,
                    globalStateOwner = {
                        commandOwner() && isActiveServiceOwner()
                    },
                    globalStateCommitLock = lifecycleCommandLock,
                )
            }
            if (!commandOwner()) return
            stopping = false
            cancelPendingBridgeRestart()
            bridgeRecoveryCoordinator.resetRestartCooldown()
            run {
                if (!commandOwner()) return
                val json = intent.getStringExtra(EXTRA_CONFIG)
                    ?.takeIf { it.length <= MaxVpnCommandPayloadLength }
                    ?: error("missing VPN config")
                val rawPlan = intent.getStringExtra(EXTRA_PROFILE_PLAN)
                    ?.takeIf { it.length <= MAX_SAVED_RUNNING_PLAN_LENGTH }
                    ?: error("missing or invalid VPN profile plan")
                require(
                    isVpnCommandPayloadWithinLimit(
                        configLength = json.length,
                        planLength = rawPlan.length,
                        settingsPayloadLength = 0,
                    ),
                ) {
                    "VPN command payload is too large"
                }
                val plan = runRecoverableCatching {
                    requireSafeJsonNesting(rawPlan)
                    ProfileRunPlan.fromJson(JSONObject(rawPlan))
                }.getOrNull() ?: error("missing or invalid VPN profile plan")
                val runtimeSettings = runtimeSettingsSnapshotFromIntent(intent)
                    ?: readRuntimeSettings(this)
                val desiredPlanJson = encodeDesiredRunningPlan(plan)
                applyCachedRuntimeSettings(runtimeSettings)
                val startingPublished = synchronized(lifecycleCommandLock) {
                    if (!commandOwner()) {
                        false
                    } else {
                        TcptunState.setStatus("Starting")
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
                tunMtu = runtimeSettings.mtu
                if (!commandOwner()) {
                    stopVpn(
                        setStopped = false,
                        clearSavedConfig = false,
                        stopSelfService = false,
                        globalStateOwner = { false },
                    )
                    return
                }
                startBridge(json, plan, vpnTun, runtimeSettings.mtu, commandOwner)
                if (!commandOwner()) {
                    stopVpn(
                        setStopped = false,
                        clearSavedConfig = false,
                        stopSelfService = false,
                        globalStateOwner = { false },
                    )
                    return
                }
                TcptunState.resetProfileHealthForBridgeEpoch(bridgeResources.activeEpoch, plan.activeProfiles)
                val profileStateAligned = ProfileStore.alignActiveIdsWithPlanIfCurrent(
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
                    stopVpn(
                        setStopped = false,
                        clearSavedConfig = false,
                        stopSelfService = false,
                        globalStateOwner = { false },
                    )
                    return
                }
                val authoritativeSnapshot = ProfileStore.snapshot(this)
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
                if (authoritativePlan != plan) {
                    // Build before claiming the next generation. A config error
                    // must still be handled by the current command owner.
                    val replacementIntent = authoritativePlan?.let { startIntent(this, it) }
                    val replacementGeneration = synchronized(lifecycleCommandLock) {
                        if (commandOwner()) lifecycleGeneration.incrementAndGet() else null
                    }
                    if (replacementGeneration == null) {
                        stopVpn(
                            setStopped = false,
                            clearSavedConfig = false,
                            stopSelfService = false,
                            globalStateOwner = { false },
                        )
                        return
                    }
                    if (replacementIntent == null) {
                        TcptunState.appendLog("VPN startup cancelled: no profile remains active")
                        stopVpn(
                            globalStateOwner = {
                                replacementGeneration == lifecycleGeneration.get() &&
                                    !destroyed.get() &&
                                    isActiveServiceOwner()
                            },
                            globalStateCommitLock = lifecycleCommandLock,
                        )
                    } else {
                        TcptunState.appendLog(
                            "profile state changed during VPN startup; queuing the saved configuration",
                        )
                        val accepted = executeLifecycleTask(
                            taskName = "VPN profile reconciliation",
                            onFailure = { error ->
                                val handled = runIfLifecycleCommandOwner(replacementGeneration) {
                                    TcptunState.error(failureDescription(error))
                                    stopSelfWhenBridgeReleased(
                                        reason = "profile reconciliation failure",
                                    )
                                }
                                if (!handled && !destroyed.get()) {
                                    TcptunState.appendLog(
                                        "stale profile reconciliation failed: ${failureDescription(error)}",
                                    )
                                }
                            },
                        ) {
                            startFromIntentNow(
                                intent = replacementIntent,
                                generation = replacementGeneration,
                                expectedProfileMutationRevision = authoritativeSnapshot.mutationRevision,
                            )
                        }
                        if (!accepted) {
                            runIfLifecycleCommandOwner(replacementGeneration) {
                                TcptunState.error("VPN profile reconciliation could not be queued")
                                stopSelfWhenBridgeReleased(
                                    reason = "profile reconciliation rejection",
                                )
                            }
                        }
                    }
                    return
                }
                val committed = ProfileStore.runIfRevisionCurrent(
                    expectedMutationRevision = authoritativeSnapshot.mutationRevision,
                    commitLock = lifecycleCommandLock,
                    canCommit = commandOwner,
                ) {
                    runningPlan = plan
                    deferredServiceStopGate.clear()
                    publishDesiredRunningPlan(this, desiredPlanJson)
                    TcptunState.setStatus("Running")
                    TcptunState.setConnectionsReady(true)
                    bridgeRecoveryCoordinator.resetRecovery()
                    bridgeRecoveryTask.cancel()
                    // Publish Running and install its monitor as one ownership
                    // commit. A connection update may claim the next lifecycle
                    // generation immediately after this block, but it must not
                    // be able to observe a Running session without a monitor.
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
                    stopVpn(
                        setStopped = false,
                        clearSavedConfig = false,
                        stopSelfService = false,
                        globalStateOwner = { false },
                    )
                    return
                }
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            if (!commandOwner()) {
                TcptunState.appendLog("VPN start cancelled")
                stopVpn(
                    setStopped = false,
                    clearSavedConfig = false,
                    stopSelfService = false,
                    globalStateOwner = { false },
                )
                return
            }
            val failure = failureDescription(error)
            if (!preserveDesiredStateOnFailure) {
                ProfileStore.clearActiveIfCurrent(
                    context = this,
                    expectedMutationRevision = expectedProfileMutationRevision,
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
                        cleanupStep("clear failed VPN desired state") {
                            clearDesiredRunningConfig(this)
                        }
                    }
                }
            }
            stopVpn(
                setStopped = false,
                clearSavedConfig = false,
                stopSelfService = false,
                globalStateOwner = { false },
            )
            if (preserveDesiredStateOnFailure) {
                throw IllegalStateException(failure, error)
            }
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
        }
    }

    private fun requestRestoreLastRunningConfig() {
        synchronized(lifecycleCommandLock) {
            explicitStopRequested.set(false)
            val generation = lifecycleGeneration.incrementAndGet()
            val profileMutationRevision = ProfileStore.currentMutationRevision()
            executeLifecycleTask(
                taskName = "VPN restore",
                onFailure = { error ->
                    val handled = runIfLifecycleCommandOwner(generation) {
                        TcptunState.error(failureDescription(error))
                        stopSelfWhenBridgeReleased(reason = "VPN restore task failure")
                    }
                    if (!handled && !destroyed.get()) {
                        TcptunState.appendLog("stale VPN restore failed: ${failureDescription(error)}")
                    }
                },
            ) {
                if (generation != lifecycleGeneration.get()) return@executeLifecycleTask
                restoreLastRunningConfig(generation, profileMutationRevision)
            }
        }
    }

    private fun restoreLastRunningConfig(generation: Int, profileMutationRevision: Long) {
        if (tun != null) return
        val plan = readDesiredRunningPlan(this) ?: run {
            runIfLifecycleCommandOwner(generation) {
                stopSelfWhenBridgeReleased(reason = "no VPN state to restore")
            }
            return
        }
        if (runRecoverableCatching { plan.normalized() }.isFailure) {
            runIfLifecycleCommandOwner(generation) {
                cleanupStep("clear invalid restored VPN state") {
                    clearDesiredRunningConfig(this)
                }
                stopSelfWhenBridgeReleased(reason = "invalid VPN restore state")
            }
            return
        }
        if (generation != lifecycleGeneration.get()) return
        val intent = startIntent(this, plan)
        if (generation != lifecycleGeneration.get()) return
        TcptunState.appendLog("restoring VPN after service restart")
        startFromIntentNow(
            intent,
            generation,
            expectedProfileMutationRevision = profileMutationRevision,
        )
    }

    private fun requestOutboundUpdate(intent: Intent) {
        synchronized(lifecycleCommandLock) {
            if (explicitStopRequested.get()) {
                TcptunState.appendLog("connection update ignored: VPN stop is pending")
                return
            }
            val lifecycleGeneration = lifecycleGeneration.get()
            val updateGeneration = connectionUpdateTracker.begin()
            val profileMutationRevision = ProfileStore.currentMutationRevision()
            TcptunState.setConnectionsReady(false)
            executeLifecycleTask(
                taskName = "connection update",
                onFailure = { error ->
                    TcptunState.appendLog(failureDescription(error))
                    markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
                },
            ) {
                if (
                    lifecycleGeneration != this.lifecycleGeneration.get() ||
                    !connectionUpdateTracker.isLatest(updateGeneration) ||
                    stopping || destroyed.get() || explicitStopRequested.get() ||
                    !isActiveServiceOwner()
                ) {
                    return@executeLifecycleTask
                }
                updateOutboundsNow(
                    intent,
                    lifecycleGeneration,
                    updateGeneration,
                    profileMutationRevision,
                )
            }
        }
    }

    private fun updateOutboundsNow(
        intent: Intent,
        lifecycleGeneration: Int,
        updateGeneration: Int,
        profileMutationRevision: Long,
    ) {
        val commandOwner = {
            lifecycleGeneration == this.lifecycleGeneration.get() &&
                connectionUpdateTracker.isLatest(updateGeneration) &&
                !explicitStopRequested.get() &&
                !destroyed.get() &&
                isActiveServiceOwner()
        }
        val nextPlan = intent.getStringExtra(EXTRA_PROFILE_PLAN)
            ?.takeIf { it.length <= MAX_SAVED_RUNNING_PLAN_LENGTH }
            ?.let { raw ->
                runRecoverableCatching {
                    requireSafeJsonNesting(raw)
                    ProfileRunPlan.fromJson(JSONObject(raw))
                }.getOrNull()
            }
            ?: run {
                TcptunState.appendLog("connection update ignored: invalid profile plan")
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
                return
            }
        val currentPlan = runningPlan
        val changedIds = currentPlan?.let { current ->
            (current.activeIds - nextPlan.activeIds) + (nextPlan.activeIds - current.activeIds)
        }.orEmpty()
        if (
            tun == null || currentPlan == null ||
            currentPlan.profiles != nextPlan.profiles
        ) {
            TcptunState.appendLog("reloading VPN connection configuration")
            val claimedGeneration = synchronized(lifecycleCommandLock) {
                if (commandOwner()) this.lifecycleGeneration.incrementAndGet() else null
            }
            claimedGeneration?.let { generation ->
                startFromIntentNow(intent, generation, profileMutationRevision) {
                    generation == this.lifecycleGeneration.get() &&
                        !explicitStopRequested.get() &&
                        !destroyed.get() &&
                        isActiveServiceOwner()
                }
            }
            return
        }
        if (changedIds.isEmpty()) {
            val desiredPlanJson = runRecoverableCatching { encodeDesiredRunningPlan(nextPlan) }
                .getOrElse { error ->
                    TcptunState.appendLog(
                        "unchanged connection plan persistence failed: ${failureDescription(error)}",
                    )
                    markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
                    return
                }
            ProfileStore.alignActiveIdsWithPlanIfCurrent(
                context = this,
                expectedMutationRevision = null,
                plan = nextPlan,
                commitLock = lifecycleCommandLock,
                canCommit = commandOwner,
            ).onSuccess { aligned ->
                if (aligned) TcptunState.notifyProfileStateChanged()
            }
            synchronized(lifecycleCommandLock) {
                if (!commandOwner()) return
                publishDesiredRunningPlan(this, desiredPlanJson)
            }
            markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
            return
        }

        val changedProfiles = currentPlan.profiles.filter { it.id in changedIds }
        val membershipEpoch = bridgeResources.activeEpoch
        val desiredNextPlanJson = runRecoverableCatching { encodeDesiredRunningPlan(nextPlan) }
            .getOrElse { error ->
                TcptunState.appendLog("connection update ignored: ${failureDescription(error)}")
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
                return
            }
        var nativeMutationAttempted = false
        try {
            TcptunState.setConnectionsReady(false)
            for (profile in changedProfiles) {
                if (!commandOwner()) {
                    if (nativeMutationAttempted) markMembershipStateUncertain()
                    return
                }
                nativeMutationAttempted = true
                setOutboundRunning(
                    profile = profile,
                    shouldRun = profile.id in nextPlan.activeIds,
                    expectedEpoch = membershipEpoch,
                    commandOwner = commandOwner,
                )
            }
            if (!commandOwner()) {
                if (nativeMutationAttempted) markMembershipStateUncertain()
                return
            }
            ProfileStore.alignActiveIdsWithPlanIfCurrent(
                context = this,
                expectedMutationRevision = null,
                plan = nextPlan,
                commitLock = lifecycleCommandLock,
                canCommit = commandOwner,
            ).onSuccess { aligned ->
                if (aligned) TcptunState.notifyProfileStateChanged()
            }.onFailure { profileError ->
                TcptunState.appendLog(
                    "connection profile state sync failed: ${failureDescription(profileError)}",
                )
            }
            val committed = synchronized(lifecycleCommandLock) {
                if (!commandOwner()) {
                    false
                } else {
                    runningPlan = nextPlan
                    TcptunState.initializeProfileHealth(nextPlan.activeProfiles)
                    publishDesiredRunningPlan(this, desiredNextPlanJson)
                    true
                }
            }
            if (!committed) {
                markMembershipStateUncertain()
                return
            }
            // Pool membership changed: re-score after Start/StopOutbound settles.
            synchronized(lifecycleCommandLock) {
                if (commandOwner()) {
                    requestMemberHealthProbe(
                        reason = "active connections changed",
                        delayMs = BridgeHealthPolicy.MEMBER_HEALTH_MEMBERSHIP_DELAY_MS,
                    )
                }
            }
            synchronized(lifecycleCommandLock) {
                if (commandOwner()) updateNotification(runningNotificationState(nextPlan))
            }
            markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            TcptunState.appendLog("connection update failed: ${failureDescription(error)}")
            if (!commandOwner()) {
                if (nativeMutationAttempted) markMembershipStateUncertain()
                TcptunState.appendLog("connection rollback skipped: a newer command is pending")
                return
            }
            var rollbackSuperseded = false
            var rollbackFailed = false
            try {
                for (profile in changedProfiles) {
                    if (!commandOwner()) {
                        rollbackSuperseded = true
                        break
                    }
                    setOutboundRunning(
                        profile = profile,
                        shouldRun = profile.id in currentPlan.activeIds,
                        expectedEpoch = membershipEpoch,
                        commandOwner = commandOwner,
                    )
                }
            } catch (rollbackError: Throwable) {
                if (rollbackError.isFatalProcessError()) throw rollbackError
                rollbackFailed = true
                TcptunState.appendLog("connection update rollback failed: ${failureDescription(rollbackError)}")
            }
            if (rollbackFailed) {
                recoverFromFailedOutboundRollback(
                    currentPlan,
                    lifecycleGeneration,
                    updateGeneration,
                    profileMutationRevision,
                )
                return
            }
            if (
                rollbackSuperseded || !commandOwner()
            ) {
                if (nativeMutationAttempted) markMembershipStateUncertain()
                TcptunState.appendLog("connection state rollback skipped: a newer command is pending")
                return
            }
            runningPlan = currentPlan
            val desiredCurrentPlanJson = runRecoverableCatching {
                encodeDesiredRunningPlan(currentPlan)
            }.onFailure { desiredError ->
                TcptunState.appendLog(
                    "connection desired-plan rollback failed: ${failureDescription(desiredError)}",
                )
            }.getOrNull()
            if (desiredCurrentPlanJson != null) {
                synchronized(lifecycleCommandLock) {
                    if (commandOwner()) publishDesiredRunningPlan(this, desiredCurrentPlanJson)
                }
            }
            try {
                val reverted = ProfileStore.replaceActiveIdsIfCurrent(
                    context = this,
                    expectedMutationRevision = null,
                    expectedActiveIds = nextPlan.activeIds,
                    replacementActiveIds = currentPlan.activeIds,
                    commitLock = lifecycleCommandLock,
                    canCommit = commandOwner,
                ).getOrThrow()
                if (reverted) {
                    TcptunState.notifyProfileStateChanged()
                } else {
                    TcptunState.appendLog("connection state rollback skipped: persisted state is newer")
                }
                synchronized(lifecycleCommandLock) {
                    if (commandOwner()) {
                        try {
                            updateNotification(runningNotificationState(currentPlan))
                        } catch (notificationError: Throwable) {
                            if (notificationError.isFatalProcessError()) throw notificationError
                            TcptunState.appendLog(
                                "connection rollback notification failed: ${failureDescription(notificationError)}",
                            )
                        }
                    }
                }
            } catch (stateError: Throwable) {
                if (stateError.isFatalProcessError()) throw stateError
                TcptunState.appendLog("connection state rollback failed: ${failureDescription(stateError)}")
            } finally {
                markConnectionsReadyAfterUpdate(lifecycleGeneration, updateGeneration)
            }
        }
    }

    private fun markMembershipStateUncertain() {
        runningPlan = null
        cancelPendingBridgeRestart()
        synchronized(lifecycleCommandLock) {
            if (!destroyed.get() && isActiveServiceOwner()) {
                TcptunState.setConnectionsReady(false)
            }
        }
    }

    /** A partial native membership rollback is unsafe; rebuild the last known-good plan. */
    private fun recoverFromFailedOutboundRollback(
        plan: ProfileRunPlan,
        lifecycleGeneration: Int,
        updateGeneration: Int,
        profileMutationRevision: Long,
    ) {
        if (
            lifecycleGeneration != this.lifecycleGeneration.get() ||
            !connectionUpdateTracker.isLatest(updateGeneration) ||
            explicitStopRequested.get() ||
            destroyed.get() ||
            !isActiveServiceOwner()
        ) {
            TcptunState.appendLog("connection recovery delegated to a newer command")
            return
        }
        val recoveryIntent = runRecoverableCatching { startIntent(this, plan) }.getOrElse { error ->
            val shouldCleanup = synchronized(lifecycleCommandLock) {
                if (
                    lifecycleGeneration != this.lifecycleGeneration.get() ||
                    !connectionUpdateTracker.isLatest(updateGeneration) ||
                    destroyed.get() ||
                    !isActiveServiceOwner()
                ) {
                    false
                } else {
                    TcptunState.error(
                        "connection recovery preparation failed: ${failureDescription(error)}",
                    )
                    true
                }
            }
            if (shouldCleanup) {
                stopVpn(
                    setStopped = false,
                    clearSavedConfig = false,
                    stopSelfService = false,
                    globalStateOwner = { false },
                )
                synchronized(lifecycleCommandLock) {
                    if (
                        lifecycleGeneration == this.lifecycleGeneration.get() &&
                        connectionUpdateTracker.isLatest(updateGeneration) &&
                        !destroyed.get() &&
                        isActiveServiceOwner()
                    ) {
                        stopSelfWhenBridgeReleased(
                            reason = "connection recovery preparation failure",
                        )
                    }
                }
            }
            return
        }
        val recoveryGeneration = synchronized(lifecycleCommandLock) {
            if (
                lifecycleGeneration != this.lifecycleGeneration.get() ||
                !connectionUpdateTracker.isLatest(updateGeneration) ||
                explicitStopRequested.get() ||
                destroyed.get() ||
                !isActiveServiceOwner()
            ) {
                null
            } else {
                this.lifecycleGeneration.incrementAndGet()
            }
        } ?: run {
            TcptunState.appendLog("connection recovery delegated to a newer command")
            return
        }
        TcptunState.appendLog("rebuilding VPN after incomplete connection rollback")
        startFromIntentNow(
            recoveryIntent,
            recoveryGeneration,
            commandOwner = {
                recoveryGeneration == this.lifecycleGeneration.get() &&
                    !explicitStopRequested.get() &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
            },
            expectedProfileMutationRevision = profileMutationRevision,
        )
    }

    private fun markConnectionsReadyAfterUpdate(
        lifecycleGeneration: Int,
        updateGeneration: Int,
    ) = synchronized(lifecycleCommandLock) {
        if (
            lifecycleGeneration == this.lifecycleGeneration.get() &&
            !destroyed.get() &&
            !explicitStopRequested.get() &&
            isActiveServiceOwner()
        ) {
            connectionUpdateTracker.runIfLatest(updateGeneration) {
                if (!stopping && tun != null && TcptunState.status == "Running") {
                    TcptunState.setConnectionsReady(true)
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
                TcptunState.status != "Running" ||
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
        registerUnderlyingNetworkCallback()
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
        synchronized(underlyingNetworkRegistrationLock) {
            if (underlyingNetworkCallbackRegistered || destroyed.get()) return
            val callbackEpoch = underlyingNetworkCallbackEpochGate.activateNext()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runNetworkCallback(callbackEpoch, "available") {
                        connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                            updateUnderlyingNetwork(network, capabilities)
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    runNetworkCallback(callbackEpoch, "capabilities changed") {
                        updateUnderlyingNetwork(network, capabilities)
                    }
                }

                override fun onLost(network: Network) {
                    runNetworkCallback(callbackEpoch, "lost") {
                        val selection = underlyingNetworkSelection.remove(network)
                        applyUnderlyingNetwork(selection, "underlying network lost")
                    }
                }
            }
            underlyingNetworkCallback = callback
            underlyingNetworkCallbackEpoch = callbackEpoch
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            try {
                connectivity.registerNetworkCallback(request, callback)
                underlyingNetworkCallbackRegistered = true
                TcptunState.appendLog("underlying network callback registered")
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                underlyingNetworkCallbackRegistered = false
                underlyingNetworkCallbackEpochGate.invalidate(callbackEpoch)
                if (underlyingNetworkCallback === callback) {
                    underlyingNetworkCallback = null
                    underlyingNetworkCallbackEpoch = 0L
                }
                TcptunState.appendLog("underlying network callback unavailable: ${failureDescription(error)}")
            }
        }
    }

    private fun runNetworkCallback(callbackEpoch: Long, event: String, action: () -> Unit) {
        underlyingNetworkCallbackEpochGate.runIfActive(callbackEpoch) callback@{
            synchronized(lifecycleCommandLock) {
                if (destroyed.get() || stopping || !isActiveServiceOwner()) return@callback
            }
            try {
                action()
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                synchronized(lifecycleCommandLock) {
                    if (!destroyed.get() && isActiveServiceOwner()) {
                        TcptunState.appendLog(
                            "underlying network $event callback failed: ${failureDescription(error)}",
                        )
                    }
                }
            }
        }
    }

    private fun unregisterUnderlyingNetworkCallback(updateGlobalDiagnostics: Boolean = true) {
        val callback = synchronized(underlyingNetworkRegistrationLock) {
            if (!underlyingNetworkCallbackRegistered) return
            underlyingNetworkCallbackRegistered = false
            val callback = underlyingNetworkCallback
            val callbackEpoch = underlyingNetworkCallbackEpoch
            underlyingNetworkCallback = null
            underlyingNetworkCallbackEpoch = 0L
            underlyingNetworkCallbackEpochGate.invalidate(callbackEpoch)
            underlyingNetworkSelection.clear()
            callback
        }
        if (callback != null && connectivityDelegate.isInitialized()) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog("underlying network callback unregister failed: ${failureDescription(error)}")
            }
        }
        if (updateGlobalDiagnostics) updateUnderlyingDiagnostics(null)
    }

    private fun updateUnderlyingNetwork(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return
        }
        val selection = underlyingNetworkSelection.update(
            network,
            underlyingNetworkScore(
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ),
        )
        applyUnderlyingNetwork(selection, "underlying network changed")
    }

    private fun applyUnderlyingNetwork(network: Network?, reason: String) {
        synchronized(lifecycleCommandLock) {
            if (destroyed.get() || stopping || !isActiveServiceOwner()) return
        }
        // Drop a stale callback whose selection was superseded before it
        // reached this method on another ConnectivityManager thread.
        val selectionClaim = underlyingNetworkSelection.claim(network) ?: return
        synchronized(lifecycleCommandLock) {
            if (destroyed.get() || stopping || !isActiveServiceOwner()) return
            updateUnderlyingDiagnostics(network)
            TcptunState.appendLog("underlying network selected: ${network ?: "none"}")
            try {
                setUnderlyingNetworks(network?.let { arrayOf(it) })
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog("set underlying network failed: ${failureDescription(error)}")
            }
            if (tun != null && !stopping) {
                if (BridgeHealthPolicy.shouldRestartForNetworkHandover(
                        initialSelection = selectionClaim.initial,
                        networkAvailable = network != null,
                        vpnRunning = TcptunState.status == "Running",
                    )
                ) {
                    // Restart path reseeds member probes after "vpn started".
                    requestBridgeRestart(
                        reason = reason,
                        settleDelayMs = BridgeHealthPolicy.NETWORK_HANDOVER_SETTLE_MS,
                    )
                } else {
                    requestMemberHealthProbe(
                        reason = reason,
                        delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun requestStopVpn() {
        cleanupStep("clear TCPing") { TcptunState.clearTcping() }
        val (command, accepted) = synchronized(lifecycleCommandLock) {
            explicitStopRequested.set(true)
            val generation = lifecycleGeneration.incrementAndGet()
            // Claiming the generation and persisting desired=false share the
            // same lock as successful-start publication, so an older start
            // cannot write desired=true after this stop request.
            cleanupStep("persist requested stopped state") { clearDesiredRunningConfig(this) }
            val profileMutationRevision = ProfileStore.currentMutationRevision()
            stopping = true
            cleanupStep("set stopping state") { TcptunState.setStatus("Stopping") }
            cleanupStep("disable connections") { TcptunState.setConnectionsReady(false) }
            cancelPendingBridgeRestart()
            bridgeReadyWaiter.getAndSet(null)?.future?.completeExceptionally(
                IllegalStateException("tcptun stop requested"),
            )
            bridgeRecoveryTask.cancel()
            bridgeRecoveryCoordinator.resetRecovery()
            (generation to profileMutationRevision) to executeLifecycleTask(
                taskName = "VPN stop",
                onFailure = { error ->
                    val handled = runIfLifecycleCommandOwner(generation) {
                        TcptunState.error(failureDescription(error))
                        stopSelfWhenBridgeReleased(reason = "VPN stop task failure")
                    }
                    if (!handled) {
                        TcptunState.appendLog("stale VPN stop failed: ${failureDescription(error)}")
                    }
                },
            ) {
                if (generation != lifecycleGeneration.get()) return@executeLifecycleTask
                ProfileStore.clearActiveIfCurrent(
                    context = this,
                    expectedMutationRevision = profileMutationRevision,
                    commitLock = lifecycleCommandLock,
                    canCommit = {
                        generation == lifecycleGeneration.get() &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                ).onSuccess { cleared ->
                    if (cleared) TcptunState.notifyProfileStateChanged()
                }.onFailure { error ->
                    TcptunState.appendLog("clear active profiles failed: ${failureDescription(error)}")
                }
                stopVpn(
                    globalStateOwner = {
                        generation == lifecycleGeneration.get() &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                    globalStateCommitLock = lifecycleCommandLock,
                )
            }
        }
        val (generation, profileMutationRevision) = command
        if (!accepted) {
            startCrashGuardedThread(
                threadName = "TcptunStopPersistenceFallback",
                onFailure = { error -> cleanupStep("stop persistence fallback") { throw error } },
            ) {
                ProfileStore.clearActiveIfCurrent(
                    context = this,
                    expectedMutationRevision = profileMutationRevision,
                    commitLock = lifecycleCommandLock,
                    canCommit = {
                        generation == lifecycleGeneration.get() &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                ).onSuccess { cleared ->
                    if (cleared) TcptunState.notifyProfileStateChanged()
                }
                synchronized(lifecycleCommandLock) {
                    if (
                        generation == lifecycleGeneration.get() &&
                        !destroyed.get() &&
                        isActiveServiceOwner()
                    ) {
                        cleanupStep("clear desired VPN config") { clearDesiredRunningConfig(this) }
                    }
                }
            }
            runIfLifecycleCommandOwner(generation) {
                stopSelfWhenBridgeReleased(reason = "VPN stop task rejection")
            }
        }
    }

    private fun closeTunAfterBridgeStopAttempt() {
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
                    !explicitStopRequested.get() &&
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
    ): Boolean {
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
            stopping = true
            cancelPendingBridgeRestart()
            if (setStopped) {
                cleanupGlobalStep("set stopping state") { TcptunState.setStatus("Stopping") }
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
            tunMtu = DEFAULT_VPN_MTU
            activeSocksPort = DEFAULT_SOCKS_PORT
            activeSocksUsername = ""
            activeSocksPassword = ""
            activeLocalProxyProtocol = DefaultLocalProxyProtocol
            activeSocksListenAll = false
            activeRouteLocalProxyTraffic = false
            activeDefaultOutbound = DefaultOutboundDynamicPool
            activeFlowAnalysisApp = ""
            activeLogLevel = DefaultLogLevel
            powerSavingMode = true
            lastMemberHealthProbeAtElapsedMs = 0L
            memberHealthBatchSelector.clear()
            runGlobalStep {
                synchronized(memberHealthDelayLock) {
                    memberHealthProbeNotBeforeElapsedMs.set(0L)
                    memberHealthProbeScheduleGeneration.incrementAndGet()
                    memberHealthDelayFuture.getAndSet(null)?.cancel(false)
                }
            }
            runningPlan = null
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
                    cleanupGlobalStep("set stopped state") { TcptunState.setStatus("Stopped") }
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
                            currentLifecycleGeneration = lifecycleGeneration.get(),
                            currentPersistentCommandGeneration = persistentCommandGeneration.get(),
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
                    )
                }
            }
            if (propagateBridgeStopFailure) {
                bridgeStopFailure?.let { error ->
                    throw IllegalStateException("VPN reload aborted because the bridge did not stop cleanly", error)
                }
            }
            disposition.resourcesReleased
        }
    }

    private fun scheduleBridgeTeardownRetry(
        setStopped: Boolean,
        clearSavedConfig: Boolean,
        stopSelfService: Boolean,
        globalStateOwner: (() -> Boolean)?,
        globalStateCommitLock: Any?,
    ) {
        if (destroyed.get() || !bridgeResources.hasOwnedResources) return
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
            if (destroyed.get() || !bridgeResources.hasOwnedResources) return@scheduleCrashGuardedFuture
            TcptunState.appendLog(
                "retrying tcptun cleanup (${retry.attempt}/${retry.maxAttempts})",
            )
            stopVpn(
                setStopped = setStopped,
                clearSavedConfig = clearSavedConfig,
                stopSelfService = stopSelfService,
                globalStateOwner = globalStateOwner,
                globalStateCommitLock = globalStateCommitLock,
            )
        }
        if (future == null) {
            TcptunState.appendLog("tcptun teardown retry could not be scheduled; service retained")
        } else {
            bridgeTeardownRetryTask.replace(future)
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
            activeMonitorWakeCallback.compareAndSet(monitorWakeCallback, null)
            synchronized(lifecycleCommandLock) {
                stopping = true
                lifecycleGeneration.incrementAndGet()
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
            var lifecycleStopped = lifecycleExecutor.awaitTermination(
                DESTROY_EXECUTOR_WAIT_MS,
                TimeUnit.MILLISECONDS,
            )
            if (!lifecycleStopped) {
                TcptunState.appendLog("tcptun lifecycle is still exiting; destroy cleanup deferred")
                lifecycleStopped = lifecycleExecutor.awaitTermination(
                    DEFERRED_DESTROY_WAIT_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
            if (!lifecycleStopped) {
                TcptunState.appendLog(
                    "tcptun lifecycle task did not exit; teardown will remain queued off the main thread",
                )
            }

            val teardownThread = startCrashGuardedThread(
                threadName = "TcptunDestroyTeardown",
                onFailure = { error -> cleanupStep("VPN destroy teardown") { throw error } },
            ) {
                stopVpn(
                    setStopped = TcptunState.status != "Error",
                    clearSavedConfig = false,
                    stopSelfService = false,
                    globalStateOwner = ::isActiveServiceOwner,
                )
                if (closeBridgeEngine()) {
                    TcptunState.appendLog("tcptun destroy cleanup completed")
                } else {
                    TcptunState.appendLog(
                        "tcptun destroy cleanup incomplete; engine retained for safe process teardown",
                    )
                }
            } ?: return@coordinator

            try {
                teardownThread.join(DESTROY_NATIVE_TEARDOWN_WAIT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                teardownThread.interrupt()
                return@coordinator
            }
            if (teardownThread.isAlive) {
                teardownThread.interrupt()
                TcptunState.appendLog("tcptun native destroy cleanup timed out; main thread remains responsive")
            }
        }
        if (coordinator == null) {
            cleanupStep("start VPN destroy coordinator") {
                throw IllegalStateException("destroy coordinator thread could not be started")
            }
        }
    }

    private fun closeBridgeEngine(): Boolean {
        if (!bridgeDelegate.isInitialized()) {
            bridgeResources.engineClosed()
            bridgeRuntimeLease.release(serviceInstanceId)
            return true
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

    private fun applyCachedRuntimeSettings(settings: RuntimeSettings) {
        tunMtu = settings.mtu
        activeSocksPort = settings.socksPort
        activeSocksUsername = settings.socksUsername
        activeSocksPassword = settings.socksPassword
        activeLocalProxyProtocol = settings.localProxyProtocol
        activeSocksListenAll = settings.socksListenAll
        activeRouteLocalProxyTraffic = settings.routeLocalProxyTraffic
        activeDefaultOutbound = settings.defaultOutbound
        activeFlowAnalysisApp = settings.flowAnalysisApp
        activeLogLevel = settings.logLevel
        powerSavingMode = settings.powerSavingMode
    }

    private fun currentStructuralRuntimeSettings(): RuntimeSettings {
        return RuntimeSettings(
            mtu = tunMtu,
            powerSavingMode = powerSavingMode,
            logLevel = activeLogLevel,
            socksPort = activeSocksPort,
            localProxyProtocol = activeLocalProxyProtocol,
            socksListenAll = activeSocksListenAll,
            socksUsername = activeSocksUsername,
            socksPassword = activeSocksPassword,
            routeLocalProxyTraffic = activeRouteLocalProxyTraffic,
            defaultOutbound = activeDefaultOutbound,
        )
    }

    private fun startBridge(
        configJson: String,
        plan: ProfileRunPlan,
        vpnTun: android.os.ParcelFileDescriptor,
        mtu: Int,
        commandOwner: () -> Boolean,
    ) {
        startBridgeSession(
            configJson = configJson,
            disabledOutboundTags = initiallyDisabledOutboundTags(plan),
            readyTimeoutMs = BRIDGE_READY_TIMEOUT_MS,
            vpnTun = vpnTun,
            mtu = mtu,
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
                        logLevel = activeLogLevel,
                    ),
                    callbacks = BridgeSessionCallbacks(
                        onLog = { line ->
                            if (!destroyed.get()) TcptunState.appendLog(line)
                        },
                        onStatus = { eventJson ->
                            if (!destroyed.get()) onBridgeStatusEvent(epoch, eventJson)
                        },
                        protectSocket = { fd -> !destroyed.get() && !stopping && protect(fd) },
                        identifyApp = { flow ->
                            if (destroyed.get() || stopping) null else appIdentityProvider.identify(flow)
                        },
                        configureFlowAnalysis = { configureFlowAnalysis(activeFlowAnalysisApp, epoch) },
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
                        // Keep callbacks and the stop obligation alive. onDestroy
                        // will retry through Stop/Close; clearing Java proxies here
                        // would violate tcptun-go's active-runtime ownership contract.
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

    private fun configureFlowAnalysis(packageName: String, epoch: Long) {
        val normalized = normalizeFlowAnalysisApp(packageName)
        TcptunState.setFlowAnalysisApp(normalized)
        appIdentityProvider.setFlowAnalysisApp(normalized)
        if (normalized.isBlank()) {
            bridge.setFlowAnalysisApp("")
            bridge.clearFlowCallback()
        } else {
            bridge.setFlowCallback { eventJson -> TcptunState.applyBridgeFlowEvent(epoch, eventJson) }
            bridge.setFlowAnalysisApp(normalized)
        }
    }

    private fun restartBridge(
        reason: String,
        claimOwner: () -> Boolean,
        commandOwner: () -> Boolean,
        globalCleanupOwner: () -> Boolean,
    ) {
        val configJson = bridgeResources.activeConfigJson ?: return
        val plan = runningPlan ?: return
        val claimed = synchronized(lifecycleCommandLock) {
            if (tun == null || !claimOwner()) {
                false
            } else {
                val now = System.currentTimeMillis()
                val remainingCooldownMs = bridgeRecoveryCoordinator.beginRestart(now)
                if (remainingCooldownMs > 0L) {
                    val waitSeconds = (
                        remainingCooldownMs / 1_000
                    ).coerceAtLeast(1)
                    TcptunState.appendLog(
                        "tcptun bridge restart skipped by cooldown: $reason; wait ${waitSeconds}s",
                    )
                    false
                } else {
                    bridgeRestarting = true
                    TcptunState.setConnectionsReady(false)
                    TcptunState.appendLog("restarting tcptun bridge transaction: $reason")
                    TcptunState.updateDiagnostics {
                        it.copy(lastRestartReason = reason, bridgeStatus = "Restarting")
                    }
                    true
                }
            }
        }
        if (!claimed) return
        try {
            stopBridge()
            if (!commandOwner()) return
            closeTunAfterBridgeStopAttempt()
            Thread.sleep(BRIDGE_RESTART_DELAY_MS)
            if (!commandOwner()) return
            claimBridgeRuntimeLease(commandOwner)
            check(commandOwner()) { "tcptun restart was superseded" }
            val replacementTun = buildTun(tunMtu)
            ownTun(replacementTun)
            startBridge(
                configJson,
                plan,
                replacementTun,
                tunMtu,
                commandOwner,
            )
            val replacementEpoch = bridgeResources.activeEpoch
            synchronized(lifecycleCommandLock) {
                if (
                    commandOwner() && replacementEpoch == bridgeResources.activeEpoch &&
                    tun === replacementTun
                ) {
                    TcptunState.appendLog("tcptun bridge transaction restarted")
                    TcptunState.setConnectionsReady(true)
                    startBridgeMonitor()
                    // The previous runtime's balance observations were discarded
                    // by the restart, so seed health after the replacement settles.
                    requestMemberHealthProbe(
                        reason = "bridge restarted: $reason",
                        delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
                    )
                }
            }
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            val cleanupClaimed = synchronized(lifecycleCommandLock) { commandOwner() }
            stopVpn(
                setStopped = false,
                clearSavedConfig = false,
                stopSelfService = false,
                globalStateOwner = { false },
                globalStateCommitLock = lifecycleCommandLock,
            )
            if (cleanupClaimed && globalCleanupOwner()) {
                scheduleBridgeRecovery(
                    plan = plan,
                    reason = reason,
                    lifecycleSnapshot = lifecycleGeneration.get(),
                    failure = error,
                )
            }
        } finally {
            bridgeRestarting = false
        }
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
        }
    }

    private fun requestBridgeRestart(
        reason: String,
        settleDelayMs: Long = 0L,
        cancelIfHealthy: Boolean = false,
    ) {
        synchronized(bridgeRestartScheduleLock) {
            if (stopping || destroyed.get()) return
            bridgeRecoveryTask.cancel()
            val token = bridgeRecoveryCoordinator.requestRestart(
                lifecycleGeneration = lifecycleGeneration.get(),
                cancelIfHealthy = cancelIfHealthy,
            )
            scheduleBridgeRestart(reason, token, settleDelayMs)
        }
    }

    private fun scheduleBridgeRestart(
        reason: String,
        token: BridgeRestartToken,
        settleDelayMs: Long = 0L,
    ): Unit = synchronized(bridgeRestartScheduleLock) {
        if (stopping || destroyed.get()) return@synchronized
        val scheduleDelayMs = bridgeRecoveryCoordinator.scheduleDelayMillis(
            token = token,
            currentLifecycleGeneration = lifecycleGeneration.get(),
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
                        !bridgeRecoveryCoordinator.isCurrent(token, lifecycleGeneration.get()) ||
                        stopping || destroyed.get()
                    ) return@restart
                }
                if (
                    !bridgeRecoveryCoordinator.isCurrent(token, lifecycleGeneration.get()) ||
                    stopping || destroyed.get() || tun == null
                ) return@restart
                val remainingMs = bridgeRecoveryCoordinator.remainingCooldownMillis(
                    System.currentTimeMillis(),
                )
                if (remainingMs > 0) {
                    scheduleBridgeRestart(reason, token)
                    return@restart
                }
                synchronized(bridgeRestartScheduleLock) {
                    if (
                        stopping || destroyed.get() ||
                        !bridgeRecoveryCoordinator.claimRestart(token, lifecycleGeneration.get())
                    ) return@restart
                }
                restartBridge(
                    reason = reason,
                    claimOwner = {
                        bridgeRecoveryCoordinator.isCurrent(token, lifecycleGeneration.get()) &&
                            !stopping &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                    commandOwner = {
                        token.lifecycleGeneration == lifecycleGeneration.get() &&
                            !stopping &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                    globalCleanupOwner = {
                        token.lifecycleGeneration == lifecycleGeneration.get() &&
                            !destroyed.get() &&
                            isActiveServiceOwner()
                    },
                )
        }
        if (future != null) bridgeRestartTask.replace(future)
    }

    private fun scheduleBridgeRecovery(
        plan: ProfileRunPlan,
        reason: String,
        lifecycleSnapshot: Int,
        failure: Throwable,
    ) {
        val recoveryAttempt = bridgeRecoveryCoordinator.nextRecoveryAttempt()
        val attempt = recoveryAttempt.number
        val delayMs = recoveryAttempt.delayMillis
        val failureText = failureDescription(failure)
        val owned = synchronized(lifecycleCommandLock) {
            if (
                lifecycleSnapshot != lifecycleGeneration.get() ||
                explicitStopRequested.get() ||
                destroyed.get() ||
                !isActiveServiceOwner()
            ) {
                false
            } else {
                TcptunState.setStatus("Starting")
                TcptunState.setConnectionsReady(false)
                TcptunState.updateDiagnostics {
                    it.copy(
                        bridgeStatus = "Reconnecting",
                        lastRestartReason = reason,
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
                    lifecycleSnapshot == lifecycleGeneration.get() &&
                    !explicitStopRequested.get() &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
                ) {
                    scheduleBridgeRecovery(
                        plan = plan,
                        reason = reason,
                        lifecycleSnapshot = lifecycleSnapshot,
                        failure = retryError,
                    )
                }
            },
        ) recovery@{
            val commandOwner = {
                lifecycleSnapshot == lifecycleGeneration.get() &&
                    !explicitStopRequested.get() &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
            }
            if (!commandOwner()) return@recovery
            val retryIntent = startIntent(this, plan)
            startFromIntentNow(
                intent = retryIntent,
                generation = lifecycleSnapshot,
                expectedProfileMutationRevision = ProfileStore.currentMutationRevision(),
                preserveDesiredStateOnFailure = true,
                commandOwner = commandOwner,
            )
        }
        if (future == null) {
            synchronized(lifecycleCommandLock) {
                if (
                    lifecycleSnapshot == lifecycleGeneration.get() &&
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
        val generation = runtimeSettingsApplyGate.request(forceRestart)
        TcptunState.appendLog("runtime settings apply requested: $reason")
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
            val forceThisApply = runtimeSettingsApplyGate.claim(generation) ?: return@runtimeApply
            scheduleRuntimeSettingsApply(reason, generation, forceThisApply)
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
        generation: Int,
        forceRestart: Boolean,
    ) {
        val accepted = synchronized(lifecycleCommandLock) {
            if (
                destroyed.get() ||
                explicitStopRequested.get() ||
                !runtimeSettingsApplyGate.isLatest(generation)
            ) {
                return@synchronized null
            }
            val lifecycleSnapshot = lifecycleGeneration.get()
            val updateSnapshot = connectionUpdateTracker.current()
            executeLifecycleTask(
                taskName = "runtime settings apply",
                onFailure = { error ->
                    if (!destroyed.get() && isRuntimeSettingsApplyLatest(generation)) {
                        TcptunState.appendLog(failureDescription(error))
                    }
                },
            ) {
                applyRuntimeSettingsNow(
                    reason = reason,
                    generation = generation,
                    forceRestart = forceRestart,
                    lifecycleSnapshot = lifecycleSnapshot,
                    updateSnapshot = updateSnapshot,
                )
            }
        }
        if (accepted == false && !destroyed.get()) {
            TcptunState.appendLog("runtime settings apply could not be scheduled")
        }
    }

    private fun applyRuntimeSettingsNow(
        reason: String,
        generation: Int,
        forceRestart: Boolean,
        lifecycleSnapshot: Int,
        updateSnapshot: Int,
    ) {
        if (!isRuntimeSettingsApplyLatest(generation) || destroyed.get() || explicitStopRequested.get()) return
        if (
            lifecycleSnapshot != lifecycleGeneration.get() ||
            updateSnapshot != connectionUpdateTracker.current()
        ) {
            scheduleRuntimeSettingsApply(reason, generation, forceRestart)
            return
        }
        if (tun == null || stopping) {
            TcptunState.appendLog("runtime settings apply skipped: VPN is not running")
            return
        }
        val plan = runningPlan ?: readDesiredRunningPlan(this) ?: run {
            TcptunState.appendLog("runtime settings apply skipped: no running profile")
            return
        }
        val settings = readRuntimeSettings(this)
        var restartRequired = BridgeHealthPolicy.requiresRuntimeRestart(
            forceRestart = forceRestart,
            currentStructuralRuntimeSettings(),
            settings,
        )
        if (!restartRequired) {
            val stillCurrent = synchronized(lifecycleCommandLock) {
                isRuntimeSettingsApplyLatest(generation) &&
                    !explicitStopRequested.get() &&
                    !destroyed.get() &&
                    isActiveServiceOwner() &&
                    lifecycleSnapshot == lifecycleGeneration.get() &&
                    updateSnapshot == connectionUpdateTracker.current()
            }
            if (!stillCurrent) {
                if (isRuntimeSettingsApplyLatest(generation) && !explicitStopRequested.get()) {
                    scheduleRuntimeSettingsApply(reason, generation, forceRestart)
                }
                return
            }
            if (settings.logLevel != activeLogLevel) {
                try {
                    synchronized(bridgeLock) {
                        bridge.setLogLevel(settings.logLevel)
                        check(bridge.logLevel() == settings.logLevel) {
                            "tcptun bridge did not apply log.level=${settings.logLevel}"
                        }
                    }
                } catch (error: Throwable) {
                    if (error.isFatalProcessError()) throw error
                    restartRequired = true
                    TcptunState.appendLog(
                        "dynamic log.level update unavailable; restarting VPN: ${failureDescription(error)}",
                    )
                }
            }
        }
        if (!restartRequired) {
            applyCachedRuntimeSettings(settings)
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
                !isRuntimeSettingsApplyLatest(generation) ||
                destroyed.get() ||
                explicitStopRequested.get() ||
                !isActiveServiceOwner() ||
                lifecycleSnapshot != lifecycleGeneration.get() ||
                updateSnapshot != connectionUpdateTracker.current()
            ) {
                null
            } else {
                lifecycleGeneration.incrementAndGet() to ProfileStore.currentMutationRevision()
            }
        }
        if (restartClaim == null) {
            if (isRuntimeSettingsApplyLatest(generation) && !explicitStopRequested.get() && !destroyed.get()) {
                scheduleRuntimeSettingsApply(reason, generation, forceRestart)
            }
            return
        }
        val (restartGeneration, profileMutationRevision) = restartClaim
        val restartReason = if (forceRestart) "route rules changed" else reason
        TcptunState.appendLog("restarting VPN to apply runtime settings")
        startFromIntentNow(
            restartIntent,
            restartGeneration,
            expectedProfileMutationRevision = profileMutationRevision,
            commandOwner = {
                restartGeneration == lifecycleGeneration.get() &&
                    !explicitStopRequested.get() &&
                    !destroyed.get() &&
                    isActiveServiceOwner()
            },
        )
        if (restartGeneration == lifecycleGeneration.get() && tun != null && !stopping) {
            TcptunState.updateDiagnostics { it.copy(lastRestartReason = restartReason) }
        }
    }

    private fun isRuntimeSettingsApplyLatest(generation: Int): Boolean =
        runtimeSettingsApplyGate.isLatest(generation)

    private fun requestFlowAnalysisUpdate() {
        executeLifecycleTask("flow analysis update") flowUpdate@{
            val packageName = readRuntimeSettings(this).flowAnalysisApp
            val epoch = bridgeResources.activeEpoch
            synchronized(lifecycleCommandLock) {
                if (destroyed.get() || !isActiveServiceOwner()) return@flowUpdate
                activeFlowAnalysisApp = packageName
                TcptunState.setFlowAnalysisApp(packageName)
            }
            if (epoch <= 0 || bridgeResources.activeConfigJson == null || tun == null || stopping) {
                TcptunState.appendLog("flow analysis saved: ${packageName.ifBlank { "disabled" }}")
                return@flowUpdate
            }
            try {
                synchronized(bridgeLock) {
                    configureFlowAnalysis(packageName, epoch)
                }
                TcptunState.appendLog("flow analysis switched without VPN restart: ${packageName.ifBlank { "disabled" }}")
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog("flow analysis update failed: ${failureDescription(error)}")
            }
        }
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
                    )
                }
            }
        }
    }

    private fun startBridgeMonitor() {
        stopBridgeMonitor()
        val generation = monitorGeneration.incrementAndGet()
        val sessionEpoch = bridgeResources.activeEpoch
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
                        !stopping
                },
                canProbe = {
                    generation == monitorGeneration.get() &&
                        sessionEpoch == bridgeResources.activeEpoch &&
                        tun != null &&
                        !stopping
                },
                awaitEvent = ::awaitBridgeHealthEvent,
                probeFailureReason = { vpnHealthFailure(generation, sessionEpoch)?.reason },
                onSchedule = { schedule ->
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
                    requestBridgeRestart(reason, cancelIfHealthy = true)
                },
                onRecoverableError = { error ->
                    TcptunState.appendLog("tcptun bridge monitor error: ${failureDescription(error)}")
                },
            )
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

    private fun vpnHealthFailure(monitorEpoch: Int, sessionEpoch: Long): HealthFailure? {
        val uiVisible = TcptunState.isUiVisible
        val previous = TcptunState.state.value.diagnostics
        // Prefer StatusCallback state already folded into TcptunState. Only
        // UI-forced refreshes reconcile against StatusJSON (authoritative snapshot).
        val reconcile = shouldReconcileStatusJson()
        val observedStatus = if (reconcile) {
            reconcileBridgeStatusFromJson()?.ifBlank { TcptunState.status }
                ?: run {
                    TcptunState.updateDiagnosticsForBridgeEpoch(sessionEpoch) {
                        it.copy(localProxyReachable = false)
                    }
                    return HealthFailure("status unavailable")
                }
        } else {
            TcptunState.state.value.diagnostics.bridgeStatus
                .ifBlank { TcptunState.status }
                .ifBlank { "Unknown" }
        }
        val probeLocalProxy = BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible)
        val localProxyReachable = if (probeLocalProxy) canConnectLocalProxy() else true
        val localProxyAddress = activeLocalSocksConnectAddr()
        val nextLocalProxyReachable = if (probeLocalProxy) localProxyReachable else previous.localProxyReachable
        if (
            uiVisible ||
            previous.localProxyReachable != nextLocalProxyReachable ||
            previous.localProxyAddress != localProxyAddress ||
            previous.localProxyPort != activeSocksPort
        ) {
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
            restoreConnectionsReadyAfterHealthySnapshot(sessionEpoch)
        }
        // Member probes only when an event forced them (network / RUNTIME_* /
        // membership / UI). Aggregate SOCKS/HTTP stays UI-only.
        if (shouldProbeMemberHealth()) {
            val targets = orderedUpstreamProbeTargets()
            probeActiveMembers(targets, monitorEpoch)
            if (monitorEpoch != monitorGeneration.get() || stopping) return null
        }
        if (shouldRunUpstreamProbe()) {
            val targets = orderedUpstreamProbeTargets()
            val upstreamFailure = upstreamProbeFailure(targets)
            updateRawProfileHealth(upstreamFailure)
            upstreamFailure?.let { return HealthFailure(it) }
        }
        return null
    }

    private fun restoreConnectionsReadyAfterHealthySnapshot(
        sessionEpoch: Long,
    ): Boolean = synchronized(lifecycleCommandLock) {
        // Never trust values read before acquiring the lifecycle lock. Status
        // callbacks use this same lock, so this is the latest ordered state.
        val current = TcptunState.state.value
        if (!canRestoreConnectionsReady(
                runtimeStatus = current.status,
                bridgeStatus = current.diagnostics.bridgeStatus,
                bridgeEventState = current.diagnostics.bridgeEventState,
                localProxyReachable = current.diagnostics.localProxyReachable,
                sessionCurrent = sessionEpoch > 0L && sessionEpoch == bridgeResources.activeEpoch,
                hasTun = tun != null,
                hasRunningPlan = runningPlan != null && bridgeResources.activeConfigJson != null,
                stopping = stopping,
                bridgeRestarting = bridgeRestarting,
                explicitStopRequested = explicitStopRequested.get(),
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
    private fun reconcileBridgeStatusFromJson(): String? {
        return runRecoverableCatching {
            val sessionEpoch = bridgeResources.activeEpoch
            val rawStatus = synchronized(bridgeLock) {
                check(
                    !stopping && sessionEpoch > 0L &&
                        sessionEpoch == bridgeResources.activeEpoch &&
                            bridgeResources.activeConfigJson != null,
                ) {
                    "tcptun session is unavailable"
                }
                bridge.statusJson()
            }
            require(rawStatus.length <= MAX_BRIDGE_STATUS_JSON_LENGTH) { "bridge status JSON is too large" }
            requireSafeJsonNesting(rawStatus)
            val json = JSONObject(rawStatus)
            val eventState = json.optString("state")
            val status = TcptunState.bridgeSimpleStatus(eventState)
            val clientIps = normalizeClientIps(
                buildList {
                    json.optJSONArray("client_ips")?.let { values ->
                        for (index in 0 until minOf(values.length(), MAX_CLIENT_IP_CANDIDATES)) {
                            add(values.optString(index))
                        }
                    }
                },
            )
            val applied = TcptunState.reconcileBridgeStatusSnapshotForEpoch(
                epoch = sessionEpoch,
                sessionId = json.optLong("session_id", 0),
                sequence = json.optLong("sequence", 0),
                bridgeStatus = status,
                bridgeLastError = json.optString("last_error"),
                eventState = eventState,
            ) {
                it.copy(
                    bridgeStatus = status,
                    bridgeEventState = eventState.ifBlank { it.bridgeEventState },
                    bridgeEventReason = json.optString("reason").ifBlank { it.bridgeEventReason },
                    bridgeEventPhase = json.optString("phase").ifBlank { it.bridgeEventPhase },
                    bridgeListen = json.optString("listen").ifBlank { it.bridgeListen },
                    bridgeRemote = if (json.has("remote")) json.optString("remote") else it.bridgeRemote,
                    bridgeActiveConnections = json.optInt("active_connections", it.bridgeActiveConnections),
                    bridgeClientIps = if (json.has("client_ips")) clientIps else it.bridgeClientIps,
                    bridgeMuxSources = json.optInt("mux_sources", it.bridgeMuxSources),
                    bridgeMuxSessions = json.optInt("mux_sessions", it.bridgeMuxSessions),
                    bridgeMuxStreams = json.optInt("mux_streams", it.bridgeMuxStreams),
                    bridgeRecoverable = json.optBoolean("recoverable", it.bridgeRecoverable),
                    bridgeLastError = if (json.has("last_error")) json.optString("last_error") else it.bridgeLastError,
                    bridgeTimestampMs = json.optLong("timestamp_ms", it.bridgeTimestampMs).takeIf { ts -> ts > 0 }
                        ?: it.bridgeTimestampMs,
                )
            }
            when {
                applied -> status
                sessionEpoch == bridgeResources.activeEpoch && !stopping ->
                    TcptunState.state.value.diagnostics.bridgeStatus.ifBlank { status }
                else -> null
            }
        }.getOrNull()
    }

    private fun probeActiveMembers(targets: List<UpstreamProbeTarget>, monitorEpoch: Int) {
        // A full-JSON profile can use a selector as its default outbound. It is
        // represented as one app profile, so only the aggregate SOCKS/TLS probe
        // can describe its health without guessing at its internal members.
        val candidates = runningPlan?.activeProfiles.orEmpty().filter { it.rawConfigJson.isBlank() }
        val sessionEpoch = bridgeResources.activeEpoch
        if (candidates.isEmpty() || targets.isEmpty() || sessionEpoch <= 0L) return
        val worstCaseProfileMs = MEMBER_HEALTH_PROBE_TIMEOUT_MS.toLong() * targets.size
        val maxProfiles = (MEMBER_HEALTH_BATCH_TIMEOUT_MS / worstCaseProfileMs)
            .toInt()
            .coerceAtLeast(1)
        val profiles = memberHealthBatchSelector.select(candidates, maxProfiles)
        val tasks = profiles.map { profile ->
            Callable { probeMember(profile, targets, sessionEpoch) }
        }
        val timeoutMs = minOf(
            MEMBER_HEALTH_BATCH_TIMEOUT_MS,
            worstCaseProfileMs * profiles.size,
        ) + MEMBER_HEALTH_PROBE_GRACE_MS
        val futures = memberHealthExecutor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
        if (monitorEpoch != monitorGeneration.get() || stopping) return
        val coreRefreshProfiles = mutableListOf<AppConfig>()
        var retryTransientFailure = false
        futures.forEachIndexed { index, future ->
            val profile = profiles[index]
            if (profile.id !in runningPlan?.activeIds.orEmpty()) return@forEachIndexed
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
            TcptunState.setProfileHealthForBridgeEpoch(sessionEpoch, profile.id, health)
            if (previous?.status != health.status) {
                val detail = health.latencyMs?.let { "${it}ms" } ?: health.error.ifBlank { "unknown" }
                TcptunState.appendLog("connection ${profile.name} health: ${health.status.name.lowercase()} $detail")
            }
        }
        if (coreRefreshProfiles.isNotEmpty()) {
            refreshProfileHealthFromCore(coreRefreshProfiles)
        }
        if (retryTransientFailure && monitorEpoch == monitorGeneration.get() && !stopping) {
            requestMemberHealthProbe(
                reason = "retry transient member health failure",
                delayMs = BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
            )
        }
    }

    private fun updateRawProfileHealth(failure: String?) {
        val sessionEpoch = bridgeResources.activeEpoch
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
        TcptunState.setProfileHealthForBridgeEpoch(sessionEpoch, profile.id, health)
    }

    private fun refreshProfileHealthFromCore(profiles: List<AppConfig>) {
        val profileByTag = profiles.associateBy(AppConfig::runtimeOutboundTag)
        val sessionEpoch = bridgeResources.activeEpoch
        runRecoverableCatching {
            val rawStatuses = synchronized(bridgeLock) {
                check(
                    !stopping && sessionEpoch > 0L && sessionEpoch == bridgeResources.activeEpoch,
                ) {
                    "tcptun session is unavailable"
                }
                bridge.outboundsStatusJson()
            }
            require(rawStatuses.length <= MAX_BRIDGE_STATUS_JSON_LENGTH) { "outbound status JSON is too large" }
            requireSafeJsonNesting(rawStatuses)
            JSONArray(rawStatuses)
        }
            .onSuccess { statuses ->
                for (index in 0 until minOf(statuses.length(), MAX_BRIDGE_STATUS_ITEM_COUNT)) {
                    val status = statuses.optJSONObject(index) ?: continue
                    val profile = profileByTag[status.optString("tag")] ?: continue
                    if (profile.id !in runningPlan?.activeIds.orEmpty()) continue
                    val healthStatus = when (status.optString("health").lowercase()) {
                        "healthy" -> ProfileHealthStatus.Healthy
                        "degraded" -> ProfileHealthStatus.Degraded
                        else -> continue
                    }
                    val previous = TcptunState.state.value.profileHealth[profile.id]
                    TcptunState.setProfileHealthForBridgeEpoch(
                        sessionEpoch,
                        profile.id,
                        ProfileHealth(
                            status = healthStatus,
                            latencyMs = status.optLong("latency_ms").takeIf { it > 0 },
                            failures = status.optLong("failures").coerceAtLeast(0),
                            lastCheckedAtMs = status.optLong("last_observed_at_ms"),
                            lastSucceededAtMs = status.optLong("last_succeeded_at_ms"),
                            error = previous?.error.takeIf { healthStatus == ProfileHealthStatus.Degraded }.orEmpty(),
                        ),
                    )
                }
            }
            .onFailure { err -> TcptunState.appendLog("outbound health status unavailable: ${err.message}") }
    }

    private fun probeMember(
        profile: AppConfig,
        targets: List<UpstreamProbeTarget>,
        sessionEpoch: Long,
    ): MemberHealthProbeResult {
        val failures = mutableListOf<String>()
        for (target in targets) {
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("health probe cancelled")
            }
            val elapsed = runRecoverableCatching {
                synchronized(bridgeLock) {
                    if (
                        Thread.currentThread().isInterrupted ||
                        stopping || tun == null || sessionEpoch != bridgeResources.activeEpoch
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
                            stopping || sessionEpoch != bridgeResources.activeEpoch
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
            require(rawStatus.length <= MAX_BRIDGE_STATUS_JSON_LENGTH) { "bridge status JSON is too large" }
            requireSafeJsonNesting(rawStatus)
            val json = JSONObject(rawStatus)
            BridgeRuntimeSnapshot(
                epoch = sessionEpoch,
                activeConnections = json.optInt("active_connections", 0),
                clientIps = normalizeClientIps(
                    buildList {
                        json.optJSONArray("client_ips")?.let { values ->
                            for (index in 0 until minOf(values.length(), MAX_CLIENT_IP_CANDIDATES)) {
                                add(values.optString(index))
                            }
                        }
                    },
                ),
                muxSources = json.optInt("mux_sources", 0),
                muxSessions = json.optInt("mux_sessions", 0),
                muxStreams = json.optInt("mux_streams", 0),
            )
        }.getOrNull()
    }

    private fun requestBridgeClientIpsRefresh() {
        executeLifecycleTask(
            taskName = "bridge client IP refresh",
            onFailure = { error ->
                if (!destroyed.get()) TcptunState.appendLog(failureDescription(error))
            },
        ) { refreshBridgeClientIps() }
    }

    private fun refreshBridgeClientIps() {
        if (stopping || tun == null || TcptunState.status != "Running") return
        val snapshot = bridgeRuntimeSnapshot() ?: return
        TcptunState.updateDiagnosticsForBridgeEpoch(snapshot.epoch) {
            it.copy(
                bridgeActiveConnections = snapshot.activeConnections,
                bridgeClientIps = snapshot.clientIps,
            )
        }
    }

    private fun canConnectLocalProxy(): Boolean {
        return runRecoverableCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOCAL_SOCKS_HOST, activeSocksPort), LOCAL_PROXY_CONNECT_TIMEOUT_MS)
            }
        }.isSuccess
    }

    private fun shouldRunUpstreamProbe(): Boolean {
        val force = forceNextUpstreamProbe.compareAndSet(true, false)
        val allowed = BridgeHealthPolicy.shouldRunUpstreamProbe(
            uiVisible = TcptunState.isUiVisible,
            force = force,
        )
        if (!allowed) {
            // Keep a pending force if this cycle could not run (UI already hidden).
            if (force && !TcptunState.isUiVisible) {
                forceNextUpstreamProbe.set(true)
            }
            return false
        }
        return true
    }

    private fun shouldReconcileStatusJson(): Boolean {
        val force = forceStatusReconcile.compareAndSet(true, false)
        val allowed = BridgeHealthPolicy.shouldReconcileStatusJson(
            uiVisible = TcptunState.isUiVisible,
            force = force,
        )
        if (!allowed) {
            if (force && !TcptunState.isUiVisible) {
                forceStatusReconcile.set(true)
            }
            return false
        }
        return true
    }

    private fun shouldProbeMemberHealth(): Boolean {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val notBeforeMs = memberHealthProbeNotBeforeElapsedMs.get()
        // Peek force without consuming while still inside the settle window so a
        // delayed wake can still run the forced probe afterward.
        if (notBeforeMs > 0L && nowElapsedMs < notBeforeMs) {
            return false
        }
        val force = forceNextMemberHealthProbe.compareAndSet(true, false)
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
        val failures = mutableListOf<String>()
        for (target in targets) {
            val failure = probeUpstream(target)
            if (failure == null) {
                TcptunState.appendLog("upstream probe ${target.label} succeeded")
                return null
            }
            failures += "${target.label}: $failure"
        }
        return "all upstream probes failed: ${failures.joinToString("; ")}"
    }

    private fun probeUpstream(target: UpstreamProbeTarget): String? {
        return runRecoverableCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOCAL_SOCKS_HOST, activeSocksPort), UPSTREAM_PROBE_TIMEOUT_MS)
                socket.soTimeout = UPSTREAM_PROBE_TIMEOUT_MS
                socks5Connect(socket, target.host, target.port, activeSocksUsername, activeSocksPassword)
                val expectedStatus = target.expectedStatus
                if (expectedStatus == null) {
                    completeTlsHandshake(socket, target.host, target.port, UPSTREAM_PROBE_TIMEOUT_MS)
                } else {
                    val status = fetchHttpsStatus(socket, target.host, target.port, target.path, UPSTREAM_PROBE_TIMEOUT_MS)
                    require(status == expectedStatus) {
                        "HTTP ${target.label} returned $status, expected $expectedStatus"
                    }
                }
            }
        }.fold(
            onSuccess = { null },
            onFailure = { err -> err.message ?: err.javaClass.simpleName },
        )
    }

    private fun orderedUpstreamProbeTargets(): List<UpstreamProbeTarget> {
        val targets = UPSTREAM_PROBE_TARGETS
        val start = (upstreamProbeIndex % targets.size).coerceAtLeast(0)
        upstreamProbeIndex = (start + 1) % targets.size
        return targets.indices.map { offset -> targets[(start + offset) % targets.size] }
    }

    private fun socks5Connect(socket: Socket, host: String, port: Int, username: String, password: String) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val authEnabled = username.isNotEmpty() || password.isNotEmpty()
        output.write(if (authEnabled) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val methodReply = input.readExact(2)
        require(methodReply[0] == 0x05.toByte()) { "invalid SOCKS5 method reply" }
        when (methodReply[1].toInt() and 0xff) {
            0x00 -> Unit
            0x02 -> socks5Authenticate(input, output, username, password)
            else -> error("SOCKS5 method rejected")
        }

        val hostBytes = host.encodeToByteArray()
        require(hostBytes.size <= 255) { "host is too long" }
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, destinationOffset = 5)
        request[request.lastIndex - 1] = ((port ushr 8) and 0xff).toByte()
        request[request.lastIndex] = (port and 0xff).toByte()
        output.write(request)
        output.flush()

        val replyHead = input.readExact(4)
        require(replyHead[0] == 0x05.toByte()) { "invalid SOCKS5 reply" }
        require(replyHead[1] == 0x00.toByte()) { "SOCKS5 connect failed: ${replyHead[1].toInt() and 0xff}" }
        val addressLength = when (replyHead[3].toInt() and 0xff) {
            0x01 -> 4
            0x03 -> input.read()
            0x04 -> 16
            else -> error("invalid SOCKS5 address type")
        }
        require(addressLength >= 0) { "SOCKS5 reply ended early" }
        input.readExact(addressLength + 2)
    }

    private fun socks5Authenticate(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        username: String,
        password: String,
    ) {
        val usernameBytes = username.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        require(usernameBytes.size <= 255) { "SOCKS5 username is too long" }
        require(passwordBytes.size <= 255) { "SOCKS5 password is too long" }
        val request = ByteArray(3 + usernameBytes.size + passwordBytes.size)
        request[0] = 0x01
        request[1] = usernameBytes.size.toByte()
        usernameBytes.copyInto(request, destinationOffset = 2)
        request[2 + usernameBytes.size] = passwordBytes.size.toByte()
        passwordBytes.copyInto(request, destinationOffset = 3 + usernameBytes.size)
        output.write(request)
        output.flush()
        val reply = input.readExact(2)
        require(reply[0] == 0x01.toByte() && reply[1] == 0x00.toByte()) {
            "SOCKS5 username/password auth failed"
        }
    }

    private fun java.io.InputStream.readExact(length: Int): ByteArray {
        require(length in 0..MAX_SOCKS_REPLY_LENGTH) { "invalid SOCKS5 reply length" }
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(data, offset, length - offset)
            if (read < 0) {
                error("connection closed")
            }
            if (read == 0) {
                val value = read()
                if (value < 0) error("connection closed")
                data[offset++] = value.toByte()
            } else {
                offset += read
            }
        }
        return data
    }

    private fun activeLocalSocksConnectAddr(): String {
        return "$LOCAL_SOCKS_HOST:$activeSocksPort"
    }

    private fun buildNotification(state: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, TcptunVpnService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(state)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(state != getString(R.string.vpn_notification_stopped))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.vpn_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun startVpnForeground(state: String) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager is unavailable")
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager is unavailable")
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.tcptun.client.START"
        const val ACTION_STOP = "com.tcptun.client.STOP"
        const val ACTION_UPDATE_OUTBOUNDS = "com.tcptun.client.UPDATE_OUTBOUNDS"
        const val ACTION_TCPING_OUTBOUNDS = "com.tcptun.client.TCPING_OUTBOUNDS"
        const val ACTION_APPLY_RUNTIME_SETTINGS = "com.tcptun.client.APPLY_RUNTIME_SETTINGS"
        const val ACTION_UPDATE_FLOW_ANALYSIS = "com.tcptun.client.UPDATE_FLOW_ANALYSIS"
        const val ACTION_REFRESH_CLIENT_IPS = "com.tcptun.client.REFRESH_CLIENT_IPS"
        const val EXTRA_CONFIG = "config"
        private const val EXTRA_PROFILE_PLAN = "profilePlan"
        private const val EXTRA_TCPING_REQUEST_ID = "tcpingRequestId"
        private const val EXTRA_TCPING_TARGET_LABEL = "tcpingTargetLabel"
        private const val EXTRA_TCPING_HOST = "tcpingHost"
        private const val EXTRA_TCPING_PORT = "tcpingPort"
        private const val EXTRA_FORCE_RUNTIME_RESTART = "forceRuntimeRestart"
        private const val EXTRA_RUNTIME_SETTINGS_VERSION = "runtimeSettingsVersion"
        private const val EXTRA_RUNTIME_MTU = "runtimeMtu"
        private const val EXTRA_RUNTIME_POWER_SAVING = "runtimePowerSaving"
        private const val EXTRA_RUNTIME_LOG_LEVEL = "runtimeLogLevel"
        private const val EXTRA_RUNTIME_SOCKS_PORT = "runtimeSocksPort"
        private const val EXTRA_RUNTIME_LOCAL_PROXY_PROTOCOL = "runtimeLocalProxyProtocol"
        private const val EXTRA_RUNTIME_SOCKS_LISTEN_ALL = "runtimeSocksListenAll"
        private const val EXTRA_RUNTIME_SOCKS_USERNAME = "runtimeSocksUsername"
        private const val EXTRA_RUNTIME_SOCKS_PASSWORD = "runtimeSocksPassword"
        private const val EXTRA_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC = "runtimeRouteLocalProxyTraffic"
        private const val EXTRA_RUNTIME_DEFAULT_OUTBOUND = "runtimeDefaultOutbound"
        private const val EXTRA_RUNTIME_FLOW_ANALYSIS_APP = "runtimeFlowAnalysisApp"
        private const val RUNTIME_SETTINGS_INTENT_VERSION = 1
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_VPN_MTU = 1400
        private const val VPN_DNS_ADDRESS = "10.77.0.1"
        private const val CHANNEL_ID = "tcptun_vpn_silent"
        private const val NOTIFICATION_ID = 1001
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
        private const val RUNTIME_SETTINGS_APPLY_DEBOUNCE_MS = 800L
        private const val DESTROY_EXECUTOR_WAIT_MS = 2_000L
        private const val DEFERRED_DESTROY_WAIT_MS = 15_000L
        private const val DESTROY_NATIVE_TEARDOWN_WAIT_MS = 15_000L
        private const val PREVIOUS_RUNTIME_RELEASE_WAIT_MS = 35_000L
        private const val MAX_MEMBER_HEALTH_PROBE_DELAY_MS = 86_400_000L
        private const val MAX_RUNTIME_CREDENTIAL_LENGTH = 4_096
        private const val MAX_SAVED_RUNNING_PLAN_LENGTH = 1_000_000
        private const val MAX_BRIDGE_STATUS_JSON_LENGTH = 64 * 1024
        private const val MAX_BRIDGE_STATUS_ITEM_COUNT = 1_024
        private const val MAX_SOCKS_REPLY_LENGTH = 512
        private const val LOCAL_PROXY_CONNECT_TIMEOUT_MS = 1_000
        private const val UPSTREAM_PROBE_TIMEOUT_MS = 5_000
        private const val RUNTIME_PREFS = "tcptun_runtime"
        private const val KEY_LAST_RUNNING_CONFIG = "lastRunningConfig"
        private const val KEY_LAST_RUNNING_PLAN = "lastRunningPlan"
        private const val KEY_DESIRED_RUNNING = "desiredRunning"
        private const val KEY_RUNNING_CONFIG_VERSION = "runningConfigVersion"
        private const val RUNNING_CONFIG_VERSION = 3
        private const val KEY_RUNTIME_MTU = "runtimeMtu"
        private const val KEY_RUNTIME_POWER_SAVING = "runtimePowerSaving"
        private const val KEY_RUNTIME_LOG_LEVEL = "runtimeLogLevel"
        private const val KEY_RUNTIME_SOCKS_PORT = "runtimeSocksPort"
        private const val KEY_RUNTIME_LOCAL_PROXY_PROTOCOL = "runtimeLocalProxyProtocol"
        private const val KEY_RUNTIME_SOCKS_LISTEN_ALL = "runtimeSocksListenAll"
        private const val KEY_RUNTIME_SOCKS_USERNAME = "runtimeSocksUsername"
        private const val KEY_RUNTIME_SOCKS_PASSWORD = "runtimeSocksPassword"
        private const val KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC = "runtimeRouteLocalProxyTraffic"
        private const val KEY_RUNTIME_DEFAULT_OUTBOUND = "runtimeDefaultOutbound"
        private const val KEY_RUNTIME_FLOW_ANALYSIS_APP = "runtimeFlowAnalysisApp"
        private val forceNextUpstreamProbe = AtomicBoolean(false)
        private val forceNextMemberHealthProbe = AtomicBoolean(false)
        private val forceStatusReconcile = AtomicBoolean(false)
        /** ElapsedRealtime deadline; member probes are blocked until this time. */
        private val memberHealthProbeNotBeforeElapsedMs = AtomicLong(0L)
        private val memberHealthProbeScheduleGeneration = AtomicInteger()
        private val memberHealthDelayLock = Any()
        private val memberHealthDelayExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TcptunMemberHealthDelay").apply { isDaemon = true }
        }
        private val memberHealthDelayFuture = AtomicReference<ScheduledFuture<*>?>(null)
        private val activeMonitorWakeCallback = AtomicReference<(() -> Unit)?>(null)
        private val serviceOwnerLock = Any()
        private val nextServiceInstanceId = AtomicLong()
        private val activeServiceInstanceId = AtomicLong()
        private val bridgeRuntimeLease = BridgeRuntimeLease()
        private val UPSTREAM_PROBE_TARGETS = listOf(
            UpstreamProbeTarget("Google 204", "connectivitycheck.gstatic.com", path = "/generate_204", expectedStatus = 204),
            UpstreamProbeTarget("Cloudflare 204", "cp.cloudflare.com", path = "/generate_204", expectedStatus = 204),
        )
        fun startIntent(context: Context, config: AppConfig): Intent {
            return startIntent(context, ProfileRunPlan(listOf(config)))
        }

        fun refreshClientIpsIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_REFRESH_CLIENT_IPS)
        }

        fun startIntent(context: Context, sourcePlan: ProfileRunPlan): Intent {
            val payload = buildVpnCommandPayload(
                context = context,
                sourcePlan = sourcePlan,
                managedRouteRules = RouteRuleStore.loadAuthoritative(context).getOrThrow(),
            )
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, payload.configJson)
                .putExtra(EXTRA_PROFILE_PLAN, payload.planJson)
                .putRuntimeSettingsSnapshot(payload.runtimeSettings)
        }

        /** Validate candidate route rules before they become authoritative storage. */
        internal fun preflightStartPayload(
            context: Context,
            sourcePlan: ProfileRunPlan,
            managedRouteRules: List<ManagedRouteRule>,
        ) {
            buildVpnCommandPayload(context, sourcePlan, managedRouteRules)
        }

        private data class VpnCommandPayload(
            val configJson: String,
            val planJson: String,
            val runtimeSettings: RuntimeSettings,
        )

        private fun buildVpnCommandPayload(
            context: Context,
            sourcePlan: ProfileRunPlan,
            managedRouteRules: List<ManagedRouteRule>,
        ): VpnCommandPayload {
            val runtimeSettings = readRuntimeSettings(context)
            val plan = sourcePlan.normalized()
            val localListenAddr = localSocksListenAddr(runtimeSettings)
            val configJson = plan.toBridgeJson(
                localListenAddr,
                localProxyProtocol = runtimeSettings.localProxyProtocol,
                logLevel = runtimeSettings.logLevel,
                socks5Username = runtimeSettings.socksUsername,
                socks5Password = runtimeSettings.socksPassword,
                managedRouteRules = managedRouteRules,
                routeLocalProxyTraffic = runtimeSettings.routeLocalProxyTraffic,
                defaultOutbound = runtimeSettings.defaultOutbound,
            )
            val planJson = plan.toJson().toString()
            val settingsPayloadLength = runtimeSettings.socksUsername.length +
                runtimeSettings.socksPassword.length +
                runtimeSettings.localProxyProtocol.length +
                runtimeSettings.logLevel.length +
                runtimeSettings.defaultOutbound.length +
                runtimeSettings.flowAnalysisApp.length
            require(isVpnCommandPayloadWithinLimit(configJson.length, planJson.length, settingsPayloadLength)) {
                "VPN configuration is too large to send to the service"
            }
            return VpnCommandPayload(configJson, planJson, runtimeSettings)
        }

        private fun Intent.putRuntimeSettingsSnapshot(settings: RuntimeSettings): Intent = apply {
            putExtra(EXTRA_RUNTIME_SETTINGS_VERSION, RUNTIME_SETTINGS_INTENT_VERSION)
            putExtra(EXTRA_RUNTIME_MTU, settings.mtu)
            putExtra(EXTRA_RUNTIME_POWER_SAVING, settings.powerSavingMode)
            putExtra(EXTRA_RUNTIME_LOG_LEVEL, settings.logLevel)
            putExtra(EXTRA_RUNTIME_SOCKS_PORT, settings.socksPort)
            putExtra(EXTRA_RUNTIME_LOCAL_PROXY_PROTOCOL, settings.localProxyProtocol)
            putExtra(EXTRA_RUNTIME_SOCKS_LISTEN_ALL, settings.socksListenAll)
            putExtra(EXTRA_RUNTIME_SOCKS_USERNAME, settings.socksUsername)
            putExtra(EXTRA_RUNTIME_SOCKS_PASSWORD, settings.socksPassword)
            putExtra(EXTRA_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC, settings.routeLocalProxyTraffic)
            putExtra(EXTRA_RUNTIME_DEFAULT_OUTBOUND, settings.defaultOutbound)
            putExtra(EXTRA_RUNTIME_FLOW_ANALYSIS_APP, settings.flowAnalysisApp)
        }

        private fun runtimeSettingsSnapshotFromIntent(intent: Intent): RuntimeSettings? {
            if (
                intent.getIntExtra(EXTRA_RUNTIME_SETTINGS_VERSION, 0) !=
                RUNTIME_SETTINGS_INTENT_VERSION
            ) {
                return null
            }
            val username = intent.getStringExtra(EXTRA_RUNTIME_SOCKS_USERNAME).orEmpty()
            val password = intent.getStringExtra(EXTRA_RUNTIME_SOCKS_PASSWORD).orEmpty()
            require(username.length <= MAX_RUNTIME_CREDENTIAL_LENGTH) { "SOCKS username is too long" }
            require(password.length <= MAX_RUNTIME_CREDENTIAL_LENGTH) { "SOCKS password is too long" }
            return RuntimeSettings(
                mtu = intent.getIntExtra(EXTRA_RUNTIME_MTU, DEFAULT_VPN_MTU).coerceIn(1280, 1500),
                powerSavingMode = intent.getBooleanExtra(EXTRA_RUNTIME_POWER_SAVING, true),
                logLevel = normalizeLogLevel(
                    intent.getStringExtra(EXTRA_RUNTIME_LOG_LEVEL).orEmpty(),
                ),
                socksPort = intent.getIntExtra(EXTRA_RUNTIME_SOCKS_PORT, DEFAULT_SOCKS_PORT)
                    .coerceIn(1, 65535),
                localProxyProtocol = normalizeLocalProxyProtocol(
                    intent.getStringExtra(EXTRA_RUNTIME_LOCAL_PROXY_PROTOCOL).orEmpty(),
                ),
                socksListenAll = intent.getBooleanExtra(EXTRA_RUNTIME_SOCKS_LISTEN_ALL, false),
                socksUsername = username,
                socksPassword = password,
                routeLocalProxyTraffic = intent.getBooleanExtra(
                    EXTRA_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC,
                    false,
                ),
                defaultOutbound = normalizeDefaultOutboundSelection(
                    intent.getStringExtra(EXTRA_RUNTIME_DEFAULT_OUTBOUND).orEmpty(),
                ),
                flowAnalysisApp = normalizeFlowAnalysisApp(
                    intent.getStringExtra(EXTRA_RUNTIME_FLOW_ANALYSIS_APP).orEmpty(),
                ),
            )
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_STOP)
        }

        fun updateOutboundsIntent(context: Context, plan: ProfileRunPlan): Intent {
            return startIntent(context, plan).setAction(ACTION_UPDATE_OUTBOUNDS)
        }

        fun applyRuntimeSettingsIntent(context: Context, forceRestart: Boolean = false): Intent {
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_APPLY_RUNTIME_SETTINGS)
                .putExtra(EXTRA_FORCE_RUNTIME_RESTART, forceRestart)
        }

        fun updateFlowAnalysisIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_UPDATE_FLOW_ANALYSIS)
        }

        fun tcpingOutboundsIntent(
            context: Context,
            requestId: Long,
            targetLabel: String,
            host: String,
            port: Int,
        ): Intent {
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_TCPING_OUTBOUNDS)
                .putExtra(EXTRA_TCPING_REQUEST_ID, requestId)
                .putExtra(EXTRA_TCPING_TARGET_LABEL, targetLabel)
                .putExtra(EXTRA_TCPING_HOST, host)
                .putExtra(EXTRA_TCPING_PORT, port)
        }

        /** One event-driven health check (network change, core status, user refresh). */
        fun requestHealthCheck(reason: String) {
            TcptunState.appendLog("bridge health check requested: $reason")
            activeMonitorWakeCallback.get()?.invoke()
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
            val delay = delayMs.coerceIn(0L, MAX_MEMBER_HEALTH_PROBE_DELAY_MS)
            forceNextMemberHealthProbe.set(true)
            if (delay > 0L) {
                synchronized(memberHealthDelayLock) {
                    val notBefore = SystemClock.elapsedRealtime() + delay
                    memberHealthProbeNotBeforeElapsedMs.updateAndGet { current ->
                        maxOf(current, notBefore)
                    }
                    val generation = memberHealthProbeScheduleGeneration.incrementAndGet()
                    TcptunState.appendLog("member health probe scheduled in ${delay}ms: $reason")
                    val scheduledDelay = (
                        memberHealthProbeNotBeforeElapsedMs.get() - SystemClock.elapsedRealtime()
                    ).coerceAtLeast(0L)
                    val future = scheduleCrashGuardedFuture(
                        executor = memberHealthDelayExecutor,
                        delay = scheduledDelay,
                        unit = TimeUnit.MILLISECONDS,
                        taskName = "delayed member health probe",
                        onFailure = { error ->
                            TcptunState.appendLog(failureDescription(error))
                        },
                    ) delayedProbe@{
                        if (generation != memberHealthProbeScheduleGeneration.get()) return@delayedProbe
                        forceNextMemberHealthProbe.set(true)
                        requestHealthCheck(reason)
                    }
                    if (future == null) {
                        TcptunState.appendLog(
                            "member health probe scheduling failed; requesting an immediate check",
                        )
                        requestHealthCheck(reason)
                        return
                    }
                    memberHealthDelayFuture.getAndSet(future)?.cancel(false)
                }
                return
            }
            requestHealthCheck(reason)
        }

        @Deprecated("Use requestHealthCheck", ReplaceWith("requestHealthCheck(reason)"))
        fun requestDenseHealthCheck(reason: String) = requestHealthCheck(reason)

        fun requestUiVisibleHealthCheck() {
            // Full UI-driven refresh: StatusJSON reconcile + local proxy + member
            // balance health + single aggregate upstream probe through the pool.
            // Immediate; do not extend the settle window so pull-to-refresh stays responsive.
            forceNextUpstreamProbe.set(true)
            forceNextMemberHealthProbe.set(true)
            forceStatusReconcile.set(true)
            synchronized(memberHealthDelayLock) {
                memberHealthProbeNotBeforeElapsedMs.set(0L)
                memberHealthProbeScheduleGeneration.incrementAndGet()
                memberHealthDelayFuture.getAndSet(null)?.cancel(false)
            }
            TcptunState.appendLog("bridge health check requested: app visible")
            activeMonitorWakeCallback.get()?.invoke()
        }

        fun readRuntimeSettings(context: Context): RuntimeSettings {
            val appContext = context.applicationContext ?: context
            val prefs = try {
                appContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog("runtime settings unavailable: ${failureDescription(error)}")
                return RuntimeSettings()
            }
            val mtu = prefs.readOrDefault(KEY_RUNTIME_MTU, DEFAULT_VPN_MTU) {
                getInt(KEY_RUNTIME_MTU, DEFAULT_VPN_MTU)
            }.coerceIn(1280, 1500)
            val powerSavingMode = prefs.readOrDefault(KEY_RUNTIME_POWER_SAVING, true) {
                getBoolean(KEY_RUNTIME_POWER_SAVING, true)
            }
            val logLevel = normalizeLogLevel(
                prefs.readOrDefault(KEY_RUNTIME_LOG_LEVEL, DefaultLogLevel) {
                    getString(KEY_RUNTIME_LOG_LEVEL, DefaultLogLevel).orEmpty()
                },
            )
            val socksPort = prefs.readOrDefault(KEY_RUNTIME_SOCKS_PORT, DEFAULT_SOCKS_PORT) {
                getInt(KEY_RUNTIME_SOCKS_PORT, DEFAULT_SOCKS_PORT)
            }.coerceIn(1, 65535)
            return RuntimeSettings(
                mtu = mtu,
                powerSavingMode = powerSavingMode,
                logLevel = logLevel,
                socksPort = socksPort,
                localProxyProtocol = normalizeLocalProxyProtocol(
                    prefs.readOrDefault(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, DefaultLocalProxyProtocol) {
                        getString(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, DefaultLocalProxyProtocol).orEmpty()
                    },
                ),
                socksListenAll = prefs.readOrDefault(KEY_RUNTIME_SOCKS_LISTEN_ALL, false) {
                    getBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, false)
                },
                socksUsername = prefs.readOrDefault(KEY_RUNTIME_SOCKS_USERNAME, "") {
                    getString(KEY_RUNTIME_SOCKS_USERNAME, "").orEmpty()
                }.take(MAX_RUNTIME_CREDENTIAL_LENGTH).let(::truncateSocksCredential),
                socksPassword = prefs.readOrDefault(KEY_RUNTIME_SOCKS_PASSWORD, "") {
                    getString(KEY_RUNTIME_SOCKS_PASSWORD, "").orEmpty()
                }.take(MAX_RUNTIME_CREDENTIAL_LENGTH).let(::truncateSocksCredential),
                routeLocalProxyTraffic = prefs.readOrDefault(KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC, false) {
                    getBoolean(KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC, false)
                },
                defaultOutbound = normalizeDefaultOutboundSelection(
                    prefs.readOrDefault(KEY_RUNTIME_DEFAULT_OUTBOUND, DefaultOutboundDynamicPool) {
                        getString(KEY_RUNTIME_DEFAULT_OUTBOUND, DefaultOutboundDynamicPool).orEmpty()
                    },
                ),
                flowAnalysisApp = normalizeFlowAnalysisApp(
                    prefs.readOrDefault(KEY_RUNTIME_FLOW_ANALYSIS_APP, "") {
                        getString(KEY_RUNTIME_FLOW_ANALYSIS_APP, "").orEmpty()
                    },
                ),
            )
        }

        fun writeRuntimeSettings(context: Context, settings: RuntimeSettings) {
            val normalizedPowerSavingMode = settings.powerSavingMode
            val normalizedLogLevel = normalizeLogLevel(settings.logLevel)
            val normalizedSocksPort = settings.socksPort.coerceIn(1, 65535)
            val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(settings.localProxyProtocol)
            val normalizedRouteLocalProxyTraffic = settings.routeLocalProxyTraffic
            val normalizedDefaultOutbound = normalizeDefaultOutboundSelection(settings.defaultOutbound)
            val normalizedFlowAnalysisApp = normalizeFlowAnalysisApp(settings.flowAnalysisApp)
            require(settings.socksUsername.length <= MAX_RUNTIME_CREDENTIAL_LENGTH) { "SOCKS username is too long" }
            require(settings.socksPassword.length <= MAX_RUNTIME_CREDENTIAL_LENGTH) { "SOCKS password is too long" }
            require(hasValidSocksCredentialSize(settings.socksUsername)) {
                "SOCKS username exceeds $MaxSocksCredentialUtf8Bytes UTF-8 bytes"
            }
            require(hasValidSocksCredentialSize(settings.socksPassword)) {
                "SOCKS password exceeds $MaxSocksCredentialUtf8Bytes UTF-8 bytes"
            }
            val normalizedSettings = settings.copy(
                powerSavingMode = normalizedPowerSavingMode,
                logLevel = normalizedLogLevel,
                socksPort = normalizedSocksPort,
                localProxyProtocol = normalizedLocalProxyProtocol,
                socksUsername = settings.socksUsername,
                socksPassword = settings.socksPassword,
                routeLocalProxyTraffic = normalizedRouteLocalProxyTraffic,
                defaultOutbound = normalizedDefaultOutbound,
                flowAnalysisApp = normalizedFlowAnalysisApp,
            )
            val appContext = context.applicationContext ?: context
            val saved = appContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RUNTIME_MTU, settings.mtu.coerceIn(1280, 1500))
                .putBoolean(KEY_RUNTIME_POWER_SAVING, normalizedPowerSavingMode)
                .putString(KEY_RUNTIME_LOG_LEVEL, normalizedLogLevel)
                .putInt(KEY_RUNTIME_SOCKS_PORT, normalizedSocksPort)
                .putString(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, normalizedLocalProxyProtocol)
                .putBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, settings.socksListenAll)
                .putString(KEY_RUNTIME_SOCKS_USERNAME, settings.socksUsername)
                .putString(KEY_RUNTIME_SOCKS_PASSWORD, settings.socksPassword)
                .putBoolean(KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC, normalizedRouteLocalProxyTraffic)
                .putString(KEY_RUNTIME_DEFAULT_OUTBOUND, normalizedDefaultOutbound)
                .putString(KEY_RUNTIME_FLOW_ANALYSIS_APP, normalizedFlowAnalysisApp)
                .commit()
            check(saved) { "runtime settings could not be persisted" }
            TcptunState.setFlowAnalysisApp(normalizedFlowAnalysisApp)
            TcptunState.updateDiagnostics {
                it.copy(
                    mtu = settings.mtu.coerceIn(1280, 1500),
                    powerSavingMode = normalizedPowerSavingMode,
                    localProxyAddress = localSocksConnectAddr(normalizedSettings),
                    localProxyPort = normalizedSocksPort,
                )
            }
            TcptunState.appendLog(
                "runtime settings saved: proxy=${normalizedSettings.localProxyProtocol}://" +
                    "${localSocksListenAddr(normalizedSettings)} mtu=${normalizedSettings.mtu} " +
                    "log-level=$normalizedLogLevel " +
                    "power-saving=$normalizedPowerSavingMode " +
                    "route-local-proxy=$normalizedRouteLocalProxyTraffic " +
                    "default-outbound=${normalizedDefaultOutbound.ifBlank { "profile-pool" }} " +
                    "flow-analysis=${normalizedFlowAnalysisApp.ifBlank { "disabled" }}",
            )
        }

        fun localSocksListenAddr(settings: RuntimeSettings): String {
            val host = if (settings.socksListenAll) "0.0.0.0" else LOCAL_SOCKS_HOST
            return "$host:${settings.socksPort.coerceIn(1, 65535)}"
        }

        fun localSocksConnectAddr(settings: RuntimeSettings): String {
            return "$LOCAL_SOCKS_HOST:${settings.socksPort.coerceIn(1, 65535)}"
        }

        fun defaultLocalSocksConnectAddr(): String {
            return "$LOCAL_SOCKS_HOST:$DEFAULT_SOCKS_PORT"
        }

        private fun encodeDesiredRunningPlan(plan: ProfileRunPlan): String {
            val rawPlan = plan.normalized().toJson().toString()
            require(rawPlan.length <= MAX_SAVED_RUNNING_PLAN_LENGTH) { "running profile plan is too large" }
            return rawPlan
        }

        private fun publishDesiredRunningPlan(context: Context, rawPlan: String) {
            require(rawPlan.length <= MAX_SAVED_RUNNING_PLAN_LENGTH) { "running profile plan is too large" }
            val appContext = context.applicationContext ?: context
            val saved = appContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_RUNNING_PLAN, rawPlan)
                .putInt(KEY_RUNNING_CONFIG_VERSION, RUNNING_CONFIG_VERSION)
                .putBoolean(KEY_DESIRED_RUNNING, true)
                .commit()
            check(saved) { "running profile plan could not be persisted" }
        }

        private fun readDesiredRunningPlan(context: Context): ProfileRunPlan? {
            return try {
                val appContext = context.applicationContext ?: context
                val prefs = appContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                val desired = prefs.readOrDefault(KEY_DESIRED_RUNNING, false) {
                    getBoolean(KEY_DESIRED_RUNNING, false)
                }
                if (!desired) return null
                when (prefs.readOrDefault(KEY_RUNNING_CONFIG_VERSION, 1) {
                    getInt(KEY_RUNNING_CONFIG_VERSION, 1)
                }) {
                    RUNNING_CONFIG_VERSION, 2 -> prefs.readOrDefault<String?>(KEY_LAST_RUNNING_PLAN, null) {
                        getString(KEY_LAST_RUNNING_PLAN, null)
                    }?.takeIf { it.length <= MAX_SAVED_RUNNING_PLAN_LENGTH }?.let { raw ->
                        runRecoverableCatching {
                            requireSafeJsonNesting(raw)
                            ProfileRunPlan.fromJson(JSONObject(raw))
                        }.getOrNull()
                    }
                    1 -> prefs.readOrDefault<String?>(KEY_LAST_RUNNING_CONFIG, null) {
                        getString(KEY_LAST_RUNNING_CONFIG, null)
                    }?.takeIf { it.length <= MAX_SAVED_RUNNING_PLAN_LENGTH }?.let { raw ->
                        runRecoverableCatching {
                            requireSafeJsonNesting(raw)
                            val config = AppConfig.fromJson(JSONObject(raw))
                            ProfileRunPlan(listOf(config)).normalized()
                        }.getOrNull()
                    }
                    else -> null
                }
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog("saved VPN plan is unavailable: ${failureDescription(error)}")
                null
            }
        }

        private fun clearDesiredRunningConfig(context: Context) {
            val appContext = context.applicationContext ?: context
            val saved = appContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DESIRED_RUNNING, false)
                .commit()
            check(saved) { "desired VPN state could not be persisted" }
        }

    }
}
