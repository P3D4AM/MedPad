package com.medpad.tracker.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.CustomerLinkEntity
import com.medpad.tracker.db.CustomerProfileCache
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.db.OpEventEntity
import com.medpad.tracker.db.PendingCheckin
import com.medpad.tracker.db.PendingCustomerOp
import com.medpad.tracker.db.PendingLocation
import com.medpad.tracker.db.PlannedVisitEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * Backup & Restore + legacy-data migration (owner mission, Nov 3 2026):
 * «به نرم افزار قابلیت بکاپ گیری هم اضافه کن… اطلاعات ورژنهای قبلی رو…
 * بکاپ بگیرم به صورتی که به این نرم افزار بتونم وارد کنم».
 *
 * ONE versioned, human-readable JSON format shared by three producers and
 * one consumer:
 *
 *   { "manifest": { "app": "MedPad", "formatVersion": 1, "schemaVersion": 14,
 *       "exportedAt": "<ISO-8601>", "appVersion": "…" },
 *     "entities": { "<table>": [ {row…}, … ] } }
 *
 *   producers: this class (in-app export), the in-app legacy-.db import
 *              (Room migrates the old file, then the SAME export code runs
 *              against it) and tools/legacy-backup/medpad_legacy_backup.py
 *              (standalone CLI for old installs — reads any schema-v4..14
 *              medpad.db directly).
 *   consumer:  [applyEntities] below — ONE upsert pipeline for every path.
 *
 * Format contract (v1 — mirrored in tools/legacy-backup/README-fa.md):
 *  - JSON keys are the SQLite column names (which equal the Kotlin property
 *    names — no @ColumnInfo renames exist in the schema).
 *  - Booleans serialize as true/false; Integers/Reals as JSON numbers;
 *    NULLABLE fields are OMITTED when null (never emitted as JSON null) —
 *    readers must treat missing and null alike ([strOrNull] does).
 *  - autoGenerate primary keys (uid on the four queue tables) are NOT part
 *    of the format: uids are row-local, never stable across devices. The
 *    stable identities are the natural keys (id / customerId) and the
 *    idempotency key localId.
 *
 * Import semantics (merge — nothing is ever deleted by an import):
 *  - doctors            upsert by id, last-write-wins by updatedAt
 *  - customer_profiles  upsert by customerId, LWW by fetchedAt
 *  - customer_links     upsert by id (REPLACE — read-only server mirror)
 *  - planned_visits     upsert by id (REPLACE — read-only server mirror)
 *  - pending_checkins / pending_locations / op_events / pending_customer_ops
 *                       insert, de-duplicated by localId (the same UUID
 *                       idempotency key the server side already trusts —
 *                       a replayed row can never double-book)
 *
 * Safety: every restore takes an automatic safety snapshot of the CURRENT
 * database first (files/backups/, newest 3 kept) — see [createSafetyBackup].
 * The import itself runs inside ONE Room transaction: any failure rolls
 * back and the database is byte-identical to before.
 */
object BackupManager {

    const val FORMAT_VERSION = 1
    const val APP_NAME = "MedPad"

    /** MUST match @Database(version = 15) in AppDatabase — asserted by the
     *  unit tests so a future bump cannot silently diverge. */
    const val CURRENT_SCHEMA_VERSION = 15

    /** the minimum legacy schema the Room migration chain can upgrade */
    const val MIN_LEGACY_SCHEMA_VERSION = 4

    // ── errors (UI maps each code to a specific Persian string) ──────────

    class BackupException(val code: Int, message: String) : Exception(message) {
        companion object {
            const val CODE_CORRUPT = 1          // not JSON / not a database at all
            const val CODE_NOT_MEDPAD = 2       // manifest.app != MedPad
            const val CODE_FORMAT_FUTURE = 3    // formatVersion > the one we know
            const val CODE_SCHEMA_FUTURE = 4    // schemaVersion > current (newer app made it)
            const val CODE_NO_ENTITIES = 5      // JSON without the entities object
            const val CODE_LEGACY_TOO_OLD = 6   // .db user_version < 4 (pre v1.9 chain)
            const val CODE_DB_OPEN_FAILED = 7   // SQLite could not open the file
            const val CODE_UNEXPECTED = 8       // anything else — honest generic
            const val CODE_SAFETY_FAILED = 9    // pre-restore snapshot failed → restore ABORTED
        }
    }

    // ── result model ──────────────────────────────────────────────────────

    class TableCounts {
        var added: Int = 0
        var updated: Int = 0
        var skipped: Int = 0
        val touched: Int get() = added + updated
    }

    class ImportResult {
        val tables = LinkedHashMap<String, TableCounts>()
        fun slot(name: String): TableCounts = tables.getOrPut(name) { TableCounts() }
        val totalAdded: Int get() = tables.values.sumOf { it.added }
        val totalUpdated: Int get() = tables.values.sumOf { it.updated }
        val totalSkipped: Int get() = tables.values.sumOf { it.skipped }
    }

    // ── EXPORT (DB → JSON) ────────────────────────────────────────────────

    /** Full-data export: every row of every table, current schema. */
    suspend fun exportJson(db: AppDatabase, appVersion: String): JSONObject {
        val entities = JSONObject()
        entities.put("doctors", JSONArray().also { a -> db.doctorDao().all().forEach { a.put(toJson(it)) } })
        entities.put("customer_links", JSONArray().also { a -> db.customerLinkDao().all().forEach { a.put(toJson(it)) } })
        entities.put("customer_profiles", JSONArray().also { a -> db.customerProfileDao().all().forEach { a.put(toJson(it)) } })
        entities.put("pending_customer_ops", JSONArray().also { a -> db.pendingCustomerOpDao().all().forEach { a.put(toJson(it)) } })
        entities.put("pending_checkins", JSONArray().also { a -> db.pendingCheckinDao().all().forEach { a.put(toJson(it)) } })
        entities.put("pending_locations", JSONArray().also { a -> db.pendingLocationDao().all().forEach { a.put(toJson(it)) } })
        entities.put("planned_visits", JSONArray().also { a -> db.plannedVisitDao().all().forEach { a.put(toJson(it)) } })
        entities.put("op_events", JSONArray().also { a -> db.opEventDao().all().forEach { a.put(toJson(it)) } })
        return JSONObject()
            .put(
                "manifest",
                JSONObject()
                    .put("app", APP_NAME)
                    .put("formatVersion", FORMAT_VERSION)
                    .put("schemaVersion", CURRENT_SCHEMA_VERSION)
                    .put("exportedAt", Instant.now().toString())
                    .put("appVersion", appVersion),
            )
            .put("entities", entities)
    }

    /** Secondary export: an exact .db file copy (checkpointed WAL folded
     *  into the main file first, so the single file is self-contained).
     *  Restorable on another install through «بازگردانی از پایگاه‌داده قدیمی». */
    suspend fun exportDbCopy(context: Context, db: AppDatabase, out: OutputStream): Long {
        checkpointWal(db)
        val src = File(dbFileOf(db, context))
        return out.use { o -> src.inputStream().use { i -> i.copyTo(o) } }
    }

    // ── IMPORT — JSON (the upsert pipeline every path feeds) ─────────────

    suspend fun importJson(db: AppDatabase, text: String): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupException(BackupException.CODE_CORRUPT, "not valid JSON: ${e.message}")
        }
        validateManifest(root)
        val entities = root.optJSONObject("entities")
            ?: throw BackupException(BackupException.CODE_NO_ENTITIES, "entities object missing")
        return db.withTransaction { applyEntities(db, entities) }
    }

    /** Manifest validation — the ONLY gate before data touches the DB. */
    internal fun validateManifest(root: JSONObject) {
        val m = root.optJSONObject("manifest")
            ?: throw BackupException(BackupException.CODE_CORRUPT, "manifest missing")
        if (m.optString("app") != APP_NAME) {
            throw BackupException(BackupException.CODE_NOT_MEDPAD, "app=${m.optString("app")}")
        }
        val fv = m.optInt("formatVersion", 0)
        if (fv > FORMAT_VERSION) {
            throw BackupException(BackupException.CODE_FORMAT_FUTURE, "formatVersion=$fv")
        }
        if (fv < FORMAT_VERSION) {
            throw BackupException(BackupException.CODE_CORRUPT, "formatVersion=$fv")
        }
        val sv = m.optInt("schemaVersion", 0)
        if (sv > CURRENT_SCHEMA_VERSION) {
            throw BackupException(BackupException.CODE_SCHEMA_FUTURE, "schemaVersion=$sv")
        }
        if (sv < 1) {
            throw BackupException(BackupException.CODE_CORRUPT, "schemaVersion=$sv")
        }
        // schemaVersion < CURRENT is fine: every added column since v4 was
        // ADDITIVE with a default — the readers below default missing keys.
    }

    /**
     * The shared upsert pipeline. Row-level tolerance: a structurally bad
     * row is counted «ردشده» and skipped — one damaged row must not void an
     * otherwise sound restore; the transaction still guarantees that the
     * applied subset lands atomically.
     */
    internal suspend fun applyEntities(db: AppDatabase, entities: JSONObject): ImportResult {
        val result = ImportResult()

        // doctors — upsert by id, LWW by updatedAt
        entities.optJSONArray("doctors")?.let { arr ->
            val c = result.slot("doctors")
            val existing = HashMap<String, Long>()
            db.doctorDao().all().forEach { existing[it.id] = it.updatedAt }
            val winners = ArrayList<Doctor>(arr.length())
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val d = doctorFrom(o)
                    val old = existing[d.id]
                    when {
                        old == null -> { winners.add(d); c.added++ }
                        d.updatedAt > old -> { winners.add(d); c.updated++ }
                        else -> c.skipped++
                    }
                } catch (_: Exception) {
                    c.skipped++
                }
            }
            if (winners.isNotEmpty()) db.doctorDao().upsertAll(winners)
        }

        // customer_links — REPLACE by id (server mirror, no LWW to arbitrate)
        entities.optJSONArray("customer_links")?.let { arr ->
            val c = result.slot("customer_links")
            val existingIds = HashSet<String>()
            db.customerLinkDao().all().forEach { existingIds.add(it.id) }
            val winners = ArrayList<CustomerLinkEntity>(arr.length())
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = CustomerLinkEntity(
                        id = o.getString("id"),
                        clinicId = o.getString("clinicId"),
                        physicianId = o.getString("physicianId"),
                        clinicName = o.strOrNull("clinicName"),
                        physicianName = o.strOrNull("physicianName"),
                        note = o.strOrNull("note"),
                        active = o.optBoolean("active", true),
                    )
                    if (existingIds.contains(row.id)) c.updated++ else c.added++
                    existingIds.add(row.id) // duplicates inside one file: last one wins
                    winners.add(row)
                } catch (_: Exception) {
                    c.skipped++
                }
            }
            if (winners.isNotEmpty()) db.customerLinkDao().upsertAll(winners)
        }

        // customer_profiles — upsert by customerId, LWW by fetchedAt
        entities.optJSONArray("customer_profiles")?.let { arr ->
            val c = result.slot("customer_profiles")
            val existing = HashMap<String, Long>()
            db.customerProfileDao().all().forEach { existing[it.customerId] = it.fetchedAt }
            val winners = ArrayList<CustomerProfileCache>(arr.length())
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = CustomerProfileCache(
                        customerId = o.getString("customerId"),
                        payload = o.getString("payload"),
                        fetchedAt = o.optLong("fetchedAt", System.currentTimeMillis()),
                    )
                    val old = existing[row.customerId]
                    when {
                        old == null -> { winners.add(row); c.added++ }
                        row.fetchedAt > old -> { winners.add(row); c.updated++ }
                        else -> c.skipped++
                    }
                    existing[row.customerId] = row.fetchedAt
                } catch (_: Exception) {
                    c.skipped++
                }
            }
            if (winners.isNotEmpty()) db.customerProfileDao().upsertAll(winners)
        }

        // pending_customer_ops — insert, deduped by localId (idempotency key)
        entities.optJSONArray("pending_customer_ops")?.let { arr ->
            val c = result.slot("pending_customer_ops")
            val seen = HashSet<String>()
            db.pendingCustomerOpDao().all().forEach { seen.add(it.localId) }
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = PendingCustomerOp(
                        uid = 0, // fresh row-local uid — never import one
                        localId = o.getString("localId"),
                        op = o.getString("op"),
                        customerId = o.strOrNull("customerId"),
                        payload = o.getString("payload"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        attempts = o.optInt("attempts", 0),
                        lastError = o.strOrNull("lastError"),
                        status = o.optString("status", "pending"),
                    )
                    if (seen.contains(row.localId)) {
                        c.skipped++
                    } else {
                        seen.add(row.localId)
                        db.pendingCustomerOpDao().insert(row)
                        c.added++
                    }
                } catch (_: Exception) {
                    c.skipped++
                }
            }
        }

        // pending_checkins — insert, deduped by localId when present; rows
        // from pre-v1.9.1 installs carry a blank localId (the column's
        // migration default) and dedupe on the composite natural key
        // (kind,customerId,createdAt) instead — re-importing the same
        // legacy file must never duplicate queue rows
        entities.optJSONArray("pending_checkins")?.let { arr ->
            val c = result.slot("pending_checkins")
            val seen = HashSet<String>()
            db.pendingCheckinDao().all().forEach {
                seen.add(if (it.localId.isNotEmpty()) it.localId else "ck:${it.kind}:${it.customerId}:${it.createdAt}")
            }
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = PendingCheckin(
                        uid = 0,
                        localId = o.optString("localId", ""), // v4-era rows: "" (the 4→5 migration default)
                        kind = o.getString("kind"),
                        customerId = o.strOrNull("customerId"),
                        note = o.optString("note", ""),
                        voicePath = o.strOrNull("voicePath"),
                        voicePaths = o.strOrNull("voicePaths"),
                        lat = o.dblOrNull("lat"),
                        lng = o.dblOrNull("lng"),
                        accuracy = o.dblOrNull("accuracy"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        attempts = o.optInt("attempts", 0),
                        lastError = o.strOrNull("lastError"),
                        status = o.optString("status", "pending"),
                    )
                    val key = if (row.localId.isNotEmpty()) row.localId else "ck:${row.kind}:${row.customerId}:${row.createdAt}"
                    if (seen.contains(key)) {
                        c.skipped++
                    } else {
                        seen.add(key)
                        db.pendingCheckinDao().insert(row)
                        c.added++
                    }
                } catch (_: Exception) {
                    c.skipped++
                }
            }
        }

        // pending_locations — insert, deduped by localId when present; a
        // pre-dev.5 install's rows carry a blank localId and dedupe on the
        // composite (lat,lng,createdAt) — same reading, same millisecond
        entities.optJSONArray("pending_locations")?.let { arr ->
            val c = result.slot("pending_locations")
            val seen = HashSet<String>()
            db.pendingLocationDao().all().forEach {
                seen.add(if (it.localId.isNotEmpty()) it.localId else "ll:${it.lat}:${it.lng}:${it.createdAt}")
            }
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = PendingLocation(
                        uid = 0,
                        localId = o.optString("localId", ""),
                        lat = o.getDouble("lat"),
                        lng = o.getDouble("lng"),
                        accuracy = o.dblOrNull("accuracy"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    )
                    val key = if (row.localId.isNotEmpty()) row.localId else "ll:${row.lat}:${row.lng}:${row.createdAt}"
                    if (seen.contains(key)) {
                        c.skipped++
                    } else {
                        seen.add(key)
                        db.pendingLocationDao().insert(row)
                        c.added++
                    }
                } catch (_: Exception) {
                    c.skipped++
                }
            }
        }

        // planned_visits — REPLACE by id (read-only server mirror)
        entities.optJSONArray("planned_visits")?.let { arr ->
            val c = result.slot("planned_visits")
            val existingIds = HashSet<String>()
            db.plannedVisitDao().all().forEach { existingIds.add(it.id) }
            val winners = ArrayList<PlannedVisitEntity>(arr.length())
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = PlannedVisitEntity(
                        id = o.getString("id"),
                        customerId = o.getString("customerId"),
                        dayKey = o.getString("dayKey"),
                        timeLocal = o.getString("timeLocal"),
                        scheduledAtMs = o.optLong("scheduledAtMs", 0L),
                        durationMin = o.optInt("durationMin", 0),
                        priority = o.optString("priority", "normal"),
                        status = o.optString("status", "scheduled"),
                        note = o.strOrNull("note"),
                        visitId = o.strOrNull("visitId"),
                        visitStartLocal = o.strOrNull("visitStartLocal"),
                        visitOpen = o.optBoolean("visitOpen", false),
                        overdue = o.optBoolean("overdue", false),
                        customerName = o.strOrNull("customerName"),
                        customerType = o.strOrNull("customerType"),
                        customerAddress = o.strOrNull("customerAddress"),
                        customerPhone = o.strOrNull("customerPhone"),
                        customerAssigned = o.optBoolean("customerAssigned", false),
                    )
                    if (existingIds.contains(row.id)) c.updated++ else c.added++
                    existingIds.add(row.id)
                    winners.add(row)
                } catch (_: Exception) {
                    c.skipped++
                }
            }
            if (winners.isNotEmpty()) db.plannedVisitDao().upsertAll(winners)
        }

        // op_events — insert, deduped by localId (upload idempotency key)
        entities.optJSONArray("op_events")?.let { arr ->
            val c = result.slot("op_events")
            val seen = HashSet<String>()
            db.opEventDao().all().forEach { seen.add(it.localId) }
            forEachRow(arr, { c.skipped++ }) { o ->
                try {
                    val row = OpEventEntity(
                        uid = 0,
                        localId = o.getString("localId"),
                        type = o.getString("type"),
                        status = o.optString("status", "success"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        errorCode = o.strOrNull("errorCode"),
                        message = o.strOrNull("message"),
                        relatedType = o.strOrNull("relatedType"),
                        relatedId = o.strOrNull("relatedId"),
                        attempt = o.intOrNull("attempt"),
                    )
                    if (seen.contains(row.localId)) {
                        c.skipped++
                    } else {
                        seen.add(row.localId)
                        db.opEventDao().insert(row)
                        c.added++
                    }
                } catch (_: Exception) {
                    c.skipped++
                }
            }
        }

        return result
    }

    // ── IMPORT — legacy .db (old installs without a backup feature) ──────

    /**
     * «بازگردانی از پایگاه‌داده قدیمی» — the "additional tool" path, in-app.
     *
     * [legacyFile] must be a WRITABLE private copy (never the user's only
     * file): Room MIGRATES it (migrations write), so the caller copies the
     * SAF-picked stream to cacheDir first. The chain 4→14 is exactly the
     * additive history the schema grew through, and Room validates the
     * result against the current entities — the migration history IS the
     * old→current mapping. After migration the SAME export code reads the
     * rows and the SAME [applyEntities] pipeline lands them in the live
     * database. Also accepts CURRENT-schema .db files (a no-migration open)
     * — which is exactly what the automatic safety snapshots are.
     */
    suspend fun importLegacyDb(context: Context, db: AppDatabase, legacyFile: File, appVersion: String): ImportResult {
        // 1) honest pre-flight: is this a SQLite database at all, and which
        //    schema version does it carry? (read-write open also recovers a
        //    sibling -wal, when the caller was able to copy one)
        try {
            SQLiteDatabase.openDatabase(legacyFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                val version = raw.version
                if (version < MIN_LEGACY_SCHEMA_VERSION) {
                    throw BackupException(BackupException.CODE_LEGACY_TOO_OLD, "user_version=$version")
                }
                if (version > CURRENT_SCHEMA_VERSION) {
                    throw BackupException(BackupException.CODE_SCHEMA_FUTURE, "user_version=$version")
                }
                if (version == 4) {
                    // pre-repo v1.9.0 build: apply the COLUMN half of
                    // MIGRATION_4_5 manually — WITHOUT the index half. The
                    // v14 entity declares no index on pending_checkins, so
                    // the real 4→5 path (which creates one) would leave a
                    // stray index Room's open-time validation rejects.
                    raw.execSQL("ALTER TABLE pending_checkins ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
                    raw.version = 5
                }
                // same reason, files that ALREADY went through the real
                // v4→v5: drop the stray index (pure performance — nothing
                // in the app requires it) so the open validates.
                raw.execSQL("DROP INDEX IF EXISTS index_pending_checkins_localId")
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupException.CODE_DB_OPEN_FAILED, e.message ?: "open failed")
        }

        // 2) open a THROWAWAY Room instance on the copy with the real chain
        val legacy = try {
            Room.databaseBuilder(context, AppDatabase::class.java, legacyFile.path)
                .addMigrations(*AppDatabase.migrations())
                .build()
        } catch (e: Exception) {
            throw BackupException(BackupException.CODE_DB_OPEN_FAILED, e.message ?: "room open failed")
        }

        // 3) read everything through the SAME export path, then apply
        try {
            val entities = exportJson(legacy, appVersion).getJSONObject("entities")
            return db.withTransaction { applyEntities(db, entities) }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            // a file that opens but does not match ANY known schema shape
            throw BackupException(BackupException.CODE_DB_OPEN_FAILED, e.message ?: "migration failed")
        } finally {
            try { legacy.close() } catch (_: Exception) { }
        }
    }

    // ── safety snapshots (automatic pre-restore backups) ─────────────────

    /** WAL checkpoint so the single .db file is self-contained. */
    private suspend fun checkpointWal(db: AppDatabase) {
        try {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (_: Exception) { /* best-effort: copy still runs */ }
    }

    /**
     * Automatic safety backup taken before EVERY restore. Returns the
     * snapshot file, or null when the snapshot could not be written — the
     * caller must then ABORT the restore (safety first: no undo-less
     * imports). Newest [KEEP_SAFETY] snapshots are retained.
     */
    suspend fun createSafetyBackup(context: Context, db: AppDatabase): File? = try {
        checkpointWal(db)
        val dir = safetyDir(context)
        val src = File(dbFileOf(db, context))
        val dst = File(dir, "medpad-pre-restore-${System.currentTimeMillis()}.db")
        if (src.exists()) src.copyTo(dst, overwrite = true) else File(dir, ".keep").apply { createNewFile() }
        pruneSafetyBackups(context)
        dst
    } catch (_: Exception) {
        null
    }

    /** The physical file of a Room instance — derived from the instance
     *  itself (works for the singleton AND for test/secondary instances);
     *  falls back to the app's default path if the helper won't tell. */
    private fun dbFileOf(db: AppDatabase, context: Context): String = try {
        db.openHelper.writableDatabase.path ?: context.getDatabasePath(DB_NAME).absolutePath
    } catch (_: Exception) {
        context.getDatabasePath(DB_NAME).absolutePath
    }

    fun safetyDir(context: Context): File =
        File(context.filesDir, "backups").apply { mkdirs() }

    fun listSafetyBackups(context: Context): List<File> =
        safetyDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith("medpad-pre-restore-") && f.name.endsWith(".db") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    private fun pruneSafetyBackups(context: Context, keep: Int = KEEP_SAFETY) {
        val all = listSafetyBackups(context)
        if (all.size > keep) all.drop(keep).forEach { it.delete() }
    }

    private const val KEEP_SAFETY = 3
    private const val DB_NAME = "medpad.db"

    // ── JSON helpers (null-safe on BOTH sides of the format) ─────────────

    /** iterate a JSONArray of objects; non-object entries are counted as
     *  skipped (the caller's «ردشده») — the summary must stay honest about
     *  every row the file claimed to carry. */
    private inline fun forEachRow(arr: JSONArray, onDropped: () -> Unit, block: (JSONObject) -> Unit) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                onDropped()
            } else {
                block(o)
            }
        }
    }

    /** missing OR explicit-null both read as null (the Python tool and a
     *  future Kotlin writer may emit either — never "null" as a string). */
    internal fun JSONObject.strOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    internal fun JSONObject.dblOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    internal fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    // NOTE on writing nullables below: the built-in JSONObject.putOpt(String,
    // Any) skips null values, so null fields are OMITTED from the output —
    // exactly the format contract documented at the top of this class.

    // ── entity ↔ JSON (one mapper per table; readers default every field
    //    that did not exist in older schemas — that IS the backward
    //    compatibility of format v1) ─────────────────────────────────────

    internal fun toJson(d: Doctor): JSONObject = JSONObject()
        .put("id", d.id)
        .put("name", d.name)
        .put("type", d.type)
        .putOpt("address", d.address)
        .putOpt("phone", d.phone)
        .putOpt("province", d.province)
        .putOpt("city", d.city)
        .put("lat", d.lat)
        .put("lng", d.lng)
        .put("radius", d.radius)
        .put("updatedAt", d.updatedAt)
        .put("assigned", d.assigned)
        .putOpt("lastName", d.lastName)
        .putOpt("title", d.title)
        .putOpt("specialty", d.specialty)
        .putOpt("managerName", d.managerName)
        .putOpt("phone2", d.phone2)
        .putOpt("district", d.district)
        .putOpt("postalCode", d.postalCode)
        .putOpt("notes", d.notes)
        .put("active", d.active)
        .putOpt("pendingLocalId", d.pendingLocalId)

    internal fun doctorFrom(o: JSONObject): Doctor = Doctor(
        id = o.getString("id"),
        name = o.getString("name"),
        type = o.optString("type", "doctor"),
        address = o.strOrNull("address"),
        phone = o.strOrNull("phone"),
        province = o.strOrNull("province"),
        city = o.strOrNull("city"),
        lat = o.getDouble("lat"),
        lng = o.getDouble("lng"),
        radius = o.optDouble("radius", 150.0),
        updatedAt = o.optLong("updatedAt", 0L),
        assigned = o.optBoolean("assigned", false),
        lastName = o.strOrNull("lastName"),
        title = o.strOrNull("title"),
        specialty = o.strOrNull("specialty"),
        managerName = o.strOrNull("managerName"),
        phone2 = o.strOrNull("phone2"),
        district = o.strOrNull("district"),
        postalCode = o.strOrNull("postalCode"),
        notes = o.strOrNull("notes"),
        active = o.optBoolean("active", true),
        pendingLocalId = o.strOrNull("pendingLocalId"),
    )

    internal fun toJson(c: CustomerLinkEntity): JSONObject = JSONObject()
        .put("id", c.id)
        .put("clinicId", c.clinicId)
        .put("physicianId", c.physicianId)
        .putOpt("clinicName", c.clinicName)
        .putOpt("physicianName", c.physicianName)
        .putOpt("note", c.note)
        .put("active", c.active)

    internal fun toJson(c: CustomerProfileCache): JSONObject = JSONObject()
        .put("customerId", c.customerId)
        .put("payload", c.payload)
        .put("fetchedAt", c.fetchedAt)

    internal fun toJson(p: PendingCustomerOp): JSONObject = JSONObject()
        .put("localId", p.localId)
        .put("op", p.op)
        .putOpt("customerId", p.customerId)
        .put("payload", p.payload)
        .put("createdAt", p.createdAt)
        .put("attempts", p.attempts)
        .putOpt("lastError", p.lastError)
        .put("status", p.status)

    internal fun toJson(p: PendingCheckin): JSONObject = JSONObject()
        .put("localId", p.localId)
        .put("kind", p.kind)
        .putOpt("customerId", p.customerId)
        .put("note", p.note)
        .putOpt("voicePath", p.voicePath)
        .putOpt("voicePaths", p.voicePaths)
        .putOpt("lat", p.lat)
        .putOpt("lng", p.lng)
        .putOpt("accuracy", p.accuracy)
        .put("createdAt", p.createdAt)
        .put("attempts", p.attempts)
        .putOpt("lastError", p.lastError)
        .put("status", p.status)

    internal fun toJson(l: PendingLocation): JSONObject = JSONObject()
        .put("localId", l.localId)
        .put("lat", l.lat)
        .put("lng", l.lng)
        .putOpt("accuracy", l.accuracy)
        .put("createdAt", l.createdAt)

    internal fun toJson(v: PlannedVisitEntity): JSONObject = JSONObject()
        .put("id", v.id)
        .put("customerId", v.customerId)
        .put("dayKey", v.dayKey)
        .put("timeLocal", v.timeLocal)
        .put("scheduledAtMs", v.scheduledAtMs)
        .put("durationMin", v.durationMin)
        .put("priority", v.priority)
        .put("status", v.status)
        .putOpt("note", v.note)
        .putOpt("visitId", v.visitId)
        .putOpt("visitStartLocal", v.visitStartLocal)
        .put("visitOpen", v.visitOpen)
        .put("overdue", v.overdue)
        .putOpt("customerName", v.customerName)
        .putOpt("customerType", v.customerType)
        .putOpt("customerAddress", v.customerAddress)
        .putOpt("customerPhone", v.customerPhone)
        .put("customerAssigned", v.customerAssigned)

    internal fun toJson(e: OpEventEntity): JSONObject = JSONObject()
        .put("localId", e.localId)
        .put("type", e.type)
        .put("status", e.status)
        .put("createdAt", e.createdAt)
        .putOpt("errorCode", e.errorCode)
        .putOpt("message", e.message)
        .putOpt("relatedType", e.relatedType)
        .putOpt("relatedId", e.relatedId)
        .putOpt("attempt", e.attempt)
}
