package com.medpad.tracker.util

/**
 * Mission item 2 (Oct 31) — the profile greeting shows ONLY the first name.
 *
 * A legacy employee row has no first/last split yet (the panel admin fills
 * it explicitly — no destructive auto-split), and an OLD panel does not
 * return the field at all. In both cases the greeting must drop the name
 * entirely («سلام») — the full name is NEVER a substitute (product rule).
 */
object Greeting {

    /** The trimmed first name, or null when the greeting must be nameless:
     *  null/blank input, or the "null" artifact of a JSON-null field that
     *  older panels serialize as a string. */
    fun firstNameFor(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty() || t == "null") return null
        return t
    }
}
