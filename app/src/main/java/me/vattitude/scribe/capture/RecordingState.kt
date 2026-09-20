package me.vattitude.scribe.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Recording(
    val meetingId: Long,
    val startedAt: Long,
    val elapsedMs: Long,
    /** Peak amplitude 0..1 of the last buffer — drives the level meter and the
     *  "are the laptop speakers actually on?" check. */
    val level: Float,
    /**
     * How long the room has been below [SILENCE_LEVEL]. The expensive failure is
     * not a crash, it is coming back after an hour to a file full of nothing,
     * so this is surfaced rather than merely logged.
     */
    val silentMs: Long = 0
) {
    /** Long enough that a pause in conversation never trips it. */
    val isWorryinglyQuiet: Boolean get() = silentMs >= QUIET_WARNING_MS

    companion object {
        /** Peak amplitude below which a buffer counts as silence, not speech. */
        const val SILENCE_LEVEL = 0.012f
        const val QUIET_WARNING_MS = 45_000L
    }
}

object RecordingState {
    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state

    val isRecording: Boolean get() = _state.value != null

    internal fun set(r: Recording?) { _state.value = r }
}
