package com.medpad.tracker.location

import android.location.Location

/**
 * G-6 (phase 1 / task 2): snapshot of the freshest REAL device location.
 *
 * The check-in/check-out dialogs use this instead of the customer's
 * coordinates (the old behavior fabricated the location from the customer
 * record). When no fresh fix exists the event is enqueued WITHOUT a
 * location and the server records it explicitly as location-missing.
 *
 * Freshness: fixes older than MAX_AGE_MS (or from the future) are ignored —
 * a stale position says nothing about where the employee is NOW.
 */
object DeviceGps {

    const val MAX_AGE_MS = 5 * 60_000L

    @Volatile
    var lastFix: Location? = null
        private set

    fun update(loc: Location) {
        lastFix = loc
    }

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Double?)

    /** Null when no trustworthy fresh fix is available. */
    fun snapshot(): Fix? {
        val loc = lastFix ?: return null
        val age = System.currentTimeMillis() - loc.time
        if (age < 0 || age > MAX_AGE_MS) return null
        val lat = loc.latitude
        val lng = loc.longitude
        if (!lat.isFinite() || !lng.isFinite()) return null
        if (Math.abs(lat) > 90.0 || Math.abs(lng) > 180.0) return null
        val acc = if (loc.hasAccuracy() && loc.accuracy > 0) loc.accuracy.toDouble() else null
        return Fix(lat, lng, acc)
    }
}
