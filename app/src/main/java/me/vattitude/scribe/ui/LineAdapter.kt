package me.vattitude.scribe.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.vattitude.scribe.databinding.ItemLineBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line

class LineAdapter(
    private val onShare: (Line) -> Unit
) : RecyclerView.Adapter<LineAdapter.VH>() {

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
        holder.b.time.text = Exporters.timestamp(l.tStartMs)
        holder.b.text.text = l.text
        holder.b.share.setOnClickListener { onShare(l) }
    }

    class VH(val b: ItemLineBinding) : RecyclerView.ViewHolder(b.root)
}
