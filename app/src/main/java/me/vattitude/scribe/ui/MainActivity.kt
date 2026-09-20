package me.vattitude.scribe.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.R
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.asr.TranscribeWorker
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.LiveTranscript
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityMainBinding
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: Repo
    private lateinit var adapter: MeetingAdapter
    private var downloading = false
    private var query: String = ""

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleRecording()
        else Snackbar.make(b.root, "Recording needs access to the microphone", Snackbar.LENGTH_LONG).show()
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* purely informational — recording works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = Repo(this)

        adapter = MeetingAdapter(
            onOpen = { m ->
                if (m.state == MeetingState.RECORDED || m.state == MeetingState.FAILED) {
                    retry(m.id)
                } else {
                    startActivity(
                        Intent(this, MeetingDetailActivity::class.java)
                            .putExtra(MeetingDetailActivity.EXTRA_ID, m.id)
                    )
                }
            },
            onLongPress = { m -> confirmDelete(m.id, m.title) }
        )
        b.meetings.layoutManager = LinearLayoutManager(this)
        b.meetings.adapter = adapter

        b.record.setOnClickListener { onRecordTapped() }
        b.modelAction.setOnClickListener { downloadModel() }
        b.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            RecordingState.state.collect { renderRecordButton() }
        }

        // The list used to repaint only in onResume(), so a meeting that
        // finished transcribing while you were watching it sat there reading
        // "Queued — tap to transcribe now" until you left the screen and came
        // back. Follow the workers instead: every transcribe job is tagged, so
        // one observer covers all of them, however many are running.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosByTagFlow(TranscribeWorker.WORK_TAG)
                    .collect { infos -> onWorkChanged(infos) }
            }
        }

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                query = e?.toString().orEmpty()
                refresh()
            }
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
        })


        if (intent.getBooleanExtra(EXTRA_REQUEST_PERMISSION, false)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        renderModelCard()
        renderRecordButton()
        lifecycleScope.launch {
            val q = query
            val (list, snips) = withContext(Dispatchers.IO) {
                val l = if (q.isBlank()) repo.meetings() else repo.search(q)
                l to repo.snippets()
            }
            adapter.submit(list, snips)

            if (q.isBlank()) {
                b.emptyIcon.setImageResource(R.drawable.ic_waveform)
                b.empty.text = "No meetings yet"
                b.emptySub.text =
                    "Tap Record, or add the widget to your home screen to start with one tap."
            } else {
                b.emptyIcon.setImageResource(R.drawable.ic_search)
                b.empty.text = "Nothing matches \u201c" + q + "\u201d"
                b.emptySub.text = "Titles and transcript text only."
            }
            b.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            // The box is only useful once there is something to search, and it
            // otherwise just crowds an empty first-run screen.
            b.searchBox.visibility =
                if (list.isNotEmpty() || q.isNotBlank()) View.VISIBLE else View.GONE

            // Nothing is transcribing right now (we are the only thing that starts
            // it, and a live job would have moved the row on). So a row still stuck
            // on the load breadcrumb means the process died inside ONNX Runtime.
            withContext(Dispatchers.IO) {
                list.filter {
                    it.state == MeetingState.TRANSCRIBING &&
                        it.error == TranscribeWorker.BREADCRUMB_LOADING
                }.forEach {
                    repo.setState(it.id, MeetingState.FAILED, "Ran out of memory loading the model")
                }
            }

            // A meeting left "recorded" was waiting on the model. Now that it's
            // here, pick it back up without making the user hunt for a button.
            if (ModelManager.isReady(this@MainActivity, ModelManager.Model.ACCURATE)) {
                list.filter { it.state == MeetingState.RECORDED }
                    .forEach { TranscribeWorker.enqueue(this@MainActivity, it.id) }
            }
        }
    }

    /**
     * Repaint on every worker transition. Rows carry their progress in the
     * database, which the worker updates per segment, so re-reading the list is
     * enough — we do not need the progress out of [WorkInfo] itself.
     */
    private fun onWorkChanged(infos: List<WorkInfo>) {
        if (infos.isEmpty()) return
        refresh()
    }

    private fun renderRecordButton() {
        val rec = RecordingState.state.value
        val recording = rec != null
        // While a meeting is running this button is a way back to it, not a
        // second stop control — stopping lives on the recording screen, the
        // notification and the widget, which is already more than enough places.
        b.record.text = if (recording) "Back" else getString(R.string.start_recording)
        b.record.setIconResource(if (recording) R.drawable.ic_waveform else R.drawable.ic_mic)
    }

    private fun renderModelCard() {
        val missing = ModelManager.missing(this)
        b.modelCard.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        if (missing.isEmpty()) b.modelReassure.visibility = View.GONE
        if (missing.isNotEmpty() && !downloading) {
            val mb = missing.sumOf { it.approxBytes } / 1_000_000
            // Lead with what the app does and with the fact that you are not
            // blocked, because the honest headline — "615 MB before anything
            // works" — is wrong: recording works right now, and the audio is
            // kept, so a download started later loses nothing.
            b.modelStatus.text =
                "$mb MB, once. After this, transcription runs entirely on this " +
                    "device \u2014 no account, no upload, works in aeroplane mode."
            b.modelAction.isEnabled = true
            b.modelAction.text = getString(R.string.model_download)
            b.modelProgress.visibility = View.GONE
            b.modelPct.visibility = View.GONE
            b.modelReassure.visibility = View.VISIBLE
        }
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        b.modelAction.isEnabled = false
        b.modelAction.text = "Downloading\u2026"
        b.modelProgress.visibility = View.VISIBLE
        b.modelProgress.isIndeterminate = true

        // Two models, downloaded back to back. Progress is weighted by their
        // known sizes so the bar advances once through the pair rather than
        // snapping back to zero when the second one starts.
        val pending = ModelManager.missing(this)
        val grandTotal = pending.sumOf { it.approxBytes }.coerceAtLeast(1L)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var alreadyDone = 0L
                    for (model in pending) {
                        val label = if (model.streaming) "live model" else "accurate model"
                        ModelManager.download(this@MainActivity, model) { done, total ->
                            val pct = ((alreadyDone + done) * 100 / grandTotal)
                                .toInt().coerceIn(0, 100)
                            runOnUiThread {
                                b.modelProgress.isIndeterminate = false
                                b.modelProgress.setProgressCompat(pct, true)
                                b.modelPct.visibility = View.VISIBLE
                                b.modelPct.text = "$pct%"
                                b.modelStatus.text = "Downloading the $label"
                            }
                        }
                        alreadyDone += model.approxBytes
                    }
                }
            }
            downloading = false
            result.onFailure {
                b.modelStatus.text = "Download failed: ${it.message}"
                b.modelAction.isEnabled = true
                b.modelAction.text = "Retry"
                b.modelProgress.visibility = View.GONE
            }
            refresh()
        }
    }

    private fun onRecordTapped() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        toggleRecording()
    }

    private fun toggleRecording() {
        if (RecordingState.isRecording) {
            RecordingActivity.open(this)
        } else {
            LiveTranscript.reset()
            RecorderService.start(this)
            RecordingActivity.open(this)
        }
    }

    /** Explicit "do it now", so transcription never depends on the scheduler's mood. */
    private fun retry(id: Long) {
        if (!ModelManager.isReady(this, ModelManager.Model.ACCURATE)) {
            Snackbar.make(b.root, "Download the speech model first", Snackbar.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.setState(id, MeetingState.RECORDED)
                repo.setProgress(id, 0, null)
            }
            TranscribeWorker.enqueue(this@MainActivity, id)
            Snackbar.make(b.root, "Transcribing\u2026", Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun confirmDelete(id: Long, title: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$title\"?")
            .setMessage("The audio and the transcript are removed from this phone. This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.delete(id) }
                    refresh()
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_REQUEST_PERMISSION = "request_permission"
    }
}
