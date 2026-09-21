package me.vattitude.scribe.asr

import android.content.Context
import android.util.Log
import me.vattitude.scribe.capture.Audio
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo
import java.io.DataInputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * Transcribes finished segments while the meeting is still being recorded.
 *
 * A 30-minute meeting used to produce nothing until it ended, and then spent
 * minutes transcribing from a standing start. The audio for minute 1 is
 * complete and durable at minute 1, so there is no reason to wait for minute 30
 * to read it — this runs the accurate model over each segment as it closes, and
 * the pass that runs after the recording finishes picks up from wherever this
 * got to.
 *
 * ## The recording always wins
 *
 * This runs the 615 MB model on the same phone that is holding a live
 * AudioRecord. That is a real risk and it is worth stating what protects
 * against it, because the failure mode is losing the meeting to speed up its
 * transcript:
 *
 *  - **Only ever reads closed segments.** [SegmentWriter] rotates at
 *    [Audio.SEGMENT_MS] and the file is flushed and closed before the next one
 *    opens, so this never races the writer for a file it is still appending to.
 *  - **Lowest thread priority**, and one inference thread rather than the two
 *    the offline pass uses. The capture thread runs at MAX_PRIORITY; this one
 *    is explicitly the thing that yields.
 *  - **Never touches AudioRecord, the queue, or anything the capture loop
 *    holds.** The only shared state is the segment directory, read-only, and
 *    the database, which is already written from several threads.
 *  - **Gives up rather than competing.** If the model cannot load, or a segment
 *    fails, it stops and leaves the work to the offline pass, which will do it
 *    properly on a phone that is no longer recording.
 *
 * ## Why not just use the live transcript
 *
 * There is already a streaming pass ([me.vattitude.scribe.capture.LiveTranscriber])
 * producing text during the recording, and it stays. But it runs a small model
 * that commits words before it has heard the end of the sentence, and its
 * output is replaced wholesale by the accurate pass afterwards. This is the
 * accurate pass, started early — what it writes is final, and the segments it
 * completes are ones the post-recording pass no longer has to do.
 *
 * ## What it deliberately does not do
 *
 * No speaker separation. Clustering is only decidable over a whole meeting —
 * "speaker 1 at minute 2 is speaker 1 at minute 40" is a global fact — so it
 * stays in the pass that runs at the end. See [Diarizer].
 */
object EarlyTranscriber {

    private const val TAG = "EarlyTranscriber"

    /**
     * How far behind the recording to stay, in segments.
     *
     * One finished segment is not enough of a cushion: the writer closes
     * seg_N the instant it opens seg_N+1, and reading a file that was closed
     * milliseconds ago on a phone that is mid-write is asking for a partial
     * read. Two segments back is a full minute of slack for no real cost —
     * the point is to be minutes ahead at the end, not seconds.
     */
    private const val LAG_SEGMENTS = 2

    @Volatile private var worker: Thread? = null
    @Volatile private var running = false

    /**
     * Starts transcribing [meetingId]'s segments as they close.
     *
     * Returns immediately. Does nothing when the accurate model is missing —
     * there is no point loading a model the user has not downloaded, and the
     * offline pass already reports that case properly.
     */
    fun start(context: Context, meetingId: Long, dir: File) {
        if (running) return
        if (!ModelManager.isReady(context, ModelManager.Model.ACCURATE)) {
            Log.i(TAG, "accurate model not downloaded - early pass disabled")
            return
        }
        running = true
        worker = thread(name = "scribe-early-asr", priority = Thread.MIN_PRIORITY) {
            runCatching { loop(context.applicationContext, meetingId, dir) }
                .onFailure { Log.w(TAG, "early pass stopped", it) }
            running = false
        }
    }

    /**
     * Stops the early pass and waits briefly for it to finish the segment it is
     * on.
     *
     * The wait is bounded and short. Anything this does not finish, the offline
     * pass redoes from [Repo.setProgress], so abandoning mid-segment costs one
     * segment of duplicated work and never a gap in the transcript.
     */
    fun stop() {
        running = false
        worker?.join(3_000)
        worker = null
    }

    private fun loop(context: Context, meetingId: Long, dir: File) {
        val repo = Repo(context)
        var engine: AsrEngine? = null
        try {
            // One thread, not two. The offline pass can have the whole CPU; this
            // one is a guest on a phone that is doing something more important.
            engine = ParakeetEngine.create(context, threads = 1)
            var done = repo.meeting(meetingId)?.segmentsDone ?: 0
            var idx = repo.nextLineIdx(meetingId)
            // The live pass writes rough lines that the accurate pass replaces.
            // Clear them once, when the first accurate line is about to land,
            // rather than up front -- if the model never loads, the rough
            // transcript is better than nothing.
            var clearedLive = false

            while (running) {
                val ready = closedSegments(dir)
                if (done >= ready) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                val file = File(dir, Audio.segmentName(done))
                if (!file.exists()) { Thread.sleep(POLL_MS); continue }

                if (!clearedLive) {
                    repo.clearLines(meetingId)
                    idx = 0
                    clearedLive = true
                }

                val samples = readPcm(file)
                if (samples.isEmpty()) { done++; continue }
                val lines = engine.transcribe(samples, done * Audio.SEGMENT_MS)
                if (lines.isNotEmpty()) {
                    repo.appendLines(meetingId, lines.map { l ->
                        Line(0, meetingId, idx++, l.tStartMs, l.tEndMs, l.text, l.confidence)
                    })
                }
                done++
                // Committed after every segment, which is what lets the offline
                // pass resume exactly here rather than starting over.
                repo.setProgress(meetingId, done, engine.modelId)
                Log.i(TAG, "early pass finished segment $done of $ready")
            }
        } catch (t: Throwable) {
            // Never fail the meeting from here. The recording is unaffected and
            // the offline pass will redo whatever this did not reach.
            Log.w(TAG, "early transcription stopped early", t)
        } finally {
            try { engine?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Segments safe to read: everything the writer has certainly finished with,
     * held [LAG_SEGMENTS] behind the newest file on disk.
     */
    private fun closedSegments(dir: File): Int {
        val count = dir.listFiles { f -> f.name.endsWith(".pcm") }?.size ?: 0
        return (count - LAG_SEGMENTS).coerceAtLeast(0)
    }

    private fun readPcm(file: File): FloatArray {
        val bytes = DataInputStream(file.inputStream().buffered()).use { it.readBytes() }
        val n = bytes.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }

    /** Long enough not to spin, short enough to pick a segment up promptly. */
    private const val POLL_MS = 2_000L
}
