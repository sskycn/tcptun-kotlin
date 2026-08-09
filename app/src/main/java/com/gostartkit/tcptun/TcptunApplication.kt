package com.tcptun.client

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the small amount of work that must outlive a Compose screen but remain
 * bounded by the Android process. The VPN service has its own executor and
 * lifecycle; these scopes are only for dispatching UI-originated commands and
 * durable mutations.
 */
internal class TcptunApplication : Application() {
    private val processJob = SupervisorJob()

    internal val vpnPlanCommandScope = CoroutineScope(
        processJob + Dispatchers.Main.immediate + VpnPlanCommandExceptionHandler,
    )
    internal val durableMutationScope = CoroutineScope(processJob + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        reportPreviousProcessExit()
    }

    override fun onTerminate() {
        processJob.cancel()
        super.onTerminate()
    }

    private fun reportPreviousProcessExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runRecoverableCatching {
            val activityManager = getSystemService(ActivityManager::class.java) ?: return
            val previous = activityManager
                .getHistoricalProcessExitReasons(packageName, 0, 1)
                .firstOrNull()
                ?: return
            val memory = buildList {
                if (previous.pss > 0L) add("pss=${previous.pss}KiB")
                if (previous.rss > 0L) add("rss=${previous.rss}KiB")
            }.joinToString(" ")
            val description = previous.description
                ?.take(MAX_PROCESS_EXIT_DESCRIPTION_LENGTH)
                ?.trim()
                .orEmpty()
            TcptunState.appendLog(
                "previous app process exit: ${processExitReasonLabel(previous.reason)}" +
                    memory.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty() +
                    description.takeIf(String::isNotBlank)?.let { " detail=$it" }.orEmpty(),
            )
        }.onFailure { error ->
            TcptunState.appendLog(
                "previous app process exit unavailable: ${failureDescription(error)}",
            )
        }
    }
}

private const val MAX_PROCESS_EXIT_DESCRIPTION_LENGTH = 1_024

internal fun processExitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "self exit"
    ApplicationExitInfo.REASON_SIGNALED -> "system signal"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
    ApplicationExitInfo.REASON_CRASH -> "Java crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user or system request"
    ApplicationExitInfo.REASON_USER_STOPPED -> "Android user stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
    ApplicationExitInfo.REASON_OTHER -> "other system reason"
    ApplicationExitInfo.REASON_FREEZER -> "app freezer"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
    else -> "unknown ($reason)"
}

private val VpnPlanCommandExceptionHandler = CoroutineExceptionHandler { _, error ->
    if (error.isFatalProcessError()) throw error
    runRecoverableCatching {
        TcptunState.appendLog("VPN command coroutine failed: ${failureDescription(error)}")
    }
}
