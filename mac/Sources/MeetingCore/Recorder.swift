import Foundation

/// One source's path to disk: lines its audio up against the recording's
/// clock, writes segments, and hands the same samples to the transcriber.
///
/// The microphone and ScreenCaptureKit run on different clocks and start at
/// different moments, and system audio can go quiet for stretches. Placing
/// every buffer by its host timestamp — padding gaps with silence — keeps
/// sample N of both files at the same instant, so "You" and "Them" lines
/// interleave in the order they were said.
final class Track {
    let source: Source
    private let writer: SegmentWriter
    private let start: Double
    private let queue: DispatchQueue
    private weak var transcriber: Transcriber?
    var onLevel: ((Source, Float) -> Void)?
    private(set) var error: Error?

    /// Gaps shorter than this are clock jitter, not missing audio.
    private static let jitter = Audio.sampleRate / 10

    init(source: Source, meeting: Int64, start: Double, transcriber: Transcriber?) throws {
        self.source = source
        self.start = start
        self.transcriber = transcriber
        writer = try SegmentWriter(dir: Paths.audio(meeting: meeting, source: source))
        queue = DispatchQueue(label: "track.\(source.folder)", qos: .userInitiated)
    }

    var written: Int { queue.sync { writer.totalSamples } }

    func accept(_ samples: [Int16], host: Double) {
        queue.async { self.place(samples, host: host) }
    }

    private func place(_ samples: [Int16], host: Double) {
        var samples = samples
        let at = Int(((host - start) * Double(Audio.sampleRate)).rounded())
        // Audio from before Record was pressed (a buffer already in flight).
        if at < 0 {
            let drop = min(samples.count, -at)
            samples.removeFirst(drop)
            if samples.isEmpty { return }
        }
        let gap = max(0, at) - writer.totalSamples
        if gap > Track.jitter { append([Int16](repeating: 0, count: gap)) }
        append(samples)
        onLevel?(source, Toolbox.rms(samples))
    }

    private func append(_ samples: [Int16]) {
        do {
            try writer.write(samples)
            transcriber?.feed(source, samples)
        } catch {
            self.error = error
        }
    }

    /// Pads to [total] samples so both files end together, then closes.
    func finish(total: Int) {
        queue.sync {
            let gap = total - writer.totalSamples
            if gap > 0 { append([Int16](repeating: 0, count: gap)) }
            writer.finish()
        }
    }
}

/// A meeting being recorded. Create one, `start()`, and `stop()`.
public final class Recording: @unchecked Sendable {
    public let meetingId: Int64
    public let startedAt: Date
    private let store: Store
    private let startHost: Double
    private var tracks: [Source: Track] = [:]
    private var system: SystemAudioCapture?
    private var mic: MicCapture?
    public private(set) var transcriber: Transcriber?
    /// What could not be captured, said plainly, e.g. no Screen Recording access.
    public private(set) var warnings: [String] = []
    public var onLevel: ((Source, Float) -> Void)?
    /// ScreenCaptureKit stopped on its own (display asleep, permission revoked).
    public var onSystemStopped: ((Error) -> Void)?

    public var elapsedMs: Int64 { Int64((hostNow() - startHost) * 1000) }

    public struct Options: Sendable {
        public var captureSystem = true
        public var captureMic = true
        public var voiceProcessing = false
        public var filterEcho = true
        public init() {}
    }

    private let options: Options

    public init(options: Options = .init(), store: Store = .shared) throws {
        self.options = options
        self.store = store
        Paths.ensure(Paths.root)
        startedAt = Date()
        meetingId = store.createMeeting(startedAt: startedAt)
        startHost = hostNow()
        if ModelFiles.ready {
            transcriber = try? Transcriber(meeting: meetingId, store: store)
            transcriber?.filterEcho = options.filterEcho
            Engine.shared.preload()
        }
        for s in Source.allCases {
            let t = try Track(source: s, meeting: meetingId, start: startHost, transcriber: transcriber)
            t.onLevel = { [weak self] s, v in self?.onLevel?(s, v) }
            tracks[s] = t
        }
    }

    /// Starts whatever can be started. Throws only if neither side can be
    /// heard; one missing side is a warning, because half a meeting beats none.
    public func start() async throws {
        if options.captureSystem {
            let cap = SystemAudioCapture { [weak self] pcm, host in self?.tracks[.system]?.accept(pcm, host: host) }
            cap.onStop = { [weak self] e in self?.onSystemStopped?(e) }
            do {
                try await cap.start()
                system = cap
            } catch {
                warnings.append("The call's audio is not being recorded: \(Recording.explain(error)) Only your microphone is.")
            }
        }
        if options.captureMic {
            let cap = MicCapture(voiceProcessing: options.voiceProcessing) { [weak self] pcm, host in
                self?.tracks[.mic]?.accept(pcm, host: host)
            }
            do {
                try cap.start()
                mic = cap
            } catch {
                warnings.append("Your microphone is not being recorded: \(error.localizedDescription)")
            }
        }
        if system == nil && mic == nil {
            store.setState(meetingId, .failed, error: warnings.first ?? "Nothing could be recorded.")
            throw SherpaError(message: warnings.joined(separator: "\n"))
        }
    }

    static func explain(_ error: Error) -> String {
        let ns = error as NSError
        // SCStreamError.userDeclined, or no grant yet.
        if ns.domain == "com.apple.ScreenCaptureKit.SCStreamErrorDomain" && ns.code == -3801 || !Permissions.screen {
            return "Screen Recording access is off for Meeting Transcript."
        }
        return error.localizedDescription
    }

    public func mark() {
        store.addMark(meetingId, atMs: elapsedMs)
    }

    /// Stops capture and finishes the transcript. Returns once every line is
    /// in; with live transcription that is moments after the last word.
    public func stop() async {
        let durationMs = elapsedMs
        await system?.stop()
        mic?.stop()
        system = nil
        mic = nil
        let total = Audio.msToSamples(durationMs)
        for t in tracks.values { t.finish(total: total) }
        store.setDuration(meetingId, ms: durationMs)
        if let error = tracks.values.compactMap(\.error).first {
            store.setState(meetingId, .failed, error: "Could not write audio: \(error.localizedDescription)")
            return
        }
        guard let transcriber else {
            store.setState(meetingId, .recorded, error: Jobs.waitingForModels)
            return
        }
        store.setState(meetingId, .transcribing)
        do {
            try await Task.detached { try transcriber.finish() }.value
            store.setState(meetingId, .done)
        } catch {
            store.setState(meetingId, .failed, error: error.localizedDescription)
        }
    }
}

/// Transcriptions that run after the fact, one at a time: meetings recorded
/// before the models arrived, cut short by a crash, or retranscribed.
public final class Jobs: @unchecked Sendable {
    public static let shared = Jobs()
    public static let waitingForModels = "Waiting for the speech model"

    private let queue = DispatchQueue(label: "jobs", qos: .utility)
    private let lock = NSLock()
    private var progress: [Int64: Double] = [:]
    private var queued: Set<Int64> = []
    public var filterEcho = true

    public func progress(of id: Int64) -> Double? {
        lock.lock(); defer { lock.unlock() }
        return progress[id]
    }

    public func enqueue(_ id: Int64, store: Store = .shared) {
        lock.lock()
        let fresh = queued.insert(id).inserted
        lock.unlock()
        guard fresh else { return }
        store.setState(id, .recorded, error: "Queued")
        queue.async { self.run(id, store) }
    }

    private func run(_ id: Int64, _ store: Store) {
        defer {
            lock.lock(); queued.remove(id); progress[id] = nil; lock.unlock()
            store.changed(id)
        }
        guard store.meeting(id) != nil else { return }
        guard ModelFiles.ready else {
            store.setState(id, .recorded, error: Jobs.waitingForModels)
            return
        }
        do {
            var last = Date.distantPast
            try Transcriber.transcribeFromDisk(meeting: id, store: store, filterEcho: filterEcho) { p in
                self.lock.lock(); self.progress[id] = p; self.lock.unlock()
                if Date().timeIntervalSince(last) > 0.5 {
                    last = Date()
                    store.changed(id)
                }
            }
        } catch {
            store.setState(id, .failed, error: error.localizedDescription)
        }
    }

    /// At launch: anything left mid-recording by a crash or a force quit is
    /// closed off with the audio it has, and everything waiting is queued.
    public func recover(store: Store = .shared) {
        for m in store.meetings() {
            switch m.state {
            case .recording, .transcribing:
                if m.state == .recording {
                    let samples = Source.allCases.map { Audio.bytes(in: Paths.audio(meeting: m.id, source: $0)) / 2 }.max() ?? 0
                    store.setDuration(m.id, ms: Audio.samplesToMs(Int(samples)))
                }
                if ModelFiles.ready { enqueue(m.id, store: store) }
                else { store.setState(m.id, .recorded, error: Jobs.waitingForModels) }
            case .recorded:
                if ModelFiles.ready { enqueue(m.id, store: store) }
            default:
                break
            }
        }
    }
}
