package com.medpad.tracker.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.medpad.tracker.R
import com.medpad.tracker.TrackerApp

/** Shared helper for in-app notifications (service + activities). */
object AppNotifier {

    /**
     * P07 + mission item 4 — can the system actually SHOW a notification
     * right now? The pure predicate (unit-tested) behind [notify].
     *
     * On API 33+ a denied POST_NOTIFICATIONS permission makes
     * NotificationManager.notify() a SILENT no-op — no exception — so the
     * pre-mission code reported success, the poll marked the notification
     * read AND deduped it, and the user never saw it: a PERMANENT silent
     * loss. Reporting failure instead keeps the row unread and undeduped,
     * so the next poll retries it (bounded: once per round) and the
     * notification appears the moment the permission is granted.
     *
     * A blocked CHANNEL (importance NONE) is equally display-dead.
     */
    fun displayAllowed(notificationsEnabled: Boolean, channelImportance: Int?): Boolean =
        notificationsEnabled && (channelImportance == null || channelImportance != NotificationManager.IMPORTANCE_NONE)

    /** The live values feeding [displayAllowed] (null channel = pre-O). */
    fun channelImportance(nm: NotificationManager, channelId: String): Int? =
        if (Build.VERSION.SDK_INT >= 26) {
            nm.getNotificationChannel(channelId)?.importance
        } else {
            null
        }

    /** P07 (permanent protocol): returns true when the notification was
     *  successfully handed to the system NotificationManager, false when
     *  the display failed (app notifications disabled, channel blocked,
     *  manager error…). Callers that track delivery (the server-notification
     *  poll) use the result to keep the notification for a retry instead of
     *  silently losing it; fire-and-forget callers (proximity alerts) may
     *  ignore it.
     *
     *  phase 2 / §1: [contentIntent] makes the notification TAPPABLE —
     *  when non-null it is attached so tapping opens the related record
     *  (see NotificationTapRouter). Callers that cannot determine a
     *  destination may pass null; the notification then behaves as before
     *  (autoCancel dismiss, no navigation).
     *
     *  mission item 4 (locked phone): alerts are VISIBILITY_PUBLIC (the
     *  text is operational, not private — it must appear on the lock
     *  screen), high priority (pre-O) + high-importance channel (TrackerApp),
     *  and CATEGORY_EVENT so OEM classifiers treat them as user-facing. */
    fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent? = null,
    ): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // mission item 4 — honest display result (see displayAllowed)
        if (!displayAllowed(nm.areNotificationsEnabled(), channelImportance(nm, TrackerApp.CHANNEL_ALERTS))) {
            return false // caller keeps the notification for a retry
        }
        val builder = NotificationCompat.Builder(context, TrackerApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        val n = builder.build()
        return try {
            nm.notify(id, n)
            true
        } catch (_: Exception) {
            false // notifications disabled — caller may retry later
        }
    }
}
