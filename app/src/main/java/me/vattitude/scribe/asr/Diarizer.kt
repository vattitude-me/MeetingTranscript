package me.vattitude.scribe.asr

import android.content.Context
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Repo
import java.io.DataInputStream
import java.io.File

/**
 * Runs speaker separation over a finished meeting and writes the labels back.
 *
 * Split out of TranscribeWorker because this pass is worth repeating on its own.
 * Transcription is the expensive half and its output never changes; the speaker
 * count is a guess the user can correct afterwards, and correcting it should not
 * cost another full transcription. See [ReDiarizeWorker].
 */
object Diarizer {

    /**
     * Labels each transcript line with the voice that spoke most of it.
     *
     * Diarization needs the whole meeting at once — clustering is what makes
     * speaker 1 the same person at minute 2 and minute 40, and that is only
     * decidable globally. So this concatenates the segments rather than working
     * per segment as transcription does.
     *
     * @param onStart called once the audio is loaded and the models are about to
     *   run, for whatever progress the caller shows.
     * @return true if speakers were written.
     */
    fun run(
        context: Context,
        repo: Repo,
        meetingId: Long,
        segments: List<File>,
        lines: List<Line>,
        onStart: () -> Unit = {}
    ): Boolean {
        val total = segments.sumOf { it.length() / 2 }.toInt()
        if (total <= 0) return false
        val all = FloatArray(total)
        var at = 0
        for (f in segments) {
            val chunk = readPcm(f)
            val room = minOf(chunk.size, total - at)
            if (room <= 0) break
            System.arraycopy(chunk, 0, all, at, room)
            at += room
        }

        // Use the count the user gave when we have one. Threshold clustering is
        // the fallback, not the preference: across single-, two- and three-speaker
        // recordings no threshold in 0.2..0.9 ever produced the right number,
        // whereas numClusters was right every time. See DiarizeEngine.
        val expected = repo.meeting(meetingId)?.expectedSpeakers ?: 0
        DiarizeEngine.create(context, expectedSpeakers = expected).use { d ->
            onStart()
            val turns = d.run(all)
            if (turns.isEmpty()) return false
            repo.setLineSpeakers(meetingId, assign(lines, turns))
            return true
        }
    }

    /** The .pcm segments of a meeting, in recording order. */
    fun segmentsOf(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedBy { it.name } ?: emptyList()

    /**
     * A line gets the speaker who holds the most of its duration. Turn and line
     * boundaries never align exactly — one is drawn by acoustics, the other by
     * punctuation — so overlap is the only sound basis for the decision.
     */
    private fun assign(lines: List<Line>, turns: List<DiarizeEngine.Turn>): Map<Int, Int> =
        buildMap {
            for (line in lines) {
                var best = -1
                var bestOverlap = 0L
                for (t in turns) {
                    if (t.endMs <= line.tStartMs) continue
                    if (t.startMs >= line.tEndMs) break
                    val overlap = minOf(t.endMs, line.tEndMs) - maxOf(t.startMs, line.tStartMs)
                    if (overlap > bestOverlap) { bestOverlap = overlap; best = t.speaker }
                }
                if (best >= 0) put(line.idx, best)
            }
        }

    /** Reads a raw little-endian PCM16 segment into the -1..1 floats the model wants. */
    private fun readPcm(file: File): FloatArray {
        val bytes = DataInputStream(file.inputStream().buffered()).use { it.readBytes() }
        val n = bytes.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }
}
