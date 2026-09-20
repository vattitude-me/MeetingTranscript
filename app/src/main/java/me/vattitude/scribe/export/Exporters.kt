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

    /**
     * How a speaker appears in an export. Diarization produces cluster numbers;
     * a name only exists if the user typed one. Falling back to "Speaker 1"
     * rather than inventing a name keeps the export honest about which of the
     * two it is \u2014 the model never learned who anyone is.
     */
    fun label(speaker: Int, names: Map<Int, String>): String? =
        if (speaker < 0) null else names[speaker] ?: "Speaker ${speaker + 1}"

    private fun prefix(l: Line, names: Map<Int, String>): String =
        label(l.speaker, names)?.let { "$it: " } ?: ""

    private fun dateOf(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    /** Human-readable transcript: one timestamped line per utterance. */
    fun plainText(meeting: Meeting, lines: List<Line>, names: Map<Int, String> = emptyMap()): String = buildString {
        appendLine(meeting.title)
        appendLine(dateOf(meeting.startedAt) + "  ·  " + timestamp(meeting.durationMs))
        appendLine()
        for (l in lines) appendLine("[${timestamp(l.tStartMs)}] ${prefix(l, names)}${l.text}")
    }

    /** Markdown, for pasting into a doc or an issue with the structure intact. */
    fun markdown(meeting: Meeting, lines: List<Line>, names: Map<Int, String> = emptyMap()): String = buildString {
        appendLine("# ${meeting.title}")
        appendLine()
        appendLine("*${dateOf(meeting.startedAt)} · ${timestamp(meeting.durationMs)}*")
        appendLine()
        for (l in lines) {
            val who = label(l.speaker, names)?.let { "**$it** " } ?: ""
            appendLine("- `[${timestamp(l.tStartMs)}]` $who${l.text}")
        }
    }

    /**
     * Transcript plus the instruction block, so handing a meeting to a model is
     * one paste rather than a paste and a re-typed prompt. PLAN.md §6 called
     * this the highest-leverage small feature in the app; it is also the one
     * place we state the citation contract that makes the output checkable.
     */
    fun promptReady(meeting: Meeting, lines: List<Line>, names: Map<Int, String> = emptyMap()): String = buildString {
        val diarized = lines.any { it.speaker >= 0 }
        appendLine("Below is a verbatim, timestamped transcript of a meeting.")
        appendLine("Each line is prefixed with its line number in square brackets.")
        if (diarized) {
            appendLine("Speakers were separated automatically by voice. Labels like")
            appendLine("\"Speaker 2\" mean a distinct voice, not a known person, and the")
            appendLine("separation itself can be wrong. Do not guess who anyone is.")
        }
        appendLine()
        appendLine("Please:")
        appendLine("1. Summarise the meeting in a short paragraph.")
        appendLine("2. List the decisions made.")
        appendLine("3. List the action items, with an owner for each where one is identifiable.")
        appendLine("4. Note anything left unresolved.")
        appendLine()
        appendLine("Cite the line numbers you drew each point from, like [12] or [12-15].")
        appendLine("If something is not in the transcript, say so rather than inferring it.")
        appendLine("The transcript is machine-generated and may contain recognition errors.")
        appendLine()
        appendLine("---")
        appendLine("Title: ${meeting.title}")
        appendLine("Date: ${dateOf(meeting.startedAt)}")
        appendLine("Duration: ${timestamp(meeting.durationMs)}")
        appendLine("---")
        appendLine()
        for (l in lines) appendLine("[${l.idx}] (${timestamp(l.tStartMs)}) ${prefix(l, names)}${l.text}")
    }

    /** SubRip. Timestamps are `HH:MM:SS,mmm`. */
    fun srt(lines: List<Line>, names: Map<Int, String> = emptyMap()): String = buildString {
        lines.forEachIndexed { i, l ->
            appendLine((i + 1).toString())
            appendLine("${srtTime(l.tStartMs)} --> ${srtTime(l.tEndMs)}")
            appendLine(prefix(l, names) + l.text)
            appendLine()
        }
    }

    /** WebVTT. Same cues, `.` for the decimal separator and a required header. */
    fun vtt(lines: List<Line>, names: Map<Int, String> = emptyMap()): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        for (l in lines) {
            appendLine("${srtTime(l.tStartMs).replace(',', '.')} --> ${srtTime(l.tEndMs).replace(',', '.')}")
            appendLine(prefix(l, names) + l.text)
            appendLine()
        }
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    /**
     * Machine-readable handoff. This is the contract with Stage 2: whatever
     * summarizes the meeting reads this file and nothing else.
     */
    fun json(meeting: Meeting, lines: List<Line>, names: Map<Int, String> = emptyMap()): String = buildString {
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
                    """"speaker": ${l.speaker}, "speaker_name": ${quote(label(l.speaker, names) ?: "")}, """ +
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
        val mime = when (ext) {
            "json" -> "application/json"
            "md" -> "text/markdown"
            "vtt" -> "text/vtt"
            else -> "text/plain"
        }
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
