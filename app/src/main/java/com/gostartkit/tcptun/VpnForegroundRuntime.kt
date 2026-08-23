package com.tcptun.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal sealed interface VpnForegroundState {
    data object Starting : VpnForegroundState
    data class Running(val activeConnections: Int? = null) : VpnForegroundState
    data class Reconnecting(val attempt: Int? = null) : VpnForegroundState
    data class Error(val retryingCleanup: Boolean) : VpnForegroundState
}

internal enum class VpnForegroundTextKind {
    Starting,
    Running,
    RunningCount,
    Reconnecting,
    ReconnectingAttempt,
    CleanupPending,
    CleanupRetrying,
}

internal enum class VpnForegroundStartMode {
    Legacy,
    SpecialUse,
}

internal fun VpnForegroundState.textKind(): VpnForegroundTextKind = when (this) {
    VpnForegroundState.Starting -> VpnForegroundTextKind.Starting
    is VpnForegroundState.Running -> if (activeConnections == null) {
        VpnForegroundTextKind.Running
    } else {
        VpnForegroundTextKind.RunningCount
    }
    is VpnForegroundState.Reconnecting -> if (attempt == null) {
        VpnForegroundTextKind.Reconnecting
    } else {
        VpnForegroundTextKind.ReconnectingAttempt
    }
    is VpnForegroundState.Error -> if (retryingCleanup) {
        VpnForegroundTextKind.CleanupRetrying
    } else {
        VpnForegroundTextKind.CleanupPending
    }
}

internal fun foregroundStartMode(sdkInt: Int): VpnForegroundStartMode =
    if (sdkInt >= 34) VpnForegroundStartMode.SpecialUse else VpnForegroundStartMode.Legacy

internal fun foregroundNotificationUpdateAllowed(
    sdkInt: Int,
    notificationPermissionGranted: Boolean,
): Boolean = sdkInt < 33 || notificationPermissionGranted

/** Android service operations needed to materialize foreground notifications. */
internal interface VpnForegroundServicePort {
    val context: Context
    @RequiresApi(26)
    fun createChannel(channel: NotificationChannel)
    fun startForeground(notificationId: Int, notification: Notification)
    @RequiresApi(29)
    fun startForeground(notificationId: Int, notification: Notification, serviceType: Int)
    fun updateNotification(notificationId: Int, notification: Notification)
    fun notificationPermissionGranted(): Boolean
}

internal class AndroidVpnForegroundServicePort(
    private val service: TcptunVpnService,
) : VpnForegroundServicePort {
    override val context: Context
        get() = service

    @RequiresApi(26)
    override fun createChannel(channel: NotificationChannel) {
        notificationManager().createNotificationChannel(channel)
    }

    override fun startForeground(notificationId: Int, notification: Notification) =
        service.startForeground(notificationId, notification)

    @RequiresApi(29)
    override fun startForeground(
        notificationId: Int,
        notification: Notification,
        serviceType: Int,
    ) = service.startForeground(notificationId, notification, serviceType)

    override fun updateNotification(notificationId: Int, notification: Notification) {
        notificationManager().notify(notificationId, notification)
    }

    override fun notificationPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(service, PostNotificationsPermission) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationManager(): NotificationManager =
        service.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager is unavailable")
}

/**
 * Decides how foreground notifications are built and published. The Service
 * remains the sole owner of when foreground state starts, changes, or ends.
 */
internal class VpnForegroundRuntime(
    private val servicePort: VpnForegroundServicePort,
) {
    private val context: Context
        get() = servicePort.context

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        createChannelApi26()
    }

    @RequiresApi(26)
    private fun createChannelApi26() {
        servicePort.createChannel(
            NotificationChannel(
                ChannelId,
                context.getString(R.string.vpn_notification_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.vpn_notification_channel_description)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    fun start(state: VpnForegroundState) {
        val notification = build(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            servicePort.startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            servicePort.startForeground(NotificationId, notification)
        }
    }

    fun update(state: VpnForegroundState) {
        val sdkInt = Build.VERSION.SDK_INT
        val permissionGranted = sdkInt < Build.VERSION_CODES.TIRAMISU ||
            servicePort.notificationPermissionGranted()
        if (!foregroundNotificationUpdateAllowed(sdkInt, permissionGranted)) return
        servicePort.updateNotification(NotificationId, build(state))
    }

    private fun build(state: VpnForegroundState): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            VpnServiceIntents.stop(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.vpn_notification_title))
            .setContentText(state.text())
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.vpn_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun VpnForegroundState.text(): String = when (this) {
        VpnForegroundState.Starting -> context.getString(R.string.vpn_notification_starting)
        is VpnForegroundState.Running -> activeConnections?.let {
            context.resources.getQuantityString(R.plurals.vpn_notification_running, it, it)
        } ?: context.getString(R.string.vpn_notification_running_generic)
        is VpnForegroundState.Reconnecting -> attempt?.let {
            context.getString(R.string.vpn_notification_reconnecting_retry, it)
        } ?: context.getString(R.string.vpn_notification_reconnecting)
        is VpnForegroundState.Error -> context.getString(
            if (retryingCleanup) {
                R.string.vpn_notification_error_retrying_cleanup
            } else {
                R.string.vpn_notification_cleanup_pending
            },
        )
    }

    private companion object {
        const val ChannelId = "tcptun_vpn_silent"
        const val NotificationId = 1001
        const val PostNotificationsPermission = "android.permission.POST_NOTIFICATIONS"
    }
}
