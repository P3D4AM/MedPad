package com.medpad.tracker.ui

/**
 * Jalali (Persian) calendar conversion — clean jalaali-js algorithm port.
 * All outputs 1-based months/days.
 */
object JalaliDate {

    data class J(
        val year: Int,
        val month: Int, // 1..12
        val day: Int,   // 1..31
    )

    private fun div(a: Int, b: Int) = a / b

    fun isLeapJalali(year: Int): Boolean {
        val mod = mod(year, 33)
        return mod in intArrayOf(1, 5, 9, 13, 17, 22, 26, 30)
    }

    private fun mod(a: Int, b: Int): Int {
        val r = a % b
        return if (r < 0) r + b else r
    }

    private fun jalCal(jy: Int): IntArray {
        val breaks = intArrayOf(-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178)
        val gy = jy + 621
        var leapJ = -14
        var jp = breaks[0]
        var jump = 0
        for (i in 1 until breaks.size) {
            val jm = breaks[i]
            jump = jm - jp
            if (jy < jm) break
            val k = jy - jp
            leapJ += div(k, 33) * 8 + div(mod(k, 33) + 3, 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1
        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return intArrayOf(leap, gy, march)
    }

    private fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) + div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
        return d
    }

    private fun d2j(jdn: Int): IntArray {
        val gy0 = d2g(jdn).let { it[0] } // hmm — use jalCal path below
        var jy = gy0 - 621
        val r = jalCal(jy)
        val jdn1f = g2d(r[1], 3, r[2])
        var k = jdn - jdn1f
        when {
            k >= 0 -> {
                if (k <= 185) return intArrayOf(jy, 1 + div(k, 31), mod(k, 31) + 1)
                k -= 186
            }
            else -> {
                jy -= 1
                k += 179
                if (r[0] == 1) k += 1
            }
        }
        return intArrayOf(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    private fun d2g(jdn: Int): IntArray {
        var j = 4 * jdn + 139361631
        j = j + div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(mod(j, 1461), 4) * 5 + 308
        val gd = div(mod(i, 153), 5) + 1
        val gm = mod(div(i, 153), 12) + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return intArrayOf(gy, gm, gd)
    }

    /** Gregorian (from java.util.Calendar fields) → Jalali */
    fun fromGregorian(gy: Int, gm: Int, gd: Int): J {
        val jdn = g2d(gy, gm, gd)
        val r = jalCal(gy - 621)
        val jdn1f = g2d(r[1], 3, r[2])
        var k = jdn - jdn1f
        var jy = gy - 621
        when {
            k >= 0 -> {
                if (k <= 185) return J(jy, 1 + div(k, 31), mod(k, 31) + 1)
                k -= 186
            }
            else -> {
                jy -= 1
                k += 179
                if (r[0] == 1) k += 1
            }
        }
        return J(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    /** Jalali → Gregorian (gy, gm, gd) */
    fun toGregorian(jy: Int, jm: Int, jd: Int): IntArray {
        val r = jalCal(jy)
        val days = daysInMonth(jy, jm)
        var jdn = g2d(r[1], 3, r[2])
        jdn += (jm - 1) * 31 - div(jm, 7) * (jm - 7)
        jdn += jd - 1
        return d2g(jdn)
    }

    fun monthLength(jy: Int, jm: Int): Int = daysInMonth(jy, jm)

    private fun daysInMonth(jy: Int, jm: Int): Int {
        return when {
            jm <= 6 -> 31
            jm <= 11 -> 30
            isLeapJalali(jy) -> 30
            else -> 29
        }
    }

    val MONTH_NAMES = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    val WEEK_DAYS = arrayOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")

    fun formatFa(j: J): String = "%d %s %d".format(j.day, MONTH_NAMES[j.month - 1], j.year)
}
