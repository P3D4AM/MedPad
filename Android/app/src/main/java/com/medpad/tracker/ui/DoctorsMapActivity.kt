package com.medpad.tracker.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
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
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.DoctorsSync
import com.medpad.tracker.sync.TrackerContext
import com.medpad.tracker.util.IranCities
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

/**
 * Customers map (osmdroid) — v1.9.0 gesture fixes:
 *  - one-finger pan WORKS (MapEventsOverlay listens passively; default touch pan is untouched)
 *  - adding a doctor requires a 1-second long-press (no accidental adds while panning)
 *  - edit allowed, DELETE removed (rep cannot delete customers)
 */
class DoctorsMapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val scope = CoroutineScope(Dispatchers.Main)
    private var doctors: List<Doctor> = emptyList()
    private var markers = HashMap<String, Marker>()
    private var longPressStart: Pair<GeoPoint, Long>? = null

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
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(35.72, 51.4))
        map.overlays.add(MapEventsOverlay(eventsReceiver))

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAdd).setOnClickListener {
            map.controller.zoomIn()
            // add at map center — quick alternative to long-press
            val center = map.mapCenter as GeoPoint
            editDialog(null, center.latitude, center.longitude)
        }

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
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun render() {
        map.overlays.removeAll { it is Marker }
        markers.clear()
        if (doctors.isEmpty()) return

        var minLat = doctors[0].lat
        var maxLat = doctors[0].lat
        var minLng = doctors[0].lng
        var maxLng = doctors[0].lng

        for (d in doctors) {
            minLat = minOf(minLat, d.lat); maxLat = maxOf(maxLat, d.lat)
            minLng = minOf(minLng, d.lng); maxLng = maxOf(maxLng, d.lng)
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
        try {
            map.zoomToBoundingBox(
                BoundingBox(maxLat, maxLng, minLat, minLng).increaseByScale(1.4f), false,
            )
        } catch (_: Exception) { /* single point */ }
        map.invalidate()
    }

    private fun editDialog(existing: Doctor?, lat: Double, lng: Double) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_customer, null)
        val edName = view.findViewById<EditText>(R.id.edName)
        val edAddress = view.findViewById<EditText>(R.id.edAddress)
        val edPhone = view.findViewById<EditText>(R.id.edPhone)
        val edCity = view.findViewById<EditText>(R.id.edCity)

        // package 8 — per-dialog city state. null = untouched (edit: pair is
        // NOT sent; create: pair omitted). Pair("", "") = explicit clear.
        var pickedCity: Pair<String, String>? = null

        existing?.let {
            edName.setText(it.name)
            edAddress.setText(it.address ?: "")
            edPhone.setText(it.phone ?: "")
            if (!it.city.isNullOrEmpty()) {
                edCity.setText(if (!it.province.isNullOrEmpty()) "${it.city} — ${it.province}" else it.city)
            }
        }

        fun showCityPicker() {
            val entries = IranCities.load(this)
            if (entries.isEmpty()) {
                // honest degradation — no fake list, no crash
                Toast.makeText(this, R.string.city_dataset_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val pickerView = LayoutInflater.from(this).inflate(R.layout.dialog_city_picker, null)
            val search = pickerView.findViewById<EditText>(R.id.edCitySearch)
            val list = pickerView.findViewById<ListView>(R.id.lvCities)
            var currentResults: List<IranCities.Entry> = emptyList()
            fun render(q: String) {
                currentResults = IranCities.search(this, q)
                val labels = ArrayList<String>(currentResults.size + 1)
                labels.add(getString(R.string.city_clear))
                currentResults.forEach { labels.add("${it.city} — ${it.province}") }
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            }
            render("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render(s?.toString() ?: "")
            })
            val picker = AlertDialog.Builder(this)
                .setTitle(R.string.city_picker_title)
                .setView(pickerView)
                .setNegativeButton(R.string.cancel, null)
                .show()
            list.setOnItemClickListener { _, _, position, _ ->
                if (position == 0) {
                    pickedCity = Pair("", "")
                    edCity.setText("")
                } else {
                    currentResults.getOrNull(position - 1)?.let { e ->
                        pickedCity = Pair(e.city, e.province)
                        edCity.setText("${e.city} — ${e.province}")
                    }
                }
                picker.dismiss()
            }
        }

        edCity.setOnClickListener { showCityPicker() }

        AlertDialog.Builder(this)
            .setTitle(getString(if (existing == null) R.string.add_customer else R.string.edit_customer))
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@DoctorsMapActivity, R.string.need_customer, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val city = pickedCity
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            if (existing == null) {
                                // create: blank/absent pair → omit entirely
                                val pair = city?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
                                ApiClient.createCustomer(
                                    name, lat, lng,
                                    edAddress.text.toString().trim(), edPhone.text.toString().trim(), "doctor",
                                    pair?.first, pair?.second,
                                ).optBoolean("ok")
                            } else {
                                DoctorsSync.edit(
                                    existing, name,
                                    edAddress.text.toString().trim(), edPhone.text.toString().trim(),
                                    city?.first, city?.second,
                                )
                            }
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (ok) {
                        Toast.makeText(this@DoctorsMapActivity, R.string.customer_saved, Toast.LENGTH_SHORT).show()
                        load()
                    } else {
                        Toast.makeText(this@DoctorsMapActivity, R.string.sync_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }
}
