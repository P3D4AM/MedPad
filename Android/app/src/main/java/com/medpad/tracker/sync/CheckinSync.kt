package com.medpad.tracker.sync

import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.api.ApiClient.HttpException
import com.medpad.tracker.api.ApiClientFallback
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.db.PendingCheckin
import com.medpad.tracker.db.QUEUE_STATUS_FAILED
import com.medpad.tracker.db.QUEUE_STATUS_PENDING
import com.medpad.tracker.db.QUEUE_STATUS_SENDING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Offline queue flush + doctors LWW sync.
 * v1.9.1 anti-double-submit: single in-flight guard + localId idempotency +
 * 90s server dedup window.
 *
 * Package 1 (phase 2 / task 3) — queue lifecycle fix (owner report: "events
 * stay in the queue and are never sent"):
 *  - Every item carries an explicit status: pending (LOCAL) / sending
 *    (IN-FLIGHT) / failed (ERROR). Server-confirmed items are deleted from
 *    the queue (that IS the confirmed state — the server is the record).
 *  - PERMANENT server rejections (HTTP 400/404/409/422) mark the item
 *    'failed' with the server's Persian error text and DO NOT block the
 *    rest of the queue — the old code retried them forever and 'break'-ed
 *    the whole flush, so one deleted customer or one bad checkout froze
 *    EVERY later event behind it.
 *  - TRANSIENT failures (network, 5xx, 429) keep the item 'pending' and
 *    stop the round (order matters: a checkout must never overtake its
 *    check-in), retried on the next resume/network-regain/submit.
 *  - P06 (permanent protocol, Oct 2026) REVERSES the old G-1 decision for
 *    403 (customer not assigned — server policy): the rejected event is
 *    kept as 'failed' with the server's error text instead of being
 *    silently deleted — recorded offline data must survive a permission
 *    rejection, stay visible in the queue UI and stay retryable.
 *    401 (token expired/revoked) still stops the whole flush (G-2).
 *  - Crash recovery: items left in 'sending' by a process that died
 *    mid-upload are reset to 'pending' at the start of every flush. A
 *    replay after such a crash cannot duplicate an event server-side
 *    because localId is the idempotency key (the server returns the
 *    original row instead of creating a new one).
 *  - retryFailed() re-queues failed items on explicit user action; the
 *    same localId idempotency makes this always duplicate-safe.
 */
object CheckinSync {

    @Volatile private var inFlight = false
    @Volatile var lastError: String? = null
        private set

    /** G-2 (phase 1 / task 2): set when the server answers 401 — the token
     *  is expired/revoked/invalid and the user must log in again. The UI
     *  (MainActivity) watches this and sends the user to the login screen.
     *  Server-side revocation/deactivation therefore reaches the device on
     *  the next flush or sync attempt, not just "silently" stops syncing. */
    @Volatile var sessionExpired: Boolean = false
        private set

    fun markSessionAlive() { sessionExpired = false }

    /** phase 2 / task 2: plans sync can also observe a 401 — route it through
     *  the SAME signal so the existing session-expired UX (MainActivity) and
     *  PlansActivity both react to one source of truth. */
    fun markSessionExpired() { sessionExpired = true }

    suspend fun enqueue(kind: String, customerId: String?, note: String, voiceFile: File?, lat: Double?, lng: Double?, accuracyM: Double? = null): PendingCheckin {
        val db = AppDatabase.get(TrackerContext.app)
        val item = PendingCheckin(
            localId = UUID.randomUUID().toString(),
            kind = kind,
            customerId = customerId,
            note = note,
            voicePath = voiceFile?.absolutePath,
            lat = lat,
            lng = lng,
            accuracy = accuracyM,
        )
        val uid = db.pendingCheckinDao().insert(item)
        return item.copy(uid = uid)
    }

    /** mission item 6 — read one queued item back by its idempotency key.
     *  The submit dialogs call this right after flush(): a null return =
     *  the server CONFIRMED the event (it left the queue); a 'failed'
     *  status carries the server's Persian rejection text (e.g. the 422
     *  geofence message with the exact distance + allowed radius), which
     *  the dialog shows verbatim — the user sees WHY, not a generic error. */
    suspend fun itemByLocalId(localId: String): PendingCheckin? = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCheckinDao().byLocalId(localId)
    }

    /** Flush whole queue. Returns number of items sent.
     *  Failed (permanently rejected) items are skipped, not retried here —
     *  they are visible in the queue UI with their error and can be
     *  re-queued with retryFailed(). */
    suspend fun flush(): Int = withContext(Dispatchers.IO) {
        if (inFlight) return@withContext 0 // guard: immediate send + 45s tick can overlap
        inFlight = true
        try {
            val db = AppDatabase.get(TrackerContext.app)
            // crash recovery: a previous process may have died mid-upload
            // with items marked 'sending' — bring them back to 'pending'
            db.pendingCheckinDao().resetSending()
            var sent = 0
            for (item in db.pendingCheckinDao().all()) {
                if (item.status == QUEUE_STATUS_FAILED) continue // user-visible; manual retry only
                when (flushOne(db, item)) {
                    FlushOutcome.SENT -> sent++
                    // Package 1 / P06: permanent rejection (400/404/409/422
                    // and, since P06, 403) — keep the event data as 'failed'
                    // (readable error + manual retry) but keep flushing the
                    // rest of the queue.
                    FlushOutcome.FAILED -> { /* continue with the next item */ }
                    FlushOutcome.RETRY -> break // transient failure — keep order
                }
            }
            sent
        } finally {
            inFlight = false
        }
    }

    private enum class FlushOutcome { SENT, FAILED, RETRY }

    /** phase 2 / §5 (dev.4) — drop the ENTIRE offline queue, including the
     *  local voice files of unsent items. Called ONLY from the change-server
     *  sheet's forced-relogin path (new address = a DIFFERENT database —
     *  queued items belong to the old server and must never be replayed
     *  against the new one). The user confirmed the loss with the exact
     *  count BEFORE the switch was saved; returns the dropped item count. */
    suspend fun clearQueue(): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(TrackerContext.app)
        val items = db.pendingCheckinDao().all()
        items.forEach { it.voicePath?.let { p -> File(p).delete() } }
        db.pendingCheckinDao().clearAll()
        items.size
    }

    /** Package 1: counts of items still waiting (local + in-flight). */
    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCheckinDao().pendingCount()
    }

    /** Package 1: count of permanently failed items (kept with their errors). */
    suspend fun failedCount(): Int = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCheckinDao().failedCount()
    }

    /** Package 1: failed items for the queue-details UI. */
    suspend fun failedItems(): List<PendingCheckin> = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCheckinDao().failed()
    }

    /** Package 1: manual retry — re-queue every failed item and flush.
     *  Always duplicate-safe: the server's localId idempotency returns the
     *  original row for an event that actually landed, so a retry can never
     *  create a second check-in/checkout. Returns number of items sent. */
    suspend fun retryFailed(): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(TrackerContext.app)
        db.pendingCheckinDao().requeueFailed()
        flush()
    }

    /**
     * Send one item. multipart with voice → on HTTP 400/404 from an old panel,
     * fall back to JSON text-only (voice dropped, note preserved — never lost).
     * P06: 403 = permanently rejected (e.g. unassigned customer) → keep as
     * 'failed' (record survives; NOT dropped).
     * Package 1: 400/404/409/422 = permanent rejection by THIS panel → mark
     * 'failed' (keep data + error for the user) and keep the queue flowing.
     * Transient (network/5xx/429) → keep 'pending' + stop the round.
     */
    private suspend fun flushOne(db: AppDatabase, item: PendingCheckin): FlushOutcome {
        val voice = item.voicePath?.let { File(it) }?.takeIf { it.exists() }
        // Package 1: in-flight marker — if the process dies here, the next
        // flush's resetSending() brings the item back to 'pending'.
        db.pendingCheckinDao().update(item.copy(status = QUEUE_STATUS_SENDING))
        val current = item.copy(status = QUEUE_STATUS_SENDING)
        try {
            val resp: JSONObject = when (item.kind) {
                "checkin" -> ApiClient.checkin(
                    customerId = item.customerId ?: "",
                    note = item.note,
                    lat = item.lat,
                    lng = item.lng,
                    localId = item.localId,
                    voice = voice,
                    clientAt = item.createdAt, // v1.9.12 (phase 1): real event time
                    accuracyM = item.accuracy,  // G-6 (phase 1 / task 2)
                )
                else -> ApiClient.checkout(
                    note = item.note,
                    localId = item.localId,
                    voice = voice,
                    clientAt = item.createdAt, // v1.9.12 (phase 1): real event time
                    lat = item.lat,            // G-6 (phase 1 / task 2)
                    lng = item.lng,
                    accuracyM = item.accuracy,
                )
            }
            if (resp.optBoolean("ok")) {
                db.pendingCheckinDao().delete(item) // server-confirmed → leave the queue
                lastError = null
                markSessionAlive()
                return FlushOutcome.SENT
            }
            // ok=false with 200 — unexpected from this panel; treat as a
            // single retry, then let the NEXT failure path categorize it
            lastError = resp.optString("error", "server said not ok")
            db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_PENDING, attempts = item.attempts + 1, lastError = lastError))
            return FlushOutcome.RETRY
        } catch (e: HttpException) {
            when (e.code) {
                401 -> { // token invalid/expired/revoked → stop flushing, keep data
                    lastError = "unauthorized"
                    sessionExpired = true
                    db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_PENDING, attempts = item.attempts + 1, lastError = lastError))
                    return FlushOutcome.RETRY
                }
                403 -> {
                    // P06 (permanent protocol, Oct 2026): 403 = permanent
                    // rejection by server policy (e.g. customer no longer
                    // assigned to this rep) — but the offline record itself
                    // must NOT be silently destroyed. This REVERSES the old
                    // G-1 "drop" decision: keep the event as 'failed' with
                    // the server's error text, visible in the queue UI and
                    // manually retryable (if the assignment is restored,
                    // retry succeeds — localId idempotency makes it safe).
                    lastError = "رد شده توسط سرور (403): ${e.message}"
                    db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_FAILED, attempts = item.attempts + 1, lastError = lastError))
                    return FlushOutcome.FAILED
                }
                400, 404 -> {
                    // old panel rejected multipart → fallback JSON without
                    // voice. P08 (permanent protocol): the voice drop is the
                    // ONLY deliberate loss here (old panels ≤1.7 cannot
                    // receive a file; the app shows the old-panel warning and
                    // this comment documents the drop). Everything else the
                    // event recorded is now SENT TOO: old panels ignore the
                    // unknown JSON keys harmlessly, and any server that
                    // understands them (current panels parse multipart only,
                    // so the fallback can only succeed against old/JSON
                    // servers) receives the full event — no silent discard of
                    // time/location any more.
                    if (item.kind == "checkin" && voice != null) {
                        try {
                            val body = JSONObject()
                                .put("customerId", item.customerId ?: "")
                                .put("note", item.note)
                                .put("localId", item.localId)
                                // P08: mirror ApiClient.checkin's multipart
                                // fields exactly (v1.9.12 real event time +
                                // G-6 GPS fix and accuracy)
                                .put("clientAt", ApiClient.isoTime(item.createdAt))
                            if (item.lat != null && item.lng != null) {
                                body.put("lat", item.lat)
                                body.put("lng", item.lng)
                                item.accuracy?.let { if (it > 0) body.put("accuracy", it) }
                            }
                            val resp = ApiClientFallback.postJson("/api/app/checkin", body)
                            if (resp.optBoolean("ok")) {
                                db.pendingCheckinDao().delete(item)
                                lastError = null
                                return FlushOutcome.SENT
                            }
                        } catch (e2: Exception) {
                            lastError = e2.message
                        }
                    }
                    // Package 1: permanent client-error answer from this
                    // panel — keep the event as 'failed' (readable error,
                    // manual retry) instead of blocking the queue forever.
                    lastError = e.message
                    db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_FAILED, attempts = item.attempts + 1, lastError = lastError))
                    return FlushOutcome.FAILED
                }
                409, 422 -> {
                    // 409: checkout without an open visit / plan conflicts —
                    // permanent for this queued event as-is. 422: strict GPS
                    // radius rejection — the queued fix can never get closer.
                    // Both become 'failed' (kept + readable + retryable).
                    lastError = e.message
                    db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_FAILED, attempts = item.attempts + 1, lastError = lastError))
                    return FlushOutcome.FAILED
                }
                else -> {
                    // 5xx / 429 / anything else — transient: keep pending
                    lastError = e.message
                    db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_PENDING, attempts = item.attempts + 1, lastError = lastError))
                    return FlushOutcome.RETRY
                }
            }
        } catch (e: Exception) {
            // network error etc. — transient: keep pending
            lastError = e.message
            db.pendingCheckinDao().update(current.copy(status = QUEUE_STATUS_PENDING, attempts = item.attempts + 1, lastError = lastError))
            return FlushOutcome.RETRY
        }
    }

    // ── doctors LWW sync ────────────────────────────────────
    suspend fun syncDoctors(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.get(TrackerContext.app)
            val maxUpdated = db.doctorDao().maxUpdatedAt() ?: 0L
            val since = if (maxUpdated > 0) java.time.Instant.ofEpochMilli(maxUpdated).toString() else null
            val resp = ApiClient.customers(since)
            if (!resp.optBoolean("ok")) return@withContext false

            val assignedIds = HashSet<String>()
            val arr: JSONArray = resp.optJSONArray("assignedIds") ?: JSONArray()
            for (i in 0 until arr.length()) assignedIds.add(arr.getString(i))

            val list = ArrayList<Doctor>()
            val cus: JSONArray = resp.optJSONArray("customers") ?: JSONArray()
            for (i in 0 until cus.length()) {
                val o = cus.getJSONObject(i)
                val updatedMs = parseIso(o.optString("updatedAt"))
                // LWW guard: skip older versions (panel edit racing with sync)
                val existing = db.doctorDao().byId(o.optString("id"))
                if (existing != null && existing.updatedAt > updatedMs) continue
                list.add(
                    Doctor(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        type = o.optString("type", "doctor"),
                        address = o.optString("address", null),
                        phone = o.optString("phone", null),
                        // package 8 — city/province (absent on old servers → null)
                        province = o.optString("province", null),
                        city = o.optString("city", null),
                        lat = o.optDouble("lat", 0.0),
                        lng = o.optDouble("lng", 0.0),
                        radius = o.optDouble("radius", 150.0),
                        updatedAt = updatedMs,
                        // G-1 (phase 1 / task 2): the server now only returns
                        // ASSIGNED customers — `assigned` mirrors server truth.
                        assigned = true,
                    )
                )
            }
            if (list.isNotEmpty()) db.doctorDao().upsertAll(list)
            // G-1 (phase 1 / task 2): reconcile with the server's assignment
            // truth — drop local rows that are no longer assigned to this
            // employee (works for both full and incremental syncs because the
            // server always returns the complete assignedIds list).
            if (assignedIds.isEmpty()) db.doctorDao().clear()
            else db.doctorDao().keepOnly(assignedIds.toList())
            lastError = null
            markSessionAlive() // a successful authed call proves the token works
            true
        } catch (e: Exception) {
            lastError = e.message
            if (e is HttpException && e.code == 401) sessionExpired = true
            false
        }
    }

    private fun parseIso(s: String): Long = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

/** App context holder set by TrackerApp */
object TrackerContext {
    lateinit var app: android.content.Context
        private set
    fun init(c: android.content.Context) {
        app = c.applicationContext
    }
}
