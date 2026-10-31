package com.medpad.tracker.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Voice recorder — mission 12 / item 10 (Nov 2026): EXPLICIT lifecycle +
 * a validity gate, plus multi-take support (item 9).
 *
 * ROOT CAUSES this class fixes (the "recorded but no valid file" report):
 *  1. stop() used to swallow its own exception and keep the file: when
 *     MediaRecorder.stop() throws, NOTHING valid was captured — the file
 *     on disk is 0 bytes or a header stub, unplayable everywhere. The old
 *     code still registered it as "recorded" and the queue uploaded it.
 *     Now a take is registered ONLY when stop() returned cleanly AND the
 *     file exists with length > 0 (state FINALIZING → SAVED); otherwise
 *     the stub is DELETED, the state is FAILED and [lastInvalidReason]
 *     carries the honest reason for the UI + the op-event log.
 *  2. Submitting while still recording uploaded a file mid-write (no
 *     final MP4 atom — unplayable server-side). The dialogs now STOP
 *     (finalize) before attaching takes to the queue.
 *  3. Re-recording used to orphan the previous file on disk forever.
 *     Takes are now a LIST (item 9): every validated take is kept, each
 *     with its own id + creation time; deleting one never touches the
 *     others, and none is silently orphaned.
 *
 * State machine (item 10):
 *   IDLE → RECORDING → FINALIZING → SAVED  (take registered)
 *                     ↘ FAILED             (stub deleted, honest reason)
 *   SAVED takes are handed to the offline queue by the dialog:
 *   Queued (pending_checkins row) → Uploading (in-flight) → Uploaded
 *   (server-confirmed; only THEN are the local files deleted) or Failed
 *   (permanent rejection — files kept locally with the event).
 *
 * v1.9.1 fixes kept: zombie recorder killed on stop, duration frozen at
 * stop (no timer race), async playback with clear error state.
 */
class VoiceRecorder(private val context: Context) {

    enum class State { IDLE, RECORDING, FINALIZING, SAVED, FAILED }

    /**
     * mission 12 / item 9 — one VALIDATED recording take. Independent id
     * and creation time per file; deleting one take never affects the
     * others.
     */
    data class Take(
        val id: String, // stable per-take id (the temp filename basis)
        val file: File,
        val durationMs: Long,
        val createdAt: Long,
    )

    var state: State = State.IDLE
        private set

    /** Honest reason of the last FAILED finalize (for the UI + op event). */
    var lastInvalidReason: String? = null
        private set

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var pendingFile: File? = null
    private var startedAt = 0L

    /** All validated takes of this dialog session (item 9). */
    val takes = mutableListOf<Take>()

    /** Last stop() duration in ms — 0 when the finalize FAILED (item 10). */
    var recordedMs: Long = 0
        private set

    val isRecording: Boolean get() = state == State.RECORDING

    val maxAmplitude: Int
        get() = try {
            if (isRecording) recorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }

    /** Legacy single-file view: the most recent validated take. */
    val currentFile: File?
        get() = takes.lastOrNull()?.file

    /** Total recorded duration across all takes. */
    fun totalDurationMs(): Long = takes.sumOf { it.durationMs }

    /** Start a NEW take (never overwrites a previous one — item 9).
     *  Returns true when the recorder actually started. */
    fun startNow(): Boolean {
        if (isRecording) return true
        lastInvalidReason = null
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
            pendingFile = f
            startedAt = System.currentTimeMillis()
            state = State.RECORDING
            true
        } catch (e: Exception) {
            // the recorder never started — no file may reach the queue
            releaseRecorder()
            pendingFile?.delete()
            pendingFile = null
            lastInvalidReason = "start-failed: ${e.javaClass.simpleName}"
            state = State.FAILED
            false
        }
    }

    /**
     * Stop the active take and FINALIZE it (item 10): the take registers
     * ONLY when stop() returned cleanly AND the file exists with
     * length > 0. A thrown stop() (nothing captured) or an empty/missing
     * file is an INVALID take: the stub is deleted, 0 is returned, the
     * state is FAILED and [lastInvalidReason] says why.
     */
    fun stop(): Long {
        if (state != State.RECORDING) return recordedMs
        state = State.FINALIZING
        val file = pendingFile
        var stopClean = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws when nothing valid was captured
            stopClean = false
            lastInvalidReason = "stop-throws: ${e.javaClass.simpleName}"
        }
        releaseRecorder()
        val duration = System.currentTimeMillis() - startedAt
        val valid = stopClean && file != null && file.exists() && file.length() > 0L
        if (!valid) {
            file?.delete()
            pendingFile = null
            if (lastInvalidReason == null) {
                lastInvalidReason = when {
                    file == null -> "no-pending-file"
                    !file.exists() -> "file-missing-after-stop"
                    else -> "empty-file(${file.length()}B)"
                }
            }
            recordedMs = 0
            state = State.FAILED
            return 0
        }
        takes.add(
            Take(
                id = file!!.nameWithoutExtension,
                file = file,
                durationMs = duration,
                createdAt = System.currentTimeMillis(),
            ),
        )
        pendingFile = null
        recordedMs = duration
        state = State.SAVED
        return duration
    }

    /** Stop if still recording (e.g. the user hit submit mid-take) so a
     *  file is never attached to the queue while still being written. */
    fun stopIfRecording(): Long = if (isRecording) stop() else recordedMs

    /** mission 12 / item 9 — remove ONE take (its file is deleted; the
     *  other takes and their files are untouched). */
    fun removeTake(take: Take) {
        if (takes.remove(take)) {
            take.file.delete()
        }
    }

    /** mission 12 / item 10 — ownership hand-over: the offline queue now
     *  references the take files (they live until the server confirms the
     *  event, then CheckinSync deletes them). Clears the take list WITHOUT
     *  deleting anything — a later dialog cancel must never destroy files
     *  a queued item still points at. */
    fun handOverTakes() {
        takes.clear()
        pendingFile?.delete()
        pendingFile = null
        recordedMs = 0
        state = State.IDLE
    }

    /** Discard EVERYTHING (dialog cancel): stop the active take and delete
     *  every take file of this session. */
    fun discard() {
        if (isRecording) stop()
        takes.forEach { it.file.delete() }
        takes.clear()
        pendingFile?.delete()
        pendingFile = null
        recordedMs = 0
        lastInvalidReason = null
        state = State.IDLE
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
        if (isRecording) stopIfRecording()
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
