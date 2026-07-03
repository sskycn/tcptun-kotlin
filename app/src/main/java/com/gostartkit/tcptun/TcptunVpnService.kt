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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TcptunVpnService : VpnService() {
    private val bridge: TcptunBridge = ReflectionTcptunBridge()
    private var forwarder: TunSocksForwarder? = null
    private var tun: android.os.ParcelFileDescriptor? = null
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetworkCallbackRegistered = false
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromIntent(intent)
            ACTION_STOP -> stopVpn()
            else -> restoreLastRunningConfig()
        }
        return START_STICKY
    }

    private fun startFromIntent(intent: Intent) {
        if (tun != null || forwarder != null) {
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
                val config = AppConfig(
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
                    udp = true,
                    upstreamProtocol = intent.getStringExtra("upstreamProtocol") ?: "socks5",
                )
                saveLastRunningConfig(this, config)
                bridge.setLogCallback(TcptunState::appendLog)
                bridge.start(json)
                val vpnTun = buildTun(config)
                startTunnel(vpnTun)
                TcptunState.setStatus("Running")
                updateNotification("Running")
            } catch (err: Exception) {
                TcptunState.error(err.message ?: err.javaClass.simpleName)
                stopVpn(setStopped = false, clearSavedConfig = true)
            }
        }.start()
    }

    private fun restoreLastRunningConfig() {
        if (tun != null || forwarder != null) return
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

    private fun startTunnel(vpnTun: android.os.ParcelFileDescriptor) {
        runCatching {
            val hevConfig = HevSocks5Tunnel.writeConfig(
                directory = applicationContext.filesDir,
                socksHost = LOCAL_SOCKS_HOST,
                socksPort = LOCAL_SOCKS_PORT,
                mtu = VPN_MTU,
            )
            HevSocks5Tunnel.start(hevConfig, vpnTun)
            tun = vpnTun
            TcptunState.appendLog("hev-socks5-tunnel started")
        }.onFailure { err ->
            TcptunState.appendLog("hev-socks5-tunnel unavailable; falling back to Kotlin forwarder: ${err.message}")
            forwarder = TunSocksForwarder(
                tun = vpnTun,
                socksHost = LOCAL_SOCKS_HOST,
                socksPort = LOCAL_SOCKS_PORT,
                enableUdp = true,
                protectSocket = ::protect,
                protectDatagramSocket = ::protect,
                log = TcptunState::appendLog,
            ).also { it.start() }
        }
    }

    private fun buildTun(config: AppConfig): android.os.ParcelFileDescriptor {
        registerDefaultNetworkCallback()
        return Builder()
            .setSession("tcptun")
            .setMtu(VPN_MTU)
            .addAddress("10.77.0.2", 32)
            .addAddress("fd00:7777::2", 128)
            .addProxyRoutes()
            .addRoute("2000::", 3)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    addDisallowedApplication(packageName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(connectivity.isActiveNetworkMetered)
                }
                allowFamily(android.system.OsConstants.AF_INET)
                allowFamily(android.system.OsConstants.AF_INET6)
            }
            .establish() ?: throw IllegalStateException("VpnService establish() returned null")
    }

    private fun registerDefaultNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || defaultNetworkCallbackRegistered) return
        val callback = defaultNetworkCallback ?: object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }.also { defaultNetworkCallback = it }
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            defaultNetworkCallbackRegistered = true
            TcptunState.appendLog("default network callback registered")
        }.onFailure { err ->
            TcptunState.appendLog("default network callback unavailable: ${err.message}")
        }
    }

    private fun unregisterDefaultNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !defaultNetworkCallbackRegistered) return
        defaultNetworkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { err -> TcptunState.appendLog("default network callback unregister failed: ${err.message}") }
        }
        defaultNetworkCallbackRegistered = false
    }

    private fun stopVpn(setStopped: Boolean = true, clearSavedConfig: Boolean = true, stopSelfService: Boolean = true) {
        if (stopping) return
        stopping = true
        unregisterDefaultNetworkCallback()
        if (clearSavedConfig) {
            clearLastRunningConfig(this)
        }
        runCatching { forwarder?.close() }
        forwarder = null
        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tun?.close() }
        tun = null
        runCatching { bridge.stop() }
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
        super.onDestroy()
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
            .setContentTitle("tcptun VPN")
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
            "tcptun VPN",
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
        const val EXTRA_CONFIG = "config"
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val LOCAL_SOCKS_PORT = 1080
        const val LOCAL_SOCKS_ADDR = "$LOCAL_SOCKS_HOST:$LOCAL_SOCKS_PORT"
        const val LOCAL_SOCKS_LISTEN_ADDR = "0.0.0.0:$LOCAL_SOCKS_PORT"
        const val VPN_MTU = 1500
        private const val CHANNEL_ID = "tcptun_vpn_silent"
        private const val NOTIFICATION_ID = 1001
        private const val ROUTE_CONFIG_FILE = "android-route.json"
        private const val ROUTE_PREFS = "tcptun_route"
        private const val KEY_MANUAL_ROUTE_CONFIG = "manualRouteConfig"
        private const val RUNTIME_PREFS = "tcptun_runtime"
        private const val KEY_LAST_RUNNING_CONFIG = "lastRunningConfig"
        private val ROUTE_KEYS = listOf("domains", "domain_regexes", "domain_suffixes", "ips", "ip_cidrs", "ip_ranges")
        private val IPV4_PROXY_ROUTES = listOf(
            "1.0.0.0" to 8,
            "2.0.0.0" to 7,
            "4.0.0.0" to 6,
            "8.0.0.0" to 7,
            "11.0.0.0" to 8,
            "12.0.0.0" to 6,
            "16.0.0.0" to 4,
            "32.0.0.0" to 3,
            "64.0.0.0" to 3,
            "96.0.0.0" to 6,
            "100.0.0.0" to 10,
            "100.128.0.0" to 9,
            "101.0.0.0" to 8,
            "102.0.0.0" to 7,
            "104.0.0.0" to 5,
            "112.0.0.0" to 5,
            "120.0.0.0" to 6,
            "124.0.0.0" to 7,
            "126.0.0.0" to 8,
            "128.0.0.0" to 3,
            "160.0.0.0" to 5,
            "168.0.0.0" to 8,
            "169.0.0.0" to 9,
            "169.128.0.0" to 10,
            "169.192.0.0" to 11,
            "169.224.0.0" to 12,
            "169.240.0.0" to 13,
            "169.248.0.0" to 14,
            "169.252.0.0" to 15,
            "169.255.0.0" to 16,
            "170.0.0.0" to 7,
            "172.0.0.0" to 12,
            "172.32.0.0" to 11,
            "172.64.0.0" to 10,
            "172.128.0.0" to 9,
            "173.0.0.0" to 8,
            "174.0.0.0" to 7,
            "176.0.0.0" to 4,
            "192.0.0.0" to 9,
            "192.128.0.0" to 11,
            "192.160.0.0" to 13,
            "192.169.0.0" to 16,
            "192.170.0.0" to 15,
            "192.172.0.0" to 14,
            "192.176.0.0" to 12,
            "192.192.0.0" to 10,
            "193.0.0.0" to 8,
            "194.0.0.0" to 7,
            "196.0.0.0" to 6,
            "200.0.0.0" to 5,
            "208.0.0.0" to 4,
        )

        fun startIntent(context: Context, config: AppConfig): Intent {
            val routeConfigPath = ensureAndroidRouteConfig(context)
            val effectiveConfig = config.copy(udp = true)
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, effectiveConfig.toBridgeJson(LOCAL_SOCKS_LISTEN_ADDR, routeConfigPath))
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
                .putExtra("udp", true)
                .putExtra("upstreamProtocol", effectiveConfig.upstreamProtocol)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_STOP)
        }

        private fun saveLastRunningConfig(context: Context, config: AppConfig) {
            context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_RUNNING_CONFIG, config.copy(udp = true).toJson().toString())
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

        fun routeConfigFile(context: Context): File {
            return File(context.applicationContext.filesDir, ROUTE_CONFIG_FILE)
        }

        fun readRouteConfig(context: Context): String {
            val file = routeConfigFile(context)
            return runCatching {
                if (file.exists()) file.readText() else buildEffectiveRouteConfig(context)
            }.getOrElse {
                buildEffectiveRouteConfig(context)
            }
        }

        fun readManualRouteConfig(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(ROUTE_PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_MANUAL_ROUTE_CONFIG, null)
            if (saved != null) {
                val migrated = removeLegacyDefaultRouteConfig(saved)
                if (migrated != saved) {
                    prefs.edit().putString(KEY_MANUAL_ROUTE_CONFIG, migrated).apply()
                }
                return migrated
            }
            val migrated = migrateManualRouteConfig(context)
            prefs.edit().putString(KEY_MANUAL_ROUTE_CONFIG, migrated).apply()
            return migrated
        }

        fun writeManualRouteConfig(context: Context, routeConfig: String): Result<Unit> {
            return runCatching {
                JSONObject(routeConfig)
                val normalized = routeConfig.trim() + "\n"
                context.applicationContext.getSharedPreferences(ROUTE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MANUAL_ROUTE_CONFIG, normalized)
                    .apply()
                writeEffectiveRouteConfig(context)
            }
        }

        fun resetManualRouteConfig(context: Context): Result<Unit> {
            return writeManualRouteConfig(context, emptyRouteConfig())
        }

        fun defaultRouteConfig(): String {
            return buildAndroidRouteConfig()
        }

        private fun ensureAndroidRouteConfig(context: Context): String {
            val file = routeConfigFile(context)
            runCatching {
                writeEffectiveRouteConfig(context)
            }.onFailure { err ->
                TcptunState.appendLog("write android route config failed: ${err.message}")
                return ""
            }
            return file.absolutePath
        }

        private fun writeEffectiveRouteConfig(context: Context) {
            routeConfigFile(context).writeText(buildEffectiveRouteConfig(context).trim() + "\n")
        }

        private fun buildEffectiveRouteConfig(context: Context): String {
            return mergeRouteConfigs(defaultRouteConfig(), readManualRouteConfig(context))
        }

        private fun migrateManualRouteConfig(context: Context): String {
            val file = routeConfigFile(context)
            if (!file.exists()) return emptyRouteConfig()
            val effective = runCatching { file.readText() }.getOrDefault("")
            if (effective.isBlank()) return emptyRouteConfig()
            return removeLegacyDefaultRouteConfig(subtractRouteConfig(effective, defaultRouteConfig()))
        }

        private fun Builder.addProxyRoutes(): Builder {
            IPV4_PROXY_ROUTES.forEach { (address, prefixLength) ->
                addRoute(address, prefixLength)
            }
            return this
        }

        private fun buildAndroidRouteConfig(): String {
            return emptyRouteConfig()
        }

        private fun legacyAndroidRouteConfig(): String {
            val ipCidrs = JSONArray()
            IPV4_PROXY_ROUTES.forEach { (address, prefixLength) ->
                ipCidrs.put("$address/$prefixLength")
            }
            ipCidrs.put("2000::/3")
            return JSONObject()
                .put(
                    "force_upstream",
                    JSONObject()
                        .put("domain_regexes", JSONArray().put(".*"))
                        .put("ip_cidrs", ipCidrs),
                )
                .toString(2)
        }

        private fun removeLegacyDefaultRouteConfig(routeConfig: String): String {
            return subtractRouteConfig(routeConfig, legacyAndroidRouteConfig())
        }

        private fun emptyRouteConfig(): String {
            return JSONObject()
                .put("force_upstream", JSONObject())
                .toString(2)
        }

        private fun mergeRouteConfigs(baseConfig: String, extraConfig: String): String {
            val merged = JSONObject()
            val forceUpstream = JSONObject()
            val base = JSONObject(baseConfig.ifBlank { "{}" }).optJSONObject("force_upstream") ?: JSONObject()
            val extra = JSONObject(extraConfig.ifBlank { "{}" }).optJSONObject("force_upstream") ?: JSONObject()
            ROUTE_KEYS.forEach { key ->
                val array = JSONArray()
                val seen = linkedSetOf<String>()
                appendRouteValues(base.optJSONArray(key), seen, array)
                appendRouteValues(extra.optJSONArray(key), seen, array)
                forceUpstream.put(key, array)
            }
            return merged.put("force_upstream", forceUpstream).toString(2)
        }

        private fun subtractRouteConfig(config: String, defaults: String): String {
            val manual = JSONObject()
            val forceUpstream = JSONObject()
            val source = JSONObject(config.ifBlank { "{}" }).optJSONObject("force_upstream") ?: JSONObject()
            val defaultSource = JSONObject(defaults.ifBlank { "{}" }).optJSONObject("force_upstream") ?: JSONObject()
            ROUTE_KEYS.forEach { key ->
                val defaultValues = jsonArrayValues(defaultSource.optJSONArray(key)).toSet()
                val array = JSONArray()
                jsonArrayValues(source.optJSONArray(key))
                    .filterNot { it in defaultValues }
                    .distinct()
                    .forEach { array.put(it) }
                forceUpstream.put(key, array)
            }
            return manual.put("force_upstream", forceUpstream).toString(2)
        }

        private fun appendRouteValues(source: JSONArray?, seen: MutableSet<String>, target: JSONArray) {
            jsonArrayValues(source).forEach { value ->
                if (seen.add(value)) {
                    target.put(value)
                }
            }
        }

        private fun jsonArrayValues(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        }
    }
}
