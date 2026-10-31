package com.medpad.tracker.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Mission 12 / item 9 — REAL Room migration 12 → 13 (the multi-voice
 * `voicePaths` JSON column on pending_checkins).
 *
 * Strategy like Migration11to12Test: build a genuine version-13 database
 * with Room, drop the new column and set user_version back to 12 (exactly
 * what a dev.6 install physically has), reopen with the REAL migrations
 * array. Room's schema validation fails the open if MIGRATION_12_13
 * restores anything differently; a seeded LEGACY single-voice queue row
 * must survive the migration byte-for-byte and keep reading through
 * PendingCheckin.voiceFiles().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration12to13Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File

    @After
    fun tearDown() {
        AppDatabase.migrationsForTest() // touch — keeps the companion honest
        if (::dbFile.isInitialized) dbFile.delete()
        (AppDatabase::class.java.getDeclaredField("inst").apply { isAccessible = true })
            .let { f -> f.set(null, null) } // reset the singleton between tests
    }

    private fun reopened(): AppDatabase {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            // recreate the v12 shape: no voicePaths column
            raw.execSQL("CREATE TABLE pending_checkins_v12 AS SELECT uid, localId, kind, customerId, note, voicePath, lat, lng, accuracy, createdAt, attempts, lastError, status FROM pending_checkins")
            raw.execSQL("DROP TABLE pending_checkins")
            raw.execSQL("ALTER TABLE pending_checkins_v12 RENAME TO pending_checkins")
            raw.version = 12
        }
        return Room.databaseBuilder(app, AppDatabase::class.java, dbFile.path)
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
    }

    @Test
    fun `migration 12 to 13 adds voicePaths and legacy rows still read`() = runBlocking {
        // build a REAL v13 database, then downgrade it to the v12 shape
        val v13 = Room.databaseBuilder(app, AppDatabase::class.java, "mig1213-test.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        dbFile = File(app.getDatabasePath("mig1213-test.db").absolutePath)
        v13.pendingCheckinDao().insert(
            PendingCheckin(localId = "q-legacy", kind = "checkin", customerId = "c1", note = "پیام صف"),
        )
        v13.pendingCheckinDao().insert(
            PendingCheckin(
                localId = "q-multi", kind = "checkout", note = "چک‌اوت",
                voicePaths = """["/data/voice/a.m4a","/data/voice/b.m4a"]""",
            ),
        )
        v13.close()
        // also seed a legacy single-voice row by hand (voicePath, no voicePaths)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO pending_checkins (localId, kind, note, voicePath, createdAt, attempts, status) " +
                    "VALUES ('q-old-voice', 'checkin', 'قدیمی', '/data/voice/old.m4a', 123, 0, 'pending')",
            )
        }

        val db = reopened() // throws if the migration restores the schema wrong

        val legacy = db.pendingCheckinDao().byLocalId("q-old-voice")!!
        assertNull(legacy.voicePaths)
        assertEquals(listOf(File("/data/voice/old.m4a")), legacy.voiceFiles())

        val multi = db.pendingCheckinDao().byLocalId("q-multi")!!
        assertEquals(2, multi.voiceFiles().size)
        assertEquals("/data/voice/a.m4a", multi.voiceFiles()[0].absolutePath)
        assertEquals("/data/voice/b.m4a", multi.voiceFiles()[1].absolutePath)

        val plain = db.pendingCheckinDao().byLocalId("q-legacy")!!
        assertTrue(plain.voiceFiles().isEmpty())
        db.close()
    }

    @Test
    fun `voiceFiles survives corrupt JSON by falling back to voicePath`() = runBlocking {
        // defensive: a hand-corrupted JSON array must never crash the queue
        val item = PendingCheckin(
            localId = "q-corrupt", kind = "checkin", note = "x",
            voicePath = "/data/voice/keep.m4a",
            voicePaths = "{not json",
        )
        assertEquals(listOf(File("/data/voice/keep.m4a")), item.voiceFiles())
    }
}
