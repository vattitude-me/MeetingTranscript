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
 * Debug-only: runs diarization over a recording at several clustering
 * thresholds and logs what each one produces.
 *
 * Choosing a threshold by intuition is how you end up with five speakers in a
 * two-person meeting. This exists so the number in DiarizeEngine is one we
 * measured against real audio rather than one that sounded reasonable.
 *
 * A threshold trades two failures against each other: too low and one voice
 * fragments into many, too high and distinct voices merge into one. A sweep
 * over a two-speaker sample only measures the merging side. **Always sweep a
 * single-speaker recording as well**, or the number you pick will look correct
 * right up until someone records a lecture.
 *
 * What this measured, so it is not re-measured from scratch: across one-, two-
 * and three-speaker recordings, no threshold in 0.2..0.9 gave the right count
 * for all of them, and none gave the right count for a nine-minute monologue at
 * any setting — its spurious second voice holds a flat 13% of the talking from
 * 0.6 to 0.9. numClusters, given the true count, was right on every sample.
 * That is why the app asks the user rather than tuning this harder.
 *
 *   adb shell am start -n me.vattitude.scribe.debug/me.vattitude.scribe.debug.DiarizeSweep \
 *     --es file single_speaker.pcm --ei expect 1
 *
 * Reads filesDir/<file> (16 kHz mono PCM16), default scribe_test.pcm.
 * `expect` is the true number of voices, used only to mark the log.
 */
class DiarizeSweep : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("file") ?: "scribe_test.pcm"
        val expect = intent.getIntExtra("expect", 0)
        val file = File(filesDir, name)
        if (!file.exists()) {
            Log.e(TAG, "no test audio at ${file.absolutePath}")
            finish()
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            val samples = readPcm(file)
            Log.i(TAG, "=== $name: ${samples.size / 16000f}s, expecting $expect voices ===")

            // Wider and lower than the original sweep. The first pass only went
            // down to 0.5 and picked 0.8, which over-splits a single speaker
            // badly over several minutes.
            for (threshold in listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)) {
                runCatching {
                    val started = System.currentTimeMillis()
                    val turns = DiarizeEngine
                        .create(this@DiarizeSweep, expectedSpeakers = 0, threshold = threshold)
                        .use { it.run(samples) }
                    val speakers = turns.map { it.speaker }.distinct().sorted()
                    val mark = if (expect > 0 && speakers.size == expect) " <-- CORRECT" else ""
                    // run() already applied the minimum-share floor, so these
                    // shares are what the app ships, not the raw clustering.
                    // Print them: when a count is wrong, the share of the
                    // spurious cluster is what says whether any floor could
                    // have caught it.
                    val held = turns.groupBy { it.speaker }
                        .mapValues { (_, ts) -> ts.sumOf { t -> t.endMs - t.startMs } }
                    val total = held.values.sum().coerceAtLeast(1L)
                    val shares = held.toSortedMap().map { (k, v) -> "$k:${100 * v / total}%" }
                    Log.i(
                        TAG,
                        "threshold=$threshold speakers=${speakers.size} $speakers " +
                            "shares=$shares turns=${turns.size} " +
                            "took=${System.currentTimeMillis() - started}ms$mark"
                    )
                }.onFailure { Log.e(TAG, "threshold=$threshold failed", it) }
            }

            // With the count known, clustering is told how many to find, which
            // is the mode the app should use whenever the user can tell us.
            if (expect > 0) {
                runCatching {
                    val started = System.currentTimeMillis()
                    val turns = DiarizeEngine
                        .create(this@DiarizeSweep, expectedSpeakers = expect)
                        .use { it.run(samples) }
                    Log.i(
                        TAG,
                        "numClusters=$expect speakers=${turns.map { it.speaker }.distinct().size} " +
                            "turns=${turns.size} took=${System.currentTimeMillis() - started}ms"
                    )
                }.onFailure { Log.e(TAG, "numClusters=$expect failed", it) }
            }

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
