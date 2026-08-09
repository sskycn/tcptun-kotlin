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

internal data class UpstreamProbeTarget(
    val label: String,
    val host: String,
    val port: Int = 443,
    val path: String = "/",
    val expectedStatus: Int? = null,
)

internal data class HealthFailure(
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

internal data class MemberHealthProbeResult(
    val profile: AppConfig,
    val elapsedMs: Long? = null,
    val error: String = "",
)

internal data class BridgeReadyWaiter(
    val epoch: Long,
    val future: CompletableFuture<Unit> = CompletableFuture(),
)

internal data class BridgeRuntimeSnapshot(
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

