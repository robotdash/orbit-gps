package com.robotdash.orbitgps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps Orbit GPS running while you navigate with the screen off or another app
 * (music, phone) in front. Android only lets an app keep using GPS in the
 * background while it shows an ongoing notification; this is that notification.
 * The navigation itself keeps running in the app as normal.
 */
class NavigationService : Service() {

    companion object {
        private const val CHANNEL = "navigation"
        private const val ID = 7
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, NavigationService::class.java).putExtra(EXTRA_TEXT, text)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        /** Update the notification line (next turn). */
        fun update(ctx: Context, text: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(ID, build(ctx, text))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NavigationService::class.java))
        }

        private fun build(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setContentIntent(open)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.navigating)
        ServiceCompat.startForeground(
            this, ID, build(this, text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
