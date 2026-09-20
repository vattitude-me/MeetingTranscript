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
     * Streaming zipformer, 20M params, int8. Chosen over the far more accurate
     * Parakeet TDT because it is the only one of the two that can decode while the
     * meeting is still happening — and because 128 MB of weights, not 600 MB, is
     * what you want resident for 45 minutes of continuous inference.
     */
    const val MODEL_ID = "streaming-zipformer-en-20M-int8"
    private const val ARCHIVE = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"
    private const val URL_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    data class Paths(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    fun modelDir(context: Context): File = File(context.filesDir, "models/$MODEL_ID")

    /**
     * Locates the model files by shape rather than by exact name — the upstream
     * bundles have varied (encoder.onnx vs encoder.int8.onnx), and guessing wrong
     * would fail at inference time instead of here.
     */
    fun resolve(context: Context): Paths? {
        val dir = modelDir(context)
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
        Log.i(TAG, "model: ${encoder.name}, ${decoder.name}, ${joiner.name}")
        return Paths(encoder, decoder, joiner, tokens)
    }

    fun isReady(context: Context): Boolean = resolve(context) != null

    /**
     * Removes model bundles we no longer use. Changing MODEL_ID would otherwise
     * strand the previous download on disk — 600 MB, in the case of the Parakeet
     * bundle this app shipped with before live transcription.
     */
    fun pruneOldModels(context: Context) {
        val root = File(context.filesDir, "models")
        if (!root.isDirectory) return
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != MODEL_ID) {
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
    fun download(context: Context, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit) {
        val target = modelDir(context)
        val staging = File(context.filesDir, "models/.staging-$MODEL_ID")
        staging.deleteRecursively()
        staging.mkdirs()

        val conn = (URL(URL_BASE + ARCHIVE).openConnection() as HttpURLConnection).apply {
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
        Log.i(TAG, "model ready at $target (${target.listFiles()?.size ?: 0} files)")
    }

    fun delete(context: Context) {
        modelDir(context).deleteRecursively()
    }

    fun sizeOnDisk(context: Context): Long =
        modelDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
