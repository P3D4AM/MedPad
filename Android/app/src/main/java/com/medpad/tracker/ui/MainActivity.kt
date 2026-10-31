package com.medpad.tracker.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.location.TrackingService
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.LocationSync
import com.medpad.tracker.sync.PlansSync
import com.medpad.tracker.sync.TrackerContext
import com.medpad.tracker.util.NotificationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main app screen.
 * v1.9.1 fixes: localId idempotency on submit paths, recording timer,
 * live queue status, old-panel warning, flush on resume/network-regain.
 *
 * phase 2 / §1 (notification tap-through): MainActivity is the guarded
 * ROUTING HUB. Every notification tap lands here (NotificationTapRouter);
 * this screen checks the login state BEFORE anything else — an expired
 * session or a cold start after logout persists the requested route and
 * sends the user to LoginActivity, and after a successful re-login the
 * pending route is consumed here and forwarded to the destination.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    /** Package 1 (B5): last known open-visit count from the server
     *  (best-effort, refreshed by updateQueueStatus). null = unknown
     *  (offline / not fetched yet) — in that state we do NOT block checkout,
     *  because a pending check-in may exist locally in the offline queue. */
    @Volatile private var lastKnownOpenVisits: Int? = null

    // mic permission
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicAction?.invoke(granted)
        pendingMicAction = null
    }
    private var pendingMicAction: ((Boolean) -> Unit)? = null

    // location + notifications permissions for the service
    private val servicePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.all { it }
        if (ok) TrackingService.start(this)
    }

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateQueueStatus()
        }
    }

    // mission item 3 — duplicate-tap guard: the same tap destination
    // delivered twice within this window (double-tap on the notification,
    // or the single-top intent redelivery) opens the destination ONCE.
    private var lastTapSignature: String? = null
    private var lastTapAt = 0L

    /** mission item 3 — single-top redelivery: the hub is already on top
     *  and the user tapped a notification. Without this override the tap
     *  was silently IGNORED while the app was open (routeFrom only ran in
     *  onCreate). Consumed here with the same guarded-hub rules: no
     *  session → persist + login detour; otherwise forward (deduped). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val route = NotificationTapRouter.routeFrom(intent) ?: return
        if (!Session.isLoggedIn) {
            NotificationTapRouter.savePending(this, route)
            return
        }
        scope.launch { forwardTapRoute(route) }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // connection regained → flush immediately (check-ins + locations)
            scope.launch(Dispatchers.IO) {
                CheckinSync.flush()
                CheckinSync.syncDoctors()
                LocationSync.flushOnce(this@MainActivity)
                // phase 2 / task 2: refresh the plans cache too (read-only,
                // separate table — a failure here cannot touch the queue)
                PlansSync.sync()
                withContext(Dispatchers.Main) { updateQueueStatus() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // phase 2 / §1 — the guarded hub: a notification tap can launch this
        // activity from COLD with no session. Not logged in? Persist the
        // requested route (it survives the login detour — consumed after a
        // successful re-login) and land on the login screen instead of a
        // broken home.
        if (!Session.isLoggedIn) {
            NotificationTapRouter.routeFrom(intent)?.let {
                NotificationTapRouter.savePending(this, it)
            }
            Toast.makeText(this, R.string.notif_tap_login_needed, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        TrackerContext.init(this)

        // phase 2 / §1 — consume the tap route: either the one THIS launch
        // carries, or a pending one saved before a login detour (cold start
        // / expired session). Direct routes take precedence; a consumed
        // pending never fires twice.
        val tapRoute = NotificationTapRouter.routeFrom(intent)
        val pendingRoute = NotificationTapRouter.takePending(this)
        (tapRoute ?: pendingRoute)?.let { route ->
            scope.launch { forwardTapRoute(route) }
        }

        // mission item 2 — the profile greeting shows ONLY the first name
        // («سلام، علی»). A legacy employee (no split yet) or an old panel
        // greets WITHOUT a name — never with the full name as a substitute.
        val greetingFirst = com.medpad.tracker.util.Greeting.firstNameFor(Session.employeeFirstName)
        findViewById<TextView>(R.id.tvHello).text =
            if (greetingFirst != null) getString(R.string.hello, greetingFirst)
            else getString(R.string.hello_no_name)

        // package 11 — in-app notification settings (local + server toggles)
        findViewById<ImageButton>(R.id.btnNotifSettings).setOnClickListener { showNotifSettings() }

        // phase 2 / dev.4 (§5+§6) — account sheets: change password + change
        // server address after login (the migration path)
        findViewById<ImageButton>(R.id.btnAccount).setOnClickListener { AccountDialogs.show(this) }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.logout_confirm)
                .setPositiveButton(R.string.logout) { _, _ ->
                    TrackingService.stop(this)
                    Session.clear(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        findViewById<View>(R.id.cardCheckin).setOnClickListener {
            withMic { granted ->
                if (granted) showCheckin()
            }
        }
        findViewById<View>(R.id.cardCheckout).setOnClickListener {
            withMic { granted ->
                if (granted) showCheckout()
            }
        }
        findViewById<View>(R.id.cardReports).setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }
        // phase 2 / task 2: planned visits screen
        findViewById<View>(R.id.cardPlans).setOnClickListener {
            startActivity(Intent(this, PlansActivity::class.java))
        }
        findViewById<View>(R.id.cardMap).setOnClickListener {
            startActivity(Intent(this, DoctorsMapActivity::class.java))
        }

        askServicePermissions()
        // API 34+ requires explicit export flag for custom-action receivers
        ContextCompat.registerReceiver(
            this, queueReceiver, IntentFilter("medpad.QUEUE_CHANGED"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        // v1.9.1: flush on every open + refresh queue + doctors sync
        // phase 3: location upload rides the same resume path
        scope.launch(Dispatchers.IO) {
            CheckinSync.flush()
            CheckinSync.syncDoctors()
            LocationSync.flushOnce(this@MainActivity)
            withContext(Dispatchers.Main) { updateQueueStatus() }
        }
        // connectivity callback
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback,
            )
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onPause() {
        super.onPause()
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onDestroy() {
        try { unregisterReceiver(queueReceiver) } catch (_: Exception) { /* ignore */ }
        super.onDestroy()
    }

    private fun askServicePermissions() {
        val wanted = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) {
            servicePermissions.launch(wanted.toTypedArray())
        } else {
            TrackingService.start(this)
        }
    }

    private fun withMic(action: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action(true) // v1.9.1: permission already granted → proceed directly
            return
        }
        pendingMicAction = action
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** phase 2 / §1 + mission item 3 — forward a consumed notification-tap
     *  route to the related screen/RECORD. Runs after the main UI is up so
     *  the back stack always has the home screen below the destination.
     *  Entity payloads open the exact record:
     *    plan    → PlansActivity on that plan's day, row highlighted +
     *              details opened
     *    request → PlansActivity with the «درخواست‌های من» dialog open
     *    customer→ the map centred on that customer's marker
     *  Without an entity (historical rows / old panels) the kind mapping
     *  decides the screen, as before.
     *  MAP routes check the referenced customer against the local cache:
     *  deleted/unassigned → the map still opens, with an honest toast
     *  (understandable + safe, never a crash). */
    private suspend fun forwardTapRoute(route: NotificationTapRouter.Route) {
        // collapse duplicate deliveries of the SAME destination (mission
        // item 3: «از باز شدن چندباره یک مقصد … جلوگیری کن»)
        val now = SystemClock.elapsedRealtime()
        if (route.signature == lastTapSignature && now - lastTapAt < 2_500) return
        lastTapSignature = route.signature
        lastTapAt = now

        when (route.route) {
            NotificationTapRouter.ROUTE_PLANS -> {
                val i = Intent(this, PlansActivity::class.java)
                when (route.entityType) {
                    NotificationTapRouter.ENTITY_PLAN ->
                        i.putExtra(PlansActivity.EXTRA_PLAN_ID, route.entityId)
                    NotificationTapRouter.ENTITY_REQUEST ->
                        i.putExtra(PlansActivity.EXTRA_OPEN_REQUESTS, true)
                }
                startActivity(i)
            }
            NotificationTapRouter.ROUTE_MAP -> {
                var known = true
                if (route.customerId != null) {
                    known = withContext(Dispatchers.IO) {
                        try {
                            com.medpad.tracker.db.AppDatabase.get(this@MainActivity)
                                .doctorDao().byId(route.customerId) != null
                        } catch (_: Exception) {
                            true // DB hiccup — do not block the navigation
                        }
                    }
                }
                if (!known) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, R.string.notif_tap_customer_gone, Toast.LENGTH_LONG).show()
                    }
                }
                val i = Intent(this, DoctorsMapActivity::class.java)
                if (known && route.customerId != null) {
                    i.putExtra(DoctorsMapActivity.EXTRA_FOCUS_CUSTOMER_ID, route.customerId)
                }
                startActivity(i)
            }
            // ROUTE_HOME (and anything else): no forwarding — the user is
            // already home. The route is simply consumed.
        }
    }

    /** package 11 + mission item 4 — notification settings: LOCAL proximity
     *  alerts, SERVER notifications toggles, AND a live delivery-diagnostics
     *  block: the exact system states that decide whether a locked-phone
     *  notification reaches the user (app permission, channel, battery
     *  exemption, running service), each with a direct fix action and an
     *  honest explanation — nothing is silently "fine". */
    private fun showNotifSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val swServer = SwitchCompat(this).apply {
            text = getString(R.string.notif_server_toggle)
            isChecked = NotificationPrefs.serverEnabled
        }
        val swProximity = SwitchCompat(this).apply {
            text = getString(R.string.notif_proximity_toggle)
            isChecked = NotificationPrefs.proximityEnabled
        }
        val note = TextView(this).apply {
            text = getString(R.string.notif_semantics_note)
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        box.addView(swServer)
        box.addView(swProximity)
        box.addView(note)

        // ── mission item 4: delivery diagnostics ──────────────────────
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val permOk = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelOk = com.medpad.tracker.sync.AppNotifier.displayAllowed(
            permOk,
            com.medpad.tracker.sync.AppNotifier.channelImportance(nm, com.medpad.tracker.TrackerApp.CHANNEL_ALERTS),
        )
        val pm = getSystemService(android.os.PowerManager::class.java)
        val batteryExempt = try {
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Exception) {
            true // unknown — do not scare the user with a wrong "blocked"
        }
        val svcRunning = TrackingService.running

        // the dialog is built FIRST so the diagLine fix-actions can capture
        // it (a local function may only reference variables declared above)
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.notif_settings_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                NotificationPrefs.serverEnabled = swServer.isChecked
                NotificationPrefs.proximityEnabled = swProximity.isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun diagLine(label: String, ok: Boolean, okText: String, badText: String, fix: (androidx.appcompat.app.AlertDialog) -> Unit): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = if (ok) "✓ $label: $okText" else "✗ $label: $badText"
                textSize = 13f
                setTextColor(if (ok) 0xFF166534.toInt() else 0xFFB91C1C.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!ok) {
                row.addView(androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = getString(R.string.notif_diag_fix)
                    textSize = 12f
                    setOnClickListener { _ -> fix(dlg) }
                })
            }
            return row
        }

        val diagTitle = TextView(this).apply {
            text = getString(R.string.notif_diag_title)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 2)
        }
        box.addView(diagTitle)

        box.addView(diagLine(getString(R.string.notif_diag_perm), channelOk,
            getString(R.string.notif_diag_on), getString(R.string.notif_diag_perm_off)) { d ->
            d.dismiss()
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                })
            } catch (_: Exception) { /* honest no-op */ }
        })
        box.addView(diagLine(getString(R.string.notif_diag_battery), batteryExempt,
            getString(R.string.notif_diag_battery_ok), getString(R.string.notif_diag_battery_off)) { d ->
            d.dismiss()
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) { /* honest no-op */ }
        })
        box.addView(diagLine(getString(R.string.notif_diag_service), svcRunning,
            getString(R.string.notif_diag_on), getString(R.string.notif_diag_service_off)) { _ ->
            TrackingService.start(this@MainActivity)
        })
        val lockNote = TextView(this).apply {
            text = getString(R.string.notif_locked_note)
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(lockNote)
        dlg.show()
    }

    private fun showCheckin() {
        scope.launch(Dispatchers.IO) { CheckinSync.syncDoctors() }
        val dialog = CheckinDialogFragment {
            scope.launch(Dispatchers.IO) { CheckinSync.flush() }
            updateQueueStatus()
            Toast.makeText(this, R.string.checkin_sent, Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, "checkin")
    }

    private fun showCheckout() {
        // Package 1 (B5): do not let the user enqueue a checkout when we
        // KNOW there is no open visit server-side AND no pending check-in
        // locally — such a checkout is permanently rejected (409) by the
        // server and used to freeze the whole queue. When the state is
        // unknown (offline) or a local check-in is waiting, we allow it and
        // let the queue + its new failed-state handling deal with it.
        scope.launch {
            val pendingCheckins = withContext(Dispatchers.IO) { CheckinSync.pendingCount() }
            val open = lastKnownOpenVisits
            if (open == 0 && pendingCheckins == 0) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.no_active_checkin)
                    .setPositiveButton(R.string.checkin, null)
                    .show()
                return@launch
            }
            val dialog = CheckoutDialogFragment {
                scope.launch(Dispatchers.IO) { CheckinSync.flush() }
                updateQueueStatus()
                Toast.makeText(this@MainActivity, R.string.checkout_sent, Toast.LENGTH_SHORT).show()
            }
            dialog.show(supportFragmentManager, "checkout")
        }
    }

    private fun updateQueueStatus() {
        scope.launch {
            // Package 1: distinguish waiting (LOCAL / in-flight) from FAILED
            val waiting = withContext(Dispatchers.IO) { CheckinSync.pendingCount() }
            val failed = withContext(Dispatchers.IO) { CheckinSync.failedCount() }
            val tvQueue = findViewById<TextView>(R.id.tvQueue)
            val label = when {
                waiting == 0 && failed == 0 -> getString(R.string.queue_empty)
                failed > 0 -> getString(R.string.queue_items_with_failed, waiting.toString(), failed.toString())
                else -> getString(R.string.queue_items, waiting.toString())
            }
            tvQueue.text = getString(R.string.queue_status, label)
            // failed items are readable + retryable from the queue details
            tvQueue.setOnClickListener { if (failed > 0 || waiting > 0) showQueueDetails() }
            tvQueue.setTextColor(
                if (failed > 0) 0xFFB91C1C.toInt() else 0xFF57534E.toInt(),
            )
            // old panel warning (voice uploads need panel ≥ 1.8.0)
            val pv = Session.panelVersion
            val old = pv.isNotEmpty() && pv.split('.').firstOrNull()?.toIntOrNull()?.let { it < 1 } == true ||
                pv.startsWith("1.6") || pv.startsWith("1.7")
            findViewById<TextView>(R.id.tvPanelWarning).visibility = if (old) View.VISIBLE else View.GONE
            // phase 3: honest tracking-permission state — the banner shows
            // whenever the location permission is missing; tapping it (re)asks
            // and starts the service on grant. Permission state also reaches
            // the panel through every upload's status snapshot.
            val tvPerm = findViewById<TextView>(R.id.tvTrackingPerm)
            val permOk = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            if (permOk) {
                tvPerm.visibility = View.GONE
            } else {
                tvPerm.text = getString(R.string.tracking_perm_banner)
                tvPerm.visibility = View.VISIBLE
                tvPerm.setOnClickListener { askServicePermissions() }
            }
            // G-2 (phase 1 / task 2): the server rejected our token (expired,
            // revoked or the employee was deactivated) → send the user to the
            // login screen instead of silently staying "logged in" offline.
            if (CheckinSync.sessionExpired) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.session_expired)
                    .setCancelable(false)
                    .setPositiveButton(R.string.login_again) { _, _ ->
                        TrackingService.stop(this@MainActivity)
                        Session.clear(this@MainActivity)
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                    .show()
            }
        }
        // also check open visits for checkout UX hint (best-effort)
        scope.launch(Dispatchers.IO) {
            try {
                val resp = com.medpad.tracker.api.ApiClient.openVisits()
                val open = resp.optJSONArray("openVisits")?.length() ?: 0
                lastKnownOpenVisits = open // Package 1 (B5): remember for the checkout guard
                withContext(Dispatchers.Main) {
                    findViewById<View>(R.id.cardCheckout).alpha = if (open > 0) 1f else 0.55f
                }
            } catch (_: Exception) { /* offline — keep as is */ }
        }
    }

    /** Package 1: queue details — waiting + failed items with their server
     *  error text, and an explicit retry for the failed ones. */
    private fun showQueueDetails() {
        scope.launch {
            val failed = withContext(Dispatchers.IO) { CheckinSync.failedItems() }
            val waiting = withContext(Dispatchers.IO) { CheckinSync.pendingCount() }
            val sb = StringBuilder()
            sb.append(getString(R.string.queue_details_waiting, waiting.toString())).append("\n\n")
            if (failed.isEmpty()) {
                sb.append(getString(R.string.queue_details_no_failures))
            } else {
                sb.append(getString(R.string.queue_details_failed_header, failed.size.toString())).append("\n")
                val cal = java.util.Calendar.getInstance()
                failed.take(10).forEach { f ->
                    cal.timeInMillis = f.createdAt
                    val time = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                    val kind = if (f.kind == "checkin") getString(R.string.checkin) else getString(R.string.checkout)
                    sb.append("• ").append(kind).append(" — ").append(time).append("\n")
                    f.lastError?.let { sb.append("    ").append(it).append("\n") }
                }
                if (failed.size > 10) sb.append("…\n")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.queue_details_title)
                .setMessage(sb.toString())
                .setPositiveButton(
                    if (failed.isEmpty()) android.R.string.ok else R.string.retry_failed,
                ) { _, _ ->
                    if (failed.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            CheckinSync.retryFailed()
                            withContext(Dispatchers.Main) { updateQueueStatus() }
                        }
                    }
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }
}
