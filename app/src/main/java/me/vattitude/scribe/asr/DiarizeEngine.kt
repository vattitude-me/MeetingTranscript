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
    private val sd: OfflineSpeakerDiarization
) : AutoCloseable {

    /** A stretch of audio attributed to one clustered voice. */
    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    /**
     * @param expectedSpeakers when > 0, the clusterer is told exactly how many
     *   voices to find. That is far more reliable than a similarity threshold,
     *   so ask the user when we can and pass what they say.
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
        return segments
            .map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
            .sortedBy { it.startMs }
    }

    override fun close() = sd.release()

    companion object {
        private const val TAG = "DiarizeEngine"

        /**
         * Cosine distance below which two voices are treated as one person.
         *
         * Measured, not guessed. Swept 0.5–0.9 over a 76-second two-speaker
         * recording made the way this app records — a phone mic picking up
         * voices across a room. 0.5 found four speakers and 0.6 found three,
         * because ordinary variation within one voice exceeded the bar. 0.8 is
         * the lowest value that finds two, and it produces the same turns as
         * telling the clusterer the answer outright (numClusters = 2), which is
         * the strongest evidence available that it is not merely lucky.
         * See app/src/debug/DiarizeSweep.
         *
         * Erring high merges two similar voices into one; erring low invents
         * speakers who do not exist. Merging is the better failure: a reader
         * notices "this label is covering two people" far more easily than they
         * notice that Speaker 4 and Speaker 6 were always the same person. That
         * asymmetry is why this sits at the top of the correct range rather than
         * the bottom.
         *
         * One sample is one sample. Re-run the sweep before trusting this on
         * voices less alike than the pair it was tuned on.
         */
        const val DEFAULT_THRESHOLD = 0.8f

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
            Log.i(TAG, "diarization ready (speakers=$expectedSpeakers)")
            // null AssetManager → sherpa-onnx loads from absolute paths, which is
            // what downloaded models need. Verified against the AAR bytecode.
            return DiarizeEngine(OfflineSpeakerDiarization(null, config))
        }
    }
}
