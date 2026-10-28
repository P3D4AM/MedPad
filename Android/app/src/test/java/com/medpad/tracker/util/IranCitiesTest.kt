package com.medpad.tracker.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * package 8 — offline Iran cities dataset (assets/iran-cities.json).
 * Verifies the bundled asset the city picker depends on: loads, has the
 * full 31-province / 451-city master shape, unique (province, city) pairs,
 * coordinates inside Iran's bounding box, and the Persian-tolerant search
 * used by both the picker and the edit-dialog echo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IranCitiesTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `dataset loads with full shape`() {
        val all = IranCities.load(app)
        assertEquals(31, all.map { it.province }.distinct().size)
        assertEquals(451, all.size)
    }

    @Test
    fun `province-city pairs are unique`() {
        val all = IranCities.load(app)
        assertEquals(all.size, all.map { it.province to it.city }.distinct().size)
    }

    @Test
    fun `same-name cities exist in different provinces`() {
        // نیر is a real city in BOTH اردبیل and یزد — the PAIR is the key.
        // (The misattributed duplicates کرج/تهران، سلطانیه/سیستان،
        // کازرون/بوشهر، فسا/اصفهان were cleaned from the dataset.)
        val neyr = IranCities.search(app, "نیر")
        assertTrue(neyr.any { it.city == "نیر" && it.province == "اردبیل" })
        assertTrue(neyr.any { it.city == "نیر" && it.province == "یزد" })
        // substring search is INTENDED: نی‌ریز (فارس) also matches «نیر»
        assertTrue(neyr.any { it.city == "نی‌ریز" && it.province == "فارس" })
        assertNull(IranCities.find(app, "کرج", "تهران")) // removed (belongs to البرز)
        assertEquals("البرز", IranCities.find(app, "کرج", "البرز")?.province)
    }

    @Test
    fun `coordinates are inside Iran bounding box`() {
        val all = IranCities.load(app)
        all.forEach {
            assertTrue("lat ${it.lat}", it.lat in 24.0..41.0)
            assertTrue("lng ${it.lng}", it.lng in 43.0..64.0)
        }
    }

    @Test
    fun `find matches exact pairs and tolerates zero-width chars`() {
        val hit = IranCities.find(app, "رباط‌کریم", "تهران") // with ZWNJ (U+200C)
        assertEquals("رباط‌کریم", hit?.city)
        val stripped = IranCities.find(app, "رباطکریم", "تهران") // same letters (ط), ZWNJ removed
        assertEquals("رباط‌کریم", stripped?.city)
        assertNull(IranCities.find(app, "نیست‌چنین‌شهری", "تهران"))
    }

    @Test
    fun `search matches city and province names`() {
        val byCity = IranCities.search(app, "شیراز")
        assertTrue(byCity.any { it.city == "شیراز" && it.province == "فارس" })
        val byProvince = IranCities.search(app, "یزد")
        assertTrue(byProvince.any { it.province == "یزد" })
        assertTrue(IranCities.search(app, "ززززز").isEmpty())
    }

    @Test
    fun `normalize strips zero-width, maps Arabic chars and Persian digits`() {
        assertEquals("ی ک", IranCities.normalize("ي ك")) // Arabic ي/ك → Persian
        assertEquals("رباطکریم", IranCities.normalize("رباط‌کریم")) // ZWNJ gone
        assertEquals("1234", IranCities.normalize("۱۲۳۴")) // Persian digits
        assertEquals("نیر", IranCities.normalize("  نیر  ")) // trimmed
        assertEquals("نیر", IranCities.normalize("نیر"))
    }
}
