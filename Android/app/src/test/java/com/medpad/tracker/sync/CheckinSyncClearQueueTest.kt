package com.medpad.tracker.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * phase 2 / §5 (dev.4) — per-server queue separation.
 *
 * The change-server sheet's forced-relogin path (new address = a DIFFERENT
 * database) drops the ENTIRE offline queue so that the old server's data is
 * never replayed against the new one. The drop must be complete:
 *  - every queued row disappears (pending AND failed — a rejected item is
 *    still the old server's data),
 *  - the local voice file of each item is deleted too (no orphan audio in
 *    the app's private storage),
 *  - the returned count is the honest number of dropped items.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckinSyncClearQueueTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @Before
    fun seed() {
        db = AppDatabase.get(app)
        runBlocking { db.pendingCheckinDao().clearAll() }
    }

    @After
    fun cleanup() {
        runBlocking { db.pendingCheckinDao().clearAll() }
    }

    @Test
    fun `clearQueue drops pending items their voice files and reports the count`() = runBlocking {
        // two queued items, one with a voice file on disk
        val voice = File(app.cacheDir, "voice/q1.m4a").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        assertTrue(voice.exists())
        CheckinSync.enqueue("checkin", "c1", "note one", voice, 35.7, 51.4, 12.0)
        CheckinSync.enqueue("checkout", null, "note two", null, 35.7, 51.4, null)
        assertEquals(2, CheckinSync.pendingCount())

        val dropped = CheckinSync.clearQueue()

        assertEquals(2, dropped)
        assertEquals(0, CheckinSync.pendingCount())
        assertEquals(0, CheckinSync.failedCount())
        // the voice file is gone too — no orphan audio left behind
        assertFalse(voice.exists())
    }

    @Test
    fun `clearQueue on an empty queue is a safe no-op`() = runBlocking {
        assertEquals(0, CheckinSync.pendingCount())
        assertEquals(0, CheckinSync.clearQueue())
        assertEquals(0, CheckinSync.pendingCount())
    }
}
