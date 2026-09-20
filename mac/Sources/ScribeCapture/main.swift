import AVFoundation
import Foundation
import ScreenCaptureKit

// Proves the one thing the Mac port rests on: that we can take system audio —
// what the meeting app is playing — and land it on disk in exactly the format
// the Android transcriber already eats. 16 kHz, mono, signed 16-bit, little
// endian, headerless. Same segment layout, so the same reader works.
//
//   swift run ScribeCapture [seconds] [output-dir]
//
// Requires Screen Recording permission. macOS asks the first time; if the
// binary is rebuilt the grant follows the path, not the build, so it persists.

let SAMPLE_RATE = 16_000.0
let SEGMENT_SECONDS = 30.0

/// Writes the incoming stream as rolling fixed-length segments, the way
/// RecorderService does on Android. A crash costs you the current segment and
/// nothing before it, and the transcriber can start on segment 0 while segment
/// 3 is still being written.
final class SegmentWriter {
    private let dir: URL
    private var handle: FileHandle?
    private var index = 0
    private var samplesInSegment = 0
    private let samplesPerSegment: Int
    private(set) var totalSamples = 0

    init(dir: URL) throws {
        self.dir = dir
        self.samplesPerSegment = Int(SAMPLE_RATE * SEGMENT_SECONDS)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try openSegment()
    }

    private func openSegment() throws {
        let name = String(format: "seg_%05d.pcm", index)
        let url = dir.appendingPathComponent(name)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
        samplesInSegment = 0
        FileHandle.standardError.write("→ \(name)\n".data(using: .utf8)!)
    }

    func write(_ samples: [Int16]) throws {
        var offset = 0
        while offset < samples.count {
            let room = samplesPerSegment - samplesInSegment
            let take = min(room, samples.count - offset)
            let slice = Array(samples[offset ..< offset + take])
            try slice.withUnsafeBufferPointer {
                try handle?.write(contentsOf: Data(buffer: $0))
            }
            samplesInSegment += take
            totalSamples += take
            offset += take
            if samplesInSegment >= samplesPerSegment {
                try handle?.close()
                index += 1
                try openSegment()
            }
        }
    }

    func finish() throws {
        try handle?.close()
        handle = nil
    }
}

/// ScreenCaptureKit hands us 48 kHz deinterleaved float. The ASR models want
/// 16 kHz mono Int16. AVAudioConverter does the rate conversion properly —
/// dropping every third sample aliases, and aliasing is exactly the kind of
/// damage a speech model cannot be told to ignore.
final class Downmixer {
    private var converter: AVAudioConverter?
    private let target: AVAudioFormat

    init() {
        target = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: SAMPLE_RATE,
            channels: 1,
            interleaved: true
        )!
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> [Int16] {
        if converter == nil || converter?.inputFormat != buffer.format {
            converter = AVAudioConverter(from: buffer.format, to: target)
        }
        guard let converter else { return [] }

        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else {
            return []
        }

        var supplied = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        if let error {
            FileHandle.standardError.write("convert: \(error)\n".data(using: .utf8)!)
            return []
        }
        guard let channel = out.int16ChannelData else { return [] }
        return Array(UnsafeBufferPointer(start: channel[0], count: Int(out.frameLength)))
    }
}

final class Capture: NSObject, SCStreamOutput {
    private let writer: SegmentWriter
    private let downmixer = Downmixer()
    private var peak: Float = 0

    init(writer: SegmentWriter) {
        self.writer = writer
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, sb.isValid, sb.numSamples > 0 else { return }
        guard let buffer = pcmBuffer(from: sb) else { return }

        // A file full of digital silence is the failure this spike exists to
        // catch: capture "succeeds", permission looks granted, and nothing was
        // recorded. Track the peak so the exit line can say which happened.
        if let floats = buffer.floatChannelData {
            for c in 0 ..< Int(buffer.format.channelCount) {
                for i in 0 ..< Int(buffer.frameLength) {
                    peak = max(peak, abs(floats[c][i]))
                }
            }
        }

        let samples = downmixer.convert(buffer)
        guard !samples.isEmpty else { return }
        try? writer.write(samples)
    }

    private func pcmBuffer(from sb: CMSampleBuffer) -> AVAudioPCMBuffer? {
        try? sb.withAudioBufferList { list, _ -> AVAudioPCMBuffer? in
            guard let description = sb.formatDescription?.audioStreamBasicDescription,
                  let format = AVAudioFormat(
                      standardFormatWithSampleRate: description.mSampleRate,
                      channels: AVAudioChannelCount(description.mChannelsPerFrame)
                  )
            else { return nil }
            return AVAudioPCMBuffer(pcmFormat: format, bufferListNoCopy: list.unsafePointer)
        }
    }

    var peakLevel: Float { peak }
}

func run() async throws {
    let args = CommandLine.arguments
    let seconds = args.count > 1 ? Double(args[1]) ?? 10 : 10
    let outDir = args.count > 2
        ? URL(fileURLWithPath: args[2])
        : URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("capture-\(Int(Date().timeIntervalSince1970))")

    // Throws if Screen Recording has not been granted, which is the correct
    // place to fail — asking for content is what triggers the system prompt.
    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
    guard let display = content.displays.first else {
        throw NSError(domain: "scribe", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "no display to attach to"])
    }

    // Audio needs a content filter, but we want none of the video. Asking for
    // the smallest legal frame is how you say "audio only" to ScreenCaptureKit.
    let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
    let config = SCStreamConfiguration()
    config.capturesAudio = true
    config.sampleRate = 48_000
    config.channelCount = 2
    config.excludesCurrentProcessAudio = true
    config.width = 2
    config.height = 2
    config.minimumFrameInterval = CMTime(value: 1, timescale: 1)

    let writer = try SegmentWriter(dir: outDir)
    let capture = Capture(writer: writer)
    let stream = SCStream(filter: filter, configuration: config, delegate: nil)
    try stream.addStreamOutput(capture, type: .audio,
                               sampleHandlerQueue: DispatchQueue(label: "scribe.audio"))
    try await stream.startCapture()

    FileHandle.standardError.write(
        "capturing system audio for \(Int(seconds))s → \(outDir.path)\n".data(using: .utf8)!)
    try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    try await stream.stopCapture()
    try writer.finish()

    let duration = Double(writer.totalSamples) / SAMPLE_RATE
    let summary = """

    wrote \(writer.totalSamples) samples · \(String(format: "%.1f", duration))s at 16 kHz mono Int16
    peak input level \(String(format: "%.4f", capture.peakLevel))\
    \(capture.peakLevel < 0.0001 ? "  ← silence: play something audible and retry" : "")
    \(outDir.path)

    """
    FileHandle.standardError.write(summary.data(using: .utf8)!)
}

do {
    try await run()
} catch {
    FileHandle.standardError.write("failed: \(error.localizedDescription)\n".data(using: .utf8)!)
    exit(1)
}
