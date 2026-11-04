@testable import Kinframe
@testable import KinframeKit
import os
import XCTest

/// Records requests and answers from a route table.
final class FakeAPI: @unchecked Sendable {
    typealias Route = (StubURLProtocol.Request) -> StubURLProtocol.Reply?
    private let state = OSAllocatedUnfairLock(initialState: [StubURLProtocol.Request]())
    private let routes: [Route]
    var requests: [StubURLProtocol.Request] { state.withLock { $0 } }

    init(_ routes: [Route]) {
        self.routes = routes
    }

    lazy var api: API = API(baseURL: URL(string: "https://k.example.org")!, tokens: StubTokens(), session: StubURLProtocol.session { [unowned self] r in
        self.state.withLock { $0.append(r) }
        for route in self.routes {
            if let reply = route(r) { return reply }
        }
        return .response(status: 404, headers: ["Content-Type": "application/problem+json"], body: Data(#"{"type":"x","title":"Not found","status":404,"code":"not_found"}"#.utf8))
    })

    static func json(_ s: String, status: Int = 200) -> StubURLProtocol.Reply {
        .response(status: status, headers: ["Content-Type": status >= 400 ? "application/problem+json" : "application/json"], body: Data(s.utf8))
    }

    static func on(_ method: String, _ path: String, _ reply: @escaping (StubURLProtocol.Request) -> StubURLProtocol.Reply) -> Route {
        { r in r.method == method && r.url.path == path ? reply(r) : nil }
    }
}

let frameJSON = #"{"id":"f1","name":"Oma’s frame","location":"Living room","owner_name":"Jeroen","role":"owner","permissions":{"send":true},"online":true,"last_seen_at":"2026-10-22T13:05:00Z"}"#

@MainActor
final class AddFrameModelTests: XCTestCase {
    func testPasteFormatsAndRedeems() async throws {
        let fake = FakeAPI([FakeAPI.on("POST", "/v1/frames/redeem") { _ in FakeAPI.json(#"{"status":"claimed","frame":\#(frameJSON)}"#) }])
        let model = AddFrameModel(api: fake.api)
        model.setCode("Code: 4821-39")
        XCTAssertEqual(model.formatted, "4821 39")
        XCTAssertFalse(model.canSubmit)
        model.setCode("4821 3907 trailing")
        XCTAssertEqual(model.formatted, "4821 3907")
        XCTAssertTrue(model.canSubmit)

        let result = await model.redeem()
        XCTAssertEqual(result?.status, .claimed)
        XCTAssertEqual(model.name, "Oma’s frame")
        XCTAssertEqual(model.location, "Living room")
        let req = try XCTUnwrap(fake.requests.first)
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: req.body), .object(["code": .string("48213907")]))
        XCTAssertNotNil(req.header("Idempotency-Key"))
        XCTAssertEqual(req.header("Authorization"), "Bearer t1")
    }

    func testWrongCodeShowsProblemAndAllowsRetryWithNewKey() async throws {
        let fake = FakeAPI([FakeAPI.on("POST", "/v1/frames/redeem") { _ in
            FakeAPI.json(#"{"type":"x","title":"Invalid","status":404,"code":"code_invalid"}"#, status: 404)
        }])
        L10n.setLanguage(.en)
        let model = AddFrameModel(api: fake.api, prefill: "11112222")
        let first = await model.redeem()
        XCTAssertNil(first)
        XCTAssertEqual(model.error, L("problem.code_invalid"))
        XCTAssertEqual(model.state, .entering)
        _ = await model.redeem()
        let keys = fake.requests.compactMap { $0.header("Idempotency-Key") }
        XCTAssertEqual(keys.count, 2)
        XCTAssertNotEqual(keys[0], keys[1], "a failed redeem gets a fresh idempotency key")
    }

    func testPendingResult() async {
        let fake = FakeAPI([FakeAPI.on("POST", "/v1/frames/redeem") { _ in FakeAPI.json(#"{"status":"pending","frame":\#(frameJSON),"join_request_id":"r1"}"#) }])
        let model = AddFrameModel(api: fake.api, prefill: "48213907")
        let result = await model.redeem()
        XCTAssertEqual(result?.status, .pending)
    }
}

@MainActor
final class HistoryModelTests: XCTestCase {
    static func send(_ id: String, created: String, reaction: String = "null") -> String {
        #"{"id":"\#(id)","created_at":"\#(created)","items":[{"id":"i-\#(id)","kind":"photo","status":"ready","sender_id":"u1","created_at":"\#(created)"}],"frames":[{"frame_id":"f1","frame_name":"Oma","online":true,"placements":[{"placement_id":"p-\#(id)","item_id":"i-\#(id)","status":"delivered","reaction":\#(reaction)}]}]}"#
    }

    func testLoadGroupsAndLiveUpdates() async throws {
        let today = ISO8601DateFormatter().string(from: Date())
        let older = "2026-01-02T10:00:00Z"
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/sends") { _ in FakeAPI.json(#"{"sends":[\#(Self.send("s1", created: today)),\#(Self.send("s2", created: older))],"next_cursor":null}"#) },
            FakeAPI.on("GET", "/v1/sends/s1") { _ in FakeAPI.json(Self.send("s1", created: today, reaction: #""wow""#)) },
        ])
        let model = HistoryModel(api: fake.api, debounce: .zero)
        await model.load()
        XCTAssertEqual(model.sends.map(\.id), ["s1", "s2"])
        XCTAssertEqual(model.groups.count, 2)
        XCTAssertEqual(model.groups.first?.bucket, .today)
        XCTAssertNil(model.cursor)

        model.apply(.reaction(placementId: "p-s2", frameId: "f1", reaction: .love, byFrameName: "Oma"))
        XCTAssertEqual(model.sends[1].frames[0].placements[0].reaction, .love)
        model.apply(.reply(placementId: "p-s2", frameId: "f1", preset: .missYou))
        XCTAssertEqual(model.sends[1].frames[0].placements[0].replies, [.missYou])
        model.apply(.frameStatus(frameId: "f1", online: false, lastSeenAt: nil))
        XCTAssertEqual(model.sends[0].frames[0].online, false)

        model.apply(.sendUpdated(sendId: "s1"))
        for _ in 0..<50 where model.sends[0].frames[0].placements[0].reaction != .wow {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(model.sends[0].frames[0].placements[0].reaction, .wow)
    }

    func testFilterIsSentToServer() async {
        let fake = FakeAPI([FakeAPI.on("GET", "/v1/sends") { _ in FakeAPI.json(#"{"sends":[],"next_cursor":null}"#) }])
        let model = HistoryModel(api: fake.api)
        model.filter = .ongoing
        await model.load()
        XCTAssertTrue(fake.requests.first?.url.query?.contains("state=ongoing") == true)
        XCTAssertTrue(model.groups.isEmpty)
    }
}

@MainActor
final class FrameSettingsModelTests: XCTestCase {
    static let settings = #"{"version":3,"slideshow":{"interval_s":30,"order":"received"},"display":{"brightness":204,"sleep_schedules":[]}}"#

    func testSavesOnlyChangesAsMergePatchWithIfMatch() async throws {
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/frames/f1/settings") { _ in FakeAPI.json(Self.settings) },
            FakeAPI.on("PATCH", "/v1/frames/f1/settings") { _ in FakeAPI.json(#"{"version":4,"slideshow":{"interval_s":60,"order":"received"},"display":{"brightness":204,"sleep_schedules":[]}}"#) },
        ])
        let model = FrameSettingsModel(api: fake.api, frameId: "f1")
        await model.load()
        XCTAssertFalse(model.dirty)
        model.int("slideshow.interval_s", 30).wrappedValue = 60
        model.bool("widgets.weather", false).wrappedValue = false
        XCTAssertTrue(model.dirty)
        let result = try await model.save()
        XCTAssertEqual(result, .saved)
        let patch = try XCTUnwrap(fake.requests.last)
        XCTAssertEqual(patch.header("If-Match"), "\"3\"")
        XCTAssertTrue(patch.header("Content-Type")?.hasPrefix("application/merge-patch+json") == true)
        let body = try JSONDecoder().decode(JSONValue.self, from: patch.body)
        XCTAssertEqual(body, .object(["slideshow": .object(["interval_s": .number(60)]), "widgets": .object(["weather": .bool(false)])]))
        XCTAssertEqual(model.version, 4)
        XCTAssertFalse(model.dirty)
    }

    func testConflictOnOtherFieldsIsRetried() async throws {
        let calls = OSAllocatedUnfairLock(initialState: 0)
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/frames/f1/settings") { _ in
                calls.withLock { $0 } == 0
                    ? FakeAPI.json(Self.settings)
                    : FakeAPI.json(#"{"version":5,"slideshow":{"interval_s":30,"order":"received"},"display":{"brightness":100,"sleep_schedules":[]}}"#)
            },
            FakeAPI.on("PATCH", "/v1/frames/f1/settings") { r in
                let n = calls.withLock { $0 += 1; return $0 }
                if n == 1 { return FakeAPI.json(#"{"type":"x","title":"Conflict","status":412,"code":"version_conflict"}"#, status: 412) }
                XCTAssertEqual(r.header("If-Match"), "\"5\"")
                return FakeAPI.json(#"{"version":6,"slideshow":{"interval_s":60,"order":"received"},"display":{"brightness":100,"sleep_schedules":[]}}"#)
            },
        ])
        let model = FrameSettingsModel(api: fake.api, frameId: "f1")
        await model.load()
        model.int("slideshow.interval_s", 30).wrappedValue = 60
        let result = try await model.save()
        XCTAssertEqual(result, .merged)
        XCTAssertEqual(model.version, 6)
    }

    func testConflictOnSameFieldAsksForReview() async throws {
        let calls = OSAllocatedUnfairLock(initialState: 0)
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/frames/f1/settings") { _ in
                calls.withLock { $0 } == 0
                    ? FakeAPI.json(Self.settings)
                    : FakeAPI.json(#"{"version":5,"slideshow":{"interval_s":10,"order":"received"},"display":{"brightness":204,"sleep_schedules":[]}}"#)
            },
            FakeAPI.on("PATCH", "/v1/frames/f1/settings") { _ in
                calls.withLock { $0 += 1 }
                return FakeAPI.json(#"{"type":"x","title":"Conflict","status":412,"code":"version_conflict"}"#, status: 412)
            },
        ])
        let model = FrameSettingsModel(api: fake.api, frameId: "f1")
        await model.load()
        model.int("slideshow.interval_s", 30).wrappedValue = 60
        let result = try await model.save()
        XCTAssertEqual(result, .review)
        XCTAssertTrue(model.conflict)
        XCTAssertEqual(model.version, 5)
        XCTAssertEqual(model.int("slideshow.interval_s", 30).wrappedValue, 60, "our edit stays on top of theirs")
        XCTAssertTrue(model.dirty)
    }

    func testLocationAndSchedules() async {
        let fake = FakeAPI([FakeAPI.on("GET", "/v1/frames/f1/settings") { _ in FakeAPI.json(Self.settings) }])
        let model = FrameSettingsModel(api: fake.api, frameId: "f1")
        await model.load()
        model.setLocation(Place(name: "Gent", admin1: "Flanders", countryCode: "BE", lat: 51.05, lon: 3.72))
        XCTAssertEqual(model.location?.name, "Gent, Flanders, BE")
        model.schedules = [SleepSchedule(id: "s1", sleepAt: "22:00", wakeAt: "07:30", days: [.sat, .sun], enabled: true)]
        XCTAssertEqual(model.schedules.first?.wakeAt, "07:30")
        model.setLocation(nil)
        XCTAssertNil(model.location)
        XCTAssertEqual(model.patch["location"], .null)
    }
}

@MainActor
final class ComposeModelTests: XCTestCase {
    var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appending(path: "compose-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    func file(_ name: String, bytes: Int = 100) -> URL {
        let url = dir.appending(path: name)
        try? Data(repeating: 7, count: bytes).write(to: url)
        return url
    }

    func testLimitsAndValidation() async {
        let model = ComposeModel(limits: Limits(maxItemsPerSend: 2, maxVideoSeconds: 60, maxUploadBytes: 150), staging: dir.appending(path: "staging"))
        let result = await model.add(files: [(file("a.jpg"), "a.jpg"), (file("doc.pdf"), "doc.pdf"), (file("b.heic", bytes: 200), "b.heic"), (file("c.png"), "c.png")])
        XCTAssertEqual(result, .init(added: 2, unsupported: 1, overLimit: 1))
        XCTAssertEqual(model.drafts.map(\.filename), ["a.jpg", "b.heic"])
        XCTAssertEqual(model.issueCount, 1, "b.heic is over the upload limit")
        XCTAssertEqual(model.blocker, .issues(1))
        model.remove(model.drafts[1].id)
        XCTAssertEqual(model.blocker, .noFrames)
        model.toggle(frame: "f1")
        XCTAssertNil(model.blocker)
        model.applyCaptionToAll(String(repeating: "x", count: 281))
        XCTAssertEqual(model.blocker, .captionTooLong)
    }

    func testCaptionsAndJob() async throws {
        let model = ComposeModel(limits: .defaults, staging: dir.appending(path: "staging"))
        await model.add(files: [(file("a.jpg"), "a.jpg"), (file("b.jpg"), "b.jpg")])
        model.applyCaptionToAll("Hallo")
        model.update(model.drafts[1].id) { $0.caption = "Eigen"; $0.fit = .fit; $0.focal = CGPoint(x: 0.2, y: 0.81234) }
        XCTAssertEqual(model.drafts.map { model.caption(of: $0) }, ["Hallo", "Eigen"])
        model.frameIds = ["f1"]
        let store = OutboxStore(root: dir.appending(path: "outbox"))
        let frame = try OutboxRunner.decoder.decode(FrameSummary.self, from: Data(frameJSON.utf8))
        let job = try model.makeJob(server: URL(string: "https://k.example.org")!, frames: [frame], store: store)
        XCTAssertEqual(job.frameNames, ["Oma’s frame"])
        XCTAssertEqual(job.items.map(\.caption), ["Hallo", "Eigen"])
        XCTAssertEqual(job.items[1].fit, .fit)
        XCTAssertEqual(job.items[1].focal?.y, 0.812)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.directory(job.id).appending(path: job.items[0].upload.file).path))
        XCTAssertTrue(model.drafts.isEmpty)
    }
}

@MainActor
final class ServerChoiceModelTests: XCTestCase {
    func testCustomServerValidation() async {
        let session = StubURLProtocol.session { r in
            r.url.host == "frames.example.org"
                ? .response(status: 200, body: Data(#"{"name":"Trappers family","version":"0.3.0","api":"https://frames.example.org/v1"}"#.utf8))
                : .failure(.cannotFindHost)
        }
        L10n.setLanguage(.en)
        let model = ServerChoiceModel(session: session)
        model.choice = .custom
        model.address = "not a server"
        await model.checkCustom()
        XCTAssertEqual(model.check, .failed(L("ios.server.invalid")))
        model.address = "nowhere.example.org"
        await model.checkCustom()
        XCTAssertEqual(model.check, .failed(L("ios.server.unreachable")))
        model.address = "Frames.Example.org/"
        await model.checkCustom()
        XCTAssertEqual(model.found?.0.absoluteString, "https://frames.example.org")
        XCTAssertEqual(model.found?.1.name, "Trappers family")
        model.address = "other.example.org"
        model.addressChanged()
        XCTAssertEqual(model.check, .idle)
    }
}
