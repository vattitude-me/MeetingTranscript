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

    init {
        barPaint.color = ContextCompat.getColor(context, R.color.scribe_primary)
        quietPaint.color = ContextCompat.getColor(context, R.color.scribe_primary)
        quietPaint.alpha = 60
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
        val slot = w / VISIBLE
        val barW = max(2f, slot * 0.55f)
        val radius = barW / 2f

        // Draw right-to-left from newest, so the meter scrolls like a tape.
        for (i in 0 until minOf(filled, VISIBLE)) {
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
        /** Bars visible at once — ~15s of meeting at the capture loop's rate. */
        const val VISIBLE = 96
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
