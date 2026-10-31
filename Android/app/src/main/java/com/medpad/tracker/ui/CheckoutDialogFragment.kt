package com.medpad.tracker.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.location.DeviceGps
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.TrackerContext
import com.medpad.tracker.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Checkout dialog — mandatory text (≥10 chars, paste disabled) + mandatory voice.
 * Closes ALL open visits server-side (v1.9.1).
 * mission item 7 — when the hub knows the open visit (server answered),
 * its identity is shown BEFORE the submit: customer, check-in time,
 * planned/plan-less and the elapsed time, so the rep closes the right
 * visit consciously. A null openVisit (offline / legacy panel) keeps the
 * old behaviour.
 */
// Package 2 (crash hardening): default for onDone → no-arg constructor exists
// → the fragment survives re-instantiation on configuration change instead of
// crashing the app ("Fragment could not be instantiated" on rotation).
class CheckoutDialogFragment(
    private val openVisit: com.medpad.tracker.sync.CheckinGate.OpenVisit? = null,
    private val onDone: () -> Unit = {},
) : DialogFragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val recorder = VoiceRecorder(TrackerContext.app)
    private val handler = Handler(Looper.getMainLooper())
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
    private var boxTakes: LinearLayout? = null

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_checkout, null)
        tvRecState = view.findViewById(R.id.tvRecState)
        btnRec = view.findViewById(R.id.btnRec)
        boxTakes = view.findViewById(R.id.boxTakes)
        // mission 12 / item 5 — on checkout the voice is REQUIRED (server
        // contract: no voice → 400); the label carries the star.
        tvRecState?.text = getString(R.string.record_voice_required)

        val edNote = view.findViewById<NoPasteEditText>(R.id.edNote)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmit)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        // mission item 7 — the open visit's identity, before the submit:
        // WHAT is being closed, since when, planned or plan-less, elapsed.
        openVisit?.let { v ->
            val cal = java.util.Calendar.getInstance().apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran")
                v.startAtMs?.let { timeInMillis = it }
            }
            val time = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            val j = JalaliDate.fromGregorian(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
            val elapsed = com.medpad.tracker.sync.CheckinGate.elapsedLabel(System.currentTimeMillis(), v.startAtMs)
            val info = TextView(requireContext()).apply {
                text = getString(
                    R.string.checkout_open_info,
                    v.customerName ?: getString(R.string.customer_unknown),
                    "$time (${JalaliDate.formatFa(j)})",
                    getString(if (v.planned) R.string.checkin_open_planned else R.string.checkin_open_planless),
                    elapsed ?: getString(R.string.elapsed_unknown),
                )
                textSize = 13f
                setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
            }
            (view as? android.view.ViewGroup)?.addView(info, 0)
        }

        btnRec?.setOnClickListener {
            if (recorder.isRecording) {
                stopRec()
            } else {
                if (recorder.startNow()) {
                    startedAt = System.currentTimeMillis()
                    btnRec?.setImageResource(R.drawable.ic_stop)
                    btnRec?.setBackgroundResource(R.drawable.rec_active)
                    handler.post(recTicker)
                } else {
                    // mission 12 / item 10 — honest failure, nothing registered
                    tvRecState?.text = getString(R.string.voice_start_failed)
                    scope.launch {
                        com.medpad.tracker.sync.OpEvents.record(
                            type = "voice.invalid",
                            status = "failed",
                            errorCode = recorder.lastInvalidReason,
                            message = "شروع ضبط ناموفق بود",
                        )
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            recorder.discard()
            dismiss()
        }

        btnSubmit.setOnClickListener {
            val note = edNote.text?.toString()?.trim() ?: ""
            if (note.length < 10) {
                edNote.error = getString(R.string.checkout_min_chars)
                return@setOnClickListener
            }
            // mission 12 / item 10 — finalize a take that is still being
            // written BEFORE attaching it (mid-write uploads used to produce
            // unplayable server-side files)
            recorder.stopIfRecording()
            renderTakes()
            // mission 12 / item 9 — checkout requires at least ONE validated
            // take (the server enforces the same rule)
            val voices = recorder.takes.map { it.file }
            if (voices.isEmpty()) {
                tvRecState?.text = getString(R.string.checkout_voice_required)
                return@setOnClickListener
            }
            btnSubmit.isEnabled = false
            progress.visibility = View.VISIBLE
            scope.launch {
                // mission item 6 — surface the SERVER's rejection text (e.g.
                // the independent check-out geofence 422 with the exact
                // distance + allowed radius) in the dialog itself.
                var serverError: String? = null
                var enqueued = false
                val ok = try {
                    withContext(Dispatchers.IO) {
                        // G-6 (phase 1 / task 2): real device GPS fix (with
                        // accuracy) or no location — never fabricated.
                        val fix = DeviceGps.snapshot()
                        val item = CheckinSync.enqueue(
                            "checkout", null, note, voices,
                            fix?.lat, fix?.lng, fix?.accuracyM,
                        )
                        enqueued = true
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
                // mission 12 / item 10 — the queue item owns the files now; a
                // later cancel must never delete a queued item's files
                if (enqueued) recorder.handOverTakes()
                recorder.release()
                progress.visibility = View.GONE
                if (ok) {
                    onDone()
                    dismiss()
                } else {
                    btnSubmit.isEnabled = true
                    if (serverError != null) {
                        tvRecState?.text = serverError
                    } else {
                        Toast.makeText(requireContext(), R.string.sync_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    private fun stopRec() {
        // mission 12 / item 10 — FINALIZING with the validity gate (same as
        // the check-in dialog): an invalid take is never registered.
        val ms = recorder.stop()
        handler.removeCallbacks(recTicker)
        btnRec?.setImageResource(R.drawable.ic_mic)
        btnRec?.setBackgroundResource(R.drawable.voice_btn_bg)
        if (ms <= 0 || recorder.state == VoiceRecorder.State.FAILED) {
            val reason = recorder.lastInvalidReason ?: "empty"
            tvRecState?.text = getString(R.string.voice_invalid, reason)
            renderTakes()
            scope.launch {
                com.medpad.tracker.sync.OpEvents.record(
                    type = "voice.invalid",
                    status = "failed",
                    errorCode = reason,
                    relatedType = "checkout",
                    message = "ضبط نامعتبر — در صف ثبت نشد",
                )
            }
            return
        }
        val sec = ms / 1000
        tvRecState?.text = getString(
            R.string.voice_recorded_more,
            String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60),
        )
        renderTakes()
    }

    /** mission 12 / item 9 — the takes list (same rows as the check-in
     *  dialog: number + duration, preview, delete — independent files). */
    private fun renderTakes() {
        val box = boxTakes ?: return
        box.removeAllViews()
        val pad = (4 * resources.displayMetrics.density).toInt()
        recorder.takes.forEachIndexed { idx, take ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
            }
            val sec = take.durationMs / 1000
            val label = TextView(requireContext()).apply {
                text = getString(
                    R.string.voice_take_label,
                    (idx + 1).toString(),
                    String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60),
                )
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnPlayTake = ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_play)
                background = null
                contentDescription = getString(R.string.play)
                setOnClickListener {
                    recorder.play(take.file, onError = {
                        tvRecState?.text = getString(R.string.voice_play_failed)
                    })
                }
            }
            val btnDel = android.widget.Button(requireContext()).apply {
                text = getString(R.string.voice_take_delete)
                textSize = 12f
                isAllCaps = false
                minHeight = 0
                minWidth = 0
                setPadding(pad * 3, pad, pad * 3, pad)
                setOnClickListener {
                    recorder.removeTake(take)
                    renderTakes()
                }
            }
            row.addView(label)
            row.addView(btnPlayTake)
            row.addView(btnDel)
            box.addView(row)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recorder.release()
        super.onDestroy()
    }
}
