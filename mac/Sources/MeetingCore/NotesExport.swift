import Foundation

/// Writes each finished meeting as a Markdown note into a folder the user
/// picked: an Obsidian vault, iCloud Drive, a Notes export folder. The
/// transcript then turns up where the user already works, with nothing to
/// export by hand.
///
/// One file per meeting, rewritten whenever the meeting changes (renamed,
/// a speaker named, a line deleted), so the note never goes stale. The file
/// carries `meeting_id` in its front matter. That is how a rewrite finds its
/// own file after a rename, and the only thing that ever lets this code delete
/// a file: a file in that folder without our id is the user's, and is never
/// touched.
public enum NotesExport {
    public static func fileName(_ m: Meeting) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HHmm"
        let title = m.title.isEmpty ? "Meeting" : m.title
        return "\(f.string(from: m.startedAt)) \(sanitize(title)).md"
    }

    /// Characters Finder, iCloud or Obsidian refuse or misread in a name, and
    /// runs of whitespace, flattened; capped so a pasted paragraph does not
    /// become a file name.
    static func sanitize(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|#^[]").union(.newlines).union(.controlCharacters)
        let cleaned = s.unicodeScalars.map { bad.contains($0) ? " " : String($0) }.joined()
        let words = cleaned.split(whereSeparator: { $0.isWhitespace })
        let joined = words.joined(separator: " ")
        let capped = String(joined.prefix(80)).trimmingCharacters(in: .whitespaces.union(CharacterSet(charactersIn: ".")))
        return capped.isEmpty ? "Meeting" : capped
    }

    public static func document(_ m: Meeting, _ lines: [Line], _ names: [Int: String]) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        iso.timeZone = .current
        var s = "---\n"
        s += "title: \(yamlString(Exporters.title(m)))\n"
        s += "date: \(iso.string(from: m.startedAt))\n"
        s += "duration: \(yamlString(Exporters.timestamp(m.durationMs)))\n"
        s += "meeting_id: \(m.id)\n"
        s += "source: Meeting Transcript\n"
        s += "---\n\n"
        s += Exporters.markdown(m, lines, names)
        return s
    }

    static func yamlString(_ s: String) -> String {
        var out = "\""
        for ch in s {
            switch ch {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n", "\r": out += " "
            default: out.append(ch)
            }
        }
        return out + "\""
    }

    /// The meeting id a note was written for, or nil if it is not one of ours.
    public static func meetingId(inNoteAt url: URL) -> Int64? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        guard let data = try? h.read(upToCount: 2048), let head = String(data: data, encoding: .utf8),
              head.hasPrefix("---\n") else { return nil }
        for line in head.split(separator: "\n").prefix(12) where line.hasPrefix("meeting_id: ") {
            return Int64(line.dropFirst("meeting_id: ".count).trimmingCharacters(in: .whitespaces))
        }
        return nil
    }

    /// Writes the note and returns where it went. [previous] is where this
    /// meeting's note was last written; if the name has changed since, that
    /// file is removed, but only if it still carries this meeting's id.
    @discardableResult
    public static func write(_ m: Meeting, _ lines: [Line], _ names: [Int: String],
                             to folder: URL, previous: URL? = nil) throws -> URL {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var url = folder.appendingPathComponent(fileName(m))
        // Two meetings in the same minute with the same title: keep both.
        if FileManager.default.fileExists(atPath: url.path), meetingId(inNoteAt: url) != m.id {
            url = folder.appendingPathComponent("\(fileName(m).dropLast(3)) (\(m.id)).md")
        }
        try document(m, lines, names).write(to: url, atomically: true, encoding: .utf8)
        if let old = previous, old.standardizedFileURL != url.standardizedFileURL,
           FileManager.default.fileExists(atPath: old.path), meetingId(inNoteAt: old) == m.id {
            try? FileManager.default.removeItem(at: old)
        }
        return url
    }
}
