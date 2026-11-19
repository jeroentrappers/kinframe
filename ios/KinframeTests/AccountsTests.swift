@testable import KinframeKit
import XCTest

// Several accounts (docs/spec/multi-server.md §2, README §5.22): the account list and its migration from a
// single-server install, matching servers (push `server`, links, `server_id`), merging lists by time, and one job per
// server for a send to several servers.
final class AccountsTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "kinframe.tests.accounts"

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    private func discovery(_ extra: String = "") throws -> Discovery {
        try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://kinframe.appmire.be/v1"\#(extra)}"#.utf8))
    }

    private func config(_ url: String, _ extra: String = "") throws -> ServerConfig {
        ServerConfig(url: URL(string: url)!, discovery: try discovery(extra))
    }

    func testDiscoveryServerFields() throws {
        let d = try discovery(#","server_id":"srv_1","server_name":"Janssens","base_url":"https://kinframe.janssens.be","leaving":true"#)
        XCTAssertEqual(d.serverId, "srv_1")
        XCTAssertEqual(d.serverName, "Janssens")
        XCTAssertEqual(d.baseURL, "https://kinframe.janssens.be")
        XCTAssertTrue(d.leaving)
        let old = try discovery(#","server_name":"""#)
        XCTAssertNil(old.serverName, "an empty label falls back to the host")
        XCTAssertNil(old.serverId)
    }

    func testMigratesTheSingleServerInstall() throws {
        let server = try config("https://kinframe.appmire.be")
        defaults.set(try JSONEncoder().encode(server), forKey: "server")
        let list = AccountStore.load(defaults)
        XCTAssertEqual(list.count, 1)
        XCTAssertEqual(list[0].id, AccountRecord.legacyId, "the existing session keeps its Keychain names: no new sign-in")
        XCTAssertEqual(list[0].server, server)
        // Stored now, and the old key still names the primary server for an older share extension.
        XCTAssertNotNil(defaults.data(forKey: "accounts"))
        XCTAssertEqual(AccountStore.load(defaults), list)
    }

    func testEmptyInstallHasNoAccounts() {
        XCTAssertTrue(AccountStore.load(defaults).isEmpty)
    }

    func testUpsertPrimaryAndRemove() throws {
        let a = AccountRecord(id: "a", server: try config("https://kinframe.appmire.be"))
        let b = AccountRecord(id: "b", server: try config("https://kinframe.janssens.be"))
        AccountStore.upsert(a, defaults)
        AccountStore.upsert(b, defaults)
        XCTAssertEqual(AccountStore.load(defaults).map(\.id), ["a", "b"])
        AccountStore.makePrimary("b", defaults)
        XCTAssertEqual(AccountStore.load(defaults).map(\.id), ["b", "a"])
        let legacy = try JSONDecoder().decode(ServerConfig.self, from: defaults.data(forKey: "server")!)
        XCTAssertEqual(legacy.url, b.server.url, "the old key follows the primary account")
        AccountStore.remove("b", defaults)
        XCTAssertEqual(AccountStore.load(defaults).map(\.id), ["a"])
    }

    func testKeysOfTheLegacyAccountStayTheSame() {
        XCTAssertEqual(AuthService.name("accessToken", AccountRecord.legacyId), "accessToken")
        XCTAssertEqual(AuthService.name("accessToken", "a-123"), "accessToken.a-123")
        XCTAssertNotEqual(AccountRecord.newId(), AccountRecord.newId())
    }

    func testServersMatchByHostPortAndBaseURL() throws {
        let r = AccountRecord(id: "a", server: try config("https://kinframe.janssens.be", #","base_url":"https://photos.janssens.be""#))
        XCTAssertTrue(AccountServers.matches(r, URL(string: "https://KINFRAME.janssens.be/")!))
        XCTAssertTrue(AccountServers.matches(r, URL(string: "https://photos.janssens.be")!), "discovery base_url")
        XCTAssertTrue(AccountServers.matches(r, URL(string: "https://kinframe.appmire.be")!), "the API base from discovery")
        XCTAssertFalse(AccountServers.matches(r, URL(string: "https://kinframe.janssens.be:8443")!))
        XCTAssertFalse(AccountServers.matches(r, URL(string: "https://other.example")!))
    }

    func testPushPicksTheAccountByServer() throws {
        let a = AccountRecord(id: "a", server: try config("https://kinframe.appmire.be"))
        let b = AccountRecord(id: "b", server: ServerConfig(url: URL(string: "https://kinframe.janssens.be")!,
                                                            discovery: try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"j","api":"https://kinframe.janssens.be/v1"}"#.utf8))))
        let records = [a, b]
        XCTAssertEqual(AccountServers.account(forPush: ["server": "https://kinframe.janssens.be"], in: records, fallback: "a")?.id, "b")
        XCTAssertEqual(AccountServers.account(forPush: ["key": "message"], in: records, fallback: "b")?.id, "b",
                       "older servers send no `server`: the endpoint's account")
        XCTAssertEqual(AccountServers.account(forPush: ["server": "https://unknown.example"], in: records, fallback: nil)?.id, "a")
    }

    func testDeduplicatesByServerId() throws {
        let a = AccountRecord(id: "a", server: try config("https://kinframe.janssens.be", #","server_id":"srv_1""#))
        let sameServer = try discovery(#","server_id":"srv_1""#)
        XCTAssertEqual(AccountStore.existing(URL(string: "https://photos.janssens.be")!, discovery: sameServer, in: [a])?.id, "a")
        XCTAssertEqual(AccountStore.existing(URL(string: "https://kinframe.janssens.be")!, discovery: nil, in: [a])?.id, "a")
        XCTAssertNil(AccountStore.existing(URL(string: "https://other.example")!, discovery: try discovery(#","server_id":"srv_2""#), in: [a]))
    }

    func testInterleaveKeepsEachListsOrder() {
        let d = { (s: Double) in Date(timeIntervalSince1970: s) }
        let a = [("a1", d(10)), ("a2", d(5)), ("a3", d(1))]
        let b = [("b1", d(8)), ("b2", d(5)), ("b3", d(0))]
        let merged = AccountMerge.interleave([a, b]) { $0.1 }.map(\.0)
        XCTAssertEqual(merged, ["a1", "b1", "a2", "b2", "a3", "b3"])
        XCTAssertEqual(AccountMerge.interleave([a]) { $0.1 }.map(\.0), ["a1", "a2", "a3"])
        XCTAssertTrue(AccountMerge.interleave([[(String, Date)]]()) { $0.1 }.isEmpty)
    }

    func testSafeCutoff() {
        let d = { (s: Double) in Date(timeIntervalSince1970: s) }
        XCTAssertEqual(AccountMerge.safeCutoff(oldest: [d(5), d(9)], hasMore: [true, true]), d(9))
        XCTAssertEqual(AccountMerge.safeCutoff(oldest: [d(5), d(9)], hasMore: [true, false]), d(5))
        XCTAssertNil(AccountMerge.safeCutoff(oldest: [d(5)], hasMore: [false]))
    }

    @MainActor
    func testOneJobPerServerAndTheFirstPrepares() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "acc-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = OutboxStore(root: dir.appending(path: "outbox"))
        let model = ComposeModel(limits: .defaults, staging: dir.appending(path: "staging"))
        let file = dir.appending(path: "a.jpg")
        try Data(repeating: 1, count: 100).write(to: file)
        _ = await model.add(files: [(file, "a.jpg")])
        XCTAssertEqual(model.drafts.count, 1)
        let frameA = FrameSummary(id: "fa", name: "Oma", role: .owner, permissions: Permissions(send: true), online: true)
        let frameB = FrameSummary(id: "fb", name: "Opa", role: .owner, permissions: Permissions(send: true), online: true)
        model.frameIds = ["fa", "fb"]
        let a = URL(string: "https://kinframe.appmire.be")!, b = URL(string: "https://kinframe.janssens.be")!
        let jobs = try model.makeJobs([.init(server: a, serverName: "Appmire", frames: [frameA], feeds: []),
                                       .init(server: b, serverName: "Janssens", frames: [frameB], feeds: []),
                                       .init(server: URL(string: "https://unused.example")!, frames: [], feeds: [])], store: store)
        XCTAssertEqual(jobs.count, 2, "servers without chosen recipients get no job")
        XCTAssertEqual(jobs.map(\.server), [a, b])
        XCTAssertEqual(jobs.map(\.frameIds), [["fa"], ["fb"]])
        XCTAssertNil(jobs[0].preparedBy)
        XCTAssertEqual(jobs[1].preparedBy, jobs[0].id)
        XCTAssertEqual(jobs.map(\.serverName), ["Appmire", "Janssens"])
        for j in jobs {
            XCTAssertTrue(FileManager.default.fileExists(atPath: store.directory(j.id).appending(path: j.items[0].upload.file).path),
                          "each job has its own copy of the file")
        }
        XCTAssertTrue(model.drafts.isEmpty)
    }
}
