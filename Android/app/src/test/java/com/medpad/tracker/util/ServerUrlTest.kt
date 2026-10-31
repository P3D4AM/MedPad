package com.medpad.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mission 12 / item 4 — ServerUrl.normalize contract.
 *
 * Every rule is a user-visible promise made by the login screen and the
 * change-server sheet:
 *   - a bare host/IP gets the default http:// prefix (LAN-first, P09)
 *   - an explicit https:// survives untouched
 *   - a doubled scheme prefix collapses (http://http://host)
 *   - pasted bidi/zero-width characters disappear
 *   - unsupported schemes (ftp://) and empty hosts are honest failures
 *   - the scheme is case-normalized; trailing slashes are dropped
 */
class ServerUrlTest {

    // ── bare host / IP gets the default scheme ──────────────────────
    @Test fun bareIpWithPort() {
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize("192.168.1.10:3000"))
    }

    @Test fun bareDomain() {
        assertEquals("http://panel.example.com", ServerUrl.normalize("panel.example.com"))
    }

    @Test fun bareHostWithPortAndPath() {
        assertEquals("http://medpad-pc:3000/panel", ServerUrl.normalize("medpad-pc:3000/panel"))
    }

    @Test fun bareIpv6Literal() {
        assertEquals("http://[fd00::1]:3000", ServerUrl.normalize("[fd00::1]:3000"))
    }

    // ── explicit schemes survive ─────────────────────────────────────
    @Test fun httpKept() {
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize("http://192.168.1.10:3000"))
    }

    @Test fun httpsKept() {
        assertEquals("https://panel.example.com", ServerUrl.normalize("https://panel.example.com"))
    }

    @Test fun httpsWithPortAndPathKept() {
        assertEquals("https://host.example.com:8443/medpad", ServerUrl.normalize("https://host.example.com:8443/medpad"))
    }

    // ── doubled scheme prefixes collapse; the FIRST scheme wins ─────
    @Test fun doubledHttp() {
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize("http://http://192.168.1.10:3000"))
    }

    @Test fun doubledHttps() {
        assertEquals("https://panel.example.com", ServerUrl.normalize("https://https://panel.example.com"))
    }

    @Test fun mixedSchemeFirstWins() {
        // the user's FIRST typed scheme (https) is the deliberate one
        assertEquals("https://panel.example.com", ServerUrl.normalize("https://http://panel.example.com"))
    }

    // ── case + trailing slash + bidi junk ────────────────────────────
    @Test fun uppercaseSchemeLowercased() {
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize("HTTP://192.168.1.10:3000"))
    }

    @Test fun trailingSlashesDropped() {
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize("http://192.168.1.10:3000/"))
    }

    @Test fun pastedBidiAndZeroWidthStripped() {
        // RLM + ZWNJ around a pasted address (Persian chat apps do this)
        val input = "\u200fhttp://192.168.1.10:3000\u200e\u200b"
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize(input))
    }

    @Test fun whitespaceTrimmed() {
        assertEquals("http://192.168.1.10:3000", ServerUrl.normalize("   http://192.168.1.10:3000   "))
    }

    // ── honest failures ──────────────────────────────────────────────
    @Test fun emptyIsNull() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
    }

    @Test fun schemeOnlyIsNull() {
        assertNull(ServerUrl.normalize("http://"))
        assertNull(ServerUrl.normalize("https://"))
    }

    @Test fun unsupportedSchemeIsNull() {
        assertNull(ServerUrl.normalize("ftp://medpad-pc:3000"))
        assertNull(ServerUrl.normalize("file:///srv/panel"))
    }

    @Test fun portOnlyIsNull() {
        // ":3000" → host is empty → honest failure
        assertNull(ServerUrl.normalize(":3000"))
    }

    // ── hasUnsupportedScheme (error-message picker) ──────────────────
    @Test fun unsupportedSchemeDetector() {
        assertTrue(ServerUrl.hasUnsupportedScheme("ftp://host"))
        assertTrue(ServerUrl.hasUnsupportedScheme("file:///x"))
        assertFalse(ServerUrl.hasUnsupportedScheme("http://host"))
        assertFalse(ServerUrl.hasUnsupportedScheme("https://host"))
        assertFalse(ServerUrl.hasUnsupportedScheme("192.168.1.10:3000"))
        assertFalse(ServerUrl.hasUnsupportedScheme("host.example.com"))
    }

    // ── the saved-address round trip (item 3 + 4 together) ───────────
    @Test fun savedAddressRoundTrip() {
        // what the login screen saves must normalize to itself on the next
        // visit — the collapsed field never turns into a different URL
        val saved = ServerUrl.normalize("192.168.1.10:3000")!!
        assertEquals(saved, ServerUrl.normalize(saved))
        assertEquals("http://192.168.1.10:3000", saved)
    }
}
