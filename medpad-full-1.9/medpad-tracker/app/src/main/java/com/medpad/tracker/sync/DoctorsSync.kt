package com.medpad.tracker.sync

import com.medpad.tracker.db.Doctor

/**
 * Doctors two-way sync with Last-Writer-Wins (LWW) semantics.
 * v1.9.0: panel edits reach the app; app edits never overwrite a NEWER panel edit.
 * Kept as a thin façade over CheckinSync.syncDoctors for clarity.
 */
object DoctorsSync {
    suspend fun sync(): Boolean = CheckinSync.syncDoctors()

    /** Push an app-side edit (returns false on conflict — caller refreshes). */
    suspend fun edit(doctor: Doctor, name: String, address: String, phone: String): Boolean {
        return try {
            val resp = ApiClientShim.editCustomer(
                doctor.id, name, doctor.lat, doctor.lng, address, phone,
                java.time.Instant.ofEpochMilli(doctor.updatedAt).toString(),
            )
            if (resp.optBoolean("ok")) {
                val db = AppDatabaseShim.get()
                db.doctorDao().upsert(
                    doctor.copy(
                        name = name,
                        address = address,
                        phone = phone,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }
}

// shims to avoid circular imports in this façade
private object ApiClientShim {
    @kotlin.jvm.Throws(Exception::class)
    fun editCustomer(id: String, name: String, lat: Double, lng: Double, address: String, phone: String, clientUpdatedAt: String) =
        com.medpad.tracker.api.ApiClient.editCustomer(id, name, lat, lng, address, phone, clientUpdatedAt)
}

private object AppDatabaseShim {
    fun get() = com.medpad.tracker.db.AppDatabase.get(TrackerContext.app)
}
