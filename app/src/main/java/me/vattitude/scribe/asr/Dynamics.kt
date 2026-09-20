package me.vattitude.scribe.asr

import me.vattitude.scribe.store.Line

/**
 * Objective conversation dynamics, computed from diarization timings alone.
 *
 * Deliberately *not* emotion or sentiment. Speech-emotion models are trained on
 * acted corpora and degrade badly on real meeting audio — doubly so for remote
 * voices, which have already been through a conferencing codec and a laptop
 * speaker. A confident "she sounded frustrated" that is wrong is worse than
 * saying nothing, because someone might act on it.
 *
 * Everything here is countable and checkable against the transcript: who spoke,
 * for how long, who started talking while someone else still was, and who asked
 * questions. Those are the numbers meeting-analytics tools are actually praised
 * for, and every one of them can be verified by reading the lines.
 */
object Dynamics {

    data class Share(
        val speaker: Int,
        val talkMs: Long,
        val fraction: Double,
        val turns: Int,
        val questions: Int,
        /** Times this speaker began while another was still talking. */
        val interruptions: Int,
        val longestTurnMs: Long
    )

    data class Summary(
        val shares: List<Share>,
        val speakers: Int,
        val totalTalkMs: Long,
        /** Fraction held by the most dominant speaker, 0.0 when unknown. */
        val dominance: Double,
        /** Longest uninterrupted stretch by one voice. */
        val longestMonologueMs: Long
    )

    /**
     * A turn counts as an interruption when it starts before the previous
     * speaker's turn has ended. The overlap must clear [OVERLAP_MS] — diarization
     * boundaries are approximate, and a 50 ms brush between turns is a boundary
     * artefact, not somebody talking over somebody.
     */
    private const val OVERLAP_MS = 300L

    fun of(turns: List<DiarizeEngine.Turn>, lines: List<Line>): Summary {
        if (turns.isEmpty()) return Summary(emptyList(), 0, 0, 0.0, 0)

        val ordered = turns.sortedBy { it.startMs }
        val talk = mutableMapOf<Int, Long>()
        val counts = mutableMapOf<Int, Int>()
        val longest = mutableMapOf<Int, Long>()
        val interrupts = mutableMapOf<Int, Int>()

        var previousEnd = Long.MIN_VALUE
        var previousSpeaker = -1
        for (t in ordered) {
            val dur = (t.endMs - t.startMs).coerceAtLeast(0)
            talk[t.speaker] = (talk[t.speaker] ?: 0) + dur
            counts[t.speaker] = (counts[t.speaker] ?: 0) + 1
            longest[t.speaker] = maxOf(longest[t.speaker] ?: 0, dur)
            if (previousSpeaker != -1 && t.speaker != previousSpeaker &&
                previousEnd - t.startMs >= OVERLAP_MS
            ) {
                interrupts[t.speaker] = (interrupts[t.speaker] ?: 0) + 1
            }
            if (t.endMs > previousEnd) { previousEnd = t.endMs; previousSpeaker = t.speaker }
        }

        val questions = lines
            .filter { it.speaker >= 0 && it.text.trimEnd().endsWith("?") }
            .groupingBy { it.speaker }
            .eachCount()

        val total = talk.values.sum().coerceAtLeast(1)
        val shares = talk.keys.sorted().map { sp ->
            Share(
                speaker = sp,
                talkMs = talk[sp] ?: 0,
                fraction = (talk[sp] ?: 0).toDouble() / total,
                turns = counts[sp] ?: 0,
                questions = questions[sp] ?: 0,
                interruptions = interrupts[sp] ?: 0,
                longestTurnMs = longest[sp] ?: 0
            )
        }.sortedByDescending { it.talkMs }

        return Summary(
            shares = shares,
            speakers = shares.size,
            totalTalkMs = talk.values.sum(),
            dominance = shares.firstOrNull()?.fraction ?: 0.0,
            longestMonologueMs = longest.values.maxOrNull() ?: 0
        )
    }

    /**
     * Rebuilds turns from labelled lines, for meetings whose audio is gone.
     *
     * Diarization's own turns are not persisted \u2014 only the per-line speaker is.
     * Consecutive lines by one voice are the same turn, which is the definition
     * that matters for talk time and interruptions anyway. The result is
     * slightly coarser than the acoustic boundaries, and it survives the audio.
     */
    fun turnsFrom(lines: List<Line>): List<DiarizeEngine.Turn> {
        val out = mutableListOf<DiarizeEngine.Turn>()
        for (l in lines) {
            if (l.speaker < 0) continue
            val last = out.lastOrNull()
            if (last != null && last.speaker == l.speaker) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, l.tEndMs))
            } else {
                out += DiarizeEngine.Turn(l.tStartMs, l.tEndMs, l.speaker)
            }
        }
        return out
    }

    /** The summary for a stored meeting, derived entirely from its lines. */
    fun of(lines: List<Line>): Summary = of(turnsFrom(lines), lines)

    /** "62% · 14 turns · 3 questions" — the row under a speaker's name. */
    fun describe(share: Share): String = buildString {
        append("${(share.fraction * 100).toInt()}%")
        append(" · ${share.turns} ${if (share.turns == 1) "turn" else "turns"}")
        if (share.questions > 0) {
            append(" · ${share.questions} ${if (share.questions == 1) "question" else "questions"}")
        }
        if (share.interruptions > 0) append(" · interrupted ${share.interruptions}×")
    }
}
