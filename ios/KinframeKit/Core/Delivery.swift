import Foundation

/// Delivery state as the family sees it, mirroring web/src/lib/logic.ts.
public enum Stage: String, Sendable, CaseIterable, Comparable {
    case failed, processing, onItsWay = "on_its_way", delivered, seen, removed

    var rank: Int {
        switch self {
        case .failed: 0
        case .processing: 1
        case .onItsWay: 2
        case .delivered: 3
        case .seen: 4
        case .removed: 5
        }
    }

    public static func < (a: Stage, b: Stage) -> Bool { a.rank < b.rank }

    public init(status: PlacementStatus, itemStatus: Item.StatusPayload? = nil) {
        if itemStatus == .failed || status == .failed { self = .failed; return }
        if status == .removed { self = .removed; return }
        if itemStatus == .processing { self = .processing; return }
        switch status {
        case .pending: self = .processing
        case .ready: self = .onItsWay
        case .delivered: self = .delivered
        case .seen: self = .seen
        case .failed: self = .failed
        case .removed: self = .removed
        }
    }

    /// The headline stage for a group: the least-advanced live stage (failures win so they are noticed).
    public static func overall(_ stages: [Stage]) -> Stage {
        stages.filter { $0 != .removed }.min() ?? .removed
    }

    /// 0…1 progress of a group (processing 0, on its way 1/3, delivered 2/3, seen 1).
    public static func progress(_ stages: [Stage]) -> Double {
        let live = stages.filter { $0 != .removed && $0 != .failed }
        guard !live.isEmpty else { return 0 }
        let sum = live.reduce(0.0) { total, stage in
            switch stage {
            case .onItsWay: total + 1.0 / 3
            case .delivered: total + 2.0 / 3
            case .seen: total + 1
            default: total
            }
        }
        return sum / Double(live.count)
    }
}

public struct StageCounts: Equatable, Sendable {
    public var processing = 0, onItsWay = 0, delivered = 0, seen = 0, failed = 0, removed = 0, total = 0

    public init(_ stages: [Stage] = []) {
        for stage in stages {
            switch stage {
            case .processing: processing += 1
            case .onItsWay: onItsWay += 1
            case .delivered: delivered += 1
            case .seen: seen += 1
            case .failed: failed += 1
            case .removed: removed += 1
            }
            total += 1
        }
    }

    /// Delivered or seen: the frame has the item.
    public var arrived: Int { delivered + seen }
}

public struct ReactionCount: Equatable, Sendable, Identifiable {
    public var reaction: Reaction
    public var count: Int
    public var id: Reaction { reaction }
}

public struct FrameDelivery: Equatable, Sendable, Identifiable {
    public var frameId: String
    public var frameName: String
    public var online: Bool
    public var stage: Stage
    public var counts: StageCounts
    public var progress: Double
    public var reactions: [Reaction?]
    public var replies: [ReplyPreset]
    public var id: String { frameId }
}

public enum Delivery {
    /// [love: 2, wow: 1] in a stable order.
    public static func tally(_ reactions: [Reaction?]) -> [ReactionCount] {
        let order: [Reaction] = [.love, .laugh, .wow, .sad]
        return order.compactMap { r in
            let n = reactions.filter { $0 == r }.count
            return n > 0 ? ReactionCount(reaction: r, count: n) : nil
        }
    }

    /// Per-frame delivery summary of a send, for the history cards.
    public static func frames(of send: Send) -> [FrameDelivery] {
        let itemStatus = Dictionary(send.items.map { ($0.id, $0.status) }, uniquingKeysWith: { a, _ in a })
        return send.frames.map { f in
            let stages = f.placements.map { Stage(status: $0.status, itemStatus: itemStatus[$0.itemId]) }
            return FrameDelivery(
                frameId: f.frameId,
                frameName: f.frameName,
                online: f.online ?? false,
                stage: Stage.overall(stages),
                counts: StageCounts(stages),
                progress: Stage.progress(stages),
                reactions: f.placements.map(\.reaction),
                replies: f.placements.flatMap { $0.replies ?? [] }
            )
        }
    }

    /// All stages of a send across frames.
    public static func stages(of send: Send) -> [Stage] {
        let itemStatus = Dictionary(send.items.map { ($0.id, $0.status) }, uniquingKeysWith: { a, _ in a })
        return send.frames.flatMap { f in f.placements.map { Stage(status: $0.status, itemStatus: itemStatus[$0.itemId]) } }
    }

    /// Apply a live reaction event to a send. Returns true if something changed.
    @discardableResult
    public static func apply(reaction: Reaction?, placementId: String, to send: inout Send) -> Bool {
        for fi in send.frames.indices {
            if let pi = send.frames[fi].placements.firstIndex(where: { $0.placementId == placementId }) {
                send.frames[fi].placements[pi].reaction = reaction
                return true
            }
        }
        return false
    }

    /// Apply a live quick-reply event to a send. Returns true if something changed.
    @discardableResult
    public static func apply(reply: ReplyPreset, placementId: String, to send: inout Send) -> Bool {
        for fi in send.frames.indices {
            if let pi = send.frames[fi].placements.firstIndex(where: { $0.placementId == placementId }) {
                send.frames[fi].placements[pi].replies = (send.frames[fi].placements[pi].replies ?? []) + [reply]
                return true
            }
        }
        return false
    }

    public static func kindCounts(_ items: [Item]) -> (photos: Int, videos: Int) {
        let videos = items.filter { $0.kind == .video }.count
        return (items.count - videos, videos)
    }
}
