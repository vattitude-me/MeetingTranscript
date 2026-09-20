package me.vattitude.scribe.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import me.vattitude.scribe.capture.Audio

/**
 * Decodes while the meeting is still running.
 *
 * The contract with the caller is deliberately narrow: push audio in, get back
 * either a *partial* (the words so far, which may still change) or a *final*
 * (an endpoint was detected, the line is settled and will never be revised).
 * Only finals are written to the database; partials exist to be looked at.
 */
class StreamingEngine private constructor(
    private val recognizer: OnlineRecognizer,
    val modelId: String
) : AutoCloseable {

    private var stream: OnlineStream = recognizer.createStream("")
    private var committedMs: Long = 0
    private var fedSamples: Long = 0

    /** Text of the utterance in progress. Provisional — it can still change. */
    var partial: String = ""
        private set

    /**
     * Feeds one buffer of 16 kHz mono float samples.
     *
     * @return a finished line when the recognizer detected an endpoint, else null.
     */
    fun accept(samples: FloatArray, count: Int): AsrLine? {
        val chunk = if (count == samples.size) samples else samples.copyOf(count)
        stream.acceptWaveform(chunk, Audio.SAMPLE_RATE)
        fedSamples += count

        while (recognizer.isReady(stream)) recognizer.decode(stream)

        val text = recognizer.getResult(stream).text.trim()
        val endpoint = recognizer.isEndpoint(stream)

        if (!endpoint) {
            partial = text
            return null
        }

        // Endpoint: the utterance is settled. Reset so the next one starts clean.
        val endMs = fedSamples * 1000 / Audio.SAMPLE_RATE
        val line = if (text.isNotEmpty()) {
            AsrLine(committedMs, endMs, text, 0.0)
        } else null

        recognizer.reset(stream)
        partial = ""
        committedMs = endMs
        return line
    }

    /** Flushes whatever is still buffered when recording stops. */
    fun finish(): AsrLine? {
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        partial = ""
        if (text.isEmpty()) return null
        val endMs = fedSamples * 1000 / Audio.SAMPLE_RATE
        return AsrLine(committedMs, endMs, text, 0.0)
    }

    override fun close() {
        try { stream.release() } catch (_: Throwable) {}
        try { recognizer.release() } catch (_: Throwable) {}
    }

    companion object {
        fun create(context: Context, threads: Int = 2): StreamingEngine {
            val p = ModelManager.resolve(context, ModelManager.Model.LIVE)
                ?: throw IllegalStateException("Live speech model not downloaded yet")

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Audio.SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = p.encoder.absolutePath,
                        decoder = p.decoder.absolutePath,
                        joiner = p.joiner.absolutePath
                    ),
                    tokens = p.tokens.absolutePath,
                    modelType = "zipformer",
                    // Two threads, not four: this now runs for the whole meeting
                    // alongside the recorder, so leaving headroom matters more
                    // than shaving milliseconds off each chunk.
                    numThreads = threads,
                    provider = "cpu",
                    debug = false
                ),
                // Commit a line after 1.4s of trailing silence, or 2.4s even mid-word,
                // so a monologue still breaks into readable lines instead of one
                // ever-growing paragraph.
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f)
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )
            return StreamingEngine(OnlineRecognizer(config = config), ModelManager.Model.LIVE.id)
        }
    }
}
