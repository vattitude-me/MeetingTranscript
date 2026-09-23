import Foundation

/// The handoff artifacts, in the same formats the Android app writes, so a
/// transcript from either device reads the same to a person or a script.
public enum Exporters {
    public enum Format: String, CaseIterable, Identifiable, Sendable {
        case text, markdown, json, srt, vtt
        public var id: String { rawValue }
        public var ext: String {
            switch self {
            case .text: return "txt"
            case .markdown: return "md"
            case .json: return "json"
            case .srt: return "srt"
            case .vtt: return "vtt"
            }
        }
        public var title: String {
            switch self {
            case .text: return "Plain text"
            case .markdown: return "Markdown"
            case .json: return "JSON (for tools)"
            case .srt: return "Subtitles (SRT)"
            case .vtt: return "Subtitles (WebVTT)"
            }
        }
    }

    public static func render(_ f: Format, _ m: Meeting, _ lines: [Line], _ names: [Int: String]) -> String {
        switch f {
        case .text: return plainText(m, lines, names)
        case .markdown: return markdown(m, lines, names)
        case .json: return json(m, lines, names)
        case .srt: return srt(lines, names)
        case .vtt: return vtt(lines, names)
        }
    }

    public static func timestamp(_ ms: Int64) -> String {
        let total = max(0, ms) / 1000
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// On the Mac a label says which stream a line was heard on — this Mac's
    /// microphone or the call — which is a fact, not a guess about voices.
    public static func label(_ speaker: Int, _ names: [Int: String]) -> String? {
        guard speaker >= 0 else { return nil }
        if let n = names[speaker], !n.trimmingCharacters(in: .whitespaces).isEmpty { return n }
        return Source(rawValue: speaker)?.defaultName ?? "Speaker \(speaker + 1)"
    }

    public static func title(_ m: Meeting) -> String {
        m.title.isEmpty ? "Meeting on \(dateOf(m.startedAt))" : m.title
    }

    private static func prefix(_ l: Line, _ names: [Int: String]) -> String {
        label(l.speaker, names).map { "\($0): " } ?? ""
    }

    private static func dateOf(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: d)
    }

    /// Travels inside the file, because a file outlives the app that explains it.
    static let speakerCaveat =
        "\"You\" is what this Mac's microphone heard; \"Them\" is everyone heard " +
        "through the call. Several people on the call all appear as \"Them\"."

    public static func plainText(_ m: Meeting, _ lines: [Line], _ names: [Int: String] = [:]) -> String {
        var s = "\(title(m))\n\(dateOf(m.startedAt))  ·  \(timestamp(m.durationMs))\n"
        if lines.contains(where: { $0.speaker >= 0 }) { s += "\nNote: \(speakerCaveat)\n" }
        s += "\n"
        for l in lines { s += "[\(timestamp(l.tStartMs))] \(prefix(l, names))\(l.text)\n" }
        return s
    }

    public static func markdown(_ m: Meeting, _ lines: [Line], _ names: [Int: String] = [:]) -> String {
        var s = "# \(title(m))\n\n*\(dateOf(m.startedAt)) · \(timestamp(m.durationMs))*\n\n"
        if lines.contains(where: { $0.speaker >= 0 }) { s += "> **Note:** \(speakerCaveat)\n\n" }
        for l in lines {
            let who = label(l.speaker, names).map { "**\($0)** " } ?? ""
            s += "- `[\(timestamp(l.tStartMs))]` \(who)\(l.text)\n"
        }
        return s
    }

    /// Transcript plus the instructions, so handing a meeting to a chatbot is
    /// one paste. The line-number citation contract makes its answer checkable.
    public static func promptReady(_ m: Meeting, _ lines: [Line], _ names: [Int: String] = [:]) -> String {
        var s = """
        Below is a verbatim, timestamped transcript of a meeting.
        Each line is prefixed with its line number in square brackets.

        """
        if lines.contains(where: { $0.speaker >= 0 }) {
            let me = label(Source.mic.rawValue, names) ?? "You"
            let them = label(Source.system.rawValue, names) ?? "Them"
            s += """
            "\(me)" is the person who recorded the meeting. "\(them)" is everyone
            else on the call, who may be several people. Do not guess who they are.

            """
        }
        s += """

        Please:
        1. Summarise the meeting in a short paragraph.
        2. List the decisions made.
        3. List the action items, with an owner for each where one is identifiable.
        4. Note anything left unresolved.

        Cite the line numbers you drew each point from, like [12] or [12-15].
        If something is not in the transcript, say so rather than inferring it.
        The transcript is machine-generated and may contain recognition errors.

        ---
        Title: \(title(m))
        Date: \(dateOf(m.startedAt))
        Duration: \(timestamp(m.durationMs))
        ---


        """
        for (i, l) in lines.enumerated() {
            s += "[\(i + 1)] (\(timestamp(l.tStartMs))) \(prefix(l, names))\(l.text)\n"
        }
        return s
    }

    public static func srt(_ lines: [Line], _ names: [Int: String] = [:]) -> String {
        var s = ""
        for (i, l) in lines.enumerated() {
            s += "\(i + 1)\n\(srtTime(l.tStartMs)) --> \(srtTime(l.tEndMs))\n\(prefix(l, names))\(l.text)\n\n"
        }
        return s
    }

    public static func vtt(_ lines: [Line], _ names: [Int: String] = [:]) -> String {
        var s = "WEBVTT\n\n"
        for l in lines {
            let a = srtTime(l.tStartMs).replacingOccurrences(of: ",", with: ".")
            let b = srtTime(l.tEndMs).replacingOccurrences(of: ",", with: ".")
            s += "\(a) --> \(b)\n\(prefix(l, names))\(l.text)\n\n"
        }
        return s
    }

    static func srtTime(_ ms: Int64) -> String {
        String(format: "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms % 3_600_000) / 60_000, (ms % 60_000) / 1000, ms % 1000)
    }

    /// The contract with whatever summarises the meeting: `scribe.transcript.v1`,
    /// field for field the same as the Android export.
    public static func json(_ m: Meeting, _ lines: [Line], _ names: [Int: String] = [:]) -> String {
        var s = "{\n"
        s += "  \"schema\": \"scribe.transcript.v1\",\n"
        s += "  \"title\": \(quote(title(m))),\n"
        s += "  \"started_at\": \(Int64(m.startedAt.timeIntervalSince1970 * 1000)),\n"
        s += "  \"duration_ms\": \(m.durationMs),\n"
        s += "  \"language\": \"en\",\n"
        s += "  \"asr_model\": \(quote(m.asrModel ?? "")),\n"
        s += "  \"lines\": [\n"
        for (i, l) in lines.enumerated() {
            let comma = i == lines.count - 1 ? "" : ","
            s += "    {\"i\": \(i + 1), \"t_start_ms\": \(l.tStartMs), \"t_end_ms\": \(l.tEndMs), "
            s += "\"speaker\": \(l.speaker), \"speaker_name\": \(quote(label(l.speaker, names) ?? "")), "
            s += "\"text\": \(quote(l.text)), \"confidence\": 1.000}\(comma)\n"
        }
        s += "  ]\n}"
        return s
    }

    static func quote(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        return out + "\""
    }

    public static func safeName(_ title: String) -> String {
        let s = title.replacingOccurrences(of: "[^A-Za-z0-9._ -]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "- "))
        return s.isEmpty ? "meeting" : s
    }
}
