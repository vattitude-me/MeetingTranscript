package me.vattitude.scribe.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.vattitude.scribe.R
import me.vattitude.scribe.ScribeApp
import me.vattitude.scribe.asr.TranscribeWorker
import me.vattitude.scribe.store.Repo
import me.vattitude.scribe.ui.MainActivity
import me.vattitude.scribe.widget.ScribeWidget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Records the meeting and nothing else.
 *
 * This service deliberately does no inference. Transcription runs afterwards as
 * a separate WorkManager job, which means an hour of recording costs almost no
 * CPU, the phone stays cool, and there is no way for the ASR path to take the
 * recording down with it.
 */
class RecorderService : Service() {

    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var meetingId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (running) return
        running = true

        val startedAt = System.currentTimeMillis()
        val title = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(startedAt))
        val dir = File(File(filesDir, "meetings"), startedAt.toString())
        val repo = Repo(this)
        meetingId = repo.createMeeting(title, startedAt, dir)

        startForeground(
            NOTIF_ID,
            buildNotification(0L),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        ScribeWidget.refresh(this)

        worker = thread(name = "scribe-recorder", priority = Thread.MAX_PRIORITY) {
            captureLoop(repo, dir, startedAt)
        }
    }

    private fun captureLoop(repo: Repo, dir: File, startedAt: Long) {
        val minBuf = AudioRecord.getMinBufferSize(
            Audio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // A generous buffer: the phone may be asleep and scheduling is lumpy.
        val bufBytes = maxOf(minBuf, Audio.SAMPLE_RATE * Audio.BYTES_PER_SAMPLE) * 2

        val rec = try {
            AudioRecord(
                // VOICE_RECOGNITION skips the aggressive call-tuned processing that
                // MIC applies; it consistently transcribes better.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Audio.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO not granted", e)
            repo.setState(meetingId, me.vattitude.scribe.store.MeetingState.FAILED, "Microphone permission denied")
            stopSelf(); return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            repo.setState(meetingId, me.vattitude.scribe.store.MeetingState.FAILED, "Could not open the microphone")
            rec.release(); stopSelf(); return
        }

        recorder = rec
        val writer = SegmentWriter(dir)
        val buf = ShortArray(bufBytes / Audio.BYTES_PER_SAMPLE)
        var lastNotify = 0L
        var lastFlush = 0L

        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                writer.write(buf, n)

                val elapsed = Audio.bytesToMs(writer.totalSamples * Audio.BYTES_PER_SAMPLE)
                var peak = 0
                var i = 0
                while (i < n) { val a = kotlin.math.abs(buf[i].toInt()); if (a > peak) peak = a; i += 16 }
                RecordingState.set(Recording(meetingId, startedAt, elapsed, peak / 32768f))

                val now = System.currentTimeMillis()
                if (now - lastNotify > 1000) {
                    lastNotify = now
                    NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(elapsed))
                }
                // Durability beats throughput: get bytes to disk every few seconds.
                if (now - lastFlush > 3000) { lastFlush = now; writer.flush() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "capture loop died", e)
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            recorder = null

            val segments = writer.close()
            val durationMs = Audio.bytesToMs(writer.totalSamples * Audio.BYTES_PER_SAMPLE)
            repo.finishRecording(meetingId, System.currentTimeMillis(), durationMs, segments)
            RecordingState.set(null)
            ScribeWidget.refresh(this)

            if (segments > 0) TranscribeWorker.enqueue(this, meetingId)
            stopSelf()
        }
    }

    private fun stopRecording() {
        running = false
        worker?.join(5_000)
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        RecordingState.set(null)
        ScribeWidget.refresh(this)
        super.onDestroy()
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ScribeApp.CHANNEL_RECORDING)
            .setContentTitle("Recording meeting")
            .setContentText(formatElapsed(elapsedMs))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(false)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "RecorderService"
        const val ACTION_STOP = "me.vattitude.scribe.STOP"
        private const val NOTIF_ID = 1001

        fun formatElapsed(ms: Long): String {
            val s = ms / 1000
            return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        }

        fun start(context: Context) {
            val i = Intent(context, RecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
