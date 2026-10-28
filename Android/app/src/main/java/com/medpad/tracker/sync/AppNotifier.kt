package com.medpad.tracker.sync

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.medpad.tracker.R
import com.medpad.tracker.TrackerApp

/** Shared helper for in-app notifications (service + activities). */
object AppNotifier {

    /** P07 (permanent protocol): returns true when the notification was
     *  successfully handed to the system NotificationManager, false when
     *  the display failed (app notifications disabled, channel blocked,
     *  manager error…). Callers that track delivery (the server-notification
     *  poll) use the result to keep the notification for a retry instead of
     *  silently losing it; fire-and-forget callers (proximity alerts) may
     *  ignore it. */
    fun notify(context: Context, id: Int, title: String, text: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, TrackerApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(id, n)
            true
        } catch (_: Exception) {
            false // notifications disabled — caller may retry later
        }
    }
}
