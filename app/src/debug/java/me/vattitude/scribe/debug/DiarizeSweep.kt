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
 * `--es tell "1,2,3"` hands the clusterer counts that are deliberately not the
 * truth, which measures what a *wrong* answer from the user costs. Measured on
 * a 62s two-speaker sample: told 1 it returns 1, told 3 it returns 3 with a
 * spurious 5% third voice, told 4 it returns 3, told 8 it returns 5. An
 * under-count is obeyed exactly; an over-count is obeyed until the audio runs
 * out of distinguishable voices, and the extra clusters are small. Nothing
 * clamps this — MIN_SHARE is skipped whenever a count is supplied, so a wrong
 * count is followed with no floor to catch it. See DiarizeEngine.dropFragments.
 *
 * Or sweep a whole matrix in one run, which is what you want when each pass
 * costs minutes — `files` is comma-separated `name:truth` pairs:
 *
 *   ... --es files "m1_long.pcm:1,m2_long.pcm:2,m3_mid.pcm:3"
 *
 * Reads filesDir/<file> (16 kHz mono PCM16), default scribe_test.pcm.
 * `expect` is the true number of voices, used only to mark the log.
 *
 * Results are appended to filesDir/sweep_results.txt as well as logged.
 * logcat's ring buffer is small and any `logcat -c` anywhere wipes it, which
 * has already cost one 25-minute run; a file cannot be lost that way.
 */
class DiarizeSweep : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Either one file, or a whole matrix of "name:truth" pairs.
        val batch = intent.getStringExtra("files")
        val jobs: List<Pair<String, Int>> = if (batch != null) {
            batch.split(",").mapNotNull { spec ->
                val bits = spec.trim().split(":")
                if (bits.size == 2) bits[0] to (bits[1].toIntOrNull() ?: 0) else null
            }
        } else {
            listOf((intent.getStringExtra("file") ?: "scribe_test.pcm")
                to intent.getIntExtra("expect", 0))
        }

        CoroutineScope(Dispatchers.Default).launch {
            for ((name, expect) in jobs) {
                val file = File(filesDir, name)
                if (!file.exists()) {
                    record("SKIP $name: not at ${file.absolutePath}")
                    continue
                }
                runCatching { sweepOne(file, name, expect) }
                    .onFailure { record("FAIL $name: $it") }
            }
            record("=== all sweeps done ===")
            finish()
        }
    }

    /** Appends to the results file and logs, so a cleared buffer costs nothing. */
    private fun record(line: String) {
        Log.i(TAG, line)
        runCatching {
            File(filesDir, "sweep_results.txt").appendText(line + "\n")
        }
    }

    private fun sweepOne(file: File, name: String, expect: Int) {
        run {
            val samples = readPcm(file)
            record("=== $name: ${samples.size / 16000f}s, expecting $expect voices ===")

            // Wider and lower than the original sweep. The first pass only went
            // down to 0.5 and picked 0.8, which over-splits a single speaker
            // badly over several minutes.
            //
            // `--ei quick 1` skips the sweep and measures only the two things
            // the shipped app actually does: the guessing fallback at its real
            // default, and numClusters. A full sweep is 8 passes per file, and
            // at two minutes a pass a ten-file matrix does not finish.
            val quick = intent.getIntExtra("quick", 0) == 1
            val grid = if (quick) listOf(DiarizeEngine.DEFAULT_THRESHOLD)
                       else listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
            for (threshold in grid) {
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
                    record(
                        "threshold=$threshold speakers=${speakers.size} $speakers " +
                            "shares=$shares turns=${turns.size} " +
                            "took=${System.currentTimeMillis() - started}ms$mark"
                    )
                }.onFailure { record("threshold=$threshold FAILED: $it") }
            }

            // With the count known, clustering is told how many to find, which
            // is the mode the app should use whenever the user can tell us.
            // `tell` is what we hand the clusterer; `expect` stays the ground
            // truth. They are the same number in a normal run, and deliberately
            // different when measuring what a *wrong* answer costs — the case
            // that matters because the count comes from a tired human at the end
            // of a meeting, not from an oracle.
            val tells = intent.getStringExtra("tell")
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                ?: listOf(expect)
            for (tell in tells) {
                if (tell <= 0) continue
                runCatching {
                    val started = System.currentTimeMillis()
                    val turns = DiarizeEngine
                        .create(this@DiarizeSweep, expectedSpeakers = tell)
                        .use { it.run(samples) }
                    val got = turns.map { it.speaker }.distinct().size
                    val held = turns.groupBy { it.speaker }
                        .mapValues { (_, ts) -> ts.sumOf { t -> t.endMs - t.startMs } }
                    val total = held.values.sum().coerceAtLeast(1L)
                    val shares = held.toSortedMap().map { (k, v) -> "$k:${100 * v / total}%" }
                    val mark = when {
                        got == expect -> " <-- MATCHES TRUTH"
                        got == tell -> " <-- OBEYED (truth was $expect)"
                        else -> " <-- NEITHER (truth $expect, told $tell)"
                    }
                    record(
                        "numClusters=$tell speakers=$got shares=$shares " +
                            "turns=${turns.size} took=${System.currentTimeMillis() - started}ms$mark"
                    )
                }.onFailure { record("numClusters=$tell FAILED: $it") }
            }
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
