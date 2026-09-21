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

    /**
     * Whether to attempt speaker separation at all. **Off by default.**
     *
     * It was on, and the default was wrong. The evidence that turned it off is
     * a ten-minute recording shaped like a real meeting: ten people present,
     * three carrying 98% of the talking between them, seven saying one short
     * thing each. Guessing returned two voices. Told three — the true answer to
     * the question the app asked — it still returned two, fusing two of the
     * three main speakers. Told four it returned three, one of them holding 68%
     * where the real speakers held about a third each.
     *
     * The earlier measurements that justified shipping this used 60-second to
     * five-minute samples with the talking split evenly, and on those the
     * clusterer obeyed a supplied count exactly. That is the whole gap: even
     * turn-taking between two or three people is the easy case, and it is not
     * what a meeting is.
     *
     * What makes this worth defaulting off rather than merely tuning is the
     * shape of the failure. A missing label is obviously missing. A wrong label
     * reads as fact — the transcript says Priya proposed the thing Sam opposed,
     * and nothing on screen suggests otherwise. Attributing words to the wrong
     * person is a worse product than attributing them to nobody.
     *
     * The code is all still here and this switch turns it back on, because the
     * models are the weak part, not the plumbing. A better on-device
     * diarization model makes this a default change and nothing else.
     */
    var identifySpeakers: Boolean
        get() = prefs.getBoolean(KEY_IDENTIFY_SPEAKERS, false)
        set(v) = prefs.edit().putBoolean(KEY_IDENTIFY_SPEAKERS, v).apply()

    /**
     * Minutes of recorded audio after which the app asks whether to keep going,
     * and pauses if nobody answers. Zero disables the check entirely.
     *
     * Nothing used to stop a recording. Left running it would fill the disk and
     * flatten the battery, and the way that happens is not a user deciding to
     * record for six hours — it is a meeting that ended without anyone pressing
     * stop. The check exists for the forgotten recording, so it must not
     * interrupt a real one that is simply long: the prompt is answerable with
     * one tap, and answering it buys another full interval.
     *
     * It pauses rather than stops. A missed prompt during a real meeting costs
     * the gap until someone notices, which is recoverable; stopping would end
     * the meeting and lose everything said afterwards. Pause is the failure
     * worth having.
     *
     * Counted in recorded audio, not wall-clock, so a recording left paused
     * overnight does not burn through its interval while writing nothing.
     */
    var checkInMinutes: Int
        get() = prefs.getInt(KEY_CHECK_IN_MINUTES, DEFAULT_CHECK_IN_MINUTES)
        set(v) = prefs.edit().putInt(KEY_CHECK_IN_MINUTES, v.coerceAtLeast(0)).apply()

    companion object {
        private const val KEY_DELETE_AUDIO = "delete_audio_after_transcribe"
        private const val KEY_GRACE_HOURS = "audio_grace_hours"
        private const val KEY_CHECK_IN_MINUTES = "check_in_minutes"
        private const val KEY_IDENTIFY_SPEAKERS = "identify_speakers"

        /**
         * 30 minutes, which is the length of the meeting this app is mostly
         * pointed at. Long enough that a standup or a one-to-one finishes
         * without ever seeing the prompt, short enough that a recording
         * forgotten at the end of one is caught before it costs anything.
         */
        const val DEFAULT_CHECK_IN_MINUTES = 30

        /** Offered in Settings. 0 is "never", for the user who records lectures. */
        val CHECK_IN_CHOICES = listOf(0, 30, 60, 90, 120)

        /**
         * How long the prompt waits for an answer before pausing. Two minutes is
         * long enough to surface on a locked phone and be seen, short enough
         * that a genuinely abandoned recording is not still writing ten minutes
         * later.
         */
        const val CHECK_IN_GRACE_MS = 120_000L

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
