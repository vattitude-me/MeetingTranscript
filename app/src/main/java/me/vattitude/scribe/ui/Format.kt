package me.vattitude.scribe.ui

/** Plain-text formatting with no Android dependencies, so it can be unit tested. */
object Format {

    /**
     * What deleting a meeting destroys, in the terms a person weighs it by:
     * "47 min of audio · 44 MB · 418 transcript lines".
     */
    fun deleteSummary(durationMs: Long, audioBytes: Long, lines: Int): String {
        val parts = mutableListOf<String>()
        val min = durationMs / 60_000
        val length = if (min >= 1) "$min min" else "${(durationMs / 1000).coerceAtLeast(0)} s"
        parts += if (audioBytes > 0) "$length of audio" else "$length meeting, audio already removed"
        if (audioBytes > 0) parts += mb(audioBytes)
        parts += when (lines) {
            0 -> "no transcript yet"
            1 -> "1 transcript line"
            else -> "$lines transcript lines"
        }
        return parts.joinToString(" · ")
    }

    /** "44 MB", or "0.4 MB" below one, so a short clip never reads as nothing. */
    fun mb(bytes: Long): String =
        if (bytes >= 1_000_000) "${bytes / 1_000_000} MB"
        else "%.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)

    /** "12:34" under an hour, "1:02:03" past it — the short clock for buttons. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
