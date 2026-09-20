package me.vattitude.scribe.capture

import android.content.Context
import android.util.Log
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.asr.StreamingEngine
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Repo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs the streaming recognizer alongside the recorder.
 *
 * Deliberately decoupled from capture by a bounded queue. The recorder pushes and
 * moves on; if inference cannot keep up, buffers are dropped here rather than
 * allowed to stall `AudioRecord`. Dropping degrades the live view — the audio file
 * on disk is always complete, so the offline pass can still produce a perfect
 * transcript later.
 */
class LiveTranscriber(
    private val context: Context,
    private val repo: Repo,
    private val meetingId: Long
) {
    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var committed = 0
    @Volatile private var dropped = 0

    /** @return true if the recognizer loaded and live transcription is running. */
    fun start(): Boolean {
        if (!ModelManager.isReady(context, ModelManager.Model.LIVE)) {
            Log.i(TAG, "no live model — live transcription disabled")
            return false
        }
        running = true
        val t = Thread({ loop() }, "scribe-live-asr")
        t.priority = Thread.NORM_PRIORITY - 1
        thread = t
        t.start()
        return true
    }

    /** Non-blocking. Converts PCM16 to float and hands off; drops if the queue is full. */
    fun offer(buf: ShortArray, count: Int) {
        if (!running) return
        val f = FloatArray(count)
        for (i in 0 until count) f[i] = buf[i] / 32768f
        if (!queue.offer(f)) dropped++
    }

    /** @return how many finished lines were written. */
    fun stop(): Int {
        if (!running) return 0
        running = false
        thread?.join(15_000)
        thread = null
        if (dropped > 0) Log.w(TAG, "dropped $dropped buffers from the live view")
        return committed
    }

    private fun loop() {
        var engine: StreamingEngine? = null
        try {
            engine = StreamingEngine.create(context)
            var idx = repo.nextLineIdx(meetingId)

            while (running || queue.isNotEmpty()) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val line = engine.accept(chunk, chunk.size)
                LiveTranscript.setPartial(engine.partial)
                if (line != null) {
                    repo.appendLines(meetingId, listOf(
                        Line(0, meetingId, idx++, line.tStartMs, line.tEndMs, line.text, line.confidence)
                    ))
                    committed++
                    LiveTranscript.append(line.text)
                }
            }

            engine.finish()?.let {
                repo.appendLines(meetingId, listOf(
                    Line(0, meetingId, idx, it.tStartMs, it.tEndMs, it.text, it.confidence)
                ))
                committed++
                LiveTranscript.append(it.text)
            }
            LiveTranscript.setPartial("")
        } catch (e: Throwable) {
            // The recording itself is unaffected — audio is already on disk, and
            // RecorderService falls back to the offline pass when we commit nothing.
            Log.e(TAG, "live transcription failed", e)
            running = false
        } finally {
            try { engine?.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "LiveTranscriber"
        // ~8 seconds of audio in flight. Past that, inference is not keeping up
        // and buffering further would only add latency to a view meant to be live.
        private const val QUEUE_CAPACITY = 16
    }
}
