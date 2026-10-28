package com.medpad.tracker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P10 — session behavior after a backup restore / device transfer.
 *
 * Security property under test: the AndroidKeyStore key that encrypts the
 * token blob NEVER takes part in backup or device-to-device transfer. So
 * whatever restore path delivers a session prefs file, the token blob can
 * never decrypt on the new device (or after a factory reset). Session must
 * react HONESTLY: drop the dead blob, report not-logged-in, and keep the
 * non-secret fields (server URL, employee identity) — no phantom
 * auto-login, no broken half-session, no plaintext fallback.
 *
 * Robolectric has no AndroidKeyStore provider, which is exactly the
 * post-restore situation; even if a future Robolectric grew one, the seeded
 * blob is a fake ciphertext that cannot decrypt either way (fail-closed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPostRestoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSessionState() = Session.clear(app)

    @After
    fun cleanup() = Session.clear(app)

    private fun sessionPrefs() =
        app.getSharedPreferences("medpad_session", Context.MODE_PRIVATE)

    /** A restored backup lands the token blob on a device whose Keystore
     *  does not hold our key (new device / factory reset): the blob is an
     *  enc:v1 ciphertext that can never decrypt. */
    @Test
    fun restoredTokenBlobWithoutKeystoreKeyIsDroppedAndRequiresRelogin() {
        Session.init(app)
        // seed prefs exactly the way a restore would deliver them
        sessionPrefs().edit()
            .putString("server_url", "https://panel.example.internal")
            .putString("token", "enc:v1:ZmFrZQ==") // fake ciphertext — undecryptable
            .putString("employee_id", "emp-7")
            .putString("employee_name", "نمونهٔ مدرپ")
            .commit()

        assertEquals("undecryptable blob must yield an empty token", "", Session.token)
        assertEquals("re-read must stay empty (blob deleted, no resurrection)", "", Session.token)
        assertFalse("dead blob must be removed from prefs", sessionPrefs().contains("token"))
        assertFalse("honest state: not logged in → fresh login required", Session.isLoggedIn)

        // non-secret session fields survive so the user keeps context
        assertEquals("https://panel.example.internal", Session.serverUrl)
        assertEquals("emp-7", Session.employeeId)
        assertEquals("نمونهٔ مدرپ", Session.employeeName)
    }

    /** Per the P10 device-transfer policy, medpad_session.xml is excluded
     *  from transfer altogether: a brand-new device starts with an empty
     *  session and must require an explicit login. */
    @Test
    fun freshDeviceWithoutSessionPrefsRequiresLogin() {
        Session.init(app)

        assertEquals("", Session.token)
        assertEquals("", Session.serverUrl)
        assertFalse("no phantom auto-login on a fresh device", Session.isLoggedIn)
    }
}
