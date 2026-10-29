package com.medpad.tracker.location

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * phase 3 — TrackingConfig (remote tracking policy on the device).
 *
 * Contract under test:
 *  - server defaults apply until a valid response arrives;
 *  - EVERY field is clamped to the server's permitted range — a corrupt or
 *    malicious response can never widen policy (e.g. a 1-second interval);
 *  - an invalid payload returns null and keeps the last valid config;
 *  - the last valid config persists across process restarts (load()) —
 *    «اپ آخرین تنظیمات معتبر را برای حالت آفلاین حفظ کند»;
 *  - the enabled flag propagates (panel on/off reaches the device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingConfigTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        // wipe persisted state so every test starts from the defaults
        app.getSharedPreferences("medpad_tracking", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        TrackingConfig.current = TrackingConfig.DEFAULTS
        TrackingConfig.load()
    }

    private fun configJson(
        enabled: Boolean = true,
        move: Int = 45,
        still: Int = 240,
        motion: Int = 40,
        maxAge: Int = 900,
        batch: Int = 200,
    ): JSONObject = JSONObject(
        """{"config":{"enabled":$enabled,"moveIntervalSec":$move,"stillIntervalSec":$still,
           "motionThresholdM":$motion,"maxAgeSec":$maxAge,"batchSize":$batch}}""".trimIndent(),
    )

    @Test
    fun `defaults apply before any fetch`() {
        assertEquals(TrackingConfig.DEFAULTS, TrackingConfig.current)
        assertTrue(TrackingConfig.current.enabled)
        assertEquals(45, TrackingConfig.current.moveIntervalSec)
        assertEquals(240, TrackingConfig.current.stillIntervalSec)
        assertEquals(40, TrackingConfig.current.motionThresholdM)
        assertEquals(900, TrackingConfig.current.maxAgeSec)
    }

    @Test
    fun `valid response is adopted`() {
        val adopted = TrackingConfig.updateFrom(configJson(move = 60, still = 300, motion = 25, maxAge = 600, batch = 50))
        assertNotNull(adopted)
        assertEquals(60, adopted!!.moveIntervalSec)
        assertEquals(300, adopted.stillIntervalSec)
        assertEquals(25, adopted.motionThresholdM)
        assertEquals(600, adopted.maxAgeSec)
        assertEquals(50, adopted.batchSize)
        assertEquals(adopted, TrackingConfig.current)
    }

    @Test
    fun `out-of-range values are clamped to the server ranges`() {
        val adopted = TrackingConfig.updateFrom(
            configJson(move = 1, still = 99999, motion = 0, maxAge = 1, batch = 100000),
        )!!
        assertEquals(15, adopted.moveIntervalSec)      // min 15
        assertEquals(3600, adopted.stillIntervalSec)   // max 3600
        assertEquals(5, adopted.motionThresholdM)      // min 5
        assertEquals(60, adopted.maxAgeSec)            // min 60
        assertEquals(500, adopted.batchSize)           // max 500 (device-side cap)
    }

    @Test
    fun `garbage payload keeps the last valid config`() {
        TrackingConfig.updateFrom(configJson(move = 60))
        assertEquals(60, TrackingConfig.current.moveIntervalSec)

        assertNull("null payload rejected", TrackingConfig.updateFrom(null))
        assertNull("empty object rejected", TrackingConfig.updateFrom(JSONObject()))
        assertNull("unrelated object rejected", TrackingConfig.updateFrom(JSONObject().put("hello", "world")))
        assertEquals("last valid kept", 60, TrackingConfig.current.moveIntervalSec)
    }

    @Test
    fun `partial field updates keep the rest`() {
        TrackingConfig.updateFrom(configJson(move = 60, still = 300))
        // a later response only carries the interval (config piggy-back on
        // upload responses may be partial in future panel versions)
        val adopted = TrackingConfig.updateFrom(
            JSONObject("""{"config":{"moveIntervalSec":90}}"""),
        )
        assertNotNull(adopted)
        assertEquals(90, adopted!!.moveIntervalSec)
        assertEquals("still kept", 300, adopted.stillIntervalSec)
    }

    @Test
    fun `disable and re-enable propagate`() {
        val off = TrackingConfig.updateFrom(configJson(enabled = false))!!
        assertFalse(off.enabled)
        assertFalse(TrackingConfig.current.enabled)

        val on = TrackingConfig.updateFrom(configJson(enabled = true))!!
        assertTrue(on.enabled)
    }

    @Test
    fun `last valid config survives a process restart`() {
        TrackingConfig.updateFrom(configJson(move = 77, still = 333, motion = 22, maxAge = 444, enabled = false))

        // simulate a restart: object resets to defaults, then load() reads
        // the persisted policy (this is exactly what TrackerApp.onCreate does)
        TrackingConfig.current = TrackingConfig.DEFAULTS
        TrackingConfig.load()

        assertEquals(77, TrackingConfig.current.moveIntervalSec)
        assertEquals(333, TrackingConfig.current.stillIntervalSec)
        assertEquals(22, TrackingConfig.current.motionThresholdM)
        assertEquals(444, TrackingConfig.current.maxAgeSec)
        assertFalse(TrackingConfig.current.enabled)
    }

    @Test
    fun `upload-response shape is accepted too (config piggy-back)`() {
        // the upload response nests config at the TOP level: {ok, accepted, duplicated, config}
        val res = JSONObject(
            """{"ok":true,"accepted":3,"duplicated":0,
               "config":{"enabled":true,"moveIntervalSec":120,"stillIntervalSec":600,
                         "motionThresholdM":60,"maxAgeSec":1200,"batchSize":200}}""",
        )
        val adopted = TrackingConfig.updateFrom(res)
        assertNotNull(adopted)
        assertEquals(120, adopted!!.moveIntervalSec)
        assertEquals(600, adopted.stillIntervalSec)
    }
}
