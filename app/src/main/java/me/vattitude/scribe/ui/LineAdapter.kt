package me.vattitude.scribe.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.vattitude.scribe.R
import me.vattitude.scribe.databinding.ItemLineBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line

class LineAdapter(
    /** Tapping a speaker label asks who it is. Naming is the user's act. */
    private val onSpeakerClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<LineAdapter.VH>() {

    private var items: List<Line> = emptyList()
    private var names: Map<Int, String> = emptyMap()

    fun submit(list: List<Line>, speakerNames: Map<Int, String> = names) {
        items = list
        names = speakerNames
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val l = items[position]
        // A timestamp on every line turns prose into a log. Show one when the
        // clock has moved on meaningfully, and leave the gutter blank otherwise,
        // so the transcript reads as paragraphs that you can still scrub through.
        val prev = if (position > 0) items[position - 1] else null
        val showTime = prev == null || l.tStartMs - prev.tStartMs >= TIME_GAP_MS
        holder.b.time.text = if (showTime) Exporters.timestamp(l.tStartMs) else ""
        holder.b.text.text = l.text

        val label = Exporters.label(l.speaker, names)
        val changed = label != null && (prev == null || prev.speaker != l.speaker)
        holder.b.speaker.visibility = if (changed) View.VISIBLE else View.GONE
        if (changed) {
            holder.b.speaker.text = label
            holder.b.speaker.setTextColor(
                ContextCompat.getColor(holder.b.root.context, COLORS[l.speaker % COLORS.size])
            )
            holder.b.speaker.setOnClickListener { onSpeakerClick?.invoke(l.speaker) }
        }
    }

    private companion object {
        /** Show a new timestamp at most this often. */
        const val TIME_GAP_MS = 30_000L

        val COLORS = intArrayOf(
            R.color.s_spk_0, R.color.s_spk_1, R.color.s_spk_2,
            R.color.s_spk_3, R.color.s_spk_4, R.color.s_spk_5
        )
    }

    class VH(val b: ItemLineBinding) : RecyclerView.ViewHolder(b.root)
}
