package me.vattitude.scribe.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.vattitude.scribe.databinding.ItemLineBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line

class LineAdapter : RecyclerView.Adapter<LineAdapter.VH>() {

    private var items: List<Line> = emptyList()

    fun submit(list: List<Line>) {
        items = list
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
    }

    private companion object {
        /** Show a new timestamp at most this often. */
        const val TIME_GAP_MS = 30_000L
    }

    class VH(val b: ItemLineBinding) : RecyclerView.ViewHolder(b.root)
}
