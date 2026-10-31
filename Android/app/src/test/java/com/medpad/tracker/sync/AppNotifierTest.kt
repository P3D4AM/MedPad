package com.medpad.tracker.sync

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission item 4 (Oct 31) — honest display results for locked-phone
 * notification delivery.
 *
 * The pre-mission bug: on API 33+ a DENIED POST_NOTIFICATIONS permission
 * makes NotificationManager.notify() a silent no-op (no exception), so
 * AppNotifier.notify returned TRUE, the poll marked the notification read
 * AND deduped it — the user never saw it: a PERMANENT silent loss. The
 * displayAllowed predicate is the fix's core: those states report
 * failure, so ServerNotifications keeps the row unread/undeduped and the
 * next poll retries it (bounded: once per round) until the permission is
 * granted.
 */
class AppNotifierTest {

    @Test
    fun `allowed when notifications enabled and channel healthy`() {
        assertTrue(AppNotifier.displayAllowed(true, NotificationManager.IMPORTANCE_HIGH))
        assertTrue(AppNotifier.displayAllowed(true, NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue(AppNotifier.displayAllowed(true, NotificationManager.IMPORTANCE_LOW))
    }

    @Test
    fun `denied permission means no display - no silent success`() {
        assertFalse(AppNotifier.displayAllowed(false, NotificationManager.IMPORTANCE_HIGH))
    }

    @Test
    fun `blocked channel means no display - no silent success`() {
        assertFalse(AppNotifier.displayAllowed(true, NotificationManager.IMPORTANCE_NONE))
        assertFalse(AppNotifier.displayAllowed(false, NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun `null channel (pre-O devices) only depends on the master switch`() {
        assertTrue(AppNotifier.displayAllowed(true, null))
        assertFalse(AppNotifier.displayAllowed(false, null))
    }
}
