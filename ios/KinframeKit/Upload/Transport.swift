import Foundation
import os

/// Identifies what an upload-queue request was for, so a response that arrives after the app was relaunched can
/// still be applied to the right job (stored in `URLSessionTask.taskDescription`).
public struct TaskTag: Codable, Equatable, Hashable, Sendable {
    public var job: UUID
    /// Index into the job's items, or nil for the final `POST /v1/sends`.
    public var item: Int?
    public var step: TusStep?

    public init(job: UUID, item: Int?, step: TusStep?) {
        self.job = job
        self.item = item
        self.step = step
    }

    var encoded: String { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "" }

    static func decode(_ s: String?) -> TaskTag? {
        s.flatMap { try? JSONDecoder().decode(TaskTag.self, from: Data($0.utf8)) }
    }
}

public struct TransportResponse: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String], body: Data) {
        self.status = status
        self.headers = headers
        self.body = body
    }

    init(_ response: HTTPURLResponse, body: Data) {
        status = response.statusCode
        headers = Dictionary(response.allHeaderFields.compactMap { k, v in (k as? String).map { ($0, "\(v)") } }, uniquingKeysWith: { a, _ in a })
        self.body = body
    }
}

/// Performs the upload queue's HTTP requests. Bodies always come from files so they can run in a background session.
public protocol UploadTransport: Sendable {
    func perform(_ request: URLRequest, bodyFile: URL?, tag: TaskTag) async throws -> TransportResponse
    /// Tags of requests still running from an earlier process (their results arrive as orphans).
    func inFlight() async -> Set<TaskTag>
}

/// A plain URLSession transport. Used by tests (with a URLProtocol stub) and for requests without a body.
public struct URLSessionUploadTransport: UploadTransport {
    let session: URLSession

    public init(session: URLSession) {
        self.session = session
    }

    public func perform(_ request: URLRequest, bodyFile: URL?, tag: TaskTag) async throws -> TransportResponse {
        let (data, response): (Data, URLResponse)
        if let bodyFile {
            (data, response) = try await session.upload(for: request, fromFile: bodyFile)
        } else {
            (data, response) = try await session.data(for: request)
        }
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return TransportResponse(http, body: data)
    }

    public func inFlight() async -> Set<TaskTag> { [] }
}

/// The production transport: a background URLSession, so uploads continue while the app is suspended or after the
/// share sheet closed. Responses for tasks started by an earlier process are handed to `onOrphan`.
public final class BackgroundUploadTransport: NSObject, UploadTransport, URLSessionDataDelegate, @unchecked Sendable {
    public typealias OrphanHandler = @Sendable (TaskTag, TransportResponse?, Error?) -> Void

    public let identifier: String
    private struct State {
        var waiters: [Int: CheckedContinuation<TransportResponse, Error>] = [:]
        var buffers: [Int: Data] = [:]
        var finishEvents: (@Sendable () -> Void)?
        var onOrphan: OrphanHandler?
    }
    private let state = OSAllocatedUnfairLock(initialState: State())
    private var session: URLSession!
    private let plain: URLSession

    public init(identifier: String) {
        self.identifier = identifier
        plain = URLSession(configuration: .default)
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: identifier)
        config.sharedContainerIdentifier = AppGroup.identifier
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 7 * 24 * 3600
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    public func setOrphanHandler(_ handler: @escaping OrphanHandler) {
        state.withLock { $0.onOrphan = handler }
    }

    /// The completion handler from `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
    public func setFinishEventsHandler(_ handler: @escaping @Sendable () -> Void) {
        state.withLock { $0.finishEvents = handler }
    }

    public func perform(_ request: URLRequest, bodyFile: URL?, tag: TaskTag) async throws -> TransportResponse {
        // Background sessions only run upload and download tasks; a HEAD is quick enough for a plain session.
        guard let bodyFile else {
            let (data, response) = try await plain.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            return TransportResponse(http, body: data)
        }
        let task = session.uploadTask(with: request, fromFile: bodyFile)
        task.taskDescription = tag.encoded
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                state.withLock { $0.waiters[task.taskIdentifier] = continuation }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    public func inFlight() async -> Set<TaskTag> {
        let tasks = await session.allTasks
        let mine = state.withLock { Set($0.waiters.keys) }
        return Set(tasks.filter { $0.state == .running && !mine.contains($0.taskIdentifier) }.compactMap { TaskTag.decode($0.taskDescription) })
    }

    // MARK: URLSessionDataDelegate

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        state.withLock { $0.buffers[dataTask.taskIdentifier, default: Data()].append(data) }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let (waiter, body, orphan) = state.withLock { s in
            (s.waiters.removeValue(forKey: task.taskIdentifier), s.buffers.removeValue(forKey: task.taskIdentifier) ?? Data(), s.onOrphan)
        }
        let response = (task.response as? HTTPURLResponse).map { TransportResponse($0, body: body) }
        if let waiter {
            if let response, error == nil {
                waiter.resume(returning: response)
            } else {
                waiter.resume(throwing: error ?? URLError(.badServerResponse))
            }
            return
        }
        if let tag = TaskTag.decode(task.taskDescription) {
            orphan?(tag, error == nil ? response : nil, error)
        }
    }

    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let handler = state.withLock { s -> (@Sendable () -> Void)? in
            defer { s.finishEvents = nil }
            return s.finishEvents
        }
        if let handler {
            DispatchQueue.main.async { handler() }
        }
    }
}
