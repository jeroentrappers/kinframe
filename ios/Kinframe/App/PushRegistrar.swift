import KinframeKit
import UIKit
import UserNotifications

/// APNs registration, behind the `KINFRAME_APNS_ENABLED` build flag (off by default): the server can't send APNs
/// pushes yet, so until then the app relies on the websocket while it's open. When enabled, permission is asked
/// after the first send (product spec §4.1) and the device token is registered with
/// `POST /v1/me/push-endpoints {kind: "apns"}`.
@MainActor
final class PushRegistrar {
    static let shared = PushRegistrar()

    private let askedKey = "push.asked"
    private let endpointKey = "push.endpointId"

    var enabled: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "KinframeAPNsEnabled") as? String)?.uppercased() == "YES"
    }

    /// Ask for notification permission once, after the first successful send.
    func askAfterFirstSend() {
        guard enabled, !UserDefaults.standard.bool(forKey: askedKey) else { return }
        UserDefaults.standard.set(true, forKey: askedKey)
        Task {
            let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            if granted { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// Re-register at launch when permission was given earlier (tokens can change).
    func refreshRegistration() {
        guard enabled, !UserDefaults.standard.bool(forKey: offKey) else { return }
        Task {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            if settings.authorizationStatus == .authorized { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// The device token, kept so an account added later registers too.
    private var token: Data?

    /// Every account registers the device with its own server (docs/spec/multi-server.md §2.1).
    func didRegister(token: Data, accounts: [AppModel]) {
        self.token = token
        for m in accounts where m.api != nil { didRegister(token: token, api: m.api, account: m.accountId) }
    }

    /// A server was added: register this device there too (when notifications are on).
    func registerNewAccount(_ m: AppModel) {
        guard let token, !UserDefaults.standard.bool(forKey: offKey) else { return }
        didRegister(token: token, api: m.api, account: m.accountId)
    }

    private func endpointKey(_ account: String) -> String {
        account == AccountRecord.legacyId ? endpointKey : "\(endpointKey).\(account)"
    }

    func didRegister(token: Data, api: API?, account: String = AccountRecord.legacyId) {
        guard enabled, let api else { return }
        let hex = token.map { String(format: "%02x", $0) }.joined()
        let endpointKey = endpointKey(account)
        Task {
            let endpoint = try? await api.call {
                try await $0.registerPushEndpoint(body: .json(.init(
                    kind: .apns,
                    endpoint: hex,
                    deviceName: String(UIDevice.current.name.prefix(60)),
                    locale: L10n.code
                ))).ok.body.json
            }
            if let id = endpoint?.value2.id { UserDefaults.standard.set(id, forKey: endpointKey) }
        }
    }

    // MARK: Birthday notifications

    /// Greetings for the same person and day replace each other (`birthday:<birthday_id>:<date>`, the newest body
    /// counts the frames), and the morning notice is one per day: drop the older ones still in Notification Center.
    /// The server can also collapse them for APNs (`apns-collapse-id`); this keeps the list tidy either way.
    func tidyBirthdayNotifications() {
        Task {
            let center = UNUserNotificationCenter.current()
            let delivered = await center.deliveredNotifications().map { n in
                BirthdayPush.Delivered(id: n.request.identifier,
                                       tag: BirthdayPush.tag(n.request.content.userInfo, received: LocalDate.today(now: n.date)),
                                       date: n.date)
            }
            let old = BirthdayPush.superseded(delivered)
            if !old.isEmpty { center.removeDeliveredNotifications(withIdentifiers: old) }
        }
    }

    // MARK: The Settings switch

    private let offKey = "push.off"

    /// The system's answer to the permission question.
    func authorization() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// On for this device: permission given and not switched off in Settings.
    func isOn() async -> Bool {
        guard enabled, !UserDefaults.standard.bool(forKey: offKey) else { return false }
        let status = await authorization()
        return status == .authorized || status == .provisional
    }

    /// Ask for permission when needed and register; false when the system said no.
    func turnOn() async -> Bool {
        guard enabled else { return false }
        UserDefaults.standard.set(true, forKey: askedKey)
        var status = await authorization()
        if status == .notDetermined {
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            status = await authorization()
        }
        guard status == .authorized || status == .provisional else { return false }
        UserDefaults.standard.set(false, forKey: offKey)
        UIApplication.shared.registerForRemoteNotifications()
        return true
    }

    /// Stop pushes to this device: the endpoints are removed from every server.
    func turnOff(accounts: [AppModel]) {
        UserDefaults.standard.set(true, forKey: offKey)
        for m in accounts { unregister(api: m.api, account: m.accountId) }
    }

    /// Forget this device's endpoint on sign-out.
    func unregister(api: API?, account: String = AccountRecord.legacyId) {
        let endpointKey = endpointKey(account)
        guard let id = UserDefaults.standard.string(forKey: endpointKey) else { return }
        UserDefaults.standard.removeObject(forKey: endpointKey)
        guard let api else { return }
        Task { _ = try? await api.call { try await $0.deletePushEndpoint(path: .init(endpointId: id)) } }
    }
}
