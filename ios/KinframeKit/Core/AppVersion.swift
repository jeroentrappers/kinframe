import Foundation

/// Family app versions and the server's version policy ("force update", docs/api/README.md §5.15).
///
/// Every request names the app and its build in `X-Kinframe-App`. Discovery's `app_versions` says, per platform,
/// the lowest build the server still serves and the build it recommends. Below the minimum the app shows only the
/// "Update kinframe" page (and the server answers `426 app_update_required`); below the recommended build it shows a
/// banner that can be dismissed, once per recommended build.

/// The family app's platform as the server knows it: the Mac Catalyst build is `macos`.
public enum AppPlatform: String, Codable, Sendable, CaseIterable {
    case ios, macos

    /// The platform this binary runs as.
    public static var current: AppPlatform {
        #if targetEnvironment(macCatalyst)
        .macos
        #else
        .ios
        #endif
    }
}

/// This app's version name and build (`CFBundleShortVersionString`, `CFBundleVersion`).
public struct AppBuild: Equatable, Sendable {
    public var platform: AppPlatform
    public var versionName: String
    /// `CFBundleVersion` as sent.
    public var buildString: String
    /// The build as a number for comparing with the policy, or nil when it isn't one.
    public var build: Int? {
        let s = buildString.trimmingCharacters(in: .whitespaces)
        if let n = Int(s) { return n }
        // "12.3" style bundle versions: compare by their first component.
        return s.split(separator: ".").first.flatMap { Int($0) }
    }

    public init(platform: AppPlatform, versionName: String, build: String) {
        self.platform = platform
        self.versionName = versionName
        buildString = build
    }

    /// From an Info.plist dictionary (`Bundle.infoDictionary`).
    public init(info: [String: Any]?, platform: AppPlatform = .current) {
        let name = (info?["CFBundleShortVersionString"] as? String) ?? "0"
        let build = (info?["CFBundleVersion"] as? String) ?? "0"
        self.init(platform: platform, versionName: name, build: build)
    }

    /// The running app (or extension: it carries the app's version numbers).
    public static let current = AppBuild(info: Bundle.main.infoDictionary)

    /// `kinframe-family-ios/1.4.0 (57)`.
    public var headerValue: String {
        "kinframe-family-\(platform.rawValue)/\(versionName) (\(buildString))"
    }
}

/// The `X-Kinframe-App` header every request carries.
public enum AppIdentity {
    public static let headerName = "X-Kinframe-App"
    public static var headerValue: String { AppBuild.current.headerValue }
}

extension URLRequest {
    /// Add `X-Kinframe-App`.
    public mutating func identifyApp() {
        setValue(AppIdentity.headerValue, forHTTPHeaderField: AppIdentity.headerName)
    }
}

extension URLSessionConfiguration {
    /// Add `X-Kinframe-App` to every request of sessions made with this configuration.
    public func identifyApp() {
        var headers = httpAdditionalHeaders ?? [:]
        headers[AppIdentity.headerName] = AppIdentity.headerValue
        httpAdditionalHeaders = headers
    }
}

/// `AppVersionPolicy`: one platform's minimum and recommended build and where to update.
public struct AppVersionPolicy: Codable, Equatable, Sendable {
    public var minimumBuild: Int?
    public var recommendedBuild: Int?
    public var storeURL: String?

    public init(minimumBuild: Int? = nil, recommendedBuild: Int? = nil, storeURL: String? = nil) {
        self.minimumBuild = minimumBuild
        self.recommendedBuild = recommendedBuild
        self.storeURL = storeURL
    }

    enum CodingKeys: String, CodingKey {
        case minimumBuild = "minimum_build"
        case recommendedBuild = "recommended_build"
        case storeURL = "store_url"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        minimumBuild = (try? c.decodeIfPresent(Int.self, forKey: .minimumBuild)) ?? nil
        recommendedBuild = (try? c.decodeIfPresent(Int.self, forKey: .recommendedBuild)) ?? nil
        storeURL = (try? c.decodeIfPresent(String.self, forKey: .storeURL)) ?? nil
    }
}

/// Discovery's `app_versions`; a platform the operator configured nothing for is absent.
public struct AppVersions: Codable, Equatable, Sendable {
    public var ios: AppVersionPolicy?
    public var macos: AppVersionPolicy?

    public init(ios: AppVersionPolicy? = nil, macos: AppVersionPolicy? = nil) {
        self.ios = ios
        self.macos = macos
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ios = (try? c.decodeIfPresent(AppVersionPolicy.self, forKey: .ios)) ?? nil
        macos = (try? c.decodeIfPresent(AppVersionPolicy.self, forKey: .macos)) ?? nil
    }

    public func policy(for platform: AppPlatform) -> AppVersionPolicy? {
        switch platform {
        case .ios: ios
        case .macos: macos
        }
    }
}

/// What the app shows for its version.
public enum AppUpdatePrompt: Equatable, Sendable {
    /// Nothing: up to date, no policy, or the banner was dismissed.
    case none
    /// "A new version of kinframe is available" (dismissable) for this recommended build.
    case banner(recommendedBuild: Int, storeURL: URL)
    /// The full-screen "Update kinframe" page; nothing else is usable.
    case blocking(storeURL: URL)

    public var isBlocking: Bool {
        if case .blocking = self { return true }
        return false
    }

    public var storeURL: URL? {
        switch self {
        case .none: nil
        case .banner(_, let url), .blocking(let url): url
        }
    }
}

public enum AppUpdatePolicy {
    /// The App Store record (iPhone, iPad and Mac share it).
    public static let appStoreId = "6833055734"

    /// The store page when the server names none.
    public static func defaultStoreURL(for platform: AppPlatform) -> URL {
        switch platform {
        case .ios: URL(string: "itms-apps://apps.apple.com/app/id\(appStoreId)")!
        case .macos: URL(string: "macappstore://apps.apple.com/app/id\(appStoreId)")!
        }
    }

    /// Where "Update" goes: the server's `store_url` when it is a usable URL, else the store page.
    public static func storeURL(_ policy: AppVersionPolicy?, platform: AppPlatform) -> URL {
        if let s = policy?.storeURL?.trimmingCharacters(in: .whitespaces), !s.isEmpty,
           let url = URL(string: s), url.scheme != nil {
            return url
        }
        return defaultStoreURL(for: platform)
    }

    /// Decide what to show for `build` under the discovery's `app_versions`. `dismissedBuild` is the recommended
    /// build whose banner was dismissed (a newer recommendation shows the banner again). `updateRequired` is true
    /// after the server answered 426 until a re-read of discovery succeeds: the server's answer blocks on its own.
    public static func prompt(
        _ versions: AppVersions?,
        build: AppBuild,
        dismissedBuild: Int? = nil,
        updateRequired: Bool = false
    ) -> AppUpdatePrompt {
        let policy = versions?.policy(for: build.platform)
        let url = storeURL(policy, platform: build.platform)
        if updateRequired { return .blocking(storeURL: url) }
        // A build that isn't a number can't be compared: only the server's 426 can block it.
        guard let current = build.build else { return .none }
        if let minimum = policy?.minimumBuild, current < minimum {
            return .blocking(storeURL: url)
        }
        if let recommended = policy?.recommendedBuild, current < recommended, dismissedBuild != recommended {
            return .banner(recommendedBuild: recommended, storeURL: url)
        }
        return .none
    }
}

/// Remembers which recommended build's banner was dismissed (App Group defaults, shared with the share extension).
public struct AppUpdateDismissals: @unchecked Sendable {
    static let key = "appUpdate.dismissedBuild"
    let defaults: UserDefaults

    public init(defaults: UserDefaults = AppGroup.defaults) {
        self.defaults = defaults
    }

    /// The recommended build whose banner was dismissed, if any.
    public var dismissedBuild: Int? {
        defaults.object(forKey: Self.key) as? Int
    }

    public func dismiss(recommendedBuild: Int) {
        defaults.set(recommendedBuild, forKey: Self.key)
    }
}

/// `426 app_update_required` from any request, wherever it was made, so the app can re-read discovery and block.
public enum AppUpdateSignal {
    public static let code = "app_update_required"
    public static let notification = Notification.Name("kinframeAppUpdateRequired")

    /// Post `notification` (from any thread).
    public static func raise() {
        NotificationCenter.default.post(name: notification, object: nil)
    }

    /// True for a 426 or a problem with code `app_update_required`.
    public static func isUpdateRequired(status: Int, code: String?) -> Bool {
        status == 426 || code == Self.code
    }
}

/// Discovery is re-read when the app becomes active at most once per `interval` (launch and a 426 always re-read).
public struct DiscoveryRefreshThrottle: Sendable {
    public var interval: TimeInterval
    public private(set) var last: Date?

    public init(interval: TimeInterval = 3600, last: Date? = nil) {
        self.interval = interval
        self.last = last
    }

    /// Whether to re-read now; records the time when it says yes.
    public mutating func shouldRefresh(now: Date = Date(), force: Bool = false) -> Bool {
        if !force, let last, now.timeIntervalSince(last) < interval, now >= last { return false }
        last = now
        return true
    }
}
