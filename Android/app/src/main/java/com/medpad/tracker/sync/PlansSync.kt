package com.medpad.tracker.sync

import android.content.Context
import androidx.room.withTransaction
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.api.ApiClient.HttpException
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.PlannedVisitEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * phase 2 / task 2 — planned-visits sync (READ-ONLY cache).
 *
 * Fetches the employee's own plans for a ±window around today and REPLACES
 * the local table in one Room transaction. The check-in offline queue is a
 * different table touched by a different object — a plans failure can
 * never touch pending_checkins (verified by construction: this object only
 * ever calls plannedVisitDao()).
 *
 * Timezone policy: plan times are SERVER-local by definition (the same
 * clock that groups days for the check-in→plan linkage). The server sends
 * precomputed mirrors (dayKey / timeLocal / visitStartLocal) which the app
 * renders verbatim. The window request is anchored on the DEVICE's today
 * with extra margin (8 back / 15 ahead), so a ±1 day device↔server skew
 * can never push the server's today outside the fetched window; the
 * server-returned window.today is then used as the canonical "today".
 */
object PlansSync {

    private const val PREFS = "medpad_plans"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    private const val KEY_SERVER_TODAY = "server_today"

    /** How far back / ahead the sync window reaches (device-anchored). */
    const val WINDOW_BACK_DAYS = 8
    const val WINDOW_AHEAD_DAYS = 15

    sealed class Result {
        object Success : Result()
        object AuthExpired : Result()
        data class Error(val message: String?) : Result()
    }

    fun lastSyncMs(): Long =
        TrackerContext.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0L)

    /** Server-local "today" (YYYY-MM-DD) as reported by the last successful
     *  sync — the canonical reference for day navigation. Empty before the
     *  first sync. */
    fun serverToday(): String =
        TrackerContext.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_TODAY, "") ?: ""

    /** Device-local day key ± offset days (pure calendar math on UTC fields —
     *  no wall-clock timezone dependence). */
    fun deviceDayKey(offsetDays: Int): String {
        val cal = GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_MONTH, offsetDays)
        return formatDayKey(cal)
    }

    /** dayKey + N days → dayKey (null when the input is malformed). */
    fun dayKeyPlus(dayKey: String, days: Int): String? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(dayKey) ?: return null
        val (y, mo, d) = m.destructured
        val cal = GregorianCalendar(
            y.toInt(), mo.toInt() - 1, d.toInt(),
        )
        cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
        cal.add(Calendar.DAY_OF_MONTH, days)
        return formatDayKey(cal)
    }

    private fun formatDayKey(cal: GregorianCalendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
    )

    /** Sync now: device-today-8 … device-today+15 (inclusive). */
    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val from = deviceDayKey(-WINDOW_BACK_DAYS)
        val to = deviceDayKey(WINDOW_AHEAD_DAYS)
        try {
            val resp = ApiClient.plannedVisits(from, to)
            if (!resp.optBoolean("ok")) {
                return@withContext Result.Error(resp.optString("error", "").ifEmpty { "server said not ok" })
            }

            val arr: JSONArray = resp.optJSONArray("plans") ?: JSONArray()
            val list = ArrayList<PlannedVisitEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cust = o.optJSONObject("customer")
                fun nullable(key: String): String? = if (o.isNull(key)) null else o.optString(key).ifEmpty { null }
                list.add(
                    PlannedVisitEntity(
                        id = o.getString("id"),
                        customerId = o.getString("customerId"),
                        dayKey = o.optString("dayKey", ""),
                        timeLocal = o.optString("timeLocal", "--:--"),
                        scheduledAtMs = parseIsoMs(o.optString("scheduledAt")),
                        durationMin = o.optInt("durationMin", 30),
                        priority = o.optString("priority", "normal"),
                        status = o.optString("status", "scheduled"),
                        note = nullable("note"),
                        visitId = nullable("visitId"),
                        visitStartLocal = if (o.isNull("visitStartLocal")) null else o.optString("visitStartLocal", null),
                        visitOpen = o.optBoolean("visitOpen", false),
                        overdue = o.optBoolean("overdue", false),
                        customerName = if (cust == null || cust.isNull("name")) null else cust.optString("name"),
                        customerType = if (cust == null || cust.isNull("type")) null else cust.optString("type"),
                        customerAddress = if (cust == null || cust.isNull("address")) null else cust.optString("address").ifEmpty { null },
                        customerPhone = if (cust == null || cust.isNull("phone")) null else cust.optString("phone").ifEmpty { null },
                        customerAssigned = cust?.optBoolean("assigned", false) ?: false,
                    )
                )
            }

            // Atomic full replace — the server window is complete truth for
            // these days; there is no local edit to preserve (read-only cache).
            val db = AppDatabase.get(TrackerContext.app)
            db.withTransaction {
                db.plannedVisitDao().clear()
                db.plannedVisitDao().upsertAll(list)
            }

            val serverToday = resp.optJSONObject("window")?.optString("today") ?: ""
            TrackerContext.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(KEY_SERVER_TODAY, serverToday)
                .apply()

            CheckinSync.markSessionAlive() // a successful authed call proves the token works
            Result.Success
        } catch (e: HttpException) {
            if (e.code == 401) {
                CheckinSync.markSessionExpired()
                Result.AuthExpired
            } else {
                Result.Error(e.message)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "network error")
        }
    }

    private fun parseIsoMs(s: String): Long = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}
