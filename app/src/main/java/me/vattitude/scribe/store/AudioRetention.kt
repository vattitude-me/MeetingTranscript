package me.vattitude.scribe.store

import android.content.Context
import android.util.Log
import me.vattitude.scribe.asr.Diarizer

/**
 * Collects the audio of meetings whose grace period has run out.
 *
 * Transcription used to delete a meeting's audio the moment it finished. That
 * made "fix speaker count" impossible on the default settings, because
 * re-running speaker separation needs the waveforms and they were already gone.
 * See [Settings.audioGraceHours] for why the window exists rather than the
 * default being reversed.
 *
 * A window is only a window if something closes it. This is that something:
 * a sweep over every transcribed meeting, deleting the audio of any that is
 * older than the grace period. It is deliberately not a scheduled job —
 * WorkManager would keep the promise even when the app is never opened, but it
 * also means a background process deleting user data on a timer the user cannot
 * see. Running it on app start and after each transcription keeps deletion tied
 * to moments the user caused, and the worst case is that audio survives until
 * the next launch of an app that is not being used.
 *
 * Every call is safe to repeat: a meeting with no audio left is skipped, and a
 * failed delete is logged and retried on the next sweep rather than throwing.
 */
object AudioRetention {

    private const val TAG = "AudioRetention"

    /**
     * Deletes audio for transcribed meetings past their grace period.
     *
     * @return how many meetings had their audio collected.
     */
    fun sweep(context: Context): Int {
        val settings = Settings(context)
        if (!settings.deleteAudioAfterTranscribe) return 0

        val repo = Repo(context)
        val now = System.currentTimeMillis()
        var collected = 0

        for (m in repo.meetings()) {
            // Only transcribed meetings. A recording still in flight, or one
            // whose pass failed, keeps its audio — that is the copy a retry
            // would read, and deleting it would make the failure permanent.
            if (m.state != MeetingState.DONE) continue
            if (!settings.graceExpired(m.startedAt, now)) continue
            if (Diarizer.segmentsOf(m.segmentDir).isEmpty()) continue

            runCatching { m.segmentDir.deleteRecursively() }
                .onSuccess { collected++ }
                .onFailure { Log.w(TAG, "could not collect audio for ${m.id}", it) }
        }
        if (collected > 0) Log.i(TAG, "collected audio for $collected meeting(s)")
        return collected
    }
}
