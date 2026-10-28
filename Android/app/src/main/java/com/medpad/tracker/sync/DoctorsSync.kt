package com.medpad.tracker.sync

import com.medpad.tracker.db.Doctor

/**
 * Doctors two-way sync with Last-Writer-Wins (LWW) semantics.
 * v1.9.0: panel edits reach the app; app edits never overwrite a NEWER panel edit.
 * Kept as a thin façade over CheckinSync.syncDoctors for clarity.
 */
object DoctorsSync {
    suspend fun sync(): Boolean = CheckinSync.syncDoctors()

    /** Push an app-side edit (returns false on conflict — caller refreshes).
     *  package 8: city/province — null = leave the stored pair untouched,
     *  "" = clear it, non-empty = validated server-side against the dataset. */
    suspend fun edit(doctor: Doctor, name: String, address: String, phone: String, city: String? = null, province: String? = null): Boolean {
        return try {
            val resp = ApiClientShim.editCustomer(
                doctor.id, name, doctor.lat, doctor.lng, address, phone,
                java.time.Instant.ofEpochMilli(doctor.updatedAt).toString(),
                city, province,
            )
            if (resp.optBoolean("ok")) {
                val db = AppDatabaseShim.get()
                db.doctorDao().upsert(
                    doctor.copy(
                        name = name,
                        address = address,
                        phone = phone,
                        // local mirror of the pair we just pushed (LWW bump
                        // matches the server row we got back)
                        city = if (city != null && province != null) city.ifBlank { null } else doctor.city,
                        province = if (city != null && province != null) province.ifBlank { null } else doctor.province,
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
    fun editCustomer(id: String, name: String, lat: Double, lng: Double, address: String, phone: String, clientUpdatedAt: String, city: String? = null, province: String? = null) =
        com.medpad.tracker.api.ApiClient.editCustomer(id, name, lat, lng, address, phone, clientUpdatedAt, city, province)
}

private object AppDatabaseShim {
    fun get() = com.medpad.tracker.db.AppDatabase.get(TrackerContext.app)
}
