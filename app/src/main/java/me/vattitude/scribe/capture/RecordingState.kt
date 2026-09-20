package me.vattitude.scribe.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Recording(
    val meetingId: Long,
    val startedAt: Long,
    val elapsedMs: Long,
    /** Peak amplitude 0..1 of the last buffer — drives the level meter and the
     *  "are the laptop speakers actually on?" check. */
    val level: Float
)

object RecordingState {
    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state

    val isRecording: Boolean get() = _state.value != null

    internal fun set(r: Recording?) { _state.value = r }
}
