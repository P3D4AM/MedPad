package com.medpad.tracker.sync

import android.content.Context
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.CUSTOP_STATUS_FAILED
import com.medpad.tracker.db.CUSTOP_STATUS_PENDING
import com.medpad.tracker.db.CUSTOP_STATUS_SENDING
import com.medpad.tracker.db.CustomerLinkEntity
import com.medpad.tracker.db.CustomerProfileCache
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.db.PendingCustomerOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Customer 360 (phase 1) — offline-first customer operations.
 *
 * OFFLINE QUEUE (pending_customer_ops) with the SAME durability contract
 * as the check-in queue:
 *  * `localId` (UUID) is the idempotency key. The server's create path
 *    stores it on the customer row (Customer.appLocalId) and RETURNS the
 *    existing row on replay — a crash mid-sync can never create a
 *    duplicate customer, no matter how many times the queue replays.
 *  * rows are deleted ONLY after the server confirmed them. A PERMANENT
 *    4xx rejection (duplicate name, city validation, 403 unassigned)
 *    marks the item `failed` with the server's Persian error text — the
 *    data stays readable + retryable, and it never blocks the queue.
 *  * crash recovery: anything left `sending` by a dead process goes back
 *    to `pending` on the next flush (the idempotency key makes the replay
 *    safe).
 *
 * OFFLINE-VISIBLE STATE:
 *  * CREATE enqueues the op AND inserts a temp Doctor row
 *    (id "local-<uuid>", assigned=false, pendingLocalId=<uuid>) so the
 *    customer is visible immediately with an honest «در انتظار همگام‌سازی»
 *    badge. When the server confirms, the temp row is REPLACED by the real
 *    row from the response (the customer then follows the normal unassigned
 *    lifecycle of mission-12/item-1 — the admin assigns it).
 *  * EDIT applies the change to the local row optimistically (LWW bump)
 *    and queues the op. Editing a row whose CREATE is still pending MERGES
 *    the edit into that pending create (one op, not two).
 *  * LINK queues the op; the link cache refreshes on the next profile load.
 */
object Customers360 {

    /** owner fix — localId → REAL server id of every offline CREATE the
     *  LAST flush confirmed. The create-customer flow uses this to request
     *  assignment for EXACTLY the customer it just created (the old code
     *  guessed "newest non-local row", which could pick a different
     *  customer and send the request for the wrong id). */
    val createdIds: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /** queue + insert the temp row; returns the temp Doctor (visible now). */
    suspend fun enqueueCreate(form: ApiClient.CustomerForm): Doctor = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(TrackerContext.app)
        val localId = UUID.randomUUID().toString()
        val payload = formToJson(form).put("localId", localId).toString()
        db.pendingCustomerOpDao().insert(
            PendingCustomerOp(localId = localId, op = "create", payload = payload)
        )
        val temp = Doctor(
            id = "local-$localId",
            name = form.name,
            type = form.type,
            address = form.address.ifBlank { null },
            phone = form.phone.ifBlank { null },
            province = form.province,
            city = form.city,
            lat = form.lat,
            lng = form.lng,
            radius = form.radius,
            updatedAt = System.currentTimeMillis(),
            assigned = false,
            lastName = form.lastName.ifBlank { null },
            title = form.title.ifBlank { null },
            specialty = form.specialty.ifBlank { null },
            managerName = form.managerName.ifBlank { null },
            phone2 = form.phone2.ifBlank { null },
            district = form.district.ifBlank { null },
            postalCode = form.postalCode.ifBlank { null },
            notes = form.notes.ifBlank { null },
            active = true,
            pendingLocalId = localId,
        )
        db.doctorDao().upsert(temp)
        temp
    }

    /**
     * Edit an existing (server) row offline: optimistic local update + a
     * queued op. Returns false when the row does not exist (caller toasts).
     */
    suspend fun enqueueEdit(doctorId: String, form: ApiClient.CustomerForm): Boolean =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(TrackerContext.app)
            val existing = db.doctorDao().byId(doctorId) ?: return@withContext false
            val localId = UUID.randomUUID().toString()
            // editing a row whose CREATE is still queued → merge into that op
            if (existing.pendingLocalId != null) {
                val pending = db.pendingCustomerOpDao().pending()
                    .firstOrNull { it.op == "create" && it.localId == existing.pendingLocalId }
                if (pending != null) {
                    db.pendingCustomerOpDao().update(
                        pending.copy(payload = formToJson(form).put("localId", pending.localId).toString())
                    )
                    db.doctorDao().upsert(applyForm(existing, form))
                    return@withContext true
                }
            }
            val payload = formToJson(form)
                .put("localId", localId)
                .put("customerId", doctorId)
                .put("clientUpdatedAt", java.time.Instant.ofEpochMilli(existing.updatedAt).toString())
                .toString()
            db.pendingCustomerOpDao().insert(
                PendingCustomerOp(localId = localId, op = "edit", customerId = doctorId, payload = payload)
            )
            db.doctorDao().upsert(applyForm(existing, form))
            true
        }

    /** queue a physician↔clinic link (both must be assigned — server-enforced). */
    suspend fun enqueueLink(clinicId: String, physicianId: String, note: String): Unit =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(TrackerContext.app)
            val localId = UUID.randomUUID().toString()
            val payload = JSONObject()
                .put("clinicId", clinicId)
                .put("physicianId", physicianId)
                .put("note", note)
                .put("localId", localId)
                .toString()
            db.pendingCustomerOpDao().insert(
                PendingCustomerOp(localId = localId, op = "link", payload = payload)
            )
        }

    private fun applyForm(d: Doctor, f: ApiClient.CustomerForm): Doctor = d.copy(
        name = f.name,
        type = f.type,
        address = f.address.ifBlank { null },
        phone = f.phone.ifBlank { null },
        city = f.city ?: d.city,
        province = f.province ?: d.province,
        lat = f.lat,
        lng = f.lng,
        radius = f.radius,
        // LWW bump: this edit is the newest version we know
        updatedAt = System.currentTimeMillis(),
        lastName = f.lastName.ifBlank { null },
        title = f.title.ifBlank { null },
        specialty = f.specialty.ifBlank { null },
        managerName = f.managerName.ifBlank { null },
        phone2 = f.phone2.ifBlank { null },
        district = f.district.ifBlank { null },
        postalCode = f.postalCode.ifBlank { null },
        notes = f.notes.ifBlank { null },
    )

    private fun formToJson(f: ApiClient.CustomerForm): JSONObject = JSONObject()
        .put("name", f.name)
        .put("type", f.type)
        .put("address", f.address)
        .put("phone", f.phone)
        .put("lat", f.lat)
        .put("lng", f.lng)
        .put("radius", f.radius)
        .apply {
            if (!f.city.isNullOrEmpty() && !f.province.isNullOrEmpty()) put("city", f.city).put("province", f.province)
            if (f.lastName.isNotBlank()) put("lastName", f.lastName)
            if (f.title.isNotBlank()) put("title", f.title)
            if (f.specialty.isNotBlank()) put("specialty", f.specialty)
            if (f.managerName.isNotBlank()) put("managerName", f.managerName)
            if (f.phone2.isNotBlank()) put("phone2", f.phone2)
            if (f.district.isNotBlank()) put("district", f.district)
            if (f.postalCode.isNotBlank()) put("postalCode", f.postalCode)
            if (f.notes.isNotBlank()) put("notes", f.notes)
        }

    private fun jsonToForm(o: JSONObject): ApiClient.CustomerForm = ApiClient.CustomerForm(
        name = o.optString("name"),
        type = o.optString("type", "doctor"),
        address = o.optString("address"),
        phone = o.optString("phone"),
        lat = o.optDouble("lat", 0.0),
        lng = o.optDouble("lng", 0.0),
        radius = o.optDouble("radius", 150.0),
        city = o.optString("city", null),
        province = o.optString("province", null),
        lastName = o.optString("lastName"),
        title = o.optString("title"),
        specialty = o.optString("specialty"),
        managerName = o.optString("managerName"),
        phone2 = o.optString("phone2"),
        district = o.optString("district"),
        postalCode = o.optString("postalCode"),
        notes = o.optString("notes"),
    )

    // ── queue status helpers (mirroring CheckinSync's UX) ──

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCustomerOpDao().pendingCount()
    }

    suspend fun failedItems(): List<PendingCustomerOp> = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCustomerOpDao().failed()
    }

    suspend fun retryFailed(): Int = withContext(Dispatchers.IO) {
        AppDatabase.get(TrackerContext.app).pendingCustomerOpDao().requeueFailed()
    }

    // ── flush ────────────────────────────────────────────────

    /**
     * Upload every pending op, oldest first. Returns the number of items
     * that left the queue (confirmed or permanently failed). A network
     * error leaves the item PENDING and stops the flush — data is never
     * lost, the next flush (resume / connectivity-regain) retries.
     */
    suspend fun flush(): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(TrackerContext.app)
        // crash recovery: a dead process may have left items in `sending`
        db.pendingCustomerOpDao().resetSending()
        var done = 0
        val queue = db.pendingCustomerOpDao().pending()
        for (op in queue) {
            db.pendingCustomerOpDao().update(op.copy(status = CUSTOP_STATUS_SENDING))
            try {
                val confirmed = when (op.op) {
                    "create" -> flushCreate(db, op)
                    "edit" -> flushEdit(db, op)
                    "link" -> flushLink(op)
                    else -> true // unknown op -> drop (forward-compat)
                }
                if (confirmed) {
                    db.pendingCustomerOpDao().deleteById(op.uid)
                    done++
                } else {
                    // server answered ok:false without an exception (link
                    // path) — recoverable: retry later, stop the flush
                    // (oldest-first ordering matters)
                    db.pendingCustomerOpDao().update(op.copy(status = CUSTOP_STATUS_PENDING, attempts = op.attempts + 1))
                    break
                }
            } catch (e: Exception) {
                val perm = e is ApiClient.HttpException && e.code in 400..499
                db.pendingCustomerOpDao().update(
                    op.copy(
                        status = if (perm) CUSTOP_STATUS_FAILED else CUSTOP_STATUS_PENDING,
                        attempts = op.attempts + 1,
                        lastError = e.message,
                    )
                )
                if (perm) done++ else break // network error -> retry later
            }
        }
        done
    }

    private suspend fun flushCreate(db: AppDatabase, op: PendingCustomerOp): Boolean {
        val payload = JSONObject(op.payload)
        val form = jsonToForm(payload)
        val resp = ApiClient.createCustomerFull(form, localId = op.localId)
        if (!resp.optBoolean("ok")) {
            // a rejection (e.g. 409 duplicate) is PERMANENT — the outer
            // catch marks the item failed with the server's Persian text;
            // the temp row stays visible with its «در انتظار» badge
            throw ApiClient.HttpException(409, resp.optString("error", "server said not ok"))
        }
        // success → replace the temp row with the REAL server row
        val c = resp.optJSONObject("customer")
        if (c != null) {
            val temp = db.doctorDao().byId("local-${op.localId}")
            db.doctorDao().upsert(
                Doctor(
                    id = c.optString("id"),
                    name = c.optString("name", form.name),
                    type = c.optString("type", form.type),
                    address = c.optString("address", null),
                    phone = c.optString("phone", null),
                    province = c.optString("province", null),
                    city = c.optString("city", null),
                    lat = c.optDouble("lat", form.lat),
                    lng = c.optDouble("lng", form.lng),
                    radius = c.optDouble("radius", form.radius),
                    updatedAt = try {
                        java.time.Instant.parse(c.optString("updatedAt")).toEpochMilli()
                    } catch (_: Exception) {
                        System.currentTimeMillis()
                    },
                    // mission-12/item-1 contract: the new customer is
                    // UNASSIGNED — it appears in the rep's list only after
                    // the admin assigns it (keep assigned=false)
                    assigned = false,
                    lastName = c.optString("lastName", null),
                    title = c.optString("title", null),
                    specialty = c.optString("specialty", null),
                    managerName = c.optString("managerName", null),
                    phone2 = c.optString("phone2", null),
                    district = c.optString("district", null),
                    postalCode = c.optString("postalCode", null),
                    notes = c.optString("notes", null),
                    active = c.optBoolean("active", true),
                )
            )
            if (temp != null) db.doctorDao().deleteById(temp.id)
            // owner fix — remember the localId→realId mapping so the caller
            // can act on EXACTLY this customer (assignment request)
            createdIds[op.localId] = c.optString("id")
        }
        return true
    }

    private suspend fun flushEdit(db: AppDatabase, op: PendingCustomerOp): Boolean {
        val payload = JSONObject(op.payload)
        val id = payload.optString("customerId")
        val form = jsonToForm(payload)
        val clientUpdatedAt = payload.optString("clientUpdatedAt")
        return try {
            val resp = ApiClient.editCustomerFull(
                id, form, clientUpdatedAt,
                city = if (payload.has("city")) payload.optString("city") else null,
                province = if (payload.has("province")) payload.optString("province") else null,
            )
            if (resp.optBoolean("conflict")) {
                // LWW: the panel edited after our base version — the SERVER
                // version wins. Refresh the local row from the response.
                val c = resp.optJSONObject("customer")
                if (c != null) {
                    val existing = db.doctorDao().byId(id)
                    if (existing != null) {
                        db.doctorDao().upsert(
                            existing.copy(
                                name = c.optString("name", existing.name),
                                address = c.optString("address", null),
                                phone = c.optString("phone", null),
                                city = c.optString("city", null),
                                province = c.optString("province", null),
                                updatedAt = try {
                                    java.time.Instant.parse(c.optString("updatedAt")).toEpochMilli()
                                } catch (_: Exception) {
                                    existing.updatedAt
                                },
                            )
                        )
                    }
                }
                true // resolved (server version applied) — op leaves the queue
            } else if (resp.optBoolean("ok")) {
                // mirror the server row (LWW truth)
                val c = resp.optJSONObject("customer")
                if (c != null) {
                    val existing = db.doctorDao().byId(id)
                    if (existing != null) {
                        db.doctorDao().upsert(
                            existing.copy(
                                name = c.optString("name", existing.name),
                                type = c.optString("type", existing.type),
                                address = c.optString("address", null),
                                phone = c.optString("phone", null),
                                city = c.optString("city", null),
                                province = c.optString("province", null),
                                lat = c.optDouble("lat", existing.lat),
                                lng = c.optDouble("lng", existing.lng),
                                updatedAt = try {
                                    java.time.Instant.parse(c.optString("updatedAt")).toEpochMilli()
                                } catch (_: Exception) {
                                    existing.updatedAt
                                },
                            )
                        )
                    }
                }
                true
            } else {
                throw ApiClient.HttpException(409, resp.optString("error", "server said not ok"))
            }
        } catch (e: ApiClient.HttpException) {
            throw e // let flush() classify (4xx permanent / network retry)
        }
    }

    private suspend fun flushLink(op: PendingCustomerOp): Boolean {
        val payload = JSONObject(op.payload)
        val resp = ApiClient.createCustomerLink(
            payload.optString("clinicId"),
            payload.optString("physicianId"),
            payload.optString("note"),
            localId = op.localId,
        )
        return resp.optBoolean("ok")
    }

    // ── 360 profile: online fetch + offline cache ────────────

    /**
     * Fetch the 360 profile. Online: the response is cached for offline
     * use. Offline (or server unreachable): the LAST cached response is
     * returned with `cached=true` so the UI can show the honest
     * «آخرین به‌روزرسانی» banner. Returns null when neither exists.
     */
    suspend fun loadProfile(customerId: String): Pair<JSONObject, Boolean>? =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(TrackerContext.app)
            try {
                val resp = ApiClient.customerProfile(customerId)
                if (resp.optBoolean("ok")) {
                    db.customerProfileDao().upsert(
                        CustomerProfileCache(customerId = customerId, payload = resp.toString())
                    )
                    cacheLinks(db, customerId, resp)
                    return@withContext resp to false
                }
                null
            } catch (_: Exception) {
                val cached = db.customerProfileDao().byId(customerId) ?: return@withContext null
                try {
                    JSONObject(cached.payload) to true
                } catch (_: Exception) {
                    null
                }
            }
        }

    /** mirror the profile's link rows into the read-only cache table. */
    private suspend fun cacheLinks(db: AppDatabase, customerId: String, resp: JSONObject) {
        try {
            val links = resp.optJSONObject("links") ?: return
            val out = ArrayList<CustomerLinkEntity>()
            links.optJSONArray("physicians")?.let { arr: JSONArray ->
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    val cp = l.optJSONObject("counterpart") ?: continue
                    if (!cp.optBoolean("visible", true)) continue // masked → skip
                    out.add(
                        CustomerLinkEntity(
                            id = l.optString("id"),
                            clinicId = customerId,
                            physicianId = cp.optString("id"),
                            clinicName = null,
                            physicianName = cp.optString("name"),
                            note = l.optString("note", null),
                            active = l.optBoolean("active", true),
                        )
                    )
                }
            }
            links.optJSONArray("clinics")?.let { arr: JSONArray ->
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    val cp = l.optJSONObject("counterpart") ?: continue
                    if (!cp.optBoolean("visible", true)) continue
                    out.add(
                        CustomerLinkEntity(
                            id = l.optString("id"),
                            clinicId = cp.optString("id"),
                            physicianId = customerId,
                            clinicName = cp.optString("name"),
                            physicianName = null,
                            note = l.optString("note", null),
                            active = l.optBoolean("active", true),
                        )
                    )
                }
            }
            db.customerLinkDao().clear()
            db.customerLinkDao().upsertAll(out)
        } catch (_: Exception) {
            // cache refresh is best-effort — the profile itself still renders
        }
    }

    /** wipe all Customer-360 caches + queue (logout / server switch). */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        db.customerLinkDao().clear()
        db.customerProfileDao().clear()
        db.pendingCustomerOpDao().clearAll()
    }
}
