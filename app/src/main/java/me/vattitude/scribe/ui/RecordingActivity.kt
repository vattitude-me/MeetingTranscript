package me.vattitude.scribe.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.vattitude.scribe.R
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.capture.LiveTranscript
import me.vattitude.scribe.capture.LiveView
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.Recording
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityRecordingBinding
import me.vattitude.scribe.databinding.SheetStopBinding
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
     * Read at stop() time rather than when the sheet is answered: the service
     * clears RecordingState within a second of being asked to stop, so by the
     * time the user has typed a name there is no state left to read the id
     * from, and the answer would be written nowhere.
     */
    private var stoppedMeetingId = -1L

    /** Keeps the screen alive while the stop sheet is on it. */
    private var asking = false

    /** Last elapsed figure from the service, and when it arrived. */
    private var lastElapsedMs = 0L
    private var lastElapsedAt = 0L

    /** Mirrors the service's pause state, so the ticker knows to hold. */
    private var paused = false
    private var ticker: Runnable? = null
    private var lastQuiet: Boolean? = null

    /** The silence card was dismissed; it stays hidden until the mic hears something. */
    private var quietDismissed = false

    /** Live words have arrived, so the lock-screen tip has done its job. */
    private var hasLiveText = false
    private var lastMarks = 0

    /** False until the first state has been painted. */
    private var sawState = false
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
        b.mark.setOnClickListener {
            if (paused || stopping) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            RecorderService.mark(this)
        }
        b.infoDismiss.setOnClickListener {
            quietDismissed = true
            lastQuiet = null
            setQuiet(false, 0)
        }
        b.levelHint.setText(R.string.level_starting)
        b.collapse.setOnClickListener { finish() }

        if (!ModelManager.isReady(this, ModelManager.Model.LIVE)) {
            b.liveText.setText(R.string.live_off)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecordingState.state.collectLatest { render(it) } }
                launch { LiveTranscript.state.collectLatest { renderLive(it) } }
            }
        }
        startBlink()
        startTicker()
    }

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
                    // stop sheet, on a screen that says STOPPING.
                    val frozen = paused || stopping
                    val since = if (frozen) 0L else System.currentTimeMillis() - lastElapsedAt
                    b.timer.text = RecorderService.formatElapsed(lastElapsedMs + since)
                }
                b.timer.postDelayed(this, 1000)
            }
        }.also { b.timer.post(it) }
    }

    /**
     * The rough streaming transcript, newest words at the bottom.
     *
     * Rough on purpose and labelled so: its job is to prove the mic is hearing
     * words rather than noise. The transcript you keep is made after stop.
     */
    private fun renderLive(v: LiveView) {
        val text = (v.lines + v.partial).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return
        b.liveText.text = text
        b.liveText.setTextColor(color(R.color.s_text_dim))
        if (!hasLiveText) {
            hasLiveText = true
            // The tip about locking the screen has been read by now; give its
            // room to the words. The silence warning still takes it back.
            if (lastQuiet != true) b.infoCard.visibility = View.GONE
        }
        // Follow the tail like a terminal, so the newest words are the ones on
        // screen without touching anything.
        b.liveScroll.post { b.liveScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun render(r: Recording?) {
        if (r == null) {
            // Starting is not instant: the service has to open AudioRecord before
            // it publishes the first Recording, and this screen is usually opened
            // in the same breath as starting it. Closing on the first null would
            // dump the user straight back to the list, which is what it did.
            // Only treat null as "stopped" once we have actually seen it running.
            // Not while the stop sheet is up. The service clears the recording
            // state almost immediately after stop(), so finishing on that
            // signal would tear the sheet off the screen before it could be
            // answered.
            if (sawRecording && !isFinishing && !asking) finish()
            return
        }
        sawRecording = true
        paused = r.paused
        b.pause.setImageResource(if (paused) R.drawable.ic_mic else R.drawable.ic_pause)
        b.pause.contentDescription =
            getString(if (paused) R.string.resume_recording else R.string.pause_recording)
        b.mark.isEnabled = !paused
        b.mark.alpha = if (paused) 0.35f else 1f
        if (paused) {
            b.statusText.setText(R.string.paused_label)
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
        lastElapsedMs = r.elapsedMs
        lastElapsedAt = System.currentTimeMillis()
        b.meter.push(r.level)

        if (b.recMeta.text.isNullOrBlank()) {
            b.recMeta.text = getString(R.string.rec_meta, clockFmt.format(Date(r.startedAt)))
        }

        b.levelHint.text = when {
            r.isWorryinglyQuiet -> getString(R.string.level_none, (r.silentMs / 1000).toInt())
            r.level < Recording.SILENCE_LEVEL -> getString(R.string.level_quiet)
            else -> getString(R.string.level_ok)
        }

        if (r.marks != lastMarks) {
            // Announce only a mark made while watching, not the count found on
            // reopening this screen mid-meeting.
            val announce = sawState && r.marks > lastMarks
            lastMarks = r.marks
            b.markCount.visibility = if (r.marks > 0) View.VISIBLE else View.GONE
            b.markCount.text = resources.getQuantityString(R.plurals.marks_count, r.marks, r.marks)
            if (announce) {
                Snackbar.make(b.root, b.markCount.text, Snackbar.LENGTH_SHORT)
                    .setAnchorView(b.stop).show()
            }
        }
        sawState = true

        // A dismissed warning comes back only after the mic has heard
        // something, so a room that stays silent is not nagged every buffer.
        if (!r.isWorryinglyQuiet && r.silentMs == 0L) quietDismissed = false

        // The whole screen changes colour when it stops hearing anything: the
        // failure that actually costs you a meeting is a mic you never notice
        // was covered, so it is worth more than a line of grey text.
        setQuiet(r.isWorryinglyQuiet && !quietDismissed, r.silentMs)
    }

    private fun setQuiet(quiet: Boolean, silentMs: Long) {
        if (quiet == lastQuiet) return
        lastQuiet = quiet
        if (quiet) {
            b.statusChip.setBackgroundResource(R.drawable.chip_warn)
            b.micDot.visibility = View.GONE
            b.statusIcon.visibility = View.VISIBLE
            b.statusText.text = getString(R.string.silent_for, (silentMs / 1000).toInt())
            b.statusText.setTextColor(color(R.color.s_on_warn_container))
            b.infoCard.visibility = View.VISIBLE
            b.infoCard.setBackgroundResource(R.drawable.surface_warn)
            b.infoIcon.setImageResource(R.drawable.ic_mic_slash)
            b.infoIcon.setColorFilter(color(R.color.s_warn))
            b.infoTitle.setText(R.string.silent_title)
            b.infoTitle.setTextColor(color(R.color.s_on_warn_container))
            b.infoBody.setText(R.string.silent_body)
            b.infoBody.setTextColor(color(R.color.s_warn_body))
            b.infoDismiss.visibility = View.VISIBLE
        } else {
            b.statusChip.setBackgroundResource(R.drawable.chip_mic)
            b.micDot.visibility = View.VISIBLE
            b.statusIcon.visibility = View.GONE
            if (!paused) {
                b.statusText.setText(R.string.mic_live)
                b.statusText.setTextColor(color(R.color.s_on_mic_container))
            }
            b.infoCard.visibility = if (hasLiveText) View.GONE else View.VISIBLE
            b.infoCard.setBackgroundResource(R.drawable.surface_card)
            b.infoIcon.setImageResource(R.drawable.ic_bell)
            b.infoIcon.setColorFilter(color(R.color.s_cyan))
            b.infoTitle.setText(R.string.lock_it)
            b.infoTitle.setTextColor(color(R.color.s_text))
            b.infoBody.setText(R.string.lock_it_body)
            b.infoBody.setTextColor(color(R.color.s_text_muted))
            b.infoDismiss.visibility = View.GONE
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

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
        b.mark.isEnabled = false
        b.pause.isEnabled = false
        b.statusText.setText(R.string.stopping_label)
        // Paint the last known length now. The ticker freezes from here, so
        // whatever is on screen when the sheet opens is what stays there.
        if (lastElapsedMs > 0) b.timer.text = RecorderService.formatElapsed(lastElapsedMs)
        // Before stopping: the service clears this as it shuts down.
        stoppedMeetingId = RecordingState.state.value?.meetingId ?: -1L
        RecorderService.stop(this)
        // Do not finish() here: wait for the service to actually clear the state,
        // so the user never sees the screen vanish on a stop that failed.
        askAfterStop()
    }

    /**
     * Asks for a name and, when speaker labels are on, a head-count — once,
     * right after stop.
     *
     * This is the one moment both answers are known and cheap to give: the
     * user just sat through the meeting and is still holding the phone. Asked
     * later, a name means scrolling back to find the meeting, and a count costs
     * a re-run of speaker separation and relies on the audio still being there.
     *
     * Everything is optional, so this never blocks. Skip, a swipe down or
     * walking away leave the title blank (the transcriber drafts one from the
     * first words) and expected_speakers at 0 (the clusterer guesses).
     * Transcription has already been handed to the service and does not wait.
     *
     * Why ask for a count at all, when the app could guess: measured across a
     * ten-sample matrix, guessing was wrong in both directions — a single voice
     * came back as six, five voices came back as three. Told the true count it
     * did better, though later measurement on a realistic ten-person meeting
     * showed it can ignore the count outright. See Settings.identifySpeakers.
     * The count is left off the sheet when speaker separation is off: asking
     * a question whose answer is never used implies the app is doing
     * something it is not.
     */
    private fun askAfterStop() {
        val id = stoppedMeetingId
        if (id < 0) return
        asking = true
        val sheet = BottomSheetDialog(this)
        val sb = SheetStopBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        val askCount = Settings(this).identifySpeakers
        sb.speakersGroup.visibility = if (askCount) View.VISIBLE else View.GONE
        if (askCount) {
            for (n in 1..8) {
                sb.speakerChips.addView(Chip(this).apply {
                    text = n.toString()
                    tag = n
                    isCheckable = true
                    contentDescription = resources.getQuantityString(R.plurals.people_count, n, n)
                })
            }
            sb.speakerChips.addView(Chip(this).apply {
                setText(R.string.not_sure)
                tag = 0
                isCheckable = true
            })
        }

        // applicationContext: this activity finishes the moment the sheet
        // closes, and the writes must outlive it.
        val app = applicationContext
        fun save() {
            val title = sb.title.text?.toString()?.trim().orEmpty()
            val count = sb.speakerChips.checkedChipId.takeIf { it != View.NO_ID }
                ?.let { sb.speakerChips.findViewById<Chip>(it).tag as Int } ?: 0
            if (title.isNotEmpty() || count > 0) {
                Thread {
                    runCatching {
                        val repo = Repo(app)
                        if (title.isNotEmpty()) repo.rename(id, title)
                        if (count > 0) repo.setExpectedSpeakers(id, count)
                    }
                }.start()
            }
            sheet.dismiss()
        }
        sb.save.setOnClickListener { save() }
        sb.skip.setOnClickListener { sheet.dismiss() }
        sb.title.setOnEditorActionListener { _, _, _ -> save(); true }
        // Every exit runs through here — Save, Skip, a swipe or a tap outside —
        // so the screen closes exactly once however the sheet is ended.
        sheet.setOnDismissListener {
            asking = false
            if (!isFinishing) finish()
        }
        sheet.show()
    }

    override fun onDestroy() {
        ticker?.let { b.timer.removeCallbacks(it) }
        blink?.cancel()
        // The sheet's dismiss listener calls finish(); if the activity is
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
