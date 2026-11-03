package com.medpad.tracker.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.CustomerLinkEntity
import com.medpad.tracker.db.CustomerProfileCache
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.db.MigrationTestFixtures
import com.medpad.tracker.db.OpEventEntity
import com.medpad.tracker.db.PendingCheckin
import com.medpad.tracker.db.PendingCustomerOp
import com.medpad.tracker.db.PendingLocation
import com.medpad.tracker.db.PlannedVisitEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Backup & Restore (owner mission, Nov 2026) — «بازگردانی از پایگاه‌داده
 * قدیمی»: the in-app legacy path. An old install's medpad.db (any schema
 * the RELEASED apks ever shipped) is opened on a private copy with the
 * REAL Room migration chain — the migration history IS the old→current
 * mapping — and every row lands through the SAME upsert pipeline as the
 * JSON import.
 *
 * Fixtures (physical shapes of real installs):
 *  * v6  — hand-built with raw SQL: the schema of the FIRST committed
 *          Android app (the earliest released apk). No province/city on
 *          doctors, no accuracy/status/voicePaths on pending_checkins,
 *          no localId/accuracy on pending_locations, no planned_visits /
 *          op_events / customer tables at all.
 *  * v13 — a genuine v14 database physically downgraded (the exact
 *          Migration13to14Test fixture: Customer-360 columns and tables
 *          removed) — the dev.7 install shape.
 *  * v11 — v13 further downgraded (no voicePaths column, no op_events)
 *          — the dev.4/dev.6 install shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDbImportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dbs = ArrayList<AppDatabase>()
    private val files = ArrayList<File>()

    @After
    fun tearDown() {
        AppDatabase.migrationsForTest() // touch — keeps the companion honest
        dbs.forEach { try { it.close() } catch (_: Exception) { } }
        files.forEach { it.delete() }
        (AppDatabase::class.java.getDeclaredField("inst").apply { isAccessible = true })
            .let { f -> f.set(null, null) }
    }

    private fun newDb(name: String): AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.migrations())
            .build()
            .also { dbs.add(it) }
            .also { files.add(File(app.getDatabasePath(name).absolutePath)) }

    // ── fixture builders ──────────────────────────────────────────────────

    /** a physical v6 database — the first committed app's schema, seeded */
    private fun buildV6(): File {
        val f = File(app.cacheDir, "legacy-v6-${System.nanoTime()}.db").also { files.add(it) }
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "CREATE TABLE doctors (" +
                    "id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, " +
                    "address TEXT, phone TEXT, " +
                    "lat REAL NOT NULL, lng REAL NOT NULL, radius REAL NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, assigned INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))",
            )
            raw.execSQL(
                "CREATE TABLE pending_checkins (" +
                    "uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "localId TEXT NOT NULL, kind TEXT NOT NULL, customerId TEXT, " +
                    "note TEXT NOT NULL, voicePath TEXT, " +
                    "lat REAL, lng REAL, " +
                    "createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT)",
            )
            raw.execSQL(
                "CREATE TABLE pending_locations (" +
                    "uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "lat REAL NOT NULL, lng REAL NOT NULL, createdAt INTEGER NOT NULL)",
            )
            raw.execSQL(
                "INSERT INTO doctors (id,name,type,address,phone,lat,lng,radius,updatedAt,assigned) VALUES " +
                    "('d-legacy','دکتر محمدی','doctor','اصفهان، خیابان چهارباغ','09131112222',32.65,51.67,220.0,${1_700_000_111_000},1)",
            )
            raw.execSQL(
                "INSERT INTO pending_checkins (localId,kind,customerId,note,voicePath,lat,lng,createdAt,attempts) VALUES " +
                    "('chk-v6-1','checkin','d-legacy','گزارش جلسهٔ صبح','/voice/old.m4a',32.65,51.67,${1_700_000_222_000},0)",
            )
            raw.execSQL(
                "INSERT INTO pending_locations (lat,lng,createdAt) VALUES (32.65,51.67,${1_700_000_333_000})",
            )
            raw.version = 6
        }
        return f
    }

    /** a genuine v14 database seeded in ALL EIGHT tables (returns the
     *  physical file, db closed — fixtures downgrade it afterwards) */
    private fun buildV14File(name: String): File {
        val db = newDb(name)
        runBlocking {
            db.doctorDao().upsert(
                Doctor(
                    id = "d-360", name = "دکتر کریمی", type = "doctor",
                    province = "البرز", city = "کرج",
                    lat = 35.84, lng = 50.94, updatedAt = 1_700_000_444_000, assigned = true,
                    specialty = "داخلی", active = true,
                ),
            )
            db.customerLinkDao().upsertAll(
                listOf(CustomerLinkEntity(id = "l-1", clinicId = "d-360", physicianId = "d-360")),
            )
            db.customerProfileDao().upsert(CustomerProfileCache(customerId = "d-360", payload = "{}"))
            db.pendingCustomerOpDao().insert(PendingCustomerOp(localId = "op-v13-1", op = "edit", customerId = "d-360", payload = "{}"))
            db.pendingCheckinDao().insert(
                PendingCheckin(
                    localId = "chk-v13-1", kind = "checkin", customerId = "d-360",
                    note = "گزارش", voicePaths = """["/voice/x.m4a"]""",
                    lat = 35.84, lng = 50.94, createdAt = 1_700_000_555_000,
                ),
            )
            db.pendingLocationDao().insert(PendingLocation(localId = "loc-v13-1", lat = 35.84, lng = 50.94, createdAt = 1_700_000_666_000))
            db.plannedVisitDao().upsertAll(
                listOf(
                    PlannedVisitEntity(
                        id = "pv-1", customerId = "d-360", dayKey = "2026-11-04", timeLocal = "10:00",
                        scheduledAtMs = 1_700_000_777_000, durationMin = 30,
                        priority = "normal", status = "scheduled",
                    ),
                ),
            )
            db.opEventDao().insert(OpEventEntity(localId = "ev-v13-1", type = "test", createdAt = 1_700_000_888_000))
        }
        val path = db.openHelper.writableDatabase.path!!
        db.close()
        return File(path)
    }

    /** physically downgrade a v14 file to the v13 shape (dev.7 install) */
    private fun downgradeToV13(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            MigrationTestFixtures.stripCustomer360Doctors(raw)
            MigrationTestFixtures.dropCustomer360Tables(raw)
            raw.version = 13
        }
    }

    /** physically downgrade to the v11 shape (dev.4/dev.6 install): v13
     *  minus the voicePaths column and the op_events table */
    private fun downgradeToV11(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            // rebuild pending_checkins without voicePaths (the Migration12to13Test DDL trick)
            val sql = raw.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='pending_checkins'", null,
            ).use { c -> c.moveToFirst(); c.getString(0) }
            val v11 = sql.replace(", `voicePaths` TEXT", "")
            check(v11 != sql) { "fixture bug: voicePaths not found" }
            raw.execSQL("DROP TABLE IF EXISTS pc_v11")
            raw.execSQL(v11.replace("CREATE TABLE `pending_checkins`", "CREATE TABLE pc_v11"))
            raw.execSQL(
                "INSERT INTO pc_v11 (uid,localId,kind,customerId,note,voicePath,lat,lng,accuracy,createdAt,attempts,lastError,status) " +
                    "SELECT uid,localId,kind,customerId,note,voicePath,lat,lng,accuracy,createdAt,attempts,lastError,status FROM pending_checkins",
            )
            raw.execSQL("DROP TABLE pending_checkins")
            raw.execSQL("ALTER TABLE pc_v11 RENAME TO pending_checkins")
            raw.execSQL("DROP TABLE IF EXISTS op_events")
            raw.version = 11
        }
    }

    // ── the tests ─────────────────────────────────────────────────────────
    @Test
    fun `imports a v6 database - the earliest released schema`() = runBlocking {
        val v6 = buildV6()
        val target = newDb("legacy-target-v6.db")

        val result = BackupManager.importLegacyDb(app, target, v6, "test")

        assertEquals(3, result.totalAdded) // doctor + checkin + location
        val doc = target.doctorDao().byId("d-legacy")!!
        assertEquals("دکتر محمدی", doc.name)
        assertEquals("اصفهان، خیابان چهارباغ", doc.address)
        assertEquals(220.0, doc.radius, 0.0)
        assertTrue(doc.assigned)
        // columns added by later migrations default honestly
        assertNull(doc.province)
        assertNull(doc.city)
        assertNull(doc.specialty)
        assertTrue("legacy rows are active by default", doc.active)

        val chk = target.pendingCheckinDao().all()[0]
        assertEquals("chk-v6-1", chk.localId)
        assertEquals("pending", chk.status)      // 8→9 default
        assertNull(chk.accuracy)                  // 6→7 nullable
        assertNull(chk.voicePaths)                // 12→13 nullable
        assertEquals("/voice/old.m4a", chk.voicePath)

        val loc = target.pendingLocationDao().all()[0]
        assertEquals("", loc.localId)             // 10→11 default ''
        assertNull(loc.accuracy)

        // the migration created the later tables; the import added nothing
        assertEquals(0, target.plannedVisitDao().count())
        assertEquals(0, target.opEventDao().count())

        // idempotent: re-importing the same legacy file duplicates nothing
        val again = BackupManager.importLegacyDb(app, target, v6, "test")
        assertEquals(0, again.totalAdded)
        assertEquals(3, again.totalSkipped) // LWW-equal doctor + same localIds… and the composite key for the location
        assertEquals(1, target.pendingCheckinDao().count())
        assertEquals(1, target.pendingLocationDao().count())
    }

    @Test
    fun `imports a v13 database - dev7 install shape with customer360 defaults`() = runBlocking {
        val v13 = buildV14File("legacy-src-v13.db")
        downgradeToV13(v13)

        val target = newDb("legacy-target-v13.db")
        val result = BackupManager.importLegacyDb(app, target, v13, "test")

        // doctors + checkin + location + planned visit + op_event survive;
        // the three Customer-360 tables did not exist on v13 (empty in the file)
        assertEquals(5, result.totalAdded)
        val doc = target.doctorDao().byId("d-360")!!
        assertEquals("دکتر کریمی", doc.name)
        assertEquals("البرز", doc.province)      // pre-360 column, survived
        assertNull(doc.specialty)                 // 360 column, defaulted
        assertTrue(doc.active)

        assertEquals("chk-v13-1", target.pendingCheckinDao().all()[0].localId)
        assertEquals(1, target.plannedVisitDao().count())
        assertEquals("ev-v13-1", target.opEventDao().all()[0].localId) // v12 table, survived
    }

    @Test
    fun `imports a v11 database - dev4 install shape`() = runBlocking {
        val v11 = buildV14File("legacy-src-v11.db")
        downgradeToV13(v11)
        downgradeToV11(v11)

        val target = newDb("legacy-target-v11.db")
        val result = BackupManager.importLegacyDb(app, target, v11, "test")

        assertEquals(4, result.totalAdded)
        val chk = target.pendingCheckinDao().all()[0]
        assertEquals("chk-v13-1", chk.localId)
        assertNull(chk.voicePaths) // the column did not exist on v11
        assertEquals(0, target.opEventDao().count()) // table recreated empty by 11→12
    }

    @Test
    fun `corrupt file is rejected with the typed error`() = runBlocking {
        val garbage = File(app.cacheDir, "garbage-${System.nanoTime()}.db").also { files.add(it) }
        garbage.writeText("این فایل پایگاه‌داده نیست")
        val target = newDb("legacy-target-garbage.db")
        try {
            BackupManager.importLegacyDb(app, target, garbage, "test")
            fail("corrupt file must throw")
        } catch (e: BackupManager.BackupException) {
            assertEquals(BackupManager.BackupException.CODE_DB_OPEN_FAILED, e.code)
        }
        assertEquals(0, target.doctorDao().all().size)
    }

    @Test
    fun `too-old schema is rejected honestly`() = runBlocking {
        val old = File(app.cacheDir, "too-old-${System.nanoTime()}.db").also { files.add(it) }
        SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("CREATE TABLE doctors (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
            raw.version = 3
        }
        val target = newDb("legacy-target-old.db")
        try {
            BackupManager.importLegacyDb(app, target, old, "test")
            fail("pre-v4 schema must throw")
        } catch (e: BackupManager.BackupException) {
            assertEquals(BackupManager.BackupException.CODE_LEGACY_TOO_OLD, e.code)
        }
    }

    @Test
    fun `future schema is rejected honestly`() = runBlocking {
        val future = buildV6()
        SQLiteDatabase.openDatabase(future.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 15 }
        val target = newDb("legacy-target-future.db")
        try {
            BackupManager.importLegacyDb(app, target, future, "test")
            fail("future schema must throw")
        } catch (e: BackupManager.BackupException) {
            assertEquals(BackupManager.BackupException.CODE_SCHEMA_FUTURE, e.code)
        }
    }
}
