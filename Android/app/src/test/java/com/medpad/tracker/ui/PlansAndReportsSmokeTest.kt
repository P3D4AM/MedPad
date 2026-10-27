package com.medpad.tracker.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.R
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.PlannedVisitEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Package 2 (phase 2 / task 3, B6) — owner report: opening «برنامه‌های
 * بازدید» (Plans) or «گزارش‌های من» (Reports) crashes the app.
 *
 * No emulator/KVM exists in this sandbox, so these Robolectric tests launch
 * the REAL activities on the JVM with the REAL Room database and exercise
 * the full render path that runs on screen open:
 *  - Plans: seeded plans for today covering every status (incl. a masked
 *    unassigned customer) → renderDay → chips → adapter binding
 *  - Reports: no server reachable (offline path) → calendar render →
 *    empty-state render
 * A crash anywhere in that path fails the test with the real stack trace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlansAndReportsSmokeTest {

    private lateinit var db: AppDatabase

    @Before
    fun seed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = AppDatabase.get(app)

        val today = todayKeyUtc()
        val plans = listOf(
            // remaining (scheduled) + assigned customer
            plan("p1", today, "09:00", "scheduled", assigned = true, overdue = false),
            // done + linked real visit
            plan("p2", today, "10:30", "done", assigned = true, overdue = false, visitStart = "10:41"),
            // needs_reschedule
            plan("p3", today, "11:00", "needs_reschedule", assigned = true, overdue = false),
            // cancelled
            plan("p4", today, "12:00", "cancelled", assigned = true, overdue = false),
            // overdue scheduled
            plan("p5", today, "07:00", "scheduled", assigned = true, overdue = true),
            // masked customer (no longer assigned) — server sends nulls
            plan("p6", today, "13:00", "scheduled", assigned = false, overdue = false),
        )
        runBlocking { db.plannedVisitDao().upsertAll(plans) }
    }

    @After
    fun cleanup() {
        runBlocking { db.plannedVisitDao().clear() }
    }

    @Test
    fun `plans screen renders all statuses without crashing`() {
        val controller = Robolectric.buildActivity(PlansActivity::class.java)
        // Robolectric 4.12: setup() returns the CONTROLLER — .get() returns the activity
        val activity = controller.setup().get()

        // drive the main looper until the DB-backed render lands (the sync
        // network call fails offline — that is exactly the path we want:
        // error → render from the seeded cache)
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        waitFor(timeoutMs = 15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            recycler.adapter?.itemCount ?: 0
        }

        // 6 plans + 4 section headers (remaining / done / needs-reschedule / cancelled)
        val expectedRows = 6 + 4
        assertEquals(expectedRows, recycler.adapter?.itemCount)
        assertEquals(View.VISIBLE, recycler.visibility)

        // day navigation must not crash either (renderDayLabel → JalaliDate)
        activity.findViewById<android.widget.ImageButton>(R.id.btnNextDay).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.ImageButton>(R.id.btnPrevDay).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.ImageButton>(R.id.btnPrevDay).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        controller.pause().stop().destroy()
    }

    @Test
    fun `reports screen renders calendar and empty state without crashing`() {
        val controller = Robolectric.buildActivity(ReportsActivity::class.java)
        val activity = controller.setup().get()

        // offline → ApiClient.reports() fails → empty list → calendar renders
        val calendarGrid = activity.findViewById<RecyclerView>(R.id.calendarGrid)
        waitFor(timeoutMs = 15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            calendarGrid.adapter?.itemCount ?: 0
        }
        val cells = calendarGrid.adapter?.itemCount ?: 0
        assertTrue("calendar should render ~29-31 cells, got $cells", cells in 29..31)

        val empty = activity.findViewById<TextView>(R.id.tvEmpty)
        assertEquals(View.VISIBLE, empty.visibility) // no server → no reports → empty state

        // month navigation must not crash (renderCalendar → JalaliDate)
        activity.findViewById<android.widget.ImageButton>(R.id.btnNextMonth).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.widget.ImageButton>(R.id.btnPrevMonth).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        controller.pause().stop().destroy()
    }

    // ── helpers ─────────────────────────────────────────────

    private fun plan(
        id: String,
        dayKey: String,
        time: String,
        status: String,
        assigned: Boolean,
        overdue: Boolean,
        visitStart: String? = null,
    ) = PlannedVisitEntity(
        id = id,
        customerId = "c-$id",
        dayKey = dayKey,
        timeLocal = time,
        scheduledAtMs = 0L,
        durationMin = 30,
        priority = if (id == "p1") "high" else "normal",
        status = status,
        note = if (id == "p2") "یادداشت آزمایشی" else null,
        visitId = if (status == "done") "v-$id" else null,
        visitStartLocal = visitStart,
        visitOpen = false,
        overdue = overdue,
        customerName = if (assigned) "داروخانهٔ آزمایشی $id" else null,
        customerType = if (assigned) "pharmacy" else null,
        customerAddress = if (assigned) "نشانی آزمایشی" else null,
        customerPhone = if (assigned) "02112345678" else null,
        customerAssigned = assigned,
    )

    /** The same device-local day key the activity anchors on (UTC fields). */
    private fun todayKeyUtc(): String {
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Poll until the predicate returns non-zero (or fail on timeout). */
    private fun waitFor(timeoutMs: Long, check: () -> Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = 0
        while (System.currentTimeMillis() < deadline) {
            last = check()
            if (last > 0) return last
            Thread.sleep(100)
        }
        return last
    }
}
