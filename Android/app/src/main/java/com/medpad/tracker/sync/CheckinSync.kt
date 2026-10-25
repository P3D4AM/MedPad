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

    suspend fun enqueue(kind: String, customerId: String?, note: String, voiceFile: File?, lat: Double?, lng: Double?): PendingCheckin {
        val db = AppDatabase.get(TrackerContext.app)
        val item = PendingCheckin(
            localId = UUID.randomUUID().toString(),
            kind = kind,
            customerId = customerId,
            note = note,
            voicePath = voiceFile?.absolutePath,
            lat = lat,
            lng = lng,
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
                val ok = flushOne(db, item)
                if (ok) sent++ else break // stop on first failure (keeps order)
            }
            sent
        } finally {
            inFlight = false
        }
    }

    suspend fun queueCount(): Int = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCheckinDao().count()
    }

    /**
     * Send one item. multipart with voice → on HTTP 400/404 from an old panel,
     * fall back to JSON text-only (voice dropped, note preserved — never lost).
     */
    private suspend fun flushOne(db: AppDatabase, item: PendingCheckin): Boolean {
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
                )
                else -> ApiClient.checkout(
                    note = item.note,
                    localId = item.localId,
                    voice = voice,
                )
            }
            if (resp.optBoolean("ok")) {
                db.pendingCheckinDao().delete(item)
                lastError = null
                return true
            }
            // ok=false with 200 — treat as failure
            lastError = resp.optString("error", "server said not ok")
            bump(db, item)
            return false
        } catch (e: HttpException) {
            when (e.code) {
                401 -> { // token invalid → stop flushing, keep data
                    lastError = "unauthorized"
                    bump(db, item)
                    return false
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
                                return true
                            }
                        } catch (e2: Exception) {
                            lastError = e2.message
                        }
                    }
                    lastError = e.message
                    bump(db, item)
                    return false
                }
                else -> {
                    lastError = e.message
                    bump(db, item)
                    return false
                }
            }
        } catch (e: Exception) {
            lastError = e.message
            bump(db, item)
            return false
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
                        assigned = assignedIds.contains(o.getString("id")) || existing?.assigned == true,
                    )
                )
            }
            if (list.isNotEmpty()) db.doctorDao().upsertAll(list)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message
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
