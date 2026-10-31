package com.medpad.tracker.ui

import com.medpad.tracker.db.PlannedVisitEntity

/**
 * Mission item 8 (Oct 31) — compare the check-in moment with the visit
 * PLAN for the selected customer.
 *
 * The window is EXACTLY the server's linkage semantics — no new tolerance
 * is invented (the mission: «اگر تعریف فعلی پروژه برای تلورانس زمانی
 * برنامه وجود دارد، آن را رعایت کن» — there is none; the server links a
 * plan by same-DAY + completes it, and the plan's own duration defines its
 * window):
 *
 *   [scheduledAt … scheduledAt + durationMin]
 *   before the window  → EARLY  (minutes until the window opens)
 *   inside the window  → ON_TIME
 *   after the window   → LATE   (minutes since the window closed)
 *
 * A customer with NO scheduled plan today is explicitly PLAN-LESS — the
 * caller must show «این چک‌این بدون برنامه ثبت می‌شود» and NEVER compute a
 * deviation for a plan that does not exist.
 */
object PlanWindow {

    enum class Status { EARLY, ON_TIME, LATE }

    data class Assessment(
        val status: Status,
        /** EARLY: minutes UNTIL the window opens; LATE: minutes SINCE the
         *  window closed; ON_TIME: 0. Long to stay honest on clock skews. */
        val diffMinutes: Long,
        val planStartMs: Long,
        val planEndMs: Long,
    )

    fun assess(nowMs: Long, planStartMs: Long, durationMin: Int): Assessment {
        val end = planStartMs + durationMin * 60_000L
        return when {
            nowMs < planStartMs -> Assessment(Status.EARLY, (planStartMs - nowMs) / 60_000, planStartMs, end)
            nowMs <= end -> Assessment(Status.ON_TIME, 0, planStartMs, end)
            else -> Assessment(Status.LATE, (nowMs - end) / 60_000, planStartMs, end)
        }
    }

    /**
     * Today's still-scheduled plan for ONE customer (the dialog's spinner
     * selection). Deterministic: the EARLIEST unlinked scheduled plan of
     * that customer on [todayKey] — the same plan the server's linkage
     * would complete (it links earliest-first).
     */
    fun findPlan(plans: List<PlannedVisitEntity>, customerId: String, todayKey: String): PlannedVisitEntity? =
        plans
            .filter { it.customerId == customerId && it.status == "scheduled" && it.dayKey == todayKey }
            .minByOrNull { it.scheduledAtMs }
}
