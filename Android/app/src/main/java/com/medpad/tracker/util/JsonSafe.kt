package com.medpad.tracker.util

import org.json.JSONObject

/**
 * Mission item 1 (Oct 31) — DISPLAY-SAFE JSON accessors.
 *
 * Root cause of the "null" text and the always-visible play button in
 * «گزارش‌های من»: `JSONObject.optString(key, "")` returns the literal
 * string "null" when the server stored JSON null — the fallback only
 * applies when the key is ABSENT. The reports API legitimately answers
 * `note: null` (no note) and `voiceUrl: null` (no recording), so both the
 * note TextView and the `hasVoice = optString(...).isNotEmpty()` check
 * rendered "null"-driven garbage.
 *
 * Rules implemented here (DISPLAY ONLY — the server data is never
 * rewritten; a stored value stays stored):
 *  - key absent or JSON null        → "" (empty display)
 *  - value "null" (trimmed, exact)  → "" — the legacy artifact of older
 *    app versions that stringified null before sending. A genuine user
 *    note consisting of exactly "null" is astronomically unlikely; when
 *    in doubt the raw text remains in the edit dialog's EditText via
 *    [rawText] (the server value is never destroyed).
 *  - anything else                  → the value as-is.
 */
object JsonSafe {

    /** Display text for a string field: JSON null / "null" → "". */
    fun text(o: JSONObject?, key: String): String {
        val raw = rawText(o, key)
        return if (raw.trim() == "null") "" else raw
    }

    /** The raw stored value (JSON null → ""), for editors that must not
     *  mask the server's truth — the report-edit dialog prefills with this
     *  so an accidental display sanitization can never destroy data. */
    fun rawText(o: JSONObject?, key: String): String {
        if (o == null || !o.has(key) || o.isNull(key)) return ""
        return o.optString(key, "")
    }

    /** True when a media URL field points at something playable: present,
     *  non-null, non-empty and not the legacy "null" artifact. Used to
     *  decide whether the play button is shown at all. */
    fun playableUrl(o: JSONObject?, key: String): String? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val v = o.optString(key, "").trim()
        return if (v.isEmpty() || v == "null") null else v
    }
}
