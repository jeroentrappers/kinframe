import CoreGraphics
import CryptoKit
import Foundation

/// One item of a queued send: the file being uploaded plus how it should appear on the frame.
public struct OutboxItem: Codable, Equatable, Sendable {
    public var upload: TusUpload
    public var kind: MediaKind
    public var caption: String?
    public var fit: Fit?
    public var focal: FocalPoint?
    public var trim: Trim?
    public var includeAudio: Bool?
    public var capturedAt: Date?
    /// A small JPEG preview in the job directory, for the "sending" card.
    public var thumbnail: String?

    public init(upload: TusUpload, kind: MediaKind, caption: String? = nil, fit: Fit? = nil, focal: FocalPoint? = nil,
                trim: Trim? = nil, includeAudio: Bool? = nil, capturedAt: Date? = nil, thumbnail: String? = nil) {
        self.upload = upload
        self.kind = kind
        self.caption = caption
        self.fit = fit
        self.focal = focal
        self.trim = trim
        self.includeAudio = includeAudio
        self.capturedAt = capturedAt
        self.thumbnail = thumbnail
    }
}

/// A send waiting in the upload queue. Lives in the App Group so the share extension can hand sends to the app.
public struct OutboxJob: Codable, Identifiable, Equatable, Sendable {
    public enum State: Codable, Equatable, Sendable {
        case uploading
        case sending
        case sent(sendId: String)
        case failed(code: String)
    }

    /// Which process is driving the job right now, and until when (renewed every step).
    public struct Lease: Codable, Equatable, Sendable {
        public var owner: String
        public var until: Date
    }

    public var id: UUID
    public var createdAt: Date
    /// API base of the server the job belongs to.
    public var server: URL
    public var frameIds: [String]
    public var frameNames: [String]
    public var items: [OutboxItem]
    /// Sent with `POST /v1/sends`, stable across retries so a retry never creates a second send.
    public var idempotencyKey: String
    public var state: State
    public var lease: Lease?
    public var attempts: Int

    public init(id: UUID = UUID(), server: URL, frameIds: [String], frameNames: [String], items: [OutboxItem]) {
        self.id = id
        createdAt = Date()
        self.server = server
        self.frameIds = frameIds
        self.frameNames = frameNames
        self.items = items
        idempotencyKey = UUID().uuidString.lowercased()
        state = .uploading
        attempts = 0
    }

    public var totalBytes: Int64 { items.reduce(0) { $0 + $1.upload.size } }
    public var doneBytes: Int64 { items.reduce(0) { $0 + $1.upload.bytesDone } }
    public var uploadedCount: Int { items.filter(\.upload.isComplete).count }

    /// 0…1, by bytes; the final send request counts as the last 2 %.
    public var progress: Double {
        switch state {
        case .sent: return 1
        case .failed: return 0
        case .uploading, .sending:
            let total = max(1, totalBytes)
            let bytes = Double(doneBytes) / Double(total)
            return min(0.98, bytes * 0.98)
        }
    }

    public var isFinished: Bool {
        switch state {
        case .sent, .failed: true
        case .uploading, .sending: false
        }
    }

    /// The `POST /v1/sends` body.
    public func sendBody() throws -> Data {
        struct Body: Encodable {
            struct Item: Encodable {
                var upload_id: String
                var caption: String?
                var fit: String?
                var focal_point: FocalPoint?
                var trim: Trim?
                var include_audio: Bool?
                var captured_at: String?
            }
            var frame_ids: [String]
            var items: [Item]
        }
        let iso = ISO8601DateFormatter()
        let items = try self.items.map { i -> Body.Item in
            guard let id = i.upload.uploadId else { throw TusError.retry }
            let caption = i.caption?.trimmingCharacters(in: .whitespacesAndNewlines)
            return Body.Item(
                upload_id: id,
                caption: caption?.isEmpty == false ? caption : nil,
                fit: i.fit?.rawValue,
                focal_point: i.focal,
                trim: i.kind == .video ? i.trim : nil,
                include_audio: i.kind == .video ? i.includeAudio : nil,
                captured_at: i.capturedAt.map(iso.string(from:))
            )
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(Body(frame_ids: frameIds, items: items))
    }
}

/// File-based queue in `<App Group>/Outbox/<job id>/`: `job.json` plus the media files. Reads and writes are
/// coordinated so the app and the share extension can both touch a job.
public final class OutboxStore: Sendable {
    public let root: URL

    public init(root: URL = AppGroup.container.appending(path: "Outbox", directoryHint: .isDirectory)) {
        self.root = root
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    public func directory(_ id: UUID) -> URL {
        root.appending(path: id.uuidString, directoryHint: .isDirectory)
    }

    func jobFile(_ id: UUID) -> URL {
        directory(id).appending(path: "job.json")
    }

    /// A new job directory to copy media into before `save`.
    public func makeDirectory(for id: UUID) throws -> URL {
        let dir = directory(id)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    public func save(_ job: OutboxJob) throws {
        let data = try JSONEncoder().encode(job)
        var coordError: NSError?
        var writeError: Error?
        NSFileCoordinator().coordinate(writingItemAt: jobFile(job.id), options: .forReplacing, error: &coordError) { url in
            do { try data.write(to: url, options: .atomic) } catch { writeError = error }
        }
        if let e = coordError ?? writeError { throw e }
    }

    public func load(_ id: UUID) -> OutboxJob? {
        var result: OutboxJob?
        var coordError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: jobFile(id), options: [], error: &coordError) { url in
            result = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(OutboxJob.self, from: $0) }
        }
        return result
    }

    /// Read-modify-write under one coordinated write. Returns the updated job, or nil if it no longer exists.
    @discardableResult
    public func update(_ id: UUID, _ change: (inout OutboxJob) -> Void) -> OutboxJob? {
        var result: OutboxJob?
        var coordError: NSError?
        NSFileCoordinator().coordinate(writingItemAt: jobFile(id), options: [], error: &coordError) { url in
            guard var job = (try? Data(contentsOf: url)).flatMap({ try? JSONDecoder().decode(OutboxJob.self, from: $0) }) else { return }
            change(&job)
            if let data = try? JSONEncoder().encode(job), (try? data.write(to: url, options: .atomic)) != nil {
                result = job
            }
        }
        return result
    }

    public func all() -> [OutboxJob] {
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return dirs.compactMap { UUID(uuidString: $0.lastPathComponent) }.compactMap(load).sorted { $0.createdAt > $1.createdAt }
    }

    /// Remove directories left without a job (a process died while queueing) and stale composer staging folders.
    public func cleanUp(olderThan age: TimeInterval = 3600) {
        let fm = FileManager.default
        let cutoff = Date().addingTimeInterval(-age)
        let staging = root.deletingLastPathComponent().appending(path: "Staging", directoryHint: .isDirectory)
        for dir in [root, staging] {
            for url in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
                let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                guard modified < cutoff else { continue }
                if dir == staging || !fm.fileExists(atPath: url.appending(path: "job.json").path) {
                    try? fm.removeItem(at: url)
                }
            }
        }
    }

    public func remove(_ id: UUID) {
        try? FileManager.default.removeItem(at: directory(id))
    }

    /// Hex SHA-256 of a file, streamed (videos can be large).
    public static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
