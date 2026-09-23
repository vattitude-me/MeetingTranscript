import Foundation

/// Where everything lives. One folder, so "where is my data" has one answer
/// and deleting the app's data is deleting one directory.
public enum Paths {
    /// Overridable for tests and for the self-test, which must never touch the
    /// user's real library.
    public static var root: URL = {
        if let custom = ProcessInfo.processInfo.environment["MEETING_TRANSCRIPT_HOME"] {
            return URL(fileURLWithPath: custom, isDirectory: true)
        }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Meeting Transcript", isDirectory: true)
    }()

    /// Models are shared across libraries: the self-test uses a scratch
    /// library but the same 490 MB of models.
    public static var models: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Meeting Transcript/models", isDirectory: true)
    }

    public static var database: URL { root.appendingPathComponent("library.sqlite") }
    public static var audio: URL { root.appendingPathComponent("audio", isDirectory: true) }

    public static func audio(meeting id: Int64) -> URL {
        audio.appendingPathComponent("\(id)", isDirectory: true)
    }

    public static func audio(meeting id: Int64, source: Source) -> URL {
        audio.appendingPathComponent("\(id)/\(source.folder)", isDirectory: true)
    }

    public static func ensure(_ url: URL) {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }
}

/// The two sides of a call. Captured separately, so who said what is
/// bookkeeping rather than a guess.
public enum Source: Int, CaseIterable, Sendable {
    /// The microphone: the person at this Mac.
    case mic = 0
    /// System audio: everyone on the other end of the call.
    case system = 1

    public var folder: String { self == .mic ? "mic" : "system" }
    public var defaultName: String { self == .mic ? "You" : "Them" }
}
