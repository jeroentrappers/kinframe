@testable import Kinframe
@testable import KinframeKit
import ObjectiveC
import os
import SwiftUI
import UIKit
import XCTest

/// Starts the real app root (`AccountsRoot`, what `KinframeApp`'s window shows) signed in to a fake kinframe server
/// with every optional feature on, in a window of its own, then opens every tab, the composer, a frame on each of
/// its pages, the family's segments and settings pages, Settings › App (photo sources, sent history), a few pushed
/// screens of Home and Chats and the simple view, through the app's own navigation state. Each screen renders and runs its tasks against the fake server. A crash
/// in a view's body, a task or a model ends the test process, which fails the test.
///
/// Guards against crashes no screen-level test sees (like the Android app's 1.2, which crashed on every start after
/// sign-in), on iPhone and on the Mac (Mac Catalyst). Runs in CI and before every upload (fastlane `release`,
/// `mac_release`).
@MainActor
final class SmokeTests: XCTestCase {
    private static let accountId = "a-smoketest"
    private var window: UIWindow?
    private var accounts: Accounts?
    /// What the test changes in the shared defaults, put back afterwards (the simulator or Mac may hold a real session).
    private var saved: [(UserDefaults, String, Any?)] = []

    override func setUp() async throws {
        SmokeServer.install()
        SmokeServer.reset(failing: false)
        let group = AppGroup.defaults
        for key in ["accounts", "server", AuthService.name("authMode", Self.accountId)] {
            saved.append((group, key, group.object(forKey: key)))
        }
        for key in ["onboardingStep", "homeFilter", "familyPostCounts", "viewMode", "viewModeAsked"] {
            saved.append((.standard, key, UserDefaults.standard.object(forKey: key)))
        }
        UserDefaults.standard.removeObject(forKey: "viewMode")
        UserDefaults.standard.removeObject(forKey: "homeFilter")
        // Signed in as the app finds it at launch: an account record with the server's discovery, a dev session.
        AccountStore.save([AccountRecord(id: Self.accountId, server: ServerConfig(url: SmokeServer.base, discovery: SmokeServer.discovery))])
        AuthService(account: Self.accountId).devSignIn(token: "t-smoke")
        Onboarding.step = .done
    }

    override func tearDown() async throws {
        window?.isHidden = true
        window?.rootViewController = nil
        window = nil
        if let accounts { await accounts.primary.signOut(keepServer: false) }
        accounts = nil
        for (defaults, key, value) in saved {
            if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
        }
        saved = []
    }

    func testTheSignedInAppStartsAndEveryScreenOpens() async throws {
        try await tapThroughTheApp()
    }

    /// Offline, a server error or a feature switched off meanwhile: error states, no crash.
    func testEveryScreenSurvivesFailingRequests() async throws {
        SmokeServer.reset(failing: true)
        try await tapThroughTheApp()
    }

    // MARK: Driving the app

    private func tapThroughTheApp() async throws {
        let started = Date()
        let accounts = Accounts()
        self.accounts = accounts
        let app = accounts.primary
        XCTAssertEqual(app.accountId, Self.accountId)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: AccountsRoot(accounts: accounts))
        window.makeKeyAndVisible()
        self.window = window

        try await waitFor("signed in and loaded") { app.phase == .main && app.me != nil && app.framesLoaded && app.feedsLoaded }
        await settle()

        for tab: AppModel.Tab in [.home, .chats, .frames, .family] {
            app.tab = tab
            await settle()
        }
        XCTAssertEqual(accounts.homeFamily?.value.id, accounts.feeds.first?.value.id, "a home family")

        // The composer (`+`): the current family, then one frame only.
        app.compose()
        await settle()
        await quiet()
        app.composer = nil
        await settle(rounds: 2)
        app.compose(frame: "f-oma")
        await settle()
        app.composer = nil
        await settle(rounds: 2)

        // A frame you own on each of its pages, then one you're a member of.
        app.tab = .frames
        await push(FrameRoute(id: "f-oma", account: app.accountId), on: \.framesPath)
        for page in FramePage.allCases {
            await push(FrameRoute(id: "f-oma", page: page, account: app.accountId), on: \.framesPath)
        }
        await push(FrameRoute(id: "f-opa", account: app.accountId), on: \.framesPath)

        // The family's segments, then its settings and their pages.
        app.tab = .family
        for segment in FamilySegment.allCases {
            app.familySegment = segment
            await settle()
            await quiet()
        }
        await push(FamilyProfileRoute(feedId: "feed-1", account: app.accountId), on: \.familyPath)
        for page: FamilyRoute.Page in [.settings, .details, .circles, .birthdays, .leave] {
            await push(FamilyRoute(feedId: "feed-1", page: page, account: app.accountId), on: \.familyPath)
        }
        await push(SentHistoryRoute(account: app.accountId), on: \.familyPath)
        await push(HistoryRoute(sendId: "s-1", account: app.accountId), on: \.familyPath)

        // Settings › App › My photo sources, the way a photo source's notification opens it.
        app.open(.photoSource(sourceId: "ps-1", frameId: nil, albumId: nil, feedId: nil))
        await settle()
        await quiet()
        XCTAssertEqual(app.tab, .family)
        app.showMyPhotoSources = false
        app.familyPath = NavigationPath()
        await settle()

        app.tab = .home
        await push(ActivityRoute(), on: \.homePath)
        await push(PostRoute(postId: "p-1", account: app.accountId), on: \.homePath)
        await push(PersonRoute(userId: "u-lies", account: app.accountId), on: \.homePath)
        app.tab = .chats
        await push(ChatRoute(id: "c-1", account: app.accountId), on: \.chatsPath)

        // The simple view, then back.
        accounts.viewMode = .simple
        await settle()
        await quiet()
        accounts.viewMode = .normal
        app.tab = .frames
        await settle()

        XCTAssertEqual(app.phase, .main, "still signed in")
        // The screens ran: their loads reached the server.
        let seen = SmokeServer.paths
        for path in ["/v1/timeline", "/v1/conversations", "/v1/sends", "/v1/frames/f-oma/members", "/v1/frames/f-oma/calendars",
                     "/v1/frames/f-oma/settings", "/v1/frames/f-opa", "/v1/feeds/feed-1/posts", "/v1/feeds/feed-1/members",
                     "/v1/feeds/feed-1/birthdays", "/v1/feeds/feed-1/circles", "/v1/photo-sources",
                     "/v1/conversations/c-1/messages", "/v1/people/u-lies", "/v1/sends/s-1"] {
            XCTAssertTrue(seen.contains(path), "\(path) was loaded")
        }
        print("SmokeTests (failing=\(SmokeServer.failing)): \(seen.count) requests in \(String(format: "%.1f", Date().timeIntervalSince(started))) s; answered with an error: \(SmokeServer.unanswered.sorted())")
    }

    /// Open `route` on an empty stack (a new screen each time, like a notification tap), let it load, go back.
    private func push(_ route: some Hashable, on path: ReferenceWritableKeyPath<AppModel, NavigationPath>) async {
        guard let app = accounts?.primary else { return }
        app[keyPath: path] = NavigationPath([route])
        await settle()
        await quiet()
        app[keyPath: path] = NavigationPath()
        await settle(rounds: 2)
    }

    /// Answers arrive on URLSession's threads: let them land, let views update and animations run, lay out again.
    private func settle(rounds: Int = 6) async {
        for _ in 0..<rounds {
            try? await Task.sleep(for: .milliseconds(60))
            window?.layoutIfNeeded()
        }
    }

    /// Until the app has sent no request for a moment (at most `limit`): slow CI runners start a screen's loads late,
    /// and leaving it before they go out would make the test miss them.
    private func quiet(limit: TimeInterval = 3) async {
        let end = Date().addingTimeInterval(limit)
        var last = SmokeServer.requests
        while Date() < end {
            await settle(rounds: 5)
            let now = SmokeServer.requests
            if now == last { return }
            last = now
        }
    }

    private func waitFor(_ what: String, timeout: TimeInterval = 15, _ condition: () -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < end else { throw SmokeError.timeout(what) }
            await settle(rounds: 1)
        }
    }

    enum SmokeError: Error { case timeout(String) }
}

/// The fake server at `https://smoke.kinframe.test`: discovery with every feature on, a profile, two frames and a
/// family, and for everything else the smallest answer the API allows (`SmokeFixtures`, generated from the API
/// description). When `failing`, everything but discovery, the profile, the frames and the family answers 500.
///
/// Every session of the app gets it: `URLSession.shared` through `registerClass`, the sessions made from
/// `URLSessionConfiguration.default` (API, websocket) by putting it in front of that configuration's protocols.
/// Other hosts go to the network as usual.
final class SmokeServer: URLProtocol, @unchecked Sendable {
    static let base = URL(string: "https://smoke.kinframe.test")!

    private struct State {
        var failing = false
        var paths: Set<String> = []
        var requests = 0
        var unanswered: Set<String> = []
    }
    private static let state = OSAllocatedUnfairLock(initialState: State())

    static var failing: Bool { state.withLock { $0.failing } }
    static var paths: Set<String> { state.withLock { $0.paths } }
    static var requests: Int { state.withLock { $0.requests } }
    static var unanswered: Set<String> { state.withLock { $0.unanswered } }

    static func reset(failing: Bool) {
        state.withLock { $0 = State(failing: failing) }
    }

    private static let installed: Void = {
        URLProtocol.registerClass(SmokeServer.self)
        let selector = NSSelectorFromString("defaultSessionConfiguration")
        guard let method = class_getClassMethod(URLSessionConfiguration.self, selector) else { return }
        typealias Getter = @convention(c) (AnyClass, Selector) -> URLSessionConfiguration
        let original = unsafeBitCast(method_getImplementation(method), to: Getter.self)
        let replacement: @convention(block) (AnyClass) -> URLSessionConfiguration = { cls in
            let config = original(cls, selector)
            config.protocolClasses = [SmokeServer.self] + (config.protocolClasses ?? [])
            return config
        }
        method_setImplementation(method, imp_implementationWithBlock(replacement))
    }()

    static func install() { _ = installed }

    override class func canInit(with request: URLRequest) -> Bool {
        guard let url = request.url else { return false }
        return url.host() == base.host() && (url.scheme == "https" || url.scheme == "http")
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let method = request.httpMethod ?? "GET"
        let (status, body) = Self.answer(method, url.path())
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": status < 400 ? "application/json" : "application/problem+json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func answer(_ method: String, _ path: String) -> (Int, String) {
        let failing = state.withLock { s in
            s.paths.insert(path)
            s.requests += 1
            return s.failing
        }
        if method == "GET", let body = essential(path) ?? (failing ? nil : ordinary(path)) {
            return (200, body)
        }
        state.withLock { _ = $0.unanswered.insert("\(method) \(path)") }
        let status = failing ? 500 : 404
        return (status, #"{"type":"about:blank","title":"Smoke","status":\#(status),"code":"smoke"}"#)
    }

    // MARK: Answers

    static let discovery: Discovery = {
        let json = """
        {"name":"Smoke","version":"test","api":"\(base.absoluteString)/v1","dev_auth":true,"weather":true,"calendar":true,
         "feeds":true,"birthdays":true,"photo_sources":true,"photo_source_providers":["immich_link","immich_api","icloud_link"],
         "photo_sources_private_networks":true,"google_photos_import":true,"email_inbox":true,"email_inbox_domain":"in.example.org",
         "telegram":true,"telegram_bot_username":"kinframe_bot","telegram_max_file_bytes":20000000,
         "social":true,"chats":true,"voice_messages":true,"link_previews":true,"engagement":true,"email_digest":true,
         "server_id":"s-smoke","server_name":"Smoke","base_url":"\(base.absoluteString)","leaving":true,"remote_families":true}
        """
        return try! JSONDecoder().decode(Discovery.self, from: Data(json.utf8))
    }()

    private static let me = #"{"id":"u-me","display_name":"Jeroen","email":"jeroen@example.org","timezone":"\#(TimeZone.current.identifier)","show_read_receipts":true,"created_at":"2025-01-02T10:00:00Z"}"#
    private static let all = #"{"send":true,"invite":true,"see_library":true,"manage_library":true,"change_settings":true}"#
    private static let frames = [
        "f-oma": #"{"id":"f-oma","name":"Oma’s frame","location":"Living room","owner_name":"Jeroen","role":"owner","permissions":\#(all),"online":true,"last_seen_at":"2026-11-20T08:00:00Z","member_count":3,"placement_count":0,"default_permissions":{"send":true},"created_at":"2025-01-02T10:00:00Z"}"#,
        "f-opa": #"{"id":"f-opa","name":"Opa Jos","location":"Kitchen","owner_name":"Lies","role":"member","permissions":{"send":true},"online":false,"last_seen_at":null}"#,
    ]
    private static let feeds = [
        "feed-1": #"{"id":"feed-1","name":"Trappers family","role":"owner","member_count":3,"subscription_count":1,"owner_name":"Jeroen","created_at":"2025-01-02T10:00:00Z","conversation_id":"c-1"}"#,
    ]

    /// What the screens need to get past loading; answered also when failing.
    private static func essential(_ path: String) -> String? {
        switch path {
        case "/.well-known/kinframe": return String(data: try! JSONEncoder().encode(discovery), encoding: .utf8)
        case "/v1/me": return me
        case "/v1/frames": return "[" + frames.keys.sorted().map { frames[$0]! }.joined(separator: ",") + "]"
        case "/v1/feeds": return "[" + feeds.values.joined(separator: ",") + "]"
        default:
            let parts = path.split(separator: "/")
            guard parts.count == 3 else { return nil }
            return parts[1] == "frames" ? frames[String(parts[2])] : parts[1] == "feeds" ? feeds[String(parts[2])] : nil
        }
    }

    /// Not linked to Telegram, no remote help running: the API answers 404 for those.
    private static let absent = ["/v1/me/telegram", "/v1/frames/{frameId}/remote-help"]

    private static let templates: [(NSRegularExpression, String)] = SmokeFixtures.get.compactMap { template, body in
        guard !absent.contains(template) else { return nil }
        let pattern = "^" + template.replacingOccurrences(of: #"\{[A-Za-z]+\}"#, with: "[^/]+", options: .regularExpression) + "$"
        return (try! NSRegularExpression(pattern: pattern), body)
    }

    private static func ordinary(_ path: String) -> String? {
        let range = NSRange(path.startIndex..., in: path)
        return templates.first { $0.0.firstMatch(in: path, range: range) != nil }?.1
    }
}
