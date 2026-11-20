@testable import KinframeKit
import SentrySwift
import XCTest

/// Crash reports (docs/spec/crash-reporting.md): only with a DSN and the setting on, and never personal data.
final class CrashReportingTests: XCTestCase {
    let info: [String: Any] = [
        "KinframeSentryDSN": "https://key@errors.example.org/3",
        "CFBundleShortVersionString": "1.4.0",
        "CFBundleVersion": "812",
    ]

    // MARK: Starting

    func testNothingStartsWithoutDSN() {
        for dsn in ["", "  ", "$(KINFRAME_SENTRY_DSN)"] {
            var i = info
            i["KinframeSentryDSN"] = dsn
            XCTAssertNil(CrashReporting.configuration(info: i, enabled: true, component: .app), dsn)
        }
        var none = info
        none["KinframeSentryDSN"] = nil
        XCTAssertNil(CrashReporting.configuration(info: none, enabled: true, component: .app))
        XCTAssertNil(CrashReporting.dsn(info: none))
    }

    func testNothingStartsWhenTurnedOff() {
        XCTAssertNil(CrashReporting.configuration(info: info, enabled: false, component: .app))
        XCTAssertNil(CrashReporting.configuration(info: info, enabled: false, component: .shareExtension))
    }

    func testStartWithoutConfigurationLeavesTheSDKOff() {
        XCTAssertFalse(CrashReporting.start(nil))
        XCTAssertFalse(SentrySDK.isEnabled)
        // The test host is a build without a DSN: the app's own start did nothing either.
        XCTAssertFalse(CrashReporting.available)
        XCTAssertFalse(CrashReporting.start(.app))
        XCTAssertFalse(SentrySDK.isEnabled)
    }

    func testConfiguration() throws {
        let c = try XCTUnwrap(CrashReporting.configuration(info: info, enabled: true, component: .shareExtension))
        XCTAssertEqual(c.dsn, "https://key@errors.example.org/3")
        XCTAssertEqual(c.release, "family-apple@1.4.0+812")
        XCTAssertEqual(c.environment, "production")
        XCTAssertEqual(c.component.rawValue, "share-extension")
        #if targetEnvironment(macCatalyst)
        XCTAssertEqual(c.platform, "mac-catalyst")
        #else
        XCTAssertEqual(c.platform, "ios")
        #endif
    }

    func testSettingDefaultsToOnAndIsKept() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "kf-crash-tests"))
        defer { defaults.removePersistentDomain(forName: "kf-crash-tests") }
        XCTAssertTrue(CrashReporting.enabled(in: defaults))
        defaults.set(false, forKey: CrashReporting.settingKey)
        XCTAssertFalse(CrashReporting.enabled(in: defaults))
        defaults.set(true, forKey: CrashReporting.settingKey)
        XCTAssertTrue(CrashReporting.enabled(in: defaults))
    }

    func testOptionsArePrivate() throws {
        let c = try XCTUnwrap(CrashReporting.configuration(info: info, enabled: true, component: .app))
        let o = CrashReporting.options(c)
        XCTAssertEqual(o.dsn, c.dsn)
        XCTAssertEqual(o.releaseName, "family-apple@1.4.0+812")
        XCTAssertEqual(o.environment, "production")
        XCTAssertFalse(o.sendDefaultPii)
        XCTAssertFalse(o.attachScreenshot)
        XCTAssertFalse(o.attachViewHierarchy)
        XCTAssertEqual(o.sessionReplay.sessionSampleRate, 0)
        XCTAssertEqual(o.sessionReplay.onErrorSampleRate, 0)
        XCTAssertEqual(o.tracesSampleRate, 0)
        XCTAssertFalse(o.isTracingEnabled)
        XCTAssertFalse(o.enableAutoPerformanceTracing)
        XCTAssertFalse(o.enableNetworkTracking)
        XCTAssertFalse(o.enableCaptureFailedRequests)
        XCTAssertFalse(o.enableAutoSessionTracking)
        XCTAssertTrue(o.enableAppHangTracking)
        XCTAssertTrue(o.enableCrashHandler)
        XCTAssertNotNil(o.beforeSend)
        XCTAssertNotNil(o.beforeBreadcrumb)
    }

    // MARK: Scrubbing

    func testEventLosesUserRequestDataAndDeviceName() throws {
        let event = Event(level: .fatal)
        let user = User(userId: "42")
        user.email = "an@example.org"
        user.username = "an"
        user.ipAddress = "203.0.113.9"
        event.user = user
        event.serverName = "An’s iPhone"
        let request = SentryRequest()
        request.method = "POST"
        request.url = "https://k.example.org/v1/sends/9?token=secret#frag"
        request.queryString = "token=secret"
        request.fragment = "frag"
        request.cookies = "session=abc"
        request.headers = ["Authorization": "Bearer abc"]
        request.bodySize = 1234
        event.request = request
        event.context = ["device": ["name": "An’s iPhone", "model": "iPhone18,2"], "user": ["email": "an@example.org"], "os": ["name": "iOS"]]
        event.breadcrumbs = [httpBreadcrumb()]

        let out = try XCTUnwrap(CrashReporting.scrub(event))
        XCTAssertNil(out.user)
        XCTAssertNil(out.serverName)
        XCTAssertEqual(out.request?.method, "POST")
        XCTAssertEqual(out.request?.url, "https://k.example.org/v1/sends/9")
        XCTAssertNil(out.request?.queryString)
        XCTAssertNil(out.request?.fragment)
        XCTAssertNil(out.request?.cookies)
        XCTAssertNil(out.request?.headers)
        XCTAssertNil(out.request?.bodySize)
        XCTAssertNil(out.context?["device"]?["name"])
        XCTAssertEqual(out.context?["device"]?["model"] as? String, "iPhone18,2")
        XCTAssertNil(out.context?["user"])
        XCTAssertEqual(out.context?["os"]?["name"] as? String, "iOS")
        XCTAssertEqual(out.breadcrumbs?.first?.data?["url"] as? String, "https://k.example.org/v1/media/a.heic")

        // What would go over the wire.
        let json = String(decoding: try JSONSerialization.data(withJSONObject: out.serialize()), as: UTF8.self)
        for secret in ["secret", "frag", "an@example.org", "203.0.113.9", "Bearer", "session=abc", "An’s iPhone", "\"42\""] {
            XCTAssertFalse(json.contains(secret), "\(secret) in \(json)")
        }
    }

    func testHTTPBreadcrumbKeepsMethodHostPathAndStatus() throws {
        let out = try XCTUnwrap(CrashReporting.scrub(httpBreadcrumb()))
        let data = try XCTUnwrap(out.data)
        XCTAssertEqual(data["url"] as? String, "https://k.example.org/v1/media/a.heic")
        XCTAssertEqual(data["method"] as? String, "PUT")
        XCTAssertEqual(data["status_code"] as? Int, 500)
        XCTAssertEqual(data["request_body_size"] as? Int, 2048)
        XCTAssertNil(data["http.query"])
        XCTAssertNil(data["http.fragment"])
        XCTAssertNil(data["request_body"])
        XCTAssertNil(data["request_headers"])
        XCTAssertEqual(out.message, "upload https://k.example.org/v1/tus/1 failed")
    }

    func testOtherBreadcrumbsStay() throws {
        let nav = Breadcrumb(level: .info, category: "ui.lifecycle")
        nav.type = "navigation"
        nav.data = ["screen": "SettingsTab", "state": "viewDidAppear"]
        let out = try XCTUnwrap(CrashReporting.scrub(nav))
        XCTAssertEqual(out.data?["screen"] as? String, "SettingsTab")
        XCTAssertEqual(out.category, "ui.lifecycle")
        XCTAssertNil(CrashReporting.scrub(Breadcrumb(level: .info, category: "app.lifecycle"))?.data)
    }

    func testStripQuery() {
        XCTAssertEqual(CrashReporting.stripQuery("https://h.example/p/a?x=1&token=2#f"), "https://h.example/p/a")
        XCTAssertEqual(CrashReporting.stripQuery("https://me:pw@h.example:8443/p?x"), "https://h.example:8443/p")
        XCTAssertEqual(CrashReporting.stripQuery("https://h.example"), "https://h.example")
        XCTAssertEqual(CrashReporting.stripQuery("not a url?with=query"), "not a url")
        XCTAssertEqual(CrashReporting.stripQueries("GET https://h.example/a?t=1 -> 401"), "GET https://h.example/a -> 401")
    }

    private func httpBreadcrumb() -> Breadcrumb {
        let b = Breadcrumb(level: .info, category: "http")
        b.type = "http"
        b.data = [
            "url": "https://k.example.org/v1/media/a.heic?token=secret&name=Holiday.heic",
            "method": "PUT",
            "status_code": 500,
            "request_body_size": 2048,
            "http.query": "token=secret&name=Holiday.heic",
            "http.fragment": "frag",
            "request_body": "{\"caption\":\"secret\"}",
            "request_headers": ["Authorization": "Bearer abc"],
        ]
        b.message = "upload https://k.example.org/v1/tus/1?token=secret failed"
        return b
    }
}
