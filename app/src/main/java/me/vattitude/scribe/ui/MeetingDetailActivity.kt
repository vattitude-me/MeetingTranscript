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
import me.vattitude.scribe.databinding.ActivityDetailBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo

class MeetingDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private lateinit var repo: Repo
    private lateinit var adapter: LineAdapter
    private var meeting: Meeting? = null
    private var lines: List<Line> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = Repo(this)

        adapter = LineAdapter()
        b.lines.layoutManager = LinearLayoutManager(this)
        b.lines.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        lifecycleScope.launch {
            val (m, ls) = withContext(Dispatchers.IO) { repo.meeting(id) to repo.lines(id) }
            if (m == null) { finish(); return@launch }
            meeting = m
            lines = ls
            title = m.title
            adapter.submit(ls)
            b.status.text = when (m.state) {
                MeetingState.DONE -> "${ls.size} lines · ${m.asrModelId ?: ""}"
                MeetingState.TRANSCRIBING -> "Transcribing ${m.segmentsDone}/${m.segmentCount}…"
                MeetingState.RECORDED -> m.error ?: "Waiting to transcribe"
                MeetingState.RECORDING -> "Recording in progress"
                else -> m.error ?: "Transcription failed"
            }
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
                    startActivity(Exporters.shareFile(this, m, Exporters.plainText(m, lines), "txt"))
                true
            }
            R.id.action_share_json -> {
                if (lines.isEmpty()) noTranscript() else
                    startActivity(Exporters.shareFile(this, m, Exporters.json(m, lines), "json"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Meetings are auto-titled with a timestamp, which is enough to record one and
     * useless for finding it again a week later.
     */
    private fun promptRename(m: Meeting) {
        val input = android.widget.EditText(this).apply {
            setText(m.title)
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
                    title = name
                    meeting = m.copy(title = name)
                }
            }
            .show()
    }

    private fun noTranscript() {
        Snackbar.make(b.root, "Nothing to share yet", Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ID = "meeting_id"
    }
}
