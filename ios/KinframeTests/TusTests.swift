@testable import KinframeKit
import os
import XCTest

final class TusMachineTests: XCTestCase {
    let endpoint = URL(string: "https://k.example.org/v1/uploads")!

    func upload(size: Int64 = 10) -> TusUpload {
        TusUpload(file: "a.jpg", size: size, filename: "a.jpg", mime: "image/jpeg", sha256: "ab")
    }

    func testStepsFollowState() {
        var u = upload(size: 10)
        XCTAssertEqual(Tus.next(u, chunkSize: 4), .create(length: 4))
        u.location = URL(string: "https://k.example.org/v1/uploads/abc")
        u.offset = 4
        XCTAssertEqual(Tus.next(u, chunkSize: 4), .patch(offset: 4, length: 4))
        u.offset = 8
        XCTAssertEqual(Tus.next(u, chunkSize: 4), .patch(offset: 8, length: 2))
        u.needsHead = true
        XCTAssertEqual(Tus.next(u, chunkSize: 4), .head)
        u.needsHead = false
        u.offset = 10
        XCTAssertEqual(Tus.next(u, chunkSize: 4), .done)
        XCTAssertEqual(u.uploadId, "abc")
    }

    func testMetadataIsBase64Pairs() {
        let m = Tus.metadata(filename: "Oma.jpg", filetype: "image/jpeg", sha256: "ff")
        XCTAssertEqual(m, "filename T21hLmpwZw==,filetype aW1hZ2UvanBlZw==,sha256 ZmY=")
        XCTAssertFalse(Tus.metadata(filename: "a", filetype: "b", sha256: nil).contains("sha256"))
    }

    func testCreateRequestHeaders() throws {
        let r = try XCTUnwrap(Tus.request(for: .create(length: 4), upload: upload(), endpoint: endpoint))
        XCTAssertEqual(r.httpMethod, "POST")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Tus-Resumable"), "1.0.0")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Upload-Length"), "10")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Type"), "application/offset+octet-stream")
        XCTAssertNotNil(r.value(forHTTPHeaderField: "Upload-Metadata"))
    }

    func testCreateWithRelativeLocationAndOffset() throws {
        var u = upload()
        try Tus.apply(.create(length: 4), to: &u, status: 201, headers: ["Location": "/v1/uploads/xyz", "Upload-Offset": "4"], endpoint: endpoint)
        XCTAssertEqual(u.location?.absoluteString, "https://k.example.org/v1/uploads/xyz")
        XCTAssertEqual(u.offset, 4)
    }

    func testCreateReportsExistingItem() throws {
        var u = upload()
        try Tus.apply(.create(length: 4), to: &u, status: 201, headers: ["location": "/v1/uploads/xyz", "kinframe-existing-item": "item-1"], endpoint: endpoint)
        XCTAssertTrue(u.isComplete)
        XCTAssertEqual(u.uploadId, "item-1")
    }

    func testOffsetMismatchAsksServer() {
        var u = upload()
        u.location = URL(string: "https://k.example.org/v1/uploads/abc")
        XCTAssertThrowsError(try Tus.apply(.patch(offset: 0, length: 4), to: &u, status: 409, headers: [:], endpoint: endpoint)) {
            XCTAssertEqual($0 as? TusError, .retry)
        }
        XCTAssertTrue(u.needsHead)
        XCTAssertNoThrow(try Tus.apply(.head, to: &u, status: 200, headers: ["Upload-Offset": "6"], endpoint: endpoint))
        XCTAssertEqual(u.offset, 6)
        XCTAssertFalse(u.needsHead)
    }

    func testExpiredUploadStartsOver() {
        var u = upload()
        u.location = URL(string: "https://k.example.org/v1/uploads/abc")
        u.offset = 8
        XCTAssertThrowsError(try Tus.apply(.head, to: &u, status: 404, headers: [:], endpoint: endpoint))
        XCTAssertNil(u.location)
        XCTAssertEqual(u.offset, 0)
        XCTAssertEqual(Tus.next(u, chunkSize: 4), .create(length: 4))
    }

    func testTooLargeIsFinal() {
        var u = upload()
        XCTAssertThrowsError(try Tus.apply(.create(length: 4), to: &u, status: 413, headers: [:], endpoint: endpoint)) {
            XCTAssertEqual($0 as? TusError, .failed(APIError(status: 413, code: "upload_too_large")))
        }
    }

    func testInterruptedUploadNeedsHeadOnlyOnceCreated() {
        var u = upload()
        Tus.interrupted(&u)
        XCTAssertFalse(u.needsHead)
        u.location = URL(string: "https://k.example.org/v1/uploads/abc")
        Tus.interrupted(&u)
        XCTAssertTrue(u.needsHead)
    }
}

/// A tiny in-memory tus server plus `POST /v1/sends`, with scripted faults.
final class FakeServer: @unchecked Sendable {
    struct State {
        var uploads: [String: (length: Int64, data: Data)] = [:]
        var log: [String] = []
        var sends: [Data] = []
        var idempotencyKeys: [String] = []
        /// Drop the connection on the n-th PATCH after the server stored the bytes (a lost response).
        var dropPatchResponse: Int?
        var patchCount = 0
        var existingItem: String?
        var rejectToken: String?
        var authHeaders: [String] = []
    }

    let state = OSAllocatedUnfairLock(initialState: State())
    var log: [String] { state.withLock { $0.log } }

    lazy var session: URLSession = StubURLProtocol.session { [unowned self] in self.handle($0) }

    func handle(_ r: StubURLProtocol.Request) -> StubURLProtocol.Reply {
        state.withLock { s in
            let path = r.url.path
            s.log.append("\(r.method) \(path)")
            s.authHeaders.append(r.header("Authorization") ?? "")
            if let bad = s.rejectToken, r.header("Authorization") == "Bearer \(bad)" {
                return .response(status: 401)
            }
            switch (r.method, path) {
            case ("POST", "/v1/uploads"):
                if let existing = s.existingItem {
                    return .response(status: 201, headers: ["Location": "/v1/uploads/dup", "Kinframe-Existing-Item": existing, "Upload-Offset": "0"])
                }
                let id = "u\(s.uploads.count + 1)"
                let length = Int64(r.header("Upload-Length") ?? "0") ?? 0
                s.uploads[id] = (length, r.body)
                return .response(status: 201, headers: ["Location": "/v1/uploads/\(id)", "Upload-Offset": "\(r.body.count)", "Tus-Resumable": "1.0.0"])
            case ("HEAD", _) where path.hasPrefix("/v1/uploads/"):
                guard let u = s.uploads[r.url.lastPathComponent] else { return .response(status: 404) }
                return .response(status: 200, headers: ["Upload-Offset": "\(u.data.count)", "Upload-Length": "\(u.length)"])
            case ("PATCH", _) where path.hasPrefix("/v1/uploads/"):
                let id = r.url.lastPathComponent
                guard var u = s.uploads[id] else { return .response(status: 404) }
                guard Int(r.header("Upload-Offset") ?? "") == u.data.count else { return .response(status: 409) }
                u.data.append(r.body)
                s.uploads[id] = u
                s.patchCount += 1
                if s.dropPatchResponse == s.patchCount { return .failure(.networkConnectionLost) }
                return .response(status: 204, headers: ["Upload-Offset": "\(u.data.count)"])
            case ("POST", "/v1/sends"):
                s.sends.append(r.body)
                s.idempotencyKeys.append(r.header("Idempotency-Key") ?? "")
                let body = #"{"id":"send-1","created_at":"2026-10-22T13:05:00Z","items":[],"frames":[]}"#
                return .response(status: 202, headers: ["Content-Type": "application/json"], body: Data(body.utf8))
            default:
                return .response(status: 404)
            }
        }
    }
}

final class OutboxRunnerTests: XCTestCase {
    var root: URL!
    let server = URL(string: "https://k.example.org")!

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appending(path: "outbox-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func makeJob(store: OutboxStore, bytes: Int, captions: [String?] = [nil]) throws -> (OutboxJob, [Data]) {
        let id = UUID()
        let dir = try store.makeDirectory(for: id)
        var items: [OutboxItem] = []
        var datas: [Data] = []
        for (i, caption) in captions.enumerated() {
            let data = Data((0..<bytes).map { UInt8(($0 + i) % 251) })
            try data.write(to: dir.appending(path: "f\(i).jpg"))
            datas.append(data)
            items.append(OutboxItem(upload: TusUpload(file: "f\(i).jpg", size: Int64(bytes), filename: "f\(i).jpg", mime: "image/jpeg"),
                                    kind: .photo, caption: caption, fit: .fill, focal: .init(x: 0.5, y: 0.25)))
        }
        let job = OutboxJob(id: id, server: server, frameIds: ["frame-1"], frameNames: ["Oma"], items: items)
        try store.save(job)
        return (job, datas)
    }

    func runner(_ fake: FakeServer, store: OutboxStore, tokens: StubTokens = StubTokens(), owner: String = "test") async -> OutboxRunner {
        let runner = OutboxRunner(store: store, transport: URLSessionUploadTransport(session: fake.session), owner: owner, chunkSize: 1024, sleep: { _ in })
        await runner.configure(server: server, tokens: tokens)
        return runner
    }

    func testUploadsInChunksThenCreatesSend() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        let (job, datas) = try makeJob(store: store, bytes: 2500, captions: ["  Hallo oma  "])
        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)

        XCTAssertEqual(fake.log, ["POST /v1/uploads", "PATCH /v1/uploads/u1", "PATCH /v1/uploads/u1", "POST /v1/sends"])
        let stored = fake.state.withLock { $0.uploads["u1"]?.data }
        XCTAssertEqual(stored, datas[0])
        let saved = try XCTUnwrap(store.load(job.id))
        XCTAssertEqual(saved.state, .sent(sendId: "send-1"))

        let body = try XCTUnwrap(fake.state.withLock { $0.sends.first })
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["frame_ids"] as? [String], ["frame-1"])
        let item = try XCTUnwrap((json["items"] as? [[String: Any]])?.first)
        XCTAssertEqual(item["upload_id"] as? String, "u1")
        XCTAssertEqual(item["caption"] as? String, "Hallo oma")
        XCTAssertEqual(item["fit"] as? String, "fill")
        XCTAssertEqual(fake.state.withLock { $0.idempotencyKeys }, [job.idempotencyKey])
        XCTAssertTrue(fake.state.withLock { $0.authHeaders.allSatisfy { $0 == "Bearer t1" } })
    }

    func testLostResponseResumesWithHead() async throws {
        let fake = FakeServer()
        fake.state.withLock { $0.dropPatchResponse = 1 }
        let store = OutboxStore(root: root)
        let (job, datas) = try makeJob(store: store, bytes: 3000)
        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)

        // The first PATCH reached the server but its response was lost: ask with HEAD, then continue from there.
        XCTAssertEqual(fake.log, ["POST /v1/uploads", "PATCH /v1/uploads/u1", "HEAD /v1/uploads/u1", "PATCH /v1/uploads/u1", "POST /v1/sends"])
        XCTAssertEqual(fake.state.withLock { $0.uploads["u1"]?.data }, datas[0])
        XCTAssertEqual(store.load(job.id)?.state, .sent(sendId: "send-1"))
    }

    func testResumesJobLeftByAnotherProcess() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        var (job, datas) = try makeJob(store: store, bytes: 2048)
        // An earlier process created the upload and sent the first KiB, then died mid-request.
        let firstKiB = datas[0].prefix(1024)
        fake.state.withLock { $0.uploads["u1"] = (2048, firstKiB) }
        job.items[0].upload.location = server.appending(path: "v1/uploads/u1")
        job.items[0].upload.offset = 0
        job.items[0].upload.needsHead = true
        job.lease = .init(owner: "share-extension", until: Date().addingTimeInterval(-1))
        try store.save(job)

        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)

        XCTAssertEqual(fake.log, ["HEAD /v1/uploads/u1", "PATCH /v1/uploads/u1", "POST /v1/sends"])
        XCTAssertEqual(fake.state.withLock { $0.uploads["u1"]?.data }, datas[0])
    }

    func testLeasedJobIsLeftAlone() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        var (job, _) = try makeJob(store: store, bytes: 100)
        job.lease = .init(owner: "share-extension", until: Date().addingTimeInterval(60))
        try store.save(job)
        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)
        XCTAssertEqual(fake.log, [])
    }

    func testDuplicateSkipsUpload() async throws {
        let fake = FakeServer()
        fake.state.withLock { $0.existingItem = "item-9" }
        let store = OutboxStore(root: root)
        let (job, _) = try makeJob(store: store, bytes: 5000)
        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)
        XCTAssertEqual(fake.log, ["POST /v1/uploads", "POST /v1/sends"])
        let body = try XCTUnwrap(fake.state.withLock { $0.sends.first })
        XCTAssertTrue(String(decoding: body, as: UTF8.self).contains(#""upload_id":"item-9""#))
    }

    func testRefreshesTokenOnceAfter401() async throws {
        let fake = FakeServer()
        fake.state.withLock { $0.rejectToken = "t1" }
        let store = OutboxStore(root: root)
        let tokens = StubTokens()
        let (job, _) = try makeJob(store: store, bytes: 500)
        let runner = await runner(fake, store: store, tokens: tokens)
        await runner.kick()
        await runner.wait(job.id)
        XCTAssertEqual(tokens.refreshes, 1)
        XCTAssertEqual(store.load(job.id)?.state, .sent(sendId: "send-1"))
    }

    func testSeveralItemsUploadInParallelAndKeepOrder() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        let (job, _) = try makeJob(store: store, bytes: 700, captions: ["a", "b", "c", "d"])
        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)
        let body = try XCTUnwrap(fake.state.withLock { $0.sends.first })
        let items = try XCTUnwrap((JSONSerialization.jsonObject(with: body) as? [String: Any])?["items"] as? [[String: Any]])
        XCTAssertEqual(items.map { $0["caption"] as? String }, ["a", "b", "c", "d"])
        XCTAssertEqual(Set(items.compactMap { $0["upload_id"] as? String }).count, 4)
    }
}
