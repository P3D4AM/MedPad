package com.medpad.tracker.util

import com.medpad.tracker.db.Doctor

/**
 * Mission item 9 (Oct 31) — the smart INITIAL map focus for «مشتریان من».
 *
 * The old render() fitted the bounds of EVERY assigned customer — with
 * customers in more than one city the view zoomed out to country/continent
 * scale. The mission: open on the city that holds MOST of the rep's
 * customers, framed so that city's markers are all visible with padding.
 *
 * Deterministic rules (pure + unit-tested):
 *  - a customer has coordinates only when (lat,lng) != (0,0) (the app's
 *    long-standing convention — 0/0 means "no coords", never a real place);
 *  - customers are counted PER CITY (non-blank Doctor.city); the city with
 *    the MOST coordinate-bearing customers wins; ties break on the smallest
 *    city name so the choice is stable across renders;
 *  - no customer carries a city (but coords exist) → no city can be picked;
 *    the caller falls back to fitting ALL points (the honest best when
 *    there is no grouping data — nothing is fabricated);
 *  - no coordinates at all → null: the map must NOT move anywhere fake;
 *    the caller keeps the admin's default-city centre and tells the user.
 */
object MapFocus {

    data class Focus(
        /** the winning city name (never blank) */
        val city: String,
        /** that city's customers' latitudes (valid coords only) */
        val lats: List<Double>,
        /** that city's customers' longitudes (valid coords only) */
        val lngs: List<Double>,
    )

    fun hasCoords(lat: Double, lng: Double): Boolean = !(lat == 0.0 && lng == 0.0)

    /** City-level focus, or null when no city pick is possible.
     *  [allCoordsFallback] receives every valid coordinate pair when no
     *  customer has a city (the caller decides to fit-all) — kept in the
     *  return so the caller does not re-scan. */
    data class Result(
        val focus: Focus?,
        /** valid coords of ALL customers (any city) — used when focus==null */
        val allCoordsFallback: List<Pair<Double, Double>>,
    )

    fun pick(doctors: List<Doctor>): Result {
        val all = ArrayList<Pair<Double, Double>>(doctors.size)
        val byCity = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
        for (d in doctors) {
            if (!hasCoords(d.lat, d.lng)) continue
            all.add(d.lat to d.lng)
            val city = d.city?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
            byCity.getOrPut(city) { ArrayList() }.add(d.lat to d.lng)
        }
        if (byCity.isEmpty()) return Result(null, all)
        // most customers win; ties → smallest city name (stable/deterministic)
        val winner = byCity.entries
            .sortedWith(compareBy({ -it.value.size }, { it.key }))
            .first()
        return Result(
            Focus(winner.key, winner.value.map { it.first }, winner.value.map { it.second }),
            all,
        )
    }
}
