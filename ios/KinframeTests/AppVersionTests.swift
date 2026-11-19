@testable import KinframeKit
import XCTest

/// The family-app version policy ("force update", docs/api/README.md §5.15).
final class AppVersionTests: XCTestCase {
    let iosBuild = AppBuild(platform: .ios, versionName: "1.4.0", build: "57")

    // MARK: Decoding

    func testDiscoveryDecodesAppVersions() throws {
        let json = """
        {"name":"k","version":"1","api":"https://k.example.org/v1","auth":{},"limits":{},
         "app_versions":{
           "android":{"minimum_build":10,"recommended_build":12,"store_url":"https://play.google.com/store/apps/details?id=app.kinframe.family"},
           "ios":{"minimum_build":50,"recommended_build":60,"store_url":null},
           "macos":{"minimum_build":null,"recommended_build":61,"store_url":"macappstore://apps.apple.com/app/id1"}
         }}
        """
        let d = try JSONDecoder().decode(Discovery.self, from: Data(json.utf8))
        XCTAssertEqual(d.appVersions?.ios, AppVersionPolicy(minimumBuild: 50, recommendedBuild: 60, storeURL: nil))
        XCTAssertEqual(d.appVersions?.macos, AppVersionPolicy(minimumBuild: nil, recommendedBuild: 61, storeURL: "macappstore://apps.apple.com/app/id1"))
        XCTAssertEqual(d.appVersions?.policy(for: .ios)?.minimumBuild, 50)
        // Stored and read back (ServerStore keeps discovery as JSON).
        let again = try JSONDecoder().decode(Discovery.self, from: JSONEncoder().encode(d))
        XCTAssertEqual(again.appVersions, d.appVersions)
    }

    func testDiscoveryWithoutOrWithOddAppVersions() throws {
        let plain = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k.example.org/v1"}"#.utf8))
        XCTAssertNil(plain.appVersions)
        // A platform left out, a wrongly typed field: the rest still decodes.
        let odd = try JSONDecoder().decode(Discovery.self, from: Data(#"""
        {"name":"k","api":"https://k.example.org/v1","app_versions":{"ios":{"minimum_build":"x","recommended_build":3},"windows":{}}}
        """#.utf8))
        XCTAssertEqual(odd.appVersions?.ios, AppVersionPolicy(minimumBuild: nil, recommendedBuild: 3))
        XCTAssertNil(odd.appVersions?.macos)
    }

    // MARK: Header

    func testHeaderValuePerPlatform() {
        XCTAssertEqual(iosBuild.headerValue, "kinframe-family-ios/1.4.0 (57)")
        let mac = AppBuild(info: ["CFBundleShortVersionString": "2.0", "CFBundleVersion": "80"], platform: .macos)
        XCTAssertEqual(mac.headerValue, "kinframe-family-macos/2.0 (80)")
        XCTAssertEqual(mac.build, 80)
        XCTAssertEqual(AppBuild(info: nil, platform: .ios).headerValue, "kinframe-family-ios/0 (0)")
        XCTAssertEqual(AppIdentity.headerName, "X-Kinframe-App")
        #if targetEnvironment(macCatalyst)
        XCTAssertEqual(AppPlatform.current, .macos)
        XCTAssertTrue(AppIdentity.headerValue.hasPrefix("kinframe-family-macos/"))
        #else
        XCTAssertEqual(AppPlatform.current, .ios)
        XCTAssertTrue(AppIdentity.headerValue.hasPrefix("kinframe-family-ios/"))
        #endif
    }

    func testBuildNumbers() {
        XCTAssertEqual(AppBuild(platform: .ios, versionName: "1", build: " 12 ").build, 12)
        XCTAssertEqual(AppBuild(platform: .ios, versionName: "1", build: "12.3").build, 12)
        XCTAssertNil(AppBuild(platform: .ios, versionName: "1", build: "dev").build)
    }

    func testRequestsCarryTheHeader() throws {
        var r = URLRequest(url: URL(string: "https://k.example.org/v1/me")!)
        r.identifyApp()
        XCTAssertEqual(r.value(forHTTPHeaderField: "X-Kinframe-App"), AppIdentity.headerValue)
        let tus = try XCTUnwrap(Tus.request(for: .create(length: 1), upload: TusUpload(file: "a", size: 1, filename: "a.jpg", mime: "image/jpeg"),
                                            endpoint: URL(string: "https://k.example.org/v1/uploads")!))
        XCTAssertEqual(tus.value(forHTTPHeaderField: "X-Kinframe-App"), AppIdentity.headerValue)
        let config = URLSessionConfiguration.ephemeral
        config.identifyApp()
        XCTAssertEqual(config.httpAdditionalHeaders?["X-Kinframe-App"] as? String, AppIdentity.headerValue)
    }

    func testGeneratedClientSendsTheHeader() async throws {
        let seen = Seen()
        let api = API(baseURL: URL(string: "https://k.example.org")!, tokens: StubTokens(), session: StubURLProtocol.session { r in
            seen.set(r.header("X-Kinframe-App"))
            return .response(status: 200, headers: ["Content-Type": "application/json"], body: Data("[]".utf8))
        })
        _ = try await api.call { try await $0.listMyFrames().ok.body.json }
        XCTAssertEqual(seen.value, AppIdentity.headerValue)
    }

    // MARK: Policy

    func testBelowMinimumBlocks() {
        let v = AppVersions(ios: AppVersionPolicy(minimumBuild: 58, recommendedBuild: 60))
        XCTAssertEqual(AppUpdatePolicy.prompt(v, build: iosBuild), .blocking(storeURL: URL(string: "itms-apps://apps.apple.com/app/id6833055734")!))
        // At the minimum: allowed (the banner still asks for the recommended build).
        let at = AppVersions(ios: AppVersionPolicy(minimumBuild: 57, recommendedBuild: 60))
        XCTAssertEqual(AppUpdatePolicy.prompt(at, build: iosBuild), .banner(recommendedBuild: 60, storeURL: AppUpdatePolicy.defaultStoreURL(for: .ios)))
    }

    func testBelowRecommendedShowsBanner() {
        let v = AppVersions(ios: AppVersionPolicy(recommendedBuild: 58, storeURL: "https://apps.apple.com/app/id6833055734"))
        XCTAssertEqual(AppUpdatePolicy.prompt(v, build: iosBuild), .banner(recommendedBuild: 58, storeURL: URL(string: "https://apps.apple.com/app/id6833055734")!))
        XCTAssertEqual(AppUpdatePolicy.prompt(AppVersions(ios: AppVersionPolicy(recommendedBuild: 57)), build: iosBuild), AppUpdatePrompt.none)
    }

    func testUsesOwnPlatformOnly() {
        let v = AppVersions(ios: AppVersionPolicy(minimumBuild: 100), macos: nil)
        let mac = AppBuild(platform: .macos, versionName: "1.4.0", build: "57")
        XCTAssertEqual(AppUpdatePolicy.prompt(v, build: mac), AppUpdatePrompt.none)
        let macPolicy = AppVersions(ios: nil, macos: AppVersionPolicy(minimumBuild: 100))
        XCTAssertEqual(AppUpdatePolicy.prompt(macPolicy, build: mac), .blocking(storeURL: URL(string: "macappstore://apps.apple.com/app/id6833055734")!))
        XCTAssertEqual(AppUpdatePolicy.prompt(macPolicy, build: iosBuild), AppUpdatePrompt.none)
    }

    func testNoPolicyOrUnknownBuild() {
        XCTAssertEqual(AppUpdatePolicy.prompt(nil, build: iosBuild), AppUpdatePrompt.none)
        XCTAssertEqual(AppUpdatePolicy.prompt(AppVersions(), build: iosBuild), AppUpdatePrompt.none)
        let dev = AppBuild(platform: .ios, versionName: "1", build: "dev")
        XCTAssertEqual(AppUpdatePolicy.prompt(AppVersions(ios: AppVersionPolicy(minimumBuild: 100)), build: dev), AppUpdatePrompt.none)
    }

    func testServerSaidUpdateRequired() {
        // After a 426 that discovery couldn't confirm or deny: block, with the policy's store URL when known.
        XCTAssertTrue(AppUpdatePolicy.prompt(nil, build: iosBuild, updateRequired: true).isBlocking)
        let v = AppVersions(ios: AppVersionPolicy(storeURL: "https://example.org/get"))
        XCTAssertEqual(AppUpdatePolicy.prompt(v, build: iosBuild, updateRequired: true).storeURL, URL(string: "https://example.org/get"))
    }

    func testStoreURLFallsBack() {
        XCTAssertEqual(AppUpdatePolicy.storeURL(AppVersionPolicy(storeURL: "  "), platform: .ios), AppUpdatePolicy.defaultStoreURL(for: .ios))
        XCTAssertEqual(AppUpdatePolicy.storeURL(AppVersionPolicy(storeURL: "not a url"), platform: .macos), AppUpdatePolicy.defaultStoreURL(for: .macos))
        XCTAssertEqual(AppUpdatePolicy.defaultStoreURL(for: .ios).absoluteString, "itms-apps://apps.apple.com/app/id6833055734")
        XCTAssertEqual(AppUpdatePolicy.defaultStoreURL(for: .macos).absoluteString, "macappstore://apps.apple.com/app/id6833055734")
    }

    // MARK: Dismissal

    func testDismissalIsPerRecommendedBuild() throws {
        let suite = "app-update-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let dismissals = AppUpdateDismissals(defaults: defaults)
        XCTAssertNil(dismissals.dismissedBuild)

        let v60 = AppVersions(ios: AppVersionPolicy(recommendedBuild: 60))
        guard case .banner(let build, _) = AppUpdatePolicy.prompt(v60, build: iosBuild, dismissedBuild: dismissals.dismissedBuild) else {
            return XCTFail("expected a banner")
        }
        dismissals.dismiss(recommendedBuild: build)
        XCTAssertEqual(AppUpdateDismissals(defaults: defaults).dismissedBuild, 60)
        XCTAssertEqual(AppUpdatePolicy.prompt(v60, build: iosBuild, dismissedBuild: dismissals.dismissedBuild), AppUpdatePrompt.none)
        // A newer recommendation shows it again.
        let v61 = AppVersions(ios: AppVersionPolicy(recommendedBuild: 61))
        XCTAssertEqual(AppUpdatePolicy.prompt(v61, build: iosBuild, dismissedBuild: dismissals.dismissedBuild), .banner(recommendedBuild: 61, storeURL: AppUpdatePolicy.defaultStoreURL(for: .ios)))
        // A dismissed banner never hides the blocking page.
        let blocked = AppVersions(ios: AppVersionPolicy(minimumBuild: 60, recommendedBuild: 60))
        XCTAssertTrue(AppUpdatePolicy.prompt(blocked, build: iosBuild, dismissedBuild: 60).isBlocking)
    }

    // MARK: Throttle

    func testDiscoveryRefreshThrottle() {
        var t = DiscoveryRefreshThrottle(interval: 3600)
        let start = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertTrue(t.shouldRefresh(now: start))
        XCTAssertFalse(t.shouldRefresh(now: start.addingTimeInterval(600)))
        XCTAssertTrue(t.shouldRefresh(now: start.addingTimeInterval(700), force: true))
        XCTAssertFalse(t.shouldRefresh(now: start.addingTimeInterval(3000)))
        XCTAssertTrue(t.shouldRefresh(now: start.addingTimeInterval(700 + 3600)))
        // A clock that went back doesn't stall it.
        XCTAssertTrue(t.shouldRefresh(now: start))
    }

    // MARK: 426

    func testUpdateRequiredMapping() {
        let expectation = expectation(forNotification: AppUpdateSignal.notification, object: nil)
        let e = APIError.from(status: 426, body: nil)
        XCTAssertEqual(e.code, "app_update_required")
        XCTAssertTrue(e.isAppUpdateRequired)
        wait(for: [expectation], timeout: 1)

        let problem = APIError.from(status: 426, body: Data(#"{"type":"about:blank","title":"Update needed","status":426,"code":"app_update_required"}"#.utf8))
        XCTAssertEqual(problem.code, "app_update_required")
        XCTAssertEqual(problem.title, "Update needed")
        XCTAssertFalse(APIError.from(status: 403, body: nil).isAppUpdateRequired)
        XCTAssertFalse(APIError.network.isAppUpdateRequired)
    }

    func testGeneratedClient426() async {
        let expectation = expectation(forNotification: AppUpdateSignal.notification, object: nil)
        let api = API(baseURL: URL(string: "https://k.example.org")!, tokens: StubTokens(), session: StubURLProtocol.session { _ in
            .response(status: 426, headers: ["Content-Type": "application/problem+json"],
                      body: Data(#"{"type":"about:blank","title":"x","status":426,"code":"app_update_required"}"#.utf8))
        })
        do {
            _ = try await api.call { try await $0.listMyFrames().ok.body.json }
            XCTFail("expected a 426")
        } catch {
            XCTAssertEqual((error as? APIError)?.code, "app_update_required")
            XCTAssertEqual((error as? APIError)?.isAppUpdateRequired, true)
        }
        await fulfillment(of: [expectation], timeout: 1)
    }

    func testTus426Fails() {
        var u = TusUpload(file: "a", size: 4, filename: "a.jpg", mime: "image/jpeg")
        u.location = URL(string: "https://k.example.org/v1/uploads/abc")
        for step in [TusStep.create(length: 4), .head, .patch(offset: 0, length: 4)] {
            XCTAssertThrowsError(try Tus.apply(step, to: &u, status: 426, headers: [:], endpoint: URL(string: "https://k.example.org/v1/uploads")!)) {
                guard case TusError.failed(let e) = $0 else { return XCTFail("\(step): \($0)") }
                XCTAssertTrue(e.isAppUpdateRequired)
            }
        }
    }
}

private final class Seen: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?
    var value: String? { lock.withLock { stored } }
    func set(_ v: String?) { lock.withLock { stored = v } }
}
