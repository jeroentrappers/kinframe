import CoreGraphics
import Foundation
import Observation

/// A picked photo or video, before it's queued.
public struct Draft: Identifiable, Sendable {
    public let id: UUID
    /// The file, staged in the App Group so it can move into the upload queue without copying.
    public var file: URL
    public var filename: String
    public var info: MediaInfo?
    public var loading: Bool
    /// Own caption; nil uses the shared caption.
    public var caption: String?
    public var fit: Fit = .fill
    public var focal = CGPoint(x: 0.5, y: 0.5)
    public var trim: Trim?
    public var includeAudio = true
    /// The motion clip of a Live Photo, staged next to the still.
    public var motionFile: URL?
    /// A Live Photo goes out live unless the person picks "send as a still photo only".
    public var sendLive = true

    /// A Live Photo that will be sent with its clip.
    public var isLive: Bool { motionFile != nil && sendLive }

    public var kind: MediaKind { info?.kind ?? (ComposeRules.kind(mime: nil, filename: filename) ?? .photo) }
    public var bytes: Int64 { info?.bytes ?? 0 }
    public var aspect: Double? {
        guard let s = info?.pixelSize, s.height > 0 else { return nil }
        return s.width / s.height
    }
}

/// The send composer: picked items, captions, framing, trims and recipients, validated against the server's
/// limits. Shared by the app's Send tab and the share extension.
@MainActor
@Observable
public final class ComposeModel {
    public private(set) var drafts: [Draft] = []
    public var frameIds: Set<String> = []
    /// Caption for every item without its own.
    public var sharedCaption = ""
    public var limits: Limits

    let staging: URL

    public init(limits: Limits, staging: URL = AppGroup.container.appending(path: "Staging/\(UUID().uuidString)", directoryHint: .isDirectory)) {
        self.limits = limits
        self.staging = staging
    }

    public struct AddResult: Equatable, Sendable {
        public var added = 0
        public var unsupported = 0
        public var overLimit = 0
    }

    public var room: Int { ComposeRules.room(alreadyQueued: drafts.count, limits: limits) }

    /// Take ownership of picked files (moved into staging) and probe them.
    @discardableResult
    public func add(files: [(url: URL, filename: String)]) async -> AddResult {
        await add(media: files.map { (url: $0.url, filename: $0.filename, motion: nil) })
    }

    /// Like `add(files:)`, with the motion clip of each Live Photo (nil for other files).
    @discardableResult
    public func add(media files: [(url: URL, filename: String, motion: URL?)]) async -> AddResult {
        var result = AddResult()
        try? FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        var fresh: [Draft] = []
        for (url, name, motion) in files {
            let discardMotion = { if let motion { try? FileManager.default.removeItem(at: motion) } }
            guard ComposeRules.kind(mime: nil, filename: name) != nil || ComposeRules.kind(mime: nil, filename: url.lastPathComponent) != nil else {
                result.unsupported += 1
                try? FileManager.default.removeItem(at: url)
                discardMotion()
                continue
            }
            guard drafts.count + fresh.count < limits.maxItemsPerSend else {
                result.overLimit += 1
                try? FileManager.default.removeItem(at: url)
                discardMotion()
                continue
            }
            let id = UUID()
            let ext = (name as NSString).pathExtension.isEmpty ? url.pathExtension : (name as NSString).pathExtension
            let dest = staging.appending(path: "\(id.uuidString).\(ext.lowercased())")
            do {
                if url.path.hasPrefix(staging.path) {
                    try FileManager.default.moveItem(at: url, to: dest)
                } else {
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.moveItem(at: url, to: dest)
                }
            } catch {
                guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { result.unsupported += 1; discardMotion(); continue }
            }
            var draft = Draft(id: id, file: dest, filename: MediaProbe.safeFilename(name, fallbackExtension: ext), loading: true)
            if let motion {
                let clip = staging.appending(path: "\(id.uuidString)-motion.\(motion.pathExtension.isEmpty ? "mov" : motion.pathExtension.lowercased())")
                if (try? FileManager.default.moveItem(at: motion, to: clip)) != nil || (try? FileManager.default.copyItem(at: motion, to: clip)) != nil {
                    draft.motionFile = clip
                }
            }
            fresh.append(draft)
        }
        drafts.append(contentsOf: fresh)
        result.added = fresh.count
        await withTaskGroup(of: (UUID, MediaInfo?).self) { group in
            for d in fresh {
                let url = d.file
                group.addTask { (d.id, await MediaProbe.probe(url)) }
            }
            for await (id, info) in group {
                guard let i = drafts.firstIndex(where: { $0.id == id }) else { continue }
                drafts[i].info = info
                drafts[i].loading = false
                if info?.kind == .video, let duration = info?.duration, duration > Double(limits.maxVideoSeconds) {
                    // Start with a trim that fits, so long videos can still be sent.
                    drafts[i].trim = ComposeRules.clampTrim(start: 0, end: Double(limits.maxVideoSeconds), duration: duration, maxSeconds: limits.maxVideoSeconds)
                }
            }
        }
        return result
    }

    public func remove(_ id: UUID) {
        guard let i = drafts.firstIndex(where: { $0.id == id }) else { return }
        try? FileManager.default.removeItem(at: drafts[i].file)
        if let motion = drafts[i].motionFile { try? FileManager.default.removeItem(at: motion) }
        drafts.remove(at: i)
    }

    public func update(_ id: UUID, _ change: (inout Draft) -> Void) {
        guard let i = drafts.firstIndex(where: { $0.id == id }) else { return }
        change(&drafts[i])
    }

    public func caption(of draft: Draft) -> String {
        draft.caption ?? sharedCaption
    }

    /// "Use for all": one caption for every item.
    public func applyCaptionToAll(_ text: String) {
        sharedCaption = text
        for i in drafts.indices { drafts[i].caption = nil }
    }

    public func toggle(frame id: String) {
        if frameIds.contains(id) { frameIds.remove(id) } else { frameIds.insert(id) }
    }

    public func issue(_ d: Draft) -> ItemIssue? {
        ComposeRules.issue(kind: d.kind, bytes: d.bytes, durationSeconds: d.info?.duration, trim: d.trim, limits: limits)
    }

    public var issueCount: Int { drafts.filter { issue($0) != nil }.count }
    public var totalBytes: Int64 { drafts.reduce(0) { $0 + $1.bytes } }
    public var isProbing: Bool { drafts.contains(where: \.loading) }

    public var blocker: ComposeRules.Blocker? {
        ComposeRules.blocker(
            itemCount: drafts.count,
            frameCount: frameIds.count,
            issues: issueCount,
            longestCaption: drafts.map { caption(of: $0).count }.max() ?? 0,
            limits: limits
        )
    }

    public var canSend: Bool { blocker == nil && !isProbing }

    /// Move the files into the upload queue and describe the send. The composer is empty afterwards.
    public func makeJob(server: URL, frames: [FrameSummary], store: OutboxStore) throws -> OutboxJob {
        let id = UUID()
        let dir = try store.makeDirectory(for: id)
        var items: [OutboxItem] = []
        for (n, d) in drafts.enumerated() {
            let file = "item-\(n).\(d.file.pathExtension)"
            try FileManager.default.moveItem(at: d.file, to: dir.appending(path: file))
            var thumbnail: String?
            if let data = d.info?.thumbnail {
                thumbnail = "thumb-\(n).jpg"
                try? data.write(to: dir.appending(path: thumbnail!))
            }
            let mime = d.info?.mime ?? ComposeRules.mimeType(forExtension: d.file.pathExtension)
            let upload = TusUpload(file: file, size: d.bytes, filename: d.filename, mime: mime)
            var motion: TusUpload?
            if let clip = d.motionFile {
                if d.isLive {
                    let name = "motion-\(n).\(clip.pathExtension)"
                    try FileManager.default.moveItem(at: clip, to: dir.appending(path: name))
                    let size = (try? dir.appending(path: name).resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
                    motion = TusUpload(file: name, size: size, filename: ((d.filename as NSString).deletingPathExtension) + "." + clip.pathExtension,
                                       mime: ComposeRules.mimeType(forExtension: clip.pathExtension))
                } else {
                    try? FileManager.default.removeItem(at: clip)
                }
            }
            let focal = Framing.clampFocal(d.focal)
            items.append(OutboxItem(
                upload: upload,
                kind: d.kind,
                caption: caption(of: d).trimmingCharacters(in: .whitespacesAndNewlines),
                fit: d.fit,
                focal: FocalPoint(x: focal.x, y: focal.y),
                trim: d.kind == .video ? d.trim : nil,
                includeAudio: d.kind == .video ? d.includeAudio : nil,
                thumbnail: thumbnail,
                motion: motion
            ))
        }
        let chosen = frames.filter { frameIds.contains($0.id) }
        let job = OutboxJob(id: id, server: server, frameIds: chosen.map(\.id), frameNames: chosen.map(\.name), items: items)
        drafts = []
        sharedCaption = ""
        try? FileManager.default.removeItem(at: staging)
        return job
    }

    /// Drop everything picked.
    public func clear() {
        drafts = []
        sharedCaption = ""
        try? FileManager.default.removeItem(at: staging)
    }
}
