package com.medpad.tracker.util

/**
 * P09 (permanent protocol) — transport-security helper.
 *
 * MedPad's architecture is deliberately LAN-first: panels run on plain
 * HTTP inside a controlled local network (see network_security_config —
 * cleartext is allowed for exactly that reason), and existing installs
 * depend on it. P09 therefore does NOT remove HTTP; instead the login
 * flow warns honestly when the entered address is a PUBLIC host over
 * cleartext, where the username/password (login), the session token
 * (every API call), the customer data and the voice files travel
 * unencrypted and can be sniffed or tampered with on a hostile network
 * (public Wi-Fi, man-in-the-middle).
 *
 * "Private" here means: loopback, the IPv4 private ranges, link-local,
 * IPv6 unique-local addresses, dot-local and dot-internal mDNS names and bare
 * dot-less workgroup hostnames. Everything else (public IPs, ordinary
 * DNS names) counts as public and gets the one-time informed-consent
 * warning. HTTPS URLs never warn.
 */
object NetSecurity {

    /** True when [host] is a private/loopback/LAN address (cleartext OK). */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removeSuffix(".").removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".internal")) return true
        if (h.contains(':')) { // IPv6 literal
            val first = h.substringBefore(':')
            // fc00::/7 unique-local (fc/fd prefix) is the LAN equivalent
            return first.startsWith("fc") || first.startsWith("fd")
        }
        val parts = h.split('.')
        if (parts.size == 4) { // IPv4 dotted quad
            val octets = parts.map { it.toIntOrNull() }
            if (octets.any { it == null || it !in 0..255 }) return false
            val a = octets[0]!!
            val b = octets[1]!!
            return when {
                a == 127 -> true                    // loopback
                a == 10 -> true                     // 10/8 private
                a == 172 && b in 16..31 -> true     // 172.16/12 private
                a == 192 && b == 168 -> true        // 192.168/16 private
                a == 169 && b == 254 -> true        // link-local
                a == 0 -> true                      // "this network"
                else -> false
            }
        }
        // a bare dot-less hostname (e.g. "medpad-pc") is a LAN workgroup
        // name — public DNS names always carry a dot
        return !h.contains('.')
    }

    /** Extract the host from http://192.168.1.10:3000 — forgiving, no
     *  java.net.URL. Bracketed IPv6 literals keep their brackets
     *  ("[fd00::1]"); isPrivateHost strips them itself. */
    fun hostOf(url: String): String {
        val s = url.trim()
        val afterScheme = when {
            s.startsWith("http://", ignoreCase = true) -> s.substring(7)
            s.startsWith("https://", ignoreCase = true) -> s.substring(8)
            else -> s
        }
        val hostPort = afterScheme.substringBefore('/')
        return if (hostPort.startsWith("[")) {
            // [ipv6]:port — the address itself contains colons, so cut at
            // the closing bracket, not at the first colon
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
    }

    /**
     * True when [url] is plain http:// to a PUBLIC host → the login flow
     * shows the cleartext warning. https (or anything not starting with
     * http://) never warns; private hosts never warn (LAN installs keep
     * working exactly as before).
     */
    fun needsCleartextWarning(url: String): Boolean {
        val s = url.trim()
        if (!s.startsWith("http://", ignoreCase = true)) return false
        return !isPrivateHost(hostOf(s))
    }
}
