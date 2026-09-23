import AVFoundation
import MeetingCore

/// Plays a meeting from any moment: both sides mixed, streamed from the
/// segment files a few seconds at a time so an hour of audio is never in
/// memory at once.
@MainActor
final class Player: ObservableObject {
    @Published private(set) var meetingId: Int64?
    @Published private(set) var isPlaying = false
    @Published private(set) var positionMs: Int64 = 0

    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: Double(Audio.sampleRate), channels: 1)!
    private var startSample = 0
    private var nextSample = 0
    private var length = 0
    private var generation = 0
    private var ticker: Timer?
    private static let chunk = Audio.sampleRate * 4

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
    }

    func play(meeting id: Int64, fromMs ms: Int64) {
        stop()
        meetingId = id
        length = Audio.length(meeting: id)
        startSample = max(0, min(Audio.msToSamples(ms), length))
        nextSample = startSample
        positionMs = ms
        generation += 1
        do {
            try engine.start()
        } catch {
            return
        }
        for _ in 0..<3 { scheduleNext() }
        node.play()
        isPlaying = true
        ticker = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    func toggle(meeting id: Int64, fromMs ms: Int64) {
        if isPlaying && meetingId == id { stop() } else { play(meeting: id, fromMs: meetingId == id && positionMs > 0 ? positionMs : ms) }
    }

    func stop() {
        generation += 1
        ticker?.invalidate()
        ticker = nil
        node.stop()
        engine.stop()
        isPlaying = false
    }

    private func scheduleNext() {
        guard nextSample < length else { return }
        let samples = Audio.readMixed(meeting: meetingId!, from: nextSample, count: Player.chunk)
        guard !samples.isEmpty, let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        buf.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { buf.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
        nextSample += samples.count
        let gen = generation
        node.scheduleBuffer(buf) { [weak self] in
            Task { @MainActor in
                guard let self, self.generation == gen else { return }
                if self.nextSample < self.length { self.scheduleNext() }
            }
        }
    }

    private func tick() {
        guard let t = node.lastRenderTime, let p = node.playerTime(forNodeTime: t) else { return }
        let played = startSample + Int(p.sampleTime)
        positionMs = Audio.samplesToMs(played)
        if played >= length { stop() }
    }
}
