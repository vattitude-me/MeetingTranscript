package me.vattitude.scribe.asr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
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
import me.vattitude.scribe.ui.MainActivity
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

        if (!ModelManager.isReady(applicationContext)) {
            // Not an error — the user simply hasn't downloaded the model yet.
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
        try {
            engine = ParakeetEngine.create(applicationContext)
            var idx = repo.nextLineIdx(meetingId)

            // Resume: skip whatever a previous run already committed.
            for (i in meeting.segmentsDone until segments.size) {
                if (isStopped) {
                    repo.setState(meetingId, MeetingState.RECORDED, "Paused")
                    return@withContext Result.retry()
                }
                val file = segments[i]
                val samples = readPcm(file)
                val offsetMs = i * Audio.SEGMENT_MS

                val lines = engine.transcribe(samples, offsetMs)
                if (lines.isNotEmpty()) {
                    repo.appendLines(meetingId, lines.map { l ->
                        Line(0, meetingId, idx++, l.tStartMs, l.tEndMs, l.text, l.confidence)
                    })
                }
                repo.setProgress(meetingId, i + 1, engine.modelId)
                notify(meetingId, i + 1, segments.size)
            }

            repo.setState(meetingId, MeetingState.DONE)
            notifyDone(meetingId, repo.meeting(meetingId)?.title ?: "Meeting")
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

    private fun notifyDone(meetingId: Long, title: String) {
        val open = PendingIntent.getActivity(
            applicationContext, 3,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_TRANSCRIBE)
            .setContentTitle("Transcript ready")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(applicationContext).let {
            try { it.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    companion object {
        private const val TAG = "TranscribeWorker"
        private const val NOTIF_ID = 1002
        const val KEY_MEETING_ID = "meetingId"

        fun enqueue(context: Context, meetingId: Long, requireCharging: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(Data.Builder().putLong(KEY_MEETING_ID, meetingId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(requireCharging)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("transcribe-$meetingId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
