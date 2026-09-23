package me.vattitude.scribe.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Meeting lifecycle. The recorder only ever moves a meeting to [RECORDED];
 * transcription is a separate, resumable job (see TranscribeWorker), so an ASR
 * failure can never cost us audio.
 */
object MeetingState {
    const val RECORDING = "recording"
    const val RECORDED = "recorded"
    const val TRANSCRIBING = "transcribing"
    const val DONE = "done"
    const val FAILED = "failed"
}

data class Meeting(
    val id: Long,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long,
    val dir: String,
    val state: String,
    val asrModelId: String?,
    val segmentsDone: Int,
    val segmentCount: Int,
    val error: String?,
    /** Voices the user said to expect, or 0 when unknown. See Repo.setExpectedSpeakers. */
    val expectedSpeakers: Int = 0
) {
    val segmentDir: File get() = File(dir)
}

data class Line(
    val id: Long,
    val meetingId: Long,
    val idx: Int,
    val tStartMs: Long,
    val tEndMs: Long,
    val text: String,
    val confidence: Double,
    /** Diarization cluster, or -1 when the meeting has not been diarized. */
    val speaker: Int = -1
)

/** A moment the user flagged while recording, in milliseconds of audio. */
data class Mark(val id: Long, val meetingId: Long, val tMs: Long)

class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE meetings (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              started_at INTEGER NOT NULL,
              ended_at INTEGER,
              duration_ms INTEGER NOT NULL DEFAULT 0,
              dir TEXT NOT NULL,
              state TEXT NOT NULL,
              asr_model_id TEXT,
              segments_done INTEGER NOT NULL DEFAULT 0,
              segment_count INTEGER NOT NULL DEFAULT 0,
              error TEXT,
              expected_speakers INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE lines (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              meeting_id INTEGER NOT NULL,
              idx INTEGER NOT NULL,
              t_start_ms INTEGER NOT NULL,
              t_end_ms INTEGER NOT NULL,
              text TEXT NOT NULL,
              confidence REAL NOT NULL DEFAULT 0,
              speaker INTEGER NOT NULL DEFAULT -1,
              FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lines_meeting ON lines(meeting_id, idx)")
        db.execSQL(
            """
            CREATE TABLE speakers (
              meeting_id INTEGER NOT NULL,
              speaker INTEGER NOT NULL,
              name TEXT,
              PRIMARY KEY(meeting_id, speaker),
              FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        createMarks(db)
    }

    private fun createMarks(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS marks (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              meeting_id INTEGER NOT NULL,
              t_ms INTEGER NOT NULL,
              FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_marks_meeting ON marks(meeting_id, t_ms)")
    }

    /**
     * Migrations are additive from here. The app is going to be in people's
     * hands, and a meeting they recorded is not reproducible — dropping the
     * table on upgrade would destroy the only copy of something they cannot
     * record again.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE lines ADD COLUMN speaker INTEGER NOT NULL DEFAULT -1")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS speakers (
                  meeting_id INTEGER NOT NULL,
                  speaker INTEGER NOT NULL,
                  name TEXT,
                  PRIMARY KEY(meeting_id, speaker),
                  FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
        if (oldV < 3) {
            // How many voices the user said to expect. 0 means they were not
            // asked or did not know, and clustering falls back to a threshold.
            db.execSQL("ALTER TABLE meetings ADD COLUMN expected_speakers INTEGER NOT NULL DEFAULT 0")
        }
        if (oldV < 4) {
            // Moments flagged with Mark while recording.
            createMarks(db)
        }
    }

    companion object {
        private const val NAME = "scribe.db"
        private const val VERSION = 4

        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context).also { instance = it } }
    }
}

class Repo(context: Context) {
    private val db = Db.get(context)

    fun createMeeting(title: String, startedAt: Long, dir: File): Long {
        val v = ContentValues().apply {
            put("title", title)
            put("started_at", startedAt)
            put("dir", dir.absolutePath)
            put("state", MeetingState.RECORDING)
        }
        return db.writableDatabase.insertOrThrow("meetings", null, v)
    }

    fun finishRecording(id: Long, endedAt: Long, durationMs: Long, segmentCount: Int) {
        val v = ContentValues().apply {
            put("ended_at", endedAt)
            put("duration_ms", durationMs)
            put("segment_count", segmentCount)
            put("state", MeetingState.RECORDED)
        }
        db.writableDatabase.update("meetings", v, "id=?", arrayOf(id.toString()))
    }

    fun setState(id: Long, state: String, error: String? = null) {
        val v = ContentValues().apply {
            put("state", state)
            put("error", error)
        }
        db.writableDatabase.update("meetings", v, "id=?", arrayOf(id.toString()))
    }

    fun setProgress(id: Long, segmentsDone: Int, asrModelId: String?) {
        val v = ContentValues().apply {
            put("segments_done", segmentsDone)
            asrModelId?.let { put("asr_model_id", it) }
        }
        db.writableDatabase.update("meetings", v, "id=?", arrayOf(id.toString()))
    }

    fun rename(id: Long, title: String) {
        db.writableDatabase.update("meetings", ContentValues().apply { put("title", title) },
            "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        val m = meeting(id)
        val arg = arrayOf(id.toString())
        // Foreign keys are not enforced on this connection, so the cascade in
        // the schema is documentation; delete the children by hand.
        db.writableDatabase.delete("lines", "meeting_id=?", arg)
        db.writableDatabase.delete("speakers", "meeting_id=?", arg)
        db.writableDatabase.delete("marks", "meeting_id=?", arg)
        db.writableDatabase.delete("meetings", "id=?", arg)
        m?.segmentDir?.deleteRecursively()
    }

    /** Drops every meeting, line and audio file. The models stay — they are a 615 MB re-download. */
    fun deleteEverything() {
        val dirs = meetings().map { it.segmentDir }
        db.writableDatabase.delete("lines", null, null)
        db.writableDatabase.delete("speakers", null, null)
        db.writableDatabase.delete("marks", null, null)
        db.writableDatabase.delete("meetings", null, null)
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    /** Deletes only the audio, keeping transcripts. Used by the storage row in Settings. */
    fun deleteAllAudio() {
        meetings().forEach { runCatching { it.segmentDir.deleteRecursively() } }
    }

    fun appendLines(meetingId: Long, lines: List<Line>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (l in lines) {
                w.insertOrThrow("lines", null, ContentValues().apply {
                    put("meeting_id", meetingId)
                    put("idx", l.idx)
                    put("t_start_ms", l.tStartMs)
                    put("t_end_ms", l.tEndMs)
                    put("text", l.text)
                    put("confidence", l.confidence)
                    put("speaker", l.speaker)
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Writes the speaker label onto each line in one transaction. Diarization
     * runs after the text exists, so this updates rows rather than inserting.
     */
    fun setLineSpeakers(meetingId: Long, byIdx: Map<Int, Int>) {
        if (byIdx.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for ((idx, speaker) in byIdx) {
                w.update(
                    "lines",
                    ContentValues().apply { put("speaker", speaker) },
                    "meeting_id=? AND idx=?",
                    arrayOf(meetingId.toString(), idx.toString())
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Records how many voices the user says to expect, or 0 for "I don't know".
     *
     * This is worth asking for. Clustering by similarity threshold is
     * unreliable — measured across single- and two-speaker recordings, no
     * threshold in 0.2..0.9 produced the right count for any of them, while
     * telling the clusterer the number produced the right answer for all of
     * them. A number the user supplies is the difference between speaker labels
     * that are right and speaker labels that are decorative.
     */
    fun setExpectedSpeakers(meetingId: Long, count: Int) {
        db.writableDatabase.update(
            "meetings",
            ContentValues().apply { put("expected_speakers", count.coerceAtLeast(0)) },
            "id=?",
            arrayOf(meetingId.toString())
        )
    }

    /** User-given names for a meeting's speakers, keyed by cluster index. */
    fun speakerNames(meetingId: Long): Map<Int, String> =
        db.readableDatabase.rawQuery(
            "SELECT speaker, name FROM speakers WHERE meeting_id=? AND name IS NOT NULL",
            arrayOf(meetingId.toString())
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
        }

    /** Names a clustered voice. The model never does this — only the user can. */
    fun nameSpeaker(meetingId: Long, speaker: Int, name: String?) {
        db.writableDatabase.insertWithOnConflict(
            "speakers",
            null,
            ContentValues().apply {
                put("meeting_id", meetingId)
                put("speaker", speaker)
                put("name", name)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Drops every line of a meeting, keeping the audio and the meeting row.
     * Used when the accurate pass is about to replace the rough live transcript.
     */
    fun clearLines(meetingId: Long) {
        db.writableDatabase.delete("lines", "meeting_id=?", arrayOf(meetingId.toString()))
    }

    fun addMark(meetingId: Long, tMs: Long): Long =
        db.writableDatabase.insertOrThrow("marks", null, ContentValues().apply {
            put("meeting_id", meetingId)
            put("t_ms", tMs)
        })

    fun deleteMark(id: Long) {
        db.writableDatabase.delete("marks", "id=?", arrayOf(id.toString()))
    }

    fun marks(meetingId: Long): List<Mark> =
        db.readableDatabase.rawQuery(
            "SELECT id, meeting_id, t_ms FROM marks WHERE meeting_id=? ORDER BY t_ms",
            arrayOf(meetingId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(Mark(c.getLong(0), c.getLong(1), c.getLong(2))) } }

    fun lineCount(meetingId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM lines WHERE meeting_id=?", arrayOf(meetingId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun nextLineIdx(meetingId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(idx), -1) + 1 FROM lines WHERE meeting_id=?",
            arrayOf(meetingId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Searches titles and transcript text together — "the meeting where someone
     * said X" is the question you actually have, and it is rarely the title.
     */
    fun search(query: String): List<Meeting> {
        val q = "%" + query.trim() + "%"
        return db.readableDatabase.rawQuery(
            """
            SELECT DISTINCT m.* FROM meetings m
            LEFT JOIN lines l ON l.meeting_id = m.id
            WHERE m.title LIKE ? OR l.text LIKE ?
            ORDER BY m.started_at DESC
            """.trimIndent(),
            arrayOf(q, q)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMeeting()) } }
    }

    /**
     * First line of each finished transcript, for the list. One query rather than
     * one per row: the list is rebuilt on every worker transition, and a query
     * per visible row turns a five-row list into six round trips.
     */
    fun snippets(): Map<Long, String> =
        db.readableDatabase.rawQuery(
            """SELECT meeting_id, text FROM lines
               WHERE idx = (SELECT MIN(idx) FROM lines l2 WHERE l2.meeting_id = lines.meeting_id)""".trimIndent(),
            null
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }

    /**
     * The first line of each meeting that contains [query], so a search result
     * shows why it matched instead of the meeting's opening words.
     */
    fun matchSnippets(query: String): Map<Long, String> {
        val q = "%" + query.trim() + "%"
        return db.readableDatabase.rawQuery(
            """SELECT meeting_id, text FROM lines
               WHERE text LIKE ? AND idx = (
                 SELECT MIN(idx) FROM lines l2
                 WHERE l2.meeting_id = lines.meeting_id AND l2.text LIKE ?)""".trimIndent(),
            arrayOf(q, q)
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
    }

    fun meetings(): List<Meeting> =
        db.readableDatabase.rawQuery("SELECT * FROM meetings ORDER BY started_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toMeeting()) } }

    fun meeting(id: Long): Meeting? =
        db.readableDatabase.rawQuery("SELECT * FROM meetings WHERE id=?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toMeeting() else null }

    fun lines(meetingId: Long): List<Line> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM lines WHERE meeting_id=? ORDER BY idx", arrayOf(meetingId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toLine()) } }

    /** Meetings left mid-recording by a crash or a kill — offered for recovery on launch. */
    fun orphaned(): List<Meeting> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM meetings WHERE state=? ORDER BY started_at DESC",
            arrayOf(MeetingState.RECORDING)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMeeting()) } }

    private fun android.database.Cursor.str(n: String) = getString(getColumnIndexOrThrow(n))
    private fun android.database.Cursor.strOrNull(n: String) =
        getColumnIndexOrThrow(n).let { if (isNull(it)) null else getString(it) }
    private fun android.database.Cursor.long(n: String) = getLong(getColumnIndexOrThrow(n))
    private fun android.database.Cursor.longOrNull(n: String) =
        getColumnIndexOrThrow(n).let { if (isNull(it)) null else getLong(it) }
    private fun android.database.Cursor.int(n: String) = getInt(getColumnIndexOrThrow(n))

    private fun android.database.Cursor.toMeeting() = Meeting(
        id = long("id"),
        title = str("title"),
        startedAt = long("started_at"),
        endedAt = longOrNull("ended_at"),
        durationMs = long("duration_ms"),
        dir = str("dir"),
        state = str("state"),
        asrModelId = strOrNull("asr_model_id"),
        segmentsDone = int("segments_done"),
        segmentCount = int("segment_count"),
        error = strOrNull("error"),
        expectedSpeakers = int("expected_speakers")
    )

    private fun android.database.Cursor.toLine() = Line(
        id = long("id"),
        meetingId = long("meeting_id"),
        idx = int("idx"),
        tStartMs = long("t_start_ms"),
        tEndMs = long("t_end_ms"),
        text = str("text"),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        speaker = int("speaker")
    )
}
