import Foundation

/// The one loaded Parakeet, shared by live and after-the-fact transcription.
/// The recognizer is not thread-safe, so every decode goes through [queue].
public final class Engine: @unchecked Sendable {
    public static let shared = Engine()
    public static let modelId = "parakeet-tdt-0.6b-v3-int8"

    let queue = DispatchQueue(label: "asr", qos: .userInitiated)
    private var recognizer: Recognizer?

    /// Audio seconds decoded and wall seconds spent, for the speed readout.
    public private(set) var audioSeconds: Double = 0
    public private(set) var decodeSeconds: Double = 0
    public var speed: Double { decodeSeconds > 0 ? audioSeconds / decodeSeconds : 0 }

    /// One thread per performance core. Measured on an M3 Pro (5P + 6E):
    /// 5 threads 18× real time, 8 threads 14×, 4 threads 16× — threads that
    /// land on efficiency cores hold the others up at each sync point.
    public static var threads: Int {
        if let n = ProcessInfo.processInfo.environment["MEETING_TRANSCRIPT_THREADS"].flatMap(Int.init), n > 0 { return n }
        var p: Int32 = 0
        var size = MemoryLayout<Int32>.size
        if sysctlbyname("hw.perflevel0.physicalcpu", &p, &size, nil, 0) == 0, p > 0 { return Int(max(2, min(8, p))) }
        return max(2, min(8, ProcessInfo.processInfo.activeProcessorCount - 2))
    }

    /// Loads the model if needed. Call on [queue].
    func load() throws -> Recognizer {
        if let r = recognizer { return r }
        guard let files = ModelFiles.parakeet() else {
            throw SherpaError(message: "The speech model is not downloaded yet.")
        }
        let r = try Recognizer(files: files, threads: Engine.threads)
        recognizer = r
        return r
    }

    /// Warms the model in the background, so the first line of a meeting is
    /// not held up by a two-second load.
    public func preload() {
        queue.async { _ = try? self.load() }
    }

    func decode(_ samples: [Float], offsetMs: Int64) throws -> [LineSplitter.Piece] {
        let r = try load()
        let t0 = Date()
        let d = r.decode(samples)
        decodeSeconds += Date().timeIntervalSince(t0)
        audioSeconds += Double(samples.count) / Double(Audio.sampleRate)
        return LineSplitter.split(d, offsetMs: offsetMs, spanMs: Audio.samplesToMs(samples.count))
    }
}

/// Transcribes one meeting, per source, as audio arrives — live during a
/// recording, or at full speed from the files on disk.
///
/// Each source has its own voice detector, so the microphone and the call
/// are cut into utterances independently and labelled by where they came
/// from rather than by guessing at voices.
public final class Transcriber: @unchecked Sendable {
    public let meetingId: Int64
    private let store: Store
    private var vads: [Source: Vad] = [:]
    private let feedQueue: [Source: DispatchQueue]
    /// Lines from the last minute, per source, for the echo check.
    private var recent: [Source: [Line]] = [.mic: [], .system: []]
    private let recentLock = NSLock()
    public var filterEcho = true
    public private(set) var echoesRemoved = 0
    private var failed: Error?

    public init(meeting: Int64, store: Store = .shared) throws {
        meetingId = meeting
        self.store = store
        guard let vadModel = ModelFiles.vad() else {
            throw SherpaError(message: "The voice detector is not downloaded yet.")
        }
        for s in Source.allCases { vads[s] = try Vad(model: vadModel) }
        feedQueue = Dictionary(uniqueKeysWithValues: Source.allCases.map { ($0, DispatchQueue(label: "vad.\($0.folder)")) })
        store.setModel(meeting, Engine.modelId)
    }

    /// Audio for one source, 16 kHz Int16, contiguous from the recording's
    /// start. Returns immediately; decoding happens on the ASR queue.
    public func feed(_ source: Source, _ pcm: [Int16]) {
        feedQueue[source]!.async {
            let speech = self.vads[source]!.accept(Audio.toFloat(pcm))
            self.enqueue(source, speech)
        }
    }

    /// Ends both streams and waits for every pending utterance to be decoded.
    public func finish() throws {
        for s in Source.allCases {
            feedQueue[s]!.sync {
                let speech = self.vads[s]!.flush()
                self.enqueue(s, speech)
            }
        }
        Engine.shared.queue.sync {}
        if let failed { throw failed }
    }

    private func enqueue(_ source: Source, _ speech: [Vad.Speech]) {
        for sp in speech {
            Engine.shared.queue.async {
                do {
                    let offset = Audio.samplesToMs(sp.start)
                    for piece in try Engine.shared.decode(sp.samples, offsetMs: offset) {
                        self.store(piece, from: source)
                    }
                } catch {
                    self.failed = error
                }
            }
        }
    }

    private func store(_ piece: LineSplitter.Piece, from source: Source) {
        recentLock.lock()
        defer { recentLock.unlock() }
        if filterEcho {
            switch source {
            case .mic:
                // The microphone heard the call through the speakers. The call
                // itself is the better copy, so the echo is dropped.
                if recent[.system]!.contains(where: { Echo.matches(mic: piece, system: $0) }) {
                    echoesRemoved += 1
                    return
                }
            case .system:
                // The echo was decoded first; remove it now the original is in.
                let echoes = recent[.mic]!.filter {
                    Echo.matches(mic: LineSplitter.Piece(startMs: $0.tStartMs, endMs: $0.tEndMs, text: $0.text), system: piece)
                }
                for e in echoes { store.deleteLine(e.id, meeting: meetingId) }
                echoesRemoved += echoes.count
                recent[.mic]!.removeAll { l in echoes.contains { $0.id == l.id } }
            }
        }
        let line = store.insertLine(meeting: meetingId, startMs: piece.startMs, endMs: piece.endMs,
                                    text: piece.text, speaker: source.rawValue)
        recent[source]!.append(line)
        recent[source]!.removeAll { line.tStartMs - $0.tEndMs > 60_000 }
    }

    /// Transcribes a meeting from its files on disk: after the models finish
    /// downloading, after a crash, or when asked to start over.
    public static func transcribeFromDisk(meeting id: Int64, store: Store = .shared, filterEcho: Bool = true,
                                          progress: @escaping (Double) -> Void) throws {
        store.clearLines(id)
        store.setState(id, .transcribing)
        let t = try Transcriber(meeting: id, store: store)
        t.filterEcho = filterEcho
        let dirs = Source.allCases.map { ($0, Audio.segments(in: Paths.audio(meeting: id, source: $0))) }
        let total = max(1, dirs.reduce(0) { $0 + $1.1.count })
        var done = 0
        // Interleave the two sources segment by segment, so echo matching
        // sees both sides of the same half-minute at the same time.
        let most = dirs.map { $0.1.count }.max() ?? 0
        for i in 0..<most {
            for (source, segs) in dirs where i < segs.count {
                t.feed(source, Audio.readInt16(segs[i]))
                done += 1
            }
            // Keep the decode queue from running far behind the reader.
            Engine.shared.queue.sync {}
            progress(Double(done) / Double(total))
        }
        try t.finish()
        store.setState(id, .done)
    }
}

/// Recognises the microphone hearing the call through the Mac's speakers.
///
/// With headphones this never fires. With speakers, the far end's words land
/// on both streams a few hundred milliseconds apart; the words match even
/// when the recognizer's spelling of them differs slightly.
public enum Echo {
    static let windowMs: Int64 = 2_000

    public static func matches(mic: LineSplitter.Piece, system: LineSplitter.Piece) -> Bool {
        guard mic.startMs <= system.endMs + windowMs, system.startMs <= mic.endMs + windowMs else { return false }
        let a = words(mic.text), b = words(system.text)
        guard !a.isEmpty, !b.isEmpty else { return false }
        let common = a.intersection(b).count
        // Mostly contained in what the call said, and not a two-word "yeah ok".
        return common >= 2 && Double(common) / Double(a.count) >= 0.6
    }

    public static func matches(mic: LineSplitter.Piece, system: Line) -> Bool {
        matches(mic: mic, system: .init(startMs: system.tStartMs, endMs: system.tEndMs, text: system.text))
    }

    static func words(_ s: String) -> Set<String> {
        Set(s.lowercased().split { !$0.isLetter && !$0.isNumber && $0 != "'" }.map(String.init))
    }
}

/// Transcribes loose audio that is not a recorded meeting: a file handed to
/// the command line, or the self-test's synthetic speech.
public enum Offline {
    public static func transcribe(_ samples: [Float], progress: ((Double) -> Void)? = nil) throws -> [LineSplitter.Piece] {
        guard let vadModel = ModelFiles.vad() else {
            throw SherpaError(message: "The voice detector is not downloaded yet. Run: MeetingTranscript models")
        }
        let vad = try Vad(model: vadModel)
        var out: [LineSplitter.Piece] = []
        let step = Audio.sampleRate * 10
        var i = 0
        func decode(_ speech: [Vad.Speech]) throws {
            for sp in speech {
                let pieces = try Engine.shared.queue.sync {
                    try Engine.shared.decode(sp.samples, offsetMs: Audio.samplesToMs(sp.start))
                }
                out += pieces
            }
        }
        while i < samples.count {
            let end = min(samples.count, i + step)
            try decode(vad.accept(Array(samples[i..<end])))
            i = end
            progress?(Double(i) / Double(max(1, samples.count)))
        }
        try decode(vad.flush())
        return out
    }

    /// Loads the model now, and says how long that took.
    public static func load() throws -> TimeInterval {
        let t0 = Date()
        _ = try Engine.shared.queue.sync { try Engine.shared.load() }
        return Date().timeIntervalSince(t0)
    }
}
