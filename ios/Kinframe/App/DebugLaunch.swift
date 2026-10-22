#if DEBUG
import KinframeKit
import SwiftUI

/// Debug-build launch arguments for screenshots and manual testing against a dev server, e.g.
/// `xcrun simctl launch booted app.kinframe.family -kfServer http://localhost:8110 -kfDevEmail me@example.org`.
///
/// - `-kfReset YES`: forget the server, session and onboarding progress.
/// - `-kfServer <url>` with `-kfDevEmail <email>` [`-kfDevName <name>`]: sign in with a dev token (dev servers only).
/// - `-kfStep welcome|server|signIn`: onboarding step.  `-kfTab frames|send|history|settings`: tab.
/// - `-kfLanguage en|nl|system`.  `-kfOpen <url>`: open a link as if tapped.  `-kfFrame <id>`: open a frame.
@MainActor
enum DebugLaunch {
    static var args: UserDefaults { .standard }

    static func beforeLaunch(_ app: AppModel) async {
        if let lang = args.string(forKey: "kfLanguage").flatMap(AppLanguage.init(rawValue:)) {
            L10n.setLanguage(lang)
        }
        if args.bool(forKey: "kfReset") {
            await app.auth.signOut()
            ServerStore.save(nil)
            UserDefaults.standard.removeObject(forKey: "onboardingStep")
            app.server = nil
            app.auth.configure(nil)
        }
        if let step = args.string(forKey: "kfStep").flatMap(Onboarding.Step.init(rawValue:)) {
            Onboarding.step = step
        }
        guard let raw = args.string(forKey: "kfServer"), let url = ServerAddress.normalize(raw),
              let discovery = try? await DiscoveryClient.fetch(url) else { return }
        let config = ServerConfig(url: url, discovery: discovery)
        if app.server != config { await app.choose(server: url, discovery: discovery) }
        guard let email = args.string(forKey: "kfDevEmail") else { return }
        if !app.auth.isSignedIn {
            let api = API(baseURL: config.apiBase, tokens: app.auth)
            let name = args.string(forKey: "kfDevName")
            if let token = try? await api.call({ try await $0.devToken(body: .json(.init(email: email, name: name))).ok.body.json.accessToken }) {
                app.auth.devSignIn(token: token)
                Onboarding.step = .done
            }
        }
    }

    static func afterLaunch(_ app: AppModel) {
        switch args.string(forKey: "kfTab") {
        case "frames": app.tab = .frames
        case "send": app.tab = .send
        case "history": app.tab = .history
        case "settings": app.tab = .settings
        default: break
        }
        if let ids = args.string(forKey: "kfComposeFrames") {
            app.composeFrameIds = Set(ids.split(separator: ",").map(String.init))
        }
        if let id = args.string(forKey: "kfFrame") {
            app.framesPath = NavigationPath([FrameRoute(id: id, section: args.string(forKey: "kfSection").flatMap(FrameSection.init(rawValue:)) ?? .overview)])
        }
        if let link = args.string(forKey: "kfOpen").flatMap(URL.init(string:)) {
            app.open(link)
        }
    }
}
#endif
