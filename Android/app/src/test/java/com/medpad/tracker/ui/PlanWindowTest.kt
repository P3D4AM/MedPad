package com.medpad.tracker.ui

import com.medpad.tracker.db.PlannedVisitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mission item 8 — the plan-window assessment used by the check-in dialog.
 * Window semantics mirror the server's linkage exactly (scheduledAt …
 * scheduledAt + durationMin); no tolerance is invented.
 */
class PlanWindowTest {

    private val start = 1_780_000_000_000L // fixed epoch
    private val min = 60_000L

    private fun plan(
        customerId: String = "c1",
        dayKey: String = "2026-10-31",
        scheduledAtMs: Long = start,
        status: String = "scheduled",
    ) = PlannedVisitEntity(
        id = "p1", customerId = customerId, dayKey = dayKey, timeLocal = "10:00",
        scheduledAtMs = scheduledAtMs, durationMin = 30, priority = "normal",
        status = status, customerAssigned = true,
    )

    @Test
    fun `early - before the window`() {
        val a = PlanWindow.assess(start - 15 * min, start, 30)
        assertEquals(PlanWindow.Status.EARLY, a.status)
        assertEquals(15L, a.diffMinutes)
    }

    @Test
    fun `on time - anywhere inside the window including both edges`() {
        assertEquals(PlanWindow.Status.ON_TIME, PlanWindow.assess(start, start, 30).status)
        assertEquals(PlanWindow.Status.ON_TIME, PlanWindow.assess(start + 29 * min, start, 30).status)
        assertEquals(PlanWindow.Status.ON_TIME, PlanWindow.assess(start + 30 * min, start, 30).status)
        assertEquals(0L, PlanWindow.assess(start + 10 * min, start, 30).diffMinutes)
    }

    @Test
    fun `late - after the window`() {
        val a = PlanWindow.assess(start + 45 * min, start, 30)
        assertEquals(PlanWindow.Status.LATE, a.status)
        assertEquals(15L, a.diffMinutes)
    }

    @Test
    fun `find plan picks the earliest unlinked scheduled plan of the customer today`() {
        val today = "2026-10-31"
        val p1 = plan(scheduledAtMs = start)
        val p2 = plan(scheduledAtMs = start + 2 * 60 * min)
        val found = PlanWindow.findPlan(listOf(p2, p1), "c1", today)
        assertEquals(start, found?.scheduledAtMs)
    }

    @Test
    fun `other customers other days and non-scheduled plans never match`() {
        val today = "2026-10-31"
        assertNull(PlanWindow.findPlan(listOf(plan(customerId = "c2")), "c1", today))
        assertNull(PlanWindow.findPlan(listOf(plan(dayKey = "2026-11-01")), "c1", today))
        assertNull(PlanWindow.findPlan(listOf(plan(status = "done")), "c1", today))
        assertNull(PlanWindow.findPlan(listOf(plan(status = "cancelled")), "c1", today))
        assertNull(PlanWindow.findPlan(emptyList(), "c1", today))
    }
}
