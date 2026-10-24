package com.medpad.tracker.sync

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.medpad.tracker.R
import com.medpad.tracker.TrackerApp

/** Shared helper for in-app notifications (service + activities). */
object AppNotifier {

    fun notify(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, TrackerApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
        } catch (_: Exception) {
            /* notifications disabled */
        }
    }
}
