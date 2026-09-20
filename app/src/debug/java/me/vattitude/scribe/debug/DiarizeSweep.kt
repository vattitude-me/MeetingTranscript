package me.vattitude.scribe.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.vattitude.scribe.asr.DiarizeEngine
import java.io.File

/**
 * Debug-only: runs diarization over a fixed recording at several clustering
 * thresholds and logs what each one produces.
 *
 * Choosing a threshold by intuition is how you end up with five speakers in a
 * two-person meeting. This exists so the number in DiarizeEngine is one we
 * measured against real audio rather than one that sounded reasonable.
 *
 *   adb shell am start -n me.vattitude.scribe.debug/me.vattitude.scribe.debug.DiarizeSweep
 *
 * Reads /sdcard/Download/scribe_test.pcm (16 kHz mono PCM16).
 */
class DiarizeSweep : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "scribe_test.pcm")
        if (!file.exists()) {
            Log.e(TAG, "no test audio at ${file.absolutePath}")
            finish()
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            val samples = readPcm(file)
            Log.i(TAG, "=== ${samples.size} samples, ${samples.size / 16000f}s ===")

            for (threshold in listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f)) {
                runCatching {
                    val started = System.currentTimeMillis()
                    val turns = DiarizeEngine
                        .create(this@DiarizeSweep, expectedSpeakers = 0, threshold = threshold)
                        .use { it.run(samples) }
                    val speakers = turns.map { it.speaker }.distinct().sorted()
                    Log.i(
                        TAG,
                        "threshold=$threshold speakers=${speakers.size} $speakers " +
                            "turns=${turns.size} took=${System.currentTimeMillis() - started}ms"
                    )
                    for (t in turns) Log.i(TAG, "   ${t.startMs}-${t.endMs} spk=${t.speaker}")
                }.onFailure { Log.e(TAG, "threshold=$threshold failed", it) }
            }

            // With the count known, clustering is told how many to find, which
            // is the mode the app should use whenever the user can tell us.
            runCatching {
                val started = System.currentTimeMillis()
                val turns = DiarizeEngine
                    .create(this@DiarizeSweep, expectedSpeakers = 2)
                    .use { it.run(samples) }
                Log.i(
                    TAG,
                    "numClusters=2 speakers=${turns.map { it.speaker }.distinct().size} " +
                        "turns=${turns.size} took=${System.currentTimeMillis() - started}ms"
                )
                for (t in turns) Log.i(TAG, "   ${t.startMs}-${t.endMs} spk=${t.speaker}")
            }.onFailure { Log.e(TAG, "numClusters=2 failed", it) }

            Log.i(TAG, "=== sweep done ===")
            finish()
        }
    }

    private fun readPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        val n = bytes.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }

    private companion object {
        const val TAG = "DiarizeSweep"
    }
}
