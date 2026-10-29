package com.medpad.tracker.sync

import com.medpad.tracker.db.PendingLocation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * phase 3 — the upload wire format (LocationSync.buildPayload) and the
 * device health snapshot's value normalization (the parts of the upload
 * contract that must hold regardless of network state).
 *
 * Wire contract mirrored from the server route (POST /api/app/locations):
 *  - one object per row: localId (string), lat, lng, recordedAt (ISO-8601),
 *    accuracy ONLY when known — the server rejects impossible accuracy
 *    values but the app simply omits unknown ones;
 *  - top-level "status" object rides along and is upserted server-side;
 *  - "locations" is an ARRAY (server rejects non-array/empty/oversized).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationUploadPayloadTest {

    private val rows = listOf(
        PendingLocation(uid = 1, localId = "aaa-111", lat = 35.700, lng = 51.400, accuracy = 12.5, createdAt = 1_770_000_000_000),
        PendingLocation(uid = 2, localId = "bbb-222", lat = 35.710, lng = 51.410, accuracy = null, createdAt = 1_770_000_060_000),
    )

    @Test
    fun `payload shape matches the server contract`() {
        val status = JSONObject().put("locationPermission", "fine").put("serviceRunning", true)
        val payload = LocationSync.buildPayload(rows, listOf("aaa-111", "bbb-222"), status)

        val locations = payload.getJSONArray("locations")
        assertEquals(2, locations.length())

        val first = locations.getJSONObject(0)
        assertEquals("aaa-111", first.getString("localId"))
        assertEquals(35.700, first.getDouble("lat"), 0.0)
        assertEquals(51.400, first.getDouble("lng"), 0.0)
        assertEquals(12.5, first.getDouble("accuracy"), 0.0)
        assertEquals("2026-02-02T02:40:00Z", first.getString("recordedAt"))

        // status object rides along verbatim
        val st = payload.getJSONObject("status")
        assertEquals("fine", st.getString("locationPermission"))
        assertTrue(st.getBoolean("serviceRunning"))
    }

    @Test
    fun `unknown accuracy is omitted not faked`() {
        val payload = LocationSync.buildPayload(
            listOf(rows[1]), listOf("bbb-222"),
            JSONObject(),
        )
        val second = payload.getJSONArray("locations").getJSONObject(0)
        assertFalse("accuracy must be absent when unknown", second.has("accuracy"))
    }

    @Test
    fun `localIds travel in row order (idempotency key alignment)`() {
        val payload = LocationSync.buildPayload(rows, listOf("x-1", "x-2"), JSONObject())
        val locations = payload.getJSONArray("locations")
        assertEquals("x-1", locations.getJSONObject(0).getString("localId"))
        assertEquals("x-2", locations.getJSONObject(1).getString("localId"))
    }

    @Test
    fun `empty batch produces an empty array (server rejects, caller skips)`() {
        val payload = LocationSync.buildPayload(emptyList(), emptyList(), JSONObject())
        assertEquals(0, payload.getJSONArray("locations").length())
    }
}
