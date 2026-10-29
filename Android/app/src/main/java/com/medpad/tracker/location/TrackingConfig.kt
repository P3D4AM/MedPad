package com.medpad.tracker.location

import android.content.Context
import com.medpad.tracker.TrackerApp
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * phase 3 — remote tracking policy, held on the device.
 *
 * Source of truth is the PANEL (admin settings; /api/app/tracking-config
 * and the config object carried on every upload response). This holder:
 *  - keeps the LAST VALID config (persisted to prefs — survives restarts
 *    AND offline periods: «اپ آخرین تنظیمات معتبر را برای حالت آفلاین
 *    حفظ کند»),
 *  - validates + clamps EVERY field to the same ranges the server enforces
 *    (a corrupt/garbage response never widens policy — e.g. a 1-second
 *    interval that would hammer the GPS),
 *  - is thread-safe: the service reads it from multiple coroutines while
 *    a response updates it.
 *
 * The values are TARGETS honoured while the OS allows it — Android doze /
 * battery savers may stretch the real interval; the app never promises the
 * panel a guaranteed GPS rate (and the panel says so).
 */
object TrackingConfig {

    data class Config(
        val enabled: Boolean,
        val moveIntervalSec: Int,
        val stillIntervalSec: Int,
        val motionThresholdM: Int,
        val maxAgeSec: Int,
        val batchSize: Int,
    )

    /** server defaults — also the device-side fallback when nothing valid
     *  was ever fetched (first run before the first config response) */
    val DEFAULTS = Config(
        enabled = true,
        moveIntervalSec = 45,
        stillIntervalSec = 240,
        motionThresholdM = 40,
        maxAgeSec = 900,
        batchSize = 200,
    )

    private const val PREFS = "medpad_tracking"
    private const val K_ENABLED = "enabled"
    private const val K_MOVE = "moveIntervalSec"
    private const val K_STILL = "stillIntervalSec"
    private const val K_MOTION = "motionThresholdM"
    private const val K_MAXAGE = "maxAgeSec"
    private const val K_BATCH = "batchSize"

    @Volatile var current: Config = DEFAULTS
        internal set // tests simulate a process restart by resetting to DEFAULTS

    private val lock = ReentrantLock()

    /** load the last valid persisted config (called once at process start) */
    fun load() {
        val p = TrackerApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(K_MOVE)) return // never persisted — defaults stand
        current = Config(
            enabled = p.getBoolean(K_ENABLED, DEFAULTS.enabled),
            moveIntervalSec = p.getInt(K_MOVE, DEFAULTS.moveIntervalSec),
            stillIntervalSec = p.getInt(K_STILL, DEFAULTS.stillIntervalSec),
            motionThresholdM = p.getInt(K_MOTION, DEFAULTS.motionThresholdM),
            maxAgeSec = p.getInt(K_MAXAGE, DEFAULTS.maxAgeSec),
            batchSize = p.getInt(K_BATCH, DEFAULTS.batchSize),
        )
    }

    /**
     * Adopt a server response (from /api/app/tracking-config or the config
     * carried on an upload response). Returns the adopted config, or NULL
     * when the payload is not a valid config object (caller keeps current).
     * Field-by-field clamping: one bad field never discards the rest.
     */
    fun updateFrom(json: JSONObject?): Config? {
        val cfg = json?.optJSONObject("config") ?: json ?: return null
        if (!cfg.has("moveIntervalSec") && !cfg.has("stillIntervalSec")) return null
        val next = Config(
            enabled = if (cfg.has("enabled")) cfg.optBoolean("enabled", DEFAULTS.enabled) else current.enabled,
            moveIntervalSec = clamp(cfg.optInt("moveIntervalSec", current.moveIntervalSec), 15, 600),
            stillIntervalSec = clamp(cfg.optInt("stillIntervalSec", current.stillIntervalSec), 60, 3600),
            motionThresholdM = clamp(cfg.optInt("motionThresholdM", current.motionThresholdM), 5, 500),
            maxAgeSec = clamp(cfg.optInt("maxAgeSec", current.maxAgeSec), 60, 21600),
            batchSize = clamp(cfg.optInt("batchSize", current.batchSize), 1, 500),
        )
        lock.withLock {
            current = next
            val ctx = TrackerApp.instance
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_ENABLED, next.enabled)
                .putInt(K_MOVE, next.moveIntervalSec)
                .putInt(K_STILL, next.stillIntervalSec)
                .putInt(K_MOTION, next.motionThresholdM)
                .putInt(K_MAXAGE, next.maxAgeSec)
                .putInt(K_BATCH, next.batchSize)
                .apply()
        }
        return next
    }

    private fun clamp(v: Int, min: Int, max: Int): Int = if (v < min) min else if (v > max) max else v
}
