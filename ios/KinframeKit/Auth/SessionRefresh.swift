import Foundation

/// Where one account's session lives: the Keychain in the app and the share extension (shared through the access
/// group), memory in tests.
public protocol SessionVault: Sendable {
    func string(_ name: String) -> String?
    func set(_ value: String?, _ name: String)
}

/// The Keychain (`Keychain`): what the app and the share extension both see.
public struct KeychainVault: SessionVault {
    public init() {}
    public func string(_ name: String) -> String? { Keychain.string(name) }
    public func set(_ value: String?, _ name: String) { Keychain.set(value, for: name) }
}

/// What Logto's token endpoint answered to a refresh.
public struct TokenGrant: Equatable, Sendable {
    public var accessToken: String
    /// A rotated refresh token; nil keeps the one that was used.
    public var refreshToken: String?
    public var idToken: String?
    public var expiresIn: Int?

    public init(accessToken: String, refreshToken: String? = nil, idToken: String? = nil, expiresIn: Int? = nil) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.idToken = idToken
        self.expiresIn = expiresIn
    }
}

public enum TokenEndpointError: Error, Equatable {
    /// The refresh token was refused (`invalid_grant`, revoked, expired): the session is over.
    case rejected
    /// Network trouble or a server error: keep the session and try again later.
    case transient
}

/// The two calls a session needs from its Logto tenant after sign-in: refreshing and revoking. Each call carries the
/// account's own refresh token and goes to the account's own tenant (`Discovery.auth`), so nothing depends on the
/// Logto SDK's single Keychain slot.
public protocol TokenEndpoint: Sendable {
    func refresh(_ refreshToken: String, auth: Discovery.Auth, endpoint: String) async throws -> TokenGrant
    func revoke(_ refreshToken: String, auth: Discovery.Auth, endpoint: String) async
}

/// Logto's OIDC endpoints over HTTP (RFC 6749 §6 refresh, RFC 7009 revocation), found through the tenant's OpenID
/// configuration.
public final class LogtoTokenEndpoint: TokenEndpoint, @unchecked Sendable {
    private let session: URLSession
    private let lock = NSLock()
    private var configs: [String: OIDCConfig] = [:]

    struct OIDCConfig: Decodable {
        var tokenEndpoint: String
        var revocationEndpoint: String?

        enum CodingKeys: String, CodingKey {
            case tokenEndpoint = "token_endpoint"
            case revocationEndpoint = "revocation_endpoint"
        }
    }

    public init(session: URLSession = API.defaultSession()) {
        self.session = session
    }

    public static let shared = LogtoTokenEndpoint()

    private func config(_ endpoint: String) async -> OIDCConfig {
        if let c = lock.withLock({ configs[endpoint] }) { return c }
        let fallback = OIDCConfig(tokenEndpoint: endpoint + "/oidc/token", revocationEndpoint: endpoint + "/oidc/token/revocation")
        guard let url = URL(string: endpoint + "/oidc/.well-known/openid-configuration"),
              let (data, response) = try? await session.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let c = try? JSONDecoder().decode(OIDCConfig.self, from: data) else { return fallback }
        lock.withLock { configs[endpoint] = c }
        return c
    }

    static func form(_ fields: [(String, String)]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return Data(fields.map { k, v in
            "\(k)=\(v.addingPercentEncoding(withAllowedCharacters: allowed) ?? v)"
        }.joined(separator: "&").utf8)
    }

    public func refresh(_ refreshToken: String, auth: Discovery.Auth, endpoint: String) async throws -> TokenGrant {
        let c = await config(endpoint)
        guard let url = URL(string: c.tokenEndpoint) else { throw TokenEndpointError.transient }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Self.form([("grant_type", "refresh_token"), ("client_id", auth.mobileClientId),
                                      ("refresh_token", refreshToken), ("resource", auth.apiResource)])
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw TokenEndpointError.transient
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try Self.grant(status: status, body: data)
    }

    /// Reads the token endpoint's answer: a grant, `rejected` for a refused refresh token, else `transient`.
    static func grant(status: Int, body: Data) throws -> TokenGrant {
        let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        if status == 200, let token = obj?["access_token"] as? String, !token.isEmpty {
            return TokenGrant(accessToken: token, refreshToken: obj?["refresh_token"] as? String,
                              idToken: obj?["id_token"] as? String, expiresIn: (obj?["expires_in"] as? NSNumber)?.intValue)
        }
        let code = obj?["error"] as? String
        if code == "invalid_grant" || status == 401 { throw TokenEndpointError.rejected }
        throw TokenEndpointError.transient
    }

    public func revoke(_ refreshToken: String, auth: Discovery.Auth, endpoint: String) async {
        let c = await config(endpoint)
        guard let s = c.revocationEndpoint, let url = URL(string: s) else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.form([("token", refreshToken), ("token_type_hint", "refresh_token"), ("client_id", auth.mobileClientId)])
        _ = try? await session.data(for: request)
    }
}

/// Lets one process at a time refresh an account's session: the app and the share extension share the refresh token,
/// and Logto rotates it on every use, so two refreshes with the same token would end the session.
public protocol SessionLock: Sendable {
    /// Wait until this process holds the account's lock. Always returns (after a timeout without the lock), so a
    /// stuck process can't block sign-in forever.
    func acquire(_ account: String) async -> SessionLockHandle
}

public protocol SessionLockHandle: Sendable {
    func release()
}

/// `flock(2)` on a file per account in the App Group container. The kernel drops the lock when a process dies.
public struct FileSessionLock: SessionLock {
    private let directory: URL
    private let timeout: Duration

    public init(directory: URL = AppGroup.container.appending(path: "Locks", directoryHint: .isDirectory), timeout: Duration = .seconds(15)) {
        self.directory = directory
        self.timeout = timeout
    }

    struct Handle: SessionLockHandle {
        let fd: Int32
        func release() {
            guard fd >= 0 else { return }
            flock(fd, LOCK_UN)
            close(fd)
        }
    }

    public func acquire(_ account: String) async -> SessionLockHandle {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let path = directory.appending(path: "session-\(account).lock").path
        let fd = open(path, O_RDWR | O_CREAT, 0o600)
        guard fd >= 0 else { return Handle(fd: -1) }
        let deadline = ContinuousClock.now + timeout
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            guard ContinuousClock.now < deadline, !Task.isCancelled else {
                close(fd)
                return Handle(fd: -1)
            }
            try? await Task.sleep(for: .milliseconds(40))
        }
        return Handle(fd: fd)
    }
}

/// Checks that an access token belongs to the account it is about to be sent for: issued by the account's Logto
/// tenant for the account's API resource. Tokens without those claims (opaque tokens) pass; the server checks again.
public enum TokenAudience {
    public static func matches(_ token: String, auth: Discovery.Auth) -> Bool {
        guard let claims = JWT.payload(token) else { return true }
        if let iss = claims["iss"] as? String, !auth.issuer.isEmpty, trimmed(iss) != trimmed(auth.issuer) { return false }
        let aud: [String] = (claims["aud"] as? String).map { [$0] } ?? (claims["aud"] as? [String]) ?? []
        if !aud.isEmpty, !auth.apiResource.isEmpty, !aud.contains(auth.apiResource) { return false }
        return true
    }

    private static func trimmed(_ s: String) -> String {
        var s = s
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
}
