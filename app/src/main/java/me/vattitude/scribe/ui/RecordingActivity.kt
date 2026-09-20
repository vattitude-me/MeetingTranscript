package me.vattitude.scribe.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
    private var startedAt = 0L
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
    private fun startTicker() {
        ticker = object : Runnable {
            override fun run() {
                val anchor = startedAt
                if (anchor > 0) {
                    b.timer.text =
                        RecorderService.formatElapsed(System.currentTimeMillis() - anchor)
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
            if (sawRecording && !isFinishing) finish()
            return
        }
        sawRecording = true
        // Note the anchor and let the ticker render it. Painting the timer from
        // here would inherit the capture loop's ~2s cadence and visibly stutter.
        startedAt = r.startedAt
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
        RecorderService.stop(this)
        // Do not finish() here: wait for the service to actually clear the state,
        // so the user never sees the screen vanish on a stop that failed.
    }

    override fun onDestroy() {
        ticker?.let { b.timer.removeCallbacks(it) }
        blink?.cancel()
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
