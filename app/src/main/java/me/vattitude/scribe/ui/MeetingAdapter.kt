package me.vattitude.scribe.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.vattitude.scribe.databinding.ItemMeetingBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.MeetingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingAdapter(
    private val onOpen: (Meeting) -> Unit,
    private val onLongPress: (Meeting) -> Unit
) : RecyclerView.Adapter<MeetingAdapter.VH>() {

    private var items: List<Meeting> = emptyList()
    private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    fun submit(list: List<Meeting>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemMeetingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.b.title.text = m.title
        holder.b.meta.text = buildString {
            append(dateFmt.format(Date(m.startedAt)))
            if (m.durationMs > 0) append("  ·  ").append(Exporters.timestamp(m.durationMs))
        }
        // An error explains the state better than the state name does, whichever
        // state we are in — that is the whole point of recording one.
        holder.b.status.text = m.error ?: when (m.state) {
            MeetingState.RECORDING -> "Recording"
            MeetingState.RECORDED -> "Queued \u2014 tap to transcribe now"
            // The live pass already left a rough transcript, so this second pass is
            // an improvement rather than a wait — say so, or "Transcribing 0/11"
            // reads as if nothing is there yet.
            MeetingState.TRANSCRIBING ->
                if (m.segmentCount > 0) "Improving transcript ${m.segmentsDone}/${m.segmentCount}"
                else "Transcribing"
            MeetingState.DONE -> "Transcript ready"
            else -> "Failed"
        }
        holder.itemView.setOnClickListener { onOpen(m) }
        holder.itemView.setOnLongClickListener { onLongPress(m); true }
    }

    class VH(val b: ItemMeetingBinding) : RecyclerView.ViewHolder(b.root)
}
