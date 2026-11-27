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
    /// Zoom and position per orientation (docs/spec/framing.md §4): only the orientations the person changed.
    public var framing: ItemFraming?
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
    /// Feeds to post to, next to the frames (docs/spec/feeds.md §6.1). At least one frame or feed.
    public var feedIds: Set<String> = []
    /// "Share with": a circle per chosen feed (feed id → circle id); a feed without one gets a whole-family post.
    public var circleByFeed: [String: String] = [:]
    /// The weekly prompt these posts answer (its feed must be chosen).
    public var promptId: String?
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

    /// A tap on the photo: the focal point, and both views' centres move there keeping their zoom (framing.md §4).
    public func setFocal(_ id: UUID, _ point: CGPoint) {
        let p = Framing.clampFocal(point)
        update(id) { d in
            d.focal = p
            d.framing = Framing.recentred(d.framing, at: p)
        }
    }

    /// Sets (or with nil, resets) one orientation's view.
    public func setView(_ id: UUID, _ o: ScreenOrientation, _ view: FramingView?) {
        update(id) { d in
            var f = d.framing ?? ItemFraming()
            f[o] = view
            d.framing = Framing.sendable(f)
        }
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

    public func toggle(feed id: String) {
        if feedIds.contains(id) { feedIds.remove(id) } else { feedIds.insert(id) }
    }

    /// Frames plus feeds.
    public var recipientCount: Int { frameIds.count + feedIds.count }

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
            feedCount: feedIds.count,
            issues: issueCount,
            longestCaption: drafts.map { caption(of: $0).count }.max() ?? 0,
            limits: limits
        )
    }

    public var canSend: Bool { blocker == nil && !isProbing }

    /// Move the files into the upload queue and describe the send. The composer is empty afterwards.
    /// One server's recipients, for a send to several accounts (docs/spec/multi-server.md §2.2).
    public struct ServerTargets: Sendable {
        public var server: URL
        public var serverName: String?
        public var frames: [FrameSummary]
        public var feeds: [FeedSummary]

        public init(server: URL, serverName: String? = nil, frames: [FrameSummary], feeds: [FeedSummary]) {
            self.server = server
            self.serverName = serverName
            self.frames = frames
            self.feeds = feeds
        }
    }

    /// One job per server that has chosen recipients: two uploads, one sheet. The first job prepares the media; the
    /// others take its prepared masters (`OutboxJob.preparedBy`). Ids are unique per server, so the chosen ids are
    /// looked up in each server's own lists.
    public func makeJobs(_ targets: [ServerTargets], store: OutboxStore) throws -> [OutboxJob] {
        let used = targets.filter { t in
            t.frames.contains { frameIds.contains($0.id) } || t.feeds.contains { feedIds.contains($0.id) }
        }
        guard !used.isEmpty else { return [] }
        var jobs: [OutboxJob] = []
        for (n, t) in used.enumerated() {
            var job = try makeJob(server: t.server, frames: t.frames, feeds: t.feeds, store: store, keepDrafts: n < used.count - 1)
            if used.count > 1 { job.serverName = t.serverName }
            if let leader = jobs.first { job.preparedBy = leader.id }
            jobs.append(job)
        }
        return jobs
    }

    public func makeJob(server: URL, frames: [FrameSummary], feeds: [FeedSummary] = [], store: OutboxStore,
                        keepDrafts: Bool = false) throws -> OutboxJob {
        let id = UUID()
        let dir = try store.makeDirectory(for: id)
        var items: [OutboxItem] = []
        for (n, d) in drafts.enumerated() {
            let file = "item-\(n).\(d.file.pathExtension)"
            if keepDrafts {
                try FileManager.default.copyItem(at: d.file, to: dir.appending(path: file))
            } else {
                try FileManager.default.moveItem(at: d.file, to: dir.appending(path: file))
            }
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
                    if keepDrafts {
                        try FileManager.default.copyItem(at: clip, to: dir.appending(path: name))
                    } else {
                        try FileManager.default.moveItem(at: clip, to: dir.appending(path: name))
                    }
                    let size = (try? dir.appending(path: name).resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
                    motion = TusUpload(file: name, size: size, filename: ((d.filename as NSString).deletingPathExtension) + "." + clip.pathExtension,
                                       mime: ComposeRules.mimeType(forExtension: clip.pathExtension))
                } else if !keepDrafts {
                    try? FileManager.default.removeItem(at: clip)
                }
            }
            // Framing is for photos (videos ignore it, framing.md §2); the focal point follows its centre (§2.1).
            let framing = d.kind == .photo ? Framing.sendable(d.framing) : nil
            let focal = Framing.focal(for: framing, tapped: d.focal)
            items.append(OutboxItem(
                upload: upload,
                kind: d.kind,
                caption: caption(of: d).trimmingCharacters(in: .whitespacesAndNewlines),
                fit: d.fit,
                focal: FocalPoint(x: focal.x, y: focal.y),
                framing: framing,
                trim: d.kind == .video ? d.trim : nil,
                includeAudio: d.kind == .video ? d.includeAudio : nil,
                thumbnail: thumbnail,
                motion: motion
            ))
        }
        let chosen = frames.filter { frameIds.contains($0.id) }
        let chosenFeeds = feeds.filter { feedIds.contains($0.id) }
        var job = OutboxJob(id: id, server: server, frameIds: chosen.map(\.id), frameNames: chosen.map(\.name),
                            feedIds: chosenFeeds.map(\.id), feedNames: chosenFeeds.map(\.name), items: items)
        let circles = chosenFeeds.compactMap { circleByFeed[$0.id] }
        job.circleIds = circles.isEmpty ? nil : circles
        job.promptId = chosenFeeds.isEmpty ? nil : promptId
        if keepDrafts { return job }
        circleByFeed = [:]
        promptId = nil
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
