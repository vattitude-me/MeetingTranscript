package me.vattitude.scribe.asr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vattitude.scribe.R
import me.vattitude.scribe.ScribeApp
import me.vattitude.scribe.capture.Audio
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo
import me.vattitude.scribe.store.Settings
import androidx.core.app.TaskStackBuilder
import me.vattitude.scribe.ui.MainActivity
import me.vattitude.scribe.ui.MeetingDetailActivity
import java.io.DataInputStream
import java.io.File

/**
 * Transcribes a finished meeting, one segment at a time, and can pick up exactly
 * where it left off.
 *
 * Progress is committed to the database after every segment, so a crash, an OOM
 * or a reboot costs one segment of work rather than an hour of it.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val meetingId = inputData.getLong(KEY_MEETING_ID, -1L)
        if (meetingId < 0) return@withContext Result.failure()

        val repo = Repo(applicationContext)
        val meeting = repo.meeting(meetingId) ?: return@withContext Result.failure()

        if (!ModelManager.isReady(applicationContext, ModelManager.Model.ACCURATE)) {
            // Not an error — the user simply hasn't downloaded the model yet. Any
            // live lines already on the meeting stay exactly as they are.
            repo.setState(meetingId, MeetingState.RECORDED, "Speech model not downloaded")
            return@withContext Result.success()
        }

        val segments = meeting.segmentDir.listFiles { f -> f.name.endsWith(".pcm") }
            ?.sortedBy { it.name } ?: emptyList()
        if (segments.isEmpty()) {
            repo.setState(meetingId, MeetingState.FAILED, "No audio was captured")
            return@withContext Result.failure()
        }

        repo.setState(meetingId, MeetingState.TRANSCRIBING)
        notify(meetingId, 0, segments.size)

        var engine: AsrEngine? = null
        // A line held back because it might continue into the next segment.
        var pendingTail: me.vattitude.scribe.asr.AsrLine? = null
        try {
            // Loading the model crosses into ONNX Runtime. A native OOM there aborts
            // the process outright and no catch below will ever see it, so leave a
            // breadcrumb on disk first: if we never clear it, the next launch knows.
            repo.setState(meetingId, MeetingState.TRANSCRIBING, BREADCRUMB_LOADING)
            engine = ParakeetEngine.create(applicationContext)
            repo.setState(meetingId, MeetingState.TRANSCRIBING)

            // The live pass may have left a rough transcript behind. Drop it now
            // that the model it will be replaced by has actually loaded — clearing
            // any earlier would leave the meeting blank if loading then failed.
            // Only on a fresh run: a resumed run is extending its own output.
            if (meeting.segmentsDone == 0) repo.clearLines(meetingId)
            var idx = repo.nextLineIdx(meetingId)

            // Resume: skip whatever a previous run already committed.
            for (i in meeting.segmentsDone until segments.size) {
                if (isStopped) {
                    // A held-back tail lives only in memory. Commit it before
                    // yielding, or the resumed run starts after that segment and
                    // the line is lost outright.
                    pendingTail?.let { t ->
                        repo.appendLines(
                            meetingId,
                            listOf(Line(0, meetingId, idx++, t.tStartMs, t.tEndMs, t.text, t.confidence))
                        )
                        pendingTail = null
                    }
                    repo.setState(meetingId, MeetingState.RECORDED, "Paused")
                    return@withContext Result.retry()
                }
                val file = segments[i]
                val samples = readPcm(file)
                val offsetMs = i * Audio.SEGMENT_MS

                val lines = engine.transcribe(samples, offsetMs)
                if (lines.isNotEmpty()) {
                    // Segments are cut on a 30s clock, not on sentences, so a
                    // sentence spanning a boundary arrives as a tail and a head.
                    // Join them when the first did not end in punctuation, rather
                    // than leaving every boundary as a visible break.
                    val merged = lines.toMutableList()
                    val tail = pendingTail
                    if (tail != null && merged.isNotEmpty()) {
                        val head = merged[0]
                        // The model sometimes opens a segment with the punctuation
                        // that closed the previous sentence (". The lock screen…"),
                        // so a plain space-join strands a " ." mid-transcript.
                        val headText = head.text.trimStart()
                        val joiner = if (headText.firstOrNull() in SENTENCE_END) "" else " "
                        merged[0] = head.copy(
                            tStartMs = tail.tStartMs,
                            text = (tail.text.trimEnd() + joiner + headText).trim()
                        )
                        pendingTail = null
                    }

                    // The model also splits *within* a segment and hands back the
                    // closing punctuation as the start of the next line, which read
                    // as an orphaned ". The lock screen is working." Fold any such
                    // line back onto the one it belongs to.
                    var j = 1
                    while (j < merged.size) {
                        val t = merged[j].text.trimStart()
                        if (t.firstOrNull() in SENTENCE_END) {
                            val prev = merged[j - 1]
                            merged[j - 1] = prev.copy(
                                tEndMs = merged[j].tEndMs,
                                text = (prev.text.trimEnd() + t).trim()
                            )
                            merged.removeAt(j)
                        } else j++
                    }

                    // Hold back a trailing unpunctuated line: it may continue.
                    val last = merged.lastOrNull()
                    if (last != null && i + 1 < segments.size && !endsSentence(last.text)) {
                        pendingTail = last
                        merged.removeAt(merged.size - 1)
                    }

                    if (merged.isNotEmpty()) {
                        repo.appendLines(meetingId, merged.map { l ->
                            Line(0, meetingId, idx++, l.tStartMs, l.tEndMs, l.text, l.confidence)
                        })
                    }
                }
                repo.setProgress(meetingId, i + 1, engine.modelId)
                notify(meetingId, i + 1, segments.size)
            }

            pendingTail?.let { t ->
                repo.appendLines(
                    meetingId,
                    listOf(Line(0, meetingId, idx++, t.tStartMs, t.tEndMs, t.text, t.confidence))
                )
                pendingTail = null
            }

            repo.setState(meetingId, MeetingState.DONE)
            var finalLines = repo.lines(meetingId)
            draftTitle(repo, meetingId, finalLines)

            // Diarization runs here, while the audio still exists — it reads
            // waveforms, not text, so it cannot be added retroactively once the
            // retention policy has deleted the recording. Doing it inside the
            // same pass is what keeps "delete audio after transcribing" safe to
            // leave ON by default.
            if (DiarizeEngine.isReady(applicationContext) && finalLines.isNotEmpty()) {
                runCatching { diarize(repo, meetingId, segments, finalLines) }
                    .onFailure { Log.w(TAG, "diarization failed for $meetingId", it) }
                finalLines = repo.lines(meetingId)
            }

            // The audio has done its job. Dropping it here rather than on a timer
            // means the window where both exist is as short as it can be, and only
            // ever closes on success — a failed pass keeps its audio to retry from.
            if (Settings(applicationContext).deleteAudioAfterTranscribe && finalLines.isNotEmpty()) {
                runCatching { meeting.segmentDir.deleteRecursively() }
                    .onFailure { Log.w(TAG, "could not delete audio for $meetingId", it) }
            }

            val done = repo.meeting(meetingId)
            notifyDone(meetingId, done?.title?.ifBlank { "Untitled meeting" } ?: "Untitled meeting", finalLines.size)
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "transcription failed for meeting $meetingId", e)
            repo.setState(meetingId, MeetingState.FAILED, e.message ?: e::class.java.simpleName)
            NotificationManagerCompat.from(applicationContext).cancel(NOTIF_ID)
            Result.failure()
        } finally {
            try { engine?.close() } catch (_: Throwable) {}
        }
    }

    private fun endsSentence(text: String): Boolean =
        text.trimEnd().lastOrNull() in SENTENCE_END

    /**
     * Labels each transcript line with the voice that spoke most of it.
     *
     * Diarization needs the whole meeting at once — clustering is what makes
     * speaker 1 the same person at minute 2 and minute 40, and that is only
     * decidable globally. So this concatenates the segments rather than working
     * per segment as transcription does.
     */
    private fun diarize(repo: Repo, meetingId: Long, segments: List<File>, lines: List<Line>) {
        val total = segments.sumOf { it.length() / 2 }.toInt()
        if (total <= 0) return
        val all = FloatArray(total)
        var at = 0
        for (f in segments) {
            val chunk = readPcm(f)
            val room = minOf(chunk.size, total - at)
            if (room <= 0) break
            System.arraycopy(chunk, 0, all, at, room)
            at += room
        }

        DiarizeEngine.create(applicationContext).use { d ->
            val turns = d.run(all) { pct -> notifyDiarizing(meetingId, pct) }
            if (turns.isEmpty()) return
            repo.setLineSpeakers(meetingId, assign(lines, turns))
        }
    }

    /**
     * A line gets the speaker who holds the most of its duration. Turn and line
     * boundaries never align exactly — one is drawn by acoustics, the other by
     * punctuation — so overlap is the only sound basis for the decision.
     */
    private fun assign(lines: List<Line>, turns: List<DiarizeEngine.Turn>): Map<Int, Int> =
        buildMap {
            for (line in lines) {
                var best = -1
                var bestOverlap = 0L
                for (t in turns) {
                    if (t.endMs <= line.tStartMs) continue
                    if (t.startMs >= line.tEndMs) break
                    val overlap = minOf(t.endMs, line.tEndMs) - maxOf(t.startMs, line.tStartMs)
                    if (overlap > bestOverlap) { bestOverlap = overlap; best = t.speaker }
                }
                if (best >= 0) put(line.idx, best)
            }
        }

    /** Reads a raw little-endian PCM16 segment into the -1..1 floats the model wants. */
    private fun readPcm(file: File): FloatArray {
        val bytes = DataInputStream(file.inputStream().buffered()).use { it.readBytes() }
        val n = bytes.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }

    private fun notify(meetingId: Long, done: Int, total: Int) {
        val open = PendingIntent.getActivity(
            applicationContext, 2,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_TRANSCRIBE)
            .setContentTitle("Transcribing meeting")
            .setContentText("$done of $total segments")
            .setSmallIcon(R.drawable.ic_mic)
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(applicationContext).let {
            try { it.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    /**
     * Diarization is a second progress bar on a job the user already thinks is
     * finishing. It reuses the same notification so there is one row, not two,
     * and says what it is doing — otherwise the bar appears to restart.
     */
    private fun notifyDiarizing(meetingId: Long, pct: Int) {
        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_TRANSCRIBE)
            .setContentTitle("Identifying speakers")
            .setContentText("$pct%")
            .setSmallIcon(R.drawable.ic_mic)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(applicationContext).let {
            try { it.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    private fun notifyDone(meetingId: Long, title: String, lineCount: Int) {
        // Opens the transcript itself, with the list behind it so Back still works.
        // "Transcript ready" that lands on a list you then have to search is a
        // notification that made you do the work anyway.
        val open = TaskStackBuilder.create(applicationContext)
            .addNextIntent(Intent(applicationContext, MainActivity::class.java))
            .addNextIntent(
                Intent(applicationContext, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_ID, meetingId)
            )
            .getPendingIntent(
                meetingId.toInt(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_TRANSCRIBE)
            .setContentTitle("Transcript ready")
            .setContentText(
                if (lineCount > 0) "$title \u00b7 $lineCount lines" else title
            )
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(applicationContext).let {
            // Per-meeting id: two meetings finishing back to back are two results,
            // and the second must not silently replace the first.
            try { it.notify(doneNotifId(meetingId), n) } catch (_: SecurityException) {}
        }
        // Clear the in-progress notification, which is a different id now.
        NotificationManagerCompat.from(applicationContext).cancel(NOTIF_ID)
    }

    /**
     * Give an untitled meeting a name from its first sentence. A meeting is only
     * ever untitled because the user skipped naming it, so anything here beats
     * the empty string — but never overwrite a title they chose themselves.
     */
    private fun draftTitle(repo: Repo, meetingId: Long, lines: List<Line>) {
        if (repo.meeting(meetingId)?.title?.isNotBlank() == true) return
        val first = lines.firstOrNull { it.text.isNotBlank() } ?: return
        val words = first.text.trim().split(Regex("\\s+"))
        val draft = words.take(TITLE_WORDS).joinToString(" ").trimEnd(',', ';', ':', '.', '!', '?')
        if (draft.isBlank()) return
        repo.rename(meetingId, if (words.size > TITLE_WORDS) "$draft…" else draft)
    }

    private fun doneNotifId(meetingId: Long): Int = 2000 + (meetingId % 1000).toInt()

    companion object {
        private const val TAG = "TranscribeWorker"
        /** Characters that close a sentence, for both holding back and joining. */
        private val SENTENCE_END = setOf('.', '?', '!')
        private const val NOTIF_ID = 1002
        const val KEY_MEETING_ID = "meetingId"

        /** One tag across every transcribe job, so the UI can watch them all at once. */
        const val WORK_TAG = "transcribe"
        const val BREADCRUMB_LOADING = "Loading speech model\u2026"

        /** Long enough to be recognisable, short enough for one line in the list. */
        private const val TITLE_WORDS = 7

        /**
         * No constraints. An earlier version required batteryNotLow, which parked
         * the job in JobScheduler indefinitely below 15% — indistinguishable, in the
         * UI, from "queued normally". Transcription is work the user explicitly asked
         * for by stopping a recording; deferring it silently is worse than the battery
         * it saves. The user can wait for a charger if they want to.
         */
        fun enqueue(context: Context, meetingId: Long) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(Data.Builder().putLong(KEY_MEETING_ID, meetingId).build())
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("transcribe-$meetingId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
