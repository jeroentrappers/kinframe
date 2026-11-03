import Foundation

/// "Share to another frame" (README §5.3 "Sharing between frames"): the decisions, free of networking so they can
/// be unit tested. Mirrors the web and Android apps.
public enum Sharing {
    /// Photos on one frame to share to others: the frame they are on and their placements there.
    public struct Source: Equatable, Sendable {
        public var frameId: String
        public var placementIds: [String]

        public init(frameId: String, placementIds: [String]) {
            self.frameId = frameId
            self.placementIds = placementIds
        }
    }

    /// How much of the selection a frame already has.
    public enum Coverage: Equatable, Sendable {
        case none, some, all
    }

    /// Per frame, how much of a `selectionSize`-item selection it already holds, from a dry run's skips.
    public static func coverage(skipped: [(placementId: String, frameId: String)], frameIds: [String], selectionSize: Int) -> [String: Coverage] {
        var have: [String: Set<String>] = [:]
        for s in skipped { have[s.frameId, default: []].insert(s.placementId) }
        var out: [String: Coverage] = [:]
        for id in frameIds {
            let n = have[id]?.count ?? 0
            out[id] = n == 0 ? Coverage.none : n >= selectionSize ? .all : .some
        }
        return out
    }

    /// Frames you can send to, except the source.
    public static func targets(_ frames: [FrameSummary], excluding sourceFrameId: String) -> [FrameSummary] {
        frames.filter { $0.id != sourceFrameId && $0.can(\.send) }
    }

    /// The first frame of a send that still holds every one of `itemIds`, with those placements.
    public static func source(of send: Send, itemIds: [String]) -> Source? {
        guard !itemIds.isEmpty else { return nil }
        for f in send.frames {
            let ids = itemIds.map { item in
                f.placements.first { $0.itemId == item && $0.status != .removed && $0.status != .failed }?.placementId
            }
            if ids.allSatisfy({ $0 != nil }) { return Source(frameId: f.frameId, placementIds: ids.compactMap { $0 }) }
        }
        return nil
    }

    /// The items of a send that can be shared: everything that didn't fail.
    public static func shareableItems(of send: Send) -> [String] {
        send.items.filter { $0.status != .failed }.map(\.id)
    }

    /// Members who shared a send's photos to this frame ("Shared by …" per frame in History).
    public static func sharedBy(_ frame: SendFrame) -> [String] {
        var seen: [String] = []
        for p in frame.placements where p.status != .removed {
            if let name = p.sharedByName, !seen.contains(name) { seen.append(name) }
        }
        return seen
    }
}
