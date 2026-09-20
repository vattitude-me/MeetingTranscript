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
    val error: String?
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
    val confidence: Double
)

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
              error TEXT
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
              FOREIGN KEY(meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lines_meeting ON lines(meeting_id, idx)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v0.1: no shipped migrations yet.
        db.execSQL("DROP TABLE IF EXISTS lines")
        db.execSQL("DROP TABLE IF EXISTS meetings")
        onCreate(db)
    }

    companion object {
        private const val NAME = "scribe.db"
        private const val VERSION = 1

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
        db.writableDatabase.delete("lines", "meeting_id=?", arrayOf(id.toString()))
        db.writableDatabase.delete("meetings", "id=?", arrayOf(id.toString()))
        m?.segmentDir?.deleteRecursively()
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
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Drops every line of a meeting, keeping the audio and the meeting row.
     * Used when the accurate pass is about to replace the rough live transcript.
     */
    fun clearLines(meetingId: Long) {
        db.writableDatabase.delete("lines", "meeting_id=?", arrayOf(meetingId.toString()))
    }

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
        error = strOrNull("error")
    )

    private fun android.database.Cursor.toLine() = Line(
        id = long("id"),
        meetingId = long("meeting_id"),
        idx = int("idx"),
        tStartMs = long("t_start_ms"),
        tEndMs = long("t_end_ms"),
        text = str("text"),
        confidence = getDouble(getColumnIndexOrThrow("confidence"))
    )
}
