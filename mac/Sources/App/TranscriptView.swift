import AppKit
import MeetingCore
import SwiftUI
import UniformTypeIdentifiers

/// A row of the transcript: something said, or a moment marked while recording.
enum TranscriptItem: Identifiable {
    case spoken(Line)
    case marked(Mark)

    var id: String {
        switch self {
        case .spoken(let l): return "l\(l.id)"
        case .marked(let m): return "m\(m.id)"
        }
    }

    /// Lines in order, each mark placed before the first line at or after it.
    static func merge(_ lines: [Line], _ marks: [Mark]) -> [TranscriptItem] {
        var pending = marks.sorted { $0.tMs < $1.tMs }[...]
        var out: [TranscriptItem] = []
        for l in lines {
            while let m = pending.first, m.tMs <= l.tStartMs { out.append(.marked(m)); pending = pending.dropFirst() }
            out.append(.spoken(l))
        }
        out += pending.map(TranscriptItem.marked)
        return out
    }
}

struct TranscriptView: View {
    let id: Int64
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var player: Player

    @State private var meeting: Meeting?
    @State private var lines: [Line] = []
    @State private var marks: [Mark] = []
    @State private var names: [Int: String] = [:]
    @State private var title = ""
    @State private var find = ""
    @State private var findIndex = 0
    @FocusState private var findFocused: Bool
    @State private var renaming: Int?
    @State private var speakerName = ""
    @State private var confirmDelete = false
    @State private var confirmRetranscribe = false
    @State private var toast: String?

    /// Show a fresh timestamp at most this often, so the page reads as prose.
    private static let timeGapMs: Int64 = 30_000

    var body: some View {
        VStack(spacing: 0) {
            if let m = meeting {
                header(m)
                banner(m)
                Divider()
                transcript(m)
            }
        }
        .onAppear(perform: reload)
        .onReceive(NotificationCenter.default.publisher(for: .storeChanged)) { note in
            if note.object == nil || note.object as? Int64 == id { reload() }
        }
        .toolbar { toolbar }
        .overlay(alignment: .bottom) {
            if let t = toast {
                Text(t).font(.callout.weight(.medium)).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Capsule().fill(.regularMaterial)).padding(20)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .alert("Who is “\(renaming.flatMap { Source(rawValue: $0)?.defaultName } ?? "")”?", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $speakerName)
            Button("Save") {
                if let s = renaming { Store.shared.setName(id, speaker: s, name: speakerName) }
                renaming = nil
            }
            Button("Cancel", role: .cancel) { renaming = nil }
        } message: {
            Text(renaming == Source.system.rawValue
                 ? "Everyone on the call shares one label. Give it a name for this meeting, like a person or a team."
                 : "The name used for lines from this Mac's microphone in this meeting.")
        }
        .confirmationDialog("Delete “\(meeting.map(Exporters.title) ?? "")”?", isPresented: $confirmDelete) {
            Button("Delete Meeting and Audio", role: .destructive) {
                if player.meetingId == id { player.stop() }
                Library.delete(id)
                model.selection = nil
            }
        } message: {
            Text(deleteSummary)
        }
        .confirmationDialog("Transcribe again?", isPresented: $confirmRetranscribe) {
            Button("Transcribe Again") { Jobs.shared.enqueue(id) }
        } message: {
            Text("The current \(lines.count) lines are replaced with a fresh transcript of the audio. Speaker names are kept.")
        }
    }

    private func reload() {
        let s = Store.shared
        meeting = s.meeting(id)
        lines = s.lines(id)
        marks = s.marks(id)
        names = s.names(id)
        if let m = meeting, !titleFocusedEditing { title = m.title }
    }

    @State private var titleFocusedEditing = false

    // MARK: header

    private func header(_ m: Meeting) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("Untitled meeting", text: $title, onEditingChanged: { editing in
                titleFocusedEditing = editing
                if !editing { commitTitle() }
            })
            .onSubmit(commitTitle)
            .textFieldStyle(.plain)
            .font(Theme.serif(.title, .semibold))
            HStack(spacing: 10) {
                Text(meta(m)).foregroundStyle(.secondary)
                if lines.contains(where: { $0.speaker >= 0 }) {
                    ForEach(Source.allCases, id: \.rawValue) { s in
                        Button { rename(s.rawValue) } label: {
                            Text(Exporters.label(s.rawValue, names) ?? "")
                                .font(.caption.weight(.semibold)).foregroundStyle(Theme.speaker(s.rawValue))
                                .padding(.horizontal, 8).padding(.vertical, 2)
                                .background(Capsule().fill(Theme.speaker(s.rawValue).opacity(0.14)))
                        }
                        .buttonStyle(.plain)
                        .help("Rename “\(Exporters.label(s.rawValue, names) ?? "")” in this meeting")
                    }
                }
                Spacer()
                findField
            }
            .font(.callout)
        }
        .padding(.horizontal, 24).padding(.top, 18).padding(.bottom, 12)
    }

    private func commitTitle() {
        if title != meeting?.title { Store.shared.rename(id, to: title) }
    }

    private func meta(_ m: Meeting) -> String {
        let f = DateFormatter()
        f.dateStyle = .full
        f.timeStyle = .short
        var s = f.string(from: m.startedAt)
        if m.durationMs > 0 { s += " · " + Exporters.timestamp(m.durationMs) }
        if !lines.isEmpty { s += " · \(lines.count) lines" }
        return s
    }

    // MARK: find

    private var matches: [(line: Int64, occurrence: Int)] {
        guard !find.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        return lines.flatMap { l in (0..<Highlight.count(l.text, find)).map { (l.id, $0) } }
    }

    private var findField: some View {
        HStack(spacing: 4) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Find in transcript", text: $find)
                .textFieldStyle(.plain).frame(width: 150)
                .focused($findFocused)
                .onSubmit { step(1) }
                .onChange(of: find) { findIndex = 0 }
            if !find.isEmpty {
                let n = matches.count
                Text(n == 0 ? "None" : "\(min(findIndex, n - 1) + 1) of \(n)").font(.caption).monospacedDigit().foregroundStyle(.secondary)
                Button { step(-1) } label: { Image(systemName: "chevron.up") }.disabled(n == 0)
                Button { step(1) } label: { Image(systemName: "chevron.down") }.disabled(n == 0)
            }
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 7).fill(Color.primary.opacity(0.06)))
        .background(Button("") { findFocused = true }.keyboardShortcut("f").hidden())
    }

    private func step(_ d: Int) {
        let n = matches.count
        guard n > 0 else { return }
        findIndex = ((findIndex + d) % n + n) % n
    }

    // MARK: status

    @ViewBuilder private func banner(_ m: Meeting) -> some View {
        switch m.state {
        case .recording:
            bannerRow("Recording. Lines appear here as people speak.", "record.circle", Theme.record)
        case .transcribing:
            HStack(spacing: 10) {
                if let p = Jobs.shared.progress(of: id) {
                    ProgressView(value: p).frame(width: 160)
                    Text("Transcribing on this Mac · \(Int(p * 100))%")
                } else {
                    ProgressView().controlSize(.small)
                    Text("Finishing the last lines…")
                }
                Spacer()
            }
            .font(.callout).foregroundStyle(.secondary)
            .padding(.horizontal, 24).padding(.bottom, 10)
        case .recorded:
            HStack {
                bannerText(m.error == Jobs.waitingForModels
                           ? "The audio is saved. It is transcribed as soon as the speech model is downloaded."
                           : "Queued for transcription.", "clock", .secondary)
                Spacer()
                if !model.modelsReady && model.download == nil {
                    Button("Download model") { model.downloadModels() }
                } else if let p = model.download {
                    ProgressView(value: p.fraction).frame(width: 120)
                }
            }
            .padding(.horizontal, 24).padding(.bottom, 10)
        case .failed:
            HStack {
                bannerText(m.error ?? "Transcription failed.", "exclamationmark.triangle.fill", .red)
                Spacer()
                if Audio.length(meeting: id) > 0 { Button("Try again") { Jobs.shared.enqueue(id) } }
            }
            .padding(.horizontal, 24).padding(.bottom, 10)
        case .done:
            if lines.isEmpty {
                bannerRow("No speech was heard in this recording.", "speaker.slash", .secondary)
            }
        }
    }

    private func bannerRow(_ text: String, _ icon: String, _ tint: Color) -> some View {
        HStack { bannerText(text, icon, tint); Spacer() }.padding(.horizontal, 24).padding(.bottom, 10)
    }

    private func bannerText(_ text: String, _ icon: String, _ tint: Color) -> some View {
        Label { Text(text) } icon: { Image(systemName: icon).foregroundStyle(tint) }.font(.callout)
    }

    // MARK: transcript

    private func transcript(_ m: Meeting) -> some View {
        let items = TranscriptItem.merge(lines, marks)
        let current = matches.isEmpty ? nil : matches[min(findIndex, matches.count - 1)]
        let playing = player.isPlaying && player.meetingId == id ? player.positionMs : nil
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                        switch item {
                        case .marked(let mk):
                            MarkRow(mark: mk, onPlay: { player.play(meeting: id, fromMs: mk.tMs) },
                                    onDelete: { Store.shared.deleteMark(mk.id, meeting: id) })
                        case .spoken(let l):
                            let prev = previousLine(items, before: i)
                            LineRow(
                                line: l,
                                showTime: prev == nil || l.tStartMs - prev!.tStartMs >= Self.timeGapMs,
                                speaker: prev?.speaker == l.speaker ? nil : Exporters.label(l.speaker, names),
                                text: Highlight.attributed(l.text, find, current: current?.line == l.id ? current?.occurrence : nil),
                                isPlaying: playing.map { $0 >= l.tStartMs && $0 < l.tEndMs + 300 } ?? false,
                                onPlay: { player.play(meeting: id, fromMs: l.tStartMs) },
                                onSpeaker: { rename(l.speaker) },
                                onCopy: { copy(l.text, "Line copied") },
                                onCopyWithTime: { copy("[\(Exporters.timestamp(l.tStartMs))] \(Exporters.label(l.speaker, names).map { "\($0): " } ?? "")\(l.text)", "Line copied") }
                            )
                            .id(l.id)
                        }
                    }
                    if m.state == .recording && lines.isEmpty {
                        Label("Listening…", systemImage: "waveform").foregroundStyle(.secondary).padding(24)
                    }
                    Color.clear.frame(height: 40).id("end")
                }
                .padding(.horizontal, 12).padding(.vertical, 12)
            }
            .onChange(of: lines.count) {
                if meeting?.state == .recording { withAnimation { proxy.scrollTo("end", anchor: .bottom) } }
            }
            .onChange(of: findIndex) { if let c = current { withAnimation { proxy.scrollTo(c.line, anchor: .center) } } }
            .onChange(of: find) { if let c = matches.first { proxy.scrollTo(c.line, anchor: .center) } }
            .onChange(of: player.positionMs) { _, ms in
                // Follow playback, a line at a time.
                guard player.isPlaying, player.meetingId == id,
                      let l = lines.last(where: { $0.tStartMs <= ms }) else { return }
                proxy.scrollTo(l.id, anchor: .center)
            }
        }
    }

    private func previousLine(_ items: [TranscriptItem], before i: Int) -> Line? {
        var j = i - 1
        while j >= 0 {
            if case .spoken(let l) = items[j] { return l }
            j -= 1
        }
        return nil
    }

    private func rename(_ speaker: Int) {
        speakerName = names[speaker] ?? ""
        renaming = speaker
    }

    // MARK: actions

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup {
            Button {
                player.toggle(meeting: id, fromMs: 0)
            } label: {
                Label(player.isPlaying && player.meetingId == id ? "Pause" : "Play",
                      systemImage: player.isPlaying && player.meetingId == id ? "pause.fill" : "play.fill")
            }
            .help("Play the meeting, with both sides together. Hover over a line's time to play from there.")
            .disabled(meeting?.state == .recording)

            Button {
                guard let m = meeting else { return }
                copy(Exporters.promptReady(m, lines, names), "Copied with instructions. Paste it into any chatbot.")
            } label: {
                Label("Copy for Chatbot", systemImage: "sparkles")
            }
            .help("Copies the transcript with instructions for a summary, decisions and action items, citing line numbers")
            .disabled(lines.isEmpty)

            Menu {
                ForEach(Exporters.Format.allCases) { f in
                    Button(f.title + "…") { export(f) }
                }
            } label: {
                Label("Export", systemImage: "square.and.arrow.up")
            }
            .disabled(lines.isEmpty)

            Menu {
                Button("Copy Transcript") {
                    if let m = meeting { copy(Exporters.plainText(m, lines, names), "Transcript copied") }
                }
                .disabled(lines.isEmpty)
                Button("Transcribe Again…") { confirmRetranscribe = true }
                    .disabled(meeting?.state == .recording || meeting?.state == .transcribing || !model.modelsReady || Audio.length(meeting: id) == 0)
                Button("Show Audio in Finder") {
                    NSWorkspace.shared.activateFileViewerSelecting([Paths.audio(meeting: id)])
                }
                Divider()
                Button("Delete…", role: .destructive) { confirmDelete = true }
                    .disabled(meeting?.state == .recording)
            } label: {
                Label("More", systemImage: "ellipsis.circle")
            }
        }
    }

    private var deleteSummary: String {
        let bytes = Source.allCases.reduce(Int64(0)) { $0 + Audio.bytes(in: Paths.audio(meeting: id, source: $1)) }
        let mb = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        return "Removes \(lines.count) lines, \(marks.count) marks and \(Exporters.timestamp(meeting?.durationMs ?? 0)) of audio (\(mb)) from this Mac. This can’t be undone."
    }

    private func copy(_ text: String, _ message: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        withAnimation { toast = message }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { withAnimation { if toast == message { toast = nil } } }
    }

    private func export(_ f: Exporters.Format) {
        guard let m = meeting else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = Exporters.safeName(Exporters.title(m)) + "." + f.ext
        if let t = UTType(filenameExtension: f.ext) { panel.allowedContentTypes = [t] }
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try Exporters.render(f, m, lines, names).write(to: url, atomically: true, encoding: .utf8)
            withAnimation { toast = "Saved \(url.lastPathComponent)" }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { withAnimation { toast = nil } }
        } catch {
            model.errorMessage = "Could not save: \(error.localizedDescription)"
        }
    }
}

struct LineRow: View {
    let line: Line
    let showTime: Bool
    /// The label, only on the first line of a run by the same speaker.
    let speaker: String?
    let text: AttributedString
    let isPlaying: Bool
    let onPlay: () -> Void
    let onSpeaker: () -> Void
    let onCopy: () -> Void
    let onCopyWithTime: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Button(action: onPlay) {
                Group {
                    if hover {
                        Image(systemName: "play.fill").font(.caption2)
                    } else {
                        Text(showTime ? Exporters.timestamp(line.tStartMs) : "")
                    }
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(hover ? Theme.accent : .secondary)
                .frame(width: 54, alignment: .trailing)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Play from \(Exporters.timestamp(line.tStartMs))")

            VStack(alignment: .leading, spacing: 4) {
                if let s = speaker {
                    Button(action: onSpeaker) {
                        Text(s).font(.caption.weight(.semibold)).foregroundStyle(Theme.speaker(line.speaker))
                    }
                    .buttonStyle(.plain)
                    .help("Rename")
                    .padding(.top, 8)
                }
                Text(text)
                    .font(Theme.serif(.body))
                    .lineSpacing(3)
                    .textSelection(.enabled)
                    .frame(maxWidth: 720, alignment: .leading)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3).padding(.horizontal, 6)
        .background(RoundedRectangle(cornerRadius: 6).fill(isPlaying ? Theme.accent.opacity(0.1) : .clear))
        .onHover { hover = $0 }
        .contextMenu {
            Button("Play from Here", action: onPlay)
            Button("Copy Line", action: onCopy)
            Button("Copy with Time and Speaker", action: onCopyWithTime)
        }
    }
}

struct MarkRow: View {
    let mark: Mark
    let onPlay: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(Exporters.timestamp(mark.tMs)).font(.caption.monospacedDigit()).foregroundStyle(Theme.accent)
                .frame(width: 54, alignment: .trailing)
            Label("Marked", systemImage: "bookmark.fill").font(.caption.weight(.semibold)).foregroundStyle(Theme.accent)
            Rectangle().fill(Theme.accent.opacity(0.3)).frame(height: 1)
        }
        .padding(.vertical, 8).padding(.horizontal, 6)
        .contentShape(Rectangle())
        .onTapGesture(perform: onPlay)
        .contextMenu {
            Button("Play from Here", action: onPlay)
            Button("Remove Mark", role: .destructive, action: onDelete)
        }
    }
}
