package me.vattitude.scribe.capture

/**
 * One format, everywhere: 16 kHz mono 16-bit PCM.
 *
 * This is exactly what Parakeet and Whisper consume, so there is no resampling
 * and no decode step between the microphone and the model. It costs ~32 KB/s
 * (~115 MB/hour), which is the cheapest thing in this app.
 */
object Audio {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2

    /** Rolling segment length. A crash or an OS kill loses at most this much audio. */
    const val SEGMENT_MS = 30_000L
    const val SEGMENT_SAMPLES = (SAMPLE_RATE * SEGMENT_MS / 1000).toInt()

    fun bytesToMs(bytes: Long): Long = bytes * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)
    fun segmentName(index: Int): String = "seg_%05d.pcm".format(index)
}
