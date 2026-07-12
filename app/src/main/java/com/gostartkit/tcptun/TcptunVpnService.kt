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
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class RuntimeSettings(
    val mtu: Int = TcptunVpnService.DEFAULT_VPN_MTU,
    val udpEnabled: Boolean = true,
    val powerSavingMode: Boolean = false,
    val socksPort: Int = TcptunVpnService.DEFAULT_SOCKS_PORT,
    val socksListenAll: Boolean = false,
    val routeExternalSources: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
)

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

class TcptunVpnService : VpnService() {
    private val bridge: TcptunBridge = ReflectionTcptunBridge()
    private val bridgeLock = Any()
    private val tunnelLock = Any()
    private var tun: android.os.ParcelFileDescriptor? = null
    @Volatile private var bridgeConfigJson: String? = null
    @Volatile private var monitorThread: Thread? = null
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetworkCallbackRegistered = false
    @Volatile private var currentDefaultNetwork: Network? = null
    @Volatile private var stopping = false
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
            ACTION_START -> startFromIntent(intent)
            ACTION_STOP -> stopVpn()
            ACTION_APPLY_RUNTIME_SETTINGS -> requestRuntimeSettingsRestart("runtime settings changed")
            else -> restoreLastRunningConfig()
        }
        return START_STICKY
    }

    private fun startFromIntent(intent: Intent) {
        if (tun != null) {
            TcptunState.appendLog("restarting VPN with selected profile")
            stopVpn(setStopped = false, clearSavedConfig = false, stopSelfService = false)
        }
        stopping = false
        val json = intent.getStringExtra(EXTRA_CONFIG) ?: run {
            TcptunState.error("missing VPN config")
            stopSelf()
            return
        }
        Thread {
            try {
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
                val config = intent.getStringExtra(EXTRA_PROFILE_CONFIG)
                    ?.let { raw -> runCatching { AppConfig.fromJson(JSONObject(raw)) }.getOrNull() }
                    ?: fallbackConfig
                val runtimeSettings = readRuntimeSettings(this)
                val effectiveUdpEnabled = config.udp && runtimeSettings.udpEnabled && !runtimeSettings.powerSavingMode
                activeSocksPort = runtimeSettings.socksPort
                activeSocksUsername = runtimeSettings.socksUsername
                activeSocksPassword = runtimeSettings.socksPassword
                saveLastRunningConfig(this, config)
                bridge.setLogCallback(TcptunState::appendLog)
                bridge.setStatusCallback { eventJson ->
                    TcptunState.applyBridgeStatusEvent(eventJson)
                    handleBridgeStatusEvent(eventJson)
                }
                bridge.setSocketProtector { fd -> protect(fd) }
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
                startBridge(json)
                val vpnTun = buildTun(runtimeSettings.mtu)
                startTunnel(
                    vpnTun,
                    runtimeSettings.mtu,
                    effectiveUdpEnabled,
                    runtimeSettings.socksPort,
                    runtimeSettings.socksUsername,
                    runtimeSettings.socksPassword,
                )
                startBridgeMonitor()
                TcptunState.setStatus("Running")
                updateBridgeDiagnostics()
                updateNotification("Running")
            } catch (err: Exception) {
                TcptunState.error(err.message ?: err.javaClass.simpleName)
                stopVpn(setStopped = false, clearSavedConfig = true)
            }
        }.start()
    }

    private fun restoreLastRunningConfig() {
        if (tun != null) return
        val config = readLastRunningConfig(this) ?: run {
            stopSelf()
            return
        }
        if (config.validate() != null) {
            clearLastRunningConfig(this)
            stopSelf()
            return
        }
        TcptunState.appendLog("restoring VPN after service restart")
        startFromIntent(startIntent(this, config))
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || underlyingNetworkCallbackRegistered) return
        val callback = underlyingNetworkCallback ?: object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentDefaultNetwork
                currentDefaultNetwork = network
                updateUnderlyingDiagnostics(network)
                setUnderlyingNetworks(arrayOf(network))
                if (previous != null && previous != network && tun != null && !stopping) {
                    requestBridgeRestart("default network changed")
                }
            }

            override fun onLost(network: Network) {
                if (currentDefaultNetwork == network) {
                    currentDefaultNetwork = null
                    updateUnderlyingDiagnostics(null)
                }
                setUnderlyingNetworks(null)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !underlyingNetworkCallbackRegistered) return
        underlyingNetworkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { err -> TcptunState.appendLog("underlying network callback unregister failed: ${err.message}") }
        }
        underlyingNetworkCallbackRegistered = false
        currentDefaultNetwork = null
        updateUnderlyingDiagnostics(null)
    }

    private fun stopVpn(setStopped: Boolean = true, clearSavedConfig: Boolean = true, stopSelfService: Boolean = true) {
        if (stopping) return
        stopping = true
        if (setStopped) {
            TcptunState.setStatus("Stopping")
        }
        stopBridgeMonitor()
        unregisterUnderlyingNetworkCallback()
        if (clearSavedConfig) {
            clearLastRunningConfig(this)
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
        stopBridge()
        TcptunState.updateDiagnostics {
            it.copy(
                bridgeStatus = "Stopped",
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
        stopVpn(setStopped = TcptunState.status.value != "Error", clearSavedConfig = false)
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

    private fun startBridge(configJson: String) {
        synchronized(bridgeLock) {
            bridge.start(configJson)
            bridgeConfigJson = configJson
            TcptunState.updateDiagnostics { it.copy(bridgeStatus = runCatching { bridge.status() }.getOrDefault("Unknown")) }
        }
    }

    private fun stopBridge() {
        synchronized(bridgeLock) {
            bridgeConfigJson = null
            runCatching { bridge.stop() }
            runCatching { bridge.clearStatusCallback() }
            runCatching { bridge.clearSocketProtector() }
            runCatching { bridge.clearAppIdentityProvider() }
        }
    }

    private fun restartBridge(reason: String) {
        val configJson = bridgeConfigJson ?: return
        synchronized(bridgeLock) {
            if (stopping || tun == null) return
            val now = System.currentTimeMillis()
            val elapsedMs = now - lastBridgeRestartAtMs
            if (elapsedMs < BRIDGE_RESTART_MIN_INTERVAL_MS) {
                val waitSeconds = ((BRIDGE_RESTART_MIN_INTERVAL_MS - elapsedMs) / 1_000).coerceAtLeast(1)
                TcptunState.appendLog("tcptun bridge restart skipped by cooldown: $reason; wait ${waitSeconds}s")
                return
            }
            lastBridgeRestartAtMs = now
            TcptunState.appendLog("restarting tcptun bridge: $reason")
            TcptunState.updateDiagnostics { it.copy(lastRestartReason = reason, bridgeStatus = "Restarting") }
            runCatching { bridge.stop() }
                .onFailure { err -> TcptunState.appendLog("tcptun bridge stop failed: ${err.message}") }
            Thread.sleep(BRIDGE_RESTART_DELAY_MS)
            bridge.start(configJson)
            TcptunState.appendLog("tcptun bridge restarted")
            updateBridgeDiagnostics()
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
            TcptunState.appendLog("hev-socks5-tunnel restarted")
        }
    }

    private fun requestBridgeRestart(reason: String) {
        Thread {
            runCatching { restartBridge(reason) }
                .onFailure { err -> TcptunState.appendLog("tcptun bridge restart failed: ${err.message}") }
        }.apply {
            name = "TcptunBridgeRestart"
            isDaemon = true
            start()
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
                val config = readLastRunningConfig(this) ?: run {
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
                startFromIntent(startIntent(this, config))
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

    private fun handleBridgeStatusEvent(eventJson: String) {
        val eventState = runCatching { JSONObject(eventJson).optString("state").lowercase() }.getOrDefault("")
        if (eventState == "degraded") {
            requestDenseHealthCheck("tcptun reported degraded")
            return
        }
        if (eventState != "error" && eventState != "stopped") return
        if (stopping || tun == null) return
        requestDenseHealthCheck("tcptun reported $eventState")
        requestBridgeRestart("tcptun reported $eventState")
    }

    private fun startBridgeMonitor() {
        stopBridgeMonitor()
        stableHealthSuccesses = 0
        monitorThread = Thread {
            var bridgeFailures = 0
            var tunnelFailures = 0
            while (!stopping && !Thread.currentThread().isInterrupted) {
                try {
                    val intervalMs = bridgeHealthIntervalMs()
                    TcptunState.updateDiagnostics { it.copy(healthCheckIntervalSeconds = intervalMs / 1_000) }
                    sleepBridgeHealthInterval(intervalMs)
                    if (tun == null || stopping) continue
                    val failure = vpnHealthFailure()
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
                                    restartBridge(failure.reason)
                                }
                            }
                            HealthRestartTarget.Tunnel -> {
                                tunnelFailures += 1
                                bridgeFailures = 0
                                if (tunnelFailures >= HEALTH_FAILURE_LIMIT) {
                                    tunnelFailures = 0
                                    restartTunnel(failure.reason)
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

    private fun vpnHealthFailure(): HealthFailure? {
        tunnelHealthFailure()?.let {
            return HealthFailure(it, HealthRestartTarget.Tunnel)
        }
        val status = runCatching { bridge.status() }.getOrElse { err ->
            TcptunState.updateDiagnostics { it.copy(bridgeStatus = "Unknown", localProxyReachable = false) }
            return HealthFailure("status unavailable: ${err.message}", HealthRestartTarget.Bridge)
        }
        val localProxyReachable = canConnectLocalProxy()
        TcptunState.updateDiagnostics {
            it.copy(
                bridgeStatus = status,
                localProxyReachable = localProxyReachable,
                localProxyAddress = activeLocalSocksConnectAddr(),
                localProxyPort = activeSocksPort,
            )
        }
        if (status != "Running") {
            return HealthFailure("bridge status is $status", HealthRestartTarget.Bridge)
        }
        if (!localProxyReachable) {
            return HealthFailure("local proxy ${activeLocalSocksConnectAddr()} is not accepting connections", HealthRestartTarget.Bridge)
        }
        if (shouldRunUpstreamProbe()) {
            upstreamProbeFailure()?.let { return HealthFailure(it, HealthRestartTarget.Bridge) }
        }
        return null
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
        val localProxyReachable = canConnectLocalProxy()
        TcptunState.updateDiagnostics {
            it.copy(
                bridgeStatus = status,
                localProxyReachable = localProxyReachable,
                localProxyAddress = activeLocalSocksConnectAddr(),
                localProxyPort = activeSocksPort,
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
        if (now >= denseHealthCheckUntilMs) return false
        if (now - lastUpstreamProbeAtMs < UPSTREAM_PROBE_MIN_INTERVAL_MS) return false
        lastUpstreamProbeAtMs = now
        return true
    }

    private fun upstreamProbeFailure(): String? {
        val target = nextUpstreamProbeTarget()
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
            onFailure = { err ->
                "upstream probe ${target.label} failed: ${err.message ?: err.javaClass.simpleName}"
            },
        )
    }

    private fun nextUpstreamProbeTarget(): UpstreamProbeTarget {
        val targets = UPSTREAM_PROBE_TARGETS
        val index = (upstreamProbeIndex % targets.size).coerceAtLeast(0)
        upstreamProbeIndex = (index + 1) % targets.size
        return targets[index]
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
        const val ACTION_APPLY_RUNTIME_SETTINGS = "com.tcptun.client.APPLY_RUNTIME_SETTINGS"
        const val EXTRA_CONFIG = "config"
        private const val EXTRA_PROFILE_CONFIG = "profileConfig"
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_VPN_MTU = 1400
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
        private const val TUNNEL_RESTART_DELAY_MS = 300L
        private const val TUNNEL_RESTART_MIN_INTERVAL_MS = 30_000L
        private const val RUNTIME_SETTINGS_RESTART_DEBOUNCE_MS = 800L
        private const val TUNNEL_STATS_SIZE = 4
        private const val LOCAL_PROXY_CONNECT_TIMEOUT_MS = 1_000
        private const val UPSTREAM_PROBE_TIMEOUT_MS = 3_000
        private const val UPSTREAM_PROBE_MIN_INTERVAL_MS = 10_000L
        private const val RUNTIME_PREFS = "tcptun_runtime"
        private const val KEY_LAST_RUNNING_CONFIG = "lastRunningConfig"
        private const val KEY_RUNTIME_MTU = "runtimeMtu"
        private const val KEY_RUNTIME_UDP_ENABLED = "runtimeUdpEnabled"
        private const val KEY_RUNTIME_POWER_SAVING = "runtimePowerSaving"
        private const val KEY_RUNTIME_SOCKS_PORT = "runtimeSocksPort"
        private const val KEY_RUNTIME_SOCKS_LISTEN_ALL = "runtimeSocksListenAll"
        private const val KEY_RUNTIME_ROUTE_EXTERNAL_SOURCES = "runtimeRouteExternalSources"
        private const val KEY_RUNTIME_SOCKS_USERNAME = "runtimeSocksUsername"
        private const val KEY_RUNTIME_SOCKS_PASSWORD = "runtimeSocksPassword"
        @Volatile private var denseHealthCheckUntilMs = 0L
        private val UPSTREAM_PROBE_TARGETS = listOf(
            UpstreamProbeTarget("Google 204", "connectivitycheck.gstatic.com", path = "/generate_204", expectedStatus = 204),
        )
        private const val VPN_DNS_SERVER = "1.1.1.1"

        fun startIntent(context: Context, config: AppConfig): Intent {
            val runtimeSettings = readRuntimeSettings(context)
            val effectiveConfig = config.copy(udp = config.udp && runtimeSettings.udpEnabled && !runtimeSettings.powerSavingMode)
            val localListenAddr = localSocksListenAddr(runtimeSettings)
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(
                    EXTRA_CONFIG,
                    effectiveConfig.toBridgeJson(
                        localListenAddr,
                        powerSavingMode = runtimeSettings.powerSavingMode,
                        socks5Username = runtimeSettings.socksUsername,
                        socks5Password = runtimeSettings.socksPassword,
                        routeExternalSources = runtimeSettings.routeExternalSources,
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
                .putExtra("upstreamProtocol", effectiveConfig.upstreamProtocol)
                .putExtra(EXTRA_PROFILE_CONFIG, config.toJson().toString())
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_STOP)
        }

        fun applyRuntimeSettingsIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_APPLY_RUNTIME_SETTINGS)
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
                socksListenAll = prefs.getBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, false),
                routeExternalSources = prefs.getBoolean(KEY_RUNTIME_ROUTE_EXTERNAL_SOURCES, false),
                socksUsername = prefs.getString(KEY_RUNTIME_SOCKS_USERNAME, "").orEmpty(),
                socksPassword = prefs.getString(KEY_RUNTIME_SOCKS_PASSWORD, "").orEmpty(),
            )
        }

        fun writeRuntimeSettings(context: Context, settings: RuntimeSettings) {
            val normalizedPowerSavingMode = settings.powerSavingMode
            val normalizedUdpEnabled = settings.udpEnabled && !normalizedPowerSavingMode
            val normalizedSocksPort = settings.socksPort.coerceIn(1, 65535)
            val normalizedSettings = settings.copy(
                udpEnabled = normalizedUdpEnabled,
                powerSavingMode = normalizedPowerSavingMode,
                socksPort = normalizedSocksPort,
                routeExternalSources = settings.routeExternalSources,
                socksUsername = settings.socksUsername,
                socksPassword = settings.socksPassword,
            )
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RUNTIME_MTU, settings.mtu.coerceIn(1280, 1500))
                .putBoolean(KEY_RUNTIME_UDP_ENABLED, normalizedUdpEnabled)
                .putBoolean(KEY_RUNTIME_POWER_SAVING, normalizedPowerSavingMode)
                .putInt(KEY_RUNTIME_SOCKS_PORT, normalizedSocksPort)
                .putBoolean(KEY_RUNTIME_SOCKS_LISTEN_ALL, settings.socksListenAll)
                .putBoolean(KEY_RUNTIME_ROUTE_EXTERNAL_SOURCES, settings.routeExternalSources)
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
            TcptunState.appendLog("runtime settings saved: socks=${localSocksListenAddr(normalizedSettings)} mtu=${normalizedSettings.mtu} udp=${normalizedSettings.udpEnabled}")
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

        private fun saveLastRunningConfig(context: Context, config: AppConfig) {
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_RUNNING_CONFIG, config.toJson().toString())
                .apply()
        }

        private fun readLastRunningConfig(context: Context): AppConfig? {
            val raw = context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_RUNNING_CONFIG, null)
                ?: return null
            return runCatching { AppConfig.fromJson(JSONObject(raw)) }.getOrNull()
        }

        private fun clearLastRunningConfig(context: Context) {
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_RUNNING_CONFIG)
                .apply()
        }

    }
}
