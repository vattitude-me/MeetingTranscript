import MeetingCore
import SwiftUI

/// Every meeting, searchable by title and by anything said in it.
struct LibraryView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var meetings: [Meeting] = []

    var body: some View {
        NavigationSplitView {
            List(selection: $model.selection) {
                ForEach(meetings) { m in
                    MeetingRow(meeting: m, query: query, finishing: model.finishing.contains(m.id)).tag(m.id)
                }
            }
            .searchable(text: $query, placement: .sidebar, prompt: "Search meetings and what was said")
            .navigationSplitViewColumnWidth(min: 240, ideal: 300)
            .overlay {
                if meetings.isEmpty { emptyList }
            }
            .safeAreaInset(edge: .bottom) { recordBar }
        } detail: {
            if let id = model.selection, meetings.contains(where: { $0.id == id }) || model.recording?.meetingId == id {
                TranscriptView(id: id).id(id)
            } else {
                placeholder
            }
        }
        .onAppear(perform: reload)
        .onChange(of: query) { reload() }
        .onReceive(NotificationCenter.default.publisher(for: .storeChanged)) { _ in reload() }
    }

    private func reload() {
        meetings = Store.shared.meetings(matching: query)
    }

    private var recordBar: some View {
        Button(action: model.toggleRecording) {
            Label(model.isRecording ? "Stop · \(Exporters.timestamp(model.elapsedMs))" : "Record meeting",
                  systemImage: model.isRecording ? "stop.fill" : "record.circle")
                .frame(maxWidth: .infinity)
        }
        .controlSize(.large)
        .buttonStyle(.borderedProminent)
        .tint(model.isRecording ? .primary : Theme.record)
        .disabled(model.starting || !model.canRecord)
        .padding(12)
    }

    @ViewBuilder private var emptyList: some View {
        VStack(spacing: 8) {
            Image(systemName: query.isEmpty ? "waveform" : "magnifyingglass").font(.largeTitle).foregroundStyle(.tertiary)
            Text(query.isEmpty ? "No meetings yet" : "Nothing matches “\(query)”").font(Theme.serif(.headline))
            Text(query.isEmpty ? "Press Record when the call starts. Lines appear as people speak." : "Search looks in titles and in every line of every transcript.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding(24)
    }

    private var placeholder: some View {
        VStack(spacing: 10) {
            Image(systemName: "text.quote").font(.system(size: 40)).foregroundStyle(.tertiary)
            Text(meetings.isEmpty ? "Your transcripts will appear here" : "Choose a meeting").font(Theme.serif(.title3))
            Text("Everything is recorded and transcribed on this Mac.").foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct MeetingRow: View {
    let meeting: Meeting
    let query: String
    let finishing: Bool

    private static let date: DateFormatter = {
        let f = DateFormatter()
        f.doesRelativeDateFormatting = true
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline) {
                if meeting.title.isEmpty {
                    Text("Untitled meeting").italic().foregroundStyle(.secondary)
                } else {
                    Text(meeting.title)
                }
                Spacer(minLength: 4)
                StateBadge(meeting: meeting, finishing: finishing)
            }
            .font(Theme.serif(.body, .medium))
            .lineLimit(1)
            Text(Self.date.string(from: meeting.startedAt) + (meeting.durationMs > 0 ? " · " + Exporters.timestamp(meeting.durationMs) : ""))
                .font(.caption).foregroundStyle(.secondary)
            if let s = meeting.snippet, !query.isEmpty {
                Text(Highlight.attributed(Highlight.around(s, query), query))
                    .font(.caption).lineLimit(2).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 3)
    }
}

enum Highlight {
    /// Every case-insensitive match of [query] tinted, for rows and lines.
    static func attributed(_ text: String, _ query: String, current: Int? = nil) -> AttributedString {
        var out = AttributedString(text)
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return out }
        var n = 0
        var search = text.startIndex..<text.endIndex
        while let r = text.range(of: q, options: [.caseInsensitive, .diacriticInsensitive], range: search) {
            if let a = AttributedString.Index(r.lowerBound, within: out), let b = AttributedString.Index(r.upperBound, within: out) {
                out[a..<b].backgroundColor = n == current ? Theme.accent : Theme.accent.opacity(0.25)
                if n == current { out[a..<b].foregroundColor = .white }
            }
            n += 1
            search = r.upperBound..<text.endIndex
        }
        return out
    }

    static func count(_ text: String, _ query: String) -> Int {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return 0 }
        var n = 0
        var search = text.startIndex..<text.endIndex
        while let r = text.range(of: q, options: [.caseInsensitive, .diacriticInsensitive], range: search) {
            n += 1
            search = r.upperBound..<text.endIndex
        }
        return n
    }

    /// A long matching line, trimmed so the match is visible in two lines.
    static func around(_ text: String, _ query: String) -> String {
        guard let r = text.range(of: query, options: .caseInsensitive) else { return text }
        let start = text.index(r.lowerBound, offsetBy: -40, limitedBy: text.startIndex) ?? text.startIndex
        return (start == text.startIndex ? "" : "…") + text[start...]
    }
}
