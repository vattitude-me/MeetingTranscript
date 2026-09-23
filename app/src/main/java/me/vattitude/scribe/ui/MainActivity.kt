package me.vattitude.scribe.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.R
import me.vattitude.scribe.asr.ModelDownloadWorker
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.asr.TranscribeWorker
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityMainBinding
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo
import me.vattitude.scribe.store.Settings
import me.vattitude.scribe.widget.ScribeWidget

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: Repo
    private lateinit var settings: Settings
    private lateinit var adapter: MeetingAdapter
    private var query: String = ""
    private var rows: List<MeetingRow> = emptyList()
    private var download: WorkInfo? = null
    private var ticker: Job? = null

    /**
     * Swiped away but not yet deleted. The row disappears at once and the
     * delete lands only when the Undo snackbar goes away, so a slip of the
     * thumb costs nothing.
     */
    private val pendingDelete = linkedSetOf<Long>()

    private lateinit var fabTint: ColorStateList
    private lateinit var fabInk: ColorStateList

    /**
     * Microphone and notifications in one prompt, asked on the first tap of
     * Record rather than at launch. Asked at launch, the notification prompt
     * arrived before the app had shown what it does, and a "no" there also
     * hid the recording notification — the one people stop recordings from.
     */
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startRecording()
        else micDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = Repo(this)
        settings = Settings(this)
        fabTint = b.record.backgroundTintList ?: ColorStateList.valueOf(getColor(R.color.s_cyan))
        fabInk = b.record.textColors

        adapter = MeetingAdapter(onOpen = ::open, onLongPress = ::showRowMenu)
        b.meetings.layoutManager = LinearLayoutManager(this)
        b.meetings.adapter = adapter
        swipeToDelete().attachToRecyclerView(b.meetings)

        b.record.setOnClickListener { onRecordTapped() }
        b.modelAction.setOnClickListener { startDownload() }
        b.modelMobile.setOnClickListener {
            ModelDownloadWorker.enqueue(this, ModelManager.missing(this), allowMobile = true)
        }
        b.modelWhat.setOnClickListener { explainDownload() }
        b.widgetAdd.setOnClickListener { addWidget() }
        b.widgetDismiss.setOnClickListener {
            settings.widgetTipDismissed = true
            b.widgetCard.visibility = View.GONE
        }
        b.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingState.state.collect { renderRecordButton() }
            }
        }

        // The list follows the workers rather than repainting only in
        // onResume(), so a meeting that finishes while you watch moves on by
        // itself. Every transcribe job is tagged, so one observer covers all.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosByTagFlow(TranscribeWorker.WORK_TAG)
                    .collect { if (it.isNotEmpty()) refresh() }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE)
                    .collect { infos ->
                        val before = download?.state
                        download = infos.firstOrNull()
                        renderModelCard()
                        // Finished: the list may have meetings waiting on it.
                        if (download?.state == WorkInfo.State.SUCCEEDED &&
                            before != WorkInfo.State.SUCCEEDED
                        ) refresh()
                    }
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

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_REQUEST_PERMISSION, false)) {
            onRecordTapped()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        // Leaving the screen commits any swipe still waiting on its Undo.
        commitPendingDeletes()
        super.onStop()
    }

    // ---- List -------------------------------------------------------------

    private fun refresh() {
        renderModelCard()
        renderRecordButton()
        lifecycleScope.launch {
            val q = query
            val live = RecordingState.state.value?.meetingId
            val (list, snips) = withContext(Dispatchers.IO) {
                recoverInterrupted(live)
                val l = if (q.isBlank()) repo.meetings() else repo.search(q)
                val s = if (q.isBlank()) repo.snippets() else repo.snippets() + repo.matchSnippets(q)
                l to s
            }
            val visible = list.filter { it.id !in pendingDelete }
            rows = visible.map { MeetingRow(it, snips[it.id], q.trim()) }
            adapter.submitList(rows)

            if (q.isBlank()) {
                b.emptyIcon.setImageResource(R.drawable.ic_waveform)
                b.empty.text = getString(R.string.empty_title)
                b.emptySub.text = getString(R.string.empty_body)
            } else {
                b.emptyIcon.setImageResource(R.drawable.ic_search)
                b.empty.text = getString(R.string.search_empty, q.trim())
                b.emptySub.text = getString(R.string.search_empty_body)
            }
            b.emptyBox.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            // The box is only useful once there is something to search, and it
            // otherwise just crowds an empty first-run screen.
            b.searchBox.visibility =
                if (list.isNotEmpty() || q.isNotBlank()) View.VISIBLE else View.GONE
            renderWidgetCard(list.isNotEmpty())

            // Nothing is transcribing right now (we are the only thing that starts
            // it, and a live job would have moved the row on). So a row still stuck
            // on the load breadcrumb means the process died inside ONNX Runtime.
            val running = withContext(Dispatchers.IO) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosByTag(TranscribeWorker.WORK_TAG).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }
            if (!running) withContext(Dispatchers.IO) {
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
     * A meeting still marked "recording" with no recorder behind it was cut off
     * — the process was killed or the phone died mid-meeting. The segments on
     * disk are intact up to the last flush, so close it off as recorded and
     * let it be transcribed, instead of leaving a row that says RECORDING
     * forever and cannot be opened, deleted or transcribed.
     *
     * Only rows whose audio has not been touched for [STALE_RECORDING_MS]: the
     * recorder flushes every few seconds, so a live one is never that quiet,
     * and this cannot race a recording that is just starting.
     */
    private fun recoverInterrupted(live: Long?) {
        val now = System.currentTimeMillis()
        repo.orphaned().filter { it.id != live }.forEach { m ->
            val segs = m.segmentDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".pcm") }
                ?.toList().orEmpty()
            val lastWrite = (segs.maxOfOrNull { it.lastModified() } ?: 0L)
                .coerceAtLeast(m.startedAt)
            if (now - lastWrite < STALE_RECORDING_MS) return@forEach
            val bytes = segs.sumOf { it.length() }
            // 16 kHz mono PCM16: 32 bytes per millisecond.
            repo.finishRecording(m.id, lastWrite, bytes / 32, segs.size)
        }
    }

    private fun open(m: Meeting) {
        val live = RecordingState.state.value?.meetingId
        if (m.state == MeetingState.RECORDING && m.id == live) {
            RecordingActivity.open(this)
            return
        }
        startActivity(
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_ID, m.id)
                .putExtra(MeetingDetailActivity.EXTRA_QUERY, query.trim())
        )
    }

    private fun showRowMenu(anchor: View, m: Meeting) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val menu = PopupMenu(this, anchor, android.view.Gravity.END)
        menu.menu.add(0, 1, 0, R.string.rename)
        menu.menu.add(0, 2, 1, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> MeetingActions.rename(this, m) { refresh() }
                2 -> if (m.state == MeetingState.RECORDING && m.id == RecordingState.state.value?.meetingId) {
                    Snackbar.make(b.root, R.string.cant_delete_recording, Snackbar.LENGTH_LONG).show()
                } else MeetingActions.confirmDelete(this, m) { refresh() }
            }
            true
        }
        menu.show()
    }

    /**
     * Swipe either way to delete, with Undo. The long-press menu stays as the
     * discoverable route and the one that asks first.
     */
    private fun swipeToDelete(): ItemTouchHelper {
        val red = ColorDrawable(getColor(R.color.s_error_container))
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_trash)!!.mutate().apply {
            setTint(getColor(R.color.s_on_error_container))
        }
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val row = rows.getOrNull(vh.bindingAdapterPosition) ?: return 0
                // The meeting being recorded is not a thing you can delete.
                return if (row.meeting.state == MeetingState.RECORDING) 0
                else super.getSwipeDirs(rv, vh)
            }

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val row = rows.getOrNull(vh.bindingAdapterPosition) ?: return
                swiped(row.meeting)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, state: Int, active: Boolean
            ) {
                val v = vh.itemView
                if (dX != 0f) {
                    val left = if (dX > 0) v.left else (v.right + dX).toInt()
                    val right = if (dX > 0) (v.left + dX).toInt() else v.right
                    red.setBounds(left, v.top, right, v.bottom)
                    red.draw(c)
                    val size = (24 * resources.displayMetrics.density).toInt()
                    val margin = (24 * resources.displayMetrics.density).toInt()
                    val top = v.top + (v.height - size) / 2
                    val x = if (dX > 0) v.left + margin else v.right - margin - size
                    icon.setBounds(x, top, x + size, top + size)
                    if (kotlin.math.abs(dX) > margin + size) icon.draw(c)
                }
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }
        return ItemTouchHelper(callback)
    }

    private fun swiped(m: Meeting) {
        pendingDelete += m.id
        rows = rows.filter { it.meeting.id != m.id }
        adapter.submitList(rows)
        b.emptyBox.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        Snackbar.make(b.root, R.string.deleted_meeting, Snackbar.LENGTH_LONG)
            .setAnchorView(b.record)
            .setAction(R.string.undo) {
                pendingDelete -= m.id
                refresh()
            }
            .addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) commitPendingDeletes(only = m.id)
                }
            })
            .show()
    }

    private fun commitPendingDeletes(only: Long? = null) {
        val ids = if (only != null) listOf(only).filter { it in pendingDelete } else pendingDelete.toList()
        if (ids.isEmpty()) return
        pendingDelete -= ids.toSet()
        val app = this
        // Not lifecycleScope: onStop may be followed by the scope being torn
        // down, and a half-run delete is worse than a late one.
        Thread { ids.forEach { MeetingActions.delete(app, it) } }.start()
    }

    // ---- Record button ----------------------------------------------------

    /**
     * While a meeting runs this button is the way back to it, and it looks
     * like the recording: magenta, with the running time on it. It used to
     * say "Back", which did not say back to what.
     */
    private fun renderRecordButton() {
        val rec = RecordingState.state.value
        if (rec == null) {
            ticker?.cancel(); ticker = null
            b.record.text = getString(R.string.start_recording)
            b.record.contentDescription = null
            b.record.setIconResource(R.drawable.ic_mic)
            b.record.backgroundTintList = fabTint
            b.record.setTextColor(fabInk)
            b.record.iconTint = fabInk
            return
        }
        val onMic = ColorStateList.valueOf(getColor(R.color.s_on_mic))
        b.record.backgroundTintList = ColorStateList.valueOf(getColor(R.color.s_mic))
        b.record.setTextColor(onMic)
        b.record.iconTint = onMic
        b.record.setIconResource(if (rec.paused) R.drawable.ic_pause else R.drawable.ic_waveform)
        b.record.contentDescription = getString(R.string.open_recording)
        paintElapsed()
        if (ticker == null) ticker = lifecycleScope.launch {
            while (isActive) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) paintElapsed()
                delay(1000)
            }
        }
    }

    private fun paintElapsed() {
        val rec = RecordingState.state.value ?: return
        val label = if (rec.paused) getString(R.string.paused_label).lowercase()
            .replaceFirstChar { it.uppercase() } else Format.clock(rec.elapsedMs)
        b.record.text = getString(R.string.recording_fab, label)
    }

    private fun onRecordTapped() {
        if (RecordingState.isRecording) {
            RecordingActivity.open(this)
            return
        }
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            checkSpaceThenRecord()
            return
        }
        // Asked before and the system no longer shows the prompt: the only
        // way forward is the app's settings page, so offer that directly
        // rather than a button that silently does nothing.
        if (settings.askedForMic && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            micDenied()
            return
        }
        settings.askedForMic = true
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
    }

    private fun micDenied() {
        val forever = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val bar = Snackbar.make(
            b.root,
            if (forever) R.string.mic_denied_forever else R.string.mic_denied,
            Snackbar.LENGTH_LONG
        ).setAnchorView(b.record)
        if (forever) bar.setAction(R.string.open_settings) {
            startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
        bar.show()
    }

    private fun startRecording() {
        if (!RecordingState.isRecording) checkSpaceThenRecord() else RecordingActivity.open(this)
    }

    /**
     * Warns before starting on a nearly full phone. The recorder stops by
     * itself below [RecorderService.LOW_SPACE_BYTES], and finding that out an
     * hour into a meeting is the expensive way.
     */
    private fun checkSpaceThenRecord() {
        val free = filesDir.usableSpace
        if (free in 0 until RecorderService.WARN_SPACE_BYTES) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.low_space_title)
                .setMessage(getString(R.string.low_space_body, Settings.format(free)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.record_anyway) { _, _ -> record() }
                .show()
        } else record()
    }

    private fun record() {
        RecorderService.start(this)
        RecordingActivity.open(this)
    }

    // ---- Model download ---------------------------------------------------

    private fun renderModelCard() {
        val missing = ModelManager.missing(this)
        if (missing.isEmpty()) {
            b.modelCard.visibility = View.GONE
            b.modelReassure.visibility = View.GONE
            return
        }
        b.modelCard.visibility = View.VISIBLE
        b.modelReassure.visibility = View.VISIBLE
        val total = missing.sumOf { it.approxBytes }
        val info = download
        b.modelMobile.visibility = View.GONE
        b.modelPct.visibility = View.GONE
        b.modelProgress.visibility = View.GONE
        b.modelAction.visibility = View.VISIBLE
        b.modelAction.isEnabled = true

        when (info?.state) {
            WorkInfo.State.RUNNING -> {
                val done = info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0)
                val all = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, total).coerceAtLeast(1)
                val speed = info.progress.getLong(ModelDownloadWorker.KEY_SPEED, 0)
                val pct = (done * 100 / all).toInt().coerceIn(0, 100)
                b.modelTitle.setText(R.string.model_title_downloading)
                b.modelStatus.text = if (done > 0) ModelDownloadWorker.progressLine(done, all, speed)
                else getString(R.string.model_starting)
                b.modelProgress.visibility = View.VISIBLE
                b.modelProgress.isIndeterminate = done == 0L
                if (done > 0) b.modelProgress.setProgressCompat(pct, true)
                b.modelPct.visibility = if (done > 0) View.VISIBLE else View.GONE
                b.modelPct.text = "$pct%"
                // Nothing to press while it runs; the notification carries it
                // if the app is closed.
                b.modelAction.visibility = View.GONE
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val onDisk = ModelManager.bytesOnDisk(this, missing)
                val wantsWifi = info.constraints.requiredNetworkType == NetworkType.UNMETERED
                b.modelTitle.setText(if (wantsWifi) R.string.model_title_waiting else R.string.model_title_downloading)
                b.modelStatus.text = if (wantsWifi) getString(
                    R.string.model_waiting_body, Settings.format(onDisk), Settings.format(total)
                ) else getString(R.string.model_retrying, Settings.format(onDisk), Settings.format(total))
                if (onDisk > 0) {
                    b.modelProgress.visibility = View.VISIBLE
                    b.modelProgress.isIndeterminate = false
                    b.modelProgress.setProgressCompat((onDisk * 100 / total.coerceAtLeast(1)).toInt(), false)
                }
                b.modelAction.visibility = View.GONE
                b.modelMobile.visibility = if (wantsWifi) View.VISIBLE else View.GONE
            }
            WorkInfo.State.FAILED -> {
                b.modelTitle.setText(R.string.model_title_failed)
                val why = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                b.modelStatus.text = getString(R.string.model_failed_body, why ?: "network error")
                b.modelAction.setText(R.string.model_resume)
            }
            else -> {
                // Lead with what the app does and with the fact that you are
                // not blocked: recording works right now, and the audio is
                // kept, so a download started later loses nothing.
                b.modelTitle.setText(R.string.model_title)
                b.modelStatus.text = getString(R.string.model_pitch, Settings.format(total))
                val onDisk = ModelManager.bytesOnDisk(this, missing)
                b.modelAction.setText(if (onDisk > 0) R.string.model_resume else R.string.model_download)
            }
        }
    }

    /**
     * Starts the download in the background. On mobile data it asks first:
     * 600-odd MB is a real share of many plans, and it was being spent
     * without a word.
     */
    private fun startDownload() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val metered = cm?.isActiveNetworkMetered ?: false
        val missing = ModelManager.missing(this)
        if (missing.isEmpty()) return
        if (!metered) {
            ModelDownloadWorker.enqueue(this, missing, allowMobile = false)
            return
        }
        val remaining = missing.sumOf { it.approxBytes } - ModelManager.bytesOnDisk(this, missing)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_metered_title)
            .setMessage(getString(R.string.model_metered_body, Settings.format(remaining.coerceAtLeast(0))))
            .setNegativeButton(R.string.model_wait_wifi) { _, _ ->
                ModelDownloadWorker.enqueue(this, missing, allowMobile = false)
            }
            .setPositiveButton(R.string.model_use_mobile) { _, _ ->
                ModelDownloadWorker.enqueue(this, missing, allowMobile = true)
            }
            .show()
    }

    private fun explainDownload() {
        val live = ModelManager.Model.entries.filter { it.streaming && !it.speaker }.sumOf { it.approxBytes }
        val accurate = ModelManager.Model.entries.filter { !it.streaming && !it.speaker }.sumOf { it.approxBytes }
        val speaker = ModelManager.Model.entries.filter { it.speaker }.sumOf { it.approxBytes }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_whats_in)
            .setMessage(
                getString(
                    R.string.model_contents,
                    Settings.format(live), Settings.format(accurate), Settings.format(speaker)
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- Widget tip -------------------------------------------------------

    /**
     * The widget is the fastest way into a meeting and nothing pointed at it
     * except one line of empty-state text that disappears after the first
     * recording. Offered once the models are in, until one is placed or the
     * tip is dismissed.
     */
    private fun renderWidgetCard(hasMeetings: Boolean) {
        val mgr = AppWidgetManager.getInstance(this)
        val placed = mgr.getAppWidgetIds(ComponentName(this, ScribeWidget::class.java)).isNotEmpty()
        val show = !settings.widgetTipDismissed && !placed && hasMeetings &&
            ModelManager.missing(this).isEmpty()
        b.widgetCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun addWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, ScribeWidget::class.java), null, null)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_widget_title)
                .setMessage(R.string.add_widget_manual)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    companion object {
        const val EXTRA_REQUEST_PERMISSION = "request_permission"
        private const val STALE_RECORDING_MS = 2 * 60_000L
    }
}
