package com.sskycn.tcptun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TcptunVpnService : VpnService() {
    private val bridge: TcptunBridge = ReflectionTcptunBridge()
    private var forwarder: TunSocksForwarder? = null
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
        }
        return START_STICKY
    }

    private fun startFromIntent(intent: Intent) {
        val json = intent.getStringExtra(EXTRA_CONFIG) ?: run {
            TcptunState.error("missing VPN config")
            stopSelf()
            return
        }
        Thread {
            try {
                TcptunState.setStatus("Starting")
                startForeground(NOTIFICATION_ID, buildNotification("Starting"))
                val config = AppConfig(
                    serverHost = intent.getStringExtra("serverHost").orEmpty(),
                    serverPort = intent.getStringExtra("serverPort") ?: "9443",
                    protocol = intent.getStringExtra("protocol") ?: "native",
                    transport = intent.getStringExtra("transport") ?: "raw",
                    token = intent.getStringExtra("token").orEmpty(),
                    sni = intent.getStringExtra("sni").orEmpty(),
                    path = intent.getStringExtra("path") ?: "/proxy",
                    tls = intent.getBooleanExtra("tls", false),
                    mux = intent.getBooleanExtra("mux", true),
                    udp = intent.getBooleanExtra("udp", true),
                )
                bridge.setLogCallback(TcptunState::appendLog)
                bridge.start(json)
                val tun = buildTun(config)
                forwarder = TunSocksForwarder(
                    tun = tun,
                    socksHost = LOCAL_SOCKS_HOST,
                    socksPort = LOCAL_SOCKS_PORT,
                    enableUdp = config.udp,
                    log = TcptunState::appendLog,
                ).also { it.start() }
                TcptunState.setStatus("Running")
                updateNotification("Running")
            } catch (err: Exception) {
                TcptunState.error(err.message ?: err.javaClass.simpleName)
                stopVpn(setStopped = false)
            }
        }.start()
    }

    private fun buildTun(config: AppConfig) = Builder()
        .setSession("tcptun")
        .setMtu(1500)
        .addAddress("10.77.0.2", 32)
        .addRoute("0.0.0.0", 0)
        .addDnsServer("1.1.1.1")
        .addDnsServer("8.8.8.8")
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addDisallowedApplication(packageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(false)
                setHttpProxy(ProxyInfo.buildDirectProxy(LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT))
            }
            if (config.udp) {
                allowFamily(android.system.OsConstants.AF_INET)
            }
        }
        .establish() ?: throw IllegalStateException("VpnService establish() returned null")

    private fun stopVpn(setStopped: Boolean = true) {
        if (stopping) return
        stopping = true
        runCatching { forwarder?.close() }
        forwarder = null
        runCatching { bridge.stop() }
        if (setStopped) {
            TcptunState.setStatus("Stopped")
        }
        updateNotification("Stopped")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn(setStopped = TcptunState.status.value != "Error")
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
            .setOngoing(state != "Stopped")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(state: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "tcptun VPN",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.sskycn.tcptun.START"
        const val ACTION_STOP = "com.sskycn.tcptun.STOP"
        const val EXTRA_CONFIG = "config"
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val LOCAL_SOCKS_PORT = 1080
        const val LOCAL_SOCKS_ADDR = "$LOCAL_SOCKS_HOST:$LOCAL_SOCKS_PORT"
        const val LOCAL_SOCKS_LISTEN_ADDR = "0.0.0.0:$LOCAL_SOCKS_PORT"
        private const val CHANNEL_ID = "tcptun_vpn"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context, config: AppConfig): Intent {
            return Intent(context, TcptunVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config.toBridgeJson(LOCAL_SOCKS_LISTEN_ADDR))
                .putExtra("serverHost", config.serverHost)
                .putExtra("serverPort", config.serverPort)
                .putExtra("protocol", config.protocol)
                .putExtra("transport", config.transport)
                .putExtra("token", config.token)
                .putExtra("sni", config.sni)
                .putExtra("path", config.path)
                .putExtra("tls", config.tls)
                .putExtra("mux", config.mux)
                .putExtra("udp", config.udp)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TcptunVpnService::class.java).setAction(ACTION_STOP)
        }
    }
}
