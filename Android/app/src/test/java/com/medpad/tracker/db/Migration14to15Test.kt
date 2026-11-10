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
 * Holoo integration (phase 1) — REAL Room migration 14 → 15.
 *
 * Same strategy as Migration13to14Test: build a genuine version-15 database
 * with Room, PHYSICALLY recreate the v14 shape (doctors without the three
 * ERP columns) and set user_version back to 14 — exactly what a v1.9.15
 * install has. Reopen with the REAL migrations array: Room's schema
 * validation fails the open if MIGRATION_14_15 restores anything
 * differently, and a seeded legacy doctor row must survive untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration14to15Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File

    @After
    fun tearDown() {
        AppDatabase.migrationsForTest() // touch — keeps the companion honest
        if (::dbFile.isInitialized) dbFile.delete()
        (AppDatabase::class.java.getDeclaredField("inst").apply { isAccessible = true })
            .let { f -> f.set(null, null) } // reset the singleton between tests
    }

    /** rebuild the physical v14 shape (v1.9.15-dev.1 install) */
    private fun downgradedToV14() {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            val sql = raw.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='doctors'", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            var v14 = sql
            listOf(
                ", `erpCode` TEXT", ", `erpMandeh` REAL", ", `erpCredit` REAL",
            ).forEach { col -> v14 = v14.replace(col, "") }
            assertTrue("downgrade DDL must differ (erp columns removed)", v14 != sql)
            raw.execSQL("DROP TABLE IF EXISTS doctors_v14")
            raw.execSQL(v14.replace("CREATE TABLE `doctors`", "CREATE TABLE doctors_v14"))
            raw.execSQL(
                "INSERT INTO doctors_v14 (id,name,type,address,phone,province,city,lat,lng,radius,updatedAt,assigned," +
                    "lastName,title,specialty,managerName,phone2,district,postalCode,notes,active,pendingLocalId) " +
                    "SELECT id,name,type,address,phone,province,city,lat,lng,radius,updatedAt,assigned," +
                    "lastName,title,specialty,managerName,phone2,district,postalCode,notes,active,pendingLocalId FROM doctors",
            )
            raw.execSQL("DROP TABLE doctors")
            raw.execSQL("ALTER TABLE doctors_v14 RENAME TO doctors")
            raw.version = 14
        }
    }

    private fun reopened(): AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, dbFile.path)
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()

    @Test
    fun `migration 14 to 15 adds erp columns and keeps legacy rows`() = runBlocking {
        // 1) build a REAL v15 database, seed, then degrade to the v14 shape
        val v15 = Room.databaseBuilder(app, AppDatabase::class.java, "mig1415-test.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        dbFile = File(app.getDatabasePath("mig1415-test.db").absolutePath)
        v15.doctorDao().upsert(
            Doctor(
                id = "c-legacy", name = "داروخانهٔ آرام", type = "pharmacy",
                address = "مشهد، بلوار وکیل‌آباد", phone = "05138123456",
                city = "مشهد", province = "خراسان رضوی",
                lat = 36.31, lng = 59.54, radius = 200.0,
                updatedAt = 1_700_000_000_000, assigned = true,
            ),
        )
        v15.close()
        downgradedToV14()

        // 2) reopen — throws if MIGRATION_14_15 restores the schema wrong
        val db = reopened()

        // 3) the legacy row survived byte-for-byte; erp columns default to NULL
        val legacy = db.doctorDao().byId("c-legacy")!!
        assertEquals("داروخانهٔ آرام", legacy.name)
        assertEquals("مشهد", legacy.city)
        assertEquals(200.0, legacy.radius, 0.0)
        assertTrue(legacy.assigned)
        assertNull("erpCode defaults to null on legacy rows", legacy.erpCode)
        assertNull("erpMandeh defaults to null on legacy rows", legacy.erpMandeh)
        assertNull("erpCredit defaults to null on legacy rows", legacy.erpCredit)
        db.close()
    }

    @Test
    fun `erp columns round-trip through the dao`() = runBlocking {
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "mig1415-roundtrip.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        dbFile = File(app.getDatabasePath("mig1415-roundtrip.db").absolutePath)
        db.doctorDao().upsert(
            Doctor(
                id = "c-erp", name = "کلینیک هلو", type = "clinic",
                lat = 36.29, lng = 59.61, assigned = true,
                erpCode = "bBALNA12dg0=", erpMandeh = 1_250_000.5, erpCredit = 500_000.0,
            ),
        )
        val row = db.doctorDao().byId("c-erp")!!
        assertEquals("bBALNA12dg0=", row.erpCode)
        assertEquals(1_250_000.5, row.erpMandeh!!, 0.0)
        assertEquals(500_000.0, row.erpCredit!!, 0.0)
        // an unlinked row keeps nulls (the UI's «not linked» state)
        db.doctorDao().upsert(
            Doctor(id = "c-plain", name = "بدون هلو", type = "doctor", lat = 36.0, lng = 59.0, assigned = true),
        )
        val plain = db.doctorDao().byId("c-plain")!!
        assertNull(plain.erpCode)
        assertNotNull(plain.name)
        db.close()
    }
}
