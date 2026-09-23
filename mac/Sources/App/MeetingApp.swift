import MeetingCore
import SwiftUI

struct MeetingApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @StateObject private var model = AppModel()
    @StateObject private var player = Player()

    var body: some Scene {
        MenuBarExtra {
            MenuView()
                .environmentObject(model)
        } label: {
            MenuLabel(model: model)
        }
        .menuBarExtraStyle(.window)

        Window("Meeting Transcript", id: "library") {
            LibraryView()
                .environmentObject(model)
                .environmentObject(player)
                .onAppear { model.windowOpened() }
                .onDisappear { model.windowClosed(); player.stop() }
        }
        .defaultSize(width: 980, height: 680)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button(model.isRecording ? "Stop Recording" : "Start Recording") { model.toggleRecording() }
                    .keyboardShortcut("r", modifiers: [.command, .shift])
                Button("Mark This Moment") { model.mark() }
                    .keyboardShortcut("m", modifiers: [.command, .shift])
                    .disabled(!model.isRecording)
            }
        }

        Window("Settings", id: "settings") {
            SettingsView()
                .environmentObject(model)
                .onAppear { model.windowOpened() }
                .onDisappear { model.windowClosed() }
        }
        .windowResizability(.contentSize)
    }
}

extension Notification.Name {
    static let openLibrary = Notification.Name("openLibrary")
}

/// A menu bar app launched from Finder shows nothing but a small icon, which
/// reads as "it didn't open". So the library opens on the first launch, and
/// whenever the app is opened again while it is already running.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows { NotificationCenter.default.post(name: .openLibrary, object: nil) }
        return true
    }
}

/// The menu bar item: a waveform at rest, a red dot and the time while recording.
/// It exists for the app's whole life, so it is what opens the library on request.
struct MenuLabel: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @AppStorage("welcomed") private var welcomed = false

    var body: some View {
        icon
            .onAppear {
                guard !welcomed else { return }
                welcomed = true
                // Not during launch itself: a window opened before the app has
                // finished launching comes up off-screen at a fraction of its size.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { openWindow(id: "library") }
            }
            .onReceive(NotificationCenter.default.publisher(for: .openLibrary)) { _ in
                openWindow(id: "library")
            }
    }

    @ViewBuilder private var icon: some View {
        if model.isRecording {
            Image(systemName: "record.circle.fill")
            Text(Exporters.timestamp(model.elapsedMs)).monospacedDigit()
        } else if !model.finishing.isEmpty {
            Image(systemName: "waveform.badge.ellipsis")
        } else {
            Image(systemName: "waveform")
        }
    }
}

enum Theme {
    static let accent = Color(red: 0.0, green: 0.55, blue: 0.62)
    static let record = Color(red: 0.86, green: 0.2, blue: 0.2)

    static func speaker(_ s: Int) -> Color {
        switch s {
        case Source.mic.rawValue: return accent
        case Source.system.rawValue: return Color(red: 0.75, green: 0.42, blue: 0.1)
        default: return .secondary
        }
    }

    static func serif(_ style: Font.TextStyle, _ weight: Font.Weight = .regular) -> Font {
        .system(style, design: .serif).weight(weight)
    }
}
