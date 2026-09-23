package me.vattitude.scribe.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import me.vattitude.scribe.capture.Audio
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays a meeting's raw segments from any point, so a line can be checked
 * against what was actually said. The audio is headerless 16 kHz mono PCM in
 * [Audio.SEGMENT_MS] files, which AudioTrack takes as it is — no decoder, no
 * temporary WAV.
 *
 * One playback at a time; [play] stops whatever was playing. Callbacks arrive
 * on the main thread.
 */
class PcmPlayer(
    private val onPosition: (Long) -> Unit,
    private val onEnd: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    val isPlaying: Boolean get() = thread != null

    fun play(segments: List<File>, fromMs: Long) {
        stop()
        val t = Thread({ run(segments, fromMs.coerceAtLeast(0)) }, "pcm-player")
        thread = t
        t.start()
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        runCatching { track?.pause(); track?.flush() }
        t.interrupt()
        t.join(500)
    }

    private fun run(segments: List<File>, fromMs: Long) {
        val me = Thread.currentThread()
        val minBuf = AudioTrack.getMinBufferSize(
            Audio.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Audio.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
        try {
            at.play()
            val first = (fromMs / Audio.SEGMENT_MS).toInt()
            val buf = ByteArray(minBuf)
            var lastReport = 0L
            for (i in first until segments.size) {
                RandomAccessFile(segments[i], "r").use { f ->
                    var pos = segmentOffset(i, first, fromMs)
                    // Segments are written whole, but the last one of an
                    // interrupted recording can be short; clamp, never seek past.
                    pos = pos.coerceAtMost(f.length() and 1L.inv())
                    f.seek(pos)
                    while (thread === me) {
                        val n = f.read(buf)
                        if (n <= 0) break
                        at.write(buf, 0, n and 1.inv())
                        pos += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            val ms = i * Audio.SEGMENT_MS + Audio.bytesToMs(pos)
                            main.post { onPosition(ms) }
                        }
                    }
                }
                if (thread !== me) return
            }
            // Let what is buffered finish rather than cutting the last words.
            if (thread === me) Thread.sleep(300)
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            runCatching { at.stop() }
            at.release()
            if (track === at) track = null
            if (thread === me) {
                thread = null
                main.post(onEnd)
            }
        }
    }

    private fun segmentOffset(i: Int, first: Int, fromMs: Long): Long {
        if (i != first) return 0
        val intoSegmentMs = fromMs - first * Audio.SEGMENT_MS
        return (intoSegmentMs * Audio.SAMPLE_RATE / 1000) * Audio.BYTES_PER_SAMPLE
    }
}
