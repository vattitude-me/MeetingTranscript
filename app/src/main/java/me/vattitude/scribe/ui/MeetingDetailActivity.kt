package me.vattitude.scribe.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.R
import me.vattitude.scribe.asr.Dynamics
import me.vattitude.scribe.asr.Diarizer
import me.vattitude.scribe.asr.ReDiarizeWorker
import me.vattitude.scribe.databinding.ActivityDetailBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private lateinit var repo: Repo
    private lateinit var adapter: LineAdapter
    private var meeting: Meeting? = null
    private var lines: List<Line> = emptyList()
    private var names: Map<Int, String> = emptyMap()

    /** Whether the original audio survives, so voices can be separated again. */
    private var hasAudio = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = Repo(this)

        adapter = LineAdapter { speaker -> promptName(speaker) }
        b.lines.layoutManager = LinearLayoutManager(this)
        b.lines.adapter = adapter
        b.rename.setOnClickListener { meeting?.let { m -> promptRename(m) } }
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Triple(repo.meeting(id), repo.lines(id), repo.speakerNames(id))
            }
            // Re-separating voices reads the original audio, which is gone if
            // the user asked for recordings to be deleted after transcription.
            hasAudio = withContext(Dispatchers.IO) {
                loaded.first?.let { Diarizer.segmentsOf(it.segmentDir).isNotEmpty() } ?: false
            }
            val (m, ls) = loaded.first to loaded.second
            if (m == null) { finish(); return@launch }
            meeting = m
            lines = ls
            names = loaded.third
            // The headline carries the name; the toolbar stays bare so the title
            // is not printed twice at two sizes.
            title = ""
            b.headline.text = m.title.ifBlank { getString(R.string.untitled) }
            adapter.submit(ls, names)

            val meta = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
                .format(Date(m.startedAt))
            val dur = if (m.durationMs > 0) " · " + Exporters.timestamp(m.durationMs) else ""
            val state = when (m.state) {
                MeetingState.DONE -> {
                    val voices = ls.map { it.speaker }.filter { it >= 0 }.distinct().size
                    buildString {
                        append(" · ${ls.size} lines")
                        if (voices > 1) {
                            append(" · $voices voices")
                            if (hasAudio) append(" · tap to fix")
                        }
                    }
                }
                MeetingState.TRANSCRIBING -> " · transcribing ${m.segmentsDone}/${m.segmentCount}"
                MeetingState.RECORDED -> " · " + (m.error ?: "queued")
                MeetingState.RECORDING -> " · recording"
                else -> " · " + (m.error ?: "failed")
            }
            b.status.text = meta + dur + state

            // The voice count is the one number on this screen the user can see
            // is wrong, so make it the thing they can tap to correct. A menu
            // item alone would not be found by the person who needs it.
            val fixable = m.state == MeetingState.DONE && ls.any { it.speaker >= 0 } && hasAudio
            b.status.isClickable = fixable
            b.status.setOnClickListener(if (fixable) View.OnClickListener { promptSpeakerCount(m) } else null)

            b.empty.visibility = if (ls.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val m = meeting ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_rename -> { promptRename(m); true }
            R.id.action_share_text -> {
                if (lines.isEmpty()) noTranscript() else
                    startActivity(Exporters.shareFile(this, m, Exporters.plainText(m, lines, names), "txt"))
                true
            }
            R.id.action_prompt -> {
                if (lines.isEmpty()) noTranscript() else
                    startActivity(Exporters.shareText(Exporters.promptReady(m, lines, names)))
                true
            }
            R.id.action_speakers -> {
                if (lines.none { it.speaker >= 0 }) {
                    Snackbar.make(b.root, "This meeting has no speaker labels", Snackbar.LENGTH_SHORT).show()
                } else showDynamics()
                true
            }
            R.id.action_speaker_count -> { promptSpeakerCount(m); true }
            R.id.action_export_as -> {
                if (lines.isEmpty()) noTranscript() else chooseFormat(m)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * One chooser rather than a menu item per format. The order is by how often
     * they get used, and each label says what the file is for rather than only
     * naming the extension.
     */
    private fun chooseFormat(m: me.vattitude.scribe.store.Meeting) {
        val formats = listOf<Triple<String, String, (List<me.vattitude.scribe.store.Line>) -> String>>(
            Triple("Plain text (.txt)", "txt") { Exporters.plainText(m, it, names) },
            Triple("Markdown (.md)", "md") { Exporters.markdown(m, it, names) },
            Triple("For a summarizer (.json)", "json") { Exporters.json(m, it, names) },
            Triple("Subtitles (.srt)", "srt") { Exporters.srt(it, names) },
            Triple("Subtitles (.vtt)", "vtt") { Exporters.vtt(it, names) }
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Export as")
            .setItems(formats.map { it.first }.toTypedArray()) { _, i ->
                val (_, ext, build) = formats[i]
                startActivity(Exporters.shareFile(this, m, build(lines), ext))
            }
            .show()
    }

    /**
     * Meetings are auto-titled with a timestamp, which is enough to record one and
     * useless for finding it again a week later.
     */
    private fun promptRename(m: Meeting) {
        val input = android.widget.EditText(this).apply {
            setText(m.title)
            hint = getString(R.string.untitled)
            setSelection(text.length)
            setSingleLine()
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename meeting")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.rename(m.id, name) }
                    b.headline.text = name
                    meeting = m.copy(title = name)
                }
            }
            .show()
    }

    /**
     * Who talked, how much, and who cut in. Read-only and derived \u2014 there is
     * nothing to configure here, so a dialog is the right weight for it.
     *
     * The footer says what the numbers are not. Talk time is not contribution,
     * and a tool that reports one while implying the other is the kind of
     * meeting analytics people are right to distrust.
     */
    private fun showDynamics() {
        val summary = Dynamics.of(lines)
        val body = buildString {
            for (share in summary.shares) {
                appendLine(Exporters.label(share.speaker, names) ?: "Speaker")
                appendLine("    " + Dynamics.describe(share))
                appendLine("    longest stretch " + Exporters.timestamp(share.longestTurnMs))
                appendLine()
            }
            append("Counted from voice separation alone. Talk time is not the ")
            append("same as contribution, and speaker separation can be wrong \u2014 ")
            append("check anything surprising against the transcript.")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Conversation")
            .setMessage(body)
            .setPositiveButton("Done", null)
            .show()
    }

    /**
     * Diarization finds distinct voices; it has no idea whose they are. Naming
     * is the user's act, done once and applied to every line that voice spoke.
     */
    private fun promptName(speaker: Int) {
        val m = meeting ?: return
        val current = names[speaker].orEmpty()
        val input = android.widget.EditText(this).apply {
            setText(current)
            hint = "Speaker ${speaker + 1}"
            setSelection(text.length)
            setSingleLine()
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Who is this?")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.nameSpeaker(m.id, speaker, name) }
                    names = names + (speaker to name)
                    adapter.submit(lines, names)
                }
            }
            .show()
    }

    /**
     * Asks how many people actually spoke, then separates the voices again.
     *
     * Guessing the count from the audio alone is the weakest part of this app.
     * A threshold decides how different two stretches of speech must be before
     * they count as two people, and no single value works: swept across one-,
     * two- and three-speaker recordings, none produced the right number for all
     * of them. One person talking for nine minutes drifts enough to be split
     * into six. Told the count outright, the clusterer got every sample right.
     *
     * So this asks. The user was in the meeting and knows the answer, and the
     * audio is still on disk, so applying it costs one diarization pass rather
     * than a whole re-transcription.
     */
    private fun promptSpeakerCount(m: Meeting) {
        if (lines.isEmpty()) { noTranscript(); return }
        if (!hasAudio) {
            // Nothing to re-read. Say why rather than failing quietly, since the
            // cause is a setting the user chose and can change for next time.
            Snackbar.make(
                b.root,
                "The recording was deleted after transcribing, so voices cannot be separated again",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        // 1..8 covers the meetings this app is for. Past that the labels are
        // noise anyway, and "let the app decide" stays available for the case
        // where the user genuinely does not know.
        val options = (1..8).map { if (it == 1) "1 person (just me)" else "$it people" } +
            "Let the app decide"
        val current = m.expectedSpeakers
        val checked = if (current in 1..8) current - 1 else options.lastIndex
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("How many people spoke?")
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val count = if (which == options.lastIndex) 0 else which + 1
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.setExpectedSpeakers(m.id, count) }
                    meeting = m.copy(expectedSpeakers = count)
                    ReDiarizeWorker.enqueue(this@MeetingDetailActivity, m.id)
                    Snackbar.make(
                        b.root,
                        "Separating voices again — this takes a minute or two",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun noTranscript() {
        Snackbar.make(b.root, "Nothing to share yet", Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ID = "meeting_id"
    }
}
