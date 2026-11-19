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
/// One instance per account (docs/spec/multi-server.md §2.1). Each account keeps its refresh token, ID token and
/// current access token in the Keychain under its own names, shared with the share extension. The Logto SDK only runs
/// the browser sign-in, with its persistent storage off, so its single Keychain slot never holds (or hands out) any
/// account's tokens. Refreshing is one call to the account's own Logto tenant (`TokenEndpoint`) with the account's own
/// refresh token, so a token of one server is never sent to another:
///
/// - Refreshes of one account are serialized: within the process one refresh at a time per account (whichever
///   `AuthService` asks), across the app and the share extension by a file lock (`SessionLock`). Under the lock the
///   Keychain is read again, so a refresh the other process just did is used instead of spending the rotated token.
/// - A 401 only refreshes when the refused token is still the cached one; concurrent 401s share one refresh.
/// - A token is only cached and sent when it was issued for this account (`TokenAudience`).
/// - Signing in or out while a refresh runs wins: the refresh's result is dropped.
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

    struct CachedToken: Codable {
        var token: String
        var expiresAt: Date
        var resource: String
    }

    public private(set) var mode: Mode?
    /// The account this session belongs to (`AccountRecord.id`).
    public let accountId: String
    private var discovery: Discovery?
    private let slot: LogtoSlot
    private let vault: SessionVault
    private let endpoint: TokenEndpoint
    private let lock: SessionLock
    private let defaults: UserDefaults
    /// Bumped by sign-in and sign-out: a refresh that started before doesn't write its result.
    private var generation = 0
    /// Called when the session ended on its own (refresh token rejected).
    public var onSignedOut: (() -> Void)?

    /// The refreshes running in this process, per account (several `AuthService`s may serve one account).
    private let refreshes: RefreshRegistry

    public init(account: String = AccountRecord.legacyId, vault: SessionVault = KeychainVault(),
                endpoint: TokenEndpoint = LogtoTokenEndpoint.shared, lock: SessionLock = FileSessionLock(),
                defaults: UserDefaults = AppGroup.defaults, refreshes: RefreshRegistry = .shared) {
        accountId = account
        self.refreshes = refreshes
        self.vault = vault
        self.endpoint = endpoint
        self.lock = lock
        self.defaults = defaults
        slot = LogtoSlot(account: account, vault: vault)
        mode = defaults.string(forKey: Self.name(Key.mode, account)).flatMap(Mode.init(rawValue:))
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
        case .dev: vault.string(name(Key.devToken)) != nil
        case .logto: slot.idToken != nil && discovery?.logtoConfigured == true
        case nil: false
        }
    }

    // MARK: Sign in and out

    /// Run the Logto sign-in in the system browser sheet. Returns the name from the ID token, if any.
    @discardableResult
    public func signIn() async throws -> String? {
        guard let discovery, discovery.logtoConfigured,
              let config = try? LogtoConfig(
                  endpoint: discovery.logtoEndpoint,
                  appId: discovery.auth.mobileClientId,
                  scopes: Self.scopes,
                  resources: [discovery.auth.apiResource],
                  usingPersistStorage: false,
                  prefersEphemeralWebBrowserSession: true
              )
        else { throw APIError(status: 0, code: "feature_disabled") }
        // A client of its own, in memory only: it starts without anyone's session and keeps nothing.
        let logto = LogtoClient(useConfig: config)
        try await logto.signInWithBrowser(redirectUri: Self.redirectURI)
        let token = try await logto.getAccessToken(for: discovery.auth.apiResource)
        generation += 1
        let handle = await lock.acquire(accountId)
        defer { handle.release() }
        slot.store(refreshToken: logto.refreshToken, idToken: logto.idToken)
        setMode(.logto)
        vault.set(nil, name(Key.accessToken))
        if TokenAudience.matches(token, auth: discovery.auth) { cache(token, resource: discovery.auth.apiResource) }
        let claims = try? logto.getIdTokenClaims()
        return (claims?.name ?? claims?.username).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// Development servers only: `POST /dev/token {email, name}`.
    public func devSignIn(token: String) {
        generation += 1
        vault.set(token, name(Key.devToken))
        setMode(.dev)
    }

    /// Forget the session on this device and revoke the refresh token.
    public func signOut() async {
        let refresh = mode == .logto ? slot.refreshToken : nil
        let discovery = discovery
        clearLocal()
        if let refresh, let discovery, discovery.logtoConfigured {
            await endpoint.revoke(refresh, auth: discovery.auth, endpoint: discovery.logtoEndpoint)
        }
    }

    private func clearLocal() {
        generation += 1
        vault.set(nil, name(Key.devToken))
        vault.set(nil, name(Key.accessToken))
        slot.store(refreshToken: nil, idToken: nil)
        setMode(nil)
    }

    private func setMode(_ mode: Mode?) {
        self.mode = mode
        defaults.set(mode?.rawValue, forKey: name(Key.mode))
    }

    /// The session is over (refresh token refused): forget it and say so once.
    private func sessionEnded() {
        guard mode != nil else { return }
        clearLocal()
        onSignedOut?()
    }

    // MARK: Tokens

    /// A valid access token for the API resource, or nil when signed out.
    public func accessToken() async throws -> String? {
        switch mode {
        case .dev:
            return vault.string(name(Key.devToken))
        case .logto:
            guard let resource = discovery?.auth.apiResource else { return nil }
            if let cached = cachedToken(resource) { return cached }
            return try await refresh(rejected: nil)
        case nil:
            return nil
        }
    }

    /// After a 401 without knowing which token was refused: treat the cached one as refused.
    public func refreshAfterUnauthorized() async -> String? {
        await refreshAfterUnauthorized(rejected: nil)
    }

    /// After a 401: when another request already replaced the refused token, the new one; else a fresh one; nil when
    /// the session is over (or the network is down: the request fails as it is).
    public func refreshAfterUnauthorized(rejected: String?) async -> String? {
        switch mode {
        case .dev:
            // Dev tokens can't be refreshed: the session is over (unless it changed meanwhile).
            if let current = vault.string(name(Key.devToken)), rejected != nil, current != rejected { return current }
            sessionEnded()
            return nil
        case .logto:
            guard let resource = discovery?.auth.apiResource else { return nil }
            if let cached = cachedToken(resource), rejected != nil, cached != rejected { return cached }
            let refused = rejected ?? cachedToken(resource, minimum: -.infinity)
            return try? await refresh(rejected: refused)
        case nil:
            return nil
        }
    }

    /// The cached access token for `resource` while it is valid for more than `minimum` seconds.
    private func cachedToken(_ resource: String, minimum: TimeInterval = 60) -> String? {
        guard let data = vault.string(name(Key.accessToken)).map({ Data($0.utf8) }),
              let cached = try? JSONDecoder().decode(CachedToken.self, from: data),
              cached.resource == resource, cached.expiresAt.timeIntervalSinceNow > minimum else { return nil }
        return cached.token
    }

    /// One refresh per account at a time in this process; callers meanwhile share its result.
    private func refresh(rejected: String?) async throws -> String? {
        if let running = refreshes.running[accountId] { return try await running.task.value }
        let id = UUID()
        let account = accountId
        let registry = refreshes
        let task = Task<String?, Error> { @MainActor in
            defer { if registry.running[account]?.id == id { registry.running[account] = nil } }
            return try await self.refreshLocked(rejected: rejected)
        }
        registry.running[account] = (id, task)
        return try await task.value
    }

    private func refreshLocked(rejected: String?) async throws -> String? {
        guard let discovery, discovery.logtoConfigured else { return nil }
        let resource = discovery.auth.apiResource
        let started = generation
        let handle = await lock.acquire(accountId)
        defer { handle.release() }
        guard generation == started, mode == .logto else { return nil }
        // The other process (or an earlier refresh here) may have refreshed while this one waited for the lock.
        if let cached = cachedToken(resource), cached != rejected { return cached }
        guard let refreshToken = slot.refreshToken else {
            sessionEnded()
            return nil
        }
        let grant: TokenGrant
        do {
            grant = try await endpoint.refresh(refreshToken, auth: discovery.auth, endpoint: discovery.logtoEndpoint)
        } catch TokenEndpointError.rejected {
            // Signed out or in again meanwhile: not this refresh's session any more.
            guard generation == started else { return nil }
            sessionEnded()
            return nil
        } catch {
            throw APIError.network
        }
        guard generation == started else { return nil }
        // Keep the rotated refresh token first: the old one is spent.
        slot.store(refreshToken: grant.refreshToken ?? refreshToken, idToken: grant.idToken ?? slot.idToken)
        guard TokenAudience.matches(grant.accessToken, auth: discovery.auth) else {
            vault.set(nil, name(Key.accessToken))
            throw APIError(status: 401, code: "unauthorized")
        }
        cache(grant.accessToken, resource: resource, expiresIn: grant.expiresIn)
        return grant.accessToken
    }

    private func cache(_ token: String, resource: String, expiresIn: Int? = nil) {
        let expiry = JWT.expiry(token) ?? expiresIn.map { Date().addingTimeInterval(TimeInterval($0)) } ?? Date().addingTimeInterval(30 * 60)
        let cached = CachedToken(token: token, expiresAt: expiry, resource: resource)
        vault.set((try? JSONEncoder().encode(cached)).flatMap { String(data: $0, encoding: .utf8) }, name(Key.accessToken))
    }
}

extension AuthService: TokenProvider {}

/// The refreshes running in one process, per account. One per process (`shared`); tests make a second one to stand
/// for the share extension.
@MainActor
public final class RefreshRegistry {
    public static let shared = RefreshRegistry()
    var running: [String: (id: UUID, task: Task<String?, Error>)] = [:]
    public init() {}
}

/// One account's Logto tokens, kept under its own Keychain names. Earlier builds kept the only account's tokens in
/// the Logto SDK's slot (`io.logto.client`): those are read once for the legacy account, never written.
struct LogtoSlot {
    let account: String
    let vault: SessionVault

    private var refreshName: String { AuthService.name("logto.refresh_token", account) }
    private var idName: String { AuthService.name("logto.id_token", account) }

    var refreshToken: String? { vault.string(refreshName) }
    var idToken: String? { vault.string(idName) }

    func store(refreshToken: String?, idToken: String?) {
        vault.set(refreshToken, refreshName)
        vault.set(idToken, idName)
    }

    /// The account signed in before several accounts were possible has its tokens only in the SDK's slot.
    func migrateIfNeeded() {
        guard account == AccountRecord.legacyId, vault is KeychainVault, idToken == nil, refreshToken == nil else { return }
        let refresh = SDKSlot.get("refresh_token"), id = SDKSlot.get("id_token")
        if refresh != nil || id != nil { store(refreshToken: refresh, idToken: id) }
    }

    /// The Logto SDK's Keychain items: service `io.logto.client`, account = the key name, UTF-8 values.
    enum SDKSlot {
        static let service = "io.logto.client"

        static func get(_ key: String) -> String? {
            let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: key, kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
                                    kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
            return String(data: data, encoding: .utf8)
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
