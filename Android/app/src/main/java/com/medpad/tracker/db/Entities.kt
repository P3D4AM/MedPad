package com.medpad.tracker.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * Customer/doctor entity cached locally (synced with LWW from panel).
 */
@Entity(tableName = "doctors")
data class Doctor(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = "doctor",
    val address: String? = null,
    val phone: String? = null,
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
}

/**
 * Offline queue entry — the heart of "data never gets lost".
 * v1.9.1: localId is a UUID used for server-side idempotency (anti double-submit).
 */
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
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Dao
interface PendingCheckinDao {
    @Query("SELECT * FROM pending_checkins ORDER BY createdAt")
    suspend fun all(): List<PendingCheckin>

    @Query("SELECT COUNT(*) FROM pending_checkins")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: PendingCheckin): Long

    @Update
    suspend fun update(item: PendingCheckin)

    @Delete
    suspend fun delete(item: PendingCheckin)

    @Query("DELETE FROM pending_checkins WHERE uid = :uid")
    suspend fun deleteById(uid: Long)

    @Query("SELECT COUNT(*) FROM pending_checkins WHERE kind = 'checkin' AND customerId = :customerId AND createdAt > :since")
    suspend fun recentCount(customerId: String, since: Long): Int
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
