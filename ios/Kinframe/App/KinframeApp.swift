import KinframeKit
import SwiftUI
import UserNotifications

@main
struct KinframeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            AccountsRoot(accounts: delegate.accounts)
                .onOpenURL { delegate.model.open($0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { delegate.model.open(url) }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { delegate.accounts.didBecomeActive() }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Created at launch, also when the system launches the app in the background to finish uploads.
    @MainActor lazy var accounts = Accounts()
    /// The primary account (navigation, links, the upload queue's background sessions).
    @MainActor var model: AppModel { accounts.primary }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Self.styleNavigationBars()
        MainActor.assumeIsolated {
            _ = accounts
            PushRegistrar.shared.registerCategories()
        }
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

    /// An external display (an Apple TV through Screen Mirroring) gets the TV scene, which shows a slideshow there
    /// only while "Show on TV" runs; everything else is the SwiftUI window group.
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if session.role == .windowExternalDisplayNonInteractive {
            let config = UISceneConfiguration(name: "TV", sessionRole: session.role)
            config.delegateClass = TvSceneDelegate.self
            return config
        }
        return UISceneConfiguration(name: nil, sessionRole: session.role)
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        let done = UncheckedSendable(completionHandler)
        MainActor.assumeIsolated {
            model.outbox.handleBackgroundEvents(identifier: identifier) { done.value() }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated {
            PushRegistrar.shared.didRegister(token: deviceToken, accounts: accounts.models)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        // The websocket already shows what happens while the app is open.
        []
    }

    /// A tapped notification opens what it is about: a feed's subscription requests or join requests, its birthdays
    /// (suggestions, decisions, the morning notice, greetings), a frame's feeds, a feed post's receipts, a send
    /// (docs/api/README.md §6.3; payloads carry only ids).
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let payload = UncheckedSendable(response.notification.request.content.userInfo)
        let action = response.actionIdentifier
        // Family social: the reminder buttons and the reply from a chat notification run without opening anything.
        let reply = (response as? UNTextInputNotificationResponse)?.userText
        // The payload's `server` says which account it is for (README §6.3); without it, the primary account.
        let account = await MainActor.run { accounts.model(forPush: payload.value) }
        if await MainActor.run(body: { PushRegistrar.shared.handleAction(action, payload: payload.value, reply: reply, api: account.api) }) { return }
        guard let route = PushRoute.parse(payload.value) else { return }
        await MainActor.run { account.open(route) }
    }
}

/// The primary account's model for the app's screens, and every account for merged lists; a new primary swaps it.
struct AccountsRoot: View {
    let accounts: Accounts

    var body: some View {
        RootView()
            .environment(accounts.primary)
            .environment(accounts)
            // Kinframe links tapped inside the app (chats, comments, captions) open here, whatever their server.
            .environment(\.openURL, OpenURLAction { url in accounts.openInApp(url) ? .handled : .systemAction })
            .id(accounts.primary.accountId)
    }
}

extension Accounts {
    /// The account a push is for: its `server` field, else the primary account (APNs has one token for all).
    func model(forPush payload: [AnyHashable: Any]) -> AppModel {
        let records = models.compactMap { m in m.server.map { AccountRecord(id: m.accountId, server: $0) } }
        let r = AccountServers.account(forPush: payload, in: records, fallback: primary.accountId)
        return model(for: r?.id)
    }
}

/// Wraps a non-Sendable value that is only used on one thread at a time.
struct UncheckedSendable<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
