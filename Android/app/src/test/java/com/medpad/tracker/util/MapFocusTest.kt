package com.medpad.tracker.util

import com.medpad.tracker.db.Doctor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * mission 12 / item 2 — MapFocus.allBounds contract.
 *
 * The mission replaces dev.6's "focus the majority city" with the
 * ALL-coordinates contract:
 *   - every VALID coordinate contributes ((0,0) = the app's "no coords"
 *     marker and is excluded — including it was the ocean-zoom bug);
 *   - 2+ points → min/max bounds over them ALL (multi-city included);
 *   - exactly 1 point → [single] (centre + readable zoom, not a fit);
 *   - 0 points → nothing: the map never flies to a fake place.
 */
class MapFocusTest {

    private fun doc(lat: Double, lng: Double, city: String? = null) =
        Doctor(id = "d$lat-$lng-$city", name = "n", lat = lat, lng = lng, city = city)

    @Test
    fun `zero-coordinate customers are excluded from the bounds`() {
        // the OLD pre-dev.6 bug: (0,0) rows stretched the box to ocean scale
        val r = MapFocus.allBounds(
            listOf(
                doc(35.70, 51.40),
                doc(0.0, 0.0), // "no coords" marker — must not contribute
            ),
        )
        assertEquals(1, r.validCount)
        assertNotNull(r.single)
        assertNull(r.bounds)
        assertEquals(35.70 to 51.40, r.single)
    }

    @Test
    fun `all valid coordinates form the bounds — multi city included`() {
        // mission item 2: a rep with customers in Tehran AND Shiraz sees
        // BOTH in the initial view (dev.6 focused only the majority city)
        val r = MapFocus.allBounds(
            listOf(
                doc(35.70, 51.40, "تهران"),
                doc(35.72, 51.41, "تهران"),
                doc(29.60, 52.53, "شیراز"),
            ),
        )
        assertEquals(3, r.validCount)
        val b = r.bounds!!
        assertEquals(29.60, b.minLat, 1e-9)
        assertEquals(35.72, b.maxLat, 1e-9)
        assertEquals(51.40, b.minLng, 1e-9)
        assertEquals(52.53, b.maxLng, 1e-9)
        assertNull(r.single)
    }

    @Test
    fun `a single valid point is a centre not a fit`() {
        val r = MapFocus.allBounds(listOf(doc(35.70, 51.40), doc(0.0, 0.0)))
        assertEquals(35.70 to 51.40, r.single)
        assertNull(r.bounds)
    }

    @Test
    fun `no valid coordinates means no movement`() {
        val r = MapFocus.allBounds(listOf(doc(0.0, 0.0), doc(0.0, 0.0)))
        assertEquals(0, r.validCount)
        assertNull(r.bounds)
        assertNull(r.single)
    }

    @Test
    fun `empty list means no movement`() {
        val r = MapFocus.allBounds(emptyList())
        assertEquals(0, r.validCount)
        assertNull(r.bounds)
        assertNull(r.single)
    }

    @Test
    fun `hasCoords treats only the exact zero pair as missing`() {
        assertEquals(false, MapFocus.hasCoords(0.0, 0.0))
        assertEquals(true, MapFocus.hasCoords(0.0, 51.4)) // on the Greenwich meridian — real
        assertEquals(true, MapFocus.hasCoords(35.7, 51.4))
    }
}
