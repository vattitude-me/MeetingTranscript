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
}

let t = CoreTests()
let checks: [(String, () throws -> Void)] = [
    ("echo matches overlapping same words", t.echoMatchesOverlappingSameWords),
    ("echo ignores replies and distant repeats", t.echoIgnoresReplyAndDistantRepeat),
    ("timestamps", t.timestamps),
    ("json escapes and labels", t.jsonEscapesAndLabels),
    ("segment writer rolls and resumes", t.segmentWriterRollsAndResumes),
    ("store round trip and search", t.storeRoundTripAndSearch),
]
for (name, check) in checks {
    current = name
    let before = failures
    do { try check() } catch { failures += 1; print("  ✘ \(name) — threw \(error)") }
    print(failures == before ? "✔ \(name)" : "✘ \(name)")
}
print(failures == 0 ? "All \(checks.count) checks passed" : "\(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
