package com.medpad.tracker.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Mission item 10 — REAL Room migration 11 → 12 (the op_events upload
 * queue: a brand-new table, nothing existing touched).
 *
 * Strategy like Migration10to11Test: build a genuine version-12 database
 * with Room, drop the new table and set user_version back to 11 (exactly
 * what a dev.5 install physically has), reopen with the REAL migrations
 * array. Room's schema validation fails the open if MIGRATION_11_12
 * restores anything differently; a seeded check-in queue row must survive
 * the migration untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration11to12Test {

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
            raw.execSQL("DROP TABLE IF EXISTS op_events")
            raw.version = 11
        }
        return Room.databaseBuilder(app, AppDatabase::class.java, dbFile.path)
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
    }

    @Test
    fun `migration 11 to 12 creates op_events and keeps the checkin queue`() = runBlocking {
        // build a REAL v12 database, then downgrade it to the v11 shape
        val v12 = Room.databaseBuilder(app, AppDatabase::class.java, "mig1112-test.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        v12.pendingCheckinDao().insert(
            PendingCheckin(localId = "q-1", kind = "checkin", customerId = "c1", note = "پیام صف"),
        )
        dbFile = app.getDatabasePath("mig1112-test.db")
        dbFile.parentFile?.mkdirs()
        v12.close()
        dbFile.copyTo(File(dbFile.path + ".keep"), overwrite = true)

        val db = reopened()
        // the seeded queue row survived byte-for-byte
        val items = db.pendingCheckinDao().all()
        assertEquals(1, items.size)
        assertEquals("q-1", items[0].localId)
        assertEquals("پیام صف", items[0].note)

        // the new table works end-to-end: insert + read-back + delete
        db.opEventDao().insert(
            OpEventEntity(localId = "ev-1", type = "queue.enqueued", message = "x"),
        )
        db.opEventDao().insert(
            OpEventEntity(localId = "ev-2", type = "notif.shown"),
        )
        assertEquals(2, db.opEventDao().count())
        val oldest = db.opEventDao().oldest(10)
        assertEquals("ev-1", oldest[0].localId) // oldest-first upload order
        db.opEventDao().deleteByUids(oldest.map { it.uid })
        assertEquals(0, db.opEventDao().count())
        db.close()
        File(dbFile.path + ".keep").delete()
        Unit // JUnit requires a void test method
    }

    @Test
    fun `fresh v11 install migrates cleanly`() = runBlocking {
        // v12 build → wipe the table + version, reopen: schema validation is
        // the assertion (Room throws on any mismatch)
        val v12 = Room.databaseBuilder(app, AppDatabase::class.java, "mig1112-fresh.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        v12.opEventDao().count()
        dbFile = app.getDatabasePath("mig1112-fresh.db")
        dbFile.parentFile?.mkdirs()
        v12.close()

        val db = reopened()
        assertTrue(db.opEventDao().count() == 0)
        db.close()
        Unit // JUnit requires a void test method
    }
}
