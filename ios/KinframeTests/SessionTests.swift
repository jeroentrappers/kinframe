@testable import KinframeKit
import os
import XCTest

// Sessions with several accounts (docs/spec/multi-server.md §2.1): every account refreshes with its own refresh token
// at its own Logto tenant, refreshes of one account are serialized (in the app, and between the app and the share
// extension), a 401 refreshes once for everyone, and a token is never cached or sent for an account it wasn't issued
// for.

/// The Keychain, in memory (shared by the "app" and the "share extension" of a test).
final class MemoryVault: SessionVault, @unchecked Sendable {
    private let items = OSAllocatedUnfairLock(initialState: [String: String]())
    func string(_ name: String) -> String? { items.withLock { $0[name] } }
    func set(_ value: String?, _ name: String) { items.withLock { $0[name] = value } }
}

/// A Logto tenant per issuer: rotates refresh tokens on every use and refuses spent ones (like Logto with rotation on).
final class FakeTenants: TokenEndpoint, @unchecked Sendable {
    struct Call: Equatable {
        var issuer: String
        var clientId: String
        var refreshToken: String
    }

    private struct State {
        var valid: [String: Set<String>] = [:]
        var calls: [Call] = []
        var revoked: [String] = []
        var serial = 0
        var transient = false
        var audience: String?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    /// How long a refresh takes (so concurrent callers overlap).
    var delay: Duration = .milliseconds(80)

    var calls: [Call] { state.withLock { $0.calls } }
    var revoked: [String] { state.withLock { $0.revoked } }

    func grant(_ refreshToken: String, issuer: String) {
        state.withLock { $0.valid[issuer, default: []].insert(refreshToken) }
    }

    func failTransiently(_ on: Bool) { state.withLock { $0.transient = on } }
    /// Hand out access tokens for another API resource (a misconfigured or hostile tenant).
    func issueFor(audience: String?) { state.withLock { $0.audience = audience } }

    func refresh(_ refreshToken: String, auth: Discovery.Auth, endpoint: String) async throws -> TokenGrant {
        state.withLock { $0.calls.append(Call(issuer: auth.issuer, clientId: auth.mobileClientId, refreshToken: refreshToken)) }
        try? await Task.sleep(for: delay)
        return try state.withLock { s in
            if s.transient { throw TokenEndpointError.transient }
            guard s.valid[auth.issuer]?.remove(refreshToken) != nil else { throw TokenEndpointError.rejected }
            s.serial += 1
            let next = "\(refreshToken.split(separator: "#").first!)#\(s.serial)"
            s.valid[auth.issuer, default: []].insert(next)
            let access = SessionTests.jwt(iss: auth.issuer, aud: s.audience ?? auth.apiResource, n: s.serial)
            return TokenGrant(accessToken: access, refreshToken: next, idToken: nil, expiresIn: 3600)
        }
    }

    func revoke(_ refreshToken: String, auth: Discovery.Auth, endpoint: String) async {
        state.withLock {
            $0.revoked.append(refreshToken)
            $0.valid[auth.issuer]?.remove(refreshToken)
        }
    }
}

/// A lock shared by the "processes" of a test: one holder per account at a time.
final class FakeSessionLock: SessionLock, @unchecked Sendable {
    private let held = OSAllocatedUnfairLock(initialState: Set<String>())

    struct Handle: SessionLockHandle {
        let release_: @Sendable () -> Void
        func release() { release_() }
    }

    func acquire(_ account: String) async -> SessionLockHandle {
        while !held.withLock({ $0.insert(account).inserted }) {
            try? await Task.sleep(for: .milliseconds(5))
        }
        return Handle { [held] in _ = held.withLock { $0.remove(account) } }
    }
}

@MainActor
final class SessionTests: XCTestCase {
    /// A fresh defaults domain per test (the sign-in mode lives there).
    private let defaults = UserDefaults(suiteName: "kinframe.tests.sessions." + UUID().uuidString)!

    nonisolated static func jwt(iss: String, aud: String, n: Int, exp: Date = Date().addingTimeInterval(3600)) -> String {
        func b64(_ obj: [String: Any]) -> String {
            let data = try! JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
            return data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return b64(["alg": "none"]) + "." + b64(["iss": iss, "aud": aud, "exp": Int(exp.timeIntervalSince1970), "n": n]) + ".sig"
    }

    private func discovery(_ host: String, resource: String = "https://api.kinframe") throws -> Discovery {
        let json = """
        {"name":"k","api":"https://\(host)/v1","auth":{"issuer":"https://auth.\(host)/oidc","mobile_client_id":"app-\(host)",
         "api_resource":"\(resource)"}}
        """
        return try JSONDecoder().decode(Discovery.self, from: Data(json.utf8))
    }

    /// A signed-in Logto account: refresh token `rt-<account>`, no access token cached yet.
    private func account(_ id: String, host: String, vault: MemoryVault, tenants: FakeTenants, lock: SessionLock,
                         registry: RefreshRegistry = .shared, resource: String = "https://api.kinframe") throws -> AuthService {
        defaults.set("logto", forKey: "authMode.\(id)")
        if vault.string("logto.refresh_token.\(id)") == nil {
            vault.set("rt-\(id)", "logto.refresh_token.\(id)")
            vault.set("id-\(id)", "logto.id_token.\(id)")
            tenants.grant("rt-\(id)", issuer: "https://auth.\(host)/oidc")
        }
        let auth = AuthService(account: id, vault: vault, endpoint: tenants, lock: lock, defaults: defaults, refreshes: registry)
        auth.configure(try discovery(host, resource: resource))
        return auth
    }

    private func claims(_ token: String?) -> [String: Any] {
        token.flatMap(JWT.payload) ?? [:]
    }

    func testConcurrentRequestsOfOneAccountShareOneRefresh() async throws {
        let vault = MemoryVault(), tenants = FakeTenants()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: FakeSessionLock())
        async let t1 = a.accessToken()
        async let t2 = a.accessToken()
        async let t3 = a.accessToken()
        let tokens = try await [t1, t2, t3]
        XCTAssertEqual(tenants.calls.count, 1)
        XCTAssertEqual(Set(tokens).count, 1)
        // Cached now: no more refreshes.
        _ = try await a.accessToken()
        XCTAssertEqual(tenants.calls.count, 1)
    }

    func testTwoAccountsRefreshAtTheSameTimeEachWithItsOwnTokens() async throws {
        let vault = MemoryVault(), tenants = FakeTenants(), lock = FakeSessionLock()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: lock)
        let b = try account("a-2", host: "two.example", vault: vault, tenants: tenants, lock: lock)
        async let ta = a.accessToken()
        async let tb = b.accessToken()
        let (tokA, tokB) = try await (ta, tb)
        XCTAssertEqual(claims(tokA)["iss"] as? String, "https://auth.one.example/oidc")
        XCTAssertEqual(claims(tokB)["iss"] as? String, "https://auth.two.example/oidc")
        let calls = tenants.calls.sorted { $0.issuer < $1.issuer }
        XCTAssertEqual(calls, [
            .init(issuer: "https://auth.one.example/oidc", clientId: "app-one.example", refreshToken: "rt-a-1"),
            .init(issuer: "https://auth.two.example/oidc", clientId: "app-two.example", refreshToken: "rt-a-2"),
        ], "each account's refresh token goes to its own tenant with its own client id")
        XCTAssertEqual(vault.string("logto.refresh_token.a-1")?.hasPrefix("rt-a-1#"), true)
        XCTAssertEqual(vault.string("logto.refresh_token.a-2")?.hasPrefix("rt-a-2#"), true)
        XCTAssertNotEqual(tokA, tokB)
    }

    func testRotatedRefreshTokenIsKeptAndUsedNextTime() async throws {
        let vault = MemoryVault(), tenants = FakeTenants()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: FakeSessionLock())
        let first = try await a.accessToken()
        let rotated = vault.string("logto.refresh_token.a-1")
        XCTAssertNotEqual(rotated, "rt-a-1")
        let second = await a.refreshAfterUnauthorized(rejected: first)
        XCTAssertNotNil(second)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(tenants.calls.map(\.refreshToken), ["rt-a-1", rotated])
        XCTAssertTrue(a.isSignedIn)
    }

    func testAppAndShareExtensionRefreshingTogetherSpendTheTokenOnce() async throws {
        // Two processes: separate in-process registries, the same Keychain and the same file lock.
        let vault = MemoryVault(), tenants = FakeTenants(), lock = FakeSessionLock()
        let app = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: lock, registry: RefreshRegistry())
        let share = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: lock, registry: RefreshRegistry())
        var ended = 0
        app.onSignedOut = { ended += 1 }
        share.onSignedOut = { ended += 1 }
        async let t1 = app.accessToken()
        async let t2 = share.accessToken()
        let (x, y) = try await (t1, t2)
        XCTAssertEqual(tenants.calls.count, 1, "the second process found the first one's token under the lock")
        XCTAssertEqual(x, y)
        XCTAssertEqual(ended, 0, "no refresh with a spent token, so no sign-out")
        XCTAssertTrue(app.isSignedIn)
        XCTAssertTrue(share.isSignedIn)
    }

    func testShareExtensionRefreshedMeanwhileAppUsesThatToken() async throws {
        let vault = MemoryVault(), tenants = FakeTenants(), lock = FakeSessionLock()
        let app = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: lock, registry: RefreshRegistry())
        let share = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: lock, registry: RefreshRegistry())
        let old = try await app.accessToken()
        // The token expired on the server side; the extension got a 401 first and refreshed.
        let fresh = await share.refreshAfterUnauthorized(rejected: old)
        XCTAssertNotNil(fresh)
        // The app's request with the old token gets a 401 too: it takes the extension's token, no second refresh.
        let again = await app.refreshAfterUnauthorized(rejected: old)
        XCTAssertEqual(again, fresh)
        XCTAssertEqual(tenants.calls.count, 2)
    }

    func testConcurrentUnauthorizedRefreshOnce() async throws {
        let vault = MemoryVault(), tenants = FakeTenants()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: FakeSessionLock())
        let old = try await a.accessToken()
        async let r1 = a.refreshAfterUnauthorized(rejected: old)
        async let r2 = a.refreshAfterUnauthorized(rejected: old)
        async let r3 = a.refreshAfterUnauthorized(rejected: old)
        let fresh = await [r1, r2, r3]
        XCTAssertEqual(Set(fresh.compactMap { $0 }).count, 1)
        XCTAssertEqual(tenants.calls.count, 2, "one at sign-in time, one for all three 401s")
        // A late 401 with the old token takes the new one.
        let late = await a.refreshAfterUnauthorized(rejected: old)
        XCTAssertEqual(late, fresh[0])
        XCTAssertEqual(tenants.calls.count, 2)
    }

    func testRefusedRefreshTokenEndsOnlyThatSession() async throws {
        let vault = MemoryVault(), tenants = FakeTenants(), lock = FakeSessionLock()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: lock)
        let b = try account("a-2", host: "two.example", vault: vault, tenants: tenants, lock: lock)
        vault.set("revoked-elsewhere", "logto.refresh_token.a-1")
        var ended = 0
        a.onSignedOut = { ended += 1 }
        let token = try await a.accessToken()
        XCTAssertNil(token)
        XCTAssertEqual(ended, 1)
        XCTAssertFalse(a.isSignedIn)
        XCTAssertNil(vault.string("logto.refresh_token.a-1"))
        let other = try await b.accessToken()
        XCTAssertNotNil(other, "the other account carries on")
        XCTAssertTrue(b.isSignedIn)
    }

    func testNetworkTroubleKeepsTheSession() async throws {
        let vault = MemoryVault(), tenants = FakeTenants()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: FakeSessionLock())
        tenants.failTransiently(true)
        do {
            _ = try await a.accessToken()
            XCTFail("expected a network error")
        } catch let e as APIError {
            XCTAssertEqual(e.code, "network")
        }
        XCTAssertTrue(a.isSignedIn)
        XCTAssertEqual(vault.string("logto.refresh_token.a-1"), "rt-a-1")
        tenants.failTransiently(false)
        let token = try await a.accessToken()
        XCTAssertNotNil(token)
    }

    func testATokenForAnotherResourceIsNeverCachedOrSent() async throws {
        let vault = MemoryVault(), tenants = FakeTenants()
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: FakeSessionLock())
        tenants.issueFor(audience: "https://api.other-server")
        do {
            _ = try await a.accessToken()
            XCTFail("expected the token to be refused")
        } catch let e as APIError {
            XCTAssertEqual(e.code, "unauthorized")
        }
        XCTAssertNil(vault.string("accessToken.a-1"))
        XCTAssertTrue(a.isSignedIn, "the rotated refresh token is kept")
    }

    func testTokenAudience() {
        let auth = Discovery.Auth(issuer: "https://auth.one.example/oidc", mobileClientId: "x", apiResource: "https://api.kinframe")
        XCTAssertTrue(TokenAudience.matches(Self.jwt(iss: "https://auth.one.example/oidc", aud: "https://api.kinframe", n: 1), auth: auth))
        XCTAssertTrue(TokenAudience.matches(Self.jwt(iss: "https://auth.one.example/oidc/", aud: "https://api.kinframe", n: 1), auth: auth))
        XCTAssertFalse(TokenAudience.matches(Self.jwt(iss: "https://auth.two.example/oidc", aud: "https://api.kinframe", n: 1), auth: auth),
                       "same resource name, another tenant: another server's token")
        XCTAssertFalse(TokenAudience.matches(Self.jwt(iss: "https://auth.one.example/oidc", aud: "https://api.other", n: 1), auth: auth))
        XCTAssertTrue(TokenAudience.matches("opaque-token", auth: auth))
    }

    func testSignOutDuringARefreshWins() async throws {
        let vault = MemoryVault(), tenants = FakeTenants()
        tenants.delay = .milliseconds(200)
        let a = try account("a-1", host: "one.example", vault: vault, tenants: tenants, lock: FakeSessionLock())
        async let token = a.accessToken()
        try await Task.sleep(for: .milliseconds(50))
        await a.signOut()
        let t = try await token
        XCTAssertNil(t, "the refresh that started before doesn't bring the session back")
        XCTAssertFalse(a.isSignedIn)
        XCTAssertNil(vault.string("logto.refresh_token.a-1"))
        XCTAssertNil(vault.string("accessToken.a-1"))
        XCTAssertEqual(tenants.revoked, ["rt-a-1"])
    }

    func testDevSessionAfterUnauthorized() async throws {
        let vault = MemoryVault()
        let a = AuthService(account: "a-dev", vault: vault, endpoint: FakeTenants(), lock: FakeSessionLock(), defaults: defaults)
        var ended = 0
        a.onSignedOut = { ended += 1 }
        a.devSignIn(token: "dev-1")
        let t = try await a.accessToken()
        XCTAssertEqual(t, "dev-1")
        let after = await a.refreshAfterUnauthorized(rejected: "dev-1")
        XCTAssertNil(after)
        XCTAssertEqual(ended, 1)
        XCTAssertFalse(a.isSignedIn)
    }

    func testMiddlewarePassesTheRefusedToken() async throws {
        final class Recorder: TokenProvider, @unchecked Sendable {
            let rejected = OSAllocatedUnfairLock(initialState: [String?]())
            func accessToken() async throws -> String? { "old" }
            func refreshAfterUnauthorized() async -> String? { nil }
            func refreshAfterUnauthorized(rejected: String?) async -> String? {
                self.rejected.withLock { $0.append(rejected) }
                return "new"
            }
        }
        let tokens = Recorder()
        let seen = OSAllocatedUnfairLock(initialState: [String]())
        let session = StubURLProtocol.session { r in
            let auth = r.header("Authorization") ?? ""
            seen.withLock { $0.append(auth) }
            if auth == "Bearer old" { return .response(status: 401) }
            return .response(status: 200, headers: ["Content-Type": "application/json"], body: Data(#"{"total":0}"#.utf8))
        }
        let api = API(baseURL: URL(string: "https://one.example")!, tokens: tokens, session: session)
        _ = try? await api.badge()
        XCTAssertEqual(tokens.rejected.withLock { $0 }, ["old"])
        XCTAssertEqual(seen.withLock { $0 }, ["Bearer old", "Bearer new"])
    }

    func testTokenEndpointAnswers() throws {
        let ok = try LogtoTokenEndpoint.grant(status: 200, body: Data(#"{"access_token":"a","refresh_token":"r2","expires_in":3600}"#.utf8))
        XCTAssertEqual(ok, TokenGrant(accessToken: "a", refreshToken: "r2", idToken: nil, expiresIn: 3600))
        XCTAssertThrowsError(try LogtoTokenEndpoint.grant(status: 400, body: Data(#"{"error":"invalid_grant"}"#.utf8))) {
            XCTAssertEqual($0 as? TokenEndpointError, .rejected)
        }
        XCTAssertThrowsError(try LogtoTokenEndpoint.grant(status: 503, body: Data())) {
            XCTAssertEqual($0 as? TokenEndpointError, .transient)
        }
        XCTAssertThrowsError(try LogtoTokenEndpoint.grant(status: 400, body: Data(#"{"error":"invalid_request"}"#.utf8))) {
            XCTAssertEqual($0 as? TokenEndpointError, .transient)
        }
        let form = String(decoding: LogtoTokenEndpoint.form([("refresh_token", "a+b/c="), ("resource", "https://api.kinframe")]), as: UTF8.self)
        XCTAssertEqual(form, "refresh_token=a%2Bb%2Fc%3D&resource=https%3A%2F%2Fapi.kinframe")
    }

    func testRefreshCallsTheTenantsTokenEndpoint() async throws {
        let seen = OSAllocatedUnfairLock(initialState: [StubURLProtocol.Request]())
        let session = StubURLProtocol.session { r in
            seen.withLock { $0.append(r) }
            if r.url.path.hasSuffix("openid-configuration") {
                return .response(status: 200, body: Data(#"{"token_endpoint":"https://auth.one.example/oidc/token"}"#.utf8))
            }
            return .response(status: 200, body: Data(#"{"access_token":"a1","refresh_token":"r2"}"#.utf8))
        }
        let endpoint = LogtoTokenEndpoint(session: session)
        let auth = Discovery.Auth(issuer: "https://auth.one.example/oidc", mobileClientId: "cid", apiResource: "https://api.kinframe")
        let grant = try await endpoint.refresh("r1", auth: auth, endpoint: "https://auth.one.example")
        XCTAssertEqual(grant.refreshToken, "r2")
        let post = try XCTUnwrap(seen.withLock { $0 }.last)
        XCTAssertEqual(post.method, "POST")
        XCTAssertEqual(post.url.absoluteString, "https://auth.one.example/oidc/token")
        let body = String(decoding: post.body, as: UTF8.self)
        XCTAssertTrue(body.contains("grant_type=refresh_token"))
        XCTAssertTrue(body.contains("client_id=cid"))
        XCTAssertTrue(body.contains("refresh_token=r1"))
        XCTAssertTrue(body.contains("resource=https%3A%2F%2Fapi.kinframe"))
    }

    func testFileLockExcludesASecondHolder() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "locks-\(UUID().uuidString)")
        let lock = FileSessionLock(directory: dir, timeout: .seconds(5))
        let first = await lock.acquire("a-1")
        let order = OSAllocatedUnfairLock(initialState: [String]())
        let waiter = Task.detached {
            let second = await lock.acquire("a-1")
            order.withLock { $0.append("second") }
            second.release()
        }
        // Another account isn't held up.
        let other = await lock.acquire("a-2")
        other.release()
        try await Task.sleep(for: .milliseconds(150))
        order.withLock { $0.append("released") }
        first.release()
        await waiter.value
        XCTAssertEqual(order.withLock { $0 }, ["released", "second"])
        try? FileManager.default.removeItem(at: dir)
    }

    func testFileLockGivesUpAfterTheTimeout() async {
        let dir = FileManager.default.temporaryDirectory.appending(path: "locks-\(UUID().uuidString)")
        let held = await FileSessionLock(directory: dir).acquire("a-1")
        let start = ContinuousClock.now
        let second = await FileSessionLock(directory: dir, timeout: .milliseconds(200)).acquire("a-1")
        XCTAssertGreaterThanOrEqual(ContinuousClock.now - start, .milliseconds(150))
        second.release()
        held.release()
        try? FileManager.default.removeItem(at: dir)
    }
}
