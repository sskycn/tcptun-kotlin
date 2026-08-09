package com.tcptun.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Materializes foreground-service notifications without owning VPN lifecycle decisions. */
internal class VpnNotificationController(
    private val service: TcptunVpnService,
) {
    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ChannelId,
            service.getString(R.string.vpn_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.vpn_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        notificationManager().createNotificationChannel(channel)
    }

    fun startForeground(state: String) {
        val notification = build(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(NotificationId, notification)
        }
    }

    fun update(state: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager().notify(NotificationId, build(state))
    }

    private fun build(state: String): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            service,
            1,
            TcptunVpnService.stopIntent(service),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(service, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(service.getString(R.string.vpn_notification_title))
            .setContentText(state)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(state != service.getString(R.string.vpn_notification_stopped))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                service.getString(R.string.vpn_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun notificationManager(): NotificationManager =
        service.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager is unavailable")

    private companion object {
        const val ChannelId = "tcptun_vpn_silent"
        const val NotificationId = 1001
    }
}
