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
 */
class CheckinDialogFragment(private val onDone: () -> Unit) : DialogFragment() {

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

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_checkin, null)
        tvRecState = view.findViewById(R.id.tvRecState)
        btnRec = view.findViewById(R.id.btnRec)
        btnPlay = view.findViewById(R.id.btnPlay)

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
                val ok = try {
                    withContext(Dispatchers.IO) {
                        // G-6 (phase 1 / task 2): the REAL device GPS fix (with
                        // accuracy), or NO location at all — the customer's
                        // coordinates are never sent as the employee position.
                        val fix = DeviceGps.snapshot()
                        CheckinSync.enqueue(
                            "checkin", cus.id, edNote.text.toString(), voice,
                            fix?.lat, fix?.lng, fix?.accuracyM,
                        )
                        CheckinSync.flush() >= 0
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
                    tvRecState?.text = getString(R.string.sync_error)
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
}
