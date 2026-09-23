package me.vattitude.scribe.asr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vattitude.scribe.Notify
import me.vattitude.scribe.R
import me.vattitude.scribe.ScribeApp
import me.vattitude.scribe.ui.MainActivity

/**
 * Fetches the speech models in the background, surviving the app being closed.
 *
 * It used to run in the main screen's coroutine scope, which made a 615 MB
 * download depend on that screen staying open: switch apps for long enough and
 * the process could be reclaimed mid-download, and every retry started from
 * zero. As a foreground job it carries on with the app closed, resumes from the
 * partial file (see [ModelManager.download]), and by default waits for an
 * unmetered network rather than spending someone's mobile data unasked.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val wanted = inputData.getStringArray(KEY_MODELS)
            ?.mapNotNull { name -> ModelManager.Model.entries.firstOrNull { it.name == name } }
            ?: ModelManager.Model.entries
        val pending = wanted.filterNot { ModelManager.isReady(applicationContext, it) }
        if (pending.isEmpty()) return Result.success()

        val grandTotal = wanted.sumOf { it.approxBytes }.coerceAtLeast(1L)
        runCatching { setForeground(foregroundInfo(0, grandTotal, 0)) }
            .onFailure { Log.w(TAG, "could not go foreground; continuing in background", it) }

        return withContext(Dispatchers.IO) {
            try {
                // Bytes of models finished before this run, so the bar covers
                // the whole set rather than restarting at each model.
                var base = wanted.filter { it !in pending }.sumOf { it.approxBytes }
                var lastEmit = 0L
                var speedFrom = System.currentTimeMillis()
                var speedBytes = -1L
                var bytesPerSec = 0L
                for (model in pending) {
                    ModelManager.download(applicationContext, model, cancelled = { isStopped }) { done, _ ->
                        val now = System.currentTimeMillis()
                        if (speedBytes < 0) { speedBytes = base + done; speedFrom = now }
                        if (now - lastEmit < 500) return@download
                        lastEmit = now
                        val soFar = base + done
                        val secs = (now - speedFrom) / 1000.0
                        if (secs >= 2) bytesPerSec = ((soFar - speedBytes) / secs).toLong()
                        setProgressAsync(
                            workDataOf(
                                KEY_DONE to soFar,
                                KEY_TOTAL to grandTotal,
                                KEY_SPEED to bytesPerSec
                            )
                        )
                        Notify.post(
                            applicationContext, NOTIF_ID,
                            foregroundInfo(soFar, grandTotal, bytesPerSec).notification
                        )
                    }
                    base += model.approxBytes
                }
                notifyDone()
                Result.success()
            } catch (e: Throwable) {
                Log.w(TAG, "model download stopped", e)
                if (isStopped) Result.retry()
                // Network drops are worth a few automatic tries; they resume
                // from the partial file, so each costs only the lost bytes.
                else if (runAttemptCount < MAX_ATTEMPTS && e is java.io.IOException) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    private fun foregroundInfo(done: Long, total: Long, speed: Long): ForegroundInfo {
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pct = (done * 100 / total.coerceAtLeast(1)).toInt()
        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_DOWNLOAD)
            .setContentTitle(applicationContext.getString(R.string.model_title_downloading))
            .setContentText(progressLine(done, total, speed))
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, pct, done == 0L)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    private fun notifyDone() {
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, ScribeApp.CHANNEL_DOWNLOAD)
            .setContentTitle(applicationContext.getString(R.string.model_done_title))
            .setContentText(applicationContext.getString(R.string.model_done_body))
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        Notify.post(applicationContext, DONE_NOTIF_ID, n)
    }

    companion object {
        private const val TAG = "ModelDownload"
        const val UNIQUE = "model-download"
        const val KEY_MODELS = "models"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SPEED = "speed"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 5
        // 1003/1004/1005 are taken by the other workers and the recorder.
        private const val NOTIF_ID = 1006
        private const val DONE_NOTIF_ID = 1007

        /**
         * Queues a download of [models]. Waits for Wi-Fi (any unmetered
         * network) unless [allowMobile]; calling again with allowMobile = true
         * replaces a download that is waiting for Wi-Fi.
         */
        fun enqueue(context: Context, models: List<ModelManager.Model>, allowMobile: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (allowMobile) NetworkType.CONNECTED else NetworkType.UNMETERED
                        )
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_MODELS to models.map { it.name }.toTypedArray()))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        /** "210 of 615 MB · 2.1 MB/s · about 3 min left" */
        fun progressLine(done: Long, total: Long, bytesPerSec: Long): String = buildString {
            append(done / 1_000_000).append(" of ").append(total / 1_000_000).append(" MB")
            if (bytesPerSec > 0) {
                append(" · ").append("%.1f".format(bytesPerSec / 1_000_000.0)).append(" MB/s")
                val secsLeft = (total - done).coerceAtLeast(0) / bytesPerSec
                append(" · ").append(
                    when {
                        secsLeft < 60 -> "under a minute left"
                        else -> "about ${(secsLeft + 59) / 60} min left"
                    }
                )
            }
        }
    }
}
