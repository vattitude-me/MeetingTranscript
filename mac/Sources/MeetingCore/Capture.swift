import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation
import ScreenCaptureKit

/// What a capture hands on: 16 kHz mono Int16, stamped with the host time of
/// its first sample so the two sources can be lined up.
public typealias AudioSink = (_ samples: [Int16], _ hostSeconds: Double) -> Void

public enum Permissions {
    public static var microphone: AVAuthorizationStatus { AVCaptureDevice.authorizationStatus(for: .audio) }

    public static func requestMicrophone() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .audio)
    }

    /// macOS files system-audio capture under Screen Recording.
    public static var screen: Bool { CGPreflightScreenCaptureAccess() }

    /// Shows the system prompt the first time; afterwards macOS only allows
    /// changing it in System Settings, which [openScreenSettings] opens.
    @discardableResult
    public static func requestScreen() -> Bool { CGRequestScreenCaptureAccess() }

    public static let screenSettingsURL =
        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")!
    public static let micSettingsURL =
        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")!
}

public func hostNow() -> Double { CMClockGetTime(CMClockGetHostTimeClock()).seconds }

/// Everything the Mac is playing — the call — via ScreenCaptureKit, minus
/// this app's own sounds. Video is configured to a 2×2 frame nobody reads.
public final class SystemAudioCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private var stream: SCStream?
    private let downmixer = Toolbox.Downmixer()
    private let queue = DispatchQueue(label: "capture.system", qos: .userInitiated)
    private let sink: AudioSink
    public var onStop: ((Error) -> Void)?

    public init(sink: @escaping AudioSink) { self.sink = sink }

    public func start() async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let display = content.displays.first else {
            throw SherpaError(message: "No display to attach system-audio capture to.")
        }
        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.sampleRate = 48_000
        config.channelCount = 2
        // Keeps "play from here" out of the recording. macOS applies it per
        // responsible app, so anything launched from the same Terminal is
        // excluded too; the override exists for testing from a shell.
        config.excludesCurrentProcessAudio = ProcessInfo.processInfo.environment["MEETING_TRANSCRIPT_INCLUDE_OWN_AUDIO"] == nil
        config.width = 2
        config.height = 2
        config.minimumFrameInterval = CMTime(value: 1, timescale: 1)
        config.queueDepth = 5
        let s = SCStream(filter: filter, configuration: config, delegate: self)
        try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: queue)
        try await s.startCapture()
        stream = s
    }

    public func stop() async {
        try? await stream?.stopCapture()
        stream = nil
        queue.sync {}
    }

    public func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, sb.isValid, sb.numSamples > 0, let buffer = pcm(sb) else { return }
        let samples = downmixer.convert(buffer)
        if !samples.isEmpty { sink(samples, sb.presentationTimeStamp.seconds) }
    }

    public func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStop?(error)
    }

    private func pcm(_ sb: CMSampleBuffer) -> AVAudioPCMBuffer? {
        try? sb.withAudioBufferList { list, _ -> AVAudioPCMBuffer? in
            guard let d = sb.formatDescription?.audioStreamBasicDescription,
                  let format = AVAudioFormat(standardFormatWithSampleRate: d.mSampleRate,
                                             channels: AVAudioChannelCount(d.mChannelsPerFrame)),
                  let src = AVAudioPCMBuffer(pcmFormat: format, bufferListNoCopy: list.unsafePointer)
            else { return nil }
            // bufferListNoCopy is only valid inside this closure; copy out.
            guard let copy = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: src.frameLength) else { return nil }
            copy.frameLength = src.frameLength
            for c in 0..<Int(format.channelCount) {
                copy.floatChannelData![c].update(from: src.floatChannelData![c], count: Int(src.frameLength))
            }
            return copy
        }
    }
}

/// The person at the Mac, from the default input device.
public final class MicCapture {
    private var engine = AVAudioEngine()
    private let downmixer = Toolbox.Downmixer()
    private let sink: AudioSink
    private let voiceProcessing: Bool
    private var observer: NSObjectProtocol?
    private let queue = DispatchQueue(label: "capture.mic", qos: .userInitiated)

    /// [voiceProcessing] turns on Apple's echo cancellation, which removes
    /// what the speakers play from what the microphone hears. It can lower the
    /// volume of other audio while it runs, so it is a setting, not a default.
    public init(voiceProcessing: Bool, sink: @escaping AudioSink) {
        self.voiceProcessing = voiceProcessing
        self.sink = sink
    }

    public func start() throws {
        try configure()
        // Plugging in headphones or a USB mic rebuilds the audio graph; follow
        // it rather than recording silence from a device that is gone.
        observer = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            self.queue.async {
                self.engine.inputNode.removeTap(onBus: 0)
                self.engine.stop()
                try? self.configure()
            }
        }
    }

    private func configure() throws {
        let input = engine.inputNode
        if voiceProcessing {
            try? input.setVoiceProcessingEnabled(true)
            if #available(macOS 14.0, *) {
                input.voiceProcessingOtherAudioDuckingConfiguration =
                    .init(enableAdvancedDucking: false, duckingLevel: .min)
            }
        }
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw SherpaError(message: "No microphone is available.")
        }
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, when in
            guard let self else { return }
            let host = when.isHostTimeValid ? AVAudioTime.seconds(forHostTime: when.hostTime) : hostNow()
            let samples = self.downmixer.convert(buffer)
            if !samples.isEmpty { self.sink(samples, host) }
        }
        engine.prepare()
        try engine.start()
    }

    public func stop() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        queue.sync {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
    }
}
