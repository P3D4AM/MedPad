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
 * package 8 — REAL Room migration 9 → 10 (doctors.province / doctors.city).
 *
 * Strategy: build a genuine version-10 database with Room, then DOWNGRADE
 * its doctors table to the version-9 shape with plain SQLite (drop the two
 * new columns, set user_version back to 9 — the exact state a real install
 * would have before updating), then reopen with Room and the REAL
 * migrations array. If MIGRATION_9_10 doesn't restore the schema exactly
 * the way Room expects, opening fails with a schema-validation error; the
 * seeded row must survive byte-for-byte (offline installs keep their data).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration9to10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        File(app.getDatabasePath("test-mig-9-10.db").absolutePath).delete()
        File(app.getDatabasePath("test-mig-9-10.db").absolutePath + "-wal").delete()
        File(app.getDatabasePath("test-mig-9-10.db").absolutePath + "-shm").delete()
    }

    private fun dbFile(): File = app.getDatabasePath("test-mig-9-10.db")

    private fun openRoom(): AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, "test-mig-9-10.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .allowMainThreadQueries()
            .build()

    @Test
    fun `migration 9 to 10 preserves existing rows and adds nullable city columns`() = runBlocking {
        // 1. create a fresh v10 database and seed a REAL row
        var room = openRoom()
        val seeded = Doctor(
            id = "doc-mig-1", name = "دکتر مهاجرت", type = "doctor",
            address = "خیابان قدیمی ۹", phone = "0211234567",
            province = "تهران", city = "تهران",
            lat = 35.70, lng = 51.41, radius = 180.0,
            updatedAt = 1770000000000L, assigned = true,
        )
        room.doctorDao().upsert(seeded)
        room.close()
        assertTrue(dbFile().exists())

        // 2. downgrade to the v9 shape with plain SQLite (what a pre-package-8
        //    install physically has: doctors WITHOUT province/city — and since
        //    the schema moved to v11 in phase 3, pending_locations WITHOUT
        //    localId/accuracy too, otherwise the 10→11 step of the reopen
        //    would hit "duplicate column name" on a REAL v9 fixture)
        val ddl = SQLiteDatabase.openOrCreateDatabase(dbFile(), null).use { raw ->
            val sql = raw.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='doctors'", null)
                .use { c -> c.moveToFirst(); c.getString(0) }
            val plSql = raw.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='pending_locations'", null)
                .use { c -> c.moveToFirst(); c.getString(0) }
            raw.beginTransaction()
            try {
                raw.execSQL("DROP TABLE IF EXISTS doctors_v9")
                val v9Ddl = sql.replace("`province` TEXT, `city` TEXT, ", "")
                // Customer 360 — the v14 columns never existed on a v9 install
                    .replace(", `lastName` TEXT", "")
                    .replace(", `title` TEXT", "")
                    .replace(", `specialty` TEXT", "")
                    .replace(", `managerName` TEXT", "")
                    .replace(", `phone2` TEXT", "")
                    .replace(", `district` TEXT", "")
                    .replace(", `postalCode` TEXT", "")
                    .replace(", `notes` TEXT", "")
                    .replace(", `active` INTEGER NOT NULL", "")
                    .replace(", `pendingLocalId` TEXT", "")
                    // Holoo integration (phase 1) — the v15 columns neither
                    .replace(", `erpCode` TEXT", "")
                    .replace(", `erpMandeh` REAL", "")
                    .replace(", `erpCredit` REAL", "")
                assertTrue("downgrade DDL must differ (columns removed)", v9Ddl != sql)
                raw.execSQL(v9Ddl.replace("CREATE TABLE `doctors`", "CREATE TABLE `doctors_v9`"))
                raw.execSQL(
                    "INSERT INTO doctors_v9 (id,name,type,address,phone,lat,lng,radius,updatedAt,assigned) " +
                        "SELECT id,name,type,address,phone,lat,lng,radius,updatedAt,assigned FROM doctors",
                )
                raw.execSQL("DROP TABLE doctors")
                raw.execSQL("ALTER TABLE doctors_v9 RENAME TO doctors")

                // v9/v10 pending_locations: uid, lat, lng, createdAt only
                raw.execSQL("DROP TABLE IF EXISTS pl_v9")
                val plV9 = plSql
                    .replace(", `localId` TEXT NOT NULL DEFAULT '', ", ", ")
                    .replace(", `localId` TEXT NOT NULL, ", ", ")
                    .replace(", `accuracy` REAL", "")
                raw.execSQL(plV9.replace("CREATE TABLE `pending_locations`", "CREATE TABLE `pl_v9`"))
                raw.execSQL(
                    "INSERT INTO pl_v9 (uid,lat,lng,createdAt) SELECT uid,lat,lng,createdAt FROM pending_locations",
                )
                raw.execSQL("DROP TABLE pending_locations")
                raw.execSQL("ALTER TABLE pl_v9 RENAME TO pending_locations")

                
            // mission 12 / item 9 — pending_checkins now carries voicePaths
            // (v13); strip it so the 12→13 step of the reopen runs for real
            // (otherwise it dies on "duplicate column name")
            val pcSql = raw.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='pending_checkins'", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            val pcV12 = pcSql.replace(", `voicePaths` TEXT", "")
            assertTrue("pc downgrade DDL must differ", pcV12 != pcSql)
            raw.execSQL("DROP TABLE IF EXISTS pc_v12")
            raw.execSQL(pcV12.replace("CREATE TABLE `pending_checkins`", "CREATE TABLE pc_v12"))
            raw.execSQL(
                "INSERT INTO pc_v12 (uid,localId,kind,customerId,note,voicePath,lat,lng,accuracy,createdAt,attempts,lastError,status) " +
                    "SELECT uid,localId,kind,customerId,note,voicePath,lat,lng,accuracy,createdAt,attempts,lastError,status FROM pending_checkins",
            )
            raw.execSQL("DROP TABLE pending_checkins")
            raw.execSQL("ALTER TABLE pc_v12 RENAME TO pending_checkins")
            // NOTE: the localId index is NOT recreated — the entity declares
            // no index, so Room's expected schema has none and validation
            // rejects a stray one (MIGRATION_4_5 only creates it on true
            // v4→v5 upgrades, which these fixtures never simulate)

MigrationTestFixtures.dropCustomer360Tables(raw)
raw.execSQL("PRAGMA user_version = 9")
                raw.setTransactionSuccessful()
            } finally {
                raw.endTransaction()
            }
            raw.rawQuery("SELECT COUNT(*) FROM doctors", null).use { c -> c.moveToFirst(); c.getInt(0) }
        }
        assertEquals(1, ddl)

        // 3. reopen with Room → the REAL MIGRATION_9_10 must run (v9 → v10)
        room = openRoom()
        val migrated = room.doctorDao().byId("doc-mig-1")
        assertTrue("row must survive the migration", migrated != null)
        // v9 had no city columns → after migration they must read as NULL
        assertNull(migrated!!.city)
        assertNull(migrated.province)
        // every pre-existing field preserved exactly
        assertEquals(seeded.name, migrated.name)
        assertEquals(seeded.address, migrated.address)
        assertEquals(seeded.phone, migrated.phone)
        assertEquals(seeded.lat, migrated.lat, 0.0)
        assertEquals(seeded.lng, migrated.lng, 0.0)
        assertEquals(seeded.radius, migrated.radius, 0.0)
        assertEquals(seeded.updatedAt, migrated.updatedAt)
        assertTrue(migrated.assigned)

        // 4. the new columns are live: write + read a (city, province) pair
        room.doctorDao().upsert(
            migrated.copy(city = "شیراز", province = "فارس", updatedAt = seeded.updatedAt + 1),
        )
        val updated = room.doctorDao().byId("doc-mig-1")
        assertEquals("شیراز", updated!!.city)
        assertEquals("فارس", updated.province)
        room.close()
    }
}
