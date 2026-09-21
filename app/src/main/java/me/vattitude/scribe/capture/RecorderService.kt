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
import me.vattitude.scribe.asr.EarlyTranscriber
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.asr.TranscribeWorker
import me.vattitude.scribe.store.Repo
import me.vattitude.scribe.store.Settings
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

    /**
     * Set while the recording is paused for a break.
     *
     * The capture loop keeps reading from AudioRecord while this is true and
     * throws the buffers away rather than stopping the hardware. Stopping and
     * restarting AudioRecord mid-meeting risks the device handing back a
     * different route or failing to reopen at all -- losing the rest of the
     * meeting to save a few milliamps is the wrong trade. Draining the reads
     * also keeps the ring buffer from overrunning while paused.
     *
     * Paused audio is never written, so it does not exist in the file and
     * cannot reach the transcript. Elapsed time is derived from bytes written,
     * which means the timer stops on its own without a second clock to keep in
     * sync.
     */
    @Volatile private var paused = false

    /** When the current recording started, for reposting the notification. */
    private var notifStartedAt = 0L

    /**
     * Recorded milliseconds at which the next check-in is due, and when the
     * outstanding prompt was posted (0 when none is waiting).
     *
     * Both are read and written only by the capture thread, except
     * [checkInAskedAt] which [ACTION_KEEP_RECORDING] clears from the main
     * thread — hence volatile. The window between the loop reading it and the
     * action clearing it is one buffer, and losing that race merely means the
     * prompt is dismissed a couple of seconds later than the tap.
     */
    private var nextCheckInMs = Long.MAX_VALUE
    @Volatile private var checkInAskedAt = 0L

    /** Total time spent paused, so the notification clock can skip it. */
    private var pausedTotalMs = 0L
    private var pausedSince = 0L
    private var worker: Thread? = null
    private var meetingId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_KEEP_RECORDING -> keepRecording()
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
        notifStartedAt = startedAt
        pausedTotalMs = 0
        pausedSince = 0
        paused = false
        checkInAskedAt = 0
        // Read once per recording: changing the interval mid-meeting would move
        // a deadline the user is already being measured against.
        val checkIn = Settings(this).checkInMinutes
        nextCheckInMs = if (checkIn > 0) checkIn * 60_000L else Long.MAX_VALUE
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

        // The accurate pass, started early, on segments that have already
        // closed. It writes final lines while the meeting is still running, so
        // a long recording is largely transcribed by the time it is stopped.
        // Reads only from disk and never touches anything this loop holds --
        // see EarlyTranscriber for what keeps it out of the recording's way.
        if (Settings(this).transcribeWhileRecording) {
            EarlyTranscriber.start(this, meetingId, dir)
        }

        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                // Paused: the read still happens, so the ring buffer keeps
                // draining, but the samples go nowhere. Nothing is written, the
                // live pass is not fed, and the meter is pinned to zero -- a
                // paused recording should look silent, not frozen mid-level.
                if (paused) {
                    // Silence is not accumulated while paused: it measures
                    // whether the mic is working, and a deliberate pause is not
                    // evidence that it is not. Left running, a five-minute break
                    // would raise "hearing nothing" the moment you resumed.
                    silentMs = 0
                    RecordingState.set(
                        Recording(
                            meetingId, startedAt,
                            Audio.bytesToMs(writer.totalSamples * Audio.BYTES_PER_SAMPLE),
                            0f, 0, paused = true
                        )
                    )
                    continue
                }

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

                // Ask, then pause if nobody answers. See Settings.checkInMinutes.
                if (checkInAskedAt == 0L && elapsed >= nextCheckInMs) {
                    checkInAskedAt = now
                    askStillRecording(elapsed)
                } else if (checkInAskedAt != 0L &&
                    now - checkInAskedAt >= Settings.CHECK_IN_GRACE_MS
                ) {
                    // Unanswered. Pause rather than stop: the meeting may still
                    // be going on in a room where nobody is looking at a phone,
                    // and a pause can be resumed from the notification.
                    checkInAskedAt = 0
                    NotificationManagerCompat.from(this).cancel(CHECK_IN_NOTIF_ID)
                    notifyAutoPaused()
                    setPaused(true)
                }

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

            // An unanswered prompt outlives the recording otherwise: "Still
            // recording?" on a meeting that finished ten minutes ago, with a
            // Keep recording button that no longer does anything.
            checkInAskedAt = 0
            NotificationManagerCompat.from(this).cancel(CHECK_IN_NOTIF_ID)

            val liveLines = live?.stop() ?: 0
            // Before writer.close() and finishRecording: the early pass writes
            // segments_done, and the offline pass resumes from it. Letting it
            // commit after the meeting is marked finished would race the worker
            // that is about to read that number.
            EarlyTranscriber.stop()

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

    /**
     * Pauses or resumes, and reposts the notification so its button matches.
     *
     * Ignored when not recording: a stale Pause tap from an old notification
     * must not start anything. Flushes on pause so the audio up to the break is
     * already durable if the break turns out to be the end of the meeting.
     */
    private fun setPaused(value: Boolean) {
        if (!running || paused == value) return
        val now = System.currentTimeMillis()
        if (value) {
            pausedSince = now
        } else if (pausedSince > 0) {
            // Push the chronometer's anchor forward by the length of the break,
            // so it resumes from where it stopped instead of counting the pause.
            pausedTotalMs += now - pausedSince
            pausedSince = 0
        }
        paused = value
        RecordingState.state.value?.let { RecordingState.set(it.copy(paused = value)) }
        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(notifStartedAt, quiet = false))
        ScribeWidget.refresh(this)
    }

    /**
     * "Keep recording" was tapped: clear the prompt and buy another interval.
     *
     * Also resumes if the prompt had already expired and paused the recording —
     * the user answering late plainly means they want it running, and making
     * them tap Resume separately would be pedantry about a two-minute deadline.
     */
    private fun keepRecording() {
        if (!running) return
        checkInAskedAt = 0
        NotificationManagerCompat.from(this).cancel(CHECK_IN_NOTIF_ID)
        val interval = Settings(this).checkInMinutes
        if (interval > 0) {
            val elapsed = RecordingState.state.value?.elapsedMs ?: 0L
            nextCheckInMs = elapsed + interval * 60_000L
        }
        if (paused) setPaused(false)
    }

    /**
     * Asks whether a long recording should continue.
     *
     * Its own notification, not an edit of the ongoing one: the recording
     * notification is silent and sits low in the shade, which is right for
     * something that needs no answer and wrong for something with a deadline.
     */
    private fun askStillRecording(elapsedMs: Long) {
        val keep = PendingIntent.getService(
            this, 3,
            Intent(this, RecorderService::class.java).setAction(ACTION_KEEP_RECORDING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 4,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val minutes = (elapsedMs / 60_000L).toInt()
        val n = NotificationCompat.Builder(this, ScribeApp.CHANNEL_CHECK_IN)
            .setContentTitle("Still recording?")
            // Says what happens if ignored. A prompt that hides its own deadline
            // is how a user learns the rule by losing audio to it once.
            .setContentText("$minutes minutes so far. Pausing shortly unless you tap Keep recording.")
            .setSmallIcon(R.drawable.ic_mic)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(R.drawable.ic_mic, "Keep recording", keep)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .build()
        NotificationManagerCompat.from(this).let {
            try { it.notify(CHECK_IN_NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    /**
     * Says the recording was paused, and offers the one tap that undoes it.
     *
     * Silence here would be the worst outcome of the whole feature: a recording
     * that stopped capturing with no trace of why.
     */
    private fun notifyAutoPaused() {
        val resume = PendingIntent.getService(
            this, 5,
            Intent(this, RecorderService::class.java).setAction(ACTION_KEEP_RECORDING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, ScribeApp.CHANNEL_CHECK_IN)
            .setContentTitle("Recording paused")
            .setContentText("Nothing is being recorded. Tap Resume to carry on.")
            .setSmallIcon(R.drawable.ic_mic)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_mic, "Resume", resume)
            .build()
        NotificationManagerCompat.from(this).let {
            try { it.notify(CHECK_IN_NOTIF_ID, n) } catch (_: SecurityException) {}
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
        // One button that flips, rather than a Pause and a Resume side by side:
        // only one of them is ever valid, and a notification row has little
        // space to waste on a control that does nothing.
        val toggle = PendingIntent.getService(
            this, 2,
            Intent(this, RecorderService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            paused -> "Paused"
            quiet -> "Recording \u2014 hearing nothing"
            else -> "Recording meeting"
        }
        val text = when {
            paused -> "Nothing is being recorded"
            quiet -> "Check the mic isn't covered"
            else -> null
        }
        return NotificationCompat.Builder(this, ScribeApp.CHANNEL_RECORDING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Android ticks this itself, once a second, smoothly. While paused
            // it is switched off rather than left running: a clock that keeps
            // counting would claim time that is not in the recording.
            .setUsesChronometer(!paused)
            // Anchored forward by the time spent paused, so the count resumes
            // where it stopped instead of jumping by the length of the break.
            .setWhen(startedAt + pausedTotalMs)
            .setShowWhen(!paused)
            .setContentIntent(open)
            .addAction(
                if (paused) R.drawable.ic_mic else R.drawable.ic_pause,
                if (paused) "Resume" else "Pause",
                toggle
            )
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "RecorderService"
        const val ACTION_STOP = "me.vattitude.scribe.STOP"
        const val ACTION_PAUSE = "me.vattitude.scribe.PAUSE"
        const val ACTION_RESUME = "me.vattitude.scribe.RESUME"
        const val ACTION_KEEP_RECORDING = "me.vattitude.scribe.KEEP_RECORDING"
        private const val NOTIF_ID = 1001

        /**
         * Separate from [NOTIF_ID]: this one is dismissible and makes noise.
         * 1004 because ReDiarizeWorker already holds 1003.
         */
        private const val CHECK_IN_NOTIF_ID = 1004

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

        /** Pause or resume the running recording. No-op when nothing is recording. */
        fun setPaused(context: Context, paused: Boolean) {
            if (!RecordingState.isRecording) return
            context.startService(
                Intent(context, RecorderService::class.java)
                    .setAction(if (paused) ACTION_PAUSE else ACTION_RESUME)
            )
        }
    }
}
