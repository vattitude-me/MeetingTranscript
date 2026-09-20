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

    companion object {
        private const val KEY_DELETE_AUDIO = "delete_audio_after_transcribe"

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
