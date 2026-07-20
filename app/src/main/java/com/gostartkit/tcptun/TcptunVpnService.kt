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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class RuntimeSettings(
    val mtu: Int = TcptunVpnService.DEFAULT_VPN_MTU,
    val powerSavingMode: Boolean = true,
    val socksPort: Int = TcptunVpnService.DEFAULT_SOCKS_PORT,
    val localProxyProtocol: String = DefaultLocalProxyProtocol,
    val socksListenAll: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
    /** When true, managed route rules also match mixed/SOCKS local proxy traffic. Default off. */
    val routeLocalProxyTraffic: Boolean = false,
    val flowAnalysisApp: String = "",
)

private val AndroidPackageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

internal fun normalizeFlowAnalysisApp(value: String): String =
    value.trim().takeIf(AndroidPackageNamePattern::matches).orEmpty()

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
    private val bridge: TcptunBridge = ReflectionTcptunBridge()
    private val bridgeLock = Any()
    private val lifecycleExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TcptunLifecycle").apply { isDaemon = true }
    }
    private val tcpingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TcptunTcping").apply { isDaemon = true }
    }
    private val memberHealthExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_MEMBER_HEALTH_PROBES) { runnable ->
        Thread(runnable, "TcptunMemberHealth").apply { isDaemon = true }
    }
    private val lifecycleGeneration = AtomicInteger()
    private val monitorGeneration = AtomicInteger()
    private val monitorWakeGeneration = AtomicInteger()
    private val bridgeRestartRequestGeneration = AtomicInteger()
    private val teardownInProgress = AtomicBoolean()
    private val bridgeReadyWaiter = AtomicReference<BridgeReadyWaiter?>(null)
    @Volatile private var tun: android.os.ParcelFileDescriptor? = null
    @Volatile private var bridgeConfigJson: String? = null
    @Volatile private var activeBridgeEpoch = 0L
    @Volatile private var runningPlan: ProfileRunPlan? = null
    @Volatile private var monitorThread: Thread? = null
    private val monitorWaitLock = Object()
    private val monitorWakeCallback: () -> Unit = ::wakeBridgeMonitor
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val appIdentityProvider by lazy { AndroidAppIdentityProvider(this, connectivity) }
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetworkCallbackRegistered = false
    private val underlyingNetworkLock = Any()
    private val availableUnderlyingNetworks = mutableMapOf<Network, NetworkCapabilities>()
    @Volatile private var currentDefaultNetwork: Network? = null
    @Volatile private var underlyingNetworkSelectionInitialized = false
    @Volatile private var stopping = false
    @Volatile private var bridgeRestarting = false
    @Volatile private var lastBridgeRestartAtMs = 0L
    @Volatile private var tunMtu = DEFAULT_VPN_MTU
    @Volatile private var activeSocksPort = DEFAULT_SOCKS_PORT
    @Volatile private var activeSocksUsername = ""
    @Volatile private var activeSocksPassword = ""
    @Volatile private var activeLocalProxyProtocol = DefaultLocalProxyProtocol
    @Volatile private var activeSocksListenAll = false
    @Volatile private var activeRouteLocalProxyTraffic = false
    @Volatile private var powerSavingMode = true
    @Volatile private var upstreamProbeIndex = 0
    @Volatile private var lastMemberHealthProbeAtElapsedMs = 0L
    @Volatile private var runtimeSettingsApplyGeneration = 0
    private val runtimeSettingsApplyLock = Any()
    private var runtimeSettingsForceRestartPending = false

    override fun onCreate() {
        super.onCreate()
        activeMonitorWakeCallback.set(monitorWakeCallback)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TcptunState.setStatus("Starting")
                startVpnForeground("Starting")
                startFromIntent(intent)
            }
            ACTION_STOP -> requestStopVpn()
            ACTION_UPDATE_OUTBOUNDS -> {
                startVpnForeground("Running")
                requestOutboundUpdate(intent)
            }
            ACTION_TCPING_OUTBOUNDS -> requestOutboundTcping(intent)
            ACTION_APPLY_RUNTIME_SETTINGS -> requestRuntimeSettingsRestart(
                reason = if (intent.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false)) {
                    "route rules changed"
                } else {
                    "runtime settings changed"
                },
                forceRestart = intent.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESTART, false),
            )
            ACTION_UPDATE_FLOW_ANALYSIS -> requestFlowAnalysisUpdate()
            ACTION_REFRESH_CLIENT_IPS -> refreshBridgeClientIps()
            else -> requestRestoreLastRunningConfig()
        }
        return START_STICKY
    }

    private fun startFromIntent(intent: Intent) {
        val generation = lifecycleGeneration.incrementAndGet()
        lifecycleExecutor.execute {
            if (generation != lifecycleGeneration.get()) return@execute
            startFromIntentNow(intent, generation)
        }
    }

    private fun startFromIntentNow(intent: Intent, generation: Int) {
        if (tun != null || bridgeConfigJson != null) {
            TcptunState.appendLog("updating active VPN connections")
            stopVpn(setStopped = false, clearSavedConfig = false, stopSelfService = false)
        }
        stopping = false
        bridgeRestartRequestGeneration.incrementAndGet()
        lastBridgeRestartAtMs = 0L
        val json = intent.getStringExtra(EXTRA_CONFIG) ?: run {
            TcptunState.error("missing VPN config")
            stopSelf()
            return
        }
        try {
                if (generation != lifecycleGeneration.get()) return
                TcptunState.setStatus("Starting")
                startVpnForeground("Starting")
                val plan = intent.getStringExtra(EXTRA_PROFILE_PLAN)
                    ?.let { raw -> runCatching { ProfileRunPlan.fromJson(JSONObject(raw)) }.getOrNull() }
                    ?: error("missing or invalid VPN profile plan")
                val runtimeSettings = readRuntimeSettings(this)
                applyCachedRuntimeSettings(runtimeSettings)
                saveDesiredRunningPlan(this, plan)
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
                val vpnTun = buildTun(runtimeSettings.mtu)
                tun = vpnTun
                tunMtu = runtimeSettings.mtu
                if (generation != lifecycleGeneration.get()) {
                    stopVpn(setStopped = false, clearSavedConfig = false, stopSelfService = false)
                    return
                }
                startBridge(json, plan, vpnTun, runtimeSettings.mtu)
                if (generation != lifecycleGeneration.get()) {
                    stopVpn(setStopped = false, clearSavedConfig = false, stopSelfService = false)
                    return
                }
                runningPlan = plan
                TcptunState.resetProfileHealth(plan.activeProfiles)
                startBridgeMonitor()
                TcptunState.setStatus("Running")
                updateBridgeDiagnostics()
                updateNotification(runningNotificationState(plan))
                // Seed balance selection with explicit member health as soon as
                // the pool is live (not only when the UI later refreshes).
                requestMemberHealthProbe("vpn started")
        } catch (err: Exception) {
            if (generation != lifecycleGeneration.get()) {
                TcptunState.appendLog("VPN start cancelled")
                stopVpn()
                return
            }
            TcptunState.error(err.message ?: err.javaClass.simpleName)
            ProfileStore.clearActive(this)
            stopVpn(setStopped = false, clearSavedConfig = true)
        }
    }

    private fun requestRestoreLastRunningConfig() {
        lifecycleExecutor.execute { restoreLastRunningConfig() }
    }

    private fun restoreLastRunningConfig() {
        if (tun != null) return
        val plan = readDesiredRunningPlan(this) ?: run {
            stopSelf()
            return
        }
        if (runCatching { plan.normalized() }.isFailure) {
            clearDesiredRunningConfig(this)
            stopSelf()
            return
        }
        TcptunState.appendLog("restoring VPN after service restart")
        TcptunState.setStatus("Starting")
        startVpnForeground("Starting")
        startFromIntent(startIntent(this, plan))
    }

    private fun requestOutboundUpdate(intent: Intent) {
        val generation = lifecycleGeneration.get()
        lifecycleExecutor.execute {
            if (generation != lifecycleGeneration.get()) return@execute
            updateOutboundsNow(intent, generation)
        }
    }

    private fun updateOutboundsNow(intent: Intent, generation: Int) {
        val nextPlan = intent.getStringExtra(EXTRA_PROFILE_PLAN)
            ?.let { raw -> runCatching { ProfileRunPlan.fromJson(JSONObject(raw)) }.getOrNull() }
            ?: run {
                TcptunState.appendLog("connection update ignored: invalid profile plan")
                return
            }
        val currentPlan = runningPlan
        val changedIds = currentPlan?.let { current ->
            (current.activeIds - nextPlan.activeIds) + (nextPlan.activeIds - current.activeIds)
        }.orEmpty()
        if (
            tun == null || stopping || currentPlan == null ||
            currentPlan.profiles != nextPlan.profiles
        ) {
            TcptunState.appendLog("reloading VPN connection configuration")
            startFromIntent(intent)
            return
        }
        if (changedIds.isEmpty()) return

        val changedProfiles = currentPlan.profiles.filter { it.id in changedIds }
        try {
            changedProfiles.forEach { profile ->
                if (generation != lifecycleGeneration.get()) return
                setOutboundRunning(profile, profile.id in nextPlan.activeIds)
            }
            runningPlan = nextPlan
            TcptunState.initializeProfileHealth(nextPlan.activeProfiles)
            // Pool membership changed: force member probes so Start/StopOutbound
            // immediately updates balance penalties for selection.
            requestMemberHealthProbe("active connections changed")
            saveDesiredRunningPlan(this, nextPlan)
            updateNotification(runningNotificationState(nextPlan))
            updateBridgeDiagnostics()
        } catch (err: Exception) {
            TcptunState.appendLog("connection update failed: ${err.message ?: err.javaClass.simpleName}")
            runCatching {
                changedProfiles.forEach { profile ->
                    setOutboundRunning(profile, profile.id in currentPlan.activeIds)
                }
            }.onFailure { rollbackError ->
                TcptunState.appendLog("connection update rollback failed: ${rollbackError.message}")
            }
            ProfileStore.save(this, ProfileStore.load(this).copy(activeIds = currentPlan.activeIds))
            TcptunState.notifyProfileStateChanged()
            saveDesiredRunningPlan(this, currentPlan)
            runningPlan = currentPlan
            updateNotification(runningNotificationState(currentPlan))
        }
    }

    private fun setOutboundRunning(profile: AppConfig, shouldRun: Boolean) {
        val tag = profile.runtimeOutboundTag()
        synchronized(bridgeLock) {
            if (shouldRun) {
                bridge.startOutbound(tag)
            } else {
                bridge.stopOutbound(tag, force = true, timeoutMillis = OUTBOUND_STOP_TIMEOUT_MS)
            }
        }
        if (shouldRun) {
            TcptunState.setProfileHealth(profile.id, ProfileHealth())
        } else {
            TcptunState.removeProfileHealth(profile.id)
        }
        TcptunState.appendLog("connection ${profile.name}: ${if (shouldRun) "started" else "stopped"}")
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
        tcpingExecutor.execute {
            val profiles = runningPlan?.activeProfiles.orEmpty()
            if (tun == null || stopping || profiles.isEmpty()) {
                TcptunState.failTcping(requestId, "no running connections")
                return@execute
            }
            val results = mutableListOf<TcpingLinkResult>()
            profiles.forEachIndexed { index, profile ->
                if (!TcptunState.isCurrentTcping(requestId)) return@execute
                TcptunState.beginTcpingStep(requestId, index + 1, profiles.size, profile.name)
                val probe = runCatching {
                    probeOutboundWithTransientQuicRetry(
                        totalTimeoutMillis = TCPING_OUTBOUND_TOTAL_TIMEOUT_MS,
                        attemptTimeoutMillis = TCPING_OUTBOUND_TIMEOUT_MS,
                        isActive = { TcptunState.isCurrentTcping(requestId) },
                    ) { timeoutMillis ->
                        bridge.probeOutbound(
                            tag = profile.runtimeOutboundTag(),
                            host = host,
                            port = port,
                            timeoutMillis = timeoutMillis,
                        )
                    }
                }
                if (probe.exceptionOrNull() is CancellationException) return@execute
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
        val suffix = if (plan.activeProfiles.size == 1) "connection" else "connections"
        return "Running · ${plan.activeProfiles.size} $suffix"
    }

    private fun buildTun(mtu: Int): android.os.ParcelFileDescriptor {
        registerUnderlyingNetworkCallback()
        return Builder()
            .setSession(VPN_DISPLAY_NAME)
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
        if (underlyingNetworkCallbackRegistered) return
        val callback = underlyingNetworkCallback ?: object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                    updateUnderlyingNetwork(network, capabilities)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateUnderlyingNetwork(network, capabilities)
            }

            override fun onLost(network: Network) {
                val selection = synchronized(underlyingNetworkLock) {
                    availableUnderlyingNetworks.remove(network)
                    selectUnderlyingNetworkLocked()
                }
                applyUnderlyingNetwork(selection, "underlying network lost")
            }
        }.also { underlyingNetworkCallback = it }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivity.registerNetworkCallback(request, callback)
            underlyingNetworkCallbackRegistered = true
            TcptunState.appendLog("underlying network callback registered")
        }.onFailure { err ->
            TcptunState.appendLog("underlying network callback unavailable: ${err.message}")
        }
    }

    private fun unregisterUnderlyingNetworkCallback() {
        if (!underlyingNetworkCallbackRegistered) return
        underlyingNetworkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { err -> TcptunState.appendLog("underlying network callback unregister failed: ${err.message}") }
        }
        underlyingNetworkCallbackRegistered = false
        synchronized(underlyingNetworkLock) { availableUnderlyingNetworks.clear() }
        currentDefaultNetwork = null
        underlyingNetworkSelectionInitialized = false
        updateUnderlyingDiagnostics(null)
    }

    private fun updateUnderlyingNetwork(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return
        }
        val selection = synchronized(underlyingNetworkLock) {
            availableUnderlyingNetworks[network] = capabilities
            selectUnderlyingNetworkLocked()
        }
        applyUnderlyingNetwork(selection, "underlying network changed")
    }

    private fun selectUnderlyingNetworkLocked(): Network? {
        return availableUnderlyingNetworks.maxByOrNull { (_, capabilities) ->
            underlyingNetworkScore(
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }?.key
    }

    private fun applyUnderlyingNetwork(network: Network?, reason: String) {
        val previous = currentDefaultNetwork
        if (previous == network) return
        val initialSelection = !underlyingNetworkSelectionInitialized
        underlyingNetworkSelectionInitialized = true
        currentDefaultNetwork = network
        updateUnderlyingDiagnostics(network)
        TcptunState.appendLog("underlying network selected: ${network ?: "none"}")
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
            .onFailure { err -> TcptunState.appendLog("set underlying network failed: ${err.message}") }
        if (tun != null && !stopping) {
            if (!initialSelection && TcptunState.status == "Running") {
                requestBridgeRestart(reason)
            } else {
                requestMemberHealthProbe(reason)
            }
        }
    }

    private fun requestStopVpn() {
        clearDesiredRunningConfig(this)
        ProfileStore.clearActive(this)
        TcptunState.clearTcping()
        lifecycleGeneration.incrementAndGet()
        stopping = true
        bridgeRestartRequestGeneration.incrementAndGet()
        bridgeReadyWaiter.getAndSet(null)?.future?.completeExceptionally(
            IllegalStateException("tcptun stop requested"),
        )
        lifecycleExecutor.execute { stopVpn() }
    }

    private fun closeTunAfterBridgeStop() {
        val activeTun = tun ?: return
        tun = null
        TcptunState.appendLog("closing VPN TUN")
        runCatching { activeTun.close() }
            .onFailure { err -> TcptunState.appendLog("VPN TUN close failed: ${err.message}") }
    }

    override fun onRevoke() {
        TcptunState.appendLog("VPN permission revoked")
        requestStopVpn()
        super.onRevoke()
    }

    private fun stopVpn(setStopped: Boolean = true, clearSavedConfig: Boolean = true, stopSelfService: Boolean = true) {
        if (!teardownInProgress.compareAndSet(false, true)) return
        stopping = true
        try {
            bridgeRestartRequestGeneration.incrementAndGet()
            if (setStopped) {
                TcptunState.setStatus("Stopping")
            }
            stopBridgeMonitor()
            unregisterUnderlyingNetworkCallback()
            if (clearSavedConfig) {
                clearDesiredRunningConfig(this)
            }
            TcptunState.appendLog("stopping tcptun bridge")
            // Engine.Stop closes the Go-owned duplicate and waits for the TUN
            // inbound to finish. Only then may VpnService close its original.
            stopBridge()
            closeTunAfterBridgeStop()
            appIdentityProvider.clear()
            tunMtu = DEFAULT_VPN_MTU
            activeSocksPort = DEFAULT_SOCKS_PORT
            activeSocksUsername = ""
            activeSocksPassword = ""
            activeLocalProxyProtocol = DefaultLocalProxyProtocol
            activeSocksListenAll = false
            activeRouteLocalProxyTraffic = false
            powerSavingMode = true
            lastMemberHealthProbeAtElapsedMs = 0L
            runningPlan = null
            TcptunState.updateDiagnostics {
                it.copy(
                    bridgeStatus = "Stopped",
                    bridgeActiveConnections = 0,
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
            if (setStopped) {
                TcptunState.setStatus("Stopped")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopSelfService) {
                stopSelf()
            }
        } finally {
            teardownInProgress.set(false)
        }
    }

    override fun onDestroy() {
        activeMonitorWakeCallback.compareAndSet(monitorWakeCallback, null)
        lifecycleGeneration.incrementAndGet()
        tcpingExecutor.shutdownNow()
        memberHealthExecutor.shutdownNow()
        stopVpn(setStopped = TcptunState.status != "Error", clearSavedConfig = false)
        runCatching { synchronized(bridgeLock) { bridge.close() } }
            .onFailure { err -> TcptunState.appendLog("tcptun engine close failed: ${err.message}") }
        lifecycleExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applyCachedRuntimeSettings(settings: RuntimeSettings) {
        tunMtu = settings.mtu
        activeSocksPort = settings.socksPort
        activeSocksUsername = settings.socksUsername
        activeSocksPassword = settings.socksPassword
        activeLocalProxyProtocol = settings.localProxyProtocol
        activeSocksListenAll = settings.socksListenAll
        activeRouteLocalProxyTraffic = settings.routeLocalProxyTraffic
        powerSavingMode = settings.powerSavingMode
    }

    private fun currentStructuralRuntimeSettings(): RuntimeSettings {
        return RuntimeSettings(
            mtu = tunMtu,
            powerSavingMode = powerSavingMode,
            socksPort = activeSocksPort,
            localProxyProtocol = activeLocalProxyProtocol,
            socksListenAll = activeSocksListenAll,
            socksUsername = activeSocksUsername,
            socksPassword = activeSocksPassword,
            routeLocalProxyTraffic = activeRouteLocalProxyTraffic,
        )
    }

    private fun startBridge(
        configJson: String,
        plan: ProfileRunPlan,
        vpnTun: android.os.ParcelFileDescriptor,
        mtu: Int,
    ) {
        startBridgeSession(
            configJson = configJson,
            disabledOutboundTags = initiallyDisabledOutboundTags(plan),
            readyTimeoutMs = BRIDGE_READY_TIMEOUT_MS,
            vpnTun = vpnTun,
            mtu = mtu,
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
    ) {
        val epoch = TcptunState.beginBridgeSession()
        activeBridgeEpoch = epoch
        val waiter = BridgeReadyWaiter(epoch)
        bridgeReadyWaiter.getAndSet(waiter)?.future?.completeExceptionally(
            IllegalStateException("superseded by a newer tcptun start"),
        )
        bridge.setLogCallback(TcptunState::appendLog)
        bridge.setStatusCallback { eventJson -> onBridgeStatusEvent(epoch, eventJson) }
        bridge.setSocketProtector { fd -> protect(fd) }
        bridge.setAppIdentityProvider(appIdentityProvider::identify)
        configureFlowAnalysis(readRuntimeSettings(this).flowAnalysisApp, epoch)
        TcptunState.applyBridgeStatusEvent(epoch, bridge.statusJson())
        val sessionId = synchronized(bridgeLock) {
            bridge.configure(configJson)
            bridge.setTun(vpnTun.fd, mtu)
            val startedSessionId = bridge.start(disabledOutboundTags)
            check(startedSessionId > 0) { "tcptun engine returned an invalid session ID" }
            bridgeConfigJson = configJson
            startedSessionId
        }
        TcptunState.appendLog("tcptun bridge session started: $sessionId")
        try {
            waiter.future.get(readyTimeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            bridgeReadyWaiter.compareAndSet(waiter, null)
        }
        TcptunState.updateDiagnostics { it.copy(bridgeStatus = bridge.status()) }
    }

    private fun stopBridge() {
        bridgeReadyWaiter.getAndSet(null)?.future?.completeExceptionally(
            IllegalStateException("tcptun stopped before core became ready"),
        )
        synchronized(bridgeLock) {
            bridgeConfigJson = null
            runCatching { bridge.stop() }
                .onFailure { err -> TcptunState.appendLog("tcptun engine stop failed: ${err.message}") }
        }
        activeBridgeEpoch = 0L
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

    private fun restartBridge(reason: String) {
        val configJson = bridgeConfigJson ?: return
        if (tun == null) return
        if (stopping) return
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastBridgeRestartAtMs
        if (elapsedMs < BRIDGE_RESTART_MIN_INTERVAL_MS) {
            val waitSeconds = ((BRIDGE_RESTART_MIN_INTERVAL_MS - elapsedMs) / 1_000).coerceAtLeast(1)
            TcptunState.appendLog("tcptun bridge restart skipped by cooldown: $reason; wait ${waitSeconds}s")
            return
        }
        lastBridgeRestartAtMs = now
        bridgeRestarting = true
        TcptunState.appendLog("restarting tcptun bridge transaction: $reason")
        TcptunState.updateDiagnostics { it.copy(lastRestartReason = reason, bridgeStatus = "Restarting") }
        try {
            stopBridge()
            if (stopping) return
            closeTunAfterBridgeStop()
            Thread.sleep(BRIDGE_RESTART_DELAY_MS)
            if (stopping) return
            val replacementTun = buildTun(tunMtu)
            tun = replacementTun
            startBridge(
                configJson,
                runningPlan ?: error("running profile plan is unavailable"),
                replacementTun,
                tunMtu,
            )
            TcptunState.appendLog("tcptun bridge transaction restarted")
            updateBridgeDiagnostics()
            // The previous runtime's balance observations were discarded by
            // the restart, so seed health only after the replacement is ready.
            requestMemberHealthProbe("bridge restarted: $reason")
        } catch (err: Exception) {
            TcptunState.error("tcptun bridge restart failed: ${err.message}")
            stopVpn(setStopped = false, clearSavedConfig = false)
            throw err
        } finally {
            bridgeRestarting = false
        }
    }

    private fun requestBridgeRestart(reason: String) {
        val generation = bridgeRestartRequestGeneration.incrementAndGet()
        scheduleBridgeRestart(reason, generation)
    }

    private fun scheduleBridgeRestart(reason: String, generation: Int) {
        val remainingCooldownMs = (
            BRIDGE_RESTART_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastBridgeRestartAtMs)
        ).coerceAtLeast(0)
        runCatching {
            lifecycleExecutor.schedule({
                if (generation != bridgeRestartRequestGeneration.get() || stopping || tun == null) return@schedule
                val remainingMs = (
                    BRIDGE_RESTART_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastBridgeRestartAtMs)
                ).coerceAtLeast(0)
                if (remainingMs > 0) {
                    scheduleBridgeRestart(reason, generation)
                    return@schedule
                }
                runCatching { restartBridge(reason) }
                    .onFailure { err -> TcptunState.appendLog("tcptun bridge restart failed: ${err.message}") }
            }, remainingCooldownMs, TimeUnit.MILLISECONDS)
        }.onFailure { err ->
            if (!stopping) TcptunState.appendLog("tcptun bridge restart scheduling failed: ${err.message}")
        }
    }

    private fun requestRuntimeSettingsRestart(reason: String, forceRestart: Boolean) {
        val generation = synchronized(runtimeSettingsApplyLock) {
            runtimeSettingsApplyGeneration += 1
            runtimeSettingsForceRestartPending = runtimeSettingsForceRestartPending || forceRestart
            runtimeSettingsApplyGeneration
        }
        TcptunState.appendLog("runtime settings apply requested: $reason")
        Thread {
            try {
                Thread.sleep(RUNTIME_SETTINGS_RESTART_DEBOUNCE_MS)
                val forceThisApply = synchronized(runtimeSettingsApplyLock) {
                    if (generation != runtimeSettingsApplyGeneration) {
                        null
                    } else {
                        runtimeSettingsForceRestartPending.also {
                            runtimeSettingsForceRestartPending = false
                        }
                    }
                } ?: return@Thread
                val plan = readDesiredRunningPlan(this) ?: run {
                    TcptunState.appendLog("runtime settings apply skipped: no running profile")
                    if (tun == null) stopSelf()
                    return@Thread
                }
                if (tun == null || stopping) {
                    TcptunState.appendLog("runtime settings apply skipped: VPN is not running")
                    if (tun == null) stopSelf()
                    return@Thread
                }
                val settings = readRuntimeSettings(this)
                val structuralChange = BridgeHealthPolicy.requiresRuntimeRestart(
                    forceRestart = forceThisApply,
                    currentStructuralRuntimeSettings(),
                    settings,
                )
                if (!structuralChange) {
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
                        "runtime settings applied without VPN restart: power-saving=${settings.powerSavingMode}",
                    )
                    wakeBridgeMonitor()
                    return@Thread
                }
                applyCachedRuntimeSettings(settings)
                val restartReason = if (forceThisApply) "route rules changed" else reason
                TcptunState.updateDiagnostics { it.copy(lastRestartReason = restartReason) }
                TcptunState.appendLog("restarting VPN to apply runtime settings")
                startFromIntent(startIntent(this, plan))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (err: Exception) {
                TcptunState.appendLog("runtime settings apply failed: ${err.message}")
            }
        }.apply {
            name = "TcptunRuntimeSettingsApply"
            isDaemon = true
            start()
        }
    }

    private fun requestFlowAnalysisUpdate() {
        lifecycleExecutor.execute {
            val packageName = readRuntimeSettings(this).flowAnalysisApp
            val epoch = activeBridgeEpoch
            TcptunState.setFlowAnalysisApp(packageName)
            if (epoch <= 0 || bridgeConfigJson == null || tun == null || stopping) {
                TcptunState.appendLog("flow analysis saved: ${packageName.ifBlank { "disabled" }}")
                return@execute
            }
            runCatching {
                synchronized(bridgeLock) {
                    configureFlowAnalysis(packageName, epoch)
                }
            }.onSuccess {
                TcptunState.appendLog("flow analysis switched without VPN restart: ${packageName.ifBlank { "disabled" }}")
            }.onFailure { err ->
                TcptunState.appendLog("flow analysis update failed: ${err.message}")
            }
        }
    }

    private fun onBridgeStatusEvent(epoch: Long, eventJson: String) {
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
        handleBridgeStatusEvent(event)
    }

    private fun handleBridgeStatusEvent(event: BridgeStatusEvent) {
        val eventState = event.state.lowercase()
        if (eventState == "degraded") {
            // Refresh per-member balance health so recovered or failing pool
            // members re-score without waiting for a UI refresh.
            requestMemberHealthProbe("tcptun reported degraded")
            return
        }
        if (eventState != "error" && eventState != "stopped") return
        if (stopping || bridgeRestarting || tun == null) return
        requestMemberHealthProbe("tcptun reported $eventState")
        requestBridgeRestart("tcptun reported $eventState")
    }

    private fun startBridgeMonitor() {
        stopBridgeMonitor()
        val generation = monitorGeneration.incrementAndGet()
        val initialHandledWakeGeneration = monitorWakeGeneration.get()
        monitorThread = Thread {
            var bridgeFailures = 0
            var handledWakeGeneration = initialHandledWakeGeneration
            while (
                generation == monitorGeneration.get() &&
                !stopping &&
                !Thread.currentThread().isInterrupted
            ) {
                try {
                    val delayMs = BridgeHealthPolicy.nextCheckDelayMs(
                        powerSaving = powerSavingMode,
                        confirmingFailure = bridgeFailures > 0,
                    )
                    val intervalSeconds = delayMs?.div(1_000) ?: 0
                    val eventDriven = delayMs == null
                    val diagnostics = TcptunState.state.value.diagnostics
                    if (
                        diagnostics.healthCheckEventDriven != eventDriven ||
                        diagnostics.healthCheckIntervalSeconds != intervalSeconds
                    ) {
                        TcptunState.updateDiagnostics {
                            it.copy(
                                healthCheckEventDriven = eventDriven,
                                healthCheckIntervalSeconds = intervalSeconds,
                            )
                        }
                    }
                    // Preserve the last generation actually consumed. A wake that
                    // arrives while the check runs is handled by the next iteration.
                    handledWakeGeneration = awaitBridgeHealthEvent(
                        handledWakeGeneration = handledWakeGeneration,
                        timeoutMs = delayMs,
                    )
                    if (generation != monitorGeneration.get() || tun == null || stopping) continue
                    val failure = vpnHealthFailure(generation)
                    if (generation != monitorGeneration.get() || stopping) return@Thread
                    if (failure == null) {
                        bridgeFailures = 0
                    } else {
                        TcptunState.appendLog("VPN health check failed: ${failure.reason}")
                        bridgeFailures += 1
                        if (bridgeFailures >= HEALTH_FAILURE_LIMIT) {
                            bridgeFailures = 0
                            requestBridgeRestart(failure.reason)
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (err: Exception) {
                    TcptunState.appendLog("tcptun bridge monitor error: ${err.message}")
                }
            }
        }.apply {
            name = "TcptunBridgeMonitor"
            isDaemon = true
            start()
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

    private fun vpnHealthFailure(monitorEpoch: Int): HealthFailure? {
        val status = runCatching { bridge.status() }.getOrElse { err ->
            TcptunState.updateDiagnostics { it.copy(bridgeStatus = "Unknown", localProxyReachable = false) }
            return HealthFailure("status unavailable: ${err.message}")
        }
        val uiVisible = TcptunState.isUiVisible
        val probeLocalProxy = BridgeHealthPolicy.shouldProbeLocalProxy(uiVisible)
        val localProxyReachable = if (probeLocalProxy) canConnectLocalProxy() else true
        val localProxyAddress = activeLocalSocksConnectAddr()
        // Avoid StateFlow churn while hanging: only publish when something changed
        // or the user is looking at diagnostics.
        val previous = TcptunState.state.value.diagnostics
        val nextLocalProxyReachable = if (probeLocalProxy) localProxyReachable else previous.localProxyReachable
        if (
            uiVisible ||
            previous.bridgeStatus != status ||
            previous.localProxyReachable != nextLocalProxyReachable ||
            previous.localProxyAddress != localProxyAddress ||
            previous.localProxyPort != activeSocksPort
        ) {
            TcptunState.updateDiagnostics {
                it.copy(
                    bridgeStatus = status,
                    localProxyReachable = nextLocalProxyReachable,
                    localProxyAddress = localProxyAddress,
                    localProxyPort = activeSocksPort,
                )
            }
        }
        if (status != "Running") {
            return HealthFailure("bridge status is $status")
        }
        if (probeLocalProxy && !localProxyReachable) {
            return HealthFailure("local proxy $localProxyAddress is not accepting connections")
        }
        // Member probes update Go balance scores (observeHealthProbe). They are
        // event-driven with a min interval and do not require the UI.
        // Aggregate SOCKS/HTTP probes stay UI-only (expensive path through the pool).
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

    private fun probeActiveMembers(targets: List<UpstreamProbeTarget>, monitorEpoch: Int) {
        // A full-JSON profile can use a selector as its default outbound. It is
        // represented as one app profile, so only the aggregate SOCKS/TLS probe
        // can describe its health without guessing at its internal members.
        val profiles = runningPlan?.activeProfiles.orEmpty().filter { it.rawConfigJson.isBlank() }
        if (profiles.isEmpty() || targets.isEmpty()) return
        val tasks = profiles.map { profile ->
            Callable { probeMember(profile, targets) }
        }
        val batches = (profiles.size + MAX_CONCURRENT_MEMBER_HEALTH_PROBES - 1) / MAX_CONCURRENT_MEMBER_HEALTH_PROBES
        val timeoutMs = MEMBER_HEALTH_PROBE_TIMEOUT_MS.toLong() * targets.size * batches + MEMBER_HEALTH_PROBE_GRACE_MS
        val futures = memberHealthExecutor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
        if (monitorEpoch != monitorGeneration.get() || stopping) return
        futures.forEachIndexed { index, future ->
            val profile = profiles[index]
            if (profile.id !in runningPlan?.activeIds.orEmpty()) return@forEachIndexed
            val result = runCatching {
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
            val health = if (result.elapsedMs != null) {
                ProfileHealth(
                    status = ProfileHealthStatus.Healthy,
                    latencyMs = result.elapsedMs,
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
                    error = result.error,
                )
            }
            TcptunState.setProfileHealth(profile.id, health)
            if (previous?.status != health.status) {
                val detail = health.latencyMs?.let { "${it}ms" } ?: health.error
                TcptunState.appendLog("connection ${profile.name} health: ${health.status.name.lowercase()} $detail")
            }
        }
        refreshProfileHealthFromCore(profiles)
    }

    private fun updateRawProfileHealth(failure: String?) {
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
        TcptunState.setProfileHealth(profile.id, health)
    }

    private fun refreshProfileHealthFromCore(profiles: List<AppConfig>) {
        val profileByTag = profiles.associateBy(AppConfig::runtimeOutboundTag)
        runCatching { JSONArray(bridge.outboundsStatusJson()) }
            .onSuccess { statuses ->
                for (index in 0 until statuses.length()) {
                    val status = statuses.optJSONObject(index) ?: continue
                    val profile = profileByTag[status.optString("tag")] ?: continue
                    if (profile.id !in runningPlan?.activeIds.orEmpty()) continue
                    val healthStatus = when (status.optString("health").lowercase()) {
                        "healthy" -> ProfileHealthStatus.Healthy
                        "degraded" -> ProfileHealthStatus.Degraded
                        else -> continue
                    }
                    val previous = TcptunState.state.value.profileHealth[profile.id]
                    TcptunState.setProfileHealth(
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

    private fun probeMember(profile: AppConfig, targets: List<UpstreamProbeTarget>): MemberHealthProbeResult {
        val failures = mutableListOf<String>()
        for (target in targets) {
            val elapsed = runCatching {
                bridge.probeOutboundHealth(
                    tag = profile.runtimeOutboundTag(),
                    host = target.host,
                    port = target.port,
                    timeoutMillis = MEMBER_HEALTH_PROBE_TIMEOUT_MS,
                )
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

    private fun updateBridgeDiagnostics() {
        val status = runCatching { bridge.status() }.getOrDefault("Unknown")
        val runtimeSnapshot = bridgeRuntimeSnapshot()
        val localProxyReachable = canConnectLocalProxy()
        TcptunState.updateDiagnostics {
            it.copy(
                bridgeStatus = status,
                localProxyReachable = localProxyReachable,
                localProxyAddress = activeLocalSocksConnectAddr(),
                localProxyPort = activeSocksPort,
                bridgeActiveConnections = runtimeSnapshot?.activeConnections ?: it.bridgeActiveConnections,
                bridgeClientIps = runtimeSnapshot?.clientIps ?: it.bridgeClientIps,
                bridgeMuxSources = runtimeSnapshot?.muxSources ?: it.bridgeMuxSources,
                bridgeMuxSessions = runtimeSnapshot?.muxSessions ?: it.bridgeMuxSessions,
                bridgeMuxStreams = runtimeSnapshot?.muxStreams ?: it.bridgeMuxStreams,
            )
        }
    }

    private fun bridgeRuntimeSnapshot(): BridgeRuntimeSnapshot? {
        return runCatching {
            val json = JSONObject(bridge.statusJson())
            BridgeRuntimeSnapshot(
                activeConnections = json.optInt("active_connections", 0),
                clientIps = normalizeClientIps(
                    buildList {
                        json.optJSONArray("client_ips")?.let { values ->
                            for (index in 0 until values.length()) add(values.optString(index))
                        }
                    },
                ),
                muxSources = json.optInt("mux_sources", 0),
                muxSessions = json.optInt("mux_sessions", 0),
                muxStreams = json.optInt("mux_streams", 0),
            )
        }.getOrNull()
    }

    private fun refreshBridgeClientIps() {
        if (stopping || tun == null || TcptunState.status != "Running") return
        val snapshot = bridgeRuntimeSnapshot() ?: return
        TcptunState.updateDiagnostics {
            it.copy(
                bridgeActiveConnections = snapshot.activeConnections,
                bridgeClientIps = snapshot.clientIps,
            )
        }
    }

    private fun canConnectLocalProxy(): Boolean {
        return runCatching {
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

    private fun shouldProbeMemberHealth(): Boolean {
        val force = forceNextMemberHealthProbe.compareAndSet(true, false)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val allowed = BridgeHealthPolicy.shouldProbeMemberHealth(
            force = force,
            lastProbeAtMs = lastMemberHealthProbeAtElapsedMs,
            nowMs = nowElapsedMs,
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
        return runCatching {
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
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(data, offset, length - offset)
            if (read < 0) {
                error("connection closed")
            }
            offset += read
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
            .setContentTitle(VPN_DISPLAY_NAME)
            .setContentText(state)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(state != "Stopped")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            VPN_DISPLAY_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Silent VPN service status"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_VPN_MTU = 1400
        private const val VPN_DNS_ADDRESS = "10.77.0.1"
        private const val VPN_DISPLAY_NAME = "TcpTun VPN"
        private const val CHANNEL_ID = "tcptun_vpn_silent"
        private const val NOTIFICATION_ID = 1001
        private const val HEALTH_FAILURE_LIMIT = 2
        private const val BRIDGE_RESTART_DELAY_MS = 300L
        private const val BRIDGE_RESTART_MIN_INTERVAL_MS = 30_000L
        private const val BRIDGE_READY_TIMEOUT_MS = 15_000L
        private const val OUTBOUND_STOP_TIMEOUT_MS = 15_000L
        private const val TCPING_OUTBOUND_TIMEOUT_MS = 3_000L
        private const val TCPING_OUTBOUND_TOTAL_TIMEOUT_MS = 20_000L
        private const val MEMBER_HEALTH_PROBE_TIMEOUT_MS = 3_000L
        private const val MEMBER_HEALTH_PROBE_GRACE_MS = 1_000L
        private const val MAX_CONCURRENT_MEMBER_HEALTH_PROBES = 4
        private const val RUNTIME_SETTINGS_RESTART_DEBOUNCE_MS = 800L
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
        private const val KEY_RUNTIME_SOCKS_PORT = "runtimeSocksPort"
        private const val KEY_RUNTIME_LOCAL_PROXY_PROTOCOL = "runtimeLocalProxyProtocol"
        private const val KEY_RUNTIME_SOCKS_LISTEN_ALL = "runtimeSocksListenAll"
        private const val KEY_RUNTIME_SOCKS_USERNAME = "runtimeSocksUsername"
        private const val KEY_RUNTIME_SOCKS_PASSWORD = "runtimeSocksPassword"
        private const val KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC = "runtimeRouteLocalProxyTraffic"
        private const val KEY_RUNTIME_FLOW_ANALYSIS_APP = "runtimeFlowAnalysisApp"
        private val forceNextUpstreamProbe = AtomicBoolean(false)
        private val forceNextMemberHealthProbe = AtomicBoolean(false)
        private val activeMonitorWakeCallback = AtomicReference<(() -> Unit)?>(null)
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
            val runtimeSettings = readRuntimeSettings(context)
            val plan = sourcePlan.normalized()
            val localListenAddr = localSocksListenAddr(runtimeSettings)
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(
                    EXTRA_CONFIG,
                    plan.toBridgeJson(
                        localListenAddr,
                        localProxyProtocol = runtimeSettings.localProxyProtocol,
                        socks5Username = runtimeSettings.socksUsername,
                        socks5Password = runtimeSettings.socksPassword,
                        managedRouteRules = RouteRuleStore.load(context),
                        routeLocalProxyTraffic = runtimeSettings.routeLocalProxyTraffic,
                    ),
                )
                .putExtra(EXTRA_PROFILE_PLAN, plan.toJson().toString())
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
         */
        fun requestMemberHealthProbe(reason: String) {
            forceNextMemberHealthProbe.set(true)
            requestHealthCheck(reason)
        }

        @Deprecated("Use requestHealthCheck", ReplaceWith("requestHealthCheck(reason)"))
        fun requestDenseHealthCheck(reason: String) = requestHealthCheck(reason)

        fun requestUiVisibleHealthCheck() {
            // Full UI-driven refresh: status + local proxy + member balance health
            // + single aggregate upstream probe through the pool.
            forceNextUpstreamProbe.set(true)
            forceNextMemberHealthProbe.set(true)
            TcptunState.appendLog("bridge health check requested: app visible")
            activeMonitorWakeCallback.get()?.invoke()
        }

        fun readRuntimeSettings(context: Context): RuntimeSettings {
            val prefs = context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            val mtu = prefs.getInt(KEY_RUNTIME_MTU, DEFAULT_VPN_MTU).coerceIn(1280, 1500)
            val powerSavingMode = prefs.getBoolean(KEY_RUNTIME_POWER_SAVING, true)
            val socksPort = prefs.getInt(KEY_RUNTIME_SOCKS_PORT, DEFAULT_SOCKS_PORT).coerceIn(1, 65535)
            return RuntimeSettings(
                mtu = mtu,
                powerSavingMode = powerSavingMode,
                socksPort = socksPort,
                localProxyProtocol = normalizeLocalProxyProtocol(
                    prefs.getString(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, DefaultLocalProxyProtocol).orEmpty(),
                ),
                socksListenAll = prefs.getBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, false),
                socksUsername = prefs.getString(KEY_RUNTIME_SOCKS_USERNAME, "").orEmpty(),
                socksPassword = prefs.getString(KEY_RUNTIME_SOCKS_PASSWORD, "").orEmpty(),
                routeLocalProxyTraffic = prefs.getBoolean(KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC, false),
                flowAnalysisApp = normalizeFlowAnalysisApp(
                    prefs.getString(KEY_RUNTIME_FLOW_ANALYSIS_APP, "").orEmpty(),
                ),
            )
        }

        fun writeRuntimeSettings(context: Context, settings: RuntimeSettings) {
            val normalizedPowerSavingMode = settings.powerSavingMode
            val normalizedSocksPort = settings.socksPort.coerceIn(1, 65535)
            val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(settings.localProxyProtocol)
            val normalizedRouteLocalProxyTraffic = settings.routeLocalProxyTraffic
            val normalizedFlowAnalysisApp = normalizeFlowAnalysisApp(settings.flowAnalysisApp)
            val normalizedSettings = settings.copy(
                powerSavingMode = normalizedPowerSavingMode,
                socksPort = normalizedSocksPort,
                localProxyProtocol = normalizedLocalProxyProtocol,
                socksUsername = settings.socksUsername,
                socksPassword = settings.socksPassword,
                routeLocalProxyTraffic = normalizedRouteLocalProxyTraffic,
                flowAnalysisApp = normalizedFlowAnalysisApp,
            )
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RUNTIME_MTU, settings.mtu.coerceIn(1280, 1500))
                .putBoolean(KEY_RUNTIME_POWER_SAVING, normalizedPowerSavingMode)
                .putInt(KEY_RUNTIME_SOCKS_PORT, normalizedSocksPort)
                .putString(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, normalizedLocalProxyProtocol)
                .putBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, settings.socksListenAll)
                .putString(KEY_RUNTIME_SOCKS_USERNAME, settings.socksUsername)
                .putString(KEY_RUNTIME_SOCKS_PASSWORD, settings.socksPassword)
                .putBoolean(KEY_RUNTIME_ROUTE_LOCAL_PROXY_TRAFFIC, normalizedRouteLocalProxyTraffic)
                .putString(KEY_RUNTIME_FLOW_ANALYSIS_APP, normalizedFlowAnalysisApp)
                .apply()
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
                    "power-saving=$normalizedPowerSavingMode " +
                    "route-local-proxy=$normalizedRouteLocalProxyTraffic " +
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

        private fun saveDesiredRunningPlan(context: Context, plan: ProfileRunPlan) {
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_RUNNING_PLAN, plan.normalized().toJson().toString())
                .putInt(KEY_RUNNING_CONFIG_VERSION, RUNNING_CONFIG_VERSION)
                .putBoolean(KEY_DESIRED_RUNNING, true)
                .commit()
        }

        private fun readDesiredRunningPlan(context: Context): ProfileRunPlan? {
            val prefs = context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_DESIRED_RUNNING, false)) return null
            return when (prefs.getInt(KEY_RUNNING_CONFIG_VERSION, 1)) {
                RUNNING_CONFIG_VERSION, 2 -> prefs.getString(KEY_LAST_RUNNING_PLAN, null)?.let { raw ->
                    runCatching { ProfileRunPlan.fromJson(JSONObject(raw)) }.getOrNull()
                }
                1 -> prefs.getString(KEY_LAST_RUNNING_CONFIG, null)?.let { raw ->
                    runCatching {
                        val config = AppConfig.fromJson(JSONObject(raw))
                        ProfileRunPlan(listOf(config)).normalized()
                    }.getOrNull()
                }
                else -> null
            }
        }

        private fun clearDesiredRunningConfig(context: Context) {
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DESIRED_RUNNING, false)
                .commit()
        }

    }
}
