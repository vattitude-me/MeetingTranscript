package me.vattitude.scribe.store

import android.content.Context
import java.io.File

/**
 * The few things the user gets to decide. Deliberately small — every setting is
 * a question the app failed to answer for them.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("scribe.settings", Context.MODE_PRIVATE)

    /**
     * Delete a meeting's audio once it has been transcribed successfully.
     *
     * Defaults ON, per PLAN.md §11: the recordings are of other people, audio is
     * the most sensitive thing on disk and by far the largest (~115 MB/hour),
     * and once there is a transcript the audio has done its job. Turning it off
     * is a deliberate choice to keep it — for re-transcription with a better
     * model, or to hear a line back.
     */
    var deleteAudioAfterTranscribe: Boolean
        get() = prefs.getBoolean(KEY_DELETE_AUDIO, true)
        set(v) = prefs.edit().putBoolean(KEY_DELETE_AUDIO, v).apply()

    /**
     * How long transcribed audio survives before [deleteAudioAfterTranscribe]
     * collects it. Zero means immediately, which is what the app did before.
     *
     * The reason this is not zero any more: speaker separation reads waveforms,
     * so once the audio is gone the speaker count is frozen at whatever the
     * first pass guessed. That guess is measurably unreliable — across a
     * ten-sample matrix it both over-counted (a one-voice recording came back as
     * six) and under-counted (five voices came back as three). "Fix speaker
     * count" re-runs it with the true number, and it was unreachable for every
     * meeting on the default path, because the audio it needs had already been
     * deleted seconds after transcription finished.
     *
     * A grace period is the smaller change than reversing the default. The
     * privacy claim — that audio does not accumulate on disk — is about steady
     * state, not about the first day. A window long enough to notice a wrong
     * label and act on it costs one meeting's worth of disk, and it expires on
     * its own with no further decision from the user.
     */
    var audioGraceHours: Int
        get() = prefs.getInt(KEY_GRACE_HOURS, DEFAULT_GRACE_HOURS)
        set(v) = prefs.edit().putInt(KEY_GRACE_HOURS, v.coerceIn(0, MAX_GRACE_HOURS)).apply()

    /** True when [startedAt] is old enough that its audio may now be collected. */
    fun graceExpired(startedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - startedAt >= audioGraceHours * 3_600_000L

    companion object {
        private const val KEY_DELETE_AUDIO = "delete_audio_after_transcribe"
        private const val KEY_GRACE_HOURS = "audio_grace_hours"

        /**
         * 24 hours. Long enough to read a transcript the next morning and
         * notice the speaker labels are wrong; short enough that audio is not
         * quietly accumulating. [MAX_GRACE_HOURS] caps what the UI can set.
         */
        const val DEFAULT_GRACE_HOURS = 24
        const val MAX_GRACE_HOURS = 48

        /** Bytes of PCM currently on disk across every meeting. */
        fun audioBytes(context: Context): Long {
            val root = File(context.filesDir, "meetings")
            if (!root.isDirectory) return 0
            return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        fun format(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }
    }
}
