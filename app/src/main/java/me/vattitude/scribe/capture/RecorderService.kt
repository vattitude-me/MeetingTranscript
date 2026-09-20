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
import me.vattitude.scribe.asr.ModelManager
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
        // Empty, not a timestamp. The row already shows date and time on its
        // metadata line, so a timestamp title was the same string twice and told
        // you nothing a week later. The UI renders "Untitled meeting" until the
        // first transcribed sentence drafts a real one.
        val title = ""
        val dir = File(File(filesDir, "meetings"), startedAt.toString())
        val repo = Repo(this)
        meetingId = repo.createMeeting(title, startedAt, dir)

        startForeground(
            NOTIF_ID,
            buildNotification(startedAt),
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
        var lastFlush = 0L
        var silentMs = 0L
        var lastQuiet = false

        // Live transcription runs on its own thread, fed by a bounded queue.
        // Inference must never block the capture loop: a slow decode would make
        // AudioRecord's ring buffer overrun and we would lose audio outright.
        // If the queue backs up we drop from the live view, never from the file.
        val live = LiveTranscriber(this, repo, meetingId).takeIf { it.start() }

        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                writer.write(buf, n)
                live?.offer(buf, n)

                val elapsed = Audio.bytesToMs(writer.totalSamples * Audio.BYTES_PER_SAMPLE)

                // One AudioRecord buffer is ~2s and the meter draws one bar per
                // published level, so publishing once per read gave half a bar a
                // second: the strip sat mostly empty for the first few minutes and
                // read as "barely hearing you". Split the buffer into short windows
                // and publish each one's peak — same audio, a meter that fills at
                // conversation speed.
                val window = (n / METER_WINDOWS).coerceAtLeast(1)
                var peak = 0
                var w = 0
                while (w < n) {
                    val end = minOf(w + window, n)
                    var wPeak = 0
                    var i = w
                    while (i < end) { val a = kotlin.math.abs(buf[i].toInt()); if (a > wPeak) wPeak = a; i += 4 }
                    if (wPeak > peak) peak = wPeak
                    RecordingState.set(
                        Recording(meetingId, startedAt, elapsed, wPeak / 32768f, silentMs)
                    )
                    w = end
                }
                val level = peak / 32768f

                // Track silence in audio time, not wall-clock: it is the bytes on
                // disk we are judging, and a stalled read should not look loud.
                val bufferMs = Audio.bytesToMs(n.toLong() * Audio.BYTES_PER_SAMPLE)
                silentMs = if (level < Recording.SILENCE_LEVEL) silentMs + bufferMs else 0
                RecordingState.set(Recording(meetingId, startedAt, elapsed, level, silentMs))

                val now = System.currentTimeMillis()
                // The capture loop only comes round every ~2s (one AudioRecord
                // buffer), so ticking the notification from here made the timer
                // jump 0, 2, 4. Let the system run the clock instead: a chronometer
                // counts smoothly on its own and costs no wakeups. We still repost
                // when the quiet warning flips, which is the only text that changes.
                val quiet = silentMs >= Recording.QUIET_WARNING_MS
                if (quiet != lastQuiet) {
                    lastQuiet = quiet
                    NotificationManagerCompat.from(this).notify(
                        NOTIF_ID, buildNotification(startedAt, quiet)
                    )
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

            val liveLines = live?.stop() ?: 0

            val segments = writer.close()
            val durationMs = Audio.bytesToMs(writer.totalSamples * Audio.BYTES_PER_SAMPLE)
            repo.finishRecording(meetingId, System.currentTimeMillis(), durationMs, segments)
            RecordingState.set(null)
            ScribeWidget.refresh(this)

            if (segments > 0) {
                // The live pass is a preview, not the product: its small streaming
                // model commits words before it has heard the end of the sentence.
                // The audio is on disk either way, so always re-run the accurate
                // model afterwards — it replaces those lines in place.
                TranscribeWorker.enqueue(this, meetingId)
            } else if (liveLines > 0) {
                // No audio segments to re-read, so whatever live produced is all
                // there will ever be.
                repo.setProgress(meetingId, 0, ModelManager.Model.LIVE.id)
                repo.setState(meetingId, me.vattitude.scribe.store.MeetingState.DONE)
            }
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

    private fun buildNotification(startedAt: Long, quiet: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, me.vattitude.scribe.ui.RecordingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ScribeApp.CHANNEL_RECORDING)
            .setContentTitle(if (quiet) "Recording \u2014 hearing nothing" else "Recording meeting")
            .setContentText(if (quiet) "Check the mic isn't covered" else null)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Android ticks this itself, once a second, smoothly.
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setShowWhen(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "RecorderService"
        const val ACTION_STOP = "me.vattitude.scribe.STOP"
        private const val NOTIF_ID = 1001

        /**
         * Level samples published per AudioRecord buffer. Eight windows over a
         * ~2s buffer is about four meter bars a second, which fills the strip in
         * roughly half a minute without flooding the UI thread.
         */
        private const val METER_WINDOWS = 8

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
