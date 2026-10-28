package com.medpad.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P09 (permanent protocol) — the cleartext-warning predicate.
 *
 * Policy under test (documented in NetSecurity):
 *  - LAN-first architecture keeps working: private IPv4 ranges, loopback,
 *    link-local, IPv6 ULA, dot-local/dot-internal names and bare workgroup
 *    names over http:// NEVER warn (existing installs are unaffected).
 *  - A PUBLIC host over plain http:// WARNS (credentials/token/voice
 *    would travel unencrypted on a possibly-hostile network).
 *  - HTTPS never warns, regardless of the host.
 */
class NetSecurityTest {

    // ── LAN addresses: never warn (existing installs keep working) ──

    @Test
    fun `private IPv4 LAN ranges over http never warn`() {
        listOf(
            "http://192.168.1.10:3000",   // the documented install pattern
            "http://192.168.0.1",
            "http://10.0.0.5:3000",
            "http://10.255.255.255",
            "http://172.16.0.1",
            "http://172.31.255.254",      // last address of 172.16/12
            "http://169.254.7.7",         // link-local
            "http://127.0.0.1:3000",      // loopback
            "http://localhost:3000",
        ).forEach { url ->
            assertFalse("LAN URL must not warn: $url", NetSecurity.needsCleartextWarning(url))
        }
    }

    @Test
    fun `mDNS and bare workgroup hostnames never warn`() {
        listOf(
            "http://medpad-pc.local:3000",
            "http://server.internal",
            "http://medpad-pc:3000",      // dot-less workgroup name
        ).forEach { url ->
            assertFalse("LAN hostname must not warn: $url", NetSecurity.needsCleartextWarning(url))
        }
    }

    @Test
    fun `IPv6 unique-local and loopback never warn`() {
        listOf(
            "http://[fd00::1]:3000",      // ULA fd — the LAN equivalent
            "http://[fc00::5]:3000",      // ULA fc
            "http://[::1]:3000",          // loopback
        ).forEach { url ->
            assertFalse("ULA IPv6 must not warn: $url", NetSecurity.needsCleartextWarning(url))
        }
    }

    // ── public cleartext: warn honestly ──

    @Test
    fun `public hosts over plain http warn`() {
        listOf(
            "http://panel.example.com",
            "http://panel.example.com:3000",
            "http://8.8.8.8:3000",        // public IP
            "http://172.32.0.1",          // just OUTSIDE 172.16/12
            "http://172.15.0.1",
            "http://192.169.1.1",         // just outside 192.168/16
            "http://11.0.0.1",            // just outside 10/8
            "http://[2001:db8::1]:3000",  // global unicast IPv6
        ).forEach { url ->
            assertTrue("public cleartext must warn: $url", NetSecurity.needsCleartextWarning(url))
        }
    }

    // ── HTTPS and non-http schemes: never a cleartext warning ──

    @Test
    fun `https never warns regardless of host`() {
        listOf(
            "https://panel.example.com",
            "https://8.8.8.8:3000",
        ).forEach { url ->
            assertFalse("https must never warn: $url", NetSecurity.needsCleartextWarning(url))
        }
    }

    @Test
    fun `non-http input never triggers the warning`() {
        listOf(
            "ftp://example.com",
            "example.com",                // no scheme — rejected by the existing validation
            "",
        ).forEach { url ->
            assertFalse("non-http must not warn: '$url'", NetSecurity.needsCleartextWarning(url))
        }
    }

    // ── host extraction ──

    @Test
    fun `hostOf extracts host from all supported URL shapes`() {
        assertEquals("192.168.1.10", NetSecurity.hostOf("http://192.168.1.10:3000"))
        assertEquals("panel.example.com", NetSecurity.hostOf("https://panel.example.com/api"))
        assertEquals("[fd00::1]", NetSecurity.hostOf("http://[fd00::1]:3000/x"))
        assertEquals("medpad-pc", NetSecurity.hostOf("http://medpad-pc:3000"))
        assertEquals("plain", NetSecurity.hostOf("plain"))
    }
}
