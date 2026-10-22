import KinframeKit
import SwiftUI
import UserNotifications

@main
struct KinframeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(delegate.model)
                .onOpenURL { delegate.model.open($0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { delegate.model.open(url) }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { delegate.model.didBecomeActive() }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Created at launch, also when the system launches the app in the background to finish uploads.
    @MainActor lazy var model = AppModel()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Self.styleNavigationBars()
        MainActor.assumeIsolated { _ = model }
        return true
    }

    /// Large titles in the serif display face, like the web's headings.
    static func styleNavigationBars() {
        let base = UIFontDescriptor.preferredFontDescriptor(withTextStyle: .largeTitle)
        let serif = (base.withDesign(.serif) ?? base).addingAttributes([.traits: [UIFontDescriptor.TraitKey.weight: UIFont.Weight.semibold]])
        let inline = UIFontDescriptor.preferredFontDescriptor(withTextStyle: .headline)
        let appearance = UINavigationBar.appearance()
        appearance.largeTitleTextAttributes = [.font: UIFont(descriptor: serif, size: 0)]
        appearance.titleTextAttributes = [.font: UIFont(descriptor: inline, size: 0)]
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        let done = UncheckedSendable(completionHandler)
        MainActor.assumeIsolated {
            model.outbox.handleBackgroundEvents(identifier: identifier) { done.value() }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated {
            PushRegistrar.shared.didRegister(token: deviceToken, api: model.api)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        // The websocket already shows what happens while the app is open.
        []
    }
}

/// Wraps a non-Sendable value that is only used on one thread at a time.
struct UncheckedSendable<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
