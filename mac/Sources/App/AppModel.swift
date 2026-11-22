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
    @AppStorage("offerToRecordCalls") var offerToRecordCalls = true { didSet { updateDetector() } }
    @AppStorage("calendarTitles") var calendarTitles = false
    /// Where finished meetings are saved as Markdown notes. Empty means off.
    @AppStorage("notesFolder") var notesFolder = ""

    // Calls
    /// A call app holding the microphone while nothing is being recorded:
    /// what the menu panel offers to record.
    @Published private(set) var callInProgress: String?
    /// The call app the current recording follows, so its end can offer Stop.
    private var recordingFollows: String?
    private let detector = CallDetector()
    private var exports: [Int64: DispatchWorkItem] = [:]

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
        Notifier.shared.onRecord = { [weak self] in self?.start() }
        Notifier.shared.onStop = { [weak self] in self?.stop() }
        detector.onStarted = { [weak self] app in self?.callStarted(app) }
        detector.onEnded = { [weak self] app in self?.callEnded(app) }
        updateDetector()
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
                recordingFollows = callInProgress ?? detector.active.sorted().first
                callInProgress = nil
                Notifier.shared.clearOffer()
                nameFromCalendar(rec.meetingId)
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
        recordingFollows = nil
        Notifier.shared.clearEnded()
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
        if let id { scheduleNote(id) }
        if let rec = recording, id == nil || id == rec.meetingId {
            liveLines = Array(Store.shared.lines(rec.meetingId).suffix(4))
        }
    }

    func refreshRecent() {
        recent = Array(Store.shared.meetings().prefix(5))
    }

    // MARK: calls

    private func updateDetector() {
        if offerToRecordCalls {
            Notifier.shared.setUp()
            detector.start()
        } else {
            detector.stop()
            callInProgress = nil
            Notifier.shared.clearOffer()
        }
    }

    private func callStarted(_ app: String) {
        if isRecording {
            // Recording began before the call app opened the mic, or a second
            // app joined: follow the first, so its end is the one that counts.
            if recordingFollows == nil { recordingFollows = app }
            // It came back, so the call was not over: withdraw "stop?".
            if recordingFollows == app { Notifier.shared.clearEnded() }
            return
        }
        guard offerToRecordCalls, !starting else { return }
        callInProgress = app
        Notifier.shared.offerRecording(app: app)
    }

    private func callEnded(_ app: String) {
        if callInProgress == app {
            callInProgress = detector.active.sorted().first
            if callInProgress == nil { Notifier.shared.clearOffer() }
        }
        // Asks rather than stops. A call app can let go of the microphone
        // while the call goes on (switching to a phone's audio, a browser
        // reloading the tab), and stopping then would lose the rest of it.
        if isRecording && recordingFollows == app {
            Notifier.shared.offerStop(app: app)
        }
    }

    func dismissCallOffer() {
        callInProgress = nil
        Notifier.shared.clearOffer()
    }

    // MARK: calendar

    /// Names a new recording after the calendar event it belongs to, unless
    /// it already has a name.
    private func nameFromCalendar(_ id: Int64) {
        guard calendarTitles, let event = CalendarTitles.shared.event() else { return }
        guard Store.shared.meeting(id)?.title.isEmpty ?? false else { return }
        Store.shared.rename(id, to: event.title)
    }

    /// Turning the setting on asks for access; a "no" turns it back off.
    func setCalendarTitles(_ on: Bool) {
        guard on else { calendarTitles = false; return }
        Task {
            let granted = await CalendarTitles.shared.requestAccess()
            calendarTitles = granted
            if !granted {
                errorMessage = "Calendar access is off for Meeting Transcript. Turn it on in System Settings → Privacy & Security → Calendars."
            }
        }
    }

    // MARK: notes folder

    var notesFolderURL: URL? {
        notesFolder.isEmpty ? nil : URL(fileURLWithPath: notesFolder, isDirectory: true)
    }

    /// Where each meeting's note was last written, so a rename replaces it.
    private var notePaths: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: "notePaths") as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: "notePaths") }
    }

    /// Store changes come in bursts (a rename, then a refresh; a transcript's
    /// last lines one by one), so the write waits for them to settle.
    private func scheduleNote(_ id: Int64) {
        guard notesFolderURL != nil else { return }
        exports[id]?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in self?.writeNote(id) }
        }
        exports[id] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    /// Writes the note for a finished meeting. Anything still recording or
    /// transcribing waits for its next change, which finishing always is.
    @discardableResult
    func writeNote(_ id: Int64) -> Bool {
        exports[id] = nil
        guard let folder = notesFolderURL, let m = Store.shared.meeting(id), m.state == .done else { return false }
        let lines = Store.shared.lines(id)
        guard !lines.isEmpty else { return false }
        var paths = notePaths
        let previous = paths[String(id)].map { URL(fileURLWithPath: $0) }
        do {
            let url = try NotesExport.write(m, lines, Store.shared.names(id), to: folder, previous: previous)
            paths[String(id)] = url.path
            notePaths = paths
            return true
        } catch {
            errorMessage = "Could not save the note to \(folder.path): \(error.localizedDescription)"
            return false
        }
    }

    func chooseNotesFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = "Save Notes Here"
        panel.message = "Each finished meeting is saved here as a Markdown file."
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        notesFolder = url.path
        // A different folder starts a different set of notes.
        notePaths = [:]
    }

    /// Saves every finished meeting already in the library. Returns how many.
    @discardableResult
    func writeAllNotes() -> Int {
        Store.shared.meetings().filter { $0.state == .done }.reduce(0) { $0 + (writeNote($1.id) ? 1 : 0) }
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
