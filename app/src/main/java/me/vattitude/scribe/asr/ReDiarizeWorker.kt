package me.vattitude.scribe.asr

import android.content.Context
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
import me.vattitude.scribe.store.Repo

/**
 * Redoes only the speaker separation on an already-transcribed meeting.
 *
 * The count the clusterer guesses is often wrong — a single narrator recorded
 * for nine minutes can come back as six people. The user can see that it is
 * wrong, and they know the real answer, so they can supply it. This applies
 * that answer without re-running transcription, which is the slow half and
 * whose output would be identical anyway.
 *
 * The audio segments are still on disk after transcription, which is what makes
 * this cheap enough to offer as a menu item.
 */
class ReDiarizeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val meetingId = inputData.getLong(KEY_MEETING_ID, -1L)
        if (meetingId < 0) return@withContext Result.failure()

        val repo = Repo(applicationContext)
        val meeting = repo.meeting(meetingId) ?: return@withContext Result.failure()
        if (!DiarizeEngine.isReady(applicationContext)) {
            Log.w(TAG, "speaker models not downloaded")
            return@withContext Result.success()
        }

        val segments = Diarizer.segmentsOf(meeting.segmentDir)
        val lines = repo.lines(meetingId)
        if (segments.isEmpty() || lines.isEmpty()) return@withContext Result.success()

        try {
            Diarizer.run(applicationContext, repo, meetingId, segments, lines) { notifyWorking() }
        } catch (t: Throwable) {
            // Speaker labels are an enhancement to a transcript that already
            // exists. Failing here must never look like the meeting broke, so
            // the old labels simply stay as they were.
            Log.w(TAG, "re-diarization failed", t)
        } finally {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIF_ID)
        }
        Result.success()
    }

    private fun notifyWorking() {
        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_TRANSCRIBE)
            .setContentTitle("Separating voices again")
            .setContentText("Applying the speaker count you gave")
            .setSmallIcon(R.drawable.ic_mic)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(applicationContext).let {
            try { it.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    companion object {
        private const val TAG = "ReDiarizeWorker"
        private const val NOTIF_ID = 1003
        const val KEY_MEETING_ID = "meetingId"
        const val WORK_TAG = "rediarize"

        /**
         * REPLACE, not KEEP: if the user changes the count twice, the second
         * answer is the one they mean.
         */
        fun enqueue(context: Context, meetingId: Long) {
            val req = OneTimeWorkRequestBuilder<ReDiarizeWorker>()
                .setInputData(Data.Builder().putLong(KEY_MEETING_ID, meetingId).build())
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("rediarize-$meetingId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
