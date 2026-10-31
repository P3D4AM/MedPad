package com.medpad.tracker.util

import com.medpad.tracker.db.Doctor

/**
 * Mission 12 / item 2 (Nov 2026) — the initial focus of «مشتریان من».
 *
 * The dev.6 behaviour focused the city holding MOST of the rep's
 * customers — customers in any OTHER city were outside the initial view.
 * The mission replaces that with the ALL-coordinates contract:
 *
 *  - collect EVERY valid coordinate (the (0,0) pair is the app's
 *    long-standing "no coords" marker and is EXCLUDED — the old
 *    pre-dev.6 code included it, which zoomed the map out to ocean scale,
 *    the reported «پرش به آمریکا» bug);
 *  - 2+ points → fit the min/max bounds of them ALL (the caller pads);
 *  - exactly 1 point → centre on it at a readable zoom (the caller picks
 *    FOLLOW_ZOOM — a "bounds fit" of one point is meaningless);
 *  - 0 points → null: the map must NOT fly to any fake place (the caller
 *    keeps the admin's default-city centre and tells the user honestly).
 *
 * Pure + deterministic so every rule is unit-tested on the JVM.
 */
object MapFocus {

    /** A customer has coordinates only when (lat,lng) != (0,0) — the app's
     *  long-standing convention: 0/0 means "no coords", never a real place. */
    fun hasCoords(lat: Double, lng: Double): Boolean = !(lat == 0.0 && lng == 0.0)

    /** The computed initial focus. [single] is set when exactly ONE valid
     *  coordinate exists (centre + a readable zoom, not a bounds fit). */
    data class Result(
        /** bounds for 2+ points: (minLat, maxLat, minLng, maxLng) */
        val bounds: Quadruple?,
        /** the only valid point, when there is exactly one */
        val single: Pair<Double, Double>?,
        /** how many customers contributed valid coordinates */
        val validCount: Int,
    )

    data class Quadruple(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double,
    )

    fun allBounds(doctors: List<Doctor>): Result {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        var count = 0
        for (d in doctors) {
            if (!hasCoords(d.lat, d.lng)) continue
            count++
            if (d.lat < minLat) minLat = d.lat
            if (d.lat > maxLat) maxLat = d.lat
            if (d.lng < minLng) minLng = d.lng
            if (d.lng > maxLng) maxLng = d.lng
        }
        return when {
            count == 0 -> Result(null, null, 0)
            count == 1 -> Result(null, minLat to minLng, 1)
            else -> Result(Quadruple(minLat, maxLat, minLng, maxLng), null, count)
        }
    }
}
