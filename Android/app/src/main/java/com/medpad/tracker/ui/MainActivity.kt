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
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.location.TrackingService
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.PlansSync
import com.medpad.tracker.sync.TrackerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main app screen.
 * v1.9.1 fixes: localId idempotency on submit paths, recording timer,
 * live queue status, old-panel warning, flush on resume/network-regain.
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // connection regained → flush immediately
            scope.launch(Dispatchers.IO) {
                CheckinSync.flush()
                CheckinSync.syncDoctors()
                // phase 2 / task 2: refresh the plans cache too (read-only,
                // separate table — a failure here cannot touch the queue)
                PlansSync.sync()
                withContext(Dispatchers.Main) { updateQueueStatus() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TrackerContext.init(this)

        findViewById<TextView>(R.id.tvHello).text =
            getString(R.string.hello, Session.employeeName.ifEmpty { "" })

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
        scope.launch(Dispatchers.IO) {
            CheckinSync.flush()
            CheckinSync.syncDoctors()
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
