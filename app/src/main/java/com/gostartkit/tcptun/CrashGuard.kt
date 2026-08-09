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

/**
 * A lifecycle scheduler must unlink cancelled debounce tasks immediately.
 * The default policy retains each task (and its captured object graph) until
 * the original delay expires, which can be up to 24 hours in this service.
 */
internal fun newLifecycleScheduledExecutor(threadName: String): ScheduledThreadPoolExecutor =
    ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

/**
 * A native probe can fail to honor its timeout or Java interruption. Keep later
 * batches bounded so one stuck worker cannot accumulate cancelled FutureTasks
 * and every object graph captured by them.
 */
internal fun newBoundedLifecycleExecutor(
    threadName: String,
    queueCapacity: Int,
): ThreadPoolExecutor {
    require(queueCapacity > 0) { "lifecycle executor queue capacity must be positive" }
    return ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
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

