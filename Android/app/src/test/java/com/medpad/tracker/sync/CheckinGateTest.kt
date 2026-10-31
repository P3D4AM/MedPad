package com.medpad.tracker.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mission item 7 — the one-open-check-in gate (pure policy, see
 * CheckinGate). The SERVER enforces the same rule (partial unique index +
 * transaction); this is the device-side half that keeps the offline queue
 * from ever creating a second, contradictory open record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckinGateTest {

    private fun server(vararg visits: JSONObject): JSONObject {
        val arr = org.json.JSONArray()
        visits.forEach { arr.put(it) }
        return JSONObject().put("ok", true).put("openVisits", arr)
    }

    private fun openVisit(
        name: String? = "داروخانهٔ نگین",
        startAt: String? = "2026-10-31T08:30:00.000Z",
        planned: Boolean = false,
    ): JSONObject {
        val v = JSONObject()
            .put("id", "v1")
            .put("customerId", "c1")
        if (name != null) v.put("customerName", name) else v.put("customerName", JSONObject.NULL)
        if (startAt != null) v.put("startAt", startAt) else v.put("startAt", JSONObject.NULL)
        v.put("planned", planned)
        return v
    }

    @Test
    fun `allow when server confirms no open visit`() {
        assertEquals(
            CheckinGate.Decision.Allow,
            CheckinGate.decide(0, server()),
        )
    }

    @Test
    fun `block with the visit identity when a visit is open`() {
        val d = CheckinGate.decide(0, server(openVisit(planned = true)))
        assertTrue(d is CheckinGate.Decision.BlockOpenVisit)
        val v = (d as CheckinGate.Decision.BlockOpenVisit).visit
        assertEquals("داروخانهٔ نگین", v.customerName)
        assertEquals(true, v.planned)
        assertEquals("c1", v.customerId)
        // startAt parsed to a real epoch (13-digit ms)
        assertTrue((v.startAtMs ?: 0L) > 1_700_000_000_000L)
    }

    @Test
    fun `block unsent local check-in first - its open state is unresolved`() {
        val d = CheckinGate.decide(2, null)
        assertEquals(CheckinGate.Decision.BlockUnsent(2), d)
        // even when the server (stale) says no open visit, the queue wins
        assertEquals(CheckinGate.Decision.BlockUnsent(2), CheckinGate.decide(2, server()))
    }

    @Test
    fun `block offline when the server state is unknowable`() {
        assertEquals(CheckinGate.Decision.BlockOffline, CheckinGate.decide(0, null))
    }

    @Test
    fun `an error answer is as unknowable as offline`() {
        assertEquals(
            CheckinGate.Decision.BlockOffline,
            CheckinGate.decide(0, JSONObject().put("ok", false).put("error", "unauthorized")),
        )
    }

    @Test
    fun `old panels without the planned flag parse as plan-less`() {
        val old = JSONObject()
            .put("ok", true)
            .put("openVisits", org.json.JSONArray().put(JSONObject().put("id", "v9").put("startAt", "2026-10-31T09:00:00.000Z")))
        val d = CheckinGate.decide(0, old)
        assertTrue(d is CheckinGate.Decision.BlockOpenVisit)
        assertEquals(false, (d as CheckinGate.Decision.BlockOpenVisit).visit.planned)
        assertNull(d.visit.customerName)
    }

    @Test
    fun `elapsed label formats hours and minutes`() {
        val start = 1_780_000_000_000L
        assertEquals("5", CheckinGate.elapsedLabel(start + 5 * 60_000, start))
        assertEquals("2:5", CheckinGate.elapsedLabel(start + 125 * 60_000, start))
        assertNull(CheckinGate.elapsedLabel(start, null))
        assertNull(CheckinGate.elapsedLabel(start - 1000, start)) // clock skew
    }
}
