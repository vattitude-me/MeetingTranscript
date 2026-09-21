package me.vattitude.scribe.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.vattitude.scribe.R
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.Recording
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityRecordingBinding
import me.vattitude.scribe.store.Repo
import me.vattitude.scribe.store.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen you look at while a meeting is being recorded.
 *
 * It exists as its own activity rather than a mode of the main screen because
 * the two want opposite things: the list is for browsing, this is for glancing
 * at from across a desk and for one unmissable stop button.
 *
 * Leaving it does not stop the recording — the service owns that, and the widget
 * and the notification can both still stop it.
 */
class RecordingActivity : AppCompatActivity() {

    private lateinit var b: ActivityRecordingBinding
    private var blink: ValueAnimator? = null
    private var stopping = false

    /** Guards against closing before the service has published its first state. */
    private var sawRecording = false

    /**
     * The meeting being recorded, captured when stop is pressed.
     *
     * Read at stop() time rather than when the dialog is answered: the service
     * clears RecordingState within a second of being asked to stop, so by the
     * time the user has chosen a number there is no state left to read the id
     * from, and the answer would be written nowhere.
     */
    private var stoppedMeetingId = -1L

    /** Keeps the screen alive while the speaker-count question is on it. */
    private var asking = false
    private var startedAt = 0L

    /** Last elapsed figure from the service, and when it arrived. */
    private var lastElapsedMs = 0L
    private var lastElapsedAt = 0L

    /** Mirrors the service's pause state, so the ticker knows to hold. */
    private var paused = false
    private var ticker: Runnable? = null
    private var lastQuiet: Boolean? = null
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(b.root)

        // You put the phone down and watch it; letting it sleep would defeat that.
        // It costs nothing when the screen is off in a pocket, because then this
        // activity is not resumed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.stop.setOnClickListener { stop() }
        b.pause.setOnClickListener {
            // The service owns the state; this only asks. render() paints the
            // result, so the button and the notification can never disagree.
            RecorderService.setPaused(this, RecordingState.state.value?.paused != true)
        }
        b.levelHint.text = "Starting\u2026"
        b.collapse.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecordingState.state.collectLatest { render(it) } }
            }
        }
        startBlink()
        startTicker()
    }

    /** Repaints the timer once a second regardless of when audio buffers land. */
    /**
     * Paints the timer once a second between service updates.
     *
     * It counts forward from the last elapsed figure the service published
     * rather than from wall-clock minus the start time. Wall-clock was wrong in
     * two ways: it kept counting through a pause, claiming time that is not in
     * the recording, and it drifted from the audio on disk even while running.
     * The service's elapsed is derived from bytes written, so it is the length
     * of the actual recording; this only smooths the ~2s gap between updates.
     */
    private fun startTicker() {
        ticker = object : Runnable {
            override fun run() {
                if (lastElapsedMs > 0 || lastElapsedAt > 0) {
                    // Hold the figure once paused or stopping. Both are states
                    // where the service has stopped publishing updates, so
                    // counting wall-clock forward from the last one would invent
                    // time that is not in the recording -- visibly, behind the
                    // speaker-count dialog, on a screen that says STOPPING.
                    val frozen = paused || stopping
                    val since = if (frozen) 0L else System.currentTimeMillis() - lastElapsedAt
                    b.timer.text = RecorderService.formatElapsed(lastElapsedMs + since)
                }
                b.timer.postDelayed(this, 1000)
            }
        }.also { b.timer.post(it) }
    }

    private fun render(r: Recording?) {
        if (r == null) {
            // Starting is not instant: the service has to open AudioRecord before
            // it publishes the first Recording, and this screen is usually opened
            // in the same breath as starting it. Closing on the first null would
            // dump the user straight back to the list, which is what it did.
            // Only treat null as "stopped" once we have actually seen it running.
            // Not while the speaker-count dialog is up. The service clears the
            // recording state almost immediately after stop(), so finishing on
            // that signal would tear the question off the screen before it
            // could be read, let alone answered.
            if (sawRecording && !isFinishing && !asking) finish()
            return
        }
        sawRecording = true
        paused = r.paused
        b.pause.setImageResource(if (paused) R.drawable.ic_mic else R.drawable.ic_pause)
        b.pause.contentDescription =
            getString(if (paused) R.string.resume_recording else R.string.pause_recording)
        if (paused) {
            b.statusText.text = "PAUSED"
            // Stop the blink: a pulsing dot says "live", which is the opposite
            // of what a paused recording is doing.
            blink?.pause()
            b.micDot.alpha = 0.25f
        } else if (!stopping) {
            blink?.resume()
            // Undo everything the paused branch overwrote. setQuiet() also
            // writes this label, but only when the quiet state *changes* — pause
            // and resume during a steady stretch never trip it, so resuming
            // would otherwise leave "PAUSED" on screen over a live recording.
            if (lastQuiet != true) {
                b.statusText.setText(R.string.mic_live)
                b.statusText.setTextColor(color(R.color.s_on_mic_container))
            }
            b.micDot.alpha = 1f
        }
        // Note the anchor and let the ticker render it. Painting the timer from
        // here would inherit the capture loop's ~2s cadence and visibly stutter.
        startedAt = r.startedAt
        lastElapsedMs = r.elapsedMs
        lastElapsedAt = System.currentTimeMillis()
        b.meter.push(r.level)

        if (b.recMeta.text.isNullOrBlank()) {
            b.recMeta.text = "Started " + clockFmt.format(Date(r.startedAt)) +
                " \u00b7 16 kHz mono \u00b7 on device"
        }

        b.levelHint.text = when {
            r.isWorryinglyQuiet -> "No input for " + (r.silentMs / 1000) + "s"
            r.level < Recording.SILENCE_LEVEL -> "Input level \u00b7 quiet"
            else -> "Input level \u00b7 picking up audio"
        }

        // The whole screen changes colour when it stops hearing anything: the
        // failure that actually costs you a meeting is a mic you never notice
        // was covered, so it is worth more than a line of grey text.
        setQuiet(r.isWorryinglyQuiet, r.silentMs)
    }

    private fun setQuiet(quiet: Boolean, silentMs: Long) {
        if (quiet == lastQuiet) return
        lastQuiet = quiet
        if (quiet) {
            b.statusChip.setBackgroundResource(R.drawable.chip_warn)
            b.micDot.visibility = android.view.View.GONE
            b.statusIcon.visibility = android.view.View.VISIBLE
            b.statusText.text = "SILENT FOR " + (silentMs / 1000) + "S"
            b.statusText.setTextColor(color(R.color.s_on_warn_container))
            b.infoCard.setBackgroundResource(R.drawable.surface_warn)
            b.infoIcon.setImageResource(R.drawable.ic_mic_slash)
            b.infoIcon.setColorFilter(color(R.color.s_warn))
            b.infoTitle.setText(R.string.silent_title)
            b.infoTitle.setTextColor(color(R.color.s_on_warn_container))
            b.infoBody.setText(R.string.silent_body)
            b.infoBody.setTextColor(color(R.color.s_warn_body))
        } else {
            b.statusChip.setBackgroundResource(R.drawable.chip_mic)
            b.micDot.visibility = android.view.View.VISIBLE
            b.statusIcon.visibility = android.view.View.GONE
            b.statusText.setText(R.string.mic_live)
            b.statusText.setTextColor(color(R.color.s_on_mic_container))
            b.infoCard.setBackgroundResource(R.drawable.surface_card)
            b.infoIcon.setImageResource(R.drawable.ic_bell)
            b.infoIcon.setColorFilter(color(R.color.s_cyan))
            b.infoTitle.setText(R.string.lock_it)
            b.infoTitle.setTextColor(color(R.color.s_text))
            b.infoBody.setText(R.string.lock_it_body)
            b.infoBody.setTextColor(color(R.color.s_text_muted))
        }
    }

    private fun color(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)

    private fun startBlink() {
        blink = ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { b.micDot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stop() {
        if (stopping) return
        stopping = true
        b.stop.isEnabled = false
        b.stop.alpha = 0.5f
        b.statusText.text = "STOPPING\u2026"
        // Paint the last known length now. The ticker freezes from here, so
        // whatever is on screen when the dialog opens is what stays there.
        if (lastElapsedMs > 0) b.timer.text = RecorderService.formatElapsed(lastElapsedMs)
        // Before stopping: the service clears this as it shuts down.
        stoppedMeetingId = RecordingState.state.value?.meetingId ?: -1L
        RecorderService.stop(this)
        // Do not finish() here: wait for the service to actually clear the state,
        // so the user never sees the screen vanish on a stop that failed.
        askSpeakerCount()
    }

    /**
     * Asks how many people spoke, once, right after the stop button is pressed.
     *
     * This is the one moment the answer is both known and cheap to give: the
     * user just sat through the meeting and is still holding the phone. Asked
     * later, from the transcript, it costs a re-run of speaker separation and
     * relies on the audio still being there.
     *
     * The count is only a hint, so this never blocks. Dismissing it, backing
     * out or walking away all leave expected_speakers at 0, which is exactly
     * the old behaviour — the clusterer guesses. Transcription has already been
     * handed to the service and does not wait for this.
     *
     * Why ask at all, when the app could just guess: measured across a
     * ten-sample matrix, guessing was wrong in both directions — a single voice
     * came back as six, five voices came back as three. Told the true count it
     * did better, though later measurement on a realistic ten-person meeting
     * showed it can ignore the count outright. See Settings.identifySpeakers.
     *
     * Skipped entirely when speaker separation is off, which is the default.
     * Asking a question whose answer is then never used is worse than not
     * asking: it costs a tap at the end of every meeting and implies the app
     * is doing something it is not.
     */
    private fun askSpeakerCount() {
        val id = stoppedMeetingId
        if (id < 0) return
        if (!Settings(this).identifySpeakers) { finish(); return }
        asking = true
        val options = (1..8).map { if (it == 1) "1 person (just me)" else "$it people" } +
            "Not sure — let the app decide"
        // Title carries the hint rather than setMessage(): AlertDialog renders a
        // message OR a list, never both, so setting one silently swallowed the
        // other and shipped a chooser with nothing to choose from.
        AlertDialog.Builder(this)
            .setTitle(
                "Who did most of the talking?\n" +
                    "Optional \u2014 helps speaker labels, which are in beta and often wrong"
            )
            .setItems(options.toTypedArray()) { _, which ->
                if (which != options.lastIndex) {
                    val count = which + 1
                    // applicationContext: this activity finishes the moment the
                    // dialog closes, and the write must outlive it.
                    val app = applicationContext
                    Thread { runCatching { Repo(app).setExpectedSpeakers(id, count) } }.start()
                }
            }
            .setNegativeButton("Skip", null)
            // Every exit runs through here — a choice, Skip, or a tap outside —
            // so the screen closes exactly once however the question is ended.
            .setOnDismissListener {
                asking = false
                if (!isFinishing) finish()
            }
            .show()
    }

    override fun onDestroy() {
        ticker?.let { b.timer.removeCallbacks(it) }
        blink?.cancel()
        // The dialog's dismiss listener calls finish(); if the activity is
        // already going away (the user backed out, or the system took it), that
        // listener must not act on a dead window.
        asking = false
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(
                Intent(context, RecordingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
