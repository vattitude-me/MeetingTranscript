import AppKit
import AVFoundation
import MeetingCore
import ServiceManagement
import SwiftUI

/// Everything the menu and the windows show, in one place, on the main actor.
@MainActor
final class AppModel: ObservableObject {
    // Recording
    @Published private(set) var recording: Recording?
    @Published private(set) var starting = false
    /// Meetings stopped but still finishing their last lines.
    @Published private(set) var finishing: Set<Int64> = []
    @Published private(set) var elapsedMs: Int64 = 0
    @Published private(set) var levels: [Source: Float] = [:]
    @Published private(set) var liveLines: [Line] = []
    @Published var warnings: [String] = []
    @Published var errorMessage: String?

    // Library
    @Published private(set) var recent: [Meeting] = []
    /// The meeting the library window should show.
    @Published var selection: Int64?

    // Models
    @Published private(set) var modelsReady = ModelFiles.ready
    @Published private(set) var download: DownloadProgress?
    @Published private(set) var downloadError: String?

    // Permissions
    @Published private(set) var micStatus = Permissions.microphone
    @Published private(set) var screenGranted = Permissions.screen

    // Settings
    @AppStorage("filterEcho") var filterEcho = true { didSet { Jobs.shared.filterEcho = filterEcho } }
    @AppStorage("voiceProcessing") var voiceProcessing = false
    @AppStorage("captureSystem") var captureSystem = true

    /// A meeting longer than this is almost certainly a forgotten Stop.
    static let maxDurationMs: Int64 = 6 * 3600 * 1000

    private let meter = LevelMeter()
    private var ticker: Timer?
    private var openWindows = 0

    init() {
        Jobs.shared.filterEcho = filterEcho
        Jobs.shared.recover()
        refreshRecent()
        if modelsReady { Engine.shared.preload() }
        NotificationCenter.default.addObserver(forName: .storeChanged, object: nil, queue: .main) { [weak self] note in
            let id = note.object as? Int64
            Task { @MainActor in self?.storeChanged(id) }
        }
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.refreshPermissions() }
        }
    }

    var isRecording: Bool { recording != nil }
    var canRecord: Bool { micStatus != .denied || screenGranted }
    var needsSetup: Bool { micStatus != .authorized || (captureSystem && !screenGranted) }

    // MARK: recording

    func toggleRecording() {
        if isRecording { stop() } else { start() }
    }

    func start() {
        guard recording == nil, !starting else { return }
        starting = true
        errorMessage = nil
        warnings = []
        Task {
            defer { starting = false }
            if micStatus == .notDetermined {
                _ = await Permissions.requestMicrophone()
                refreshPermissions()
            }
            var opts = Recording.Options()
            opts.captureSystem = captureSystem
            opts.voiceProcessing = voiceProcessing
            opts.filterEcho = filterEcho
            do {
                let rec = try Recording(options: opts)
                let meter = self.meter
                rec.onLevel = { s, v in meter.add(s, v) }
                rec.onSystemStopped = { [weak self] e in
                    DispatchQueue.main.async {
                        self?.warnings.append("The call's audio stopped being recorded: \(e.localizedDescription)")
                    }
                }
                try await rec.start()
                warnings = rec.warnings
                recording = rec
                liveLines = []
                elapsedMs = 0
                selection = rec.meetingId
                refreshPermissions()
                startTicker()
            } catch {
                errorMessage = error.localizedDescription
                refreshRecent()
            }
        }
    }

    func stop() {
        guard let rec = recording else { return }
        recording = nil
        ticker?.invalidate()
        levels = [:]
        finishing.insert(rec.meetingId)
        Task {
            await rec.stop()
            finishing.remove(rec.meetingId)
            refreshRecent()
        }
    }

    func mark() { recording?.mark() }

    private func startTicker() {
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        guard let rec = recording else { return }
        elapsedMs = rec.elapsedMs
        let peak = meter.take()
        for s in Source.allCases {
            // Rise fast, fall slowly, so speech reads as speech and not flicker.
            let now = min(1, (peak[s] ?? 0) * 6)
            levels[s] = max(now, (levels[s] ?? 0) * 0.75)
        }
        if elapsedMs > AppModel.maxDurationMs {
            warnings.append("Stopped automatically after 6 hours.")
            stop()
        }
    }

    // MARK: store

    private func storeChanged(_ id: Int64?) {
        refreshRecent()
        if let rec = recording, id == nil || id == rec.meetingId {
            liveLines = Array(Store.shared.lines(rec.meetingId).suffix(4))
        }
    }

    func refreshRecent() {
        recent = Array(Store.shared.meetings().prefix(5))
    }

    // MARK: models

    func downloadModels() {
        guard download == nil else { return }
        downloadError = nil
        download = DownloadProgress(done: 0, total: 1, bytesPerSecond: 0, phase: "Starting")
        Task.detached {
            do {
                try await ModelDownloader.downloadMissing { p in
                    Task { @MainActor [weak self] in self?.download = p }
                }
                await MainActor.run { self.modelsDidLand() }
            } catch {
                await MainActor.run {
                    self.download = nil
                    self.downloadError = "\(error.localizedDescription) The download resumes where it stopped."
                }
            }
        }
    }

    private func modelsDidLand() {
        download = nil
        modelsReady = ModelFiles.ready
        guard modelsReady else { return }
        Engine.shared.preload()
        // Everything recorded while waiting is transcribed now.
        Jobs.shared.recover()
    }

    // MARK: permissions

    func refreshPermissions() {
        micStatus = Permissions.microphone
        screenGranted = Permissions.screen
    }

    func requestMic() {
        if micStatus == .notDetermined {
            Task {
                _ = await Permissions.requestMicrophone()
                refreshPermissions()
            }
        } else {
            NSWorkspace.shared.open(Permissions.micSettingsURL)
        }
    }

    func requestScreen() {
        // The first call shows the system prompt; after that macOS only
        // changes it in System Settings.
        if !Permissions.requestScreen() { NSWorkspace.shared.open(Permissions.screenSettingsURL) }
        refreshPermissions()
    }

    // MARK: windows

    /// A menu bar app has no Dock icon, so its windows can open behind
    /// whatever is in front. While one is open it becomes a regular app.
    func windowOpened() {
        openWindows += 1
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func windowClosed() {
        openWindows = max(0, openWindows - 1)
        if openWindows == 0 { NSApp.setActivationPolicy(.accessory) }
    }

    // MARK: login item

    var opensAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                errorMessage = "Could not change Open at Login: \(error.localizedDescription)"
            }
            objectWillChange.send()
        }
    }
}

/// Loudest level per source since it was last read, written from audio threads.
final class LevelMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var peak: [Source: Float] = [:]
    func add(_ s: Source, _ v: Float) { lock.lock(); peak[s] = max(peak[s] ?? 0, v); lock.unlock() }
    func take() -> [Source: Float] { lock.lock(); defer { peak = [:]; lock.unlock() }; return peak }
}
