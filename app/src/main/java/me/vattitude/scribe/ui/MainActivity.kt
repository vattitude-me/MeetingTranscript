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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.asr.TranscribeWorker
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityMainBinding
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: Repo
    private lateinit var adapter: MeetingAdapter
    private var downloading = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleRecording()
        else Snackbar.make(b.root, "Scribe needs the microphone to record", Snackbar.LENGTH_LONG).show()
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
                startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_ID, m.id)
                )
            },
            onLongPress = { m -> confirmDelete(m.id, m.title) }
        )
        b.meetings.layoutManager = LinearLayoutManager(this)
        b.meetings.adapter = adapter

        b.record.setOnClickListener { onRecordTapped() }
        b.modelAction.setOnClickListener { downloadModel() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            RecordingState.state.collect { renderRecordButton() }
        }

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
            val list = withContext(Dispatchers.IO) { repo.meetings() }
            adapter.submit(list)
            b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

            // A meeting left "recorded" was waiting on the model. Now that it's
            // here, pick it back up without making the user hunt for a button.
            if (ModelManager.isReady(this@MainActivity)) {
                list.filter { it.state == MeetingState.RECORDED }
                    .forEach { TranscribeWorker.enqueue(this@MainActivity, it.id) }
            }
        }
    }

    private fun renderRecordButton() {
        val rec = RecordingState.state.value
        b.record.text = if (rec != null) "Stop recording" else "Start recording"
        b.recordHint.text = when {
            rec != null -> "Recording · " + RecorderService.formatElapsed(rec.elapsedMs)
            else -> "Put the phone next to your laptop speaker."
        }
    }

    private fun renderModelCard() {
        val ready = ModelManager.isReady(this)
        b.modelCard.visibility = if (ready) View.GONE else View.VISIBLE
        if (!ready && !downloading) {
            b.modelStatus.text =
                "Speech model not downloaded (~600 MB, one time).\n" +
                    "You can record without it — transcription starts once it's here."
            b.modelAction.isEnabled = true
            b.modelAction.text = "Download model"
            b.modelProgress.visibility = View.GONE
        }
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        b.modelAction.isEnabled = false
        b.modelAction.text = "Downloading…"
        b.modelProgress.visibility = View.VISIBLE
        b.modelProgress.isIndeterminate = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModelManager.download(this@MainActivity) { done, total ->
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            runOnUiThread {
                                b.modelProgress.isIndeterminate = false
                                b.modelProgress.progress = pct
                                b.modelStatus.text = "Downloading speech model… $pct%"
                            }
                        }
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
        if (RecordingState.isRecording) RecorderService.stop(this) else RecorderService.start(this)
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
