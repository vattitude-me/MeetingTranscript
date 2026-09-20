package me.vattitude.scribe.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the recording screen shows while a meeting is in progress. */
data class LiveView(
    val lines: List<String> = emptyList(),
    /** The utterance still being decoded. Provisional — it can still change. */
    val partial: String = ""
)

object LiveTranscript {
    private val _state = MutableStateFlow(LiveView())
    val state: StateFlow<LiveView> = _state

    // Only the tail is kept in memory; the database holds the real transcript.
    private const val MAX_LINES = 200

    fun append(text: String) {
        if (text.isBlank()) return
        _state.value = _state.value.let {
            it.copy(lines = (it.lines + text).takeLast(MAX_LINES), partial = "")
        }
    }

    fun setPartial(text: String) {
        if (_state.value.partial != text) _state.value = _state.value.copy(partial = text)
    }

    fun reset() { _state.value = LiveView() }
}
