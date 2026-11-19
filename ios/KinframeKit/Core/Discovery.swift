import Foundation

/// The server discovery document, `GET <server>/.well-known/kinframe` (docs/architecture.md → Server discovery).
/// Decoded leniently: unknown fields are ignored and optional parts get defaults, so a newer or partly configured
/// server still works.
public struct Discovery: Codable, Equatable, Sendable {
    public struct Auth: Codable, Equatable, Sendable {
        public var issuer: String
        public var mobileClientId: String
        public var webClientId: String
        public var apiResource: String

        public init(issuer: String = "", mobileClientId: String = "", webClientId: String = "", apiResource: String = "") {
            self.issuer = issuer
            self.mobileClientId = mobileClientId
            self.webClientId = webClientId
            self.apiResource = apiResource
        }

        enum CodingKeys: String, CodingKey {
            case issuer
            case mobileClientId = "mobile_client_id"
            case webClientId = "web_client_id"
            case apiResource = "api_resource"
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            issuer = try c.decodeIfPresent(String.self, forKey: .issuer) ?? ""
            mobileClientId = try c.decodeIfPresent(String.self, forKey: .mobileClientId) ?? ""
            webClientId = try c.decodeIfPresent(String.self, forKey: .webClientId) ?? ""
            apiResource = try c.decodeIfPresent(String.self, forKey: .apiResource) ?? ""
        }
    }

    public var name: String
    public var version: String
    public var api: String
    public var web: String?
    public var auth: Auth
    public var limits: Limits
    public var devAuth: Bool
    public var weather: Bool
    /// Calendars, sent events and countdowns are on (`calendar`).
    public var calendar: Bool = false
    /// The server runs the pre-order shop (`shop` in discovery): operators see its reservations.
    public var shop: Bool = false
    /// Feeds and subscriptions are on (`feeds`, docs/spec/feeds.md). When off, the app shows nothing of them.
    public var feeds: Bool = false
    /// Feeds have birthday lists (`birthdays`, docs/spec/birthdays.md). Only counts together with `feeds`.
    public var birthdays: Bool = false
    /// Albums and feeds can sync from Immich or iCloud (`photo_sources`, docs/spec/photo-sources.md), with the providers
    /// this server allows (raw values: a newer server may list providers this app doesn't know, they are skipped).
    public var photoSources: Bool = false
    public var photoSourceProviders: [String] = []
    /// Sources may reach home-network addresses (`photo_sources_private_networks`).
    public var photoSourcesPrivateNetworks: Bool = false
    /// "Add from Google Photos" is offered (`google_photos_import`).
    public var googlePhotosImport: Bool = false
    /// Per-platform version policy of the family apps (`app_versions`, README §5.15); nil when none is set.
    public var appVersions: AppVersions?
    /// Frames and feeds can have inbox addresses (`email_inbox`, docs/spec/email-inbox.md), at `emailInboxDomain`.
    public var emailInbox: Bool = false
    public var emailInboxDomain: String?
    /// The server has a Telegram bot (`telegram`, docs/spec/telegram.md): its username for t.me links, whether it runs
    /// in privacy mode (a group must make it an admin) and the largest file it can download.
    public var telegram: Bool = false
    public var telegramBotUsername: String?
    public var telegramPrivacyMode: Bool = false
    public var telegramMaxFileBytes: Int64?
    /// Family social (docs/spec/family-social.md): the timeline, threads, people's reactions, the `member` role and
    /// circles (`social`, only with `feeds`); chats, blocks and reports (`chats`), voice messages and link previews in
    /// them; encouragement to share (`engagement`) and the email digest. Each area is hidden while its flag is off.
    public var social: Bool = false
    public var chats: Bool = false
    public var voiceMessages: Bool = false
    public var linkPreviews: Bool = false
    public var engagement: Bool = false
    public var emailDigest: Bool = false
    /// Several servers (docs/spec/multi-server.md §2.3): the server's stable random id (deduplicates accounts), its
    /// operator-set label (the server chip) and its public base URL (the `server` of push payloads).
    public var serverId: String?
    public var serverName: String?
    public var baseURL: String?
    /// Leaving a family with a choice, taking photos away, keep requests, the deletion handover and the memories
    /// filter (`leaving`, README §5.21). Hidden while off.
    public var leaving: Bool = false
    /// Frames here can follow families on other servers, families here can allow frames on other servers
    /// (`remote_families`, README §5.22). Hidden while off.
    public var remoteFamilies: Bool = false

    public init(name: String, version: String, api: String, web: String? = nil, auth: Auth = Auth(), limits: Limits = .defaults, devAuth: Bool = false, weather: Bool = false) {
        self.name = name
        self.version = version
        self.api = api
        self.web = web
        self.auth = auth
        self.limits = limits
        self.devAuth = devAuth
        self.weather = weather
    }

    enum CodingKeys: String, CodingKey {
        case name, version, api, web, auth, limits, weather, calendar, shop, feeds, birthdays
        case devAuth = "dev_auth"
        case photoSources = "photo_sources"
        case photoSourceProviders = "photo_source_providers"
        case photoSourcesPrivateNetworks = "photo_sources_private_networks"
        case googlePhotosImport = "google_photos_import"
        case appVersions = "app_versions"
        case emailInbox = "email_inbox"
        case emailInboxDomain = "email_inbox_domain"
        case telegram
        case telegramBotUsername = "telegram_bot_username"
        case telegramPrivacyMode = "telegram_privacy_mode"
        case telegramMaxFileBytes = "telegram_max_file_bytes"
        case social, chats, engagement
        case voiceMessages = "voice_messages"
        case linkPreviews = "link_previews"
        case emailDigest = "email_digest"
        case serverId = "server_id"
        case serverName = "server_name"
        case baseURL = "base_url"
        case leaving
        case remoteFamilies = "remote_families"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        version = try c.decodeIfPresent(String.self, forKey: .version) ?? ""
        api = try c.decode(String.self, forKey: .api)
        web = try c.decodeIfPresent(String.self, forKey: .web)
        auth = try c.decodeIfPresent(Auth.self, forKey: .auth) ?? Auth()
        limits = try c.decodeIfPresent(Limits.self, forKey: .limits) ?? .defaults
        devAuth = try c.decodeIfPresent(Bool.self, forKey: .devAuth) ?? false
        weather = try c.decodeIfPresent(Bool.self, forKey: .weather) ?? false
        calendar = try c.decodeIfPresent(Bool.self, forKey: .calendar) ?? false
        feeds = (try? c.decodeIfPresent(Bool.self, forKey: .feeds)) ?? false
        birthdays = feeds && ((try? c.decodeIfPresent(Bool.self, forKey: .birthdays)) ?? false)
        photoSources = (try? c.decodeIfPresent(Bool.self, forKey: .photoSources)) ?? false
        photoSourceProviders = photoSources ? ((try? c.decodeIfPresent([String].self, forKey: .photoSourceProviders)) ?? []) : []
        photoSourcesPrivateNetworks = (try? c.decodeIfPresent(Bool.self, forKey: .photoSourcesPrivateNetworks)) ?? false
        googlePhotosImport = (try? c.decodeIfPresent(Bool.self, forKey: .googlePhotosImport)) ?? false
        appVersions = (try? c.decodeIfPresent(AppVersions.self, forKey: .appVersions)) ?? nil
        emailInbox = (try? c.decodeIfPresent(Bool.self, forKey: .emailInbox)) ?? false
        emailInboxDomain = emailInbox ? ((try? c.decodeIfPresent(String.self, forKey: .emailInboxDomain)) ?? nil) : nil
        telegram = (try? c.decodeIfPresent(Bool.self, forKey: .telegram)) ?? false
        telegramBotUsername = telegram ? ((try? c.decodeIfPresent(String.self, forKey: .telegramBotUsername)) ?? nil) : nil
        telegramPrivacyMode = telegram && ((try? c.decodeIfPresent(Bool.self, forKey: .telegramPrivacyMode)) ?? false)
        telegramMaxFileBytes = telegram ? ((try? c.decodeIfPresent(Int64.self, forKey: .telegramMaxFileBytes)) ?? nil) : nil
        social = feeds && ((try? c.decodeIfPresent(Bool.self, forKey: .social)) ?? false)
        chats = (try? c.decodeIfPresent(Bool.self, forKey: .chats)) ?? false
        voiceMessages = chats && ((try? c.decodeIfPresent(Bool.self, forKey: .voiceMessages)) ?? false)
        linkPreviews = chats && ((try? c.decodeIfPresent(Bool.self, forKey: .linkPreviews)) ?? false)
        engagement = (try? c.decodeIfPresent(Bool.self, forKey: .engagement)) ?? false
        emailDigest = (try? c.decodeIfPresent(Bool.self, forKey: .emailDigest)) ?? false
        serverId = (try? c.decodeIfPresent(String.self, forKey: .serverId)).flatMap { $0?.isEmpty == false ? $0 : nil }
        serverName = (try? c.decodeIfPresent(String.self, forKey: .serverName)).flatMap { $0?.isEmpty == false ? $0 : nil }
        baseURL = (try? c.decodeIfPresent(String.self, forKey: .baseURL)).flatMap { $0?.isEmpty == false ? $0 : nil }
        leaving = (try? c.decodeIfPresent(Bool.self, forKey: .leaving)) ?? false
        remoteFamilies = (try? c.decodeIfPresent(Bool.self, forKey: .remoteFamilies)) ?? false
        // The server sends the shop's configuration (an object); we keep only whether there is one.
        if let b = try? c.decode(Bool.self, forKey: .shop) {
            shop = b
        } else {
            shop = c.contains(.shop) && (try? c.decodeNil(forKey: .shop)) == false
        }
    }

    /// Logto is configured when the server published an issuer and a native client id.
    public var logtoConfigured: Bool {
        !auth.issuer.isEmpty && !auth.mobileClientId.isEmpty && !auth.apiResource.isEmpty
    }

    /// The base URL the API paths (which start with /v1) are relative to: `api` without its /v1 suffix.
    public func apiBase(fallback: URL) -> URL {
        var s = api.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/v1") { s.removeLast(3) }
        guard let url = URL(string: s), url.scheme == "https" || url.scheme == "http", url.host != nil else { return fallback }
        return url
    }

    /// Logto's endpoint is the issuer without its /oidc suffix.
    public var logtoEndpoint: String {
        var s = auth.issuer
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/oidc") { s.removeLast(5) }
        return s
    }
}

public enum ServerAddress {
    public static let defaultServer = URL(string: "https://kinframe.appmire.be")!

    /// Turn what someone typed into a server base URL: adds https:// when missing, drops paths, whitespace and
    /// trailing slashes, and tolerates a pasted discovery or join link. Returns nil when it can't be a server.
    public static func normalize(_ input: String) -> URL? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty, !s.contains(" ") else { return nil }
        if s.lowercased().hasPrefix("https:/") && !s.lowercased().hasPrefix("https://") {
            s = "https://" + s.dropFirst(7)
        }
        if !s.contains("://") { s = "https://" + s }
        guard var c = URLComponents(string: s), let scheme = c.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = c.host, !host.isEmpty, host.contains(".") || host == "localhost" || host.contains(":")
        else { return nil }
        c.scheme = scheme
        c.host = host.lowercased()
        c.path = ""
        c.query = nil
        c.fragment = nil
        c.user = nil
        c.password = nil
        return c.url
    }

    /// The well-known discovery URL for a server base URL.
    public static func discoveryURL(for server: URL) -> URL {
        server.appending(path: ".well-known/kinframe")
    }

    /// Display form: host plus port when not default, e.g. "kinframe.appmire.be".
    public static func display(_ server: URL) -> String {
        var s = server.host() ?? server.absoluteString
        if let port = server.port { s += ":\(port)" }
        if server.scheme == "http" { s = "http://" + s }
        return s
    }
}

public enum DiscoveryError: Error, Equatable {
    case invalidAddress
    case unreachable
    case notKinframe
}

public enum DiscoveryClient {
    /// Fetch and parse the discovery document of a server.
    public static func fetch(_ server: URL, session: URLSession = .shared) async throws -> Discovery {
        var request = URLRequest(url: ServerAddress.discoveryURL(for: server), timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.identifyApp()
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw DiscoveryError.unreachable
        }
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw DiscoveryError.notKinframe }
        do {
            return try JSONDecoder().decode(Discovery.self, from: data)
        } catch {
            throw DiscoveryError.notKinframe
        }
    }
}
