package me.vattitude.scribe.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Meeting
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 1 ends at the transcript. These writers produce the handoff artifacts —
 * the plain text a person reads, and the JSON a Stage 2 summarizer consumes.
 */
object Exporters {

    fun timestamp(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun dateOf(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    /** Human-readable transcript: one timestamped line per utterance. */
    fun plainText(meeting: Meeting, lines: List<Line>): String = buildString {
        appendLine(meeting.title)
        appendLine(dateOf(meeting.startedAt) + "  ·  " + timestamp(meeting.durationMs))
        appendLine()
        for (l in lines) appendLine("[${timestamp(l.tStartMs)}] ${l.text}")
    }

    /**
     * Machine-readable handoff. This is the contract with Stage 2: whatever
     * summarizes the meeting reads this file and nothing else.
     */
    fun json(meeting: Meeting, lines: List<Line>): String = buildString {
        appendLine("{")
        appendLine("""  "schema": "scribe.transcript.v1",""")
        appendLine("""  "title": ${quote(meeting.title)},""")
        appendLine("""  "started_at": ${meeting.startedAt},""")
        appendLine("""  "duration_ms": ${meeting.durationMs},""")
        appendLine("""  "language": "en",""")
        appendLine("""  "asr_model": ${quote(meeting.asrModelId ?: "")},""")
        appendLine("""  "lines": [""")
        lines.forEachIndexed { i, l ->
            val comma = if (i == lines.lastIndex) "" else ","
            appendLine(
                """    {"i": ${l.idx}, "t_start_ms": ${l.tStartMs}, "t_end_ms": ${l.tEndMs}, """ +
                    """"text": ${quote(l.text)}, "confidence": ${"%.3f".format(l.confidence)}}$comma"""
            )
        }
        appendLine("  ]")
        append("}")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "meeting" }

    /** Writes a file into cache/share and returns a chooser Intent for it. */
    fun shareFile(context: Context, meeting: Meeting, content: String, ext: String): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "${safeName(meeting.title)}.$ext")
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = if (ext == "json") "application/json" else "text/plain"
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, meeting.title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share transcript"
        )
    }

    /** Sharing a single line, which is the point of storing lines separately. */
    fun shareText(text: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        "Share"
    )
}
