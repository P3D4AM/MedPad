package com.medpad.tracker.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mission item 1 (Oct 31) — display-safe JSON accessors.
 *
 * Mirrors the real /api/app/reports shapes:
 *  - note: null (no note) | "text" | legacy "null" artifact
 *  - voiceUrl: null (no recording) | "/api/media/voice/x.m4a"
 *
 * The acceptance criteria:
 *  - a JSON-null note must NOT render as the raw string "null";
 *  - the play button must be hidden for absent/empty/"null" voice URLs and
 *    shown for a real URL;
 *  - the edit dialog must keep the RAW server value (no display masking in
 *    an editor — stored data is never mutated client-side).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonSafeTest {

    private fun report(note: Any?, voiceUrl: Any?): JSONObject =
        JSONObject().put("id", "r1").put("note", note).put("voiceUrl", voiceUrl)

    @Test
    fun `json null note displays as empty`() {
        assertEquals("", JsonSafe.text(report(JSONObject.NULL, JSONObject.NULL), "note"))
    }

    @Test
    fun `absent key displays as empty`() {
        assertEquals("", JsonSafe.text(JSONObject().put("id", "r1"), "note"))
    }

    @Test
    fun `legacy literal null artifact displays as empty`() {
        assertEquals("", JsonSafe.text(report("null", JSONObject.NULL), "note"))
    }

    @Test
    fun `padded legacy null artifact displays as empty`() {
        assertEquals("", JsonSafe.text(report(" null ", JSONObject.NULL), "note"))
    }

    @Test
    fun `real persian note displays verbatim`() {
        assertEquals("ویزیت انجام شد", JsonSafe.text(report("ویزیت انجام شد", JSONObject.NULL), "note"))
    }

    @Test
    fun `note containing the word null inside a sentence survives`() {
        assertEquals("مقدار null نیست", JsonSafe.text(report("مقدار null نیست", JSONObject.NULL), "note"))
    }

    @Test
    fun `raw text keeps the literal null for editors`() {
        // display sanitization must never leak into the edit dialog — the
        // raw stored value is what the editor sees and resubmits
        assertEquals("null", JsonSafe.rawText(report("null", JSONObject.NULL), "note"))
        assertEquals("", JsonSafe.rawText(report(JSONObject.NULL, JSONObject.NULL), "note"))
    }

    @Test
    fun `play button hidden for null empty and artifact urls`() {
        assertNull(JsonSafe.playableUrl(report("x", JSONObject.NULL), "voiceUrl"))
        assertNull(JsonSafe.playableUrl(report("x", ""), "voiceUrl"))
        assertNull(JsonSafe.playableUrl(report("x", "null"), "voiceUrl"))
        assertNull(JsonSafe.playableUrl(JSONObject().put("id", "r1"), "voiceUrl"))
    }

    @Test
    fun `play button shown for a real media url`() {
        val url = "/api/media/voice/e1_123_abc.m4a"
        assertEquals(url, JsonSafe.playableUrl(report("x", url), "voiceUrl"))
        assertNotNull(JsonSafe.playableUrl(report("x", url), "voiceUrl"))
    }

    @Test
    fun `null object is tolerated everywhere`() {
        assertEquals("", JsonSafe.text(null, "note"))
        assertEquals("", JsonSafe.rawText(null, "note"))
        assertNull(JsonSafe.playableUrl(null, "voiceUrl"))
    }
}
