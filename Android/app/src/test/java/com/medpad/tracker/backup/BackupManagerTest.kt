package com.medpad.tracker.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.CustomerLinkEntity
import com.medpad.tracker.db.CustomerProfileCache
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.db.OpEventEntity
import com.medpad.tracker.db.PendingCheckin
import com.medpad.tracker.db.PendingCustomerOp
import com.medpad.tracker.db.PendingLocation
import com.medpad.tracker.db.PlannedVisitEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Backup & Restore (owner mission, Nov 2026) — the JSON pipeline:
 *  * export → import round-trip over ALL EIGHT tables
 *  * conflict resolution: doctors LWW by updatedAt, queue tables deduped
 *    by localId, mirrors REPLACE by id — and re-import = all skipped
 *  * corrupt file / bad manifest handling — typed errors, DB untouched
 *  * the null contract: writers omit nulls, readers accept BOTH missing
 *    keys and explicit JSON nulls (the Python tool may emit either)
 *  * safety snapshots: written before every restore, pruned to 3
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dbs = ArrayList<AppDatabase>()
    private val files = ArrayList<File>()

    @After
    fun tearDown() {
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

    /** representative rows in every table — the round-trip payload */
    private fun seed(db: AppDatabase) = runBlocking {
        db.doctorDao().upsert(
            Doctor(
                id = "d1", name = "دکتر احمدی", type = "doctor",
                address = "تهران، ولیعصر", phone = "09121112233",
                province = "تهران", city = "تهران",
                lat = 35.72, lng = 51.42, radius = 200.0,
                updatedAt = 1_700_000_100_000, assigned = true,
                lastName = "احمدی", title = "دکتر", specialty = "قلب",
                managerName = "آقای رضایی", phone2 = "02112345678",
                district = "منطقهٔ ۳", postalCode = "1234567890",
                notes = "یادداشت پرونده", active = true,
            ),
        )
        db.doctorDao().upsert(
            // minimal legacy-shaped row: every nullable field null
            Doctor(id = "d2", name = "کلینیک مهر", type = "clinic", lat = 35.0, lng = 51.0, updatedAt = 1_500_000_000_000),
        )
        db.customerLinkDao().upsertAll(
            listOf(
                CustomerLinkEntity(
                    id = "l1", clinicId = "d2", physicianId = "d1",
                    clinicName = "کلینیک مهر", physicianName = "دکتر احمدی",
                    note = "شنبه‌ها", active = true,
                ),
            ),
        )
        db.customerProfileDao().upsert(
            CustomerProfileCache(customerId = "d1", payload = """{"visits":3}""", fetchedAt = 1_700_000_200_000),
        )
        db.pendingCustomerOpDao().insert(
            PendingCustomerOp(
                localId = "op-uuid-1", op = "create",
                payload = """{"name":"کلینیک جدید"}""",
                createdAt = 1_700_000_300_000, status = "pending",
            ),
        )
        db.pendingCheckinDao().insert(
            PendingCheckin(
                localId = "chk-uuid-1", kind = "checkin", customerId = "d1",
                note = "گزارش اول", voicePath = "/voice/a.m4a",
                voicePaths = """["/voice/a.m4a","/voice/b.m4a"]""",
                lat = 35.72, lng = 51.42, accuracy = 12.5,
                createdAt = 1_700_000_400_000, status = "failed",
                lastError = "خطای آزمایشی",
            ),
        )
        db.pendingLocationDao().insert(
            PendingLocation(localId = "loc-uuid-1", lat = 35.72, lng = 51.42, accuracy = 8.0, createdAt = 1_700_000_500_000),
        )
        db.plannedVisitDao().upsertAll(
            listOf(
                PlannedVisitEntity(
                    id = "p1", customerId = "d1", dayKey = "2026-11-03", timeLocal = "09:30",
                    scheduledAtMs = 1_700_000_600_000, durationMin = 45, priority = "high",
                    status = "scheduled", note = "ویزیت مهم", customerName = "دکتر احمدی",
                    customerAssigned = true,
                ),
            ),
        )
        db.opEventDao().insert(
            OpEventEntity(
                localId = "ev-uuid-1", type = "queue.flush", status = "success",
                createdAt = 1_700_000_700_000, message = "صف خالی شد",
            ),
        )
    }

    // ── 1) the format constants must never drift from the real schema ────

    @Test
    fun `schema version constant matches the real database version`() = runBlocking {
        val db = newDb("bk-const.db")
        assertEquals(BackupManager.CURRENT_SCHEMA_VERSION, db.openHelper.writableDatabase.version)
    }

    // ── 2) export → wipe → import round-trips EVERY table ────────────────

    @Test
    fun `export then import round-trips every table`() = runBlocking {
        val a = newDb("bk-a.db")
        seed(a)
        val json = BackupManager.exportJson(a, "test-1.9.14-dev.2")

        // manifest contract
        val m = json.getJSONObject("manifest")
        assertEquals("MedPad", m.getString("app"))
        assertEquals(1, m.getInt("formatVersion"))
        assertEquals(BackupManager.CURRENT_SCHEMA_VERSION, m.getInt("schemaVersion"))
        assertEquals("test-1.9.14-dev.2", m.getString("appVersion"))
        assertTrue("exportedAt must be ISO-8601", m.getString("exportedAt").endsWith("Z"))

        // the format never carries row-local autoGenerate uids
        val chkRow = json.getJSONObject("entities").getJSONArray("pending_checkins").getJSONObject(0)
        assertFalse("uid must not be exported", chkRow.has("uid"))
        // null fields are omitted, not emitted as null
        val d2 = json.getJSONObject("entities").getJSONArray("doctors").getJSONObject(1)
        assertFalse("null fields must be omitted", d2.has("address"))

        val b = newDb("bk-b.db")
        val result = BackupManager.importJson(b, json.toString(2))
        assertEquals(9, result.totalAdded) // 2 doctors + 7 rows across the other tables

        // every table's data survived byte-comparable
        val d1 = b.doctorDao().byId("d1")!!
        assertEquals("دکتر احمدی", d1.name)
        assertEquals("قلب", d1.specialty)
        assertEquals("1234567890", d1.postalCode)
        assertEquals(200.0, d1.radius, 0.0)
        assertEquals(1_700_000_100_000, d1.updatedAt)
        val d2b = b.doctorDao().byId("d2")!!
        assertNull(d2b.address)
        assertNull(d2b.province)

        assertEquals(1, b.customerLinkDao().byClinic("d2").size)
        assertEquals("شنبه‌ها", b.customerLinkDao().byClinic("d2")[0].note)

        assertEquals("""{"visits":3}""", b.customerProfileDao().byId("d1")!!.payload)
        assertEquals(1_700_000_200_000, b.customerProfileDao().byId("d1")!!.fetchedAt)

        val ops = b.pendingCustomerOpDao().all()
        assertEquals(1, ops.size)
        assertEquals("op-uuid-1", ops[0].localId)
        assertEquals("create", ops[0].op)

        val chks = b.pendingCheckinDao().all()
        assertEquals(1, chks.size)
        assertEquals("chk-uuid-1", chks[0].localId)
        assertEquals("failed", chks[0].status)
        assertEquals("خطای آزمایشی", chks[0].lastError)
        assertEquals(12.5, chks[0].accuracy!!, 0.0)
        assertEquals(2, chks[0].voiceFiles().size) // multi-voice JSON survived

        val locs = b.pendingLocationDao().all()
        assertEquals(1, locs.size)
        assertEquals("loc-uuid-1", locs[0].localId)
        assertEquals(8.0, locs[0].accuracy!!, 0.0)

        val plans = b.plannedVisitDao().byDay("2026-11-03")
        assertEquals(1, plans.size)
        assertEquals("ویزیت مهم", plans[0].note)
        assertEquals("high", plans[0].priority)

        val evs = b.opEventDao().all()
        assertEquals(1, evs.size)
        assertEquals("صف خالی شد", evs[0].message)
    }

    // ── 3) conflict resolution ────────────────────────────────────────────

    @Test
    fun `import merges with LWW on doctors and localId dedupe on queues`() = runBlocking {
        val db = newDb("bk-merge.db")
        seed(db)

        // a NEWER version of d1 (LWW winner) and an OLDER version of d2
        val json = """
            {"manifest":{"app":"MedPad","formatVersion":1,"schemaVersion":${BackupManager.CURRENT_SCHEMA_VERSION},
            "exportedAt":"2026-11-03T10:00:00Z","appVersion":"t"},
            "entities":{
              "doctors":[
                {"id":"d1","name":"دکتر احمدی-ویرایش","type":"doctor","lat":35.72,"lng":51.42,
                 "updatedAt":${1_700_000_100_001},"assigned":true},
                {"id":"d2","name":"کلینیک مهر-قدیمی","type":"clinic","lat":35.0,"lng":51.0,
                 "updatedAt":${1_000_000_000_000},"assigned":false}
              ],
              "pending_checkins":[
                {"localId":"chk-uuid-1","kind":"checkout","note":"تکراری","createdAt":1}
              ],
              "pending_locations":[
                {"localId":"loc-uuid-1","lat":1.0,"lng":2.0,"createdAt":1},
                {"localId":"loc-uuid-2","lat":3.0,"lng":4.0,"createdAt":2}
              ]
            }}
        """.trimIndent()

        val result = BackupManager.importJson(db, json)
        val doctors = result.tables["doctors"]!!
        assertEquals(0, doctors.added)
        assertEquals(1, doctors.updated)   // d1 newer
        assertEquals(1, doctors.skipped)   // d2 older
        val checkins = result.tables["pending_checkins"]!!
        assertEquals(0, checkins.added)
        assertEquals(1, checkins.skipped)  // same localId
        val locations = result.tables["pending_locations"]!!
        assertEquals(1, locations.added)   // new localId
        assertEquals(1, locations.skipped)

        assertEquals("دکتر احمدی-ویرایش", db.doctorDao().byId("d1")!!.name)
        assertEquals("کلینیک مهر", db.doctorDao().byId("d2")!!.name) // older LOST
        assertEquals(1, db.pendingCheckinDao().count())
        assertEquals(2, db.pendingLocationDao().count())
    }

    @Test
    fun `re-importing the same file changes nothing`() = runBlocking {
        val a = newDb("bk-re-a.db")
        seed(a)
        val text = BackupManager.exportJson(a, "t").toString(2)
        val b = newDb("bk-re-b.db")
        val first = BackupManager.importJson(b, text)
        assertEquals(9, first.totalAdded)
        val second = BackupManager.importJson(b, text)
        assertEquals(0, second.totalAdded)
        // the two REPLACE-by-id mirrors (customer_links, planned_visits) are
        // re-replaced with identical content — counted as «به‌روزرسانی»
        assertEquals(2, second.totalUpdated)
        assertEquals(7, second.totalSkipped)
    }

    // ── 4) corrupt / invalid input never touches the database ────────────

    @Test
    fun `corrupt json is rejected and the database is unchanged`() = runBlocking {
        val db = newDb("bk-corrupt.db")
        seed(db)
        val before = db.doctorDao().all().size
        for (bad in listOf("", "not json at all", "{\"manifest\":", "[]", "{}")) {
            try {
                BackupManager.importJson(db, bad)
                fail("corrupt input must throw: <$bad>")
            } catch (e: BackupManager.BackupException) {
                // typed — the UI maps it to a specific Persian string
            }
        }
        assertEquals(before, db.doctorDao().all().size)
        assertEquals("دکتر احمدی", db.doctorDao().byId("d1")!!.name)
    }

    @Test
    fun `manifest validation rejects foreign apps and future versions`() = runBlocking {
        val db = newDb("bk-manifest.db")
        fun json(app: String, fv: Int, sv: Int) =
            """{"manifest":{"app":"$app","formatVersion":$fv,"schemaVersion":$sv,"exportedAt":"2026-11-03T10:00:00Z","appVersion":"t"},"entities":{}}"""

        fun expectCode(text: String, code: Int) {
            try {
                runBlocking { BackupManager.importJson(db, text) }
                fail("must throw with code $code")
            } catch (e: BackupManager.BackupException) {
                assertEquals(code, e.code)
            }
        }
        expectCode(json("OtherApp", 1, 14), BackupManager.BackupException.CODE_NOT_MEDPAD)
        expectCode(json("MedPad", 2, 14), BackupManager.BackupException.CODE_FORMAT_FUTURE)
        expectCode(json("MedPad", 1, 99), BackupManager.BackupException.CODE_SCHEMA_FUTURE)
        expectCode(json("MedPad", 0, 14), BackupManager.BackupException.CODE_CORRUPT)
        // a manifest but no entities object
        expectCode("""{"manifest":{"app":"MedPad","formatVersion":1,"schemaVersion":14}}""", BackupManager.BackupException.CODE_NO_ENTITIES)
        // an OLDER schemaVersion is fine (fields default)
        val ok = runBlocking { BackupManager.importJson(db, json("MedPad", 1, 11)) }
        assertEquals(0, ok.totalAdded)
    }

    @Test
    fun `a structurally bad row is skipped but good rows land`() = runBlocking {
        val db = newDb("bk-rowtol.db")
        val json = """
            {"manifest":{"app":"MedPad","formatVersion":1,"schemaVersion":${BackupManager.CURRENT_SCHEMA_VERSION},
            "exportedAt":"2026-11-03T10:00:00Z","appVersion":"t"},
            "entities":{"doctors":[
                {"id":"ok1","name":"سالم","lat":1.0,"lng":2.0},
                {"id":"bad"},
                "not-even-an-object"
            ]}}
        """.trimIndent()
        val result = BackupManager.importJson(db, json)
        assertEquals(1, result.tables["doctors"]!!.added)
        assertEquals(2, result.tables["doctors"]!!.skipped)
        assertNotNull(db.doctorDao().byId("ok1"))
    }

    // ── 5) the null contract both ways ────────────────────────────────────

    @Test
    fun `readers accept explicit json nulls like omitted fields`() = runBlocking {
        val db = newDb("bk-nulls.db")
        // the Python tool serializes None as explicit null — both shapes
        // must parse identically
        val json = """
            {"manifest":{"app":"MedPad","formatVersion":1,"schemaVersion":${BackupManager.CURRENT_SCHEMA_VERSION},
            "exportedAt":"2026-11-03T10:00:00Z","appVersion":"t"},
            "entities":{"doctors":[
                {"id":"n1","name":"تهی","address":null,"phone":null,"province":null,
                 "lat":1.0,"lng":2.0,"radius":null,"updatedAt":null,"assigned":null}
            ]}}
        """.trimIndent()
        val result = BackupManager.importJson(db, json)
        assertEquals(1, result.totalAdded)
        val d = db.doctorDao().byId("n1")!!
        assertNull(d.address)
        assertNull(d.province)
        // optDouble(name, default) with an explicit null must fall back, not NaN
        assertEquals(150.0, d.radius, 0.0)
        assertEquals(0L, d.updatedAt)
        assertFalse(d.assigned)
    }

    // ── 6) safety snapshots ───────────────────────────────────────────────

    @Test
    fun `safety snapshot is written, listed, pruned and re-importable`() {
        runBlocking<Unit> {
        // build the app's REAL database file (createSafetyBackup copies
        // getDatabasePath("medpad.db")) on a private name and rename — the
        // snapshot path is fixed, so run this test against the real path
        val realPath = app.getDatabasePath("medpad.db")
        realPath.parentFile?.mkdirs()
        realPath.delete()
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "medpad.db")
            .addMigrations(*AppDatabase.migrations())
            .build()
            .also { dbs.add(it) }
        db.doctorDao().upsert(Doctor(id = "s1", name = "پیش از بازگردانی", lat = 1.0, lng = 2.0))

        val snap = BackupManager.createSafetyBackup(app, db)
        assertNotNull("snapshot must be written", snap)
        snap!!
        assertTrue(snap.length() > 0)
        assertEquals(1, BackupManager.listSafetyBackups(app).size)

        // prune: 4 stale snapshots + the fresh one → only 3 newest survive
        val dir = BackupManager.safetyDir(app)
        for (i in 1..4) {
            File(dir, "medpad-pre-restore-100000000000$i.db").writeText("old")
        }
        BackupManager.createSafetyBackup(app, db)
        val kept = BackupManager.listSafetyBackups(app)
        assertEquals(3, kept.size)
        // the prune keeps the NEWEST three (by name = timestamp)
        assertTrue(kept.none { it.name == "medpad-pre-restore-1000000000001.db" })

        // the undo path: a snapshot is a plain v14 .db — the legacy import
        // (which the safety-restore UI uses) re-imports it losslessly
        val target = newDb("bk-snap-target.db")
        val result = BackupManager.importLegacyDb(app, target, snap, "t")
        assertEquals(1, result.totalAdded)
        assertEquals("پیش از بازگردانی", target.doctorDao().byId("s1")!!.name)

        realPath.delete()
        dir.listFiles()?.forEach { it.delete() }
        }
    }

    // ── 7) the .db exact-copy export is restorable through the same path ──

    @Test
    fun `exportDbCopy produces a file importLegacyDb can read back`() = runBlocking {
        val a = newDb("bk-dcopy.db")
        seed(a)
        val out = File(app.cacheDir, "dcopy-test.db").also { files.add(it) }
        BackupManager.exportDbCopy(app, a, out.outputStream())
        assertTrue(out.length() > 0)

        val b = newDb("bk-dcopy-target.db")
        val result = BackupManager.importLegacyDb(app, b, out, "t")
        assertEquals(9, result.totalAdded)
        assertEquals("دکتر احمدی", b.doctorDao().byId("d1")!!.name)
        // SQLite header sanity
        val header = ByteArray(16)
        out.inputStream().use { it.read(header) }
        assertEquals("SQLite format 3", String(header, 0, 15).trim())
    }
}
