package com.medpad.tracker.util

import android.content.Context
import android.content.SharedPreferences

/**
 * package 11 — in-app notification settings & dedup state.
 *
 * Three kinds of notifications exist in MedPad (deliberately distinct):
 *  1. LOCAL — proximity alerts fired by TrackingService's geofence check
 *     (device-side, throttled to one per customer per 10 minutes).
 *  2. SERVER — AppNotification rows the panel writes (assignment, plans,
 *     admin broadcast…), polled by TrackingService every ~40 s while the
 *     app/tracking is active and shown through the system notification.
 *  3. REAL PUSH (FCM/APNs-style) — NOT implemented: it requires an
 *     external push service (Firebase project + google-services.json +
 *     Play Services on the device), which contradicts the offline-first,
 *     LAN-server architecture. No instant background delivery is claimed;
 *     notifications arrive when the app or the tracking service runs.
 *
 * This object stores the user's toggles for (1) and (2) plus the set of
 * already-SHOWN server-notification ids so a poll→mark-read race or a
 * service restart can never re-fire the same notification (the old code
 * deduped only within a single poll response).
 */
object NotificationPrefs {
    private const val PREFS = "medpad_notif"
    private const val KEY_SERVER = "server_notifications_enabled"
    private const val KEY_PROXIMITY = "proximity_alerts_enabled"
    private const val KEY_SHOWN = "shown_notification_ids"
    private const val SHOWN_CAP = 200

    /** Server (panel) notifications — polled & shown as system notifications. */
    var serverEnabled: Boolean
        get() = prefs()?.getBoolean(KEY_SERVER, true) ?: true
        set(v) = prefs()?.edit()?.putBoolean(KEY_SERVER, v)?.apply() ?: Unit

    /** Local proximity alerts («نزدیک مشتری هستید»). */
    var proximityEnabled: Boolean
        get() = prefs()?.getBoolean(KEY_PROXIMITY, true) ?: true
        set(v) = prefs()?.edit()?.putBoolean(KEY_PROXIMITY, v)?.apply() ?: Unit

    /** True when this server-notification id was already shown (dedup). */
    fun wasShown(context: Context, id: String): Boolean =
        prefs(context)?.getStringSet(KEY_SHOWN, emptySet())?.contains(id) == true

    /** Record a shown id; prunes the set to the newest SHOWN_CAP entries. */
    fun markShown(context: Context, id: String) {
        val p = prefs(context) ?: return
        val cur = HashSet(p.getStringSet(KEY_SHOWN, emptySet()) ?: emptySet())
        cur.add(id)
        if (cur.size > SHOWN_CAP) {
            // keep a deterministic subset — ids are cuids; dropping the
            // lexicographically smallest mimics "oldest first" well enough
            // for a dedup window of 200
            val keep = cur.sortedDescending().take(SHOWN_CAP).toSet()
            p.edit().putStringSet(KEY_SHOWN, keep).apply()
        } else {
            p.edit().putStringSet(KEY_SHOWN, cur).apply()
        }
    }

    /** Test/maintenance hook — clears the dedup window. */
    fun clearShown(context: Context) {
        prefs(context)?.edit()?.remove(KEY_SHOWN)?.apply()
    }

    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun prefs(context: Context? = null): SharedPreferences? =
        (context ?: ctx)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
