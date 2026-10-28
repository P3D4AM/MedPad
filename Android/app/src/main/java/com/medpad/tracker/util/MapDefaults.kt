package com.medpad.tracker.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * package 9 — map defaults & bounds, in one testable place.
 *
 * B7 (owner report): the map allowed INFINITE zoom (both directions).
 * Bounds below match MAPNIK's practical tile range — zooming past it
 * shows either nothing (too far out) or blurred upscaling (too close).
 *
 * The default city comes from the ADMIN's panel setting (settings dialog →
 * «شهر پیش‌فرض نقشهٔ اپ»), delivered via /api/app/config as
 * {city, province, lat, lng} (city-CENTER approximation — centering only,
 * NEVER a customer's or the user's position). It is cached in
 * SharedPreferences so the map still opens sensibly OFFLINE; the fallback
 * is central Tehran, the previous hard-coded behavior.
 */
object MapDefaults {
    const val MIN_ZOOM: Double = 5.0 // whole-country level
    const val MAX_ZOOM: Double = 19.0 // MAPNIK practical ceiling
    const val INITIAL_ZOOM: Double = 13.0 // city level
    const val FOLLOW_ZOOM: Double = 16.0 // after locating the user

    // previous hard-coded fallback (central Tehran)
    const val FALLBACK_LAT: Double = 35.72
    const val FALLBACK_LNG: Double = 51.4

    private const val PREFS = "map_defaults"
    private const val KEY_CITY = "default_city_json"

    data class City(val city: String, val province: String, val lat: Double, val lng: Double)

    /** Parse /api/app/config's defaultCity — null when absent/corrupt. */
    fun parseConfig(configJson: JSONObject?): City? {
        if (configJson == null) return null
        val dc = configJson.optJSONObject("defaultCity") ?: return null
        val city = dc.optString("city", "")
        val province = dc.optString("province", "")
        val lat = dc.optDouble("lat", Double.NaN)
        val lng = dc.optDouble("lng", Double.NaN)
        if (city.isEmpty() || province.isEmpty() ||
            !lat.isFinite() || !lng.isFinite() ||
            lat !in -90.0..90.0 || lng !in -180.0..180.0
        ) return null
        return City(city, province, lat, lng)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Cache the last fetched default city (offline fallback). */
    fun cache(context: Context, city: City?) {
        prefs(context).edit()
            .putString(KEY_CITY, if (city == null) "" else {
                JSONObject().put("city", city.city).put("province", city.province)
                    .put("lat", city.lat).put("lng", city.lng).toString()
            })
            .apply()
    }

    /** Load the cached default city — null when never set or cleared. */
    fun cached(context: Context): City? = parseConfig(
        prefs(context).getString(KEY_CITY, "")?.takeIf { it.isNotEmpty() }?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }?.let { JSONObject().put("defaultCity", it) },
    )
}
