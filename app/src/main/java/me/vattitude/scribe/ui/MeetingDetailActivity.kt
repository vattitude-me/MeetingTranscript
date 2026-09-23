package me.vattitude.scribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.R
import me.vattitude.scribe.asr.Diarizer
import me.vattitude.scribe.asr.Dynamics
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.asr.ReDiarizeWorker
import me.vattitude.scribe.asr.TranscribeWorker
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.databinding.ActivityDetailBinding
import me.vattitude.scribe.databinding.SheetConversationBinding
import me.vattitude.scribe.export.Exporters
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Mark
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private lateinit var repo: Repo
    private lateinit var adapter: LineAdapter
    private lateinit var player: PcmPlayer
    private var meetingId = -1L
    private var meeting: Meeting? = null
    private var lines: List<Line> = emptyList()
    private var items: List<TranscriptItem> = emptyList()
    private var names: Map<Int, String> = emptyMap()
    private var rediarizing = false

    /** The original audio, if it survives: needed to play or re-separate voices. */
    private var segments: List<File> = emptyList()
    private val hasAudio get() = segments.isNotEmpty()

    /** Find bar: every match as (adapter position, occurrence in that line). */
    private var matches: List<Pair<Int, Int>> = emptyList()
    private var matchIndex = -1

    private val closeFind = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideFind()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = ""
        repo = Repo(this)
        meetingId = intent.getLongExtra(EXTRA_ID, -1L)
        onBackPressedDispatcher.addCallback(this, closeFind)

        adapter = LineAdapter(
            onSpeakerClick = ::promptName,
            onLineClick = ::showLineMenu,
            onMarkClick = ::showMarkMenu
        )
        b.lines.layoutManager = LinearLayoutManager(this)
        b.lines.adapter = adapter
        // Rows are rebound on every refresh while a meeting transcribes; a
        // cross-fade on each would make the page shimmer.
        (b.lines.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        b.rename.setOnClickListener { meeting?.let { m -> MeetingActions.rename(this, m) { reload() } } }

        player = PcmPlayer(
            onPosition = { ms -> b.playerText.text = getString(R.string.player_playing, Exporters.timestamp(ms)) },
            onEnd = { b.player.visibility = View.GONE }
        )
        b.playerStop.setOnClickListener { player.stop(); b.player.visibility = View.GONE }

        b.findInput.doAfterTextChanged { runFind(it?.toString().orEmpty()) }
        b.findInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { step(1); hideKeyboard(); true } else false
        }
        b.findNext.setOnClickListener { step(1) }
        b.findPrev.setOnClickListener { step(-1) }
        b.findClose.setOnClickListener { hideFind() }

        // Opened from a search result: carry the term in, so the match that
        // put this meeting in the results is on screen rather than somewhere
        // in an hour of text.
        val q = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        if (savedInstanceState == null && q.isNotBlank()) {
            showFind(focus = false)
            b.findInput.setText(q)
        }

        // Follow this meeting's jobs, so the banner and the lines move on
        // without leaving and coming back.
        val wm = WorkManager.getInstance(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wm.getWorkInfosForUniqueWorkFlow("transcribe-$meetingId").collect { reload() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wm.getWorkInfosForUniqueWorkFlow("rediarize-$meetingId").collect { infos ->
                    val was = rediarizing
                    rediarizing = infos.any {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    if (was != rediarizing) reload()
                }
            }
        }
        // Progress lives in the database, written once per segment. Poll it
        // while there is something moving; a transcript fills in as it goes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(POLL_MS)
                    val s = meeting?.state
                    if (s == MeetingState.TRANSCRIBING || s == MeetingState.RECORDED || rediarizing) reload()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onStop() {
        player.stop()
        b.player.visibility = View.GONE
        super.onStop()
    }

    private fun reload() {
        lifecycleScope.launch {
            val id = meetingId
            val loaded = withContext(Dispatchers.IO) {
                val m = repo.meeting(id) ?: return@withContext null
                Loaded(m, repo.lines(id), repo.marks(id), repo.speakerNames(id), Diarizer.segmentsOf(m.segmentDir))
            }
            if (loaded == null) { finish(); return@launch }
            val m = loaded.meeting
            meeting = m
            lines = loaded.lines
            names = loaded.names
            segments = loaded.segments
            items = TranscriptItem.merge(lines, loaded.marks)
            b.headline.text = m.title.ifBlank { getString(R.string.untitled) }
            adapter.submit(items, names)
            renderStatus(m)
            renderBanner(m)
            b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            b.empty.setText(
                if (m.state == MeetingState.DONE) R.string.no_transcript else R.string.transcript_pending
            )
            if (b.findBar.visibility == View.VISIBLE) runFind(b.findInput.text.toString(), keepPlace = true)
            invalidateOptionsMenu()
        }
    }

    private data class Loaded(
        val meeting: Meeting,
        val lines: List<Line>,
        val marks: List<Mark>,
        val names: Map<Int, String>,
        val segments: List<File>
    )

    /** The date line under the title: when, how long, how much. */
    private fun renderStatus(m: Meeting) {
        val parts = mutableListOf(
            SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(m.startedAt))
        )
        if (m.durationMs > 0) parts += Exporters.timestamp(m.durationMs)
        if (lines.isNotEmpty()) parts += resources.getQuantityString(R.plurals.lines_count, lines.size, lines.size)
        val voices = lines.map { it.speaker }.filter { it >= 0 }.distinct().size
        val fixable = m.state == MeetingState.DONE && voices > 0 && hasAudio
        // "beta" on the count itself. This line is the only place a reader
        // meets the speaker labels, and a bare "3 voices" reads as a
        // measurement rather than the guess it is.
        if (voices > 1) parts += getString(R.string.voices_beta, voices)
        b.status.text = parts.joinToString(" · ")
        // The voice count is the one number here the user can see is wrong, so
        // it is the thing they can tap to correct.
        if (fixable && voices > 1) {
            b.status.append(" · ")
            b.status.append(getString(R.string.tap_to_fix))
        }
        b.status.isClickable = fixable
        b.status.setOnClickListener(if (fixable) View.OnClickListener { promptSpeakerCount(m) } else null)
    }

    /**
     * Where a meeting that is not simply done is up to, and the one thing
     * that can be done about it. Transcribing used to start when you tapped a
     * waiting row in the list, which made opening a meeting impossible without
     * also kicking off work.
     */
    private fun renderBanner(m: Meeting) {
        b.banner.visibility = View.VISIBLE
        b.bannerBody.visibility = View.GONE
        b.bannerProgress.visibility = View.GONE
        b.bannerAction.visibility = View.GONE
        b.bannerIcon.setImageResource(R.drawable.ic_clock)
        b.bannerIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.s_cyan))
        b.banner.setBackgroundResource(R.drawable.surface_card)

        fun body(text: CharSequence?) {
            b.bannerBody.text = text
            b.bannerBody.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        fun action(label: Int, onClick: () -> Unit) {
            b.bannerAction.setText(label)
            b.bannerAction.visibility = View.VISIBLE
            b.bannerAction.setOnClickListener { onClick() }
        }

        when (m.state) {
            MeetingState.RECORDING -> {
                b.bannerIcon.setImageResource(R.drawable.ic_mic)
                b.bannerIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.s_mic))
                b.bannerTitle.setText(R.string.still_recording)
                if (RecordingState.state.value?.meetingId == m.id) {
                    action(R.string.open_label) { RecordingActivity.open(this) }
                } else body(getString(R.string.recording_interrupted))
            }
            MeetingState.TRANSCRIBING -> {
                b.bannerTitle.text = if (m.segmentCount > 0)
                    getString(R.string.status_transcribing, m.segmentsDone, m.segmentCount)
                else getString(R.string.status_transcribing_start)
                body(
                    if (m.error == TranscribeWorker.BREADCRUMB_LOADING) m.error
                    else getString(R.string.status_transcribing_body)
                )
                b.bannerProgress.visibility = View.VISIBLE
                b.bannerProgress.isIndeterminate = m.segmentCount <= 0
                if (m.segmentCount > 0) {
                    b.bannerProgress.setProgressCompat(m.segmentsDone * 100 / m.segmentCount, true)
                }
            }
            MeetingState.RECORDED -> {
                val modelReady = ModelManager.isReady(this, ModelManager.Model.ACCURATE)
                b.bannerTitle.setText(R.string.status_waiting)
                if (!modelReady) {
                    body(getString(R.string.status_waiting_models))
                } else {
                    body(
                        if (m.error == "Paused") getString(R.string.status_paused)
                        else getString(R.string.status_queued)
                    )
                    action(R.string.transcribe_now) { transcribeNow(m, restart = false) }
                }
            }
            MeetingState.FAILED -> {
                b.banner.setBackgroundResource(R.drawable.surface_error)
                b.bannerIcon.setImageResource(R.drawable.ic_warning)
                b.bannerIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.s_error))
                b.bannerTitle.setText(R.string.status_failed)
                body(m.error)
                if (hasAudio) action(R.string.try_again) { transcribeNow(m, restart = true) }
            }
            else -> {
                if (rediarizing) {
                    b.bannerIcon.setImageResource(R.drawable.ic_people)
                    b.bannerTitle.setText(R.string.rediarizing)
                    body(getString(R.string.rediarizing_body))
                    b.bannerProgress.visibility = View.VISIBLE
                    b.bannerProgress.isIndeterminate = true
                } else {
                    b.banner.visibility = View.GONE
                }
            }
        }
    }

    /** Explicit "do it now", so transcription never depends on the scheduler's mood. */
    private fun transcribeNow(m: Meeting, restart: Boolean) {
        if (!ModelManager.isReady(this, ModelManager.Model.ACCURATE)) {
            Snackbar.make(b.root, R.string.status_waiting_models, Snackbar.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.setState(m.id, MeetingState.RECORDED)
                // A failure starts over; a queued meeting keeps what an earlier
                // run already transcribed.
                if (restart) repo.setProgress(m.id, 0, null)
            }
            // Replace a job the scheduler is sitting on rather than queueing
            // behind it (enqueue keeps an existing one).
            val wm = WorkManager.getInstance(this@MeetingDetailActivity)
            wm.cancelUniqueWork("transcribe-${m.id}")
            TranscribeWorker.enqueue(this@MeetingDetailActivity, m.id)
            reload()
        }
    }

    // ---- Toolbar ----------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val m = meeting
        val hasLines = lines.isNotEmpty()
        val labelled = lines.any { it.speaker >= 0 }
        menu.findItem(R.id.action_find)?.isVisible = hasLines
        menu.findItem(R.id.action_prompt)?.isVisible = hasLines
        menu.findItem(R.id.action_share_text)?.isVisible = hasLines
        menu.findItem(R.id.action_export_as)?.isVisible = hasLines
        menu.findItem(R.id.action_speakers)?.isVisible = labelled
        menu.findItem(R.id.action_speaker_count)?.isVisible =
            hasLines && hasAudio && m?.state == MeetingState.DONE
        menu.findItem(R.id.action_delete)?.isVisible =
            m != null && !(m.state == MeetingState.RECORDING && RecordingState.state.value?.meetingId == m.id)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        val m = meeting ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_find -> showFind(focus = true)
            R.id.action_rename -> MeetingActions.rename(this, m) { reload() }
            R.id.action_share_text ->
                startActivity(Exporters.shareFile(this, m, Exporters.plainText(m, lines, names), "txt"))
            R.id.action_prompt -> copyForChatbot(m)
            R.id.action_speakers -> showConversation()
            R.id.action_speaker_count -> promptSpeakerCount(m)
            R.id.action_export_as -> chooseFormat(m)
            R.id.action_delete -> MeetingActions.confirmDelete(this, m) { finish() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Straight to the clipboard. It used to open the share sheet, which put a
     * picker between the user and the chatbot they already had open.
     */
    private fun copyForChatbot(m: Meeting) {
        val clip = ClipData.newPlainText(m.title, Exporters.promptReady(m, lines, names))
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        Snackbar.make(b.root, R.string.copied_prompt, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * One chooser rather than a menu item per format. The order is by how often
     * they get used, and each label says what the file is for rather than only
     * naming the extension.
     */
    private fun chooseFormat(m: Meeting) {
        val formats = listOf<Triple<String, String, (List<Line>) -> String>>(
            Triple("Plain text (.txt)", "txt") { Exporters.plainText(m, it, names) },
            Triple("Markdown (.md)", "md") { Exporters.markdown(m, it, names) },
            Triple("For a summarizer (.json)", "json") { Exporters.json(m, it, names) },
            Triple("Subtitles (.srt)", "srt") { Exporters.srt(it, names) },
            Triple("Subtitles (.vtt)", "vtt") { Exporters.vtt(it, names) }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_as)
            .setItems(formats.map { it.first }.toTypedArray()) { _, i ->
                val (_, ext, build) = formats[i]
                startActivity(Exporters.shareFile(this, m, build(lines), ext))
            }
            .show()
    }

    // ---- Lines and marks --------------------------------------------------

    private fun showLineMenu(anchor: View, line: Line) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        menu.menu.add(0, 1, 0, R.string.copy)
        menu.menu.add(0, 2, 1, R.string.share)
        if (hasAudio) menu.menu.add(0, 3, 2, R.string.play_from_here)
        menu.setOnMenuItemClickListener {
            val label = Exporters.label(line.speaker, names)
            val text = (if (label != null) "$label: " else "") + line.text
            when (it.itemId) {
                1 -> {
                    getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("line", text))
                    Snackbar.make(b.root, R.string.copied_line, Snackbar.LENGTH_SHORT).show()
                }
                2 -> startActivity(Exporters.shareText(text))
                3 -> play(line.tStartMs)
            }
            true
        }
        menu.show()
    }

    private fun showMarkMenu(anchor: View, mark: Mark) {
        val menu = PopupMenu(this, anchor, Gravity.START)
        if (hasAudio) menu.menu.add(0, 1, 0, R.string.play_from_here)
        menu.menu.add(0, 2, 1, R.string.remove_mark)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                // A few seconds early: a mark is tapped after the thing worth
                // marking has started.
                1 -> play((mark.tMs - MARK_LEAD_MS).coerceAtLeast(0))
                2 -> lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteMark(mark.id) }
                    reload()
                }
            }
            true
        }
        menu.show()
    }

    private fun play(fromMs: Long) {
        if (!hasAudio) return
        b.player.visibility = View.VISIBLE
        b.playerText.text = getString(R.string.player_playing, Exporters.timestamp(fromMs))
        player.play(segments, fromMs)
    }

    // ---- Find -------------------------------------------------------------

    private fun showFind(focus: Boolean) {
        b.findBar.visibility = View.VISIBLE
        closeFind.isEnabled = true
        if (focus) {
            b.findInput.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(b.findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideFind() {
        b.findBar.visibility = View.GONE
        closeFind.isEnabled = false
        hideKeyboard()
        b.findInput.setText("")
        adapter.setFind("", -1, -1)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.findInput.windowToken, 0)
    }

    private fun runFind(q: String, keepPlace: Boolean = false) {
        val query = q.trim()
        val found = mutableListOf<Pair<Int, Int>>()
        if (query.isNotEmpty()) {
            items.forEachIndexed { pos, item ->
                if (item is TranscriptItem.Spoken) {
                    Highlight.matches(item.line.text, query).indices.forEach { n -> found += pos to n }
                }
            }
        }
        val previous = matches.getOrNull(matchIndex)
        matches = found
        matchIndex = when {
            found.isEmpty() -> -1
            keepPlace && previous != null && previous in found -> found.indexOf(previous)
            else -> 0
        }
        paintFind(scroll = !keepPlace)
    }

    private fun step(by: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + by).mod(matches.size)
        paintFind(scroll = true)
    }

    private fun paintFind(scroll: Boolean) {
        val query = b.findInput.text.toString().trim()
        val current = matches.getOrNull(matchIndex)
        adapter.setFind(query, current?.first ?: -1, current?.second ?: -1)
        b.findCount.text = when {
            query.isEmpty() -> ""
            matches.isEmpty() -> getString(R.string.find_none)
            else -> getString(R.string.find_count, matchIndex + 1, matches.size)
        }
        b.findPrev.isEnabled = matches.size > 1
        b.findNext.isEnabled = matches.size > 1
        if (scroll && current != null) {
            // A third of the way down, so the lines before the match are
            // visible as context.
            (b.lines.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(current.first, b.lines.height / 3)
        }
    }

    // ---- Speakers ---------------------------------------------------------

    /**
     * Who talked, how much, and who cut in, as bars in each speaker's colour.
     * Read-only and derived — nothing to configure.
     *
     * The footer says what the numbers are not. Talk time is not contribution,
     * and a tool that reports one while implying the other is the kind of
     * meeting analytics people are right to distrust.
     */
    private fun showConversation() {
        val summary = Dynamics.of(lines)
        val sheet = BottomSheetDialog(this)
        val v = SheetConversationBinding.inflate(layoutInflater)
        if (summary.shares.isEmpty()) {
            v.summary.setText(R.string.conversation_empty)
        } else {
            v.summary.text = resources.getQuantityString(
                R.plurals.voices_count, summary.speakers, summary.speakers
            ) + " · " + Exporters.timestamp(summary.totalTalkMs) + " " + getString(R.string.of_talk)
            val dp = resources.displayMetrics.density
            for (share in summary.shares.sortedByDescending { it.talkMs }) {
                val color = ContextCompat.getColor(this, LineAdapter.COLORS[share.speaker % LineAdapter.COLORS.size])
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (12 * dp).toInt(), 0, 0)
                }
                row.addView(TextView(this).apply {
                    text = Exporters.label(share.speaker, names) ?: "Speaker"
                    setTextColor(color)
                    textSize = 15f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.serif_semibold)
                })
                row.addView(LinearProgressIndicator(this).apply {
                    max = 100
                    progress = (share.fraction * 100).toInt()
                    setIndicatorColor(color)
                    trackColor = getColor(R.color.s_divider)
                    trackThickness = (8 * dp).toInt()
                    trackCornerRadius = (4 * dp).toInt()
                    setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
                })
                row.addView(TextView(this).apply {
                    text = Dynamics.describe(share) + " · " +
                        getString(R.string.longest_stretch, Exporters.timestamp(share.longestTurnMs))
                    setTextColor(getColor(R.color.s_text_muted))
                    textSize = 13f
                })
                v.rows.addView(row)
            }
        }
        sheet.setContentView(v.root)
        sheet.show()
    }

    /**
     * Diarization finds distinct voices; it has no idea whose they are. Naming
     * is the user's act, done once and applied to every line that voice spoke.
     */
    private fun promptName(speaker: Int) {
        val m = meeting ?: return
        MeetingActions.promptText(
            this, getString(R.string.who_is_this), "Speaker ${speaker + 1}", names[speaker].orEmpty()
        ) { name ->
            if (name.isEmpty()) return@promptText
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { repo.nameSpeaker(m.id, speaker, name) }
                names = names + (speaker to name)
                adapter.submit(items, names)
            }
        }
    }

    /**
     * Asks how many people actually spoke, then separates the voices again.
     *
     * Guessing the count from the audio alone is the weakest part of this app.
     * A threshold decides how different two stretches of speech must be before
     * they count as two people, and no single value works: swept across one-,
     * two- and three-speaker recordings, none produced the right number for all
     * of them. One person talking for nine minutes drifts enough to be split
     * into six.
     *
     * So this asks, because the user was in the meeting and the audio is still
     * on disk — applying an answer costs one diarization pass rather than a
     * whole re-transcription.
     *
     * **But the answer is not always obeyed, and this dialog says so.** On a
     * ten-minute recording shaped like a real meeting — ten people, three doing
     * 98% of the talking — the clusterer returned two voices when told three.
     * The count is a hint to the clusterer, not an instruction it follows,
     * which is why speaker separation ships off by default. See
     * Settings.identifySpeakers.
     */
    private fun promptSpeakerCount(m: Meeting) {
        if (lines.isEmpty()) return
        if (!hasAudio) {
            // Nothing to re-read. Say why rather than failing quietly, since the
            // cause is a setting the user chose and can change for next time.
            Snackbar.make(b.root, R.string.no_audio_to_rediarize, Snackbar.LENGTH_LONG).show()
            return
        }
        // "Did most of the talking", not "spoke": a meeting of ten where seven
        // say one sentence each has three voices worth labelling.
        val options = (1..8).map { if (it == 1) "1 person (just me)" else "$it people" } +
            "Let the app decide"
        val current = m.expectedSpeakers
        val checked = if (current in 1..8) current - 1 else options.lastIndex
        MaterialAlertDialogBuilder(this)
            // setMessage is dropped when a choice list takes the content slot,
            // so the beta warning rides in the title area instead.
            .setCustomTitle(TextView(this).apply {
                text = buildString {
                    append(getString(R.string.who_talked_most)).append("\n\n")
                    append(getString(R.string.speaker_beta_warning))
                }
                val dp = resources.displayMetrics.density
                setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
                setTextColor(getColor(R.color.s_text_dim))
                textSize = 14f
            })
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val count = if (which == options.lastIndex) 0 else which + 1
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.setExpectedSpeakers(m.id, count) }
                    meeting = m.copy(expectedSpeakers = count)
                    ReDiarizeWorker.enqueue(this@MeetingDetailActivity, m.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "meeting_id"
        /** The list's search term, to open the find bar already on it. */
        const val EXTRA_QUERY = "query"
        private const val POLL_MS = 2_500L
        private const val MARK_LEAD_MS = 5_000L
    }
}
