package com.medpad.tracker.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Customer 360 (phase 1) — REAL Room migration 13 → 14.
 *
 * Strategy like Migration12to13Test: build a genuine version-14 database
 * with Room, then PHYSICALLY recreate the v13 shape (doctors without the
 * new profile columns, no customer_links / customer_profiles /
 * pending_customer_ops tables) and set user_version back to 13 — exactly
 * what a dev.7 install has. Reopen with the REAL migrations array: Room's
 * schema validation fails the open if MIGRATION_13_14 restores anything
 * differently, and a seeded legacy doctor row must survive byte-for-byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration13to14Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File

    @After
    fun tearDown() {
        AppDatabase.migrationsForTest() // touch — keeps the companion honest
        if (::dbFile.isInitialized) dbFile.delete()
        (AppDatabase::class.java.getDeclaredField("inst").apply { isAccessible = true })
            .let { f -> f.set(null, null) } // reset the singleton between tests
    }

    /** rebuild the physical v13 shape (dev.7 / mission-12 install) */
    private fun downgradedToV13() {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            // doctors: rebuild WITHOUT the 10 new columns (edit-the-DDL trick —
            // constraints and the primary key survive)
            val sql = raw.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='doctors'", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            var v13 = sql
            listOf(
                ", `lastName` TEXT", ", `title` TEXT", ", `specialty` TEXT",
                ", `managerName` TEXT", ", `phone2` TEXT", ", `district` TEXT",
                ", `postalCode` TEXT", ", `notes` TEXT",
                ", `active` INTEGER NOT NULL", ", `pendingLocalId` TEXT",
            ).forEach { col -> v13 = v13.replace(col, "") }
            assertTrue("downgrade DDL must differ (profile columns removed)", v13 != sql)
            raw.execSQL("DROP TABLE IF EXISTS doctors_v13")
            raw.execSQL(v13.replace("CREATE TABLE `doctors`", "CREATE TABLE doctors_v13"))
            raw.execSQL(
                "INSERT INTO doctors_v13 (id,name,type,address,phone,province,city,lat,lng,radius,updatedAt,assigned) " +
                    "SELECT id,name,type,address,phone,province,city,lat,lng,radius,updatedAt,assigned FROM doctors",
            )
            raw.execSQL("DROP TABLE doctors")
            raw.execSQL("ALTER TABLE doctors_v13 RENAME TO doctors")
            // the three NEW tables simply did not exist on v13
            raw.execSQL("DROP TABLE IF EXISTS customer_links")
            raw.execSQL("DROP TABLE IF EXISTS customer_profiles")
            raw.execSQL("DROP TABLE IF EXISTS pending_customer_ops")
            raw.version = 13
        }
    }

    private fun reopened(): AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, dbFile.path)
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()

    @Test
    fun `migration 13 to 14 adds profile columns tables and keeps legacy rows`() = runBlocking {
        // 1) build a REAL v14 database, seed, then degrade to the v13 shape
        val v14 = Room.databaseBuilder(app, AppDatabase::class.java, "mig1314-test.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        dbFile = File(app.getDatabasePath("mig1314-test.db").absolutePath)
        v14.doctorDao().upsert(
            Doctor(
                id = "c-legacy", name = "دکتر احمدی", type = "doctor",
                address = "تهران، خیابان ولیعصر", phone = "09121234567",
                city = "تهران", province = "تهران",
                lat = 35.72, lng = 51.42, radius = 180.0,
                updatedAt = 1_700_000_000_000, assigned = true,
            ),
        )
        v14.doctorDao().upsert(
            Doctor(
                id = "c-clinic", name = "کلینیک مهر", type = "clinic",
                lat = 35.70, lng = 51.40, assigned = true,
                managerName = "آقای رضایی", active = true,
            ),
        )
        v14.close()
        downgradedToV13()

        // 2) reopen — throws if MIGRATION_13_14 restores the schema wrong
        val db = reopened()

        // 3) legacy rows survived; new columns default correctly
        val legacy = db.doctorDao().byId("c-legacy")!!
        assertEquals("دکتر احمدی", legacy.name)
        assertEquals("تهران", legacy.city)
        assertEquals("09121234567", legacy.phone)
        assertEquals(180.0, legacy.radius, 0.0)
        assertTrue(legacy.assigned)
        assertTrue("legacy rows are active by default", legacy.active)
        assertEquals(null, legacy.lastName)
        assertEquals(null, legacy.specialty)
        assertEquals(null, legacy.pendingLocalId)
        // fullName falls back to the plain name when no family name exists
        assertEquals("دکتر احمدی", legacy.fullName)

        // 4) the new DAOs work against the migrated tables
        db.customerLinkDao().upsertAll(
            listOf(
                CustomerLinkEntity(
                    id = "l1", clinicId = "c-clinic", physicianId = "c-legacy",
                    physicianName = "دکتر احمدی", note = "شنبه‌ها",
                ),
            ),
        )
        assertEquals(1, db.customerLinkDao().byClinic("c-clinic").size)
        assertEquals(1, db.customerLinkDao().byPhysician("c-legacy").size)

        db.customerProfileDao().upsert(
            CustomerProfileCache(customerId = "c-legacy", payload = """{"ok":true}"""),
        )
        assertEquals("""{"ok":true}""", db.customerProfileDao().byId("c-legacy")!!.payload)

        db.pendingCustomerOpDao().insert(
            PendingCustomerOp(localId = "op-1", op = "create", payload = "{}"),
        )
        assertEquals(1, db.pendingCustomerOpDao().pendingCount())

        // 5) keepOnlyExceptPending: the sweep keeps offline-created rows
        db.doctorDao().upsert(
            Doctor(
                id = "local-op-1", name = "کلینیک آفلاین", type = "clinic",
                lat = 35.0, lng = 51.0, assigned = false, pendingLocalId = "op-1",
            ),
        )
        db.doctorDao().keepOnlyExceptPending(listOf("c-legacy")) // c-clinic de-assigned
        assertNotNull(db.doctorDao().byId("c-legacy"))
        assertNotNull("offline-created rows survive the sweep", db.doctorDao().byId("local-op-1"))
        assertFalse(db.doctorDao().byId("c-clinic") != null)

        // 6) empty-assignment sweep also keeps pending rows
        db.doctorDao().clearExceptPending()
        assertNotNull(db.doctorDao().byId("local-op-1"))
        assertEquals(1, db.doctorDao().all().size)
        db.close()
    }

    @Test
    fun `new profile columns round-trip through the dao`() = runBlocking {
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "mig1314-roundtrip.db")
            .addMigrations(*AppDatabase.migrationsForTest())
            .build()
        dbFile = File(app.getDatabasePath("mig1314-roundtrip.db").absolutePath)
        db.doctorDao().upsert(
            Doctor(
                id = "c-new", name = "مریم", lastName = "محمدی", type = "doctor",
                title = "دکتر", specialty = "قلب", phone = "09120000000", phone2 = "02188888888",
                district = "منطقهٔ ۳", postalCode = "1234567890",
                notes = "یادداشت پرونده", lat = 35.1, lng = 51.1,
                assigned = true, active = true,
            ),
        )
        val row = db.doctorDao().byId("c-new")!!
        assertEquals("مریم محمدی", row.fullName)
        assertEquals("قلب", row.specialty)
        assertEquals("منطقهٔ ۳", row.district)
        assertEquals("1234567890", row.postalCode)
        assertEquals("یادداشت پرونده", row.notes)
        db.close()
    }
}
