import AVFoundation
import Foundation
import MeetingCore

/// The same binary, driven from Terminal. Useful for checking a build without
/// clicking through the app, and for recording without any window at all.
enum CLI {
    static let commands = ["selftest", "models", "transcribe", "record", "list", "export", "help", "--help", "-h"]

    /// Runs a subcommand if one was given; nil means start the app.
    static func runIfCommand() -> Int32? {
        let args = Array(CommandLine.arguments.dropFirst())
        guard let cmd = args.first, commands.contains(cmd) else { return nil }
        let rest = Array(args.dropFirst())
        var code: Int32 = 0
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            do {
                switch cmd {
                case "selftest": code = try await selftest(rest)
                case "models": try await models()
                case "transcribe": try transcribe(rest)
                case "record": try await record(rest)
                case "list": list()
                case "export": try export(rest)
                default: help()
                }
            } catch {
                err("error: \(error.localizedDescription)")
                code = 1
            }
            done.signal()
        }
        done.wait()
        return code
    }

    static func help() {
        print("""
        Meeting Transcript — records meetings and transcribes them on this Mac.

        Run with no arguments to start the menu bar app. Or:

          models                  Download the speech model and voice detector (~490 MB, once)
          selftest [--keep]       Transcribe synthetic speech and report speed; checks the whole
                                  pipeline, echo removal included, in a scratch library
          transcribe <file>       Print the transcript of any audio file (wav, m4a, mp3, aiff…)
          record <seconds>        Record the call and your microphone headlessly, then transcribe
                                  into the library, as if Record had been pressed in the app
          list                    Meetings in the library
          export <id> [fmt]       Print a meeting as txt, md, json, srt, vtt or prompt

        Library: \(Paths.root.path)
        """)
    }

    static func err(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) }

    static func requireModels() throws {
        guard ModelFiles.ready else {
            throw SherpaError(message: "Models missing: \(ModelFiles.missing.map(\.fileName).joined(separator: ", ")). Run: MeetingTranscript models")
        }
    }

    // MARK: models

    static func models() async throws {
        if ModelFiles.ready { print("Models are installed in \(Paths.models.path)"); return }
        var lastLine = Date.distantPast
        try await ModelDownloader.downloadMissing { p in
            guard Date().timeIntervalSince(lastLine) > 0.5 else { return }
            lastLine = Date()
            let mb = { (b: Int64) in String(format: "%.0f", Double(b) / 1_000_000) }
            let rate = p.bytesPerSecond > 0 ? String(format: " · %.1f MB/s", p.bytesPerSecond / 1_000_000) : ""
            print("\r\(p.phase) \(Int(p.fraction * 100))%  \(mb(p.done))/\(mb(p.total)) MB\(rate)   ", terminator: "")
            fflush(stdout)
        }
        print("\nDone. Models are in \(Paths.models.path)")
    }

    // MARK: transcribe

    static func transcribe(_ args: [String]) throws {
        guard let path = args.first else { throw SherpaError(message: "usage: transcribe <audio file>") }
        try requireModels()
        let samples = try Audio.readAnyFile(URL(fileURLWithPath: path))
        let load = try Offline.load()
        let t0 = Date()
        let pieces = try Offline.transcribe(samples)
        let wall = Date().timeIntervalSince(t0)
        for p in pieces { print("[\(Exporters.timestamp(p.startMs))] \(p.text)") }
        let audio = Double(samples.count) / Double(Audio.sampleRate)
        err(String(format: "%.0f s of audio in %.1f s (%.0f× real time, model load %.1f s)", audio, wall, audio / max(wall, 0.001), load))
    }

    // MARK: record

    static func record(_ args: [String]) async throws {
        let seconds = Double(args.first ?? "") ?? 30
        if !ModelFiles.ready { err("Models missing: recording only; transcription waits for `models`.") }
        if Permissions.microphone == .notDetermined { _ = await Permissions.requestMicrophone() }
        var opts = Recording.Options()
        opts.voiceProcessing = UserDefaults.standard.bool(forKey: "voiceProcessing")
        let rec = try Recording(options: opts)
        let meter = Meter()
        rec.onLevel = { s, v in meter.add(s, v) }
        try await rec.start()
        for w in rec.warnings { err("warning: \(w)") }
        err("Recording meeting \(rec.meetingId) for \(Int(seconds)) s…")
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            let peak = meter.take()
            let you = peak[.mic] ?? 0, them = peak[.system] ?? 0
            err(String(format: "  %@  you %@  them %@", Exporters.timestamp(rec.elapsedMs), bar(you), bar(them)))
        }
        err("Stopping; finishing the transcript…")
        await rec.stop()
        let m = Store.shared.meeting(rec.meetingId)!
        print(Exporters.plainText(m, Store.shared.lines(m.id), Store.shared.names(m.id)))
        if let e = m.error { err("state: \(m.state.rawValue) — \(e)") }
    }

    /// Loudest level per source since it was last read.
    final class Meter: @unchecked Sendable {
        private let lock = NSLock()
        private var peak: [Source: Float] = [:]
        func add(_ s: Source, _ v: Float) { lock.lock(); peak[s] = max(peak[s] ?? 0, v); lock.unlock() }
        func take() -> [Source: Float] { lock.lock(); defer { peak = [:]; lock.unlock() }; return peak }
    }

    static func bar(_ rms: Float) -> String {
        let n = min(10, Int((rms * 60).rounded()))
        return String(repeating: "▮", count: n) + String(repeating: "·", count: 10 - n)
    }

    // MARK: library

    static func list() {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        let all = Store.shared.meetings()
        if all.isEmpty { print("No meetings yet. Record one from the menu bar, or with `record <seconds>`.") }
        for m in all {
            print("\(m.id)\t\(f.string(from: m.startedAt))\t\(Exporters.timestamp(m.durationMs))\t\(m.state.rawValue)\t\(Exporters.title(m))")
        }
    }

    static func export(_ args: [String]) throws {
        guard let id = args.first.flatMap(Int64.init), let m = Store.shared.meeting(id) else {
            throw SherpaError(message: "usage: export <id> [txt|md|json|srt|vtt|prompt] — see `list` for ids")
        }
        let lines = Store.shared.lines(id), names = Store.shared.names(id)
        let fmt = args.dropFirst().first ?? "txt"
        if fmt == "prompt" { print(Exporters.promptReady(m, lines, names)); return }
        guard let f = Exporters.Format.allCases.first(where: { $0.ext == fmt }) else {
            throw SherpaError(message: "unknown format \(fmt)")
        }
        print(Exporters.render(f, m, lines, names))
    }

    // MARK: selftest

    /// Proves the pipeline end to end with no microphone and no meeting:
    /// synthetic speech on the call side, a reply on the microphone side, and
    /// a quiet copy of the call leaking into the microphone the way laptop
    /// speakers do. Passes if both sides are transcribed in order and the leak
    /// is removed. Uses a scratch library; the real one is not touched.
    static func selftest(_ args: [String]) async throws -> Int32 {
        try requireModels()
        let home = FileManager.default.temporaryDirectory
            .appendingPathComponent("meeting-transcript-selftest-\(ProcessInfo.processInfo.processIdentifier)")
        // Paths.root reads this once, on first use; nothing has used it yet.
        setenv("MEETING_TRANSCRIPT_HOME", home.path, 1)
        precondition(Paths.root.path == home.path, "selftest must set its home before anything touches the library")
        Paths.ensure(home)
        print("Scratch library: \(home.path)")
        print(String(format: "Threads: %d of %d cores", Engine.threads, ProcessInfo.processInfo.activeProcessorCount))

        let themText = "Thanks for joining. The budget review moves to Thursday afternoon, and Priya will send the revised numbers before then."
        let youText = "That works for me. I will book the large conference room and share the agenda by Wednesday."
        let them = try speak(themText, to: home.appendingPathComponent("them.aiff"))
        let you = try speak(youText, to: home.appendingPathComponent("you.aiff"))

        let load = try Offline.load()
        print(String(format: "Model load: %.1f s", load))

        // Speed: a minute of speech, decoded the way a finished meeting is.
        var minute: [Float] = []
        while minute.count < Audio.sampleRate * 60 {
            minute += them + [Float](repeating: 0, count: Audio.sampleRate) + you + [Float](repeating: 0, count: Audio.sampleRate)
        }
        let t0 = Date()
        let pieces = try Offline.transcribe(minute)
        let wall = Date().timeIntervalSince(t0)
        let audioSec = Double(minute.count) / Double(Audio.sampleRate)
        print(String(format: "Speed: %.0f s of speech in %.2f s — %.0f× real time", audioSec, wall, audioSec / wall))
        print("First line: \(pieces.first?.text ?? "(none)")")

        // Pipeline: both sources through the recorder's own files and the
        // same transcriber a meeting uses.
        let store = Store.shared
        let id = store.createMeeting(startedAt: Date())
        let sys = try SegmentWriter(dir: Paths.audio(meeting: id, source: .system))
        let mic = try SegmentWriter(dir: Paths.audio(meeting: id, source: .mic))
        let sr = Audio.sampleRate
        let lead = [Int16](repeating: 0, count: sr)
        let themPcm = int16(them)
        let youPcm = int16(you)
        let gapAfterThem = [Int16](repeating: 0, count: sr * 2)
        // Call: silence, them, silence for the reply.
        try sys.write(lead + themPcm + gapAfterThem + [Int16](repeating: 0, count: youPcm.count + sr))
        // Mic: the call leaking in 150 ms late at a third of the volume, then the reply.
        let delay = [Int16](repeating: 0, count: sr * 150 / 1000)
        let leak = int16(them.map { $0 * 0.35 })
        let micPcm = lead + delay + leak + [Int16](repeating: 0, count: gapAfterThem.count - delay.count) + youPcm + [Int16](repeating: 0, count: sr)
        try mic.write(micPcm)
        sys.finish(); mic.finish()
        store.setDuration(id, ms: Audio.samplesToMs(micPcm.count))
        try Transcriber.transcribeFromDisk(meeting: id, store: store) { _ in }

        let lines = store.lines(id)
        print("\nTranscript:")
        for l in lines { print("  [\(Exporters.timestamp(l.tStartMs))] \(Exporters.label(l.speaker, [:])!): \(l.text)") }

        var failures: [String] = []
        let micLines = lines.filter { $0.speaker == Source.mic.rawValue }
        let sysLines = lines.filter { $0.speaker == Source.system.rawValue }
        if sysLines.isEmpty { failures.append("no line from the call") }
        if micLines.isEmpty { failures.append("no line from the microphone") }
        if micLines.contains(where: { $0.text.lowercased().contains("budget") }) {
            failures.append("the call leaking into the microphone was not removed")
        }
        if let a = sysLines.first, let b = micLines.first, a.tStartMs > b.tStartMs {
            failures.append("lines out of order")
        }
        let words = Set(lines.map(\.text).joined(separator: " ").lowercased().split { !$0.isLetter }.map(String.init))
        for w in ["budget", "thursday", "conference", "agenda"] where !words.contains(w) {
            failures.append("missed the word \"\(w)\"")
        }
        if failures.isEmpty {
            print("\nPASS")
        } else {
            print("\nFAIL: " + failures.joined(separator: "; "))
        }
        if !args.contains("--keep") { print("(scratch library left in the system temp folder; macOS clears it)") }
        return failures.isEmpty ? 0 : 1
    }

    static func int16(_ f: [Float]) -> [Int16] {
        f.map { Int16(max(-1, min(1, $0)) * 32767) }
    }

    /// Text to speech with the Mac's own `say`, read back as 16 kHz mono.
    static func speak(_ text: String, to url: URL) throws -> [Float] {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/say")
        p.arguments = ["-o", url.path, text]
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw SherpaError(message: "`say` failed") }
        return try Audio.readAnyFile(url)
    }
}
