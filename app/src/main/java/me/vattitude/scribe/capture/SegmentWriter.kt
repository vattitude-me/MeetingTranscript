package me.vattitude.scribe.capture

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes raw little-endian PCM to numbered segment files, rotating every
 * [Audio.SEGMENT_MS].
 *
 * The recorder and the transcriber are coupled only through this directory —
 * never through memory. That is what makes an ASR crash survivable: the audio
 * is already durable on disk before anything tries to read it.
 */
class SegmentWriter(private val dir: File) {

    private var out: BufferedOutputStream? = null
    private var samplesInSegment = 0
    var segmentIndex = 0
        private set
    var totalSamples = 0L
        private set

    init {
        dir.mkdirs()
    }

    fun write(buf: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            if (out == null) openSegment()
            val room = Audio.SEGMENT_SAMPLES - samplesInSegment
            val n = minOf(room, count - offset)

            val bytes = ByteArray(n * Audio.BYTES_PER_SAMPLE)
            for (i in 0 until n) {
                val v = buf[offset + i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out!!.write(bytes)

            samplesInSegment += n
            totalSamples += n
            offset += n

            if (samplesInSegment >= Audio.SEGMENT_SAMPLES) rotate()
        }
    }

    /** Flush to disk without closing, so a kill mid-segment still leaves usable audio. */
    fun flush() {
        out?.flush()
    }

    fun close(): Int {
        out?.let { it.flush(); it.close() }
        out = null
        return if (samplesInSegment > 0) segmentIndex + 1 else segmentIndex
    }

    private fun openSegment() {
        out = BufferedOutputStream(FileOutputStream(File(dir, Audio.segmentName(segmentIndex))), 64 * 1024)
        samplesInSegment = 0
    }

    private fun rotate() {
        out?.let { it.flush(); it.close() }
        out = null
        segmentIndex++
        samplesInSegment = 0
    }
}
