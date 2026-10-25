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
        val dialog = CheckoutDialogFragment {
            scope.launch(Dispatchers.IO) { CheckinSync.flush() }
            updateQueueStatus()
            Toast.makeText(this, R.string.checkout_sent, Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, "checkout")
    }

    private fun updateQueueStatus() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { CheckinSync.queueCount() }
            val label = if (n == 0) getString(R.string.queue_empty) else getString(R.string.queue_items, n.toString())
            findViewById<TextView>(R.id.tvQueue).text = getString(R.string.queue_status, label)
            // old panel warning (voice uploads need panel ≥ 1.8.0)
            val pv = Session.panelVersion
            val old = pv.isNotEmpty() && pv.split('.').firstOrNull()?.toIntOrNull()?.let { it < 1 } == true ||
                pv.startsWith("1.6") || pv.startsWith("1.7")
            findViewById<TextView>(R.id.tvPanelWarning).visibility = if (old) View.VISIBLE else View.GONE
        }
        // also check open visits for checkout UX hint (best-effort)
        scope.launch(Dispatchers.IO) {
            try {
                val resp = com.medpad.tracker.api.ApiClient.openVisits()
                val open = resp.optJSONArray("openVisits")?.length() ?: 0
                withContext(Dispatchers.Main) {
                    findViewById<View>(R.id.cardCheckout).alpha = if (open > 0) 1f else 0.55f
                }
            } catch (_: Exception) { /* offline — keep as is */ }
        }
    }
}
