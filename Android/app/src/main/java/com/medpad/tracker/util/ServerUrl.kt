package com.medpad.tracker.util

/**
 * Mission 12 / item 4 (Nov 2026) — server-address normalization.
 *
 * The login screen (and the change-server sheet) used to REJECT a bare
 * `192.168.1.10:3000` outright — the rep had to know and type the scheme
 * by hand. Now the field is forgiving in exactly one direction: what the
 * user typed is UPGRADED to a valid http(s) URL, never silently reinterpreted:
 *
 *  - surrounding whitespace and invisible bidi/zero-width characters
 *    (common when an address is pasted from Persian chat apps) are removed;
 *  - a repeated scheme prefix is collapsed — `http://http://host` and
 *    `https://https://host` become single-scheme (the FIRST scheme the
 *    user typed wins — it is the deliberate one);
 *  - a bare host/IP (with optional port and path) gets the DEFAULT
 *    `http://` prefix — MedPad panels are LAN-first (see NetSecurity/P09);
 *    a public HTTPS host must be typed with its `https://` explicitly,
 *    and then it is preserved untouched;
 *  - the scheme is lower-cased (`HTTP://Host` → `http://Host`);
 *  - any OTHER scheme (`ftp://`, `file://`, …) is a hard REJECT — the
 *    honest error message says so instead of producing a network failure;
 *  - an empty host is a hard reject;
 *  - trailing slashes are dropped (Session.serverUrl does the same).
 *
 * PURE logic (no Context, no Android framework) so every rule is directly
 * unit-testable on the JVM. The normalized address is still PROBED by the
 * normal login/config flow before anything is saved — normalization never
 * turns an unreachable address into a reachable one, it only removes
 * typing friction.
 */
object ServerUrl {

    /** The default scheme prepended to a bare host (LAN-first, see P09). */
    const val DEFAULT_SCHEME = "http://"

    /**
     * Normalize a user-entered server address. Returns the normalized URL,
     * or null when no usable address can be derived (empty input, no host,
     * or an unsupported non-http(s) scheme).
     */
    fun normalize(input: String): String? {
        var s = input
            .replace("\u200b", "")  // zero-width space
            .replace("\u200c", "")  // ZWNJ (نیم‌فاصله)
            .replace("\u200e", "")  // LRM
            .replace("\u200f", "")  // RLM
            .replace("\ufeff", "")  // BOM
            .trim()
        if (s.isEmpty()) return null

        // collapse repeated scheme prefixes; the FIRST scheme seen is the
        // user's deliberate choice and wins over any stray pasted one
        var scheme: String? = null
        while (true) {
            val lower = s.lowercase()
            val found = when {
                lower.startsWith("http://") -> "http"
                lower.startsWith("https://") -> "https"
                else -> null
            } ?: break
            if (scheme == null) scheme = found
            s = s.substring(if (found == "http") 7 else 8).trimStart()
        }

        // a remaining `xxx://` prefix is an unsupported scheme — reject
        if (scheme == null && s.contains("://")) {
            val head = s.substringBefore("://")
            if (head.isNotEmpty() && head.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*$"))) {
                return null
            }
        }
        if (s.isEmpty()) return null

        val withScheme = if (scheme != null) "$scheme://$s" else DEFAULT_SCHEME + s

        // host sanity — NetSecurity.hostOf handles IPv6 brackets and ports
        val host = NetSecurity.hostOf(withScheme)
        if (host.isEmpty()) return null

        return withScheme.trimEnd('/')
    }

    /** True when [input] carries an explicit http:// or https:// scheme —
     *  used to pick the honest error message after a null normalize(). */
    fun hasUnsupportedScheme(input: String): Boolean {
        val s = input.trim()
        val idx = s.indexOf("://")
        if (idx <= 0) return false
        val head = s.substring(0, idx)
        if (!head.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*$"))) return false
        return !head.equals("http", ignoreCase = true) && !head.equals("https", ignoreCase = true)
    }
}
