package com.medpad.tracker.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holoo integration (phase 1) — the ERP mirror parse contract for
 * /api/app/customers rows (CheckinSync.parseErpMirror):
 *
 *  * a LINKED customer carries erpCode on the row + an `erp` block with
 *    mandeh/credit → all three parsed;
 *  * an UNLINKED customer (or an OLD server before phase 1) has neither →
 *    all three null (never 0.0 — null means «unknown», the UI's
 *    «not linked» state);
 *  * JSON nulls and non-numeric junk inside the erp block degrade to null
 *    instead of NaN/0;
 *  * erpCode follows the same `"null"`-string guard the rest of the
 *    sync uses for optional strings.
 *
 * Robolectric runner: org.json is an android.jar stub in plain JVM unit
 *  tests — the SAME reason every other payload test here uses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ErpMirrorParseTest {

    @Test
    fun `linked customer parses code mandeh credit`() {
        val o = JSONObject(
            """
            {"id":"c1","name":"داروخانهٔ آرام","erpCode":"bBALNA12dg0=",
             "erp":{"erpCode":"bBALNA12dg0=","mandeh":1250000.5,"credit":500000,"syncedAt":"2026-11-10T09:00:00.000Z"}}
            """.trimIndent(),
        )
        val (code, mandeh, credit) = CheckinSync.parseErpMirror(o)
        assertEquals("bBALNA12dg0=", code)
        assertEquals(1250000.5, mandeh!!, 0.0)
        assertEquals(500000.0, credit!!, 0.0)
    }

    @Test
    fun `unlinked customer parses all nulls`() {
        val o = JSONObject("""{"id":"c2","name":"کلینیک مهر"}""".trimIndent())
        val (code, mandeh, credit) = CheckinSync.parseErpMirror(o)
        assertNull(code)
        assertNull(mandeh)
        assertNull(credit)
    }

    @Test
    fun `old server without erp block but with legacy fields stays null`() {
        // a pre-phase-1 server never sends erpCode either
        val o = JSONObject("""{"id":"c3","name":"دکتر احمدی","updatedAt":"2026-01-01T00:00:00.000Z"}""".trimIndent())
        val (code, mandeh, credit) = CheckinSync.parseErpMirror(o)
        assertNull(code)
        assertNull(mandeh)
        assertNull(credit)
    }

    @Test
    fun `json null and junk inside erp block degrade to null not nan`() {
        val o = JSONObject(
            """
            {"id":"c4","name":"x","erpCode":"bBAPNA12dg0=",
             "erp":{"mandeh":null,"credit":"not-a-number"}}
            """.trimIndent(),
        )
        val (code, mandeh, credit) = CheckinSync.parseErpMirror(o)
        assertEquals("bBAPNA12dg0=", code)
        assertNull("JSON null mandeh → null", mandeh)
        assertNull("non-numeric credit → null (never NaN)", credit)
    }

    @Test
    fun `json-null erpCode string guard`() {
        val o = JSONObject("""{"id":"c5","name":"x","erpCode":null}""".trimIndent())
        val (code, _, _) = CheckinSync.parseErpMirror(o)
        assertNull("JSONObject.NULL erpCode → null (the \"null\" string guard)", code)
    }

    @Test
    fun `zero mandeh is preserved as zero not null`() {
        val o = JSONObject(
            """{"id":"c6","name":"x","erpCode":"e6","erp":{"mandeh":0,"credit":0}}""".trimIndent(),
        )
        val (_, mandeh, credit) = CheckinSync.parseErpMirror(o)
        assertEquals(0.0, mandeh!!, 0.0)
        assertEquals(0.0, credit!!, 0.0)
    }
}
