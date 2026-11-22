import AppKit
import UserNotifications

/// The two questions the app asks without being opened: "a call started,
/// record it?" and "the call ended, stop?".
///
/// Notifications only work from the built .app: UserNotifications needs a real
/// bundle, and a bare `swift run` binary has none, so it would abort. There the
/// menu bar panel still shows the same offer.
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    var onRecord: (() -> Void)?
    var onStop: (() -> Void)?

    private static let offerId = "call.offer"
    private static let endedId = "call.ended"
    private static let offerCategory = "call.offer"
    private static let endedCategory = "call.ended"
    private static let recordAction = "record"
    private static let stopAction = "stop"

    private var ready = false

    static var available: Bool { Bundle.main.bundleURL.pathExtension == "app" }

    func setUp() {
        guard Notifier.available, !ready else { return }
        ready = true
        let c = UNUserNotificationCenter.current()
        c.delegate = self
        c.setNotificationCategories([
            UNNotificationCategory(identifier: Notifier.offerCategory,
                                   actions: [UNNotificationAction(identifier: Notifier.recordAction, title: "Record")],
                                   intentIdentifiers: []),
            UNNotificationCategory(identifier: Notifier.endedCategory,
                                   actions: [UNNotificationAction(identifier: Notifier.stopAction, title: "Stop recording")],
                                   intentIdentifiers: []),
        ])
        c.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func offerRecording(app: String) {
        post(id: Notifier.offerId, category: Notifier.offerCategory,
             title: "\(app) is using the microphone",
             body: "Click to record this call. Everything stays on this Mac.")
    }

    func offerStop(app: String) {
        post(id: Notifier.endedId, category: Notifier.endedCategory,
             title: "\(app) has stopped using the microphone",
             body: "Is the call over? Click to stop recording.")
    }

    func clearOffer() { clear(Notifier.offerId) }
    func clearEnded() { clear(Notifier.endedId) }

    private func clear(_ id: String) {
        guard ready else { return }
        let c = UNUserNotificationCenter.current()
        c.removeDeliveredNotifications(withIdentifiers: [id])
        c.removePendingNotificationRequests(withIdentifiers: [id])
    }

    private func post(id: String, category: String, title: String, body: String) {
        guard ready else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.categoryIdentifier = category
        content.sound = .default
        // Same identifier each time: a second offer replaces the first rather
        // than stacking up in Notification Center.
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let category = response.notification.request.content.categoryIdentifier
        let action = response.actionIdentifier
        let clicked = action == UNNotificationDefaultActionIdentifier
        Task { @MainActor in
            if category == Notifier.offerCategory && (clicked || action == Notifier.recordAction) {
                Notifier.shared.onRecord?()
            } else if category == Notifier.endedCategory && (clicked || action == Notifier.stopAction) {
                Notifier.shared.onStop?()
            }
        }
        completionHandler()
    }

    /// A menu bar app counts as frontmost surprisingly often; show the banner
    /// anyway, or the offer would vanish silently.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
