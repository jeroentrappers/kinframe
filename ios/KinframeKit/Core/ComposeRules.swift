import CoreGraphics
import Foundation

/// Server limits from discovery (`limits`).
public struct Limits: Codable, Equatable, Sendable {
    public var maxItemsPerSend: Int
    public var maxVideoSeconds: Int
    public var maxUploadBytes: Int64
    public var trashRetentionDays: Int?
    /// Feeds one account may own, subscriptions per feed, and the cap of `history: all` per subscription.
    public var maxFeedsPerUser: Int = 20
    public var maxSubscriptionsPerFeed: Int = 100
    public var maxFeedBackfillItems: Int = 5000
    /// Birthday people per feed (pending and approved).
    public var maxBirthdaysPerFeed: Int = 200
    /// Photo sources (docs/spec/photo-sources.md §11): per album or feed, per account, live items per source, and
    /// items per Google Photos import.
    public var maxPhotoSourcesPerTarget: Int = 5
    public var maxPhotoSourcesPerUser: Int = 20
    public var maxPhotoSourceItems: Int = 10_000
    public var maxGoogleImportItems: Int = 500

    public init(maxItemsPerSend: Int = 200, maxVideoSeconds: Int = 300, maxUploadBytes: Int64 = 2 << 30, trashRetentionDays: Int? = 30) {
        self.maxItemsPerSend = maxItemsPerSend
        self.maxVideoSeconds = maxVideoSeconds
        self.maxUploadBytes = maxUploadBytes
        self.trashRetentionDays = trashRetentionDays
    }

    public static let defaults = Limits()

    enum CodingKeys: String, CodingKey {
        case maxItemsPerSend = "max_items_per_send"
        case maxVideoSeconds = "max_video_seconds"
        case maxUploadBytes = "max_upload_bytes"
        case trashRetentionDays = "trash_retention_days"
        case maxFeedsPerUser = "max_feeds_per_user"
        case maxSubscriptionsPerFeed = "max_subscriptions_per_feed"
        case maxFeedBackfillItems = "max_feed_backfill_items"
        case maxBirthdaysPerFeed = "max_birthdays_per_feed"
        case maxPhotoSourcesPerTarget = "max_photo_sources_per_target"
        case maxPhotoSourcesPerUser = "max_photo_sources_per_user"
        case maxPhotoSourceItems = "max_photo_source_items"
        case maxGoogleImportItems = "max_google_import_items"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        maxItemsPerSend = try c.decodeIfPresent(Int.self, forKey: .maxItemsPerSend) ?? 200
        maxVideoSeconds = try c.decodeIfPresent(Int.self, forKey: .maxVideoSeconds) ?? 300
        maxUploadBytes = try c.decodeIfPresent(Int64.self, forKey: .maxUploadBytes) ?? 2 << 30
        trashRetentionDays = try c.decodeIfPresent(Int.self, forKey: .trashRetentionDays)
        maxFeedsPerUser = try c.decodeIfPresent(Int.self, forKey: .maxFeedsPerUser) ?? 20
        maxSubscriptionsPerFeed = try c.decodeIfPresent(Int.self, forKey: .maxSubscriptionsPerFeed) ?? 100
        maxFeedBackfillItems = try c.decodeIfPresent(Int.self, forKey: .maxFeedBackfillItems) ?? 5000
        maxBirthdaysPerFeed = try c.decodeIfPresent(Int.self, forKey: .maxBirthdaysPerFeed) ?? 200
        maxPhotoSourcesPerTarget = (try? c.decodeIfPresent(Int.self, forKey: .maxPhotoSourcesPerTarget)) ?? 5
        maxPhotoSourcesPerUser = (try? c.decodeIfPresent(Int.self, forKey: .maxPhotoSourcesPerUser)) ?? 20
        maxPhotoSourceItems = (try? c.decodeIfPresent(Int.self, forKey: .maxPhotoSourceItems)) ?? 10_000
        maxGoogleImportItems = (try? c.decodeIfPresent(Int.self, forKey: .maxGoogleImportItems)) ?? 500
    }
}

public enum MediaKind: String, Codable, Sendable {
    case photo, video
}

public enum ItemIssue: Equatable, Sendable {
    case tooLarge
    case videoTooLong
}

/// A trim window in milliseconds, as sent in `items[].trim`.
public struct Trim: Codable, Equatable, Sendable {
    public var startMs: Int
    public var endMs: Int

    public init(startMs: Int, endMs: Int) {
        self.startMs = startMs
        self.endMs = endMs
    }

    public var seconds: Double { Double(endMs - startMs) / 1000 }

    enum CodingKeys: String, CodingKey {
        case startMs = "start_ms"
        case endMs = "end_ms"
    }
}

/// Validation of a send against the server's limits (README §5.3), mirroring web/src/lib/logic.ts.
public enum ComposeRules {
    public static let minVideoSeconds = 3.0

    static let photoExtensions: Set<String> = ["jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif"]
    static let videoExtensions: Set<String> = ["mp4", "mov", "m4v", "3gp", "mkv", "webm", "hevc"]

    /// Classify a file by MIME type, falling back to the extension.
    public static func kind(mime: String?, filename: String) -> MediaKind? {
        if let mime {
            if mime.hasPrefix("image/") { return .photo }
            if mime.hasPrefix("video/") { return .video }
        }
        let ext = (filename as NSString).pathExtension.lowercased()
        if photoExtensions.contains(ext) { return .photo }
        if videoExtensions.contains(ext) { return .video }
        return nil
    }

    /// MIME type for an upload, from its extension.
    public static func mimeType(forExtension ext: String) -> String {
        switch ext.lowercased() {
        case "jpg", "jpeg": "image/jpeg"
        case "png": "image/png"
        case "heic": "image/heic"
        case "heif": "image/heif"
        case "webp": "image/webp"
        case "gif": "image/gif"
        case "avif": "image/avif"
        case "mov": "video/quicktime"
        case "mp4", "m4v": "video/mp4"
        case "3gp": "video/3gpp"
        case "webm": "video/webm"
        case "mkv": "video/x-matroska"
        default: "application/octet-stream"
        }
    }

    /// The video length the server will check: the trimmed window when there is one.
    public static func effectiveSeconds(duration: Double?, trim: Trim?) -> Double? {
        if let trim { return trim.seconds }
        return duration
    }

    public static func issue(kind: MediaKind, bytes: Int64, durationSeconds: Double?, trim: Trim?, limits: Limits) -> ItemIssue? {
        if bytes > limits.maxUploadBytes { return .tooLarge }
        if kind == .video, let seconds = effectiveSeconds(duration: durationSeconds, trim: trim), seconds > Double(limits.maxVideoSeconds) + 0.5 {
            return .videoTooLong
        }
        return nil
    }

    /// How many more items fit in this send.
    public static func room(alreadyQueued: Int, limits: Limits) -> Int {
        max(0, limits.maxItemsPerSend - alreadyQueued)
    }

    /// Clamp a trim window to [0, duration], at least 3 s (or the whole video when shorter) and at most the
    /// server's maximum video length.
    public static func clampTrim(start: Double, end: Double, duration: Double, maxSeconds: Int) -> Trim {
        let minLen = min(minVideoSeconds, duration)
        let maxLen = min(Double(maxSeconds), duration)
        var s = max(0, min(start, duration - minLen))
        var e = min(duration, max(end, s + minLen))
        if e - s > maxLen {
            // Keep whichever edge moved last steady: prefer the start.
            e = s + maxLen
        }
        if e - s < minLen { s = max(0, e - minLen) }
        return Trim(startMs: Int((s * 1000).rounded()), endMs: Int((e * 1000).rounded()))
    }

    public enum Blocker: Equatable, Sendable {
        /// `noFrames`: no recipient at all (neither a frame nor a feed). `tooManyFeeds`: more feeds than a send takes.
        case noItems, noFrames, issues(Int), captionTooLong, overLimit(Int), tooManyFeeds(Int)
    }

    /// Why the send button is disabled, if it is. A feed counts as one recipient, however many frames follow it.
    public static func blocker(itemCount: Int, frameCount: Int, feedCount: Int = 0, issues: Int, longestCaption: Int, limits: Limits) -> Blocker? {
        if itemCount == 0 { return .noItems }
        if itemCount > limits.maxItemsPerSend { return .overLimit(limits.maxItemsPerSend) }
        if issues > 0 { return .issues(issues) }
        if longestCaption > Format.captionMax { return .captionTooLong }
        return Recipients.problem(frameCount: frameCount, feedCount: feedCount)
    }
}

/// Framing maths shared with the web app's FocalPicker.
public enum Framing {
    /// Relative offset (0…1 along the overflowing axis) that centres the focal point when an image of
    /// `imageAspect` is scaled to cover a box of `boxAspect`, clamped so no empty space shows. This is how the
    /// frame crops in fill mode (web: coverPosition).
    public static func coverPosition(focal: CGPoint, imageAspect: Double, boxAspect: Double) -> CGPoint {
        let scaleW = imageAspect >= boxAspect ? imageAspect / boxAspect : 1
        let scaleH = imageAspect >= boxAspect ? 1 : boxAspect / imageAspect
        func axis(_ f: Double, _ size: Double) -> Double {
            if size <= 1.0001 { return 0.5 }
            let t = min(0, max(1 - size, 0.5 - f * size))
            return t / (1 - size)
        }
        return CGPoint(x: axis(focal.x, scaleW), y: axis(focal.y, scaleH))
    }

    public static func clampFocal(_ p: CGPoint) -> CGPoint {
        func r(_ v: Double) -> Double { (min(1, max(0, v)) * 1000).rounded() / 1000 }
        return CGPoint(x: r(p.x), y: r(p.y))
    }
}

/// README §6: reconnect with exponential back-off from 1 s up to 60 s, ±20 % jitter. `random` in [0, 1).
public func backoffDelay(attempt: Int, random: Double = Double.random(in: 0..<1)) -> TimeInterval {
    let base = min(60.0, pow(2, Double(max(0, attempt))))
    return base * (1 + (random * 2 - 1) * 0.2)
}
