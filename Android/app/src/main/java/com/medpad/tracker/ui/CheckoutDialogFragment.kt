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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
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
 */
class CheckoutDialogFragment(private val onDone: () -> Unit) : DialogFragment() {

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

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_checkout, null)
        tvRecState = view.findViewById(R.id.tvRecState)
        btnRec = view.findViewById(R.id.btnRec)

        val edNote = view.findViewById<NoPasteEditText>(R.id.edNote)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmit)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        btnRec?.setOnClickListener {
            if (recorder.isRecording) {
                stopRec()
            } else {
                if (recorder.startNow()) {
                    startedAt = System.currentTimeMillis()
                    btnRec?.setImageResource(R.drawable.ic_stop)
                    btnRec?.setBackgroundResource(R.drawable.rec_active)
                    handler.post(recTicker)
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
            val voice = recorder.currentFile?.takeIf { it.exists() }
            if (voice == null) {
                tvRecState?.text = getString(R.string.checkout_voice_required)
                return@setOnClickListener
            }
            btnSubmit.isEnabled = false
            progress.visibility = View.VISIBLE
            scope.launch {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        CheckinSync.enqueue("checkout", null, note, voice, null, null)
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
                    Toast.makeText(requireContext(), R.string.sync_error, Toast.LENGTH_SHORT).show()
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    private fun stopRec() {
        val ms = recorder.stop()
        handler.removeCallbacks(recTicker)
        btnRec?.setImageResource(R.drawable.ic_mic)
        btnRec?.setBackgroundResource(R.drawable.voice_btn_bg)
        val sec = ms / 1000
        tvRecState?.text = getString(R.string.voice_recorded, String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recorder.release()
        super.onDestroy()
    }
}
