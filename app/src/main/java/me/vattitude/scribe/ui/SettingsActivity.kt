package me.vattitude.scribe.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.databinding.ActivitySettingsBinding
import me.vattitude.scribe.export.Backup
import me.vattitude.scribe.store.Repo
import me.vattitude.scribe.store.Settings

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var repo: Repo
    private lateinit var settings: Settings

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument(Backup.MIME)
    ) { uri -> uri?.let { runExport(it) } }

    private val pickBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { confirmImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = Repo(this)
        settings = Settings(this)

        setSupportActionBar(b.toolbar)
        // The headline below already says "Settings"; a toolbar title would only
        // repeat it or, worse, repeat the app name.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.exportRow.setOnClickListener { chooseExportScope() }
        b.importRow.setOnClickListener {
            pickBackup.launch(arrayOf(Backup.MIME, "application/octet-stream", "*/*"))
        }

        b.retentionSwitch.isChecked = settings.deleteAudioAfterTranscribe
        b.retentionRow.setOnClickListener {
            val next = !b.retentionSwitch.isChecked
            b.retentionSwitch.isChecked = next
            settings.deleteAudioAfterTranscribe = next
        }

        b.speakerRow.setOnClickListener { confirmSpeakerModels() }
        b.audioRow.setOnClickListener { confirmClearAudio() }
        b.clearRow.setOnClickListener { confirmClearEverything() }

        b.version.text = buildString {
            append("Version ").append(Backup.version(this@SettingsActivity))
            val model = ModelManager.Model.ACCURATE
            append("\nModel   ")
            append(if (ModelManager.isReady(this@SettingsActivity, model)) model.id else "not downloaded")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSizes()
        refreshSpeakerRow()
    }

    private var downloadingSpeakers = false

    private fun refreshSpeakerRow() {
        if (downloadingSpeakers) return
        val missing = ModelManager.missingSpeaker(this)
        b.speakerSub.text = if (missing.isEmpty()) {
            "On. New recordings are split by voice."
        } else {
            val mb = missing.sumOf { it.approxBytes } / 1_000_000
            "Waiting on a ${mb} MB download."
        }
    }

    /**
     * Speaker separation is not a toggle. It comes with the models and runs on
     * every recording, so this row explains what it does and what it does not
     * \u2014 the distinction between finding a voice and knowing a person is the
     * one thing a user must not be confused about.
     */
    private fun confirmSpeakerModels() {
        if (downloadingSpeakers) return
        val missing = ModelManager.missingSpeaker(this)
        if (missing.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Speaker separation is on")
                .setMessage(
                    "New recordings are split by voice after transcribing, and " +
                        "you can name each voice in the transcript.\n\n" +
                        "Meetings already transcribed are not relabelled \u2014 it needs " +
                        "the audio, which may already have been deleted."
                )
                .setPositiveButton("Done", null)
                .show()
            return
        }
        val mb = missing.sumOf { it.approxBytes } / 1_000_000
        AlertDialog.Builder(this)
            .setTitle("Finish the download?")
            .setMessage(
                "$mb MB is still missing, so recordings are not being split by " +
                    "voice yet. Everything else works.\n\n" +
                    "It runs offline like the rest. It finds voices, not people " +
                    "\u2014 nothing identifies anyone until you type a name."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Download") { _, _ -> downloadSpeakerModels(missing) }
            .show()
    }

    private fun downloadSpeakerModels(models: List<ModelManager.Model>) {
        downloadingSpeakers = true
        b.speakerSub.text = "Downloading\u2026"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val total = models.sumOf { it.approxBytes }
                    var before = 0L
                    for (m in models) {
                        ModelManager.download(this@SettingsActivity, m) { done, _ ->
                            val pct = ((before + done) * 100 / total.coerceAtLeast(1)).toInt()
                            runOnUiThread {
                                b.speakerSub.text = "Downloading\u2026 ${pct.coerceIn(0, 100)}%"
                            }
                        }
                        before += m.approxBytes
                    }
                }.isSuccess
            }
            downloadingSpeakers = false
            refreshSpeakerRow()
            Snackbar.make(
                b.root,
                if (ok) "Speaker separation is on" else "Download failed",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshSizes() {
        lifecycleScope.launch {
            val (audio, meetings) = withContext(Dispatchers.IO) {
                Settings.audioBytes(this@SettingsActivity) to repo.meetings().size
            }
            b.audioSub.text = if (audio == 0L) {
                "No recorded audio on this phone."
            } else {
                "${Settings.format(audio)} of audio. Transcripts stay."
            }
            b.exportSub.text = when (meetings) {
                0 -> "Nothing recorded yet."
                1 -> "1 meeting, as one file you keep."
                else -> "$meetings meetings, as one file you keep."
            }
        }
    }

    /**
     * Audio is the bulk of a backup by orders of magnitude — a transcripts-only
     * archive is kilobytes, the same archive with audio is hundreds of megabytes.
     * That is too big a difference to decide for someone.
     */
    private fun chooseExportScope() {
        lifecycleScope.launch {
            val audio = withContext(Dispatchers.IO) { Settings.audioBytes(this@SettingsActivity) }
            if (audio == 0L) {
                includeAudio = false
                createBackup.launch(Backup.suggestedName())
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Include the audio?")
                .setMessage(
                    "Transcripts on their own make a small file that restores everything you read.\n\n" +
                        "Adding the recordings makes it about ${Settings.format(audio)} larger, and lets a " +
                        "restored copy be transcribed again later."
                )
                .setNeutralButton("Cancel", null)
                .setNegativeButton("Transcripts only") { _, _ ->
                    includeAudio = false
                    createBackup.launch(Backup.suggestedName())
                }
                .setPositiveButton("Include audio") { _, _ ->
                    includeAudio = true
                    createBackup.launch(Backup.suggestedName())
                }
                .show()
        }
    }

    private var includeAudio = false

    private fun runExport(target: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Backup.write(this@SettingsActivity, target, includeAudio) }
            }
            result.onSuccess {
                Snackbar.make(
                    b.root,
                    "Saved ${it.meetings} ${if (it.meetings == 1) "meeting" else "meetings"} · ${Settings.format(it.bytes)}",
                    Snackbar.LENGTH_LONG
                ).setAction("Share") { shareBackup(target) }.show()
            }.onFailure {
                Snackbar.make(b.root, "Export failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun shareBackup(uri: Uri) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(Backup.MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share backup"
            )
        )
    }

    private fun confirmImport(uri: Uri) {
        lifecycleScope.launch {
            val peek = withContext(Dispatchers.IO) {
                runCatching { Backup.inspect(this@SettingsActivity, uri) }
            }
            peek.onFailure {
                Snackbar.make(b.root, it.message ?: "Could not read that file", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val s = peek.getOrThrow()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (s.meetings == 1) "Restore 1 meeting?" else "Restore ${s.meetings} meetings?")
                .setMessage(
                    (if (s.lines == 1) "1 transcript line" else "${s.lines} transcript lines") +
                        (if (s.hasAudio) ", with audio" else ", transcripts only") +
                        ".\n\nThese are added to what is already on this phone. Nothing is replaced."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ -> runImport(uri) }
                .show()
        }
    }

    private fun runImport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Backup.read(this@SettingsActivity, uri) }
            }
            result.onSuccess {
                refreshSizes()
                Snackbar.make(
                    b.root,
                    "Restored ${it.meetings} ${if (it.meetings == 1) "meeting" else "meetings"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }.onFailure {
                Snackbar.make(b.root, "Restore failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmClearAudio() {
        lifecycleScope.launch {
            val audio = withContext(Dispatchers.IO) { Settings.audioBytes(this@SettingsActivity) }
            if (audio == 0L) {
                Snackbar.make(b.root, "There is no audio to clear", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Clear ${Settings.format(audio)} of audio?")
                .setMessage(
                    "Transcripts are kept and stay readable. Meetings that have not " +
                        "been transcribed yet cannot be transcribed afterwards — their audio is the source."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear audio") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.deleteAllAudio() }
                        refreshSizes()
                        Snackbar.make(b.root, "Audio cleared", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    /**
     * The destructive one. It asks the user to type the word rather than tap
     * "OK", because the tap is muscle memory and this cannot be undone — there
     * is no server-side copy to fall back on, which is the whole point of the app.
     */
    private fun confirmClearEverything() {
        val input = EditText(this).apply {
            hint = "Type DELETE"
            setPadding(56, 32, 56, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Delete everything?")
            .setMessage(
                "Every meeting, transcript and recording is removed from this phone " +
                    "and cannot be recovered. Export a backup first if you want to keep any of it.\n\n" +
                    "The downloaded speech models are kept, so you will not need to download them again."
            )
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (input.text.toString().trim().equals("DELETE", ignoreCase = true)) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.deleteEverything() }
                        refreshSizes()
                        Snackbar.make(b.root, "Everything deleted", Snackbar.LENGTH_LONG).show()
                    }
                } else {
                    Snackbar.make(b.root, "Not deleted — the word did not match", Snackbar.LENGTH_LONG).show()
                }
            }
            .show()
    }
}
