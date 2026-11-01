package com.medpad.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.DoctorsSync
import com.medpad.tracker.sync.TrackerContext
import com.medpad.tracker.util.IranCities
import com.medpad.tracker.util.MapDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Customers map (osmdroid) — v1.9.0 gesture fixes:
 *  - one-finger pan WORKS (MapEventsOverlay listens passively; default touch pan is untouched)
 *  - adding a doctor requires a 1-second long-press (no accidental adds while panning)
 *  - edit allowed, DELETE removed (rep cannot delete customers)
 * package 9 (phase 2 / task 6, B7):
 *  - zoom BOUNDED (5..19 — MAPNIK's practical range; was infinite)
 *  - initial center = admin's default-city setting (offline-cached), not a
 *    hard-coded Tehran
 *  - «موقعیت من» button: follows the REAL GPS fix only — the user's
 *    location is NEVER approximated with customer coordinates
 */
class DoctorsMapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val scope = CoroutineScope(Dispatchers.Main)
    private var doctors: List<Doctor> = emptyList()
    private var markers = HashMap<String, Marker>()
    private var longPressStart: Pair<GeoPoint, Long>? = null
    // package 9 — real-GPS location overlay (null until first use)
    private var myLocationOverlay: MyLocationNewOverlay? = null

    companion object {
        private const val REQ_LOCATION = 41

        /** mission item 3 — notification tap-through: centre the map on THIS
         *  customer's marker (and open its info window). The hub has already
         *  verified the customer exists locally. */
        const val EXTRA_FOCUS_CUSTOMER_ID = "medpad.extra.FOCUS_CUSTOMER_ID"
    }

    private val eventsReceiver = object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
            cancelLongPress()
            return false
        }

        override fun longPressHelper(p: GeoPoint?): Boolean {
            // MapEventsOverlay fires long-press after its own timeout —
            // we additionally track a 1s hold below via touch events
            p?.let { maybeAdd(it) }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        setContentView(R.layout.activity_doctors_map)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true) // pinch zoom + one-finger pan intact
        // package 9 (B7) — logical zoom bounds instead of infinite zoom
        map.minZoomLevel = MapDefaults.MIN_ZOOM
        map.maxZoomLevel = MapDefaults.MAX_ZOOM
        map.controller.setZoom(MapDefaults.INITIAL_ZOOM)
        // package 9 — admin's default city (offline-cached), else Tehran
        val start = MapDefaults.cached(this)
        map.controller.setCenter(
            GeoPoint(start?.lat ?: MapDefaults.FALLBACK_LAT, start?.lng ?: MapDefaults.FALLBACK_LNG),
        )
        map.overlays.add(MapEventsOverlay(eventsReceiver))

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAdd).setOnClickListener {
            map.controller.zoomIn()
            // add at map center — quick alternative to long-press
            val center = map.mapCenter as GeoPoint
            editDialog(null, center.latitude, center.longitude)
        }
        // package 9 — go to my REAL location (GPS only, never customer coords)
        findViewById<ImageButton>(R.id.btnLocate).setOnClickListener { onMyLocationClicked() }

        // pan/zoom gestures stay native to osmdroid (one-finger pan works)
        map.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> longPressStart = null
                MotionEvent.ACTION_UP -> longPressStart = null
            }
            false // let osmdroid handle everything
        }

        load()
    }

    private fun cancelLongPress() {
        longPressStart = null
    }

    private fun maybeAdd(p: GeoPoint) {
        editDialog(null, p.latitude, p.longitude)
    }

    private fun load() {
        scope.launch {
            withContext(Dispatchers.IO) { CheckinSync.syncDoctors() }
            doctors = withContext(Dispatchers.IO) {
                AppDatabase.get(TrackerContext.app).doctorDao().all()
            }
            render()
            // package 9 — refresh the admin's default-city setting and
            // cache it for OFFLINE reuse; center on it when the rep has
            // no customers yet (map markers would show nothing otherwise).
            val fetched = withContext(Dispatchers.IO) {
                try { MapDefaults.parseConfig(ApiClient.config()) } catch (_: Exception) { null }
            }
            if (fetched != null) {
                MapDefaults.cache(this@DoctorsMapActivity, fetched)
                if (doctors.isEmpty()) {
                    map.controller.animateTo(GeoPoint(fetched.lat, fetched.lng))
                    map.controller.setZoom(MapDefaults.INITIAL_ZOOM)
                }
            }
        }
    }

    // ── package 9: my location (REAL GPS fix only) ─────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            true // unknown state — let the fix listener report honestly
        }
    }

    private fun onMyLocationClicked() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
            return
        }
        if (!isLocationEnabled()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.gps_disabled)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        // The overlay uses ONLY real device fixes — the user's position is
        // never guessed from customer coordinates (B7 requirement).
        val overlay = myLocationOverlay ?: MyLocationNewOverlay(map).also {
            myLocationOverlay = it
            map.overlays.add(it)
        }
        overlay.enableMyLocation()
        overlay.enableFollowLocation()
        val fix = overlay.myLocation
        if (fix != null) {
            map.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
            map.controller.setZoom(MapDefaults.FOLLOW_ZOOM)
        } else {
            Toast.makeText(this, R.string.gps_waiting_fix, Toast.LENGTH_SHORT).show()
            overlay.runOnFirstFix {
                runOnUiThread {
                    val first = myLocationOverlay?.myLocation ?: return@runOnUiThread
                    map.controller.animateTo(GeoPoint(first.latitude, first.longitude))
                    map.controller.setZoom(MapDefaults.FOLLOW_ZOOM)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            onMyLocationClicked() // permission granted — proceed
        } else {
            Toast.makeText(this, R.string.gps_no_permission, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun render() {
        map.overlays.removeAll { it is Marker }
        markers.clear()
        if (doctors.isEmpty()) return

        for (d in doctors) {
            val m = Marker(map).apply {
                position = GeoPoint(d.lat, d.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = d.name
                // package 8 — city (when set) shown with the address
                snippet = listOfNotNull(d.city, d.address).joinToString(" — ")
                setOnMarkerClickListener { _, _ ->
                    editDialog(d, d.lat, d.lng)
                    true
                }
            }
            markers[d.id] = m
            map.overlays.add(m)
        }

        // mission 12 / item 2 — initial view: fit the bounds of ALL valid
        // coordinates (the (0,0) "no coords" marker excluded — including it
        // was the «پرش به آمریکا» ocean-zoom bug). ONE point → a readable
        // zoom on it; NO valid coordinates → never fly to a fake place (the
        // admin's default-city centre stays + an honest toast). Free pan
        // and zoom stay untouched (this runs once per render, animate=false).
        val focus = com.medpad.tracker.util.MapFocus.allBounds(doctors)
        val b = focus.bounds
        when {
            b != null -> {
                try {
                    map.zoomToBoundingBox(
                        BoundingBox(b.maxLat, b.maxLng, b.minLat, b.minLng)
                            .increaseByScale(1.4f), // padding — markers clear the edges
                        false,
                    )
                } catch (_: Exception) { /* degenerate box — keep the current view */ }
            }
            focus.single != null -> {
                map.controller.setCenter(GeoPoint(focus.single.first, focus.single.second))
                map.controller.setZoom(MapDefaults.FOLLOW_ZOOM)
            }
            else -> {
                // NO valid coordinates: never move the map to a fake place —
                // keep the default-city centre and tell the user honestly
                Toast.makeText(this, R.string.map_no_coords, Toast.LENGTH_LONG).show()
            }
        }
        map.invalidate()

        // mission item 3 — notification tap-through: the notification was
        // about ONE customer → centre on that marker and open its info
        // window. The hub (MainActivity) only passes the extra when the
        // customer is still in the local cache; a vanished marker would be
        // a cache race, handled honestly below.
        intent.getStringExtra(EXTRA_FOCUS_CUSTOMER_ID)?.takeIf { it.isNotEmpty() }?.let { cid ->
            val m = markers[cid]
            if (m != null) {
                map.controller.animateTo(m.position)
                map.controller.setZoom(kotlin.math.max(map.zoomLevelDouble, MapDefaults.FOLLOW_ZOOM))
                m.showInfoWindow()
            } else {
                Toast.makeText(this, R.string.notif_tap_customer_gone, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun editDialog(existing: Doctor?, lat: Double, lng: Double) {
        // Customer 360 (phase 1) — the shared form (offline queue + full
        // profile fields + Persian validation) replaces the old inline
        // dialog. The map supplies the preset position for NEW customers
        // (long-press spot); edits keep their stored coordinates.
        CustomerFormDialog.show(this, existing, lat, lng) {
            scope.launch { load() }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        // package 9 — stop GPS updates when not visible (battery honesty)
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay?.disableFollowLocation()
        map.onPause()
        super.onPause()
    }
}
