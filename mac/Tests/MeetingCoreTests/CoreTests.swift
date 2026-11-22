import Foundation
@testable import MeetingCore

// Unit checks as a plain executable: `swift run CoreChecks`. Without Xcode
// there is no XCTest, and the Command Line Tools' Swift Testing runner finds
// no tests in a package, so this is the form that runs on any Mac.

var failures = 0
var current = ""

func expect(_ ok: @autoclosure () -> Bool, _ line: Int = #line) {
    if !ok() {
        failures += 1
        print("  ✘ \(current) — line \(line)")
    }
}

struct CoreTests {
    func echoMatchesOverlappingSameWords() {
        let sys = LineSplitter.Piece(startMs: 1_000, endMs: 5_000, text: "The budget review moves to Thursday afternoon.")
        let mic = LineSplitter.Piece(startMs: 1_200, endMs: 5_100, text: "budget review moves to thursday")
        expect(Echo.matches(mic: mic, system: sys))
    }

    func echoIgnoresReplyAndDistantRepeat() {
        let sys = LineSplitter.Piece(startMs: 1_000, endMs: 5_000, text: "The budget review moves to Thursday afternoon.")
        expect(!Echo.matches(mic: .init(startMs: 5_500, endMs: 7_000, text: "Yes, that works."), system: sys))
        expect(!Echo.matches(mic: .init(startMs: 60_000, endMs: 64_000, text: "The budget review moves to Thursday."), system: sys))
        expect(!Echo.matches(mic: .init(startMs: 1_000, endMs: 2_000, text: "Thursday"), system: sys))
    }

    func timestamps() {
        expect(Exporters.timestamp(0) == "0:00")
        expect(Exporters.timestamp(65_000) == "1:05")
        expect(Exporters.timestamp(3_723_000) == "1:02:03")
        expect(Exporters.srtTime(3_723_456) == "01:02:03,456")
    }

    func jsonEscapesAndLabels() {
        let m = Meeting(id: 1, title: "Q\"3\"\nplan", startedAt: Date(timeIntervalSince1970: 1), durationMs: 5_000,
                        state: .done, error: nil, asrModel: "m", snippet: nil)
        let lines = [Line(id: 1, meetingId: 1, tStartMs: 0, tEndMs: 900, text: "Hi\tthere", speaker: 1)]
        let json = Exporters.json(m, lines, [1: "Acme"])
        expect(json.contains(#""title": "Q\"3\"\nplan""#))
        expect(json.contains(#""speaker_name": "Acme""#))
        expect(json.contains(#""text": "Hi\tthere""#))
        expect((try? JSONSerialization.jsonObject(with: Data(json.utf8))) != nil)
        expect(Exporters.label(0, [:]) == "You")
        expect(Exporters.label(1, [:]) == "Them")
    }

    func segmentWriterRollsAndResumes() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("seg-\(UUID())")
        var w = try SegmentWriter(dir: dir)
        try w.write([Int16](repeating: 1, count: Audio.samplesPerSegment + 100))
        w.finish()
        expect(Audio.segments(in: dir).count == 2)
        w = try SegmentWriter(dir: dir)
        expect(w.totalSamples == Audio.samplesPerSegment + 100)
        try w.write([2, 3])
        w.finish()
        expect(Audio.bytes(in: dir) == Int64(Audio.samplesPerSegment + 102) * 2)
    }

    func storeRoundTripAndSearch() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID()).sqlite")
        let s = Store(url: url)
        let id = s.createMeeting(startedAt: Date())
        s.insertLine(meeting: id, startMs: 0, endMs: 1000, text: "We ship on Monday", speaker: 1)
        s.insertLine(meeting: id, startMs: 1000, endMs: 2000, text: "Agreed", speaker: 0)
        s.rename(id, to: "  Launch sync ")
        s.setName(id, speaker: 1, name: "Acme")
        s.addMark(id, atMs: 1500)
        expect(s.meeting(id)?.title == "Launch sync")
        expect(s.lines(id).map(\.text) == ["We ship on Monday", "Agreed"])
        expect(s.meetings(matching: "monday").first?.snippet == "We ship on Monday")
        expect(s.meetings(matching: "100%").isEmpty)
        expect(s.names(id) == [1: "Acme"])
        expect(s.marks(id).count == 1)
        s.delete(id)
        expect(s.meeting(id) == nil)
        expect(s.lines(id).isEmpty)
    }

    func callAppsByBundleID() {
        expect(CallApps.name(forBundleID: "us.zoom.xos") == "Zoom")
        expect(CallApps.name(forBundleID: "com.microsoft.teams2") == "Teams")
        expect(CallApps.name(forBundleID: "com.google.Chrome.helper") == "Chrome")
        expect(CallApps.name(forBundleID: "com.apple.WebKit.GPU") == "Safari")
        expect(CallApps.name(forBundleID: "company.thebrowser.browser.helper") == "Arc")
        expect(CallApps.name(forBundleID: "com.apple.SpeechRecognitionCore.speechrecognitiond") == nil)
        expect(CallApps.name(forBundleID: "com.apple.VoiceMemos") == nil)
    }

    func callTrackerDebouncesBothEdges() {
        var t = CallTracker(startAfter: 2, endAfter: 3)
        // A one-poll blip is never a call.
        expect(t.update(["Chrome"]).isEmpty)
        expect(t.update([]).isEmpty)
        expect(t.update(["Chrome"]).isEmpty)
        expect(t.update([]).isEmpty)
        // Two polls in a row is.
        expect(t.update(["Zoom"]).isEmpty)
        expect(t.update(["Zoom"]) == [.started("Zoom")])
        expect(t.update(["Zoom"]).isEmpty)
        // A short gap, such as a headset being plugged in, is not an end.
        expect(t.update([]).isEmpty)
        expect(t.update([]).isEmpty)
        expect(t.update(["Zoom"]).isEmpty)
        expect(t.active == ["Zoom"])
        // A long one is.
        expect(t.update([]).isEmpty)
        expect(t.update([]).isEmpty)
        expect(t.update([]) == [.ended("Zoom")])
        expect(t.active.isEmpty)
        // And a new call after it is offered again.
        expect(t.update(["Zoom"]).isEmpty)
        expect(t.update(["Zoom"]) == [.started("Zoom")])
    }

    func eventPickerChoosesTheMeetingNow() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func at(_ min: Double) -> Date { now.addingTimeInterval(min * 60) }
        let focus = EventCandidate(title: "Focus time", start: at(-60), end: at(60))
        let standup = EventCandidate(title: "Standup", start: at(-5), end: at(10), attendees: ["Sam"], hasCallLink: true)
        let next = EventCandidate(title: "Design review", start: at(2), end: at(30), attendees: ["Priya"], hasCallLink: true)
        let holiday = EventCandidate(title: "Holiday", start: at(-600), end: at(600), isAllDay: true)
        let declined = EventCandidate(title: "All hands", start: at(-1), end: at(60), declined: true, attendees: ["Everyone"], hasCallLink: true)
        let later = EventCandidate(title: "1:1", start: at(20), end: at(50), attendees: ["Alex"], hasCallLink: true)
        let over = EventCandidate(title: "Earlier", start: at(-60), end: at(-1), attendees: ["Sam"], hasCallLink: true)

        // A call with people beats a solo block.
        expect(EventPicker.best([focus, standup], at: now)?.title == "Standup")
        // Back to back: the one whose start is nearest wins.
        expect(EventPicker.best([standup, next], at: now)?.title == "Design review")
        // Not all-day, not declined, not too far ahead, not already over.
        expect(EventPicker.best([holiday, declined, later, over], at: now) == nil)
        // A solo block is still better than nothing.
        expect(EventPicker.best([focus, holiday], at: now)?.title == "Focus time")
    }

    func callLinks() {
        expect(CallLinks.contains(nil, "https://us02web.zoom.us/j/123", nil))
        expect(CallLinks.contains("https://meet.google.com/abc-defg-hij"))
        expect(!CallLinks.contains("Room 4B", nil, "Bring the slides"))
    }

    func notesExportNamesAndRewrites() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("notes-\(UUID())")
        var m = Meeting(id: 7, title: "Q3: plan/review", startedAt: Date(), durationMs: 65_000,
                        state: .done, error: nil, asrModel: "m", snippet: nil)
        let lines = [Line(id: 1, meetingId: 7, tStartMs: 0, tEndMs: 900, text: "We ship Monday", speaker: 1)]
        expect(NotesExport.sanitize("Q3: plan/review") == "Q3 plan review")
        expect(NotesExport.sanitize("  \n ") == "Meeting")

        let first = try NotesExport.write(m, lines, [:], to: dir)
        expect(first.lastPathComponent.hasSuffix(" Q3 plan review.md"))
        let text = try String(contentsOf: first, encoding: .utf8)
        expect(text.hasPrefix("---\ntitle: \"Q3: plan/review\"\n"))
        expect(text.contains("meeting_id: 7\n"))
        expect(text.contains("We ship Monday"))
        expect(NotesExport.meetingId(inNoteAt: first) == 7)

        // A rename writes the new name and removes the old file.
        m.title = "Launch"
        let second = try NotesExport.write(m, lines, [:], to: dir, previous: first)
        expect(second.lastPathComponent.hasSuffix(" Launch.md"))
        expect(!FileManager.default.fileExists(atPath: first.path))

        // A file of the user's under the old name is never removed.
        try "my own notes".write(to: first, atomically: true, encoding: .utf8)
        m.title = "Launch v2"
        _ = try NotesExport.write(m, lines, [:], to: dir, previous: first)
        expect(FileManager.default.fileExists(atPath: first.path))

        // Nor overwritten by a different meeting that happens to share a name.
        let user = dir.appendingPathComponent(NotesExport.fileName(m))
        try "mine".write(to: user, atomically: true, encoding: .utf8)
        let other = try NotesExport.write(m, lines, [:], to: dir)
        expect(other != user)
        expect((try? String(contentsOf: user, encoding: .utf8)) == "mine")
        try? FileManager.default.removeItem(at: dir)
    }
}

let t = CoreTests()
let checks: [(String, () throws -> Void)] = [
    ("echo matches overlapping same words", t.echoMatchesOverlappingSameWords),
    ("echo ignores replies and distant repeats", t.echoIgnoresReplyAndDistantRepeat),
    ("timestamps", t.timestamps),
    ("json escapes and labels", t.jsonEscapesAndLabels),
    ("segment writer rolls and resumes", t.segmentWriterRollsAndResumes),
    ("store round trip and search", t.storeRoundTripAndSearch),
    ("call apps by bundle id", t.callAppsByBundleID),
    ("call tracker debounces both edges", t.callTrackerDebouncesBothEdges),
    ("event picker chooses the meeting now", t.eventPickerChoosesTheMeetingNow),
    ("call links", t.callLinks),
    ("notes export names and rewrites", t.notesExportNamesAndRewrites),
]
for (name, check) in checks {
    current = name
    let before = failures
    do { try check() } catch { failures += 1; print("  ✘ \(name) — threw \(error)") }
    print(failures == before ? "✔ \(name)" : "✘ \(name)")
}
print(failures == 0 ? "All \(checks.count) checks passed" : "\(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
