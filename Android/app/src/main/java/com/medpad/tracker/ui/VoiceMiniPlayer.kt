package com.medpad.tracker.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * mission 12 / item 6 (Nov 2026) — the «گزارش‌های من» mini player.
 *
 * Replaces the old bare Play button (download → MediaPlayer.start, no
 * controls). Requirements implemented here:
 *  - play / pause / RESUME-from-pause / stop
 *  - played / total duration display + a seek bar
 *  - playback speed 1× / 1.5× / 2× (cycle)
 *  - RESUME FROM BREAKPOINT: the last position of every voice file is
 *    remembered (companion map — survives list scrolls AND activity
 *    recreation) and replay continues where it left off
 *  - loading and error states with an honest Persian text; a failed file's
 *    cache entry is dropped so the next tap retries the download
 *  - MULTIPLE files per report (mission item 9): one player row per
 *    recording, all playable, with NO simultaneous playback — exactly ONE
 *    MediaPlayer exists per screen and starting row B stops row A
 *  - resources are RELEASED on completion/stop/teardown (no leaked players)
 *
 * Pure view plumbing (no framework Fragments) so it also renders inside a
 * RecyclerView row; the hub is activity-scoped and released in onDestroy.
 */
class VoiceMiniPlayerHub(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        /** resume-from-breakpoint memory: voice file id → last position ms.
         *  Static on purpose — rotating the screen must not lose a rep's
         *  listening position. Cleared never (bounded by the number of voice
         *  files the rep has played this process). */
        private val resumeAt = HashMap<String, Int>()

        private val SPEEDS = floatArrayOf(1f, 1.5f, 2f)
        private val SPEED_LABELS = arrayOf("×۱", "×۱٫۵", "×۲")
    }

    private var player: MediaPlayer? = null
    private var active: Row? = null
    private val handler = Handler(Looper.getMainLooper())

    /** One player UI row per voice file. */
    private inner class Row(val fileId: String, val url: String) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnPlayPause = ImageButton(context).apply {
            setImageResource(R.drawable.ic_play)
            background = null
            contentDescription = context.getString(R.string.play)
        }
        val btnStop = ImageButton(context).apply {
            setImageResource(R.drawable.ic_stop)
            background = null
            contentDescription = context.getString(R.string.player_stop)
        }
        val btnSpeed = TextView(context).apply {
            text = SPEED_LABELS[0]
            textSize = 12f
            setPadding(dp(8), 0, dp(8), 0)
        }
        val seek = SeekBar(context).apply { isEnabled = false; max = 0 }
        val tvTime = TextView(context).apply { textSize = 11f }
        val tvState = TextView(context).apply { textSize = 11f }
        var speedIdx = 0
        var prepared = false
        var playing = false
        var durationMs = 0

        init {
            val pad = dp(2)
            root.setPadding(0, pad, 0, pad)
            root.addView(btnPlayPause)
            root.addView(btnStop)
            root.addView(btnSpeed)
            seek.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
            root.addView(seek)
            root.addView(tvTime)
            root.addView(tvState)
            setState("")
        }

        fun setState(s: String) {
            tvState.text = s
            tvState.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }

        fun renderTime(posMs: Int) {
            tvTime.text = "${clock(posMs)} / ${if (durationMs > 0) clock(durationMs) else "−:−−"}"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val r = active ?: return
            if (r.playing) {
                val pos = try { p.currentPosition } catch (_: Exception) { 0 }
                r.seek.progress = pos
                r.renderTime(pos)
                handler.postDelayed(this, 300)
            }
        }
    }

    /** Build the UI row for ONE voice file (transport controls + time). */
    fun buildRow(fileId: String, url: String): View {
        val row = Row(fileId, url)
        row.btnPlayPause.setOnClickListener { playPause(row) }
        row.btnStop.setOnClickListener { stop(row) }
        row.btnSpeed.setOnClickListener {
            row.speedIdx = (row.speedIdx + 1) % SPEEDS.size
            row.btnSpeed.text = SPEED_LABELS[row.speedIdx]
            if (active === row) {
                try { player?.playbackRate = SPEEDS[row.speedIdx] } catch (_: Exception) { }
            }
        }
        row.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val p = player ?: return
                if (active === row && row.prepared) {
                    val v = sb?.progress ?: 0
                    try {
                        p.seekTo(v)
                        row.renderTime(v)
                        resumeAt[row.fileId] = v
                    } catch (_: Exception) { }
                }
            }
        })
        return row.root
    }

    /** Leading label for a row («صوت ۱») — returned separately so the caller
     *  controls the row composition. */
    fun labelView(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 0, dp(6), 0)
    }

    // ── transport ────────────────────────────────────────────────────

    private fun playPause(row: Row) {
        val p = player
        if (active === row && p != null && row.prepared) {
            if (row.playing) {
                // pause — remember the position (resume from breakpoint)
                try {
                    resumeAt[row.fileId] = p.currentPosition
                    p.pause()
                } catch (_: Exception) { }
                row.playing = false
                row.btnPlayPause.setImageResource(R.drawable.ic_play)
                row.btnPlayPause.contentDescription = context.getString(R.string.player_resume)
                row.setState(context.getString(R.string.player_paused))
            } else {
                try { p.start() } catch (_: Exception) { }
                row.playing = true
                row.btnPlayPause.setImageResource(R.drawable.ic_pause)
                row.btnPlayPause.contentDescription = context.getString(R.string.player_pause)
                row.setState("")
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            return
        }
        startNew(row)
    }

    private fun startNew(row: Row) {
        // single-flight: exactly one audible row — starting B stops A
        stopActive()
        active = row
        row.setState(context.getString(R.string.player_loading))
        row.btnPlayPause.isEnabled = false
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    cachedOrDownload(row.fileId, row.url)
                } catch (_: Exception) {
                    null
                }
            }
            if (file == null) {
                row.btnPlayPause.isEnabled = true
                row.setState(context.getString(R.string.voice_play_failed))
                if (active === row) active = null
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                preparePlayer(file)
            }
            row.btnPlayPause.isEnabled = true
            if (ok == null) {
                // unplayable file (corrupt download?) — drop the cache entry
                // so the NEXT tap re-downloads honestly
                file.delete()
                row.setState(context.getString(R.string.voice_play_failed))
                if (active === row) active = null
                return@launch
            }
            player = ok
            row.prepared = true
            row.durationMs = try { ok.duration } catch (_: Exception) { 0 }
            row.seek.max = row.durationMs
            row.seek.isEnabled = true
            val resume = resumeAt[row.fileId] ?: 0
            if (resume > 0 && resume < row.durationMs) {
                try { ok.seekTo(resume) } catch (_: Exception) { }
            }
            try {
                ok.playbackRate = SPEEDS[row.speedIdx]
                ok.start()
            } catch (_: Exception) {
                row.setState(context.getString(R.string.voice_play_failed))
                return@launch
            }
            row.playing = true
            row.btnPlayPause.setImageResource(R.drawable.ic_pause)
            row.btnPlayPause.contentDescription = context.getString(R.string.player_pause)
            row.setState("")
            row.renderTime(resume)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    private fun preparePlayer(file: File): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                val r = active
                if (r != null) {
                    resumeAt[r.fileId] = 0 // played to the end — next play starts over
                    r.playing = false
                    r.prepared = false
                    r.seek.progress = 0
                    r.renderTime(0)
                    r.btnPlayPause.setImageResource(R.drawable.ic_play)
                    r.setState(context.getString(R.string.player_ended))
                }
                releasePlayer()
            }
            prepare()
        }
    } catch (_: Exception) {
        null
    }

    private fun stop(row: Row) {
        if (active !== row) return
        val p = player ?: return
        resumeAt[row.fileId] = 0 // explicit stop = start over next time
        try { p.stop() } catch (_: Exception) { }
        releasePlayer()
        row.playing = false
        row.prepared = false
        row.seek.isEnabled = false
        row.seek.progress = 0
        row.renderTime(0)
        row.btnPlayPause.setImageResource(R.drawable.ic_play)
        row.setState("")
        active = null
    }

    /** Stop whatever is playing, remembering its position (row switch). */
    private fun stopActive() {
        val r = active ?: return
        val p = player
        if (p != null && r.playing) {
            try { resumeAt[r.fileId] = p.currentPosition } catch (_: Exception) { }
        }
        try { p?.stop() } catch (_: Exception) { }
        releasePlayer()
        r.playing = false
        r.prepared = false
        r.seek.isEnabled = false
        r.btnPlayPause.setImageResource(R.drawable.ic_play)
        if (r.tvState.text.toString() == context.getString(R.string.player_paused) ||
            r.tvState.text.isNotEmpty()
        ) r.setState("")
        active = null
    }

    /** Activity teardown — every resource released. */
    fun release() {
        handler.removeCallbacks(ticker)
        val r = active
        val p = player
        if (r != null && p != null && r.playing) {
            try { resumeAt[r.fileId] = p.currentPosition } catch (_: Exception) { }
        }
        releasePlayer()
        active = null
    }

    private fun releasePlayer() {
        handler.removeCallbacks(ticker)
        try {
            player?.release()
        } catch (_: Exception) { /* ignore */ }
        player = null
    }

    // ── download cache (per voice FILE id — mission item 9) ──────────

    private fun cacheFile(fileId: String): File {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        return File(dir, "$fileId.m4a")
    }

    private suspend fun cachedOrDownload(fileId: String, url: String): File {
        val f = cacheFile(fileId)
        if (f.exists() && f.length() > 0) return f
        val bytes = ApiClient.httpCall(url)
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    private fun clock(ms: Int): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
