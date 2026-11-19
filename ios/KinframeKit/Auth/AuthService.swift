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

/// The primary account's server (single-server callers; the account list is `AccountStore`).
public enum ServerStore {
    public static func load() -> ServerConfig? {
        AccountStore.primary?.server
    }
}

/// Signs people in with Logto (Authorization Code + PKCE through ASWebAuthenticationSession), or with a dev token
/// on development servers, and hands out access tokens for the API resource.
///
/// Refresh tokens live in the Keychain and the current access token is cached there too, so the share extension uses
/// the same session. Before refreshing, the Logto client is rebuilt from the Keychain so a refresh token rotated by
/// the other process is picked up.
///
/// One instance per account (docs/spec/multi-server.md §2.1). The Logto SDK keeps a single session in its own Keychain
/// service, so each account keeps its tokens under its own names (`LogtoSlot`) and lends them to a Logto client right
/// before using it; what the client ends up with (a rotated refresh token) is copied back from its memory, never from
/// the shared slot another account may have written meanwhile.
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
    /// The account this session belongs to (`AccountRecord.id`).
    public let accountId: String
    private var discovery: Discovery?
    private var refreshing: Task<String?, Error>?
    private let slot: LogtoSlot
    /// Called when the session ended on its own (refresh token rejected).
    public var onSignedOut: (() -> Void)?

    public init(account: String = AccountRecord.legacyId) {
        accountId = account
        slot = LogtoSlot(account: account)
        mode = AppGroup.defaults.string(forKey: Self.name(Key.mode, account)).flatMap(Mode.init(rawValue:))
        if mode == .logto { slot.migrateIfNeeded() }
    }

    /// Keychain and defaults names: the legacy account keeps the old ones (no new sign-in after the update).
    nonisolated static func name(_ key: String, _ account: String) -> String {
        account == AccountRecord.legacyId ? key : "\(key).\(account)"
    }

    private func name(_ key: String) -> String { Self.name(key, accountId) }

    public func configure(_ discovery: Discovery?) {
        self.discovery = discovery
    }

    public var isSignedIn: Bool {
        switch mode {
        case .dev: Keychain.string(name(Key.devToken)) != nil
        case .logto: slot.idToken != nil && discovery?.logtoConfigured == true
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
        // The SDK reads its Keychain slot while initialising: put this account's tokens there first (no suspension
        // point in between, so no other account can write the slot meanwhile).
        slot.lend()
        return LogtoClient(useConfig: config)
    }

    /// Keep what the client holds now (a rotated refresh token, the ID token after a sign-in).
    private func keep(_ logto: LogtoClient) {
        slot.store(refreshToken: logto.refreshToken, idToken: logto.idToken)
    }

    // MARK: Sign in and out

    /// Run the Logto sign-in in the system browser sheet. Returns the name from the ID token, if any.
    @discardableResult
    public func signIn() async throws -> String? {
        // A fresh client: never sign in on top of another account's session.
        slot.store(refreshToken: nil, idToken: nil)
        guard let logto = makeLogto(), let discovery else { throw APIError(status: 0, code: "feature_disabled") }
        try await logto.signInWithBrowser(redirectUri: Self.redirectURI)
        keep(logto)
        setMode(.logto)
        let token = try await logto.getAccessToken(for: discovery.auth.apiResource)
        keep(logto)
        cache(token, resource: discovery.auth.apiResource)
        let claims = try? logto.getIdTokenClaims()
        return (claims?.name ?? claims?.username).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// Development servers only: `POST /dev/token {email, name}`.
    public func devSignIn(token: String) {
        Keychain.set(token, for: name(Key.devToken))
        setMode(.dev)
    }

    /// Forget the session on this device and revoke the refresh token.
    public func signOut() async {
        if mode == .logto, let logto = makeLogto() {
            _ = await logto.clearCredentials()
        }
        slot.store(refreshToken: nil, idToken: nil)
        clearLocal()
    }

    private func clearLocal() {
        Keychain.set(nil as Data?, for: name(Key.devToken))
        Keychain.set(nil as Data?, for: name(Key.accessToken))
        refreshing?.cancel()
        refreshing = nil
        setMode(nil)
    }

    private func setMode(_ mode: Mode?) {
        self.mode = mode
        AppGroup.defaults.set(mode?.rawValue, forKey: name(Key.mode))
    }

    // MARK: Tokens

    /// A valid access token for the API resource, or nil when signed out.
    public func accessToken() async throws -> String? {
        switch mode {
        case .dev:
            return Keychain.string(name(Key.devToken))
        case .logto:
            guard let resource = discovery?.auth.apiResource else { return nil }
            if let cached = Keychain.data(name(Key.accessToken)).flatMap({ try? JSONDecoder().decode(CachedToken.self, from: $0) }),
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
            Keychain.set(nil as Data?, for: name(Key.accessToken))
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
                self.keep(logto)
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
        Keychain.set(try? JSONEncoder().encode(cached), for: name(Key.accessToken))
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

/// One account's Logto tokens, kept under its own Keychain names, and the SDK's single slot (`io.logto.client`,
/// written the way its KeychainAccess store does) they are lent to.
struct LogtoSlot {
    let account: String

    private var refreshName: String { AuthService.name("logto.refresh_token", account) }
    private var idName: String { AuthService.name("logto.id_token", account) }

    var refreshToken: String? { Keychain.string(refreshName) }
    var idToken: String? { Keychain.string(idName) }

    func store(refreshToken: String?, idToken: String?) {
        Keychain.set(refreshToken, for: refreshName)
        Keychain.set(idToken, for: idName)
    }

    /// The account signed in before several accounts were possible has its tokens only in the SDK's slot.
    func migrateIfNeeded() {
        guard account == AccountRecord.legacyId, idToken == nil, refreshToken == nil else { return }
        let refresh = SDKSlot.get("refresh_token"), id = SDKSlot.get("id_token")
        if refresh != nil || id != nil { store(refreshToken: refresh, idToken: id) }
    }

    /// Put this account's tokens into the SDK's slot (an empty slot for an account without a session).
    func lend() {
        SDKSlot.set(refreshToken, "refresh_token")
        SDKSlot.set(idToken, "id_token")
    }

    /// The Logto SDK's Keychain items: service `io.logto.client`, account = the key name, UTF-8 values.
    enum SDKSlot {
        static let service = "io.logto.client"

        private static func query(_ key: String) -> [String: Any] {
            [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key,
             kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        }

        static func get(_ key: String) -> String? {
            var q = query(key)
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
            return String(data: data, encoding: .utf8)
        }

        static func set(_ value: String?, _ key: String) {
            SecItemDelete(query(key) as CFDictionary)
            guard let value else { return }
            let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                      kSecAttrAccount as String: key, kSecValueData as String: Data(value.utf8),
                                      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}

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
