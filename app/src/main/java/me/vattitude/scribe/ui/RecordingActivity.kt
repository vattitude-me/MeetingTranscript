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
import me.vattitude.scribe.capture.LiveTranscript
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.Recording
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityRecordingBinding

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(b.root)

        // You put the phone down and watch it; letting it sleep would defeat that.
        // It costs nothing when the screen is off in a pocket, because then this
        // activity is not resumed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.stop.setOnClickListener { stop() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecordingState.state.collectLatest { render(it) } }
                launch {
                    LiveTranscript.state.collectLatest { v ->
                        val text = (v.lines + v.partial).filter { it.isNotBlank() }.joinToString(" ")
                        b.liveText.text = text.ifBlank {
                            "Listening… words appear here as people talk."
                        }
                        // Follow the tail like a terminal, so the newest words are
                        // the ones on screen without touching anything.
                        b.livePanel.post { b.livePanel.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }
            }
        }
        startBlink()
    }

    private fun render(r: Recording?) {
        if (r == null) {
            // The service stopped — from here, the notification, or the widget.
            if (!isFinishing) finish()
            return
        }
        b.timer.text = RecorderService.formatElapsed(r.elapsedMs)
        b.meter.push(r.level)
        b.levelHint.text = when {
            r.isWorryinglyQuiet ->
                "Hearing nothing for " + (r.silentMs / 1000) + "s — is the mic covered?"
            r.level < Recording.SILENCE_LEVEL -> "Quiet"
            else -> "Picking up audio"
        }
    }

    private fun startBlink() {
        blink = ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { b.recDot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stop() {
        if (stopping) return
        stopping = true
        b.stop.isEnabled = false
        b.stop.text = "Stopping…"
        RecorderService.stop(this)
        // Do not finish() here: wait for the service to actually clear the state,
        // so the user never sees the screen vanish on a stop that failed.
    }

    override fun onDestroy() {
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
