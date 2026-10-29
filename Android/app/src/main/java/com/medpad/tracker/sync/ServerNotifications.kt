package com.medpad.tracker.sync

import android.content.Context
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.util.NotificationPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * P07 (permanent protocol) — the server-notification poll, extracted from
 * TrackingService so the delivery contract is unit-testable.
 *
 * Three states are deliberately distinct (P07):
 *  - RECEIVED: present in the server's notification list for this poll.
 *  - SHOWN:    successfully handed to the system NotificationManager. The
 *              persisted dedup set is written ONLY after a successful
 *              display — a failed display is never deduped.
 *  - READ:     server-side readAt, marked PER-ID for exactly the
 *              notifications the app has actually shown (now, or in an
 *              earlier round whose mark-read call failed — convergence for
 *              the package-11 poll→mark-read race).
 *
 * P07 core rule: a DISPLAY FAILURE must NOT silently lose the
 * notification. A failed display is neither deduped nor marked read, so
 * the next poll retries it (bounded: one attempt per ~40 s poll round —
 * no tight loop) and no duplicate can ever be shown (dedup is only
 * written after success). The read call also never uses the blanket
 * mark-all: rows that arrived on the server between the list and read
 * calls stay unread instead of being consumed unseen.
 */
object ServerNotifications {

    /** One poll round's outcome (for tests + future diagnostics). */
    data class PollResult(
        val displayed: Int,      // shown now (and deduped + marked read)
        val failedDisplays: Int, // display failed → kept for the next poll
        val markedRead: Int,     // ids sent to the server's read endpoint
    )

    /**
     * One poll round. [display] hands a single notification to the system
     * and must return whether the display SUCCEEDED (TrackingService
     * passes AppNotifier.notify; tests simulate failures). Display
     * exceptions count as failures — they must not abort the round.
     *
     * phase 2 / §1: the display callback now also receives the notification
     * KIND and CUSTOMER ID so the caller can attach the tap-through
     * contentIntent (NotificationTapRouter) — the tap opens the related
     * screen per kind.
     */
    suspend fun pollOnce(
        context: Context,
        display: (notifId: Int, title: String, body: String, kind: String?, customerId: String?) -> Boolean,
    ): PollResult = withContext(Dispatchers.IO) {
        val resp = ApiClient.notifications()
        val arr: JSONArray = resp.optJSONArray("notifications") ?: JSONArray()
        var displayed = 0
        var failed = 0
        val seenIds = ArrayList<String>(arr.length()) // shown now or in an earlier round

        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            if (n.has("readAt") && !n.isNull("readAt")) continue // already read server-side
            val id = n.getString("id")
            if (NotificationPrefs.wasShown(context, id)) {
                // displayed in an earlier round (e.g. its mark-read call
                // failed) — never re-display it, but still converge readAt
                seenIds.add(id)
                continue
            }
            val title = n.optString("title")
            val body = n.optString("body")
            // phase 2 / §1: kind + customerId feed the tap-through routing
            val kind = if (n.has("kind") && !n.isNull("kind")) n.optString("kind") else null
            val customerId = if (n.has("customerId") && !n.isNull("customerId")) n.optString("customerId") else null
            val shown = try {
                display(id.hashCode(), title, body, kind, customerId)
            } catch (_: Exception) {
                false
            }
            if (shown) {
                NotificationPrefs.markShown(context, id)
                seenIds.add(id)
                displayed++
            } else {
                // P07: display failed — NOT deduped, NOT marked read →
                // the next poll retries this notification. No silent loss.
                failed++
            }
        }

        // Mark read EXACTLY what the app processed — never the blanket
        // mark-all (which consumed rows that arrived after the list call
        // and rows whose display had just failed).
        var markedRead = 0
        if (seenIds.isNotEmpty()) {
            ApiClient.markNotificationsRead(seenIds)
            markedRead = seenIds.size
        }
        PollResult(displayed, failed, markedRead)
    }
}
