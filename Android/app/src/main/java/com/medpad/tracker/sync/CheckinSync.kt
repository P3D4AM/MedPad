package com.medpad.tracker.sync

import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.api.ApiClient.HttpException
import com.medpad.tracker.api.ApiClientFallback
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.db.PendingCheckin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Offline queue flush + doctors LWW sync.
 * v1.9.1 anti-double-submit: single in-flight guard + localId idempotency +
 * 90s server dedup window. Try/catch keeps the queue item on transient errors
 * but permanently drops server-confirmed duplicates.
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

    /** Flush whole queue. Returns number of items sent. */
    suspend fun flush(): Int = withContext(Dispatchers.IO) {
        if (inFlight) return@withContext 0 // guard: immediate send + 45s tick can overlap
        inFlight = true
        try {
            val db = AppDatabase.get(TrackerContext.app)
            var sent = 0
            for (item in db.pendingCheckinDao().all()) {
                when (flushOne(db, item)) {
                    FlushResult.SENT -> sent++
                    // G-1 (phase 1 / task 2): server permanently rejected the
                    // item (e.g. customer no longer assigned) — drop it and
                    // keep flushing; a 403 must not block the whole queue.
                    FlushResult.DROPPED -> { /* continue with the next item */ }
                    FlushResult.RETRY -> break // transient failure — keep order
                }
            }
            sent
        } finally {
            inFlight = false
        }
    }

    private enum class FlushResult { SENT, DROPPED, RETRY }

    suspend fun queueCount(): Int = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCheckinDao().count()
    }

    /**
     * Send one item. multipart with voice → on HTTP 400/404 from an old panel,
     * fall back to JSON text-only (voice dropped, note preserved — never lost).
     * G-1: 403 = permanently rejected (e.g. unassigned customer) → DROP.
     */
    private suspend fun flushOne(db: AppDatabase, item: PendingCheckin): FlushResult {
        val voice = item.voicePath?.let { File(it) }?.takeIf { it.exists() }
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
                db.pendingCheckinDao().delete(item)
                lastError = null
                markSessionAlive()
                return FlushResult.SENT
            }
            // ok=false with 200 — treat as failure
            lastError = resp.optString("error", "server said not ok")
            bump(db, item)
            return FlushResult.RETRY
        } catch (e: HttpException) {
            when (e.code) {
                401 -> { // token invalid/expired/revoked → stop flushing, keep data
                    lastError = "unauthorized"
                    sessionExpired = true
                    bump(db, item)
                    return FlushResult.RETRY
                }
                403 -> { // G-1: not assigned (server policy) — permanent, drop it
                    lastError = e.message
                    db.pendingCheckinDao().delete(item)
                    return FlushResult.DROPPED
                }
                400, 404, 409 -> {
                    // old panel rejected multipart (or short note) → fallback JSON without voice
                    if (item.kind == "checkin" && voice != null) {
                        try {
                            val body = JSONObject()
                                .put("customerId", item.customerId ?: "")
                                .put("note", item.note)
                                .put("localId", item.localId)
                            val resp = ApiClientFallback.postJson("/api/app/checkin", body)
                            if (resp.optBoolean("ok")) {
                                db.pendingCheckinDao().delete(item)
                                lastError = null
                                return FlushResult.SENT
                            }
                        } catch (e2: Exception) {
                            lastError = e2.message
                        }
                    }
                    lastError = e.message
                    bump(db, item)
                    return FlushResult.RETRY
                }
                else -> {
                    lastError = e.message
                    bump(db, item)
                    return FlushResult.RETRY
                }
            }
        } catch (e: Exception) {
            lastError = e.message
            bump(db, item)
            return FlushResult.RETRY
        }
    }

    private suspend fun bump(db: AppDatabase, item: PendingCheckin) {
        db.pendingCheckinDao().update(item.copy(attempts = item.attempts + 1, lastError = lastError))
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
