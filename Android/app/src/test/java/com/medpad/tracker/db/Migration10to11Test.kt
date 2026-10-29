package com.medpad.tracker.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * phase 3 — REAL Room migration 10 → 11 (pending_locations.localId /
 * .accuracy — the location queue becomes an upload store).
 *
 * Same strategy as Migration9to10Test: build a genuine version-11 database
 * with Room, DOWNGRADE pending_locations to the version-10 shape with plain
 * SQLite (drop localId/accuracy, set user_version back to 10 — exactly what
 * a dev.4 install physically has), reopen with the REAL migrations array.
 * Room's schema validation fails the open if MIGRATION_10_11 restores
 * anything differently; the seeded rows must survive byte-for-byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration10to11Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        listOf("", "-wal", "-shm").forEach {
            File(app.getDatabasePath("test-mig-10-11.db").absolutePath + it).delete()
        }
    }

    private fun dbFile(): File = app.getDatabasePath("test-mig-10-11.db")

    private fun openRoom(): AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, "test-mig-10-11.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .allowMainThreadQueries()
            .build()

    @Test
    fun `migration 10 to 11 preserves rows and adds localId-accuracy`() = runBlocking {
        // 1. fresh v11 database with REAL rows (one with accuracy, one without)
        var room = openRoom()
        val dao = room.pendingLocationDao()
        dao.insert(PendingLocation(localId = "uuid-1111", lat = 35.700, lng = 51.400, accuracy = 12.5, createdAt = 1_770_000_000_000))
        dao.insert(PendingLocation(localId = "uuid-2222", lat = 35.710, lng = 51.410, accuracy = null, createdAt = 1_770_000_060_000))
        assertEquals(2, dao.count())
        room.close()

        // 2. downgrade to the v10 shape (what a dev.4 install has: no
        //    localId, no accuracy — plus legacy rows keep their coordinates)
        SQLiteDatabase.openOrCreateDatabase(dbFile(), null).use { raw ->
            val sql = raw.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='pending_locations'", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            raw.beginTransaction()
            try {
                // Room's generated CREATE TABLE carries NO default for
                // localId (defaults live only in the ALTER of migrations);
                // match both spellings defensively
                val v10 = sql
                    .replace(", `localId` TEXT NOT NULL DEFAULT '', ", ", ")
                    .replace(", `localId` TEXT NOT NULL, ", ", ")
                    .replace(", `accuracy` REAL", "")
                assertTrue("downgrade DDL must differ", v10 != sql)
                raw.execSQL("DROP TABLE IF EXISTS pl_v10")
                raw.execSQL(v10.replace("CREATE TABLE `pending_locations`", "CREATE TABLE `pl_v10`"))
                raw.execSQL("INSERT INTO pl_v10 (uid,lat,lng,createdAt) SELECT uid,lat,lng,createdAt FROM pending_locations")
                raw.execSQL("DROP TABLE pending_locations")
                raw.execSQL("ALTER TABLE pl_v10 RENAME TO pending_locations")
                raw.execSQL("PRAGMA user_version = 10")
                raw.setTransactionSuccessful()
            } finally {
                raw.endTransaction()
            }
        }

        // 3. reopen with Room → the REAL MIGRATION_10_11 must run (v10 → v11)
        room = openRoom()
        val dao2 = room.pendingLocationDao()
        assertEquals("rows survive the migration", 2, dao2.count())

        // legacy rows read back with empty localId + null accuracy
        val first = dao2.oldest(10)[0]
        assertEquals(35.700, first.lat, 0.0)
        assertEquals(51.400, first.lng, 0.0)
        assertEquals(1_770_000_000_000L, first.createdAt)
        assertEquals("", first.localId)
        assertNull(first.accuracy)

        // 4. the new columns are live: assign an idempotency id (exactly
        //    what LocationSync does with legacy rows at upload time), delete
        //    by uid after "confirmation", and the queue ops behave
        dao2.assignLocalId(first.uid, "uuid-migrated-1")
        val afterAssign = dao2.oldest(10)[0]
        assertEquals("uuid-migrated-1", afterAssign.localId)

        dao2.deleteByUids(listOf(afterAssign.uid))
        assertEquals(1, dao2.count())
        val remaining = dao2.oldest(10)[0]
        assertEquals(35.710, remaining.lat, 0.0)

        // 5. inserting NEW rows with the full shape works (the service path)
        val uid = dao2.insert(PendingLocation(localId = "uuid-3333", lat = 35.72, lng = 51.42, accuracy = 8.0))
        assertTrue(uid > 0)
        val newest = dao2.last()
        assertNotNull(newest)
        assertEquals(8.0, newest!!.accuracy!!, 0.0)
        room.close()
    }
}
