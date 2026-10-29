package com.medpad.tracker.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * Customer/doctor entity cached locally (synced with LWW from panel).
 * Package 8: province/city come from the server's offline Iran-cities
 * dataset — nullable and absent on old servers, so they parse tolerantly
 * (optString default null) and default to null for legacy rows.
 */
@Entity(tableName = "doctors")
data class Doctor(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = "doctor",
    val address: String? = null,
    val phone: String? = null,
    val province: String? = null,
    val city: String? = null,
    val lat: Double,
    val lng: Double,
    val radius: Double = 150.0,
    val updatedAt: Long = 0L, // epoch ms — LWW key
    val assigned: Boolean = false,
)

@Dao
interface DoctorDao {
    @Query("SELECT * FROM doctors WHERE assigned = 1 ORDER BY name")
    suspend fun assigned(): List<Doctor>

    @Query("SELECT * FROM doctors ORDER BY name")
    suspend fun all(): List<Doctor>

    @Query("SELECT * FROM doctors WHERE id = :id")
    suspend fun byId(id: String): Doctor?

    @Query("SELECT MAX(updatedAt) FROM doctors")
    suspend fun maxUpdatedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(doctors: List<Doctor>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doctor: Doctor)

    @Query("DELETE FROM doctors")
    suspend fun clear()

    /** G-1 (phase 1 / task 2): drop local rows whose id is no longer assigned. */
    @Query("DELETE FROM doctors WHERE id NOT IN (:keepIds)")
    suspend fun keepOnly(keepIds: List<String>)
}

/**
 * Offline queue entry — the heart of "data never gets lost".
 * v1.9.1: localId is a UUID used for server-side idempotency (anti double-submit).
 * G-6 (phase 1 / task 2): accuracy = GPS accuracy in meters for the queued
 * fix (null when the device reported none). lat/lng null = no location —
 * the server then records the event as explicitly location-missing (the
 * customer's coordinates are NEVER used as a substitute).
 * Package 1 (phase 2 / task 3): explicit lifecycle status so the app can
 * distinguish LOCAL (pending), IN-FLIGHT (sending) and PERMANENTLY FAILED
 * (failed). A 4xx answer from the server (customer deleted, checkout without
 * an open visit, strict-radius rejection…) marks the item 'failed' — the
 * event data is kept for the user to read/retry, but it no longer blocks the
 * rest of the queue (the old code retried such items forever and 'break'-ed
 * the whole flush — root cause of the "stuck queue" report).
 */
const val QUEUE_STATUS_PENDING = "pending"
const val QUEUE_STATUS_SENDING = "sending"
const val QUEUE_STATUS_FAILED = "failed"

@Entity(tableName = "pending_checkins")
data class PendingCheckin(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val localId: String,               // UUID — idempotency key
    val kind: String,                  // "checkin" | "checkout"
    val customerId: String? = null,    // checkin only
    val note: String,
    val voicePath: String? = null,     // local file path
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Double? = null,      // G-6: GPS accuracy (meters)
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
    val status: String = QUEUE_STATUS_PENDING, // pending | sending | failed
)

@Dao
interface PendingCheckinDao {
    @Query("SELECT * FROM pending_checkins ORDER BY createdAt")
    suspend fun all(): List<PendingCheckin>

    @Query("SELECT COUNT(*) FROM pending_checkins")
    suspend fun count(): Int

    /** Package 1: transport backlog = waiting + in-flight (excludes failed). */
    @Query("SELECT COUNT(*) FROM pending_checkins WHERE status != 'failed'")
    suspend fun pendingCount(): Int

    /** Package 1: permanently failed items (kept with their error text). */
    @Query("SELECT COUNT(*) FROM pending_checkins WHERE status = 'failed'")
    suspend fun failedCount(): Int

    @Query("SELECT * FROM pending_checkins WHERE status = 'failed' ORDER BY createdAt")
    suspend fun failed(): List<PendingCheckin>

    /** Package 1: crash recovery — anything marked 'sending' by a process
     *  that died mid-upload goes back to 'pending' before a new flush. */
    @Query("UPDATE pending_checkins SET status = 'pending' WHERE status = 'sending'")
    suspend fun resetSending(): Int

    /** Package 1: manual retry of failed items (safe — server localId
     *  idempotency makes a replay of an already-recorded event a no-op). */
    @Query("UPDATE pending_checkins SET status = 'pending' WHERE status = 'failed'")
    suspend fun requeueFailed(): Int

    @Insert
    suspend fun insert(item: PendingCheckin): Long

    @Update
    suspend fun update(item: PendingCheckin)

    @Delete
    suspend fun delete(item: PendingCheckin)

    @Query("DELETE FROM pending_checkins WHERE uid = :uid")
    suspend fun deleteById(uid: Long)

    /** phase 2 / §5 (dev.4) — drop the whole offline queue (a server
     *  switch to a DIFFERENT database makes every queued item not the new
     *  server's business; the user confirms with the exact count first). */
    @Query("DELETE FROM pending_checkins")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM pending_checkins WHERE kind = 'checkin' AND customerId = :customerId AND createdAt > :since")
    suspend fun recentCount(customerId: String, since: Long): Int
}

/**
 * Planned visit cached from the server (phase 2 / task 2).
 *
 * READ-ONLY cache: the app can never write a plan — status 'done' is set
 * exclusively by the server's check-in linkage, so the local table is a
 * mirror of the last successful sync, nothing more. A sync REPLACES the
 * whole table (plans for the requested window arrive complete from the
 * server; there is no LWW to arbitrate because there is no local edit).
 *
 * All display fields are server-computed mirrors (dayKey / timeLocal /
 * visitStartLocal) — the device never does timezone arithmetic on plan
 * times, so a device clock or timezone can never shift a plan to another
 * day. Customer data is denormalized here (NOT joined with `doctors`)
 * because plans may reference customers that are no longer assigned —
 * those arrive from the server already MASKED (name/address/phone null,
 * customerAssigned=false).
 */
@Entity(tableName = "planned_visits", indices = [Index("dayKey")])
data class PlannedVisitEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val dayKey: String,               // server-local YYYY-MM-DD — canonical grouping key
    val timeLocal: String,            // server-local HH:mm — rendered verbatim
    val scheduledAtMs: Long,          // epoch ms — ordering only
    val durationMin: Int,
    val priority: String,             // low | normal | high
    val status: String,               // scheduled | done | cancelled | needs_reschedule
    val note: String? = null,
    val visitId: String? = null,      // linked REAL visit (set by the server only)
    val visitStartLocal: String? = null,
    val visitOpen: Boolean = false,
    val overdue: Boolean = false,     // computed on the SERVER clock — every device agrees
    val customerName: String? = null, // null when the customer is no longer assigned (masked)
    val customerType: String? = null,
    val customerAddress: String? = null,
    val customerPhone: String? = null,
    val customerAssigned: Boolean = false,
)

@Dao
interface PlannedVisitDao {
    @Query("SELECT * FROM planned_visits WHERE dayKey = :dayKey ORDER BY timeLocal")
    suspend fun byDay(dayKey: String): List<PlannedVisitEntity>

    @Query("SELECT COUNT(*) FROM planned_visits")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PlannedVisitEntity>)

    @Query("DELETE FROM planned_visits")
    suspend fun clear()
}

/**
 * Location track points (kept locally, flushed best-effort).
 */
@Entity(tableName = "pending_locations")
data class PendingLocation(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val lat: Double,
    val lng: Double,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface PendingLocationDao {
    @Insert
    suspend fun insert(loc: PendingLocation): Long

    @Query("SELECT * FROM pending_locations ORDER BY createdAt DESC LIMIT 1")
    suspend fun last(): PendingLocation?

    @Query("DELETE FROM pending_locations WHERE createdAt < :before")
    suspend fun cleanup(before: Long)
}
