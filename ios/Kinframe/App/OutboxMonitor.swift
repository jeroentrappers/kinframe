import KinframeKit
import SwiftUI

/// The app's side of the upload queue: owns the runner and its background session, publishes job progress for the
/// UI and answers the system's background-session wake-ups (also for sessions the share extension started).
@MainActor
@Observable
final class OutboxMonitor {
    static let sessionIdentifier = "app.kinframe.family.uploads"
    static let shareSessionPrefix = "app.kinframe.family.share."

    private(set) var jobs: [OutboxJob] = []
    var onSent: ((Send) -> Void)?

    let store = OutboxStore()
    private let transport = BackgroundUploadTransport(identifier: OutboxMonitor.sessionIdentifier)
    private let runner: OutboxRunner
    /// Sessions the share extension started, recreated here when the system wakes the app for them.
    private var shareTransports: [String: BackgroundUploadTransport] = [:]

    init() {
        runner = OutboxRunner(store: store, transport: transport, owner: "app-\(UUID().uuidString)")
        let runner = runner
        transport.setOrphanHandler { tag, response, error in
            Task { await runner.handleOrphan(tag: tag, response: response, error: error) }
        }
        jobs = store.all()
        Task { [weak self] in
            for await event in runner.events {
                self?.apply(event)
            }
        }
    }

    func configure(server: URL?, tokens: TokenProvider?) {
        Task {
            await runner.configure(server: server, tokens: tokens)
            await runner.kick()
        }
    }

    func kick() {
        jobs = store.all()
        Task { await runner.kick() }
    }

    /// Queue a send and start uploading.
    func enqueue(_ job: OutboxJob) throws {
        try store.save(job)
        jobs.insert(job, at: 0)
        Task { await runner.kick() }
    }

    func cancel(_ id: UUID) {
        Task { await runner.cancel(id) }
    }

    func retry(_ id: UUID) {
        Task { await runner.retry(id) }
    }

    /// Jobs still on their way (shown above the history).
    var active: [OutboxJob] {
        jobs.filter {
            if case .sent = $0.state { return false }
            return true
        }
    }

    func job(_ id: UUID) -> OutboxJob? {
        jobs.first { $0.id == id }
    }

    private func apply(_ event: OutboxEvent) {
        switch event {
        case .updated(let job):
            if let i = jobs.firstIndex(where: { $0.id == job.id }) {
                jobs[i] = job
            } else {
                jobs.insert(job, at: 0)
            }
        case .sent(let job, let send):
            if let i = jobs.firstIndex(where: { $0.id == job.id }) { jobs[i] = job }
            onSent?(send)
            PushRegistrar.shared.askAfterFirstSend()
        case .removed(let id):
            jobs.removeAll { $0.id == id }
        }
    }

    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
    func handleBackgroundEvents(identifier: String, completion: @escaping @Sendable () -> Void) {
        if identifier == Self.sessionIdentifier {
            transport.setFinishEventsHandler(completion)
            return
        }
        guard identifier.hasPrefix(Self.shareSessionPrefix) else {
            completion()
            return
        }
        let t = shareTransports[identifier] ?? BackgroundUploadTransport(identifier: identifier)
        let runner = runner
        t.setOrphanHandler { tag, response, error in
            Task { await runner.handleOrphan(tag: tag, response: response, error: error) }
        }
        t.setFinishEventsHandler(completion)
        shareTransports[identifier] = t
    }
}
