package me.vattitude.scribe.asr

/** One transcribed span of audio, already offset into meeting-relative time. */
data class AsrLine(
    val tStartMs: Long,
    val tEndMs: Long,
    val text: String,
    val confidence: Double
)

/**
 * Everything the rest of the app knows about speech recognition.
 *
 * Kept deliberately small so the engine can be swapped — Parakeet today,
 * Whisper or whatever wins the next leaderboard round tomorrow — without
 * touching the recorder, the job queue or the UI.
 */
interface AsrEngine : AutoCloseable {
    val modelId: String

    /**
     * @param samples mono PCM, [me.vattitude.scribe.capture.Audio.SAMPLE_RATE], normalised to -1..1
     * @param offsetMs where this buffer starts within the meeting
     */
    fun transcribe(samples: FloatArray, offsetMs: Long): List<AsrLine>
}
