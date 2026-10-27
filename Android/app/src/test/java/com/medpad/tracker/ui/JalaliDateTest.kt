package com.medpad.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Package 2 (phase 2 / task 3, B6): the Jalali calendar math is on the hot
 * path of BOTH crashing screens — ReportsActivity.renderCalendar indexes
 * MONTH_NAMES[calMonth - 1] and PlansActivity.renderDayLabel indexes
 * WEEK_DAYS[...]. An out-of-range month/day from the conversion algorithm
 * would be an instant ArrayIndexOutOfBounds crash on every render. This test
 * walks EVERY day across a wide date range and asserts the full contract.
 */
class JalaliDateTest {

    private fun gregorian(y: Int, m: Int, d: Int): Calendar =
        GregorianCalendar(y, m - 1, d).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

    @Test
    fun `every day of 2024-2027 converts within valid ranges`() {
        var checked = 0
        val cal = gregorian(2024, 1, 1)
        val end = gregorian(2027, 12, 31).timeInMillis
        while (cal.timeInMillis <= end) {
            val j = JalaliDate.fromGregorian(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
            // contract: these index MONTH_NAMES[j.month - 1] and are rendered
            // directly — any violation crashes Reports/Plans on open
            assertTrue("month out of range: ${j.month} (g=${cal.time})", j.month in 1..12)
            assertTrue("day out of range: ${j.day} (g=${cal.time})", j.day in 1..31)
            assertTrue("year unreasonable: ${j.year}", j.year in 1400..1500)
            assertTrue(
                "day exceeds month length: ${j.year}-${j.month}-${j.day}",
                j.day <= JalaliDate.monthLength(j.year, j.month),
            )
            checked++
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        assertTrue("suspiciously few days checked: $checked", checked >= 1460)
    }

    @Test
    fun `round trip gregorian to jalali and back is identity`() {
        val cal = gregorian(2025, 3, 20) // Nowruz boundary area
        repeat(800) {
            val gy = cal.get(Calendar.YEAR)
            val gm = cal.get(Calendar.MONTH) + 1
            val gd = cal.get(Calendar.DAY_OF_MONTH)
            val j = JalaliDate.fromGregorian(gy, gm, gd)
            val back = JalaliDate.toGregorian(j.year, j.month, j.day)
            assertEquals("round trip failed for $gy-$gm-$gd -> $j -> ${back.contentToString()}", gy, back[0])
            assertEquals(gm, back[1])
            assertEquals(gd, back[2])
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    @Test
    fun `known dates convert correctly`() {
        // 2026-10-28 = 1405-08-06 (Aban) — Farvardin 1, 1405 = 2026-03-21
        val j1 = JalaliDate.fromGregorian(2026, 10, 28)
        assertEquals(1405, j1.year)
        assertEquals(8, j1.month)
        assertEquals(6, j1.day)
        // Nowruz: 2026-03-21 = 1405-01-01
        val j2 = JalaliDate.fromGregorian(2026, 3, 21)
        assertEquals(1405, j2.year)
        assertEquals(1, j2.month)
        assertEquals(1, j2.day)
        // day before Nowruz belongs to the previous Jalali year: 1404-12-29/30
        val j3 = JalaliDate.fromGregorian(2026, 3, 20)
        assertEquals(1404, j3.year)
        assertEquals(12, j3.month)
        // formatFa uses MONTH_NAMES[j.month - 1] — must not throw
        assertTrue(JalaliDate.formatFa(j1).contains("آبان"))
    }

    @Test
    fun `weekday mapping stays in bounds for all seven days`() {
        // PlansActivity.renderDayLabel: WEEK_DAYS[(DAY_OF_WEEK - SATURDAY + 7) % 7]
        val cal = gregorian(2026, 10, 24) // a Saturday
        repeat(7) {
            val idx = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7
            assertTrue("weekday index out of range: $idx", idx in JalaliDate.WEEK_DAYS.indices)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}
