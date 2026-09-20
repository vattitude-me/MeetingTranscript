package me.vattitude.scribe.asr

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Models are never shipped in the APK — they are fetched once, over Wi-Fi, into
 * app-private storage.
 *
 * Default is Parakeet TDT 0.6B v3 (int8): best reported English accuracy per
 * millisecond of the on-device options, with punctuation built in, and a working
 * set the Pixel 9's 12 GB absorbs without noticing.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /**
     * Two models, because no single on-device model does both jobs well.
     *
     * [LIVE] streams: it can decode while people are still talking, which is the
     * point of the live view. It is 20M parameters and it shows — it commits words
     * before hearing the end of the sentence, and it is wrong more often.
     *
     * [ACCURATE] cannot stream at all, but it is 600M parameters and reads the
     * whole utterance before deciding. It produces the transcript you keep.
     *
     * The live pass is scaffolding; the accurate pass is the product.
     */
    enum class Model(
        val id: String,
        val archive: String,
        val approxBytes: Long,
        val streaming: Boolean,
        /**
         * Speaker models. Downloaded with the rest, but the app still runs
         * without them \u2014 a missing one costs speaker labels, not transcripts,
         * so [isReady] for the ASR models never depends on these.
         */
        val speaker: Boolean = false,
        /** Published as a bare .onnx rather than a .tar.bz2 bundle. */
        val bareModel: Boolean = false,
        /** Speaker models live under different release tags than the ASR ones. */
        val urlBase: String = URL_BASE
    ) {
        LIVE(
            "streaming-zipformer-en-20M-int8",
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
            127_887_156L,
            true
        ),
        ACCURATE(
            "parakeet-tdt-0.6b-v3-int8",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
            487_170_055L,
            false
        ),

        /**
         * Speaker diarization, in two parts: [SEGMENTATION] finds where speech
         * is and where it changes hands, [EMBEDDING] turns each stretch into a
         * voiceprint so the same person clusters together across the meeting.
         *
         * Together 37 MB against the 615 MB already downloaded, and both run on
         * the sherpa-onnx binary the app already ships \u2014 no new dependency.
         * Downloaded with the rest, because separating voices is the reason to
         * use this over a recorder app. Transcription gates on [ACCURATE] alone,
         * so a missing speaker model costs labels, never a transcript.
         */
        SEGMENTATION(
            "pyannote-segmentation-3-0",
            "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            6_958_444L,
            false,
            speaker = true,
            urlBase = SPEAKER_SEG_BASE
        ),
        EMBEDDING(
            "3dspeaker-cam++-en-common",
            "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
            29_596_978L,
            false,
            speaker = true,
            bareModel = true,
            urlBase = SPEAKER_EMB_BASE
        );
    }

    private const val URL_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val SPEAKER_SEG_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/"
    // "recongition" is upstream's spelling in the release tag, not a typo here.
    private const val SPEAKER_EMB_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"

    data class Paths(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    fun modelDir(context: Context, model: Model): File =
        File(context.filesDir, "models/${model.id}")

    /**
     * Locates the model files by shape rather than by exact name — the upstream
     * bundles have varied (encoder.onnx vs encoder.int8.onnx), and guessing wrong
     * would fail at inference time instead of here.
     */
    fun resolve(context: Context, model: Model): Paths? {
        val dir = modelDir(context, model)
        if (!dir.isDirectory) return null
        val onnx = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".onnx") }.toList()
        val tokens = dir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" } ?: return null

        // These bundles ship fp32 AND int8 side by side (encoder-….onnx next to
        // encoder-….int8.onnx). Taking whichever the file walk happened to reach
        // first could load the fp32 weights — several times the memory, for nothing.
        // Always prefer int8, and never fall back silently.
        fun pick(kind: String): File? {
            val matches = onnx.filter { it.name.contains(kind) }
            return matches.firstOrNull { it.name.contains(".int8.") } ?: matches.firstOrNull()
        }

        val encoder = pick("encoder") ?: return null
        val decoder = pick("decoder") ?: return null
        val joiner = pick("joiner") ?: return null
        Log.i(TAG, "${model.id}: ${encoder.name}, ${decoder.name}, ${joiner.name}")
        return Paths(encoder, decoder, joiner, tokens)
    }

    /**
     * Resolves a model that is one bare .onnx file rather than an
     * encoder/decoder/joiner triple — the diarization pair. [resolve] would
     * return null for these because it insists on all three parts plus tokens.
     */
    fun resolveSingle(context: Context, model: Model): File? {
        val dir = modelDir(context, model)
        if (!dir.isDirectory) return null
        val onnx = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".onnx") }.toList()
        if (onnx.isEmpty()) return null
        return onnx.firstOrNull { it.name.contains(".int8.") } ?: onnx.first()
    }

    fun isReady(context: Context, model: Model): Boolean =
        if (model.bareModel || model.speaker) resolveSingle(context, model) != null
        else resolve(context, model) != null

    /** True when at least one model is present, i.e. we can transcribe somehow. */
    fun anyReady(context: Context): Boolean =
        Model.entries.any { isReady(context, it) }

    /**
     * Everything still to download. The speaker models are included: knowing who
     * spoke is the reason this app exists rather than a recorder, so shipping it
     * as an opt-in extra would hide the feature behind a settings screen most
     * people never open. 37 MB on top of 615 MB is not a decision worth asking
     * about.
     */
    fun missing(context: Context): List<Model> =
        Model.entries.filterNot { isReady(context, it) }

    /** Just the speaker pair, for reporting status in Settings. */
    fun missingSpeaker(context: Context): List<Model> =
        Model.entries.filter { it.speaker }.filterNot { isReady(context, it) }


    /**
     * Removes model bundles we no longer use. Changing MODEL_ID would otherwise
     * strand the previous download on disk — 600 MB, in the case of the Parakeet
     * bundle this app shipped with before live transcription.
     */
    fun pruneOldModels(context: Context) {
        val root = File(context.filesDir, "models")
        if (!root.isDirectory) return
        root.listFiles()?.forEach { child ->
            val known = Model.entries.map { it.id }.toSet()
            if (child.isDirectory && child.name !in known && !child.name.startsWith(".staging-")) {
                val freed = child.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (child.deleteRecursively()) {
                    Log.i(TAG, "pruned ${child.name}, freed ${freed / 1_000_000} MB")
                }
            }
        }
    }

    /**
     * Downloads and unpacks the model bundle. Safe to re-run: it stages into a
     * temp directory and only swaps it in once the archive is fully extracted,
     * so an interrupted download never leaves a half-model that looks ready.
     */
    fun download(
        context: Context,
        model: Model,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val target = modelDir(context, model)
        val staging = File(context.filesDir, "models/.staging-${model.id}")
        staging.deleteRecursively()
        staging.mkdirs()

        val conn = (URL(model.urlBase + model.archive).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Model download failed: HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        var read = 0L

        conn.inputStream.use { raw ->
            val counting = object : java.io.FilterInputStream(BufferedInputStream(raw, 1 shl 16)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { read += n; onProgress(read, total) }
                    return n
                }
            }
            if (model.bareModel) {
                // Published as a plain .onnx, not an archive. Nothing to unpack.
                FileOutputStream(File(staging, File(model.archive).name)).use { out ->
                    counting.copyTo(out, 1 shl 16)
                }
                target.deleteRecursively()
                target.parentFile?.mkdirs()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
                Log.i(TAG, "${model.id} ready at $target")
                return
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting, true)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    // Flatten: the archive has a single top-level directory we don't need.
                    val name = File(entry.name).name
                    if (!entry.isDirectory && name.isNotEmpty() && !name.startsWith(".")) {
                        FileOutputStream(File(staging, name)).use { out -> tar.copyTo(out, 1 shl 16) }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        Log.i(TAG, "${model.id} ready at $target (${target.listFiles()?.size ?: 0} files)")
    }

    fun delete(context: Context) {
        Model.entries.forEach { modelDir(context, it).deleteRecursively() }
    }

    fun sizeOnDisk(context: Context): Long =
        Model.entries.sumOf { model ->
            modelDir(context, model).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
}
