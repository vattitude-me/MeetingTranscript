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
 * dependency and no native library — only the two model downloads (~80 MB
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
    fun run(samples: FloatArray, onProgress: ((Int) -> Unit)? = null): List<Turn> {
        if (samples.isEmpty()) return emptyList()
        val segments = if (onProgress == null) {
            sd.process(samples)
        } else {
            sd.processWithCallback(
                samples,
                { done, total, _ ->
                    if (total > 0) onProgress((done * 100 / total).coerceIn(0, 100))
                    0
                },
                0L
            )
        }
        return segments
            .map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
            .sortedBy { it.startMs }
    }

    override fun close() = sd.release()

    companion object {
        private const val TAG = "DiarizeEngine"

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
            threads: Int = 2
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
                    // Only consulted when numClusters <= 0. 0.5 is the upstream
                    // default for CAM++ and errs toward merging similar voices
                    // rather than inventing extra speakers — the less confusing
                    // failure to read in a transcript.
                    threshold = 0.5f,
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
