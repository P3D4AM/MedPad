package com.medpad.tracker.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Voice recorder — v1.9.1 fixes:
 *  - startNow() is a real function call (was referenced without () — recording
 *    never started when mic permission was already granted!)
 *  - zombie MediaRecorder killed properly on stop
 *  - duration frozen at stop (no timer race)
 *  - playback is async with clear error state
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    var currentFile: File? = null
        private set
    var isRecording: Boolean = false
        private set
    var recordedMs: Long = 0
        private set
    private var startedAt: Long = 0

    val maxAmplitude: Int
        get() = try {
            if (isRecording) recorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }

    /** Start recording to a fresh m4a file. Returns true on success. */
    fun startNow(): Boolean {
        if (isRecording) return true
        return try {
            val dir = File(context.filesDir, "voice").apply { mkdirs() }
            val f = File(dir, "v_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.m4a")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = f
            startedAt = System.currentTimeMillis()
            recordedMs = 0
            isRecording = true
            true
        } catch (e: Exception) {
            releaseRecorder()
            false
        }
    }

    /** Stop recording; returns recorded duration ms (frozen, not timer-based). */
    fun stop(): Long {
        if (!isRecording) return recordedMs
        recordedMs = System.currentTimeMillis() - startedAt
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // stop() throws if nothing valid was captured
        }
        releaseRecorder()
        isRecording = false
        return recordedMs
    }

    fun discard() {
        stop()
        currentFile?.delete()
        currentFile = null
        recordedMs = 0
    }

    /** Async playback of a file; onError/onComplete run on the calling thread's looper. */
    fun play(file: File?, onDone: (() -> Unit)? = null, onError: (() -> Unit)? = null): Boolean {
        if (file == null || !file.exists()) {
            onError?.invoke()
            return false
        }
        stopPlayback()
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                stopPlayback()
                onDone?.invoke()
            }
            p.setOnErrorListener { _, _, _ ->
                onError?.invoke()
                true
            }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            releasePlayer()
            onError?.invoke()
            false
        }
    }

    fun stopPlayback() {
        try {
            player?.takeIf { it.isPlaying }?.stop()
        } catch (_: Exception) { /* ignore */ }
        releasePlayer()
    }

    fun release() {
        if (isRecording) stop()
        stopPlayback()
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) { /* ignore */ }
        recorder = null
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) { /* ignore */ }
        player = null
    }
}
