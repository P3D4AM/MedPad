package com.medpad.tracker.util

import com.medpad.tracker.db.Doctor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission item 9 — the deterministic initial map focus: the city holding
 * MOST of the rep's coordinate-bearing customers, with a stable tie-break;
 * never a fabricated coordinate.
 */
class MapFocusTest {

    private fun doc(
        id: String,
        lat: Double,
        lng: Double,
        city: String? = null,
    ) = Doctor(
        id = id, name = id, type = "doctor", lat = lat, lng = lng, city = city,
        updatedAt = 0L, assigned = true,
    )

    @Test
    fun `the city with the most customers wins`() {
        val r = MapFocus.pick(
            listOf(
                doc("m1", 35.70, 51.42, "تهران"),
                doc("m2", 35.71, 51.43, "تهران"),
                doc("m3", 35.72, 51.44, "تهران"),
                doc("k1", 36.30, 59.60, "مشهد"),
                doc("k2", 36.31, 59.61, "مشهد"),
            ),
        )
        assertEquals("تهران", r.focus?.city)
        assertEquals(3, r.focus?.lats?.size)
        assertEquals(5, r.allCoordsFallback.size)
    }

    @Test
    fun `a tie breaks on the smallest city name - stable across renders`() {
        val a = MapFocus.pick(
            listOf(doc("m1", 36.30, 59.60, "مشهد"), doc("k1", 35.70, 51.42, "تهران")),
        )
        val b = MapFocus.pick(
            listOf(doc("k1", 35.70, 51.42, "تهران"), doc("m1", 36.30, 59.60, "مشهد")),
        )
        assertEquals(a.focus?.city, b.focus?.city)
        // تهران < مشهد lexicographically (Arabic-script code points)
        assertEquals("تهران", a.focus?.city)
    }

    @Test
    fun `customers without coordinates never count`() {
        val r = MapFocus.pick(
            listOf(
                doc("t1", 35.70, 51.42, "تهران"),
                doc("zero1", 0.0, 0.0, "تهران"), // no coords — excluded everywhere
                doc("zero2", 0.0, 0.0, "مشهد"),
            ),
        )
        assertEquals("تهران", r.focus?.city)
        assertEquals(1, r.focus?.lats?.size)
        assertEquals(1, r.allCoordsFallback.size)
    }

    @Test
    fun `no city data but coords exist - fallback to all points, no city invented`() {
        val r = MapFocus.pick(
            listOf(doc("a", 35.70, 51.42), doc("b", 36.30, 59.60, " ")),
        )
        assertNull(r.focus)
        assertEquals(2, r.allCoordsFallback.size)
    }

    @Test
    fun `no coordinates at all - nothing to focus, nothing fabricated`() {
        val r = MapFocus.pick(listOf(doc("a", 0.0, 0.0, "تهران")))
        assertNull(r.focus)
        assertTrue(r.allCoordsFallback.isEmpty())
    }
}
