import MeetingCore
import SwiftUI

/// The panel under the menu bar icon: everything needed during a meeting,
/// and a way into the library for everything after.
struct MenuView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            if !model.modelsReady { ModelCard() }
            if model.needsSetup && !model.isRecording { PermissionsCard() }
            recordSection
            if let e = model.errorMessage { Notice(text: e, systemImage: "exclamationmark.triangle.fill", tint: .red) }
            ForEach(model.warnings, id: \.self) { Notice(text: $0, systemImage: "exclamationmark.triangle", tint: .orange) }
            if model.isRecording && !model.liveLines.isEmpty { liveSection }
            if !model.recent.isEmpty && !model.isRecording { recentSection }
            Divider()
            footer
        }
        .padding(16)
        .frame(width: 360)
        .onAppear { model.refreshPermissions(); model.refreshRecent() }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Meeting Transcript").font(Theme.serif(.title3, .semibold))
            Spacer()
            Text(status).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var status: String {
        if model.isRecording { return "Recording" }
        if !model.finishing.isEmpty { return "Finishing transcript…" }
        if !model.modelsReady { return "Records now, transcribes once the model is in" }
        return "On this Mac only"
    }

    private var recordSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: model.toggleRecording) {
                HStack(spacing: 10) {
                    Image(systemName: model.isRecording ? "stop.fill" : "record.circle")
                        .font(.system(size: 17, weight: .semibold))
                    Text(model.isRecording ? "Stop" : model.starting ? "Starting…" : "Record meeting")
                        .font(.system(size: 15, weight: .semibold))
                    Spacer()
                    if model.isRecording {
                        Text(Exporters.timestamp(model.elapsedMs)).monospacedDigit().font(.system(size: 15, weight: .medium))
                    } else {
                        Text("⇧⌘R").font(.caption).foregroundStyle(.white.opacity(0.75))
                    }
                }
                .padding(.horizontal, 14)
                .frame(height: 44)
                .foregroundStyle(.white)
                .background(RoundedRectangle(cornerRadius: 12).fill(model.isRecording ? Color.primary.opacity(0.8) : Theme.record))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(model.starting || !model.canRecord)
            .help(model.canRecord ? "Records the call's audio and your microphone" : "Allow the microphone or Screen Recording first")

            if model.isRecording {
                HStack(spacing: 14) {
                    LevelBar(label: "You", level: model.levels[.mic] ?? 0, color: Theme.speaker(0))
                    LevelBar(label: "Them", level: model.levels[.system] ?? 0, color: Theme.speaker(1))
                    Button { model.mark() } label: { Label("Mark", systemImage: "bookmark") }
                        .help("Mark this moment (⇧⌘M) — it shows in the transcript")
                }
                if !model.modelsReady {
                    Text("The speech model is not in yet. The audio is saved and transcribed when it lands.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var liveSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("LIVE").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(model.liveLines) { l in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(Source(rawValue: l.speaker)?.defaultName ?? "")
                        .font(.caption.weight(.semibold)).foregroundStyle(Theme.speaker(l.speaker))
                        .frame(width: 38, alignment: .leading)
                    Text(l.text).font(Theme.serif(.callout)).lineLimit(3)
                }
            }
        }
    }

    private var recentSection: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("RECENT").font(.caption2.weight(.semibold)).foregroundStyle(.secondary).padding(.bottom, 4)
            ForEach(model.recent) { m in
                Button {
                    model.selection = m.id
                    openWindow(id: "library")
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(Exporters.title(m)).lineLimit(1).font(Theme.serif(.body))
                            Text(meta(m)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        StateBadge(meeting: m, finishing: model.finishing.contains(m.id))
                    }
                    .padding(.vertical, 5).padding(.horizontal, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(RowButtonStyle())
            }
        }
    }

    private var footer: some View {
        HStack {
            Button("Open Library") { openWindow(id: "library") }.keyboardShortcut("l")
            Spacer()
            Button { openWindow(id: "settings") } label: { Image(systemName: "gearshape") }
                .help("Settings").keyboardShortcut(",")
            Button("Quit") { NSApp.terminate(nil) }.keyboardShortcut("q")
                .disabled(model.isRecording)
                .help(model.isRecording ? "Stop the recording first" : "Quit Meeting Transcript")
        }
        .buttonStyle(.borderless)
    }

    private func meta(_ m: Meeting) -> String {
        let f = DateFormatter()
        f.doesRelativeDateFormatting = true
        f.dateStyle = .short
        f.timeStyle = .short
        return f.string(from: m.startedAt) + (m.durationMs > 0 ? " · " + Exporters.timestamp(m.durationMs) : "")
    }
}

struct RowButtonStyle: ButtonStyle {
    @State private var hover = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(RoundedRectangle(cornerRadius: 7).fill(Color.primary.opacity(configuration.isPressed ? 0.12 : hover ? 0.06 : 0)))
            .onHover { hover = $0 }
    }
}

struct LevelBar: View {
    let label: String
    let level: Float
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Text(label).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.1))
                    Capsule().fill(color).frame(width: max(4, g.size.width * CGFloat(level)))
                }
            }
            .frame(height: 6)
            .animation(.linear(duration: 0.15), value: level)
        }
        .accessibilityElement()
        .accessibilityLabel("\(label) level")
        .accessibilityValue("\(Int(level * 100)) percent")
    }
}

struct Notice: View {
    let text: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label { Text(text).font(.callout).fixedSize(horizontal: false, vertical: true) } icon: {
            Image(systemName: systemImage).foregroundStyle(tint)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(tint.opacity(0.1)))
    }
}

/// First run: one download, explained, with the promise that recording
/// does not have to wait for it.
struct ModelCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Get the speech model", systemImage: "arrow.down.circle")
                .font(Theme.serif(.headline, .semibold)).foregroundStyle(Theme.accent)
            Text("About 490 MB, once. It runs on this Mac, so meetings are transcribed here and never uploaded. You can record now; the audio waits for it.")
                .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let p = model.download {
                ProgressView(value: p.fraction)
                HStack {
                    Text("\(p.phase) \(Int(p.fraction * 100))%")
                    Spacer()
                    if p.bytesPerSecond > 0 {
                        Text(String(format: "%.1f MB/s", p.bytesPerSecond / 1_000_000)).monospacedDigit()
                    }
                }
                .font(.caption).foregroundStyle(.secondary)
            } else {
                if let e = model.downloadError { Text(e).font(.caption).foregroundStyle(.red) }
                Button(model.downloadError == nil ? "Download (490 MB)" : "Try again") { model.downloadModels() }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.accent.opacity(0.08)))
    }
}

/// The two grants, with what each one is for, in the words macOS will not use.
struct PermissionsCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Before your first meeting").font(Theme.serif(.headline, .semibold))
            row(done: model.micStatus == .authorized, title: "Microphone",
                detail: "Your side of the conversation.",
                action: model.micStatus == .denied ? "Open Settings" : "Allow", perform: model.requestMic)
            if model.captureSystem {
                row(done: model.screenGranted, title: "Screen Recording",
                    detail: "How macOS lets an app hear the call. Only the sound is kept — the picture is a 2×2-pixel frame that is thrown away. Nothing leaves this Mac. After allowing it, quit and reopen the app.",
                    action: "Allow", perform: model.requestScreen)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Color.primary.opacity(0.12)))
    }

    private func row(done: Bool, title: String, detail: String, action: String, perform: @escaping () -> Void) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? Theme.accent : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.callout.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if !done { Button(action, action: perform).controlSize(.small) }
        }
    }
}

/// One word for where a meeting is, or nothing when it is simply done.
struct StateBadge: View {
    let meeting: Meeting
    var finishing = false

    var body: some View {
        switch meeting.state {
        case .recording:
            badge("Recording", Theme.record, "circle.fill")
        case .transcribing:
            if let p = Jobs.shared.progress(of: meeting.id) {
                badge("\(Int(p * 100))%", Theme.accent, "waveform")
            } else {
                badge(finishing ? "Finishing" : "Transcribing", Theme.accent, "waveform")
            }
        case .recorded:
            badge(meeting.error == Jobs.waitingForModels ? "Waiting for model" : (meeting.error ?? "Queued"), .secondary, "clock")
        case .failed:
            badge("Failed", .red, "exclamationmark.triangle.fill")
        case .done:
            EmptyView()
        }
    }

    private func badge(_ text: String, _ color: Color, _ icon: String) -> some View {
        Label(text, systemImage: icon)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.12)))
            .labelStyle(.titleAndIcon)
            .imageScale(.small)
    }
}
