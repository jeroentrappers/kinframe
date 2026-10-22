import Foundation

public enum OutboxEvent: Sendable {
    /// The job changed (progress, state).
    case updated(OutboxJob)
    /// The server accepted the send.
    case sent(OutboxJob, Send)
    case removed(UUID)
}

/// Drives queued sends: uploads each file with tus (3 at a time, 4 MiB chunks), then creates the send with
/// `POST /v1/sends` and a stable `Idempotency-Key`. Every step is persisted, so a job resumes after the app is
/// suspended, killed or woken for background URLSession events, and the share extension can start a job that the
/// app finishes.
public actor OutboxRunner {
    public static let parallelUploads = 3
    static let maxAttempts = 6
    static let leaseDuration: TimeInterval = 90

    public let store: OutboxStore
    let transport: UploadTransport
    let owner: String
    let chunkSize: Int64
    let sleep: @Sendable (TimeInterval) async throws -> Void

    private var server: URL?
    private var tokens: TokenProvider?
    private var running: [UUID: Task<Void, Never>] = [:]

    public nonisolated let events: AsyncStream<OutboxEvent>
    private let emit: AsyncStream<OutboxEvent>.Continuation

    public init(store: OutboxStore, transport: UploadTransport, owner: String = "app-\(UUID().uuidString)",
                chunkSize: Int64 = Tus.chunkSize,
                sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(for: .seconds($0)) }) {
        self.store = store
        self.transport = transport
        self.owner = owner
        self.chunkSize = chunkSize
        self.sleep = sleep
        (events, emit) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(256))
    }

    /// The signed-in session jobs run with. Jobs for another server wait until it's current again.
    public func configure(server: URL?, tokens: TokenProvider?) {
        self.server = server
        self.tokens = tokens
    }

    /// Start every job that isn't finished, running, waiting on an in-flight background request or driven by the
    /// other process.
    public func kick() async {
        let inFlight = await transport.inFlight()
        for var job in store.all() {
            if case .sent = job.state {
                store.remove(job.id)
                continue
            }
            // Network failures retry on their own the next time the queue is kicked.
            if job.state == .failed(code: "network") {
                job = store.update(job.id) { $0.state = $0.items.allSatisfy(\.upload.isComplete) ? .sending : .uploading; $0.attempts = 0 } ?? job
            }
            if job.isFinished || running[job.id] != nil { continue }
            if inFlight.contains(where: { $0.job == job.id }) { continue }
            if let lease = job.lease, lease.owner != owner, lease.until > Date() { continue }
            start(job.id)
        }
    }

    /// Wait until a job has finished (used by tests and by the share extension's hand-off).
    public func wait(_ id: UUID) async {
        await running[id]?.value
    }

    /// Stop and delete a job.
    public func cancel(_ id: UUID) {
        running.removeValue(forKey: id)?.cancel()
        store.remove(id)
        emit.yield(.removed(id))
    }

    /// Try a failed job again.
    public func retry(_ id: UUID) {
        guard let job = store.update(id, { j in
            j.state = j.items.allSatisfy(\.upload.isComplete) ? .sending : .uploading
            j.attempts = 0
            j.lease = nil
        }) else { return }
        emit.yield(.updated(job))
        start(id)
    }

    private func start(_ id: UUID) {
        running[id] = Task { [weak self] in
            await self?.run(id)
            await self?.finished(id)
        }
    }

    private func finished(_ id: UUID) {
        running[id] = nil
    }

    // MARK: Background events

    /// A request started by an earlier process (or the share extension) finished: apply it and carry on.
    public func handleOrphan(tag: TaskTag, response: TransportResponse?, error: Error?) async {
        guard var job = store.load(tag.job) else { return }
        let endpoint = job.server.appending(path: "v1/uploads")
        if let index = tag.item, let step = tag.step, job.items.indices.contains(index) {
            var upload = job.items[index].upload
            if let response {
                do {
                    try Tus.apply(step, to: &upload, status: response.status, headers: response.headers, endpoint: endpoint)
                } catch TusError.failed(let e) {
                    job.state = .failed(code: e.code)
                } catch {}
            } else {
                Tus.interrupted(&upload)
            }
            job.items[index].upload = upload
        } else if tag.item == nil, let response, (200..<300).contains(response.status),
                  let send = try? Self.decoder.decode(Send.self, from: response.body) {
            job.state = .sent(sendId: send.id)
            emit.yield(.sent(job, send))
        }
        job.lease = .init(owner: owner, until: Date().addingTimeInterval(Self.leaseDuration))
        let current = job
        if store.update(job.id, { $0 = current }) != nil {
            emit.yield(.updated(current))
        }
        await kick()
    }

    // MARK: Running a job

    private enum Stop: Error {
        case lostLease
    }

    private func run(_ id: UUID) async {
        guard let job = store.update(id, { $0.lease = .init(owner: owner, until: Date().addingTimeInterval(Self.leaseDuration)) }) else { return }
        emit.yield(.updated(job))
        guard let server, job.server == server, let tokens else {
            // Signed out, or the job belongs to another server: leave it for later.
            return
        }
        do {
            if case .uploading = job.state {
                try await uploadAll(job, tokens: tokens)
                if let j = store.update(id, { $0.state = .sending }) { emit.yield(.updated(j)) }
            }
            try await createSend(id, tokens: tokens)
        } catch is CancellationError {
        } catch Stop.lostLease {
        } catch {
            let code = (error as? APIError)?.code ?? "network"
            if let j = store.update(id, { $0.state = .failed(code: code); $0.lease = nil }) { emit.yield(.updated(j)) }
        }
    }

    private func uploadAll(_ job: OutboxJob, tokens: TokenProvider) async throws {
        let pending = job.items.indices.filter { !job.items[$0].upload.isComplete }
        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = pending.makeIterator()
            for _ in 0..<Self.parallelUploads {
                guard let index = next.next() else { break }
                group.addTask { try await self.upload(job.id, item: index, tokens: tokens) }
            }
            while try await group.next() != nil {
                if let index = next.next() {
                    group.addTask { try await self.upload(job.id, item: index, tokens: tokens) }
                }
            }
        }
    }

    /// Load the job and check we still drive it; renews the lease.
    private func claim(_ id: UUID) throws -> OutboxJob {
        guard let job = store.load(id) else { throw CancellationError() }
        if let lease = job.lease, lease.owner != owner { throw Stop.lostLease }
        return job
    }

    private func save(_ id: UUID, item index: Int, _ upload: TusUpload) throws {
        var lost = false
        let job = store.update(id) { j in
            if let lease = j.lease, lease.owner != self.owner { lost = true; return }
            j.items[index].upload = upload
            j.lease = .init(owner: self.owner, until: Date().addingTimeInterval(Self.leaseDuration))
        }
        if lost { throw Stop.lostLease }
        if let job { emit.yield(.updated(job)) }
    }

    /// Upload one file of a job until it's complete.
    func upload(_ id: UUID, item index: Int, tokens: TokenProvider) async throws {
        var job = try claim(id)
        let dir = store.directory(id)
        let endpoint = job.server.appending(path: "v1/uploads")
        var upload = job.items[index].upload
        let source = dir.appending(path: upload.file)

        if upload.sha256 == nil, upload.location == nil {
            upload.sha256 = try? OutboxStore.sha256(of: source)
            try save(id, item: index, upload)
        }

        var failures = 0
        var refreshedToken = false
        while true {
            try Task.checkCancellation()
            job = try claim(id)
            let step = Tus.next(job.items[index].upload, chunkSize: chunkSize)
            upload = job.items[index].upload
            guard var request = Tus.request(for: step, upload: upload, endpoint: endpoint) else { return }
            guard let token = try await tokens.accessToken() else { throw APIError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

            let body = try chunkFile(for: step, upload: upload, source: source, dir: dir, item: index)
            let response: TransportResponse
            do {
                response = try await transport.perform(request, bodyFile: body?.url, tag: TaskTag(job: id, item: index, step: step))
            } catch is CancellationError {
                throw CancellationError()
            } catch let e as URLError where e.code == .cancelled {
                throw CancellationError()
            } catch {
                body?.cleanup()
                Tus.interrupted(&upload)
                try save(id, item: index, upload)
                failures += 1
                if failures >= Self.maxAttempts { throw APIError.network }
                try await sleep(backoffDelay(attempt: failures - 1))
                continue
            }
            body?.cleanup()

            do {
                try Tus.apply(step, to: &upload, status: response.status, headers: response.headers, endpoint: endpoint)
                failures = 0
                try save(id, item: index, upload)
            } catch TusError.unauthorized {
                guard !refreshedToken, await tokens.refreshAfterUnauthorized() != nil else { throw APIError.unauthorized }
                refreshedToken = true
            } catch TusError.failed(let e) {
                throw e
            } catch {
                try save(id, item: index, upload)
                failures += 1
                if failures >= Self.maxAttempts { throw APIError.from(status: response.status, body: response.body) }
                try await sleep(backoffDelay(attempt: failures - 1))
            }
        }
    }

    private struct Body {
        var url: URL
        var temporary: Bool
        func cleanup() {
            if temporary { try? FileManager.default.removeItem(at: url) }
        }
    }

    /// The bytes for a create/patch step, as a file (background sessions upload from files).
    private func chunkFile(for step: TusStep, upload: TusUpload, source: URL, dir: URL, item: Int) throws -> Body? {
        let range: (offset: Int64, length: Int64)
        switch step {
        case .create(let length): range = (0, length)
        case .patch(let offset, let length): range = (offset, length)
        case .head, .done: return nil
        }
        if range.offset == 0, range.length == upload.size {
            return Body(url: source, temporary: false)
        }
        let handle = try FileHandle(forReadingFrom: source)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(range.offset))
        let data = try handle.read(upToCount: Int(range.length)) ?? Data()
        let url = dir.appending(path: "chunk-\(item)-\(range.offset).part")
        try data.write(to: url, options: .atomic)
        return Body(url: url, temporary: true)
    }

    private func createSend(_ id: UUID, tokens: TokenProvider) async throws {
        var job = try claim(id)
        let bodyURL = store.directory(id).appending(path: "send.json")
        var failures = 0
        var refreshedToken = false
        var resetUploads = false
        while true {
            try Task.checkCancellation()
            job = try claim(id)
            try job.sendBody().write(to: bodyURL, options: .atomic)
            var request = URLRequest(url: job.server.appending(path: "v1/sends"))
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue(job.idempotencyKey, forHTTPHeaderField: "Idempotency-Key")
            request.setValue(L10n.code, forHTTPHeaderField: "Accept-Language")
            guard let token = try await tokens.accessToken() else { throw APIError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

            let response: TransportResponse
            do {
                response = try await transport.perform(request, bodyFile: bodyURL, tag: TaskTag(job: id, item: nil, step: nil))
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                failures += 1
                if failures >= Self.maxAttempts { throw APIError.network }
                try await sleep(backoffDelay(attempt: failures - 1))
                continue
            }

            switch response.status {
            case 200..<300:
                let send = try Self.decoder.decode(Send.self, from: response.body)
                guard let done = store.update(id, { $0.state = .sent(sendId: send.id); $0.lease = nil }) else { return }
                emit.yield(.sent(done, send))
                emit.yield(.updated(done))
                // Media is on the server now; keep only what the history card needs until the next kick.
                for item in done.items {
                    try? FileManager.default.removeItem(at: store.directory(id).appending(path: item.upload.file))
                }
                return
            case 401:
                guard !refreshedToken, await tokens.refreshAfterUnauthorized() != nil else { throw APIError.unauthorized }
                refreshedToken = true
            case 400..<500 where response.status != 408 && response.status != 429:
                let problem = APIError.from(status: response.status, body: response.body)
                // Uploads expire after 24 h without a send: upload again, once.
                if problem.code == "upload_not_found", !resetUploads {
                    resetUploads = true
                    job = store.update(id) { j in
                        for i in j.items.indices where j.items[i].upload.existingItemId == nil { Tus.reset(&j.items[i].upload) }
                        j.state = .uploading
                    } ?? job
                    try await uploadAll(job, tokens: tokens)
                    continue
                }
                throw problem
            default:
                failures += 1
                if failures >= Self.maxAttempts { throw APIError.from(status: response.status, body: response.body) }
                try await sleep(backoffDelay(attempt: failures - 1))
            }
        }
    }

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let s = try decoder.singleValueContainer().decode(String.self)
            if let date = FlexibleDateTranscoder.parse(s) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date \(s)"))
        }
        return d
    }()
}
