import CoreAudio
import Foundation

/// Noticing that a call has started, so recording it is one click instead of
/// something to remember.
///
/// The signal is the microphone. Every call app opens it when a call starts and
/// lets go when the call ends, and macOS 14 says which process holds it
/// (`kAudioHardwarePropertyProcessObjectList`, with `IsRunningInput` and the
/// bundle ID per process). Nothing is recorded or read to decide this: it is
/// the same fact the orange dot in the menu bar shows.
///
/// Only known call apps and browsers count. Dictation, Siri and voice memos
/// use the microphone too, and offering to record those would teach people to
/// ignore the offer.
public enum CallApps {
    /// Bundle-ID prefixes, matched case-insensitively. Browsers are listed by
    /// prefix because their audio runs in a helper process
    /// (`com.google.Chrome.helper`, Safari's `com.apple.WebKit.GPU`), and the
    /// helper is what holds the microphone.
    static let known: [(prefix: String, name: String)] = [
        ("us.zoom.", "Zoom"),
        ("com.microsoft.teams", "Teams"),
        ("com.cisco.webex", "Webex"),
        ("cisco-systems.spark", "Webex"),
        ("com.tinyspeck.slackmacgap", "Slack"),
        ("com.apple.facetime", "FaceTime"),
        ("com.hnc.discord", "Discord"),
        ("net.whatsapp.whatsapp", "WhatsApp"),
        ("com.google.chrome", "Chrome"),
        ("com.apple.webkit.gpu", "Safari"),
        ("com.apple.safari", "Safari"),
        ("com.microsoft.edgemac", "Edge"),
        ("company.thebrowser.", "Arc"),
        ("com.brave.browser", "Brave"),
        ("org.mozilla.firefox", "Firefox"),
        ("org.mozilla.plugincontainer", "Firefox"),
    ]

    /// The name to show for a process holding the microphone, or nil when it
    /// is not something that holds calls.
    public static func name(forBundleID id: String) -> String? {
        let lower = id.lowercased()
        return known.first { lower.hasPrefix($0.prefix) }?.name
    }
}

/// Turns a stream of "who holds the microphone right now" snapshots into call
/// started / call ended events.
///
/// Both edges are debounced. A start needs the app on the microphone for
/// [startAfter] snapshots in a row, so a browser tab that opens the mic for a
/// moment does not raise an offer. An end needs it gone for [endAfter] in a
/// row, because call apps briefly let go when the input device changes (a
/// headset plugged in mid-call), and offering to stop then would be wrong.
public struct CallTracker: Sendable {
    public enum Event: Equatable, Sendable {
        case started(String)
        case ended(String)
    }

    public let startAfter: Int
    public let endAfter: Int
    private var present: [String: Int] = [:]
    private var absent: [String: Int] = [:]
    public private(set) var active: Set<String> = []

    public init(startAfter: Int = 2, endAfter: Int = 8) {
        self.startAfter = max(1, startAfter)
        self.endAfter = max(1, endAfter)
    }

    public mutating func update(_ snapshot: Set<String>) -> [Event] {
        var events: [Event] = []
        for name in snapshot.sorted() {
            present[name, default: 0] += 1
            absent[name] = 0
            if !active.contains(name) && present[name]! >= startAfter {
                active.insert(name)
                events.append(.started(name))
            }
        }
        for name in present.keys.sorted() where !snapshot.contains(name) {
            if active.contains(name) {
                absent[name, default: 0] += 1
                if absent[name]! >= endAfter {
                    active.remove(name)
                    present[name] = nil
                    absent[name] = nil
                    events.append(.ended(name))
                }
            } else {
                // Never confirmed as a call: a blip, forgotten at once.
                present[name] = nil
                absent[name] = nil
            }
        }
        return events
    }
}

/// Which other processes are taking microphone input, read from Core Audio.
public enum MicUsers {
    /// Bundle IDs of processes other than this one that are recording from any
    /// input right now. Empty if Core Audio cannot say.
    public static func current() -> [String] {
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain)
        let system = AudioObjectID(kAudioObjectSystemObject)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &addr, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(system, &addr, 0, nil, &size, &ids) == noErr else { return [] }

        let me = getpid()
        return ids.compactMap { id -> String? in
            guard uint32(id, kAudioProcessPropertyIsRunningInput) == 1 else { return nil }
            guard pid(id) != me else { return nil }
            return bundleID(id)
        }
    }

    private static func uint32(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> UInt32? {
        var addr = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        return AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr ? value : nil
    }

    private static func pid(_ id: AudioObjectID) -> pid_t? {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioProcessPropertyPID, mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var value: pid_t = 0
        var size = UInt32(MemoryLayout<pid_t>.size)
        return AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr ? value : nil
    }

    private static func bundleID(_ id: AudioObjectID) -> String? {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioProcessPropertyBundleID, mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr,
              let s = value?.takeRetainedValue() as String?, !s.isEmpty else { return nil }
        return s
    }
}

/// Polls [MicUsers] every couple of seconds and reports call edges on the main
/// queue. Polling rather than property listeners: the process list and each
/// process's input flag would each need a listener, re-registered as processes
/// come and go, and a 2-second poll of a few properties costs nothing.
public final class CallDetector: @unchecked Sendable {
    public var onStarted: ((String) -> Void)?
    public var onEnded: ((String) -> Void)?

    private let queue = DispatchQueue(label: "calls", qos: .utility)
    private var timer: DispatchSourceTimer?
    private var tracker = CallTracker()
    private let interval: TimeInterval

    public init(interval: TimeInterval = 2) { self.interval = interval }

    /// Call apps currently confirmed to be in a call.
    public var active: Set<String> { queue.sync { tracker.active } }

    public func start() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now(), repeating: interval, leeway: .milliseconds(500))
        t.setEventHandler { [weak self] in self?.poll() }
        timer = t
        t.resume()
    }

    public func stop() {
        timer?.cancel()
        timer = nil
        queue.async { self.tracker = CallTracker() }
    }

    private func poll() {
        let names = Set(MicUsers.current().compactMap(CallApps.name(forBundleID:)))
        for e in tracker.update(names) {
            DispatchQueue.main.async { [weak self] in
                switch e {
                case .started(let n): self?.onStarted?(n)
                case .ended(let n): self?.onEnded?(n)
                }
            }
        }
    }
}
