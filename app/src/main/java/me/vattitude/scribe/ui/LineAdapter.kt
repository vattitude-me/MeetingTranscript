package me.vattitude.scribe.ui

import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.vattitude.scribe.R
import me.vattitude.scribe.databinding.ItemLineBinding
import me.vattitude.scribe.databinding.ItemMarkBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Mark

/** A row of the transcript: a spoken line, or a moment marked while recording. */
sealed class TranscriptItem {
    abstract val tMs: Long

    data class Spoken(val line: Line) : TranscriptItem() {
        override val tMs get() = line.tStartMs
    }

    data class Marked(val mark: Mark) : TranscriptItem() {
        override val tMs get() = mark.tMs
    }

    companion object {
        /**
         * Lines in order, each mark placed before the first line that starts
         * at or after it — which is where the reader was when they tapped Mark.
         */
        fun merge(lines: List<Line>, marks: List<Mark>): List<TranscriptItem> {
            if (marks.isEmpty()) return lines.map(::Spoken)
            val out = ArrayList<TranscriptItem>(lines.size + marks.size)
            val pending = marks.sortedBy { it.tMs }.toMutableList()
            for (l in lines) {
                while (pending.isNotEmpty() && pending.first().tMs <= l.tStartMs) {
                    out += Marked(pending.removeAt(0))
                }
                out += Spoken(l)
            }
            pending.forEach { out += Marked(it) }
            return out
        }
    }
}

class LineAdapter(
    /** Tapping a speaker label asks who it is. Naming is the user's act. */
    private val onSpeakerClick: (Int) -> Unit,
    /** Tapping a line offers Copy, Share and Play from here. */
    private val onLineClick: (View, Line) -> Unit,
    private val onMarkClick: (View, Mark) -> Unit
) : ListAdapter<TranscriptItem, RecyclerView.ViewHolder>(Diff) {

    private var names: Map<Int, String> = emptyMap()
    private var query: String = ""
    /** Adapter position and occurrence of the find bar's current match, or -1. */
    private var currentPos = -1
    private var currentOccurrence = -1

    fun submit(items: List<TranscriptItem>, speakerNames: Map<Int, String>) {
        names = speakerNames
        // Rebind everything after the diff: whether a row shows its time and
        // speaker depends on the row above it, which the diff cannot see, so
        // a relabelled line would otherwise leave its neighbours stale.
        submitList(items) { notifyItemRangeChanged(0, itemCount) }
    }

    /** Highlights every match of [q], and [pos]/[occurrence] as the current one. */
    fun setFind(q: String, pos: Int, occurrence: Int) {
        val old = currentPos
        val queryChanged = q != query
        query = q
        currentPos = pos
        currentOccurrence = occurrence
        if (queryChanged) notifyItemRangeChanged(0, itemCount)
        else {
            if (old >= 0) notifyItemChanged(old)
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is TranscriptItem.Marked) TYPE_MARK else TYPE_LINE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MARK) MarkVH(ItemMarkBinding.inflate(inflater, parent, false))
        else LineVH(ItemLineBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TranscriptItem.Marked -> bindMark(holder as MarkVH, item.mark)
            is TranscriptItem.Spoken -> bindLine(holder as LineVH, item.line, position)
        }
    }

    private fun bindMark(holder: MarkVH, mark: Mark) {
        val ctx = holder.b.root.context
        holder.b.markText.text = ctx.getString(R.string.mark_line, Exporters.timestamp(mark.tMs))
        holder.b.markRow.setOnClickListener { onMarkClick(it, mark) }
    }

    private fun bindLine(holder: LineVH, l: Line, position: Int) {
        val b = holder.b
        val ctx = b.root.context
        val prev = previousLine(position)
        // A timestamp on every line turns prose into a log. Show one when the
        // clock has moved on meaningfully, and leave the gutter blank otherwise,
        // so the transcript reads as paragraphs that you can still scrub through.
        val showTime = prev == null || l.tStartMs - prev.tStartMs >= TIME_GAP_MS
        b.time.text = if (showTime) Exporters.timestamp(l.tStartMs) else ""
        b.text.text = highlighted(l.text, position, ctx)

        val label = Exporters.label(l.speaker, names)
        val changed = label != null && (prev == null || prev.speaker != l.speaker)
        b.speaker.visibility = if (changed) View.VISIBLE else View.GONE
        if (changed) {
            val color = ContextCompat.getColor(ctx, COLORS[l.speaker % COLORS.size])
            b.speaker.text = label
            b.speaker.setTextColor(color)
            // The pill is the speaker's colour at low strength, so the name
            // reads as a tappable tag and keeps its hue.
            b.speaker.backgroundTintList = ColorStateList.valueOf(color and 0x00FFFFFF or 0x2E000000)
            b.speaker.contentDescription = ctx.getString(R.string.name_speaker_desc, label)
            b.speaker.setOnClickListener { onSpeakerClick(l.speaker) }
        }
        b.lineRow.setOnClickListener { onLineClick(it, l) }
    }

    private fun highlighted(text: String, position: Int, ctx: android.content.Context): CharSequence {
        if (query.isBlank()) return text
        val at = Highlight.matches(text, query)
        if (at.isEmpty()) return text
        val s = SpannableString(text)
        val len = query.trim().length
        at.forEachIndexed { n, i ->
            val current = position == currentPos && n == currentOccurrence
            val bg = ContextCompat.getColor(ctx, if (current) R.color.s_cyan else R.color.s_cyan_container)
            val fg = ContextCompat.getColor(ctx, if (current) R.color.s_on_cyan else R.color.s_on_cyan_container)
            s.setSpan(BackgroundColorSpan(bg), i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(fg), i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    private fun previousLine(position: Int): Line? {
        var p = position - 1
        while (p >= 0) {
            val item = getItem(p)
            if (item is TranscriptItem.Spoken) return item.line
            p--
        }
        return null
    }

    class LineVH(val b: ItemLineBinding) : RecyclerView.ViewHolder(b.root)
    class MarkVH(val b: ItemMarkBinding) : RecyclerView.ViewHolder(b.root)

    private object Diff : DiffUtil.ItemCallback<TranscriptItem>() {
        override fun areItemsTheSame(a: TranscriptItem, b: TranscriptItem) = when {
            a is TranscriptItem.Spoken && b is TranscriptItem.Spoken -> a.line.id == b.line.id
            a is TranscriptItem.Marked && b is TranscriptItem.Marked -> a.mark.id == b.mark.id
            else -> false
        }
        override fun areContentsTheSame(a: TranscriptItem, b: TranscriptItem) = a == b
    }

    companion object {
        private const val TYPE_LINE = 0
        private const val TYPE_MARK = 1

        /** Show a new timestamp at most this often. */
        const val TIME_GAP_MS = 30_000L

        val COLORS = intArrayOf(
            R.color.s_spk_0, R.color.s_spk_1, R.color.s_spk_2,
            R.color.s_spk_3, R.color.s_spk_4, R.color.s_spk_5
        )
    }
}
