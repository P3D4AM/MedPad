package com.medpad.tracker.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.api.ApiClient.HttpException
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.PendingLocation
import com.medpad.tracker.location.TrackingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * phase 3 — upload flush for the local location queue.
 *
 * Durability contract (mirrors the check-in queue, CheckinSync):
 *  - rows leave the device ONLY after the server confirmed the batch (200),
 *    or after a PERMANENT 400 rejection (structurally invalid data — the
 *    same fields would be rejected by every MedPad server, so retrying
 *    forever would only grow the queue; the drop is counted honestly in
 *    lastDropCount).
 *  - TRANSIENT failures (network error / 5xx / 429) keep every row —
 *    internet cuts never silently lose location data.
 *  - 401 (token expired/revoked) keeps the rows and raises the shared
 *    sessionExpired signal; the queue is wiped only on explicit logout /
 *    server change to a different database (where it belongs to the OLD
 *    server and can never be uploaded).
 *  - crash-safety: ids are persisted BEFORE the request (assignLocalId);
 *    a replay after a crash mid-upload hits the server's localId UNIQUE
 *    index and is counted as duplicated — never stored twice.
 */
object LocationSync {

    /** dropped rows in the LAST permanently-rejected batch (honest counter;
     *  surfaced by the queue UI / diagnostics, never a silent loss) */
    @Volatile var lastDropCount: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    @Volatile private var inFlight = false

    /** last successful upload size + server verdicts (diagnostics) */
    @Volatile var lastAccepted: Int = 0
    @Volatile var lastDuplicated: Int = 0

    suspend fun flushOnce(context: Context): Unit = withContext(Dispatchers.IO) {
        if (inFlight) return@withContext
        if (!Session.isLoggedIn) return@withContext
        inFlight = true
        try {
            val dao = AppDatabase.get(context).pendingLocationDao()
            val batch = dao.oldest(TrackingConfig.current.batchSize.coerceAtMost(200))
            if (batch.isEmpty()) return@withContext

            // legacy rows (pre-dev.5) have localId == "" — assign the
            // idempotency id PERSISTENTLY before the request (crash-safe)
            val ids = batch.map { row ->
                if (row.localId.isEmpty()) {
                    val fresh = UUID.randomUUID().toString()
                    dao.assignLocalId(row.uid, fresh)
                    fresh
                } else row.localId
            }

            val payload = buildPayload(batch, ids, statusSnapshot(context))

            try {
                val res = ApiClient.uploadLocations(payload)
                lastAccepted = res.optInt("accepted", 0)
                lastDuplicated = res.optInt("duplicated", 0)
                // confirmed (or safely-replayed) — remove from the device
                dao.deleteByUids(batch.map { it.uid })
                lastError = null
                // every upload response doubles as a config refresh
                TrackingConfig.updateFrom(res)
            } catch (e: HttpException) {
                when (e.code) {
                    400 -> {
                        // permanently invalid (validated-whole-batch server
                        // policy): the same data would be rejected by any
                        // MedPad panel — drop it, but count it honestly
                        dao.deleteByUids(batch.map { it.uid })
                        lastDropCount = batch.size
                        lastError = e.message
                    }
                    401 -> {
                        CheckinSync.markSessionExpired()
                        lastError = "نشست منقضی شده است"
                    }
                    else -> lastError = "HTTP ${e.code}" // 5xx/429 — keep, retry later
                }
            }
        } catch (_: Exception) {
            // network failure — keep the queue intact
        } finally {
            inFlight = false
        }
    }

    /** wire-format builder — pure and unit-tested: localId/lat/lng always,
     *  accuracy ONLY when known (server discards impossible values), the
     *  device clock as ISO-8601 recordedAt, plus the status snapshot. */
    internal fun buildPayload(
        rows: List<PendingLocation>,
        ids: List<String>,
        status: JSONObject,
    ): JSONObject {
        val arr = JSONArray()
        for (i in rows.indices) {
            arr.put(
                JSONObject()
                    .put("localId", ids[i])
                    .put("lat", rows[i].lat)
                    .put("lng", rows[i].lng)
                    .apply { rows[i].accuracy?.let { put("accuracy", it) } }
                    .put("recordedAt", ApiClient.isoTime(rows[i].createdAt)),
            )
        }
        return JSONObject().put("locations", arr).put("status", status)
    }

    /** device health snapshot reported with every batch (phase 3 §3). All
     *  values are FACTS the device can prove — the panel renders them with
     *  Persian labels, unknown stays unknown. */
    fun statusSnapshot(context: Context): JSONObject {
        fun granted(perm: String) =
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val perm = when {
            fine -> "fine"
            coarse -> "coarse"
            else -> "denied"
        }
        // background permission exists from API 29; we do not REQUEST it
        // (the foreground service pattern runs on while-in-use access) but
        // we REPORT its true state so the panel can advise the admin.
        val bg = if (Build.VERSION.SDK_INT >= 29) {
            if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "granted" else "denied"
        } else "unavailable"
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { null }
        return JSONObject()
            .put("locationPermission", perm)
            .put("backgroundLocation", bg)
            .put("serviceRunning", true) // the tracker service is the reporter
            .put("batteryUnrestricted", batteryUnrestricted)
            .apply { version?.let { put("appVersion", it) } }
    }

    /** wipe the queue — ONLY for logout / server-change-to-another-DB */
    suspend fun clearQueue(context: Context) {
        withContext(Dispatchers.IO) {
            AppDatabase.get(context).pendingLocationDao().clear()
        }
    }

    /** pending count (diagnostics / UI) */
    suspend fun pendingCount(context: Context): Int =
        withContext(Dispatchers.IO) { AppDatabase.get(context).pendingLocationDao().count() }
}
