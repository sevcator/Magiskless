package com.topjohnwu.magisk.view

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.core.content.getSystemService
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.R
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
object Notifications {

    val mgr by lazy { AppContext.getSystemService<NotificationManager>()!! }

    private const val PROGRESS_CHANNEL = "progress"

    private val nextId = AtomicInteger(10)

    fun setup() {
        AppContext.apply {
            if (SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(PROGRESS_CHANNEL,
                    getString(R.string.progress_channel), NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannels(listOf(channel))
            }
        }
    }

    fun startProgress(title: CharSequence): Notification.Builder {
        val builder = if (SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(AppContext, PROGRESS_CHANNEL)
        } else {
            Notification.Builder(AppContext).setPriority(Notification.PRIORITY_LOW)
        }
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(0, 0, true)
            .setOngoing(true)
        if (SDK_INT >= Build.VERSION_CODES.S)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder
    }

    fun nextId() = nextId.incrementAndGet()
}
