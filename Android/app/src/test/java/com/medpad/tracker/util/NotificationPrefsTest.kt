package com.medpad.tracker.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * package 11 — notification settings + cross-poll/restart dedup state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPrefsTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        NotificationPrefs.init(app)
        NotificationPrefs.clearShown(app)
    }

    @Test
    fun `toggles default on and persist`() {
        assertTrue(NotificationPrefs.serverEnabled)
        assertTrue(NotificationPrefs.proximityEnabled)
        NotificationPrefs.serverEnabled = false
        NotificationPrefs.proximityEnabled = false
        // a NEW process would read the same prefs file — simulate by reading again
        assertFalse(NotificationPrefs.serverEnabled)
        assertFalse(NotificationPrefs.proximityEnabled)
        NotificationPrefs.serverEnabled = true
        NotificationPrefs.proximityEnabled = true
        assertTrue(NotificationPrefs.serverEnabled)
        assertTrue(NotificationPrefs.proximityEnabled)
    }

    @Test
    fun `shown ids dedup across polls and restarts`() {
        val id = "cmnotif123"
        assertFalse(NotificationPrefs.wasShown(app, id))
        NotificationPrefs.markShown(app, id)
        assertTrue(NotificationPrefs.wasShown(app, id))
        // same id again (next poll / after service restart) → still shown
        NotificationPrefs.markShown(app, id)
        assertTrue(NotificationPrefs.wasShown(app, id))
        assertFalse(NotificationPrefs.wasShown(app, "cmnotif-other"))
    }

    @Test
    fun `shown set is pruned to a bounded window`() {
        for (i in 0 until 260) NotificationPrefs.markShown(app, "n%03d".format(i))
        val p = app.getSharedPreferences("medpad_notif", android.content.Context.MODE_PRIVATE)
        val size = p.getStringSet("shown_notification_ids", emptySet())?.size ?: -1
        assertEquals(200, size)
        // the pruning kept the LEXICOGRAPHICALLY LARGEST (newest-style) ids
        assertTrue(NotificationPrefs.wasShown(app, "n259"))
        assertFalse(NotificationPrefs.wasShown(app, "n000"))
    }
}
