import Foundation
@preconcurrency import LogtoClient
@preconcurrency import Logto

/// The chosen server and its discovery document, shared with the share extension.
public struct ServerConfig: Codable, Equatable, Sendable {
    public var url: URL
    public var discovery: Discovery

    public init(url: URL, discovery: Discovery) {
        self.url = url
        self.discovery = discovery
    }

    /// Base URL for API calls (from discovery `api`, falling back to the server address).
    public var apiBase: URL { discovery.apiBase(fallback: url) }
}

public enum ServerStore {
    private static let key = "server"

    public static func load() -> ServerConfig? {
        AppGroup.defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(ServerConfig.self, from: $0) }
    }

    public static func save(_ config: ServerConfig?) {
        AppGroup.defaults.set(config.flatMap { try? JSONEncoder().encode($0) }, forKey: key)
    }
}

/// Signs people in with Logto (Authorization Code + PKCE through ASWebAuthenticationSession), or with a dev token
/// on development servers, and hands out access tokens for the API resource.
///
/// Refresh tokens live in the Keychain (Logto SDK) and the current access token is cached there too, so the share
/// extension uses the same session. Before refreshing, the Logto client is rebuilt from the Keychain so a refresh
/// token rotated by the other process is picked up.
@MainActor
public final class AuthService {
    public enum Mode: String, Sendable {
        case logto, dev
    }

    public static let redirectURI = "app.kinframe:/callback"
    static let scopes = ["email", "profile", "offline_access", "kinframe:operate"]

    private enum Key {
        static let mode = "authMode"
        static let devToken = "devToken"
        static let accessToken = "accessToken"
    }

    private struct CachedToken: Codable {
        var token: String
        var expiresAt: Date
        var resource: String
    }

    public private(set) var mode: Mode?
    private var discovery: Discovery?
    private var refreshing: Task<String?, Error>?
    /// Called when the session ended on its own (refresh token rejected).
    public var onSignedOut: (() -> Void)?

    public init() {
        mode = AppGroup.defaults.string(forKey: Key.mode).flatMap(Mode.init(rawValue:))
    }

    public func configure(_ discovery: Discovery?) {
        self.discovery = discovery
    }

    public var isSignedIn: Bool {
        switch mode {
        case .dev: Keychain.string(Key.devToken) != nil
        case .logto: makeLogto()?.isAuthenticated ?? false
        case nil: false
        }
    }

    private func makeLogto() -> LogtoClient? {
        guard let discovery, discovery.logtoConfigured,
              let config = try? LogtoConfig(
                  endpoint: discovery.logtoEndpoint,
                  appId: discovery.auth.mobileClientId,
                  scopes: Self.scopes,
                  resources: [discovery.auth.apiResource],
                  prefersEphemeralWebBrowserSession: true
              )
        else { return nil }
        return LogtoClient(useConfig: config)
    }

    // MARK: Sign in and out

    /// Run the Logto sign-in in the system browser sheet. Returns the name from the ID token, if any.
    @discardableResult
    public func signIn() async throws -> String? {
        guard let logto = makeLogto(), let discovery else { throw APIError(status: 0, code: "feature_disabled") }
        try await logto.signInWithBrowser(redirectUri: Self.redirectURI)
        setMode(.logto)
        let token = try await logto.getAccessToken(for: discovery.auth.apiResource)
        cache(token, resource: discovery.auth.apiResource)
        let claims = try? logto.getIdTokenClaims()
        return (claims?.name ?? claims?.username).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// Development servers only: `POST /dev/token {email, name}`.
    public func devSignIn(token: String) {
        Keychain.set(token, for: Key.devToken)
        setMode(.dev)
    }

    /// Forget the session on this device and revoke the refresh token.
    public func signOut() async {
        if mode == .logto, let logto = makeLogto() {
            _ = await logto.clearCredentials()
        }
        clearLocal()
    }

    private func clearLocal() {
        Keychain.set(nil as Data?, for: Key.devToken)
        Keychain.set(nil as Data?, for: Key.accessToken)
        refreshing?.cancel()
        refreshing = nil
        setMode(nil)
    }

    private func setMode(_ mode: Mode?) {
        self.mode = mode
        AppGroup.defaults.set(mode?.rawValue, forKey: Key.mode)
    }

    // MARK: Tokens

    /// A valid access token for the API resource, or nil when signed out.
    public func accessToken() async throws -> String? {
        switch mode {
        case .dev:
            return Keychain.string(Key.devToken)
        case .logto:
            guard let resource = discovery?.auth.apiResource else { return nil }
            if let cached = Keychain.data(Key.accessToken).flatMap({ try? JSONDecoder().decode(CachedToken.self, from: $0) }),
               cached.resource == resource, cached.expiresAt.timeIntervalSinceNow > 60 {
                return cached.token
            }
            return try await refresh()
        case nil:
            return nil
        }
    }

    /// After a 401: drop the cached token and get a new one.
    public func refreshAfterUnauthorized() async -> String? {
        switch mode {
        case .dev:
            // Dev tokens can't be refreshed: the session is over.
            clearLocal()
            onSignedOut?()
            return nil
        case .logto:
            Keychain.set(nil as Data?, for: Key.accessToken)
            return try? await refresh()
        case nil:
            return nil
        }
    }

    private func refresh() async throws -> String? {
        if let refreshing { return try await refreshing.value }
        let task = Task<String?, Error> { @MainActor in
            defer { self.refreshing = nil }
            guard let logto = self.makeLogto(), let resource = self.discovery?.auth.apiResource else { return nil }
            guard logto.isAuthenticated else {
                self.clearLocal()
                self.onSignedOut?()
                return nil
            }
            do {
                let token = try await logto.getAccessToken(for: resource)
                self.cache(token, resource: resource)
                return token
            } catch {
                if Self.isTransient(error) { throw APIError.network }
                // The refresh token was rejected: the session is over.
                self.clearLocal()
                self.onSignedOut?()
                return nil
            }
        }
        refreshing = task
        return try await task.value
    }

    private func cache(_ token: String, resource: String) {
        let expiry = JWT.expiry(token) ?? Date().addingTimeInterval(30 * 60)
        let cached = CachedToken(token: token, expiresAt: expiry, resource: resource)
        Keychain.set(try? JSONEncoder().encode(cached), for: Key.accessToken)
    }

    /// Network trouble (keep the session) versus a rejected refresh token (sign out).
    static func isTransient(_ error: Error) -> Bool {
        var current: Error? = error
        for _ in 0..<4 {
            guard let e = current else { return false }
            if e is URLError { return true }
            if let a = e as? LogtoClientErrors.AccessToken { current = a.innerError; continue }
            let mirror = Mirror(reflecting: e)
            current = mirror.children.first { $0.label == "innerError" }?.value as? Error
        }
        return false
    }
}

extension AuthService: TokenProvider {}

/// Minimal JWT payload reading (no verification: the server verifies).
enum JWT {
    static func payload(_ token: String) -> [String: Any]? {
        let parts = token.split(separator: ".")
        guard parts.count == 3 else { return nil }
        var b64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func expiry(_ token: String) -> Date? {
        (payload(token)?["exp"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue) }
    }
}
