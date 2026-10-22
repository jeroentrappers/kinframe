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
        guard enabled else { return }
        Task {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            if settings.authorizationStatus == .authorized { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func didRegister(token: Data, api: API?) {
        guard enabled, let api else { return }
        let hex = token.map { String(format: "%02x", $0) }.joined()
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

    /// Forget this device's endpoint on sign-out.
    func unregister(api: API?) {
        guard let id = UserDefaults.standard.string(forKey: endpointKey) else { return }
        UserDefaults.standard.removeObject(forKey: endpointKey)
        guard let api else { return }
        Task { _ = try? await api.call { try await $0.deletePushEndpoint(path: .init(endpointId: id)) } }
    }
}
