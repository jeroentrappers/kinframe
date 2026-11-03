import Foundation

public typealias TvLink = Components.Schemas.TvLink
public typealias TvLinkCreate = Components.Schemas.TvLinkCreate
public typealias TvSlide = Components.Schemas.TvSlide
public typealias TvSlideshow = Components.Schemas.TvSlideshow

extension Components.Schemas.TvLink: Identifiable {}

/// What to show on a TV: a selection, one album or the whole visible library (web: lib/tv.ts).
public enum TvScope: Equatable, Hashable, Sendable {
    case all
    case album(id: String, name: String)
    case selection([String])
}

/// Showing a frame's library on a TV (product spec 4.11), free of UI so it can be unit tested.
public enum TvLogic {
    /// Slide intervals offered, in seconds.
    public static let intervals = [5, 10, 15, 30, 60]
    public static let defaultInterval = 10
    /// The server takes at most this many placements in a link.
    public static let maxPlacements = 500

    public enum Purpose: String, Sendable {
        /// A link opened in the TV's browser.
        case tv
        /// This app shows the slideshow itself (AirPlay); the link lives as long as the session.
        case cast
        /// The TV shows what the frame shows, live.
        case mirror
    }

    public static func linkBody(scope: TvScope, interval: Int, purpose: Purpose) -> TvLinkCreate {
        var body = TvLinkCreate()
        body.intervalSeconds = interval
        body.purpose = TvLinkCreate.PurposePayload(rawValue: purpose.rawValue)
        body.expiresInHours = purpose == .cast ? 6 : 12
        // A mirror shows whatever the frame shows: it has no scope of its own.
        guard purpose != .mirror else { return body }
        switch scope {
        case .all: break
        case .album(let id, _): body.albumId = id
        case .selection(let ids): body.placementIds = Array(ids.prefix(maxPlacements))
        }
        return body
    }

    /// Wrap-around step through n slides.
    public static func step(_ index: Int, count n: Int, by dir: Int) -> Int {
        guard n > 0 else { return 0 }
        return ((index + dir) % n + n) % n
    }

    /// How long a slide stays up: photos use the interval; videos play to the end (capped at 5 min).
    public static func dwell(_ slide: TvSlide?, interval: Int) -> TimeInterval {
        if let slide, slide.kind == .video, let ms = slide.durationMs, ms > 0 {
            return min(Double(ms + 500), 5 * 60_000) / 1000
        }
        return Double(max(3, interval))
    }

    /// Signed media URLs expire: refetch the slideshow after `refresh_after`.
    public static func needsRefresh(_ show: TvSlideshow, now: Date = Date()) -> Bool {
        now >= show.refreshAfter
    }

    /// After a refetch, stay on the same placement if it is still there.
    public static func keepPlace(previous: String?, in next: [TvSlide], fallback: Int) -> Int {
        if let previous, let i = next.firstIndex(where: { $0.placementId == previous }) { return i }
        return next.isEmpty ? 0 : min(max(0, fallback), next.count - 1)
    }

    /// The TV page for a link: the server's `url`, or `<web>/tv/<frameId>?t=<token>`.
    public static func pageURL(_ link: TvLink, web: String?) -> URL? {
        if let url = URL(string: link.url), url.scheme != nil { return url }
        guard let web, var c = URLComponents(string: web) else { return nil }
        c.path = (c.path.hasSuffix("/") ? String(c.path.dropLast()) : c.path) + "/tv/\(link.frameId)"
        c.queryItems = [URLQueryItem(name: "t", value: link.token)]
        return c.url
    }

    /// The second line under a caption: "Oma · 12 May 2024".
    public static func byline(_ s: TvSlide, locale: Locale) -> String {
        let when = s.capturedAt ?? s.receivedAt
        let f = DateFormatter()
        f.locale = locale
        f.setLocalizedDateFormatFromTemplate("dMMMMyyyy")
        return [s.senderName, f.string(from: when)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// The links worth listing as "active" (not this session's cast link), soonest to end last.
    public static func activeLinks(_ links: [TvLink], excluding id: String? = nil, now: Date = Date()) -> [TvLink] {
        links.filter { ($0.purpose == .tv || $0.purpose == .mirror) && $0.id != id && $0.expiresAt > now }
    }

    /// A send on a TV: its live placements on the first frame that has any (web: history/[id] tvFrame).
    public static func sendOnTv(_ send: Send) -> (frameId: String, frameName: String, placementIds: [String])? {
        let ready = Set(send.items.filter { $0.status == .ready }.map(\.id))
        for f in send.frames {
            let ids = f.placements.filter { ready.contains($0.itemId) && $0.status != .removed && $0.status != .failed }.map(\.placementId)
            if !ids.isEmpty { return (f.frameId, f.frameName, ids) }
        }
        return nil
    }
}
