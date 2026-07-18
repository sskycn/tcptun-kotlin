package com.tcptun.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class RuntimeSettings(
    val mtu: Int = TcptunVpnService.DEFAULT_VPN_MTU,
    val udpEnabled: Boolean = true,
    val powerSavingMode: Boolean = false,
    val socksPort: Int = TcptunVpnService.DEFAULT_SOCKS_PORT,
    val localProxyProtocol: String = DefaultLocalProxyProtocol,
    val socksListenAll: Boolean = false,
    val routeExternalSources: Boolean = false,
    val directFirst: Boolean = false,
    val probeTimeout: String = TcptunVpnService.DEFAULT_PROBE_TIMEOUT,
    val failureThreshold: Int = TcptunVpnService.DEFAULT_FAILURE_THRESHOLD,
    val positiveTtl: String = TcptunVpnService.DEFAULT_POSITIVE_TTL,
    val negativeTtl: String = TcptunVpnService.DEFAULT_NEGATIVE_TTL,
    val socksUsername: String = "",
    val socksPassword: String = "",
)

private val DurationPattern = Regex("^(?:\\d+(?:\\.\\d+)?(?:ns|us|µs|μs|ms|s|m|h))+$")

internal fun isValidDuration(value: String): Boolean = DurationPattern.matches(value.trim())

private data class UpstreamProbeTarget(
    val label: String,
    val host: String,
    val port: Int = 443,
    val path: String = "/",
    val expectedStatus: Int? = null,
)

private enum class HealthRestartTarget {
    Bridge,
    Tunnel,
}

private data class HealthFailure(
    val reason: String,
    val restartTarget: HealthRestartTarget,
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
    private val tunnelLock = Any()
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
    private val bridgeRestartRequestGeneration = AtomicInteger()
    private val bridgeReadyWaiter = AtomicReference<BridgeReadyWaiter?>(null)
    private var tun: android.os.ParcelFileDescriptor? = null
    @Volatile private var bridgeConfigJson: String? = null
    @Volatile private var runningPlan: ProfileRunPlan? = null
    @Volatile private var monitorThread: Thread? = null
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetworkCallbackRegistered = false
    private val underlyingNetworkLock = Any()
    private val availableUnderlyingNetworks = mutableMapOf<Network, NetworkCapabilities>()
    @Volatile private var currentDefaultNetwork: Network? = null
    @Volatile private var underlyingNetworkSelectionInitialized = false
    @Volatile private var stopping = false
    @Volatile private var bridgeRestarting = false
    @Volatile private var lastBridgeRestartAtMs = 0L
    @Volatile private var stableHealthSuccesses = 0
    @Volatile private var lastUpstreamProbeAtMs = 0L
    @Volatile private var lastTunnelRestartAtMs = 0L
    @Volatile private var tunnelMtu = DEFAULT_VPN_MTU
    @Volatile private var tunnelUdpEnabled = true
    @Volatile private var tunnelSocksPort = DEFAULT_SOCKS_PORT
    @Volatile private var tunnelSocksUsername = ""
    @Volatile private var tunnelSocksPassword = ""
    @Volatile private var activeSocksPort = DEFAULT_SOCKS_PORT
    @Volatile private var activeSocksUsername = ""
    @Volatile private var activeSocksPassword = ""
    @Volatile private var upstreamProbeIndex = 0
    @Volatile private var runtimeSettingsApplyGeneration = 0
    private val runtimeSettingsApplyLock = Any()
    private var deviceActivityReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerDeviceActivityReceiver()
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
            ACTION_APPLY_RUNTIME_SETTINGS -> requestRuntimeSettingsRestart("runtime settings changed")
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
        lastTunnelRestartAtMs = 0L
        lastUpstreamProbeAtMs = 0L
        val json = intent.getStringExtra(EXTRA_CONFIG) ?: run {
            TcptunState.error("missing VPN config")
            stopSelf()
            return
        }
        try {
                if (generation != lifecycleGeneration.get()) return
                TcptunState.setStatus("Starting")
                startVpnForeground("Starting")
                val fallbackConfig = AppConfig(
                    serverHost = intent.getStringExtra("serverHost").orEmpty(),
                    serverPort = intent.getStringExtra("serverPort") ?: "9443",
                    protocol = intent.getStringExtra("protocol") ?: "native",
                    transport = intent.getStringExtra("transport") ?: "raw",
                    token = intent.getStringExtra("token").orEmpty(),
                    sni = intent.getStringExtra("sni").orEmpty(),
                    path = intent.getStringExtra("path") ?: "/proxy",
                    tls = intent.getBooleanExtra("tls", false),
                    tlsInsecure = intent.getBooleanExtra("tlsInsecure", false),
                    tunnelSecurity = intent.getStringExtra("tunnelSecurity").orEmpty(),
                    flow = intent.getStringExtra("flow").orEmpty(),
                    realityPublicKey = intent.getStringExtra("realityPublicKey").orEmpty(),
                    realityShortId = intent.getStringExtra("realityShortId").orEmpty(),
                    realityFingerprint = intent.getStringExtra("realityFingerprint").orEmpty(),
                    realitySpiderX = intent.getStringExtra("realitySpiderX").orEmpty(),
                    mux = intent.getBooleanExtra("mux", true),
                    udp = intent.getBooleanExtra("udp", true),
                    upstreamProtocol = intent.getStringExtra("upstreamProtocol") ?: "socks5",
                )
                val intentProfile = intent.getStringExtra(EXTRA_PROFILE_CONFIG)
                    ?.let { raw -> runCatching { AppConfig.fromJson(JSONObject(raw)) }.getOrNull() }
                    ?: fallbackConfig
                val plan = intent.getStringExtra(EXTRA_PROFILE_PLAN)
                    ?.let { raw -> runCatching { ProfileRunPlan.fromJson(JSONObject(raw)) }.getOrNull() }
                    ?: ProfileRunPlan(listOf(intentProfile)).normalized()
                val runtimeSettings = readRuntimeSettings(this)
                val effectiveUdpEnabled = plan.profiles.any(AppConfig::udp) &&
                    runtimeSettings.udpEnabled && !runtimeSettings.powerSavingMode
                activeSocksPort = runtimeSettings.socksPort
                activeSocksUsername = runtimeSettings.socksUsername
                activeSocksPassword = runtimeSettings.socksPassword
                saveDesiredRunningPlan(this, plan)
                TcptunState.updateDiagnostics {
                    it.copy(
                        bridgeStatus = "Starting",
                        localProxyReachable = false,
                        mtu = runtimeSettings.mtu,
                        udpEnabled = effectiveUdpEnabled,
                        powerSavingMode = runtimeSettings.powerSavingMode,
                        localProxyAddress = localSocksConnectAddr(runtimeSettings),
                        localProxyPort = runtimeSettings.socksPort,
                        socketProtectEnabled = true,
                    )
                }
                startBridge(json, plan)
                if (generation != lifecycleGeneration.get()) {
                    stopVpn(setStopped = false, clearSavedConfig = false, stopSelfService = false)
                    return
                }
                val vpnTun = buildTun(runtimeSettings.mtu)
                if (generation != lifecycleGeneration.get()) {
                    runCatching { vpnTun.close() }
                    stopVpn(setStopped = false, clearSavedConfig = false, stopSelfService = false)
                    return
                }
                startTunnel(
                    vpnTun,
                    runtimeSettings.mtu,
                    effectiveUdpEnabled,
                    runtimeSettings.socksPort,
                    runtimeSettings.socksUsername,
                    runtimeSettings.socksPassword,
                )
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
            requestDenseHealthCheck("active connections changed")
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
                val result = runCatching {
                    bridge.probeOutbound(
                        tag = profile.runtimeOutboundTag(),
                        host = host,
                        port = port,
                        timeoutMillis = TCPING_OUTBOUND_TIMEOUT_MS,
                    )
                }.fold(
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
                requestDenseHealthCheck("TCPing failed on ${results.count { it.elapsedMs == null }} connection(s)")
            }
        }
    }

    private fun runningNotificationState(plan: ProfileRunPlan): String {
        val suffix = if (plan.activeProfiles.size == 1) "connection" else "connections"
        return "Running · ${plan.activeProfiles.size} $suffix"
    }

    private fun startTunnel(
        vpnTun: android.os.ParcelFileDescriptor,
        mtu: Int,
        udpEnabled: Boolean,
        socksPort: Int,
        socksUsername: String,
        socksPassword: String,
    ) {
        synchronized(tunnelLock) {
            tun = vpnTun
            tunnelMtu = mtu
            tunnelUdpEnabled = udpEnabled
            tunnelSocksPort = socksPort
            tunnelSocksUsername = socksUsername
            tunnelSocksPassword = socksPassword
            val hevConfig = writeHevConfig(mtu, udpEnabled, socksPort, socksUsername, socksPassword)
            try {
                HevSocks5Tunnel.start(hevConfig, vpnTun)
                if (!HevSocks5Tunnel.isRunning()) {
                    throw IllegalStateException("native packet engine did not enter running state")
                }
            } catch (err: Exception) {
                throw IllegalStateException("hev-socks5-tunnel failed: ${err.message}", err)
            }
        }
        TcptunState.appendLog("hev-socks5-tunnel started mtu=$mtu udp=${if (udpEnabled) "udp" else "tcp"}")
    }

    private fun writeHevConfig(
        mtu: Int,
        udpEnabled: Boolean,
        socksPort: Int,
        socksUsername: String,
        socksPassword: String,
    ): File {
        return HevSocks5Tunnel.writeConfig(
            directory = applicationContext.filesDir,
            socksHost = LOCAL_SOCKS_HOST,
            socksPort = socksPort,
            mtu = mtu,
            dnsServer = VPN_DNS_SERVER,
            udpEnabled = udpEnabled,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
        )
    }

    private fun buildTun(mtu: Int): android.os.ParcelFileDescriptor {
        registerUnderlyingNetworkCallback()
        return Builder()
            .setSession(VPN_DISPLAY_NAME)
            .setMtu(mtu)
            .addAddress("10.77.0.2", 32)
            .addAddress("fd00:7777::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(VPN_DNS_SERVER)
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
            requestDenseHealthCheck(reason)
            if (!initialSelection) requestBridgeRestart(reason)
        }
    }

    private fun requestStopVpn() {
        clearDesiredRunningConfig(this)
        ProfileStore.clearActive(this)
        TcptunState.clearTcping()
        lifecycleGeneration.incrementAndGet()
        lifecycleExecutor.execute { stopVpn() }
    }

    override fun onRevoke() {
        TcptunState.appendLog("VPN permission revoked")
        requestStopVpn()
        super.onRevoke()
    }

    private fun stopVpn(setStopped: Boolean = true, clearSavedConfig: Boolean = true, stopSelfService: Boolean = true) {
        if (stopping) return
        stopping = true
        bridgeRestartRequestGeneration.incrementAndGet()
        if (setStopped) {
            TcptunState.setStatus("Stopping")
        }
        stopBridgeMonitor()
        unregisterUnderlyingNetworkCallback()
        if (clearSavedConfig) {
            clearDesiredRunningConfig(this)
        }
        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tun?.close() }
        tun = null
        tunnelMtu = DEFAULT_VPN_MTU
        tunnelUdpEnabled = true
        tunnelSocksPort = DEFAULT_SOCKS_PORT
        tunnelSocksUsername = ""
        tunnelSocksPassword = ""
        activeSocksPort = DEFAULT_SOCKS_PORT
        activeSocksUsername = ""
        activeSocksPassword = ""
        runningPlan = null
        stopBridge()
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
                socketProtectEnabled = false,
            )
        }
        if (setStopped) {
            TcptunState.setStatus("Stopped")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (stopSelfService) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        lifecycleGeneration.incrementAndGet()
        tcpingExecutor.shutdownNow()
        memberHealthExecutor.shutdownNow()
        stopVpn(setStopped = TcptunState.status != "Error", clearSavedConfig = false)
        runCatching { synchronized(bridgeLock) { bridge.close() } }
            .onFailure { err -> TcptunState.appendLog("tcptun engine close failed: ${err.message}") }
        lifecycleExecutor.shutdownNow()
        unregisterDeviceActivityReceiver()
        super.onDestroy()
    }

    private fun registerDeviceActivityReceiver() {
        if (deviceActivityReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> handleDeviceBecameActive(intent.action.orEmpty())
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            deviceActivityReceiver = receiver
        }.onFailure { err ->
            TcptunState.appendLog("device activity receiver unavailable: ${err.message}")
        }
    }

    private fun unregisterDeviceActivityReceiver() {
        val receiver = deviceActivityReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
            .onFailure { err -> TcptunState.appendLog("device activity receiver unregister failed: ${err.message}") }
        deviceActivityReceiver = null
    }

    private fun handleDeviceBecameActive(action: String) {
        if (stopping || tun == null) return
        requestDenseHealthCheck("device active: ${action.substringAfterLast('.')}")
    }

    private fun startBridge(configJson: String, plan: ProfileRunPlan) {
        startBridgeSession(
            configJson = configJson,
            disabledOutboundTags = initiallyDisabledOutboundTags(plan),
            readyTimeoutMs = BRIDGE_READY_TIMEOUT_MS,
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
    ) {
        val epoch = TcptunState.beginBridgeSession()
        val waiter = BridgeReadyWaiter(epoch)
        bridgeReadyWaiter.getAndSet(waiter)?.future?.completeExceptionally(
            IllegalStateException("superseded by a newer tcptun start"),
        )
        bridge.setLogCallback(TcptunState::appendLog)
        bridge.setStatusCallback { eventJson -> onBridgeStatusEvent(epoch, eventJson) }
        bridge.setSocketProtector { fd -> protect(fd) }
        TcptunState.applyBridgeStatusEvent(epoch, bridge.statusJson())
        val sessionId = synchronized(bridgeLock) {
            bridge.configure(configJson)
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
    }

    private fun restartBridge(reason: String) {
        val configJson = bridgeConfigJson ?: return
        val vpnTun = tun ?: return
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
            synchronized(tunnelLock) { HevSocks5Tunnel.stop() }
            stopBridge()
            Thread.sleep(BRIDGE_RESTART_DELAY_MS)
            startBridge(configJson, runningPlan ?: error("running profile plan is unavailable"))
            synchronized(tunnelLock) {
                val hevConfig = writeHevConfig(
                    tunnelMtu,
                    tunnelUdpEnabled,
                    tunnelSocksPort,
                    tunnelSocksUsername,
                    tunnelSocksPassword,
                )
                HevSocks5Tunnel.start(hevConfig, vpnTun)
                if (!HevSocks5Tunnel.isRunning()) {
                    throw IllegalStateException("native packet engine did not enter running state")
                }
            }
            TcptunState.appendLog("tcptun bridge transaction restarted")
            updateBridgeDiagnostics()
        } catch (err: Exception) {
            TcptunState.error("tcptun bridge restart failed: ${err.message}")
            stopVpn(setStopped = false, clearSavedConfig = false)
            throw err
        } finally {
            bridgeRestarting = false
        }
    }

    private fun restartTunnel(reason: String) {
        synchronized(tunnelLock) {
            val vpnTun = tun ?: return
            if (stopping) return
            val now = System.currentTimeMillis()
            val elapsedMs = now - lastTunnelRestartAtMs
            if (elapsedMs < TUNNEL_RESTART_MIN_INTERVAL_MS) {
                val waitSeconds = ((TUNNEL_RESTART_MIN_INTERVAL_MS - elapsedMs) / 1_000).coerceAtLeast(1)
                TcptunState.appendLog("hev-socks5-tunnel restart skipped by cooldown: $reason; wait ${waitSeconds}s")
                return
            }
            lastTunnelRestartAtMs = now
            TcptunState.appendLog("restarting hev-socks5-tunnel: $reason")
            TcptunState.updateDiagnostics { it.copy(lastRestartReason = reason) }
            runCatching { HevSocks5Tunnel.stop() }
                .onFailure { err -> TcptunState.appendLog("hev-socks5-tunnel stop failed: ${err.message}") }
            Thread.sleep(TUNNEL_RESTART_DELAY_MS)
            val hevConfig = writeHevConfig(
                tunnelMtu,
                tunnelUdpEnabled,
                tunnelSocksPort,
                tunnelSocksUsername,
                tunnelSocksPassword,
            )
            HevSocks5Tunnel.start(hevConfig, vpnTun)
            if (!HevSocks5Tunnel.isRunning()) {
                throw IllegalStateException("native packet engine did not enter running state")
            }
            TcptunState.appendLog("hev-socks5-tunnel restarted")
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

    private fun requestTunnelRestart(reason: String) {
        runCatching {
            lifecycleExecutor.execute {
                runCatching { restartTunnel(reason) }
                    .onFailure { err ->
                        TcptunState.error("hev-socks5-tunnel restart failed: ${err.message}")
                        stopVpn(setStopped = false, clearSavedConfig = false)
                    }
            }
        }.onFailure { err ->
            if (!stopping) TcptunState.appendLog("hev-socks5-tunnel restart scheduling failed: ${err.message}")
        }
    }

    private fun requestRuntimeSettingsRestart(reason: String) {
        val generation = synchronized(runtimeSettingsApplyLock) {
            runtimeSettingsApplyGeneration += 1
            runtimeSettingsApplyGeneration
        }
        TcptunState.appendLog("runtime settings apply requested: $reason")
        Thread {
            try {
                Thread.sleep(RUNTIME_SETTINGS_RESTART_DEBOUNCE_MS)
                val shouldApply = synchronized(runtimeSettingsApplyLock) {
                    generation == runtimeSettingsApplyGeneration
                }
                if (!shouldApply) return@Thread
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
                TcptunState.updateDiagnostics { it.copy(lastRestartReason = reason) }
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
            requestDenseHealthCheck("tcptun reported degraded")
            return
        }
        if (eventState != "error" && eventState != "stopped") return
        if (stopping || bridgeRestarting || tun == null) return
        requestDenseHealthCheck("tcptun reported $eventState")
        requestBridgeRestart("tcptun reported $eventState")
    }

    private fun startBridgeMonitor() {
        stopBridgeMonitor()
        val generation = monitorGeneration.incrementAndGet()
        stableHealthSuccesses = 0
        monitorThread = Thread {
            var bridgeFailures = 0
            var tunnelFailures = 0
            while (
                generation == monitorGeneration.get() &&
                !stopping &&
                !Thread.currentThread().isInterrupted
            ) {
                try {
                    val intervalMs = bridgeHealthIntervalMs()
                    TcptunState.updateDiagnostics { it.copy(healthCheckIntervalSeconds = intervalMs / 1_000) }
                    sleepBridgeHealthInterval(intervalMs)
                    if (generation != monitorGeneration.get() || tun == null || stopping) continue
                    val failure = vpnHealthFailure(generation)
                    if (generation != monitorGeneration.get() || stopping) return@Thread
                    if (failure == null) {
                        bridgeFailures = 0
                        tunnelFailures = 0
                        stableHealthSuccesses += 1
                        updateBridgeDiagnostics()
                    } else {
                        stableHealthSuccesses = 0
                        requestDenseHealthCheck("health check failed")
                        TcptunState.appendLog("VPN health check failed: ${failure.reason}")
                        when (failure.restartTarget) {
                            HealthRestartTarget.Bridge -> {
                                bridgeFailures += 1
                                tunnelFailures = 0
                                if (bridgeFailures >= HEALTH_FAILURE_LIMIT) {
                                    bridgeFailures = 0
                                    requestBridgeRestart(failure.reason)
                                }
                            }
                            HealthRestartTarget.Tunnel -> {
                                tunnelFailures += 1
                                bridgeFailures = 0
                                if (tunnelFailures >= HEALTH_FAILURE_LIMIT) {
                                    tunnelFailures = 0
                                    requestTunnelRestart(failure.reason)
                                }
                            }
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
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun bridgeHealthIntervalMs(): Long {
        val now = System.currentTimeMillis()
        if (now < denseHealthCheckUntilMs) return BRIDGE_HEALTH_FAST_INTERVAL_MS
        if (stableHealthSuccesses < BRIDGE_STABLE_SUCCESS_LIMIT) return BRIDGE_HEALTH_FAST_INTERVAL_MS
        val settings = readRuntimeSettings(this)
        return if (settings.powerSavingMode) {
            BRIDGE_HEALTH_POWER_SAVING_INTERVAL_MS
        } else {
            BRIDGE_HEALTH_STABLE_INTERVAL_MS
        }
    }

    private fun sleepBridgeHealthInterval(intervalMs: Long) {
        val wasDense = System.currentTimeMillis() < denseHealthCheckUntilMs
        val deadlineMs = System.currentTimeMillis() + intervalMs
        while (!stopping && !Thread.currentThread().isInterrupted) {
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0) return
            Thread.sleep(remainingMs.coerceAtMost(BRIDGE_HEALTH_SLEEP_GRANULARITY_MS))
            if (!wasDense && System.currentTimeMillis() < denseHealthCheckUntilMs) {
                return
            }
        }
    }

    private fun vpnHealthFailure(monitorEpoch: Int): HealthFailure? {
        tunnelHealthFailure()?.let {
            return HealthFailure(it, HealthRestartTarget.Tunnel)
        }
        val status = runCatching { bridge.status() }.getOrElse { err ->
            TcptunState.updateDiagnostics { it.copy(bridgeStatus = "Unknown", localProxyReachable = false) }
            return HealthFailure("status unavailable: ${err.message}", HealthRestartTarget.Bridge)
        }
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
        if (status != "Running") {
            return HealthFailure("bridge status is $status", HealthRestartTarget.Bridge)
        }
        if (!localProxyReachable) {
            return HealthFailure("local proxy ${activeLocalSocksConnectAddr()} is not accepting connections", HealthRestartTarget.Bridge)
        }
        if (shouldRunUpstreamProbe()) {
            val targets = orderedUpstreamProbeTargets()
            probeActiveMembers(targets, monitorEpoch)
            if (monitorEpoch != monitorGeneration.get() || stopping) return null
            val upstreamFailure = upstreamProbeFailure(targets)
            updateRawProfileHealth(upstreamFailure)
            upstreamFailure?.let { return HealthFailure(it, HealthRestartTarget.Bridge) }
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

    private fun tunnelHealthFailure(): String? {
        val running = runCatching { HevSocks5Tunnel.isRunning() }.getOrElse { err ->
            return "hev-socks5-tunnel status unavailable: ${err.message ?: err.javaClass.simpleName}"
        }
        if (!running) {
            return "hev-socks5-tunnel is not running"
        }
        val stats = runCatching { HevSocks5Tunnel.stats() }.getOrElse { err ->
            return "hev-socks5-tunnel stats unavailable: ${err.message ?: err.javaClass.simpleName}"
        }
        if (stats.size < TUNNEL_STATS_SIZE) {
            return "hev-socks5-tunnel stats invalid: ${stats.size} values"
        }
        return null
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
        val now = System.currentTimeMillis()
        val intervalMs = when {
            now < denseHealthCheckUntilMs -> UPSTREAM_PROBE_DENSE_INTERVAL_MS
            readRuntimeSettings(this).powerSavingMode -> UPSTREAM_PROBE_POWER_SAVING_INTERVAL_MS
            else -> UPSTREAM_PROBE_STABLE_INTERVAL_MS
        }
        if (now - lastUpstreamProbeAtMs < intervalMs) return false
        lastUpstreamProbeAtMs = now
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
        const val ACTION_REFRESH_CLIENT_IPS = "com.tcptun.client.REFRESH_CLIENT_IPS"
        const val EXTRA_CONFIG = "config"
        private const val EXTRA_PROFILE_CONFIG = "profileConfig"
        private const val EXTRA_PROFILE_PLAN = "profilePlan"
        private const val EXTRA_TCPING_REQUEST_ID = "tcpingRequestId"
        private const val EXTRA_TCPING_TARGET_LABEL = "tcpingTargetLabel"
        private const val EXTRA_TCPING_HOST = "tcpingHost"
        private const val EXTRA_TCPING_PORT = "tcpingPort"
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_VPN_MTU = 1400
        const val DEFAULT_PROBE_TIMEOUT = "120ms"
        const val DEFAULT_FAILURE_THRESHOLD = 1
        const val DEFAULT_POSITIVE_TTL = "30m"
        const val DEFAULT_NEGATIVE_TTL = "10m"
        const val MAX_FAILURE_THRESHOLD = 100
        private const val VPN_DISPLAY_NAME = "TcpTun VPN"
        private const val CHANNEL_ID = "tcptun_vpn_silent"
        private const val NOTIFICATION_ID = 1001
        private const val BRIDGE_HEALTH_FAST_INTERVAL_MS = 15_000L
        private const val BRIDGE_HEALTH_STABLE_INTERVAL_MS = 60_000L
        private const val BRIDGE_HEALTH_POWER_SAVING_INTERVAL_MS = 120_000L
        private const val BRIDGE_DENSE_HEALTH_WINDOW_MS = 120_000L
        private const val BRIDGE_HEALTH_SLEEP_GRANULARITY_MS = 5_000L
        private const val BRIDGE_STABLE_SUCCESS_LIMIT = 2
        private const val HEALTH_FAILURE_LIMIT = 2
        private const val BRIDGE_RESTART_DELAY_MS = 300L
        private const val BRIDGE_RESTART_MIN_INTERVAL_MS = 30_000L
        private const val BRIDGE_READY_TIMEOUT_MS = 15_000L
        private const val OUTBOUND_STOP_TIMEOUT_MS = 15_000L
        private const val TCPING_OUTBOUND_TIMEOUT_MS = 3_000L
        private const val MEMBER_HEALTH_PROBE_TIMEOUT_MS = 3_000L
        private const val MEMBER_HEALTH_PROBE_GRACE_MS = 1_000L
        private const val MAX_CONCURRENT_MEMBER_HEALTH_PROBES = 4
        private const val TUNNEL_RESTART_DELAY_MS = 300L
        private const val TUNNEL_RESTART_MIN_INTERVAL_MS = 30_000L
        private const val RUNTIME_SETTINGS_RESTART_DEBOUNCE_MS = 800L
        private const val TUNNEL_STATS_SIZE = 4
        private const val LOCAL_PROXY_CONNECT_TIMEOUT_MS = 1_000
        private const val UPSTREAM_PROBE_TIMEOUT_MS = 5_000
        private const val UPSTREAM_PROBE_DENSE_INTERVAL_MS = 10_000L
        private const val UPSTREAM_PROBE_STABLE_INTERVAL_MS = 60_000L
        private const val UPSTREAM_PROBE_POWER_SAVING_INTERVAL_MS = 120_000L
        private const val RUNTIME_PREFS = "tcptun_runtime"
        private const val KEY_LAST_RUNNING_CONFIG = "lastRunningConfig"
        private const val KEY_LAST_RUNNING_PLAN = "lastRunningPlan"
        private const val KEY_DESIRED_RUNNING = "desiredRunning"
        private const val KEY_RUNNING_CONFIG_VERSION = "runningConfigVersion"
        private const val RUNNING_CONFIG_VERSION = 3
        private const val KEY_RUNTIME_MTU = "runtimeMtu"
        private const val KEY_RUNTIME_UDP_ENABLED = "runtimeUdpEnabled"
        private const val KEY_RUNTIME_POWER_SAVING = "runtimePowerSaving"
        private const val KEY_RUNTIME_SOCKS_PORT = "runtimeSocksPort"
        private const val KEY_RUNTIME_LOCAL_PROXY_PROTOCOL = "runtimeLocalProxyProtocol"
        private const val KEY_RUNTIME_SOCKS_LISTEN_ALL = "runtimeSocksListenAll"
        private const val KEY_RUNTIME_ROUTE_EXTERNAL_SOURCES = "runtimeRouteExternalSources"
        private const val KEY_RUNTIME_DIRECT_FIRST = "runtimeDirectFirst"
        private const val KEY_RUNTIME_PROBE_TIMEOUT = "runtimeProbeTimeout"
        private const val KEY_RUNTIME_FAILURE_THRESHOLD = "runtimeFailureThreshold"
        private const val KEY_RUNTIME_POSITIVE_TTL = "runtimePositiveTtl"
        private const val KEY_RUNTIME_NEGATIVE_TTL = "runtimeNegativeTtl"
        private const val KEY_RUNTIME_SOCKS_USERNAME = "runtimeSocksUsername"
        private const val KEY_RUNTIME_SOCKS_PASSWORD = "runtimeSocksPassword"
        @Volatile private var denseHealthCheckUntilMs = 0L
        private val UPSTREAM_PROBE_TARGETS = listOf(
            UpstreamProbeTarget("Google 204", "connectivitycheck.gstatic.com", path = "/generate_204", expectedStatus = 204),
            UpstreamProbeTarget("Cloudflare 204", "cp.cloudflare.com", path = "/generate_204", expectedStatus = 204),
        )
        private const val VPN_DNS_SERVER = "1.1.1.1"

        fun startIntent(context: Context, config: AppConfig): Intent {
            return startIntent(context, ProfileRunPlan(listOf(config)))
        }

        fun refreshClientIpsIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_REFRESH_CLIENT_IPS)
        }

        fun startIntent(context: Context, sourcePlan: ProfileRunPlan): Intent {
            val runtimeSettings = readRuntimeSettings(context)
            val plan = sourcePlan.normalized()
            val effectivePlan = plan.copy(
                profiles = plan.profiles.map { config ->
                    config.copy(udp = config.udp && runtimeSettings.udpEnabled && !runtimeSettings.powerSavingMode)
                },
            )
            val config = plan.profiles.first()
            val effectiveConfig = effectivePlan.profiles.first()
            val localListenAddr = localSocksListenAddr(runtimeSettings)
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(
                    EXTRA_CONFIG,
                    effectivePlan.toBridgeJson(
                        localListenAddr,
                        localProxyProtocol = runtimeSettings.localProxyProtocol,
                        socks5Username = runtimeSettings.socksUsername,
                        socks5Password = runtimeSettings.socksPassword,
                        routeExternalSources = runtimeSettings.routeExternalSources,
                        directFirst = runtimeSettings.directFirst,
                        probeTimeout = runtimeSettings.probeTimeout,
                        failureThreshold = runtimeSettings.failureThreshold,
                        positiveTtl = runtimeSettings.positiveTtl,
                        negativeTtl = runtimeSettings.negativeTtl,
                        managedRouteRules = RouteRuleStore.load(context),
                    ),
                )
                .putExtra("serverHost", effectiveConfig.serverHost)
                .putExtra("serverPort", effectiveConfig.serverPort)
                .putExtra("protocol", effectiveConfig.protocol)
                .putExtra("transport", effectiveConfig.transport)
                .putExtra("token", effectiveConfig.token)
                .putExtra("sni", effectiveConfig.sni)
                .putExtra("path", effectiveConfig.path)
                .putExtra("tls", effectiveConfig.tls)
                .putExtra("tlsInsecure", effectiveConfig.tlsInsecure)
                .putExtra("tunnelSecurity", effectiveConfig.tunnelSecurity)
                .putExtra("flow", effectiveConfig.flow)
                .putExtra("realityPublicKey", effectiveConfig.realityPublicKey)
                .putExtra("realityShortId", effectiveConfig.realityShortId)
                .putExtra("realityFingerprint", effectiveConfig.realityFingerprint)
                .putExtra("realitySpiderX", effectiveConfig.realitySpiderX)
                .putExtra("mux", effectiveConfig.mux)
                .putExtra("udp", config.udp)
                .putExtra("upstreamProtocol", runtimeSettings.localProxyProtocol)
                .putExtra(EXTRA_PROFILE_CONFIG, config.toJson().toString())
                .putExtra(EXTRA_PROFILE_PLAN, plan.toJson().toString())
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_STOP)
        }

        fun updateOutboundsIntent(context: Context, plan: ProfileRunPlan): Intent {
            return startIntent(context, plan).setAction(ACTION_UPDATE_OUTBOUNDS)
        }

        fun applyRuntimeSettingsIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_APPLY_RUNTIME_SETTINGS)
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

        fun requestDenseHealthCheck(reason: String) {
            val nextUntilMs = System.currentTimeMillis() + BRIDGE_DENSE_HEALTH_WINDOW_MS
            if (nextUntilMs > denseHealthCheckUntilMs) {
                denseHealthCheckUntilMs = nextUntilMs
            }
            TcptunState.appendLog("dense bridge health check requested: $reason")
        }

        fun readRuntimeSettings(context: Context): RuntimeSettings {
            val prefs = context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            val mtu = prefs.getInt(KEY_RUNTIME_MTU, DEFAULT_VPN_MTU).coerceIn(1280, 1500)
            val powerSavingMode = prefs.getBoolean(KEY_RUNTIME_POWER_SAVING, false)
            val socksPort = prefs.getInt(KEY_RUNTIME_SOCKS_PORT, DEFAULT_SOCKS_PORT).coerceIn(1, 65535)
            return RuntimeSettings(
                mtu = mtu,
                udpEnabled = prefs.getBoolean(KEY_RUNTIME_UDP_ENABLED, true) && !powerSavingMode,
                powerSavingMode = powerSavingMode,
                socksPort = socksPort,
                localProxyProtocol = normalizeLocalProxyProtocol(
                    prefs.getString(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, DefaultLocalProxyProtocol).orEmpty(),
                ),
                socksListenAll = prefs.getBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, false),
                routeExternalSources = prefs.getBoolean(KEY_RUNTIME_ROUTE_EXTERNAL_SOURCES, false),
                directFirst = prefs.getBoolean(KEY_RUNTIME_DIRECT_FIRST, false),
                probeTimeout = prefs.getString(KEY_RUNTIME_PROBE_TIMEOUT, DEFAULT_PROBE_TIMEOUT)
                    .orEmpty().trim().takeIf(::isValidDuration).orEmpty().ifBlank { DEFAULT_PROBE_TIMEOUT },
                failureThreshold = prefs.getInt(KEY_RUNTIME_FAILURE_THRESHOLD, DEFAULT_FAILURE_THRESHOLD)
                    .coerceIn(1, MAX_FAILURE_THRESHOLD),
                positiveTtl = prefs.getString(KEY_RUNTIME_POSITIVE_TTL, DEFAULT_POSITIVE_TTL)
                    .orEmpty().trim().takeIf(::isValidDuration).orEmpty().ifBlank { DEFAULT_POSITIVE_TTL },
                negativeTtl = prefs.getString(KEY_RUNTIME_NEGATIVE_TTL, DEFAULT_NEGATIVE_TTL)
                    .orEmpty().trim().takeIf(::isValidDuration).orEmpty().ifBlank { DEFAULT_NEGATIVE_TTL },
                socksUsername = prefs.getString(KEY_RUNTIME_SOCKS_USERNAME, "").orEmpty(),
                socksPassword = prefs.getString(KEY_RUNTIME_SOCKS_PASSWORD, "").orEmpty(),
            )
        }

        fun writeRuntimeSettings(context: Context, settings: RuntimeSettings) {
            val normalizedPowerSavingMode = settings.powerSavingMode
            val normalizedUdpEnabled = settings.udpEnabled && !normalizedPowerSavingMode
            val normalizedSocksPort = settings.socksPort.coerceIn(1, 65535)
            val normalizedLocalProxyProtocol = normalizeLocalProxyProtocol(settings.localProxyProtocol)
            val normalizedProbeTimeout = settings.probeTimeout.trim().takeIf(::isValidDuration) ?: DEFAULT_PROBE_TIMEOUT
            val normalizedFailureThreshold = settings.failureThreshold.coerceIn(1, MAX_FAILURE_THRESHOLD)
            val normalizedPositiveTtl = settings.positiveTtl.trim().takeIf(::isValidDuration) ?: DEFAULT_POSITIVE_TTL
            val normalizedNegativeTtl = settings.negativeTtl.trim().takeIf(::isValidDuration) ?: DEFAULT_NEGATIVE_TTL
            val normalizedSettings = settings.copy(
                udpEnabled = normalizedUdpEnabled,
                powerSavingMode = normalizedPowerSavingMode,
                socksPort = normalizedSocksPort,
                localProxyProtocol = normalizedLocalProxyProtocol,
                routeExternalSources = settings.routeExternalSources,
                directFirst = settings.directFirst,
                probeTimeout = normalizedProbeTimeout,
                failureThreshold = normalizedFailureThreshold,
                positiveTtl = normalizedPositiveTtl,
                negativeTtl = normalizedNegativeTtl,
                socksUsername = settings.socksUsername,
                socksPassword = settings.socksPassword,
            )
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RUNTIME_MTU, settings.mtu.coerceIn(1280, 1500))
                .putBoolean(KEY_RUNTIME_UDP_ENABLED, normalizedUdpEnabled)
                .putBoolean(KEY_RUNTIME_POWER_SAVING, normalizedPowerSavingMode)
                .putInt(KEY_RUNTIME_SOCKS_PORT, normalizedSocksPort)
                .putString(KEY_RUNTIME_LOCAL_PROXY_PROTOCOL, normalizedLocalProxyProtocol)
                .putBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, settings.socksListenAll)
                .putBoolean(KEY_RUNTIME_ROUTE_EXTERNAL_SOURCES, settings.routeExternalSources)
                .putBoolean(KEY_RUNTIME_DIRECT_FIRST, settings.directFirst)
                .putString(KEY_RUNTIME_PROBE_TIMEOUT, normalizedProbeTimeout)
                .putInt(KEY_RUNTIME_FAILURE_THRESHOLD, normalizedFailureThreshold)
                .putString(KEY_RUNTIME_POSITIVE_TTL, normalizedPositiveTtl)
                .putString(KEY_RUNTIME_NEGATIVE_TTL, normalizedNegativeTtl)
                .putString(KEY_RUNTIME_SOCKS_USERNAME, settings.socksUsername)
                .putString(KEY_RUNTIME_SOCKS_PASSWORD, settings.socksPassword)
                .apply()
            TcptunState.updateDiagnostics {
                it.copy(
                    mtu = settings.mtu.coerceIn(1280, 1500),
                    udpEnabled = normalizedUdpEnabled,
                    powerSavingMode = normalizedPowerSavingMode,
                    localProxyAddress = localSocksConnectAddr(normalizedSettings),
                    localProxyPort = normalizedSocksPort,
                )
            }
            TcptunState.appendLog("runtime settings saved: proxy=${normalizedSettings.localProxyProtocol}://${localSocksListenAddr(normalizedSettings)} mtu=${normalizedSettings.mtu} udp=${normalizedSettings.udpEnabled} direct-first=${normalizedSettings.directFirst} probe-timeout=${normalizedSettings.probeTimeout} failure-threshold=${normalizedSettings.failureThreshold} positive-ttl=${normalizedSettings.positiveTtl} negative-ttl=${normalizedSettings.negativeTtl}")
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
