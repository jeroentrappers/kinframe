import Foundation
import SentrySwift

/// Crash reports to kinframe's own GlitchTip, project `family-apple` (docs/spec/crash-reporting.md).
///
/// Only builds made with a DSN (build setting `KINFRAME_SENTRY_DSN` → Info.plist `KinframeSentryDSN`, set by the
/// release lanes) report, and only while "Send crash reports" is on. Otherwise the SDK isn't started at all. Reports
/// carry the stack trace, release, OS, device model, app state and breadcrumbs, never personal data (§3): no user, IP,
/// cookies, headers, query strings or bodies, screenshots, view hierarchy or replay, and no tracing.
public enum CrashReporting {
    /// Which process reports, a tag on every event.
    public enum Component: String, Sendable {
        case app
        case shareExtension = "share-extension"
    }

    /// What the SDK starts with.
    public struct Configuration: Equatable, Sendable {
        public var dsn: String
        /// `family-apple@<version>+<build>`.
        public var release: String
        public var environment = "production"
        /// `ios` or `mac-catalyst`.
        public var platform: String
        public var component: Component
    }

    static let infoKey = "KinframeSentryDSN"
    static let settingKey = "kfSendCrashReports"

    /// "Send crash reports" (§4): on unless turned off, kept in the App Group so the share extension follows it.
    /// It takes effect at the next start.
    public static var enabled: Bool {
        get { enabled(in: AppGroup.defaults) }
        set { AppGroup.defaults.set(newValue, forKey: settingKey) }
    }

    static func enabled(in defaults: UserDefaults) -> Bool {
        defaults.object(forKey: settingKey) as? Bool ?? true
    }

    /// Whether this build reports at all (it has a DSN); the setting shows only then.
    public static var available: Bool { dsn(info: Bundle.main.infoDictionary ?? [:]) != nil }

    /// The DSN from the Info.plist, or nil when the build has none (empty, or the setting wasn't expanded).
    static func dsn(info: [String: Any]) -> String? {
        let value = (info[infoKey] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty || value.hasPrefix("$(") ? nil : value
    }

    /// The configuration to start with, or nil when nothing should start: no DSN, or the user turned reports off.
    static func configuration(info: [String: Any], enabled: Bool, component: Component) -> Configuration? {
        guard enabled, let dsn = dsn(info: info) else { return nil }
        let version = info["CFBundleShortVersionString"] as? String ?? "0"
        let build = info["CFBundleVersion"] as? String ?? "0"
        #if targetEnvironment(macCatalyst)
        let platform = "mac-catalyst"
        #else
        let platform = "ios"
        #endif
        return Configuration(dsn: dsn, release: "family-apple@\(version)+\(build)", platform: platform, component: component)
    }

    /// Starts the SDK when this build has a DSN and reports are on; call first thing at launch. Returns whether it
    /// started.
    @discardableResult
    public static func start(_ component: Component) -> Bool {
        start(configuration(info: Bundle.main.infoDictionary ?? [:], enabled: enabled, component: component))
    }

    @discardableResult
    static func start(_ configuration: Configuration?) -> Bool {
        guard let configuration else { return false }
        SentrySDK.start(options: options(configuration))
        let tags = ["app.platform": configuration.platform, "component": configuration.component.rawValue]
        SentrySDK.configureScope { $0.setTags(tags) }
        return true
    }

    static func options(_ c: Configuration) -> Options {
        let o = Options()
        o.dsn = c.dsn
        o.releaseName = c.release
        o.environment = c.environment
        // Personal data: none, and nothing captured from the screen.
        o.sendDefaultPii = false
        o.attachScreenshot = false
        o.attachViewHierarchy = false
        o.sessionReplay.sessionSampleRate = 0
        o.sessionReplay.onErrorSampleRate = 0
        // Performance tracing off; crashes, app hangs and watchdog terminations on.
        o.tracesSampleRate = 0
        o.enableAutoPerformanceTracing = false
        o.enableUIViewControllerTracing = false
        o.enableUserInteractionTracing = false
        o.enableNetworkTracking = false
        o.enableFileIOTracing = false
        o.enableCoreDataTracing = false
        o.enableAppHangTracking = true
        o.enableCrashHandler = true
        o.enableWatchdogTerminationTracking = true
        // Failed requests would become events of their own, with headers and URLs.
        o.enableCaptureFailedRequests = false
        // Release health and metrics: GlitchTip doesn't use them (and sessions carry an installation id).
        o.enableAutoSessionTracking = false
        o.enableMetrics = false
        // Navigation and lifecycle breadcrumbs stay; HTTP ones lose their query strings below.
        o.enableAutoBreadcrumbTracking = true
        o.enableNetworkBreadcrumbs = true
        o.beforeSend = { scrub($0) }
        o.beforeBreadcrumb = { scrub($0) }
        return o
    }

    // MARK: - Scrubbing (§3: every SDK's beforeSend enforces it, whatever a later SDK version defaults to)

    /// The event without user, IP, device name, cookies, headers, bodies and query strings.
    static func scrub(_ event: Event) -> Event? {
        event.user = nil
        event.serverName = nil
        if let request = event.request {
            request.url = request.url.map(stripQuery)
            request.queryString = nil
            request.fragment = nil
            request.cookies = nil
            request.headers = nil
            request.bodySize = nil
        }
        if var context = event.context {
            context["device"]?["name"] = nil
            context["user"] = nil
            event.context = context
        }
        event.breadcrumbs = event.breadcrumbs?.compactMap { scrub($0) }
        return event
    }

    /// The breadcrumb with URLs down to scheme, host and path, and no query, fragment or body.
    static func scrub(_ breadcrumb: Breadcrumb) -> Breadcrumb? {
        guard var data = breadcrumb.data else { return breadcrumb }
        for key in data.keys {
            let k = key.lowercased()
            if k == "http.query" || k == "http.fragment" || (k.contains("body") && !k.hasSuffix("_size"))
                || k.contains("header") || k.contains("cookie") {
                data[key] = nil
            } else if let s = data[key] as? String, s.contains("://") {
                data[key] = stripQuery(s)
            }
        }
        breadcrumb.data = data
        breadcrumb.message = breadcrumb.message.map(stripQueries)
        return breadcrumb
    }

    /// `https://h/p?token=…#x` → `https://h/p`; also drops user info in the authority.
    static func stripQuery(_ url: String) -> String {
        guard var parts = URLComponents(string: url), parts.host != nil else {
            return String(url.prefix { $0 != "?" && $0 != "#" })
        }
        parts.query = nil
        parts.fragment = nil
        parts.user = nil
        parts.password = nil
        return parts.string ?? String(url.prefix { $0 != "?" && $0 != "#" })
    }

    /// Every URL inside a free text, without its query and fragment.
    static func stripQueries(_ text: String) -> String {
        guard text.contains("://") else { return text }
        return text.split(separator: " ", omittingEmptySubsequences: false)
            .map { $0.contains("://") ? stripQuery(String($0)) : String($0) }
            .joined(separator: " ")
    }
}
