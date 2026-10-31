package com.medpad.tracker.sync

import com.medpad.tracker.db.OpEventEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mission item 10 — the device→server event wire format (OpEvents.
 * buildPayload), mirrored from POST /api/app/events:
 *  - one object per row: localId, type, status, at (ISO-8601 device time),
 *    optional errorCode/message/relatedType/relatedId/attempt — omitted
 *    when null (never JSON null);
 *  - appVersion rides on every row;
 *  - "events" is an ARRAY (the server rejects non-array/empty/oversized).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpEventPayloadTest {

    private fun row(
        type: String = "queue.enqueued",
        status: String = "success",
        errorCode: String? = null,
        attempt: Int? = null,
    ) = OpEventEntity(
        uid = 1, localId = "ev-1", type = type, status = status,
        createdAt = 1_770_000_000_000, errorCode = errorCode,
        message = "پیام آزمایشی", relatedType = "checkin", relatedId = "c-9",
        attempt = attempt,
    )

    @Test
    fun `payload shape matches the server contract`() {
        val payload = OpEvents.buildPayload(listOf(row()), "1.9.13-dev.6")
        val events = payload.getJSONArray("events")
        assertEquals(1, events.length())
        val e = events.getJSONObject(0)
        assertEquals("ev-1", e.getString("localId"))
        assertEquals("queue.enqueued", e.getString("type"))
        assertEquals("success", e.getString("status"))
        assertEquals("1.9.13-dev.6", e.getString("appVersion"))
        // at is a parseable ISO-8601 instant
        assertTrue(java.time.Instant.parse(e.getString("at")).toEpochMilli() == 1_770_000_000_000L)
    }

    @Test
    fun `null optional fields are omitted - never json null`() {
        val payload = OpEvents.buildPayload(
            listOf(row(errorCode = "403", attempt = 2).copy(message = null, relatedType = null, relatedId = null)),
            "v",
        )
        val e = payload.getJSONArray("events").getJSONObject(0)
        assertEquals("403", e.getString("errorCode"))
        assertEquals(2, e.getInt("attempt"))
        assertFalse(e.has("message"))
        assertFalse(e.has("relatedType"))
        assertFalse(e.has("relatedId"))
    }

    @Test
    fun `multiple rows keep their order`() {
        val rows = listOf(row().copy(uid = 1, localId = "a"), row().copy(uid = 2, localId = "b"))
        val payload = OpEvents.buildPayload(rows, "v")
        val events = payload.getJSONArray("events")
        assertEquals("a", events.getJSONObject(0).getString("localId"))
        assertEquals("b", events.getJSONObject(1).getString("localId"))
    }

    @Test
    fun `failure statuses survive the wire`() {
        val payload = OpEvents.buildPayload(
            listOf(row(type = "notif.display_failed", status = "failed", errorCode = "display-blocked")),
            "v",
        )
        val e = payload.getJSONArray("events").getJSONObject(0)
        assertEquals("failed", e.getString("status"))
        assertEquals("display-blocked", e.getString("errorCode"))
    }

    @Test
    fun `the payload is valid json with an events array`() {
        val payload = OpEvents.buildPayload(listOf(row()), "v")
        assertTrue(payload is JSONObject)
        assertTrue(payload.has("events"))
    }
}
