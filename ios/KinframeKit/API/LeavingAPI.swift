import Foundation
import OpenAPIRuntime

/// Leaving a family or frame with a choice, taking photos away, asking to keep and the memories filter (tag
/// `leaving`, README §5.21), as one-line calls for the screens. Errors arrive as `APIError` like every `call`.
extension API {
    // MARK: Leaving

    /// `GET /v1/me/leave-preview?feed_id=` or `?frame_id=`: the numbers and checkboxes of the leave sheet.
    public func leavePreview(_ scope: LeavingScope) async throws -> LeavePreview {
        try await call {
            try await $0.getLeavePreview(query: .init(feedId: scope.feedId, frameId: scope.frameId)).ok.body.json
        }
    }

    /// `POST /v1/feeds/{id}/leave`. With `photos` the server answers with the result; without it (a server without
    /// `leaving`) it answers `204` and this returns nil.
    public func leaveFeed(_ id: String, photos: PhotosChoice?, comments: CommentsChoice? = nil,
                          leaveFrameIds: [String] = []) async throws -> TakeAwayResult? {
        let body = Components.Schemas.LeaveFeedRequest(photos: photos, comments: photos == nil ? nil : comments,
                                                       leaveFrameIds: photos == nil || leaveFrameIds.isEmpty ? nil : leaveFrameIds)
        return try await call { (client: Client) async throws -> TakeAwayResult? in
            switch try await client.leaveFeed(path: .init(feedId: id), body: .json(body)) {
            case .ok(let ok): return try ok.body.json
            case .noContent: return nil
            default: throw APIError(status: 0, code: "unexpected")
            }
        }
    }

    /// `POST /v1/frames/{id}/leave {photos}` (nil `photos`: the older `remove_my_items`).
    public func leaveFrame(_ id: String, photos: PhotosChoice?, removeMyItems: Bool = false) async throws -> TakeAwayResult? {
        let body = Components.Schemas.LeaveFrameRequest(photos: photos, removeMyItems: photos == nil ? removeMyItems : nil)
        return try await call { (client: Client) async throws -> TakeAwayResult? in
            switch try await client.leaveFrame(path: .init(frameId: id), body: .json(body)) {
            case .ok(let ok): return try ok.body.json
            case .noContent: return nil
            default: throw APIError(status: 0, code: "unexpected")
            }
        }
    }

    /// Families and frames you left (or were removed from) that still show photos you sent.
    public func formerPlaces() async throws -> FormerPlaces {
        try await call { try await $0.listFormerPlaces().ok.body.json }
    }

    /// Take your own photos away later (also as a current member), with the 30-day notice.
    public func takeAway(_ scope: LeavingScope, comments: CommentsChoice? = nil) async throws -> TakeAwayResult {
        let key = UUID().uuidString.lowercased()
        switch scope {
        case .feed(let id):
            return try await call {
                try await $0.takeAwayFromFeed(path: .init(feedId: id), headers: .init(idempotencyKey: key),
                                              body: .json(.init(comments: comments))).ok.body.json
            }
        case .frame(let id):
            return try await call {
                try await $0.takeAwayFromFrame(path: .init(frameId: id), headers: .init(idempotencyKey: key)).ok.body.json
            }
        }
    }

    // MARK: Taken away and keep requests

    /// The takings still running in a frame's trash (managers) or a family (owner and admins).
    public func takenAway(_ scope: LeavingScope) async throws -> [TakenAwayGroup] {
        switch scope {
        case .feed(let id): try await call { try await $0.listFeedTakenAway(path: .init(feedId: id)).ok.body.json.groups }
        case .frame(let id): try await call { try await $0.listFrameTakenAway(path: .init(frameId: id)).ok.body.json.groups }
        }
    }

    /// The placements of one taking in a frame's trash (`visibility=taken_away&taking_id=`).
    public func takenAwayPlacements(frameId: String, takingId: String, cursor: String? = nil) async throws -> (placements: [Placement], next: String?) {
        let page = try await call {
            try await $0.listPlacements(path: .init(frameId: frameId),
                                        query: .init(cursor: cursor, limit: 200, visibility: .takenAway, takingId: takingId)).ok.body.json
        }
        return (page.placements, page.nextCursor)
    }

    /// "Ask Tom to keep them": `itemIds` nil asks for all of the taking in that scope.
    public func askToKeep(_ scope: LeavingScope, takingId: String, itemIds: [String]?, note: String?) async throws -> KeepRequest {
        let key = UUID().uuidString.lowercased()
        let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = Components.Schemas.KeepRequestCreate(takingId: takingId, frameId: scope.frameId, feedId: scope.feedId,
                                                        itemIds: itemIds?.isEmpty == false ? itemIds : nil,
                                                        note: trimmed?.isEmpty == false ? String(trimmed!.prefix(300)) : nil)
        return try await call { try await $0.createKeepRequest(headers: .init(idempotencyKey: key), body: .json(body)).created.body.json }
    }

    /// Requests sent to you (open ones by default).
    public func myKeepRequests(all: Bool = false) async throws -> [KeepRequest] {
        try await call { try await $0.listMyKeepRequests(query: .init(status: all ? .all : .open)).ok.body.json.requests }
    }

    /// Requests made for a frame or family (its managers).
    public func keepRequests(_ scope: LeavingScope) async throws -> [KeepRequest] {
        switch scope {
        case .feed(let id): try await call { try await $0.listFeedKeepRequests(path: .init(feedId: id)).ok.body.json.requests }
        case .frame(let id): try await call { try await $0.listFrameKeepRequests(path: .init(frameId: id)).ok.body.json.requests }
        }
    }

    /// One request with the photos asked for.
    public func keepRequest(_ id: String) async throws -> KeepRequest {
        try await call { try await $0.getKeepRequest(path: .init(requestId: id)).ok.body.json }
    }

    /// Allow all, some (`itemIds`) or none. Final.
    public func answerKeepRequest(_ id: String, _ answer: KeepAnswer) async throws -> KeepRequest {
        let body: Components.Schemas.KeepRequestAnswer = switch answer {
        case .all: .init(allow: .all)
        case .none: .init(allow: .none)
        case .some(let ids): .init(allow: .some, itemIds: ids)
        }
        return try await call { try await $0.answerKeepRequest(path: .init(requestId: id), body: .json(body)).ok.body.json }
    }

    // MARK: Memories

    public func memoryPreferences() async throws -> MemoryPreferences {
        try await call { try await $0.getMemoryPreferences().ok.body.json }
    }

    /// Replaces the lists given (nil leaves one unchanged).
    public func updateMemoryPreferences(hiddenPeople: [String]? = nil, hiddenMemories: [String]? = nil) async throws -> MemoryPreferences {
        try await call {
            try await $0.updateMemoryPreferences(body: .json(.init(hiddenPeople: hiddenPeople, hiddenMemories: hiddenMemories))).ok.body.json
        }
    }

    // MARK: Account

    /// `DELETE /v1/me {photos}`; the older `remove_items` too, so a server without `leaving` does the same.
    public func deleteAccount(photos: AccountPhotos) async throws {
        _ = try await call {
            try await $0.deleteMe(query: .init(removeItems: photos == .take),
                                  body: .json(.init(photos: photos == .take ? .take : .leave))).noContent
        }
    }
}
