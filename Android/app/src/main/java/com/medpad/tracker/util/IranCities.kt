package com.medpad.tracker.util

import android.content.Context
import org.json.JSONObject

/**
 * package 8 — OFFLINE Iran provinces & cities, bundled at
 * assets/iran-cities.json (generated from the same master dataset as the
 * panel's src/lib/iran-cities.ts — keep both in sync via the generator).
 *
 * The (province, city) PAIR is the unique key: several city names repeat
 * across provinces (نیر، نور، محمودآباد، حاجی‌آباد، نورآباد). Coordinates
 * are city-center approximations — used ONLY as map defaults/centering,
 * never stored as a customer's position without the rep confirming the
 * exact spot on the map.
 */
object IranCities {
    data class Entry(val city: String, val province: String, val lat: Double, val lng: Double)

    @Volatile private var cached: List<Entry>? = null

    /** Load & cache the dataset; returns an empty list only if the asset is
     *  unreadable (corrupt APK) — callers treat that as "picker unavailable". */
    fun load(context: Context): List<Entry> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val list = mutableListOf<Entry>()
            try {
                val raw = context.assets.open("iran-cities.json").bufferedReader().use { it.readText() }
                val root = JSONObject(raw)
                val provinces = root.optJSONArray("p") ?: org.json.JSONArray()
                for (i in 0 until provinces.length()) {
                    val prov = provinces.getJSONArray(i)
                    val province = prov.optString(0)
                    val cities = prov.optJSONArray(1) ?: continue
                    for (j in 0 until cities.length()) {
                        val c = cities.getJSONArray(j)
                        list.add(Entry(c.optString(0), province, c.optDouble(1), c.optDouble(2)))
                    }
                }
            } catch (_: Exception) {
                // honest degradation: no fake data, no crash — empty list
            }
            cached = list
            return list
        }
    }

    /** Persian-tolerant normalization (ZWNJ, Arabic ي/ك, Persian digits). */
    fun normalize(s: String): String = s
        .replace(Regex("[\\u06F0-\\u06F9]")) { ((it.value[0].code - 0x06F0 + 0x30).toChar()).toString() }
        .replace("\u200c", "").replace("\u200f", "").replace("\u200e", "").replace("\ufeff", "")
        .replace("ي", "ی").replace("ك", "ک")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Case/pair lookup used to echo the stored city back in the edit dialog. */
    fun find(context: Context, city: String?, province: String?): Entry? {
        if (city.isNullOrEmpty() || province.isNullOrEmpty()) return null
        val nc = normalize(city); val np = normalize(province)
        return load(context).firstOrNull {
            normalize(it.city) == nc && normalize(it.province) == np
        }
    }

    /** Search by city OR province name (normalized substring, both directions). */
    fun search(context: Context, query: String, limit: Int = 60): List<Entry> {
        val all = load(context)
        val nq = normalize(query)
        if (nq.isEmpty()) return all.take(limit)
        return all.filter {
            normalize(it.city).contains(nq) || normalize(it.province).contains(nq)
        }.take(limit)
    }
}
