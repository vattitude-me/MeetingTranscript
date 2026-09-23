import CSherpaOnnx
import Foundation

/// Keeps C strings alive for as long as a config struct that points at them.
private final class CStrings {
    private var owned: [UnsafeMutablePointer<CChar>] = []
    func callAsFunction(_ s: String) -> UnsafePointer<CChar> {
        let p = strdup(s)!
        owned.append(p)
        return UnsafePointer(p)
    }
    deinit { owned.forEach { free($0) } }
}

public struct SherpaError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
    public init(message: String) { self.message = message }
}

/// Parakeet TDT 0.6B v3 (int8) through sherpa-onnx's C API — the same model,
/// the same config and the same library version as the Android app, so a
/// meeting transcribes to the same words on either device.
///
/// Loading costs a couple of seconds and ~1 GB, so one instance serves every
/// meeting. It is not thread-safe; [Transcriber] serialises access to it.
public final class Recognizer {
    private let ptr: OpaquePointer

    public init(files: ModelFiles.Parakeet, threads: Int) throws {
        let s = CStrings()
        var c = SherpaOnnxOfflineRecognizerConfig()
        c.feat_config.sample_rate = Int32(Audio.sampleRate)
        c.feat_config.feature_dim = 80
        c.model_config.transducer.encoder = s(files.encoder.path)
        c.model_config.transducer.decoder = s(files.decoder.path)
        c.model_config.transducer.joiner = s(files.joiner.path)
        c.model_config.tokens = s(files.tokens.path)
        c.model_config.num_threads = Int32(threads)
        c.model_config.provider = s(ProcessInfo.processInfo.environment["MEETING_TRANSCRIPT_PROVIDER"] ?? "cpu")
        c.model_config.model_type = s("nemo_transducer")
        c.decoding_method = s("greedy_search")
        guard let p = SherpaOnnxCreateOfflineRecognizer(&c) else {
            throw SherpaError(message: "Could not load the speech model from \(files.encoder.deletingLastPathComponent().path)")
        }
        ptr = p
    }

    deinit { SherpaOnnxDestroyOfflineRecognizer(ptr) }

    public struct Decoded {
        public var text: String
        public var tokens: [String]
        public var timestamps: [Float]
    }

    public func decode(_ samples: [Float]) -> Decoded {
        guard !samples.isEmpty, let stream = SherpaOnnxCreateOfflineStream(ptr) else {
            return Decoded(text: "", tokens: [], timestamps: [])
        }
        defer { SherpaOnnxDestroyOfflineStream(stream) }
        samples.withUnsafeBufferPointer {
            SherpaOnnxAcceptWaveformOffline(stream, Int32(Audio.sampleRate), $0.baseAddress, Int32($0.count))
        }
        SherpaOnnxDecodeOfflineStream(ptr, stream)
        guard let r = SherpaOnnxGetOfflineStreamResult(stream) else {
            return Decoded(text: "", tokens: [], timestamps: [])
        }
        defer { SherpaOnnxDestroyOfflineRecognizerResult(r) }
        let res = r.pointee
        let text = res.text.map { String(cString: $0) } ?? ""
        let n = Int(res.count)
        var tokens: [String] = []
        var stamps: [Float] = []
        if n > 0, let arr = res.tokens_arr, let ts = res.timestamps {
            tokens.reserveCapacity(n)
            for i in 0..<n {
                tokens.append(arr[i].map { String(cString: $0) } ?? "")
                stamps.append(ts[i])
            }
        }
        return Decoded(text: text, tokens: tokens, timestamps: stamps)
    }
}

/// Silero VAD. Cuts a continuous stream into utterances, so the recognizer
/// sees whole sentences instead of fixed 30-second slabs cut mid-word — and so
/// a meeting can be transcribed while it is still happening.
public final class Vad {
    private let ptr: OpaquePointer
    private var pending: [Float] = []
    private let window = 512

    public struct Speech {
        /// Sample index in the stream fed so far.
        public let start: Int
        public let samples: [Float]
    }

    public init(model: URL) throws {
        let s = CStrings()
        var c = SherpaOnnxVadModelConfig()
        c.silero_vad.model = s(model.path)
        c.silero_vad.threshold = 0.5
        // Long enough that a breath mid-sentence does not end the utterance.
        c.silero_vad.min_silence_duration = 0.6
        c.silero_vad.min_speech_duration = 0.25
        c.silero_vad.window_size = Int32(window)
        // Someone talking without pause is cut here, so lines keep appearing
        // during a monologue and no single decode grows unbounded.
        c.silero_vad.max_speech_duration = 20
        c.sample_rate = Int32(Audio.sampleRate)
        c.num_threads = 1
        guard let p = SherpaOnnxCreateVoiceActivityDetector(&c, 60) else {
            throw SherpaError(message: "Could not load the voice detector from \(model.path)")
        }
        ptr = p
    }

    deinit { SherpaOnnxDestroyVoiceActivityDetector(ptr) }

    public func accept(_ samples: [Float]) -> [Speech] {
        pending.append(contentsOf: samples)
        var offset = 0
        pending.withUnsafeBufferPointer { buf in
            while buf.count - offset >= window {
                SherpaOnnxVoiceActivityDetectorAcceptWaveform(ptr, buf.baseAddress! + offset, Int32(window))
                offset += window
            }
        }
        pending.removeFirst(offset)
        return drain()
    }

    /// End of stream: whatever is mid-utterance is emitted now.
    public func flush() -> [Speech] {
        if !pending.isEmpty {
            let tail = pending + [Float](repeating: 0, count: window - pending.count)
            tail.withUnsafeBufferPointer {
                SherpaOnnxVoiceActivityDetectorAcceptWaveform(ptr, $0.baseAddress, Int32(window))
            }
            pending.removeAll()
        }
        SherpaOnnxVoiceActivityDetectorFlush(ptr)
        return drain()
    }

    private func drain() -> [Speech] {
        var out: [Speech] = []
        while SherpaOnnxVoiceActivityDetectorEmpty(ptr) == 0 {
            if let seg = SherpaOnnxVoiceActivityDetectorFront(ptr) {
                let s = seg.pointee
                let samples = s.samples.map { Array(UnsafeBufferPointer(start: $0, count: Int(s.n))) } ?? []
                out.append(Speech(start: Int(s.start), samples: samples))
                SherpaOnnxDestroySpeechSegment(seg)
            }
            SherpaOnnxVoiceActivityDetectorPop(ptr)
        }
        return out
    }
}

/// Turns one decoded utterance into readable, individually quotable lines.
/// A port of ParakeetEngine.splitIntoLines on Android, with its constants, so
/// both platforms break lines in the same places.
public enum LineSplitter {
    static let maxLineMs: Int64 = 30_000
    static let lineBreakGapMs: Int64 = 2_500
    static let minLineMs: Int64 = 1_500

    public struct Piece: Equatable {
        public let startMs: Int64
        public let endMs: Int64
        public let text: String

        public init(startMs: Int64, endMs: Int64, text: String) {
            self.startMs = startMs
            self.endMs = endMs
            self.text = text
        }
    }

    public static func split(_ d: Recognizer.Decoded, offsetMs: Int64, spanMs: Int64) -> [Piece] {
        let clean = d.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return [] }
        guard !d.tokens.isEmpty, d.tokens.count == d.timestamps.count else {
            return [Piece(startMs: offsetMs, endMs: offsetMs + spanMs, text: clean)]
        }
        var out: [Piece] = []
        var sb = ""
        var lineStart = d.timestamps[0]
        var prevEnd = d.timestamps[0]

        func ms(_ sec: Float) -> Int64 { Int64((sec * 1000).rounded()) }
        func flush(_ endSec: Float) {
            let body = sb.replacingOccurrences(of: "▁", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            sb = ""
            if body.isEmpty { return }
            let start = offsetMs + ms(lineStart)
            out.append(Piece(startMs: start, endMs: max(offsetMs + ms(endSec), start + 1), text: body))
        }

        for i in d.tokens.indices {
            let t = d.timestamps[i]
            if !sb.isEmpty && ms(t - prevEnd) > lineBreakGapMs {
                flush(prevEnd)
                lineStart = t
            }
            sb += d.tokens[i]
            let run = ms(t - lineStart)
            let tok = d.tokens[i].trimmingCharacters(in: .whitespaces)
            let endsSentence = tok.hasSuffix(".") || tok.hasSuffix("?") || tok.hasSuffix("!")
            if (endsSentence && run >= minLineMs) || run > maxLineMs {
                flush(t)
                if i + 1 < d.tokens.count { lineStart = d.timestamps[i + 1] }
            }
            prevEnd = t
        }
        flush(prevEnd)
        return out.isEmpty ? [Piece(startMs: offsetMs, endMs: offsetMs + spanMs, text: clean)] : out
    }
}
