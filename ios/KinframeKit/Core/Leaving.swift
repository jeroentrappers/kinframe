import Foundation

// Leaving a family or frame (docs/spec/leaving.md, README §5.21): short names for the generated schemas and the pure
// rules the screens share (defaults, texts, push routes, websocket events). Unit-tested in LeavingTests.

public typealias LeavePreview = Components.Schemas.LeavePreview
public typealias LeavePreviewFrame = Components.Schemas.LeavePreview.FramesPayloadPayload
public typealias TakeAwayResult = Components.Schemas.TakeAwayResult
public typealias FormerPlaces = Components.Schemas.FormerPlaces
public typealias TakenAwayGroup = Components.Schemas.TakenAwayGroup
public typealias KeepRequest = Components.Schemas.KeepRequest
public typealias KeepRequestStatus = Components.Schemas.KeepRequestStatus
public typealias MemoryPreferences = Components.Schemas.MemoryPreferences
public typealias PhotosChoice = Components.Schemas.PhotosChoice
public typealias CommentsChoice = Components.Schemas.CommentsChoice

extension Components.Schemas.TakenAwayGroup: Identifiable {
    public var id: String { takingId }
}

extension Components.Schemas.KeepRequest: Identifiable {}

/// A family (`feed`) or a frame: where photos are left, taken away or asked to keep.
public enum LeavingScope: Hashable, Sendable {
    case feed(String)
    case frame(String)

    public var feedId: String? { if case .feed(let id) = self { id } else { nil } }
    public var frameId: String? { if case .frame(let id) = self { id } else { nil } }
}

/// The answer to a keep request.
public enum KeepAnswer: Equatable, Sendable {
    case all
    case some([String])
    case none
}

/// Account deletion: take the photos (default) or leave them to the families.
public enum AccountPhotos: String, CaseIterable, Sendable {
    case take, leave
}

/// The leave sheet's choices with the spec's defaults (decision A): photos stay, comments stay, and the frames that
/// get this family's photos (and that the leaver doesn't own) are left too.
public struct LeaveChoices: Equatable, Sendable {
    public var photos: PhotosChoice = .keep
    public var comments: CommentsChoice = .keep
    public var leaveFrameIds: Set<String> = []

    public init(photos: PhotosChoice = .keep, comments: CommentsChoice = .keep, leaveFrameIds: Set<String> = []) {
        self.photos = photos
        self.comments = comments
        self.leaveFrameIds = leaveFrameIds
    }

    public init(preview: LeavePreview) {
        leaveFrameIds = Set(Self.leavableFrames(preview).filter(\.suggested).map(\.frameId))
    }

    /// Frames offered as "Leave this frame too": not the ones the leaver owns (they transfer first).
    public static func leavableFrames(_ preview: LeavePreview) -> [LeavePreviewFrame] {
        (preview.frames ?? []).filter { $0.canLeave != false && $0.role != .owner }
    }

    /// Only frames still offered go into the request.
    public func frameIds(for preview: LeavePreview) -> [String] {
        Self.leavableFrames(preview).map(\.frameId).filter(leaveFrameIds.contains)
    }
}

public enum LeavingText {
    /// "19 December" (the day the frames' trash deletes what nobody asked to keep).
    public static func day(_ date: Date, locale: Locale = L10n.locale) -> String {
        date.formatted(.dateTime.day().month(.wide).locale(locale))
    }

    /// The line after leaving or taking away: "214 photos were taken away from 2 frames. They stay in the frames'
    /// trash until 19 December." or, when nothing was taken away, nil.
    public static func result(_ r: TakeAwayResult, locale: Locale = L10n.locale) -> String? {
        guard r.photosTakenAway > 0 else { return nil }
        let frames = r.frames.count + (r.remoteFrames ?? 0)
        var line = frames > 0
            ? Lp("leaving.result.photosFrames", count: r.photosTakenAway, ["frames": frames])
            : Lp("leaving.result.photos", count: r.photosTakenAway)
        if let until = r.until { line += " " + L("leaving.result.until", ["date": day(until, locale: locale)]) }
        if r.commentsDeleted > 0 { line += " " + Lp("leaving.result.comments", count: r.commentsDeleted) }
        return line
    }

    /// "Taken away by Tom" with "214 photos · until 19 December".
    public static func groupTitle(_ g: TakenAwayGroup) -> String {
        L("leaving.group.title", ["name": g.byName])
    }

    public static func groupDetail(_ g: TakenAwayGroup, locale: Locale = L10n.locale) -> String {
        Lp("leaving.group.count", count: g.count) + " · " + L("leaving.group.until", ["date": day(g.until, locale: locale)])
    }

    /// Why "Ask to keep" isn't offered, or nil when it is (or a request already exists: its status says it).
    public static func cannotAsk(_ g: TakenAwayGroup) -> String? {
        guard !g.canAsk, g.keepRequest == nil else { return nil }
        switch g.cannotAskReason {
        case .blocked: return L("leaving.cannotAsk.blocked", ["name": g.byName])
        case .expired: return L("leaving.cannotAsk.expired")
        case .accountDeleted: return L("leaving.cannotAsk.accountDeleted", ["name": g.byName])
        case .alreadyAsked, .notAllowed, nil: return nil
        }
    }

    /// The asker's view of a request: "Asked 3 June · waiting for Tom", "Tom allowed 12 of 12".
    public static func requestStatus(_ r: KeepRequest, person: String) -> String {
        switch r.status {
        case .open: L("leaving.request.open", ["name": person])
        case .allowed: L("leaving.request.allowed", ["name": person])
        case .partlyAllowed: L("leaving.request.partly", ["name": person, "allowed": r.allowedCount ?? 0, "count": r.itemCount])
        case .declined: L("leaving.request.declined", ["name": person])
        case .expired: L("leaving.request.expired")
        }
    }

    /// The asked person's view: "Lies asks to keep 12 photos on Oma Janssens's frame."
    public static func requestToMe(_ r: KeepRequest) -> String {
        let place = r.frameName ?? r.feedName ?? ""
        return Lp(r.scope == .frame ? "leaving.toMe.frame" : "leaving.toMe.feed", count: r.itemCount,
                  ["name": r.askedByName, "place": place])
    }

    /// "Tom (former member)" for photos handed over at account deletion.
    public static func formerSender(_ item: Item) -> String? {
        guard let name = item.formerSenderName?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        return L("leaving.formerMember", ["name": name])
    }

    /// The read-only line of a direct chat with someone you no longer share a family or frame with.
    public static func readOnlyLine(_ c: Conversation) -> String? {
        guard c.readOnly == true, c.readOnlyReason == .noLongerContacts else { return nil }
        let name = ChatList.title(c)
        return L("leaving.chat.noLongerContacts", ["name": name])
    }
}

// MARK: Push

/// Where a leaving notification leads (README §6.3, `LeavingPushKey`).
public enum LeavingRoute: Equatable, Sendable {
    /// `photos_taken_away`: the frame's trash (its taken-away groups) or the family's.
    case takenAway(frameId: String?, feedId: String?, takingId: String?)
    /// `keep_request`: the request to answer; `keep_answered`: the asker's frame or family.
    case keepRequest(requestId: String)
    case keepAnswered(requestId: String?, frameId: String?, feedId: String?)
    /// `ownership_changed`: the frame or family.
    case ownership(frameId: String?, feedId: String?)

    public static func route(key: String, id: (String) -> String?) -> LeavingRoute? {
        switch key {
        case "photos_taken_away":
            let frame = id("frame_id"), feed = id("feed_id")
            guard frame != nil || feed != nil else { return nil }
            return .takenAway(frameId: frame, feedId: feed, takingId: id("taking_id"))
        case "keep_request":
            return id("request_id").map { .keepRequest(requestId: $0) }
        case "keep_answered":
            let frame = id("frame_id"), feed = id("feed_id"), request = id("request_id")
            guard frame != nil || feed != nil || request != nil else { return nil }
            return .keepAnswered(requestId: request, frameId: frame, feedId: feed)
        case "ownership_changed":
            let frame = id("frame_id"), feed = id("feed_id")
            guard frame != nil || feed != nil else { return nil }
            return .ownership(frameId: frame, feedId: feed)
        default:
            return nil
        }
    }
}

// MARK: Websocket

/// Leaving's websocket events (README §6.2).
public enum LeavingEvent: Equatable, Sendable {
    public enum PlacementReason: String, Sendable {
        case takenAway = "taken_away", restored, purged
    }

    /// Placements were taken away by their sender, came back after a keep request, or were purged after 30 days.
    case placementUpdated(frameId: String, placementIds: [String], reason: PlacementReason?, takingId: String?)
    /// A keep request was made, answered or expired.
    case keepRequestUpdated(KeepRequest)

    public static func parse(_ type: String, _ obj: [String: Any]) -> LeavingEvent? {
        switch type {
        case "placement.updated":
            guard let frame = obj["frame_id"] as? String else { return nil }
            return .placementUpdated(frameId: frame, placementIds: obj["placement_ids"] as? [String] ?? [],
                                     reason: (obj["reason"] as? String).flatMap(PlacementReason.init(rawValue:)),
                                     takingId: obj["taking_id"] as? String)
        case "keep_request.updated":
            guard let raw = obj["request"], JSONSerialization.isValidJSONObject(raw),
                  let data = try? JSONSerialization.data(withJSONObject: raw),
                  let r = try? OutboxRunner.decoder.decode(KeepRequest.self, from: data) else { return nil }
            return .keepRequestUpdated(r)
        default:
            return nil
        }
    }
}
