package me.vattitude.scribe.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import me.vattitude.scribe.capture.Audio
import kotlin.math.max

/**
 * Parakeet TDT 0.6B v3 (int8) through sherpa-onnx.
 *
 * Loading the recogniser costs seconds, so one instance is reused for a whole
 * meeting rather than being rebuilt per segment.
 */
class ParakeetEngine private constructor(
    private val recognizer: OfflineRecognizer
) : AsrEngine {

    override val modelId: String = ModelManager.MODEL_ID

    override fun transcribe(samples: FloatArray, offsetMs: Long): List<AsrLine> {
        if (samples.isEmpty()) return emptyList()

        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, Audio.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            splitIntoLines(result.text, result.tokens, result.timestamps, offsetMs, samples.size)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /** Hard cap on how long a single transcript line may run. */
        private const val MAX_LINE_MS = 15_000L
        /** A pause longer than this ends the line even mid-sentence. */
        private const val LINE_BREAK_GAP_MS = 700L

        fun create(context: Context, threads: Int = 4): ParakeetEngine {
            val p = ModelManager.resolve(context)
                ?: throw IllegalStateException("Speech model not downloaded yet")

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Audio.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = p.encoder.absolutePath,
                        decoder = p.decoder.absolutePath,
                        joiner = p.joiner.absolutePath
                    ),
                    tokens = p.tokens.absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = threads,
                    provider = "cpu",
                    debug = false
                ),
                decodingMethod = "greedy_search"
            )
            return ParakeetEngine(OfflineRecognizer(config = config))
        }

        /**
         * Turns one decoded utterance into readable, individually shareable lines.
         *
         * Breaks on sentence punctuation (Parakeet emits its own), on a long
         * pause, or on [MAX_LINE_MS] — whichever comes first — so that a line is
         * always something you could sensibly quote on its own.
         */
        internal fun splitIntoLines(
            text: String,
            tokens: Array<String>?,
            timestamps: FloatArray?,
            offsetMs: Long,
            sampleCount: Int
        ): List<AsrLine> {
            val spanMs = sampleCount * 1000L / Audio.SAMPLE_RATE
            val clean = text.trim()
            if (clean.isEmpty()) return emptyList()

            // No per-token timing available: emit the whole span as one line.
            if (tokens == null || timestamps == null || tokens.isEmpty() ||
                timestamps.size != tokens.size
            ) {
                return listOf(AsrLine(offsetMs, offsetMs + spanMs, clean, 0.0))
            }

            val out = mutableListOf<AsrLine>()
            val sb = StringBuilder()
            var lineStart = timestamps[0]
            var prevEnd = timestamps[0]

            fun flush(endSec: Float) {
                val body = sb.toString().replace('▁', ' ').trim()
                sb.setLength(0)
                if (body.isEmpty()) return
                val startMs = offsetMs + (lineStart * 1000).toLong()
                val endMs = offsetMs + max((endSec * 1000).toLong(), (lineStart * 1000).toLong() + 1)
                out.add(AsrLine(startMs, endMs, body, 0.0))
            }

            for (i in tokens.indices) {
                val t = timestamps[i]
                val gapMs = ((t - prevEnd) * 1000).toLong()

                if (sb.isNotEmpty() && gapMs > LINE_BREAK_GAP_MS) {
                    flush(prevEnd)
                    lineStart = t
                }
                sb.append(tokens[i])
                val runMs = ((t - lineStart) * 1000).toLong()
                val endsSentence = tokens[i].trimEnd().endsWith('.') ||
                    tokens[i].trimEnd().endsWith('?') || tokens[i].trimEnd().endsWith('!')

                if (endsSentence || runMs > MAX_LINE_MS) {
                    flush(t)
                    if (i + 1 < tokens.size) lineStart = timestamps[i + 1]
                }
                prevEnd = t
            }
            flush(prevEnd)

            return out.ifEmpty { listOf(AsrLine(offsetMs, offsetMs + spanMs, clean, 0.0)) }
        }
    }
}
