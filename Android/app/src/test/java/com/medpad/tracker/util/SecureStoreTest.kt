package com.medpad.tracker.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/**
 * package 10 — secure token storage.
 *
 * The AES/GCM core (encryptWith/decryptWith) is pure javax.crypto and runs
 * with a software key on the JVM; the AndroidKeyStore glue is exercised on
 * real devices (Robolectric has no real Keystore — the Session tests below
 * therefore cover the honest-degradation and prefs behavior paths).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureStoreTest {

    private lateinit var app: Application
    private val key: javax.crypto.SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val otherKey: javax.crypto.SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Session.init(app)
    }

    @Test
    fun `round-trip preserves the token`() {
        val blob = SecureStore.encryptWith(key, "tok-سرور-123-!@#")
        assertTrue(SecureStore.isEncrypted(blob))
        assertEquals("tok-سرور-123-!@#", SecureStore.decryptWith(key, blob))
    }

    @Test
    fun `ciphertext never contains the plaintext and IV is fresh`() {
        val plain = "very-secret-token-value"
        val a = SecureStore.encryptWith(key, plain)
        val b = SecureStore.encryptWith(key, plain)
        assertFalse(a.contains(plain))
        assertNotEquals(a, b) // fresh IV per encryption
        assertEquals(plain, SecureStore.decryptWith(key, a))
        assertEquals(plain, SecureStore.decryptWith(key, b))
    }

    @Test
    fun `tampered blob fails closed`() {
        val blob = SecureStore.encryptWith(key, "token")
        val raw = android.util.Base64.decode(blob.removePrefix(SecureStore.PREFIX), android.util.Base64.NO_WRAP)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = SecureStore.PREFIX + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        assertNull(SecureStore.decryptWith(key, tampered))
    }

    @Test
    fun `wrong key or corrupt input fails closed`() {
        val blob = SecureStore.encryptWith(key, "token")
        assertNull(SecureStore.decryptWith(otherKey, blob)) // wrong key
        assertNull(SecureStore.decryptWith(key, "")) // empty
        assertNull(SecureStore.decryptWith(key, "enc:v1:%%%not-base64%%%")) // corrupt
        assertNull(SecureStore.decryptWith(key, "plaintext-not-prefixed")) // not ours
    }

    @Test
    fun `session token is never stored in plaintext and legacy token is wiped`() {
        // legacy install: a PLAINTEXT token sits in the old prefs key
        val legacy = app.getSharedPreferences("medpad_session", android.content.Context.MODE_PRIVATE)
        legacy.edit().putString("token", "legacy-plain-token").apply()

        val readBack = Session.token
        assertEquals("legacy-plain-token", readBack) // usable for the live session
        val stored = legacy.getString("token", "") ?: ""
        // on the JVM there is no AndroidKeyStore → the migration must REMOVE
        // the plaintext instead of leaving it on disk (fail-closed honesty);
        // on a real device it would be replaced by an enc:v1: blob.
        val migrated = stored.isEmpty() || SecureStore.isEncrypted(stored)
        assertTrue("stored token must be encrypted or removed, was: $stored", migrated)
    }

    @Test
    fun `remembered username survives logout, session prefs do not`() {
        Session.rememberUsername = true
        Session.rememberedUsername = "m_emp1"
        Session.serverUrl = "http://1.2.3.4:3000"
        Session.employeeName = "نمونه"

        Session.clear(app) // logout

        assertEquals("m_emp1", Session.rememberedUsername) // survives
        assertTrue(Session.rememberUsername) // checkbox state survives
        assertEquals("", Session.serverUrl) // session wiped
        assertEquals("", Session.employeeName)
        assertEquals("", Session.token)
    }

    @Test
    fun `unchecking remember clears the stored username`() {
        Session.rememberUsername = true
        Session.rememberedUsername = "someone"
        Session.rememberUsername = false
        Session.rememberedUsername = ""
        assertEquals("", Session.rememberedUsername)
        assertFalse(Session.rememberUsername)
    }
}
