import AVFoundation
import Foundation

/// The on-disk audio format, shared byte for byte with the Android app:
/// 16 kHz, mono, signed 16-bit little endian, headerless, in 30 s segments
/// named seg_00000.pcm, seg_00001.pcm, …
public enum Audio {
    public static let sampleRate = 16_000
    public static let segmentSeconds = 30
    public static var samplesPerSegment: Int { sampleRate * segmentSeconds }

    public static func segmentName(_ i: Int) -> String { String(format: "seg_%05d.pcm", i) }

    public static func samplesToMs(_ n: Int) -> Int64 { Int64(n) * 1000 / Int64(sampleRate) }
    public static func msToSamples(_ ms: Int64) -> Int { Int(ms * Int64(sampleRate) / 1000) }

    /// Segment files in order. Sorted by name, which is sorted by index.
    public static func segments(in dir: URL) -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.lastPathComponent.hasPrefix("seg_") && $0.pathExtension == "pcm" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    public static func bytes(in dir: URL) -> Int64 {
        segments(in: dir).reduce(0) { sum, url in
            sum + ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0)
        }
    }

    public static func toFloat(_ pcm: [Int16]) -> [Float] {
        pcm.map { Float($0) / 32768 }
    }

    public static func readInt16(_ url: URL) -> [Int16] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return data.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
    }

    /// Reads any file AVFoundation can open (wav, m4a, aiff, mp3…) as 16 kHz
    /// mono float, for transcribing a file that was not recorded here.
    public static func readAnyFile(_ url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(file.length)) else {
            return []
        }
        try file.read(into: buffer)
        return Toolbox.Downmixer().convert(buffer).map { Float($0) / 32768 }
    }
}

public enum Toolbox {
    /// Converts whatever a device delivers — 48 kHz, stereo, float, nine
    /// voice-processing channels — to 16 kHz mono Int16. AVAudioConverter does
    /// the rate conversion properly: dropping every third sample aliases, and
    /// aliasing is damage a speech model cannot be told to ignore.
    public final class Downmixer {
        private var converter: AVAudioConverter?
        private var monoFormat: AVAudioFormat?
        private let target = AVAudioFormat(
            commonFormat: .pcmFormatInt16, sampleRate: Double(Audio.sampleRate), channels: 1, interleaved: true
        )!

        public init() {}

        public func convert(_ input: AVAudioPCMBuffer) -> [Int16] {
            guard input.frameLength > 0 else { return [] }
            // Take channel 0 ourselves rather than asking the converter to
            // downmix: a voice-processed mic can report many channels, and
            // only the first carries the processed voice.
            let buffer = firstChannel(of: input) ?? input
            if converter == nil || converter?.inputFormat != buffer.format {
                converter = AVAudioConverter(from: buffer.format, to: target)
            }
            guard let converter else { return [] }
            let ratio = target.sampleRate / buffer.format.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return [] }
            var supplied = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true
                status.pointee = .haveData
                return buffer
            }
            if error != nil { return [] }
            guard let channel = out.int16ChannelData else { return [] }
            return Array(UnsafeBufferPointer(start: channel[0], count: Int(out.frameLength)))
        }

        private func firstChannel(of input: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
            guard input.format.channelCount > 1, let src = input.floatChannelData else { return nil }
            if monoFormat?.sampleRate != input.format.sampleRate {
                monoFormat = AVAudioFormat(standardFormatWithSampleRate: input.format.sampleRate, channels: 1)
            }
            guard let fmt = monoFormat,
                  let out = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: input.frameLength) else { return nil }
            out.frameLength = input.frameLength
            let n = Int(input.frameLength)
            // Stereo system audio is averaged, so a voice panned to one side
            // is not halved; many-channel mic input keeps channel 0 only.
            if input.format.channelCount == 2 {
                for i in 0..<n { out.floatChannelData![0][i] = (src[0][i] + src[1][i]) * 0.5 }
            } else {
                out.floatChannelData![0].update(from: src[0], count: n)
            }
            return out
        }
    }

    /// Root mean square of a buffer, 0…1, for the level meters.
    public static func rms(_ samples: [Int16]) -> Float {
        guard !samples.isEmpty else { return 0 }
        var sum: Float = 0
        for s in samples { let f = Float(s) / 32768; sum += f * f }
        return (sum / Float(samples.count)).squareRoot()
    }
}

/// Writes one source as rolling fixed-length segments, the way RecorderService
/// does on Android. A crash costs the current segment and nothing before it.
public final class SegmentWriter {
    private let dir: URL
    private var handle: FileHandle?
    private var index = 0
    private var samplesInSegment = 0
    public private(set) var totalSamples = 0

    public init(dir: URL) throws {
        self.dir = dir
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Continue after existing segments, so a resumed recording appends.
        let existing = Audio.segments(in: dir)
        if let last = existing.last,
           let n = Int(last.deletingPathExtension().lastPathComponent.dropFirst(4)) {
            let size = (try? last.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            index = n
            samplesInSegment = size / 2
            totalSamples = (existing.count - 1) * Audio.samplesPerSegment + samplesInSegment
            handle = try FileHandle(forWritingTo: last)
            try handle?.seekToEnd()
            if samplesInSegment >= Audio.samplesPerSegment { try roll() }
        } else {
            try open()
        }
    }

    private func open() throws {
        let url = dir.appendingPathComponent(Audio.segmentName(index))
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
        samplesInSegment = 0
    }

    private func roll() throws {
        try handle?.synchronize()
        try handle?.close()
        index += 1
        try open()
    }

    public func write(_ samples: [Int16]) throws {
        var offset = 0
        while offset < samples.count {
            let take = min(Audio.samplesPerSegment - samplesInSegment, samples.count - offset)
            try samples[offset..<offset + take].withUnsafeBufferPointer {
                try handle?.write(contentsOf: Data(buffer: $0))
            }
            samplesInSegment += take
            totalSamples += take
            offset += take
            if samplesInSegment >= Audio.samplesPerSegment { try roll() }
        }
    }

    public func finish() {
        try? handle?.synchronize()
        try? handle?.close()
        handle = nil
    }
}

public extension Audio {
    /// Both sources summed, as one listener would have heard the meeting:
    /// [count] samples from sample [from], shorter at the end of the audio.
    static func readMixed(meeting id: Int64, from: Int, count: Int) -> [Float] {
        var out = [Float](repeating: 0, count: count)
        var longest = 0
        for source in Source.allCases {
            let segs = segments(in: Paths.audio(meeting: id, source: source))
            var pos = from, filled = 0
            while filled < count {
                let seg = pos / samplesPerSegment
                guard seg < segs.count, let h = try? FileHandle(forReadingFrom: segs[seg]) else { break }
                defer { try? h.close() }
                let within = pos % samplesPerSegment
                try? h.seek(toOffset: UInt64(within * 2))
                let want = min(count - filled, samplesPerSegment - within)
                guard let data = try? h.read(upToCount: want * 2), !data.isEmpty else { break }
                data.withUnsafeBytes { raw in
                    let s = raw.bindMemory(to: Int16.self)
                    for i in 0..<s.count { out[filled + i] += Float(s[i]) / 32768 }
                }
                filled += data.count / 2
                pos += data.count / 2
                if data.count / 2 < want { break }
            }
            longest = max(longest, filled)
        }
        out.removeLast(count - longest)
        for i in out.indices { out[i] = max(-1, min(1, out[i])) }
        return out
    }

    /// Samples recorded for the meeting: the longer of its two sources.
    static func length(meeting id: Int64) -> Int {
        Int(Source.allCases.map { bytes(in: Paths.audio(meeting: id, source: $0)) }.max() ?? 0) / 2
    }
}

public enum Library {
    /// Removes a meeting: its rows, and its audio folder. Only ever called
    /// after the person confirmed, in words that said the audio goes too.
    public static func delete(_ id: Int64, store: Store = .shared) {
        store.delete(id)
        try? FileManager.default.removeItem(at: Paths.audio(meeting: id))
    }
}
