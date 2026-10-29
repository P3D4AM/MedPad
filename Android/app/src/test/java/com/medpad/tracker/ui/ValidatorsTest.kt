package com.medpad.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * phase 2 / dev.4 (§5+§6+§2) — the shared client-side validators.
 *
 * Every rule here MIRRORS a server-side rule, so the tests double as the
 * contract between the app UI and the panel routes:
 *
 *  - passwordChange  ↔  POST /api/app/change-password
 *     (all fields present · new === confirm · new.length >= 8 ·
 *      new !== current — the same ORDER as the server route, so both
 *      sides show the same first complaint)
 *  - reportNoteTooLong  ↔  POST /api/app/report-edit
 *     (trimmed length cap 5000 — the history-table protection; a blank
 *      text is NOT an error, it clears the note server-side)
 *  - isHttpServerUrl  ↔  the login screen's address rule (§5 reuses it)
 */
class ValidatorsTest {

    // ── §6 password change ───────────────────────────────────────────

    @Test
    fun `password change - valid triple passes`() {
        assertNull(Validators.passwordChange("oldpass1", "newpass12", "newpass12"))
    }

    @Test
    fun `password change - exactly 8 chars is long enough (boundary)`() {
        assertNull(Validators.passwordChange("oldpass1", "12345678", "12345678"))
    }

    @Test
    fun `password change - any empty field is rejected first`() {
        assertEquals(Validators.PwError.EMPTY, Validators.passwordChange("", "newpass12", "newpass12"))
        assertEquals(Validators.PwError.EMPTY, Validators.passwordChange("oldpass1", "", "newpass12"))
        assertEquals(Validators.PwError.EMPTY, Validators.passwordChange("oldpass1", "newpass12", ""))
        assertEquals(Validators.PwError.EMPTY, Validators.passwordChange("", "", ""))
    }

    @Test
    fun `password change - confirm mismatch is rejected`() {
        assertEquals(Validators.PwError.MISMATCH, Validators.passwordChange("oldpass1", "newpass12", "different"))
    }

    @Test
    fun `password change - 7 chars is too short (boundary)`() {
        assertEquals(Validators.PwError.TOO_SHORT, Validators.passwordChange("oldpass1", "1234567", "1234567"))
    }

    @Test
    fun `password change - new equal to current is rejected`() {
        assertEquals(Validators.PwError.SAME_AS_CURRENT, Validators.passwordChange("samepass1", "samepass1", "samepass1"))
    }

    @Test
    fun `password change - order of checks matches the server route`() {
        // empty beats mismatch beats short beats same — the server route
        // checks in exactly this order, so the user sees the same first
        // complaint on both sides
        assertEquals(Validators.PwError.EMPTY, Validators.passwordChange("", "a", "b"))
        assertEquals(Validators.PwError.MISMATCH, Validators.passwordChange("cur", "short", "other"))
        assertEquals(Validators.PwError.TOO_SHORT, Validators.passwordChange("cur", "short", "short"))
        assertEquals(Validators.PwError.SAME_AS_CURRENT, Validators.passwordChange("12345678", "12345678", "12345678"))
    }

    // ── §5 server address ────────────────────────────────────────────

    @Test
    fun `server url - http and https schemes are accepted`() {
        assertTrue(Validators.isHttpServerUrl("http://192.168.1.10:3000"))
        assertTrue(Validators.isHttpServerUrl("https://panel.example.com"))
    }

    @Test
    fun `server url - missing scheme, other schemes and blanks are rejected`() {
        assertFalse(Validators.isHttpServerUrl("192.168.1.10:3000"))
        assertFalse(Validators.isHttpServerUrl("ftp://192.168.1.10:3000"))
        assertFalse(Validators.isHttpServerUrl("medpad-pc:3000"))
        assertFalse(Validators.isHttpServerUrl(""))
    }

    // ── §2 report note cap ───────────────────────────────────────────

    private fun chars(n: Int) = "x".repeat(n)

    @Test
    fun `report note - 5000 chars is the exact cap (boundary)`() {
        assertFalse(Validators.reportNoteTooLong(chars(5000)))
        assertTrue(Validators.reportNoteTooLong(chars(5001)))
    }

    @Test
    fun `report note - the cap applies to the TRIMMED text`() {
        // 4998 visible chars + leading/trailing whitespace → legal
        assertFalse(Validators.reportNoteTooLong("   " + chars(4998) + "   "))
        // 5001 visible chars stay too long even padded
        assertTrue(Validators.reportNoteTooLong(" " + chars(5001) + " "))
    }

    @Test
    fun `report note - blank text is not a length error (clears the note)`() {
        assertFalse(Validators.reportNoteTooLong(""))
        assertFalse(Validators.reportNoteTooLong("   "))
    }
}
