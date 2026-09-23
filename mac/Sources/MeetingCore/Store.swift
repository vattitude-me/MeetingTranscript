import Foundation
import SQLite3

public enum MeetingState: String, Sendable {
    /// Audio is being captured now.
    case recording
    /// Audio is on disk and waiting to be transcribed (models missing, or
    /// the app quit before it finished).
    case recorded
    case transcribing
    case done
    case failed
}

public struct Meeting: Identifiable, Hashable, Sendable {
    public let id: Int64
    public var title: String
    public var startedAt: Date
    public var durationMs: Int64
    public var state: MeetingState
    public var error: String?
    public var asrModel: String?
    /// While searching: the line that matched, so the row shows why it is here.
    public var snippet: String?
}

public struct Line: Identifiable, Hashable, Sendable {
    public let id: Int64
    public let meetingId: Int64
    public var tStartMs: Int64
    public var tEndMs: Int64
    public var text: String
    /// 0 = microphone ("You"), 1 = system audio ("Them").
    public var speaker: Int
}

public struct Mark: Identifiable, Hashable, Sendable {
    public let id: Int64
    public let meetingId: Int64
    public let tMs: Int64
}

public extension Notification.Name {
    /// Posted on the main queue with the meeting id (or nil for the list).
    static let storeChanged = Notification.Name("MeetingTranscript.storeChanged")
}

/// The library: meetings, their lines, marks and speaker names, in SQLite.
/// The same tables as the Android app's Db, so the two stay easy to reason
/// about together. One connection behind a serial queue.
public final class Store: @unchecked Sendable {
    public static let shared = Store(url: Paths.database)

    private var db: OpaquePointer?
    private let q = DispatchQueue(label: "store")
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    public init(url: URL) {
        Paths.ensure(url.deletingLastPathComponent())
        if sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            fatalError("Cannot open library at \(url.path)")
        }
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA foreign_keys=ON")
        exec("""
        CREATE TABLE IF NOT EXISTS meetings (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          title TEXT NOT NULL DEFAULT '',
          started_at INTEGER NOT NULL,
          duration_ms INTEGER NOT NULL DEFAULT 0,
          state TEXT NOT NULL,
          error TEXT,
          asr_model TEXT
        )
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS lines (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          meeting_id INTEGER NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
          t_start_ms INTEGER NOT NULL,
          t_end_ms INTEGER NOT NULL,
          text TEXT NOT NULL,
          speaker INTEGER NOT NULL DEFAULT -1
        )
        """)
        exec("CREATE INDEX IF NOT EXISTS lines_meeting ON lines(meeting_id, t_start_ms)")
        exec("""
        CREATE TABLE IF NOT EXISTS marks (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          meeting_id INTEGER NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
          t_ms INTEGER NOT NULL
        )
        """)
        exec("""
        CREATE TABLE IF NOT EXISTS speaker_names (
          meeting_id INTEGER NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
          speaker INTEGER NOT NULL,
          name TEXT NOT NULL,
          PRIMARY KEY (meeting_id, speaker)
        )
        """)
    }

    // MARK: meetings

    public func createMeeting(startedAt: Date) -> Int64 {
        let id: Int64 = q.sync {
            run("INSERT INTO meetings (started_at, state) VALUES (?, ?)",
                [Int64(startedAt.timeIntervalSince1970 * 1000), MeetingState.recording.rawValue])
            return sqlite3_last_insert_rowid(db)
        }
        changed(nil)
        return id
    }

    public func meeting(_ id: Int64) -> Meeting? {
        q.sync { query("SELECT \(Store.meetingCols) FROM meetings WHERE id = ?", [id], Store.readMeeting).first }
    }

    /// Newest first. With a query, meetings whose title or any line matches,
    /// each carrying the first matching line as its snippet.
    public func meetings(matching text: String = "") -> [Meeting] {
        let t = text.trimmingCharacters(in: .whitespaces)
        return q.sync {
            if t.isEmpty {
                return query("SELECT \(Store.meetingCols) FROM meetings ORDER BY started_at DESC", [], Store.readMeeting)
            }
            let like = "%" + t.replacingOccurrences(of: "%", with: "\\%").replacingOccurrences(of: "_", with: "\\_") + "%"
            let sql = """
            SELECT \(Store.meetingCols),
              (SELECT text FROM lines l WHERE l.meeting_id = m.id AND l.text LIKE ?1 ESCAPE '\\'
               ORDER BY t_start_ms LIMIT 1) AS snip
            FROM meetings m
            WHERE m.title LIKE ?1 ESCAPE '\\'
               OR EXISTS (SELECT 1 FROM lines l WHERE l.meeting_id = m.id AND l.text LIKE ?1 ESCAPE '\\')
            ORDER BY started_at DESC
            """
            return query(sql, [like]) { s in
                var m = Store.readMeeting(s)
                m.snippet = Store.text(s, 7)
                return m
            }
        }
    }

    public func setState(_ id: Int64, _ state: MeetingState, error: String? = nil) {
        q.sync { run("UPDATE meetings SET state = ?, error = ? WHERE id = ?", [state.rawValue, error as Any, id]) }
        changed(id)
    }

    public func setDuration(_ id: Int64, ms: Int64) {
        q.sync { run("UPDATE meetings SET duration_ms = ? WHERE id = ?", [ms, id]) }
        changed(id)
    }

    public func setModel(_ id: Int64, _ model: String) {
        q.sync { run("UPDATE meetings SET asr_model = ? WHERE id = ?", [model, id]) }
    }

    public func rename(_ id: Int64, to title: String) {
        q.sync { run("UPDATE meetings SET title = ? WHERE id = ?", [title.trimmingCharacters(in: .whitespacesAndNewlines), id]) }
        changed(id)
    }

    /// Removes the meeting's rows. The caller removes its audio folder.
    public func delete(_ id: Int64) {
        q.sync { run("DELETE FROM meetings WHERE id = ?", [id]) }
        changed(nil)
    }

    // MARK: lines

    @discardableResult
    public func insertLine(meeting: Int64, startMs: Int64, endMs: Int64, text: String, speaker: Int) -> Line {
        let id: Int64 = q.sync {
            run("INSERT INTO lines (meeting_id, t_start_ms, t_end_ms, text, speaker) VALUES (?, ?, ?, ?, ?)",
                [meeting, startMs, endMs, text, Int64(speaker)])
            return sqlite3_last_insert_rowid(db)
        }
        changed(meeting)
        return Line(id: id, meetingId: meeting, tStartMs: startMs, tEndMs: endMs, text: text, speaker: speaker)
    }

    public func deleteLine(_ id: Int64, meeting: Int64) {
        q.sync { run("DELETE FROM lines WHERE id = ?", [id]) }
        changed(meeting)
    }

    public func clearLines(_ meeting: Int64) {
        q.sync { run("DELETE FROM lines WHERE meeting_id = ?", [meeting]) }
        changed(meeting)
    }

    /// In spoken order. Ties go to the far end, whose words usually prompted
    /// the reply.
    public func lines(_ meeting: Int64) -> [Line] {
        q.sync {
            query("SELECT id, meeting_id, t_start_ms, t_end_ms, text, speaker FROM lines WHERE meeting_id = ? ORDER BY t_start_ms, speaker DESC, id",
                  [meeting]) { s in
                Line(id: sqlite3_column_int64(s, 0), meetingId: sqlite3_column_int64(s, 1),
                     tStartMs: sqlite3_column_int64(s, 2), tEndMs: sqlite3_column_int64(s, 3),
                     text: Store.text(s, 4) ?? "", speaker: Int(sqlite3_column_int64(s, 5)))
            }
        }
    }

    public func lineCount(_ meeting: Int64) -> Int {
        q.sync { query("SELECT COUNT(*) FROM lines WHERE meeting_id = ?", [meeting]) { Int(sqlite3_column_int64($0, 0)) }.first ?? 0 }
    }

    // MARK: marks

    public func addMark(_ meeting: Int64, atMs: Int64) {
        q.sync { run("INSERT INTO marks (meeting_id, t_ms) VALUES (?, ?)", [meeting, atMs]) }
        changed(meeting)
    }

    public func deleteMark(_ id: Int64, meeting: Int64) {
        q.sync { run("DELETE FROM marks WHERE id = ?", [id]) }
        changed(meeting)
    }

    public func marks(_ meeting: Int64) -> [Mark] {
        q.sync {
            query("SELECT id, meeting_id, t_ms FROM marks WHERE meeting_id = ? ORDER BY t_ms", [meeting]) {
                Mark(id: sqlite3_column_int64($0, 0), meetingId: sqlite3_column_int64($0, 1), tMs: sqlite3_column_int64($0, 2))
            }
        }
    }

    // MARK: speaker names

    public func names(_ meeting: Int64) -> [Int: String] {
        let rows: [(Int, String)] = q.sync {
            query("SELECT speaker, name FROM speaker_names WHERE meeting_id = ?", [meeting]) {
                (Int(sqlite3_column_int64($0, 0)), Store.text($0, 1) ?? "")
            }
        }
        var out: [Int: String] = [:]
        for (k, v) in rows { out[k] = v }
        return out
    }

    public func setName(_ meeting: Int64, speaker: Int, name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        q.sync {
            if n.isEmpty {
                run("DELETE FROM speaker_names WHERE meeting_id = ? AND speaker = ?", [meeting, Int64(speaker)])
            } else {
                run("INSERT OR REPLACE INTO speaker_names (meeting_id, speaker, name) VALUES (?, ?, ?)",
                    [meeting, Int64(speaker), n])
            }
        }
        changed(meeting)
    }

    // MARK: plumbing

    private static let meetingCols = "id, title, started_at, duration_ms, state, error, asr_model"

    private static func readMeeting(_ s: OpaquePointer) -> Meeting {
        Meeting(id: sqlite3_column_int64(s, 0), title: text(s, 1) ?? "",
                startedAt: Date(timeIntervalSince1970: Double(sqlite3_column_int64(s, 2)) / 1000),
                durationMs: sqlite3_column_int64(s, 3),
                state: MeetingState(rawValue: text(s, 4) ?? "") ?? .failed,
                error: text(s, 5), asrModel: text(s, 6), snippet: nil)
    }

    private static func text(_ s: OpaquePointer, _ i: Int32) -> String? {
        guard let c = sqlite3_column_text(s, i) else { return nil }
        return String(cString: c)
    }

    public func changed(_ id: Int64?) {
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .storeChanged, object: id)
        }
    }

    private func exec(_ sql: String) {
        if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK {
            NSLog("sqlite: %@ — %@", String(cString: sqlite3_errmsg(db)), sql)
        }
    }

    private func bind(_ s: OpaquePointer, _ args: [Any]) {
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case let v as Int64: sqlite3_bind_int64(s, idx, v)
            case let v as Int: sqlite3_bind_int64(s, idx, Int64(v))
            case let v as String: sqlite3_bind_text(s, idx, v, -1, Store.transient)
            case let v as Double: sqlite3_bind_double(s, idx, v)
            default: sqlite3_bind_null(s, idx)
            }
        }
    }

    private func run(_ sql: String, _ args: [Any]) {
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK, let s else {
            NSLog("sqlite prepare: %@", String(cString: sqlite3_errmsg(db)))
            return
        }
        defer { sqlite3_finalize(s) }
        bind(s, args)
        if sqlite3_step(s) != SQLITE_DONE {
            NSLog("sqlite step: %@", String(cString: sqlite3_errmsg(db)))
        }
    }

    private func query<T>(_ sql: String, _ args: [Any], _ read: (OpaquePointer) -> T) -> [T] {
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK, let s else {
            NSLog("sqlite prepare: %@", String(cString: sqlite3_errmsg(db)))
            return []
        }
        defer { sqlite3_finalize(s) }
        bind(s, args)
        var out: [T] = []
        while sqlite3_step(s) == SQLITE_ROW { out.append(read(s)) }
        return out
    }
}
