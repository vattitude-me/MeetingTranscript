import Foundation

/// Picks the calendar event a recording belongs to, so a meeting is called
/// "Weekly sync" instead of "Untitled meeting".
///
/// Pure on purpose: EventKit hands over events, this decides between them, and
/// the deciding is what needs checks. EventKit lives in the app target.
public struct EventCandidate: Sendable, Equatable {
    public var title: String
    public var start: Date
    public var end: Date
    public var isAllDay: Bool
    /// The user declined, or the organiser cancelled.
    public var declined: Bool
    public var attendees: [String]
    /// Its URL, location or notes hold a Zoom/Meet/Teams-style link.
    public var hasCallLink: Bool

    public init(title: String, start: Date, end: Date, isAllDay: Bool = false, declined: Bool = false,
                attendees: [String] = [], hasCallLink: Bool = false) {
        self.title = title
        self.start = start
        self.end = end
        self.isAllDay = isAllDay
        self.declined = declined
        self.attendees = attendees
        self.hasCallLink = hasCallLink
    }
}

public enum EventPicker {
    /// How early a recording may start and still belong to an event. People
    /// join a couple of minutes early; ten covers that without reaching back
    /// into the previous meeting's slot.
    public static let earlyJoin: TimeInterval = 10 * 60

    /// The event happening at [now], or nil when nothing plausible is on.
    ///
    /// Ignores all-day events (a holiday is not a meeting), declined and
    /// cancelled ones, and untitled ones. Among the rest, an event with a call
    /// link beats one without, one with attendees beats a solo block ("Focus
    /// time"), and then the one whose start is nearest wins, which is the
    /// back-to-back case: at 10:59 the 11:00 meeting, not the 10:00 one.
    public static func best(_ events: [EventCandidate], at now: Date) -> EventCandidate? {
        let live = events.filter {
            !$0.isAllDay && !$0.declined
                && !$0.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && $0.start <= now.addingTimeInterval(earlyJoin)
                && $0.end > now
        }
        return live.min { a, b in
            let sa = score(a), sb = score(b)
            if sa != sb { return sa > sb }
            return abs(a.start.timeIntervalSince(now)) < abs(b.start.timeIntervalSince(now))
        }
    }

    private static func score(_ e: EventCandidate) -> Int {
        (e.hasCallLink ? 2 : 0) + (e.attendees.isEmpty ? 0 : 1)
    }
}

public enum CallLinks {
    static let hosts = [
        "zoom.us/", "meet.google.com/", "teams.microsoft.com/", "teams.live.com/",
        "webex.com/", "facetime.apple.com/", "whereby.com/", "around.co/",
    ]

    /// True when any of the texts holds a link to a known call service.
    public static func contains(_ texts: String?...) -> Bool {
        texts.contains { t in
            guard let t = t?.lowercased() else { return false }
            return hosts.contains { t.contains($0) }
        }
    }
}
