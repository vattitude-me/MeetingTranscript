package me.vattitude.scribe.ui

import android.text.InputType
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.vattitude.scribe.R
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.Repo

/** Rename and delete, shared by the list and the transcript so both behave the same. */
object MeetingActions {

    /**
     * Deletes a meeting for good. Cancels its jobs first, so a transcription
     * that was mid-flight does not write lines for a meeting that is gone.
     */
    fun delete(activity: AppCompatActivity, id: Long) {
        val wm = WorkManager.getInstance(activity)
        wm.cancelUniqueWork("transcribe-$id")
        wm.cancelUniqueWork("rediarize-$id")
        Repo(activity).delete(id)
    }

    /** Asks first, spelling out what goes: minutes of audio, megabytes and lines. */
    fun confirmDelete(activity: AppCompatActivity, m: Meeting, onDeleted: () -> Unit) {
        activity.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val bytes = m.segmentDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                Format.deleteSummary(m.durationMs, bytes, Repo(activity).lineCount(m.id))
            }
            val title = m.title.ifBlank { activity.getString(R.string.untitled) }
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.delete_meeting_title, title))
                .setMessage(activity.getString(R.string.delete_meeting_body, summary))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { delete(activity, m.id) }
                        onDeleted()
                    }
                }
                .show()
        }
    }

    /** A text field in a dialog, pre-filled and selected so typing replaces it. */
    fun promptText(
        activity: AppCompatActivity,
        title: String,
        hint: String,
        initial: String,
        onSave: (String) -> Unit
    ) {
        val ctx = activity
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
            .apply { this.hint = hint }
        val input = TextInputEditText(layout.context).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            setSelectAllOnFocus(true)
        }
        layout.addView(input)
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> onSave(input.text?.toString()?.trim().orEmpty()) }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    fun rename(activity: AppCompatActivity, m: Meeting, onDone: () -> Unit) {
        promptText(activity, activity.getString(R.string.rename), activity.getString(R.string.stop_sheet_hint).substringBefore(" ("), m.title) { t ->
            if (t.isEmpty()) return@promptText
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { Repo(activity).rename(m.id, t) }
                onDone()
            }
        }
    }

    @Suppress("unused")
    private fun inflater(activity: AppCompatActivity) = LayoutInflater.from(activity)
}
