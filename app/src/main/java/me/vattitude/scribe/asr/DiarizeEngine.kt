package me.vattitude.scribe.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * Speaker diarization: who spoke when.
 *
 * Two models in sequence. Pyannote segmentation finds speech regions and where
 * they change hands; CAM++ turns each region into an embedding, which is then
 * clustered so the same voice gets the same label across the whole meeting.
 *
 * Both ship in the sherpa-onnx AAR this app already bundles, so this adds no
 * dependency and no native library — only the two model downloads (37 MB
 * against the 615 MB already there).
 *
 * This produces *turns*, not names. Nothing here identifies a person: a cluster
 * is "the voice that recurs", and it cannot be matched to anyone outside this
 * one meeting. Naming a speaker is the user's act, not the model's.
 */
class DiarizeEngine private constructor(
    private val sd: OfflineSpeakerDiarization,
    /** True when no speaker count was supplied and the count is inferred. */
    private val guessing: Boolean
) : AutoCloseable {

    /** A stretch of audio attributed to one clustered voice. */
    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    /**
     * Returns the turns, with cluster ids packed to 0,1,2,… When the speaker
     * count was guessed rather than supplied, trivial clusters are folded into
     * their neighbours first — see [dropFragments].
     */
    fun run(samples: FloatArray): List<Turn> {
        if (samples.isEmpty()) return emptyList()
        // Deliberately process(), not processWithCallback().
        //
        // The callback overload takes Function3<Integer, Integer, Long, Integer>
        // \u2014 boxed. Kotlin compiles a lambda with those parameter types to a
        // primitive invoke(IIJ), the JNI lookup for the boxed method fails, and
        // the result is not an exception but an abort in native code that takes
        // the whole process down. No runCatching can defend against that, so the
        // callback is not worth a progress bar.
        val segments = sd.process(samples)
        val turns = segments
            .map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
            .sortedBy { it.startMs }
        return renumber(if (guessing) dropFragments(turns) else turns)
    }

    /**
     * Folds away clusters that hold a trivial share of the talking.
     *
     * Only applied when we are guessing the speaker count. When the user told
     * us how many voices to expect, every cluster is one they asked for, and
     * discarding one would be overruling them.
     *
     * A single voice recorded for nine minutes drifts — energy, pace, distance
     * from the mic — and the clusterer mints a new speaker for each drift.
     * Those spurious clusters are small; real participants are not. Reassigning
     * a fragment to whichever voice surrounds it is closer to the truth than
     * leaving a "Speaker 5" who is really Speaker 1 having a quiet moment.
     *
     * [MIN_SHARE] is 10%, and it is a partial measure, not a fix. Measured
     * across four recordings, the floor corrects a two-speaker dialogue that
     * clusters into three, and leaves a real third participant holding 12% of a
     * conversation intact. It does *not* rescue the case that prompted it: a
     * nine-minute monologue splits into two voices whose smaller half holds 13%
     * of the talking at every threshold from 0.6 to 0.9, so no floor low enough
     * to keep that 12% speaker can remove it.
     *
     * Raising the floor past 13% would fix the monologue and delete a real
     * person, which is the worse error by far — someone who spoke would vanish
     * from the transcript. So the floor stays where it does the good it can, and
     * the real answer is to ask the user: told the true count, the clusterer was
     * right on all four samples. See MeetingDetailActivity.promptSpeakerCount.
     */
    private fun dropFragments(turns: List<Turn>): List<Turn> {
        if (turns.isEmpty()) return turns
        val held = turns.groupBy { it.speaker }
            .mapValues { (_, ts) -> ts.sumOf { it.endMs - it.startMs } }
        val total = held.values.sum().toDouble()
        if (total <= 0) return turns
        val keep = held.filterValues { it / total >= MIN_SHARE }.keys
        // Never discard everything: if no cluster clears the bar, the recording
        // is too fragmented to judge and the raw result is the honest answer.
        if (keep.isEmpty()) return turns
        val dominant = held.maxByOrNull { it.value }?.key ?: return turns
        return turns.map { t ->
            if (t.speaker in keep) t
            else t.copy(speaker = nearestKept(turns, t, keep) ?: dominant)
        }
    }

    /** The kept voice speaking closest in time to [t] — usually the one either side of it. */
    private fun nearestKept(turns: List<Turn>, t: Turn, keep: Set<Int>): Int? =
        turns.filter { it.speaker in keep }
            .minByOrNull { other ->
                when {
                    other.endMs <= t.startMs -> t.startMs - other.endMs
                    other.startMs >= t.endMs -> other.startMs - t.endMs
                    else -> 0L
                }
            }?.speaker

    /**
     * Packs cluster ids down to 0,1,2,… in order of first appearance.
     *
     * The clusterer returns whatever internal ids survived clustering, and they
     * are not dense: a six-speaker result came back as [0,1,2,4,5,6]. The UI
     * renders "Speaker ${id + 1}", so that displayed a "Speaker 7" in a meeting
     * the header called six voices — which reads as a bug even when the
     * clustering is right.
     *
     * Ordering by first appearance also means Speaker 1 is whoever talks first,
     * which is what a reader assumes a transcript means.
     */
    private fun renumber(turns: List<Turn>): List<Turn> {
        val dense = HashMap<Int, Int>()
        return turns.map { t ->
            val id = dense.getOrPut(t.speaker) { dense.size }
            if (id == t.speaker) t else t.copy(speaker = id)
        }
    }

    override fun close() = sd.release()

    companion object {
        private const val TAG = "DiarizeEngine"

        /**
         * Cosine distance below which two voices are treated as one person.
         *
         * **This number cannot be made correct, and that is the point.** Swept
         * 0.2–0.9 across three recordings — a 9-minute monologue, an 88-second
         * two-speaker dialogue and a 7-minute one. No value in that range
         * produced the right speaker count for *any* of them. A single narrator
         * never collapsed below two voices; two speakers never resolved below
         * three. Meanwhile numClusters, given the true count, was right on all
         * three. See app/src/debug/DiarizeSweep.
         *
         * An earlier version of this comment claimed 0.8 was "measured, not
         * guessed" on the strength of one 76-second two-speaker clip. It was
         * measured, but a two-speaker sample only tests whether a threshold
         * wrongly *merges* voices, never whether it wrongly *splits* one. The
         * missing case — one person talking for nine minutes — came back as six
         * speakers in real use.
         *
         * So this is a fallback for when the user did not tell us the count,
         * paired with [MIN_SHARE] to fold away the worst fragments. 0.9 is kept
         * because higher is consistently less wrong here: over-splitting is the
         * failure that actually occurs. Even so, the pair still over-counts a
         * long monologue. The fallback is a best effort, not a solution — the
         * fix is to ask the user, and the UI now does.
         */
        const val DEFAULT_THRESHOLD = 0.9f

        /**
         * Minimum share of total speech a cluster must hold to count as a
         * person, when the count is being guessed. See [dropFragments] for why
         * it cannot go higher, and why no value of it is sufficient on its own.
         */
        const val MIN_SHARE = 0.10

        fun isReady(context: Context): Boolean =
            ModelManager.resolveSingle(context, ModelManager.Model.SEGMENTATION) != null &&
                ModelManager.resolveSingle(context, ModelManager.Model.EMBEDDING) != null

        /**
         * @param expectedSpeakers exact number of voices, or 0 to let the
         *   clusterer decide from [threshold].
         */
        fun create(
            context: Context,
            expectedSpeakers: Int = 0,
            threads: Int = 2,
            threshold: Float = DEFAULT_THRESHOLD
        ): DiarizeEngine {
            val seg = ModelManager.resolveSingle(context, ModelManager.Model.SEGMENTATION)
                ?: throw IllegalStateException("Speaker segmentation model not downloaded")
            val emb = ModelManager.resolveSingle(context, ModelManager.Model.EMBEDDING)
                ?: throw IllegalStateException("Speaker embedding model not downloaded")

            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = seg.absolutePath),
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = emb.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                ),
                clustering = FastClusteringConfig(
                    numClusters = expectedSpeakers,
                    // Only consulted when numClusters <= 0.
                    threshold = threshold,
                    computeConfidence = false
                ),
                // Speech shorter than this is dropped, and gaps shorter than this
                // do not split a turn. Keeps "mm-hm" from becoming a speaker.
                minDurationOn = 0.3f,
                minDurationOff = 0.5f
            )
            Log.i(
                TAG,
                if (expectedSpeakers > 0) "diarization ready (told: $expectedSpeakers speakers)"
                else "diarization ready (guessing, threshold=$threshold)"
            )
            // null AssetManager → sherpa-onnx loads from absolute paths, which is
            // what downloaded models need. Verified against the AAR bytecode.
            return DiarizeEngine(
                OfflineSpeakerDiarization(null, config),
                guessing = expectedSpeakers <= 0
            )
        }
    }
}
