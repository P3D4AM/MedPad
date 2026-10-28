package com.medpad.tracker.location

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
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
import com.medpad.tracker.sync.ServerNotifications
import com.medpad.tracker.util.NotificationPrefs
import com.medpad.tracker.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground tracking service:
 *  - location updates → local track store
 *  - geofence proximity ping (≤ radius, throttled 10 min server-side)
 *  - notification poll every 45s (+ on start / resume)
 *  - offline queue flush on every tick AND on connectivity regain
 */
class TrackingService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var lastProximityAt = HashMap<String, Long>()
    private var lastNotifPollAt = 0L
    private var lastFlushAt = 0L

    companion object {
        const val NOTIF_ID = 1001
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
        requestLocation()

        // heartbeat: flush queue + poll notifications every 45s
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                tick()
                kotlinx.coroutines.delay(45_000)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TrackerApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_map)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(getString(R.string.notif_tracking_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        if (!hasLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
            .setMinUpdateDistanceMeters(20f)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            // G-6 (phase 1 / task 2): publish the fix for the check-in dialogs
            DeviceGps.update(loc)
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.get(applicationContext)
                db.pendingLocationDao().insert(PendingLocation(lat = loc.latitude, lng = loc.longitude))
                db.pendingLocationDao().cleanup(System.currentTimeMillis() - 24 * 3600_000L)
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
                        AppNotifier.notify(
                            this@TrackingService, doc.id.hashCode(),
                            getString(R.string.notif_nearby, doc.name),
                            "فاصله حدود ${dist.roundToInt()} متر",
                        )
                    }
                }
            }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        // flush offline queue (also on network regain handled by caller/MainActivity)
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
                    ServerNotifications.pollOnce(applicationContext) { id, title, body ->
                        AppNotifier.notify(this@TrackingService, id, title, body)
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
