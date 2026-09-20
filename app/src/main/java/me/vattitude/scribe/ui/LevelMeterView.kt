package me.vattitude.scribe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import me.vattitude.scribe.R
import kotlin.math.log10
import kotlin.math.max

/**
 * A scrolling bar-per-buffer level meter — the answer to "is this thing actually
 * hearing the room?" from across a desk.
 *
 * It shows history rather than a single bouncing bar on purpose: a bar that is
 * moving tells you the mic works right now, but a strip that has been flat for
 * twenty seconds tells you something is wrong, which is the failure that
 * actually costs you a meeting.
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bars = FloatArray(CAPACITY)
    private var head = 0
    private var filled = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val quietPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = context.resources.displayMetrics.density

    init {
        // Magenta is the app's one signal for "the microphone is live". A bar
        // that drops to the outline grey is saying the opposite, so the two
        // colours have to stay distinguishable at a glance from a desk away.
        barPaint.color = ContextCompat.getColor(context, R.color.s_mic)
        quietPaint.color = ContextCompat.getColor(context, R.color.s_outline)
    }

    /** @param level peak amplitude 0..1 from the capture loop. */
    fun push(level: Float) {
        bars[head] = normalise(level)
        head = (head + 1) % CAPACITY
        if (filled < CAPACITY) filled++
        invalidate()
    }

    fun clear() {
        head = 0
        filled = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (filled == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        // Fixed 4dp bars on a 7dp pitch, as drawn in the design, rather than a
        // width derived from the view: the bars should look identical whatever
        // surface they sit on.
        val slot = BAR_W_DP * density + GAP_DP * density
        val barW = BAR_W_DP * density
        val radius = barW / 2f
        val visible = (w / slot).toInt().coerceAtLeast(1)

        // Draw right-to-left from newest, so the meter scrolls like a tape.
        for (i in 0 until minOf(filled, visible)) {
            val v = bars[(head - 1 - i + CAPACITY * 2) % CAPACITY]
            val cx = w - (i + 0.5f) * slot
            // Always draw something: a dead-flat strip is itself the signal, and
            // an empty canvas reads as "broken" rather than "silent".
            val barH = max(2f, v * h)
            rect.set(cx - barW / 2f, (h - barH) / 2f, cx + barW / 2f, (h + barH) / 2f)
            canvas.drawRoundRect(rect, radius, radius, if (v < QUIET) quietPaint else barPaint)
        }
    }

    private companion object {
        const val CAPACITY = 256
        const val BAR_W_DP = 4f
        const val GAP_DP = 3f
        /** Below this normalised level, a bar is drawn dimmed as "basically silence". */
        const val QUIET = 0.08f

        /**
         * Linear amplitude is useless to look at: normal speech sits near the
         * bottom of the range and the meter looks dead. Map to dB across a
         * 60 dB window, which is roughly how loud things feel.
         */
        fun normalise(level: Float): Float {
            if (level <= 0.0001f) return 0f
            val db = 20f * log10(level)          // -80..0
            return ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
    }
}
