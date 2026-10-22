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
        case name, version, api, web, auth, limits, weather
        case devAuth = "dev_auth"
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
