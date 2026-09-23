package me.vattitude.scribe.export

import android.content.Context
import android.net.Uri
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Meeting
import me.vattitude.scribe.store.MeetingState
import me.vattitude.scribe.store.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-library backup: every meeting, every transcript, and optionally the
 * audio, as one zip the user owns.
 *
 * This is deliberately a different artifact from [Exporters], which produces a
 * *transcript* for a reader or a Stage 2 summarizer. A backup exists to be read
 * back by Scribe itself — new phone, factory reset — so it round-trips the
 * fields the app needs rather than the ones an external agent wants.
 *
 *   manifest.json          schema + the meeting rows and their lines
 *   audio/<id>/<seg>.pcm   raw segments, only when includeAudio
 *
 * Entries are streamed in and out. A two-hour meeting is ~230 MB of PCM and
 * must never be held in memory (PLAN.md §4.2 rule 5).
 */
object Backup {

    const val SCHEMA = "scribe.backup.v1"
    const val MIME = "application/zip"

    /** What the caller needs to describe the result without re-reading the zip. */
    data class Written(val meetings: Int, val lines: Int, val bytes: Long, val withAudio: Boolean)

    data class Summary(val meetings: Int, val lines: Int, val hasAudio: Boolean)

    class BadBackup(message: String) : Exception(message)

    fun write(context: Context, out: Uri, includeAudio: Boolean): Written {
        val repo = Repo(context)
        val meetings = repo.meetings()
        var lineCount = 0

        val resolver = context.contentResolver
        resolver.openOutputStream(out)?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                val root = JSONObject()
                root.put("schema", SCHEMA)
                root.put("exported_at", System.currentTimeMillis())
                root.put("app_version", version(context))
                root.put("includes_audio", includeAudio)

                val arr = JSONArray()
                for (m in meetings) {
                    val lines = repo.lines(m.id)
                    lineCount += lines.size
                    arr.put(meetingJson(m, lines, repo.marks(m.id).map { it.tMs }))
                }
                root.put("meetings", arr)

                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(root.toString(2).toByteArray())
                zip.closeEntry()

                if (includeAudio) {
                    for (m in meetings) {
                        val dir = m.segmentDir
                        if (!dir.isDirectory) continue
                        for (seg in dir.listFiles().orEmpty().sortedBy { it.name }) {
                            if (!seg.isFile) continue
                            zip.putNextEntry(ZipEntry("audio/${m.id}/${seg.name}"))
                            seg.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
        } ?: throw BadBackup("Could not open the destination file")

        val size = runCatching {
            resolver.openFileDescriptor(out, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L

        return Written(meetings.size, lineCount, size, includeAudio)
    }

    /**
     * Restores a backup **additively** — imported meetings are added alongside
     * whatever is already on the phone, never replacing it. Import is not
     * destructive, so a user who picks the wrong file loses nothing; clearing
     * data is its own explicit, confirmed action.
     */
    fun read(context: Context, input: Uri): Summary {
        val repo = Repo(context)
        val staging = File(context.cacheDir, "restore").apply {
            deleteRecursively(); mkdirs()
        }

        var manifest: JSONObject? = null
        try {
            context.contentResolver.openInputStream(input)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (entry.isDirectory) { zip.closeEntry(); continue }

                        if (name == "manifest.json") {
                            manifest = JSONObject(zip.readBytes().decodeToString())
                        } else if (name.startsWith("audio/")) {
                            // Zip entries are attacker-controlled names; resolve and
                            // verify each one stays inside the staging directory
                            // rather than trusting "../" not to appear.
                            val dest = File(staging, name.removePrefix("audio/"))
                            val ok = dest.canonicalPath.startsWith(staging.canonicalPath + File.separator)
                            if (ok) {
                                dest.parentFile?.mkdirs()
                                dest.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw BadBackup("Could not read that file")

            val root = manifest ?: throw BadBackup("This isn't a Meeting Transcript backup file")
            if (root.optString("schema") != SCHEMA) {
                throw BadBackup("Unsupported backup format: ${root.optString("schema", "unknown")}")
            }

            val arr = root.optJSONArray("meetings") ?: JSONArray()
            var lineCount = 0
            var hasAudio = false

            for (i in 0 until arr.length()) {
                val mo = arr.getJSONObject(i)
                val oldId = mo.optLong("id")
                val dir = File(context.filesDir, "meetings/restored-${System.currentTimeMillis()}-$oldId")
                dir.mkdirs()

                val staged = File(staging, oldId.toString())
                if (staged.isDirectory) {
                    staged.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
                    hasAudio = true
                }

                val newId = repo.createMeeting(
                    title = mo.optString("title", "Untitled meeting"),
                    startedAt = mo.optLong("started_at"),
                    dir = dir
                )
                repo.finishRecording(
                    id = newId,
                    endedAt = mo.optLong("ended_at"),
                    durationMs = mo.optLong("duration_ms"),
                    segmentCount = mo.optInt("segment_count")
                )

                val lo = mo.optJSONArray("lines") ?: JSONArray()
                val lines = buildList {
                    for (j in 0 until lo.length()) {
                        val l = lo.getJSONObject(j)
                        add(
                            Line(
                                id = 0,
                                meetingId = newId,
                                idx = l.optInt("i", j),
                                tStartMs = l.optLong("t_start_ms"),
                                tEndMs = l.optLong("t_end_ms"),
                                text = l.optString("text"),
                                confidence = l.optDouble("confidence", 0.0)
                            )
                        )
                    }
                }
                if (lines.isNotEmpty()) repo.appendLines(newId, lines)
                lineCount += lines.size
                mo.optJSONArray("marks")?.let { ma ->
                    for (j in 0 until ma.length()) repo.addMark(newId, ma.optLong(j))
                }

                repo.setProgress(newId, mo.optInt("segments_done"), mo.optString("asr_model_id").ifEmpty { null })
                // A restored meeting with a transcript is done; one without still
                // has its audio, so leave it where the normal retry path can see it.
                val state = mo.optString("state", MeetingState.DONE)
                repo.setState(newId, if (lines.isNotEmpty()) MeetingState.DONE else state)
            }

            return Summary(arr.length(), lineCount, hasAudio)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Peeks at the manifest without writing anything, so the UI can confirm first. */
    fun inspect(context: Context, input: Uri): Summary {
        context.contentResolver.openInputStream(input)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "manifest.json") {
                        val root = JSONObject(zip.readBytes().decodeToString())
                        if (root.optString("schema") != SCHEMA) {
                            throw BadBackup("Unsupported backup format: ${root.optString("schema", "unknown")}")
                        }
                        val arr = root.optJSONArray("meetings") ?: JSONArray()
                        var lines = 0
                        for (i in 0 until arr.length()) {
                            lines += arr.getJSONObject(i).optJSONArray("lines")?.length() ?: 0
                        }
                        return Summary(arr.length(), lines, root.optBoolean("includes_audio"))
                    }
                    zip.closeEntry()
                }
            }
        }
        throw BadBackup("This isn't a Meeting Transcript backup file")
    }

    private fun meetingJson(m: Meeting, lines: List<Line>, marks: List<Long>): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("title", m.title)
        put("started_at", m.startedAt)
        put("ended_at", m.endedAt ?: JSONObject.NULL)
        put("duration_ms", m.durationMs)
        put("state", m.state)
        put("asr_model_id", m.asrModelId ?: JSONObject.NULL)
        put("segments_done", m.segmentsDone)
        put("segment_count", m.segmentCount)
        put("lines", JSONArray().apply {
            for (l in lines) put(
                JSONObject()
                    .put("i", l.idx)
                    .put("t_start_ms", l.tStartMs)
                    .put("t_end_ms", l.tEndMs)
                    .put("text", l.text)
                    .put("confidence", l.confidence)
            )
        })
        // Optional: readers that predate marks ignore the key.
        if (marks.isNotEmpty()) put("marks", JSONArray().apply { marks.forEach { put(it) } })
    }

    fun suggestedName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return "scribe-backup-$stamp.zip"
    }

    fun version(context: Context): String = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        "${p.versionName} ($code)"
    }.getOrDefault("unknown")
}
