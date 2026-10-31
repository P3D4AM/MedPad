package com.medpad.tracker.sync

import com.medpad.tracker.Session
import com.medpad.tracker.TrackerApp
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.OpEventEntity
import com.medpad.tracker.sync.TrackerContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Mission item 10 (Oct 31) — the device-side operational event recorder +
 * uploader (the twin of LocationSync, same durability contract).
 *
 * record(): append one event locally (UUID idempotency key) — NEVER throws
 * into the caller: event logging must not break the operation it observes.
 *
 * flushOnce(): upload the oldest batch (≤100) to POST /api/app/events.
 *  - 200: the batch is acknowledged → rows deleted (server-confirmed).
 *  - 400: the server rejected the batch as INVALID (e.g. an event type it
 *    does not know — an app newer than the panel): the rows are dropped —
 *    retrying structurally-invalid data forever would clog the queue.
 *  - network / 5xx / 429: rows stay; the next tick retries (no silent loss).
 *
 * Payload mirror of the server route: events[] with localId/type/status/at/
 * errorCode?/message?/relatedType?/relatedId?/attempt? — appVersion rides
 * along from the running BuildConfig version name.
 */
object OpEvents {

    const val BATCH = 100

    /** Record one event (fire-and-forget; never throws). */
    suspend fun record(
        type: String,
        status: String = "success",
        errorCode: String? = null,
        message: String? = null,
        relatedType: String? = null,
        relatedId: String? = null,
        attempt: Int? = null,
    ) {
        try {
            withContext(Dispatchers.IO) {
                AppDatabase.get(TrackerContext.app).opEventDao().insert(
                    OpEventEntity(
                        localId = UUID.randomUUID().toString(),
                        type = type,
                        status = status,
                        errorCode = errorCode,
                        message = message?.take(300),
                        relatedType = relatedType,
                        relatedId = relatedId,
                        attempt = attempt,
                    ),
                )
            }
        } catch (_: Exception) {
            // event logging must never break the observed operation
        }
    }

    /** Build the wire payload for a batch (pure — unit-tested). */
    fun buildPayload(rows: List<OpEventEntity>, appVersion: String): JSONObject {
        val arr = JSONArray()
        for (r in rows) {
            val o = JSONObject()
                .put("localId", r.localId)
                .put("type", r.type)
                .put("status", r.status)
                .put("at", ApiClient.isoTime(r.createdAt))
            r.errorCode?.let { o.put("errorCode", it) }
            r.message?.let { o.put("message", it) }
            r.relatedType?.let { o.put("relatedType", it) }
            r.relatedId?.let { o.put("relatedId", it) }
            r.attempt?.let { o.put("attempt", it) }
            o.put("appVersion", appVersion)
            arr.put(o)
        }
        return JSONObject().put("events", arr)
    }

    /** One upload round. Returns the number of rows that LEFT the local
     *  queue (acknowledged or structurally-rejected), or -1 on a transient
     *  failure (rows kept). */
    suspend fun flushOnce(): Int = withContext(Dispatchers.IO) {
        if (!Session.isLoggedIn) return@withContext 0
        val db = AppDatabase.get(TrackerContext.app)
        val rows = db.opEventDao().oldest(BATCH)
        if (rows.isEmpty()) return@withContext 0
        val payload = buildPayload(rows, appVersion())
        try {
            val resp = ApiClient.uploadEvents(payload)
            // acknowledged (stored + duplicated both mean: the server has them)
            db.opEventDao().deleteByUids(rows.map { it.uid })
            rows.size
        } catch (e: ApiClient.HttpException) {
            if (e.code == 400 || e.code == 404 || e.code == 422) {
                // the panel does not understand this batch (old server vs new
                // app) — structurally invalid; keeping it would clog forever
                db.opEventDao().deleteByUids(rows.map { it.uid })
                rows.size
            } else {
                -1 // transient (401 stops at CheckinSync's session handling, 5xx/429)
            }
        } catch (_: Exception) {
            -1 // network — rows stay for the next round
        }
    }

    private fun appVersion(): String = try {
        TrackerApp.instance.packageManager.getPackageInfo(TrackerApp.instance.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
