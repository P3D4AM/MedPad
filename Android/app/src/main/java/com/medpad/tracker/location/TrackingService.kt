package com.medpad.tracker.location

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.TrackerApp
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.PendingLocation
import com.medpad.tracker.location.DeviceGps
import com.medpad.tracker.sync.AppNotifier
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.LocationSync
import com.medpad.tracker.sync.ServerNotifications
import com.medpad.tracker.util.NotificationPrefs
import com.medpad.tracker.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Foreground tracking service — phase 3 (dev.5).
 *
 *  - location requests driven by the REMOTE policy (TrackingConfig): target
 *    interval while MOVING / while STATIONARY with a distance-based motion
 *    threshold; re-issued whenever the motion state flips.
 *  - every fix is stored locally as an upload row (UUID idempotency key +
 *    accuracy) and uploaded in batches by LocationSync — unsent fixes are
 *    NEVER purged by time (internet cuts don't lose data silently).
 *  - geofence proximity ping (≤ radius, throttled 10 min server-side) —
 *    runs on the SAME location stream and therefore stops together with
 *    tracking when the admin disables it (documented panel behaviour).
 *  - notification poll every 45s (+ on start / resume).
 *  - offline queue flush on every tick AND on connectivity regain.
 *  - policy refresh every 5 minutes — ALSO while tracking is disabled, so
 *    a panel re-enable reaches the device without user action; while
 *    ENABLED, every upload response carries the config too (piggy-back).
 *
 * Honest limitation (documented, per the phase-3 instruction): the OS may
 * stretch intervals under doze / battery restrictions / force-stop; this
 * service never claims a guaranteed 24/7 GPS rate. Force-stop requires the
 * user to reopen the app; reboot is covered by BootReceiver.
 */
class TrackingService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var lastProximityAt = HashMap<String, Long>()
    private var lastNotifPollAt = 0L
    private var lastFlushAt = 0L
    private var lastConfigPollAt = 0L
    private var lastLocationUploadAt = 0L
    private var requestingLocation = false
    private var moving = true // start on the faster cadence until proven still

    companion object {
        const val NOTIF_ID = 1001
        private const val TICK_MS = 45_000L
        private const val CONFIG_POLL_MS = 5 * 60_000L
        private const val UPLOAD_MIN_GAP_MS = 20_000L // don't hammer on every tick

        @Volatile var running = false
            private set

        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, TrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIF_ID, buildNotification())
        fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        applyTrackingState()

        // heartbeat: flush queues + poll notifications + upload locations
        // + refresh the remote policy
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                tick()
                kotlinx.coroutines.delay(TICK_MS)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            !TrackingConfig.current.enabled -> getString(R.string.notif_tracking_disabled)
            !hasLocationPermission() -> getString(R.string.notif_tracking_noperm)
            else -> getString(R.string.notif_tracking_text)
        }
        return NotificationCompat.Builder(this, TrackerApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_map)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** refresh the permanent notification so the user sees the true state */
    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** (re)start or stop location requests according to the CURRENT policy
     *  + permission — the single decision point, called on every config
     *  change and on motion-state flips. */
    private fun applyTrackingState() {
        val cfg = TrackingConfig.current
        val want = cfg.enabled && hasLocationPermission() && Session.isLoggedIn
        if (want) {
            val intervalMs = (if (moving) cfg.moveIntervalSec else cfg.stillIntervalSec) * 1000L
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
                .setMinUpdateDistanceMeters(10f)
                .build()
            try {
                fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
                requestingLocation = true
            } catch (_: SecurityException) {
                requestingLocation = false // permission revoked mid-flight
            } catch (_: Exception) {
                requestingLocation = false // play services unavailable
            }
        } else if (requestingLocation) {
            try {
                fused.removeLocationUpdates(locationCallback)
            } catch (_: Exception) { /* ignore */ }
            requestingLocation = false
        }
        refreshNotification()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val prev = lastLocation
            lastLocation = loc
            // G-6 (phase 1 / task 2): publish the fix for the check-in dialogs
            DeviceGps.update(loc)

            // motion detection (phase 3 §2): distance between consecutive
            // fixes vs the admin-set threshold; a flip re-issues the request
            // with the other cadence
            if (prev != null) {
                val cfg = TrackingConfig.current
                val dist = distanceMeters(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
                val nowMoving = dist > cfg.motionThresholdM
                if (nowMoving != moving) {
                    moving = nowMoving
                    applyTrackingState()
                }
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.get(applicationContext)
                // phase 3: UUID idempotency key + accuracy; NO time-based
                // purge — rows are deleted only after server confirmation
                db.pendingLocationDao().insert(
                    PendingLocation(
                        localId = UUID.randomUUID().toString(),
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracy = if (loc.hasAccuracy() && loc.accuracy > 0) loc.accuracy.toDouble() else null,
                    )
                )
                checkProximity(loc.latitude, loc.longitude)
            }
        }
    }

    private suspend fun checkProximity(lat: Double, lng: Double) {
        val db = AppDatabase.get(applicationContext)
        val now = System.currentTimeMillis()
        for (doc in db.doctorDao().assigned()) {
            if (doc.lat == 0.0 && doc.lng == 0.0) continue
            val dist = distanceMeters(lat, lng, doc.lat, doc.lng)
            if (dist <= doc.radius) {
                val last = lastProximityAt[doc.id] ?: 0
                if (now - last > 10 * 60_000L) { // local throttle mirrors server
                    lastProximityAt[doc.id] = now
                    try {
                        ApiClient.proximity(doc.id, lat, lng)
                    } catch (_: Exception) {
                        /* offline — skip, will re-trigger */
                    }
                    // package 11 — local proximity alert honours the user's toggle
                    if (NotificationPrefs.proximityEnabled) {
                        // phase 2 / §1 + mission item 3: tap-through opens the
                        // map centred on THIS customer's marker
                        AppNotifier.notify(
                            this@TrackingService, doc.id.hashCode(),
                            getString(R.string.notif_nearby, doc.name),
                            "فاصله حدود ${dist.roundToInt()} متر",
                            com.medpad.tracker.ui.NotificationTapRouter.pendingIntent(
                                this@TrackingService, "proximity", doc.id,
                                com.medpad.tracker.ui.NotificationTapRouter.ENTITY_CUSTOMER, doc.id,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()

        // remote policy refresh — ALWAYS (also while disabled, so a panel
        // re-enable reaches the device); an enabled tracker additionally
        // gets the config piggy-backed on every upload response
        if (now - lastConfigPollAt > CONFIG_POLL_MS) {
            lastConfigPollAt = now
            val before = TrackingConfig.current
            try {
                if (Session.isLoggedIn) TrackingConfig.updateFrom(ApiClient.trackingConfig())
            } catch (_: Exception) { /* offline — keep last valid */ }
            if (TrackingConfig.current != before) applyTrackingState()
        }

        // location upload (phase 3) — oldest-first batch, idempotent
        if (TrackingConfig.current.enabled && Session.isLoggedIn &&
            now - lastLocationUploadAt > UPLOAD_MIN_GAP_MS
        ) {
            lastLocationUploadAt = now
            try {
                LocationSync.flushOnce(applicationContext)
            } catch (_: Exception) { /* keep queue */ }
        }

        // flush check-in queue (also on network regain handled by MainActivity)
        if (now - lastFlushAt > 30_000) {
            lastFlushAt = now
            try {
                if (Session.isLoggedIn) CheckinSync.flush()
            } catch (_: Exception) { /* keep queue */ }
        }
        // notification poll (package 11: honours the user's toggle and dedups
        // across polls/restarts via the persisted shown-id set — a poll that
        // succeeds while the mark-read call fails can no longer re-fire the
        // same notification on the next poll)
        // P07 (permanent protocol): the poll now lives in ServerNotifications —
        // display failures no longer silently lose the notification (dedup
        // and mark-read happen ONLY after a successful display; failed ones
        // are retried on the next poll) and read is marked per-id, never
        // blanket mark-all.
        if (now - lastNotifPollAt > 40_000 && NotificationPrefs.serverEnabled) {
            lastNotifPollAt = now
            try {
                if (Session.isLoggedIn) {
                    ServerNotifications.pollOnce(applicationContext) { id, title, body, kind, customerId, entityType, entityId ->
                        // phase 2 / §1: tap-through per kind; mission item 3:
                        // entityType/entityId open the exact record
                        AppNotifier.notify(
                            this@TrackingService, id, title, body,
                            com.medpad.tracker.ui.NotificationTapRouter.pendingIntent(
                                this@TrackingService, kind, customerId, entityType, entityId,
                            ),
                        )
                    }
                }
            } catch (_: Exception) { /* offline */ }
        }
    }

    override fun onDestroy() {
        running = false
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (_: Exception) { /* ignore */ }
        super.onDestroy()
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
