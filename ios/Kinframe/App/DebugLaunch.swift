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
/// - `-kfScrollTo plugins|details|ownership`: scroll the frame page there (`signOut`: the end of Settings).
/// - `-kfSheet share|pluginAdd|pluginEdit|transfer|leave|replace|member|reservations`: open that sheet (`-kfTransferConfirm YES`:
///   the transfer at its confirm step).
/// - `-kfDevOperator YES`: the dev token carries the operator scope (with `-kfReset YES` to sign in again).
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
            if args.bool(forKey: "kfDevOperator"), let token = await operatorToken(config.apiBase, email: email, name: name) {
                app.auth.devSignIn(token: token)
                Onboarding.step = .done
            } else if let token = try? await api.call({ try await $0.devToken(body: .json(.init(email: email, name: name))).ok.body.json.accessToken }) {
                app.auth.devSignIn(token: token)
                Onboarding.step = .done
            }
        }
    }

    /// `POST /dev/token {operator: true}`: the generated client doesn't know the dev-only field.
    private static func operatorToken(_ base: URL, email: String, name: String?) async -> String? {
        var request = URLRequest(url: base.appending(path: "dev/token"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["email": email, "operator": true]
        if let name { body["name"] = name }
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return json["access_token"] as? String
    }

    /// A sheet to open once (`-kfSheet`), for screenshots.
    static func takeSheet(_ name: String) -> Bool {
        guard args.string(forKey: "kfSheet") == name else { return false }
        args.set("", forKey: "kfSheet")
        return true
    }

    static func afterLaunch(_ app: AppModel) {
        switch args.string(forKey: "kfTab") {
        case "frames": app.tab = .frames
        case "send": app.tab = .send
        case "history": app.tab = .history
        case "settings": app.tab = .settings
        default: break
        }
        if let id = args.string(forKey: "kfSend") {
            app.historyPath = NavigationPath([HistoryRoute(sendId: id)])
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
