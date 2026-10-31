package com.medpad.tracker.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.location.DeviceGps
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.PlansSync
import com.medpad.tracker.sync.TrackerContext
import com.medpad.tracker.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Check-in dialog with text + voice.
 * v1.9.1: startNow() actually called, live recording timer, localId idempotency.
 * phase 2 / task 2: optional preselectCustomerId — when the dialog is opened
 * from a planned visit, the customer is preselected in the spinner (the
 * flow itself is UNCHANGED: same GPS/voice/queue/idempotency path).
 */
// Package 2 (crash hardening): ALL constructor params have defaults so Kotlin
// generates the no-arg constructor the framework needs to re-instantiate the
// fragment on configuration change (rotation). Without it, rotating the screen
// with the dialog open crashed the app ("Fragment could not be instantiated").
// After a rotation the dialog reappears without its callback — the cancel
// button still works and nothing is lost (the queue owns the data).
class CheckinDialogFragment(
    private val preselectCustomerId: String? = null,
    private val onDone: () -> Unit = {},
) : DialogFragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val recorder = VoiceRecorder(TrackerContext.app)
    private val recTicker = object : Runnable {
        override fun run() {
            val sec = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            tvRecState?.text = getString(R.string.recording, String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60))
            handler.postDelayed(this, 500)
        }
    }
    private var startedAt = 0L
    private var tvRecState: TextView? = null
    private var btnRec: ImageButton? = null
    private var btnPlay: ImageButton? = null
    private var doctors: List<Doctor> = emptyList()

    // mission item 8 — live device clock + plan-window comparison state
    private var tvClock: TextView? = null
    private var tvPlan: TextView? = null
    private val clockTicker = object : Runnable {
        override fun run() {
            renderClock()
            handler.postDelayed(this, 1_000)
        }
    }

    /** renders the CURRENT device time (the time the check-in will carry —
     *  distinct from the server's final record time, which may differ on
     *  clock skew; the server stores both) */
    private fun renderClock() {
        val cal = java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran")
        }
        val time = "%02d:%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
        val j = JalaliDate.fromGregorian(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        tvClock?.text = getString(R.string.checkin_now_time, time, JalaliDate.formatFa(j))
    }

    /** mission item 8 — compare NOW with TODAY's plan for the SELECTED
     *  customer: the window, the status (early / on-time / late with the
     *  exact minutes) or an explicit «بدون برنامه» (no deviation is ever
     *  computed for a plan that does not exist). */
    private fun refreshPlanStatus(spCustomer: android.widget.Spinner) {
        val cus = doctors.getOrNull(spCustomer.selectedItemPosition)
        val tv = tvPlan ?: return
        if (cus == null) {
            tv.text = ""
            tv.visibility = android.view.View.GONE
            return
        }
        scope.launch {
            val today = PlansSync.serverToday().ifEmpty { PlansSync.deviceDayKey(0) }
            val plans = withContext(Dispatchers.IO) {
                try {
                    AppDatabase.get(TrackerContext.app).plannedVisitDao().byDay(today)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val plan = PlanWindow.findPlan(plans, cus.id, today)
            withContext(Dispatchers.Main) {
                if (plan == null) {
                    tv.text = getString(R.string.checkin_planless)
                    tv.setTextColor(0xFF57534E.toInt())
                    tv.visibility = android.view.View.VISIBLE
                    return@withContext
                }
                val a = PlanWindow.assess(System.currentTimeMillis(), plan.scheduledAtMs, plan.durationMin)
                val startCal = java.util.Calendar.getInstance().apply {
                    timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran")
                    timeInMillis = plan.scheduledAtMs
                }
                val endCal = java.util.Calendar.getInstance().apply {
                    timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran")
                    timeInMillis = a.planEndMs
                }
                val windowLabel = "%02d:%02d تا %02d:%02d".format(
                    startCal.get(java.util.Calendar.HOUR_OF_DAY), startCal.get(java.util.Calendar.MINUTE),
                    endCal.get(java.util.Calendar.HOUR_OF_DAY), endCal.get(java.util.Calendar.MINUTE),
                )
                val statusLabel = when (a.status) {
                    PlanWindow.Status.EARLY -> getString(R.string.checkin_plan_early, a.diffMinutes.toString())
                    PlanWindow.Status.ON_TIME -> getString(R.string.checkin_plan_ontime)
                    PlanWindow.Status.LATE -> getString(R.string.checkin_plan_late, a.diffMinutes.toString())
                }
                tv.text = getString(R.string.checkin_plan_window, windowLabel, statusLabel)
                tv.setTextColor(
                    when (a.status) {
                        PlanWindow.Status.ON_TIME -> 0xFF166534.toInt()
                        PlanWindow.Status.EARLY -> 0xFF92400E.toInt()
                        PlanWindow.Status.LATE -> 0xFFB91C1C.toInt()
                    },
                )
                tv.visibility = android.view.View.VISIBLE
            }
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_checkin, null)
        tvRecState = view.findViewById(R.id.tvRecState)
        btnRec = view.findViewById(R.id.btnRec)
        btnPlay = view.findViewById(R.id.btnPlay)

        // mission item 8 — a live device clock + the plan-window comparison
        // sit at the TOP of the form (before the customer spinner): the rep
        // sees the exact time the check-in will carry and the plan status
        // BEFORE confirming. The views are added programmatically to keep
        // the committed layout untouched for older-translations compat.
        (view as? android.view.ViewGroup)?.let { vg ->
            val pad = (6 * resources.displayMetrics.density).toInt()
            tvClock = TextView(requireContext()).apply {
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, pad)
            }
            tvPlan = TextView(requireContext()).apply {
                textSize = 13f
                setPadding(0, 0, 0, pad)
            }
            vg.addView(tvClock, 1) // right under the dialog title
            vg.addView(tvPlan, 2)
        }
        renderClock()
        handler.postDelayed(clockTicker, 1_000)

        val spCustomer = view.findViewById<Spinner>(R.id.spCustomer)
        val edNote = view.findViewById<TextInputEditText>(R.id.edNote)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmit)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        scope.launch {
            doctors = withContext(Dispatchers.IO) {
                AppDatabase.get(TrackerContext.app).doctorDao().assigned()
            }
            if (doctors.isEmpty()) {
                tvRecState?.text = getString(R.string.no_customers_yet)
            } else {
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, doctors.map { it.name })
                spCustomer.adapter = adapter
                // phase 2 / task 2: opened from a planned visit → preselect its
                // customer (only if it is still in the assigned list; a masked /
                // unassigned customer is surfaced honestly instead)
                preselectCustomerId?.let { pid ->
                    val idx = doctors.indexOfFirst { it.id == pid }
                    if (idx >= 0) spCustomer.setSelection(idx)
                    else tvRecState?.text = getString(R.string.plan_customer_not_in_list)
                }
                // mission item 8 — re-assess the plan window whenever the
                // customer selection changes (and once at open)
                refreshPlanStatus(spCustomer)
                spCustomer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                        refreshPlanStatus(spCustomer)
                    }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            }
        }

        btnRec?.setOnClickListener {
            if (recorder.isRecording) {
                stopRec()
            } else {
                // v1.9.1: real call — recording starts even when permission pre-granted
                if (recorder.startNow()) {
                    startedAt = System.currentTimeMillis()
                    btnRec?.setImageResource(R.drawable.ic_stop)
                    btnRec?.setBackgroundResource(R.drawable.rec_active)
                    handler.post(recTicker)
                } else {
                    tvRecState?.text = getString(R.string.voice_play_failed)
                }
            }
        }

        btnPlay?.setOnClickListener {
            btnPlay?.isEnabled = false
            recorder.play(recorder.currentFile, onDone = { btnPlay?.isEnabled = true }, onError = {
                btnPlay?.isEnabled = true
                tvRecState?.text = getString(R.string.voice_play_failed)
            })
        }

        btnCancel.setOnClickListener {
            recorder.discard()
            dismiss()
        }

        btnSubmit.setOnClickListener {
            val cus = doctors.getOrNull(spCustomer.selectedItemPosition)
            if (cus == null) {
                tvRecState?.text = getString(R.string.need_customer)
                return@setOnClickListener
            }
            val voice = recorder.currentFile?.takeIf { it.exists() }
            btnSubmit.isEnabled = false
            progress.visibility = View.VISIBLE
            scope.launch {
                // mission item 6 — track the item so a PERMANENT server
                // rejection (e.g. 422 geofence: «فاصلهٔ شما … بیشتر از شعاع
                // مجاز چک‌این …») surfaces its Persian text HERE, in the
                // dialog, instead of a generic sync error buried in the
                // queue screen.
                var serverError: String? = null
                val ok = try {
                    withContext(Dispatchers.IO) {
                        // G-6 (phase 1 / task 2): the REAL device GPS fix (with
                        // accuracy), or NO location at all — the customer's
                        // coordinates are never sent as the employee position.
                        val fix = DeviceGps.snapshot()
                        val item = CheckinSync.enqueue(
                            "checkin", cus.id, edNote.text.toString(), voice,
                            fix?.lat, fix?.lng, fix?.accuracyM,
                        )
                        CheckinSync.flush() >= 0
                        val after = CheckinSync.itemByLocalId(item.localId)
                        if (after != null && after.status == com.medpad.tracker.db.QUEUE_STATUS_FAILED) {
                            serverError = after.lastError
                        }
                        after == null // confirmed by the server → left the queue
                    }
                } catch (e: Exception) {
                    false
                }
                recorder.release()
                progress.visibility = View.GONE
                if (ok) {
                    onDone()
                    dismiss()
                } else {
                    btnSubmit.isEnabled = true
                    tvRecState?.text = serverError ?: getString(R.string.sync_error)
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    private fun stopRec() {
        val ms = recorder.stop() // duration frozen at stop (v1.9.1)
        handler.removeCallbacks(recTicker)
        btnRec?.setImageResource(R.drawable.ic_mic)
        btnRec?.setBackgroundResource(R.drawable.voice_btn_bg)
        val sec = ms / 1000
        tvRecState?.text = getString(R.string.voice_recorded, String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60))
        if (ms > 0) btnPlay?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recorder.release()
        super.onDestroy()
    }

    override fun onStop() {
        // mission item 8 — stop the 1s clock ticker when the dialog is not
        // visible (battery honesty)
        handler.removeCallbacks(clockTicker)
        super.onStop()
    }
}
