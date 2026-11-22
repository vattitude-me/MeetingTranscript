import EventKit
import MeetingCore

/// Reads the calendar, only to name a meeting after the event it belongs to.
/// Read-only, on this Mac, and only at the moment a recording starts.
@MainActor
final class CalendarTitles {
    static let shared = CalendarTitles()
    private let store = EKEventStore()

    var authorized: Bool { EKEventStore.authorizationStatus(for: .event) == .fullAccess }

    /// Shows the system prompt the first time. After a "no", macOS only
    /// changes it in System Settings.
    func requestAccess() async -> Bool {
        if authorized { return true }
        return (try? await store.requestFullAccessToEvents()) ?? false
    }

    func event(at now: Date = Date()) -> EventCandidate? {
        guard authorized else { return nil }
        let window = store.predicateForEvents(withStart: now.addingTimeInterval(-12 * 3600),
                                              end: now.addingTimeInterval(EventPicker.earlyJoin + 60),
                                              calendars: nil)
        let candidates = store.events(matching: window).map { e in
            let me = e.attendees?.first(where: \.isCurrentUser)
            return EventCandidate(
                title: e.title ?? "",
                start: e.startDate,
                end: e.endDate,
                isAllDay: e.isAllDay,
                declined: e.status == .canceled || me?.participantStatus == .declined,
                attendees: (e.attendees ?? []).filter { !$0.isCurrentUser }.compactMap(\.name),
                hasCallLink: CallLinks.contains(e.url?.absoluteString, e.location, e.notes))
        }
        return EventPicker.best(candidates, at: now)
    }

    static let settingsURL =
        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars")!
}
