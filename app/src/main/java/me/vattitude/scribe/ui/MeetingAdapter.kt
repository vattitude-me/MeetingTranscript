package me.vattitude.scribe.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.vattitude.scribe.R
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
    private var snippets: Map<Long, String> = emptyMap()
    private val dateFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

    fun submit(list: List<Meeting>, snips: Map<Long, String> = emptyMap()) {
        items = list
        snippets = snips
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemMeetingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val b = holder.b
        val ctx = b.root.context
        val recording = m.state == MeetingState.RECORDING

        // Title, or the italic placeholder plus a pencil so it reads as editable
        // rather than as a meeting that failed to get a name.
        if (m.title.isBlank()) {
            b.title.text = ctx.getString(R.string.untitled)
            b.title.setTypeface(null, Typeface.ITALIC)
            b.title.setTextColor(ContextCompat.getColor(ctx, R.color.s_text_muted))
            b.renameHint.visibility = View.VISIBLE
        } else {
            b.title.text = m.title
            b.title.setTypeface(null, Typeface.NORMAL)
            b.title.setTextColor(ContextCompat.getColor(ctx, R.color.s_text))
            b.renameHint.visibility = View.GONE
        }

        b.meta.text = buildString {
            append(dateFmt.format(Date(m.startedAt)))
            if (m.durationMs > 0) append(" · ").append(Exporters.timestamp(m.durationMs))
        }

        b.rowIcon.setImageResource(if (recording) R.drawable.ic_waveform else R.drawable.ic_file_text)
        b.rowIcon.setColorFilter(
            ContextCompat.getColor(ctx, if (recording) R.color.s_mic else R.color.s_text_dim)
        )

        // Exactly one of chip / progress / snippet, so a row never stacks two
        // competing status lines.
        b.chip.visibility = View.GONE
        b.progressRow.visibility = View.GONE
        b.snippet.visibility = View.GONE

        when {
            m.error != null -> chip(
                holder, m.error, R.drawable.chip_error, R.color.s_on_error_container,
                icon = R.drawable.ic_warning, dot = false
            )
            recording -> chip(
                holder, ctx.getString(R.string.recording).uppercase(Locale.getDefault()),
                R.drawable.chip_mic, R.color.s_on_mic_container, icon = null, dot = true
            )
            m.state == MeetingState.TRANSCRIBING -> {
                b.progressRow.visibility = View.VISIBLE
                val pct = if (m.segmentCount > 0) m.segmentsDone * 100 / m.segmentCount else 0
                b.rowProgress.setProgressCompat(pct, true)
                b.progressText.text = if (m.segmentCount > 0)
                    "Transcribing ${m.segmentsDone}/${m.segmentCount}" else "Transcribing"
            }
            m.state == MeetingState.RECORDED -> chip(
                holder, "Queued", R.drawable.chip_outline, R.color.s_text_dim,
                icon = R.drawable.ic_clock, dot = false
            )
            m.state == MeetingState.DONE -> {
                val snip = snippets[m.id]
                if (!snip.isNullOrBlank()) {
                    b.snippet.visibility = View.VISIBLE
                    b.snippet.text = snip
                }
            }
            else -> chip(
                holder, "Failed", R.drawable.chip_error, R.color.s_on_error_container,
                icon = R.drawable.ic_warning, dot = false
            )
        }

        holder.itemView.setOnClickListener { onOpen(m) }
        holder.itemView.setOnLongClickListener { onLongPress(m); true }
    }

    private fun chip(holder: VH, text: String, bg: Int, fg: Int, icon: Int?, dot: Boolean) {
        val b = holder.b
        val ctx = b.root.context
        b.chip.visibility = View.VISIBLE
        b.chip.setBackgroundResource(bg)
        b.chipText.text = text
        b.chipText.setTextColor(ContextCompat.getColor(ctx, fg))
        b.chipDot.visibility = if (dot) View.VISIBLE else View.GONE
        if (icon != null) {
            b.chipIcon.visibility = View.VISIBLE
            b.chipIcon.setImageResource(icon)
            b.chipIcon.setColorFilter(ContextCompat.getColor(ctx, fg))
        } else {
            b.chipIcon.visibility = View.GONE
        }
    }

    class VH(val b: ItemMeetingBinding) : RecyclerView.ViewHolder(b.root)
}
