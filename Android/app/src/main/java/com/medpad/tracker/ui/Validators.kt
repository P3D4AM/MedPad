package com.medpad.tracker.ui

/**
 * phase 2 / §5+§6+§2 (dev.4) — shared client-side input validators.
 *
 * Pure logic (no Context, no Android framework) so every rule is directly
 * unit-testable on the JVM. The rules MIRROR the server-side validation of
 * the matching routes — the app checks FIRST for honest instant feedback,
 * the server checks AGAIN and remains the single source of truth: a
 * bypassed/modified client never gets anything invalid accepted.
 *
 * Mirrored routes:
 *  - passwordChange  ↔  POST /api/app/change-password
 *  - reportNote*     ↔  POST /api/app/report-edit
 *  - isHttpServerUrl ↔  the login screen's address rule (§5 reuses it)
 */
object Validators {

    // ── §6 password change (mirrors /api/app/change-password) ───────

    /** Why the triple (current, new, confirm) is NOT a valid change.
     *  Order of checks matches the server route so the user sees the same
     *  first complaint on both sides. */
    enum class PwError { EMPTY, MISMATCH, TOO_SHORT, SAME_AS_CURRENT }

    fun passwordChange(current: String, newPassword: String, confirm: String): PwError? {
        if (current.isEmpty() || newPassword.isEmpty() || confirm.isEmpty()) return PwError.EMPTY
        if (newPassword != confirm) return PwError.MISMATCH
        if (newPassword.length < MIN_PASSWORD_LEN) return PwError.TOO_SHORT
        if (newPassword == current) return PwError.SAME_AS_CURRENT
        return null
    }

    const val MIN_PASSWORD_LEN = 8

    // ── §5 server address (same rule as the login screen) ───────────

    /** A server address must carry an explicit http:// or https:// scheme
     *  — exactly what the login screen enforces before attempting a
     *  connection. Anything else (ftp://, medpad-pc:3000, …) is rejected
     *  up front with an honest message instead of a cryptic network error. */
    fun isHttpServerUrl(s: String): Boolean =
        s.startsWith("http://") || s.startsWith("https://")

    // ── §2 report edit (mirrors /api/app/report-edit) ────────────────

    /** The server's hard cap for a report text (protects the append-only
     *  history table). The app enforces the same cap while typing. */
    const val REPORT_NOTE_MAX = 5000

    /** True when the (trimmed) text exceeds the cap. An empty/blank text is
     *  NOT an error — the server treats it as "clear the note" and records
     *  the previous text in the version history. */
    fun reportNoteTooLong(note: String): Boolean = note.trim().length > REPORT_NOTE_MAX
}
