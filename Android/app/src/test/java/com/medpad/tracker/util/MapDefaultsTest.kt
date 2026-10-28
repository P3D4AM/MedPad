package com.medpad.tracker.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * package 9 — map defaults & bounds (B7 fix) + default-city config parse
 * with the OFFLINE SharedPreferences cache round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapDefaultsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `zoom bounds are logical and ordered`() {
        // B7: zoom used to be unbounded; now 5..19 (MAPNIK practical range)
        assertTrue(MapDefaults.MIN_ZOOM < MapDefaults.INITIAL_ZOOM)
        assertTrue(MapDefaults.INITIAL_ZOOM < MapDefaults.MAX_ZOOM)
        assertTrue(MapDefaults.MIN_ZOOM >= 1.0)
        assertTrue(MapDefaults.MAX_ZOOM <= 20.0) // MAPNIK ceiling
        assertTrue(MapDefaults.FOLLOW_ZOOM <= MapDefaults.MAX_ZOOM)
    }

    @Test
    fun `parseConfig reads the resolved default city`() {
        val cfg = JSONObject()
            .put("ok", true)
            .put("panelVersion", "1.9.13-dev.2")
            .put("defaultCity", JSONObject().put("city", "شیراز").put("province", "فارس").put("lat", 29.5918).put("lng", 52.5837))
        val c = MapDefaults.parseConfig(cfg)
        assertNotNull(c)
        assertEquals("شیراز", c!!.city)
        assertEquals("فارس", c.province)
        assertEquals(29.5918, c.lat, 1e-9)
        assertEquals(52.5837, c.lng, 1e-9)
    }

    @Test
    fun `parseConfig handles absent corrupt and invalid values`() {
        assertNull(MapDefaults.parseConfig(null))
        assertNull(MapDefaults.parseConfig(JSONObject().put("ok", true))) // no defaultCity
        assertNull(MapDefaults.parseConfig(JSONObject().put("defaultCity", JSONObject.NULL)))
        // corrupt / out-of-range coordinates are rejected — no fake centering
        assertNull(MapDefaults.parseConfig(JSONObject().put("defaultCity", JSONObject().put("city", "x").put("province", "y").put("lat", 999.0).put("lng", 52.0))))
        assertNull(MapDefaults.parseConfig(JSONObject().put("defaultCity", JSONObject().put("city", "").put("province", "فارس").put("lat", 29.0).put("lng", 52.0))))
    }

    @Test
    fun `prefs cache round-trips and clear works for offline reuse`() {
        MapDefaults.cache(app, MapDefaults.City("یزد", "یزد", 31.8974, 54.3569))
        val cached = MapDefaults.cached(app)
        assertNotNull(cached)
        assertEquals("یزد", cached!!.city)
        assertEquals(31.8974, cached.lat, 1e-9)
        // clearing persists too (admin unset -> next offline open falls back)
        MapDefaults.cache(app, null)
        assertNull(MapDefaults.cached(app))
    }

    @Test
    fun `fallback is the previous hard-coded Tehran center`() {
        assertEquals(35.72, MapDefaults.FALLBACK_LAT, 1e-9)
        assertEquals(51.4, MapDefaults.FALLBACK_LNG, 1e-9)
    }
}
