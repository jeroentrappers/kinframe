import Foundation
import OpenAPIRuntime

/// The timeline, threads, reactions and circles (tags `social` and `circles` in openapi.yaml), as one-line calls for
/// the screens. Errors arrive as `APIError` like every `call`.
extension API {
    // MARK: Timeline and posts

    /// One page of the home timeline (or of one family, circle, prompt or frame), newest first.
    public func timeline(before: String? = nil, limit: Int = 20, feedId: String? = nil, circleId: String? = nil,
                         promptId: String? = nil, frameId: String? = nil) async throws -> TimelinePage {
        try await call {
            try await $0.getTimeline(query: .init(before: before, limit: limit, feedId: feedId, circleId: circleId,
                                                  promptId: promptId, frameId: frameId)).ok.body.json
        }
    }

    public func post(_ postId: String) async throws -> TimelinePost {
        try await call { try await $0.getPost(path: .init(postId: postId)).ok.body.json }
    }

    // MARK: Comments

    public func comments(_ postId: String, after: String? = nil, limit: Int = 100) async throws -> CommentPage {
        try await call { try await $0.listComments(path: .init(postId: postId), query: .init(after: after, limit: limit)).ok.body.json }
    }

    /// A comment, or a reply (`parentId` = the top comment; `quotedUserId` for a reply to a reply).
    public func createComment(_ postId: String, body: String, mentions: [Mention], parentId: String? = nil,
                              quotedUserId: String? = nil) async throws -> Comment {
        let key = UUID().uuidString.lowercased()
        let payload = Components.Schemas.CommentCreate(body: body, parentId: parentId, quotedUserId: quotedUserId,
                                                       mentions: mentions.isEmpty ? nil : mentions)
        return try await call {
            try await $0.createComment(path: .init(postId: postId), headers: .init(idempotencyKey: key), body: .json(payload)).created.body.json
        }
    }

    public func updateComment(_ postId: String, commentId: String, body: String, mentions: [Mention]) async throws -> Comment {
        try await call {
            try await $0.updateComment(path: .init(postId: postId, commentId: commentId),
                                       body: .json(.init(body: body, mentions: mentions))).ok.body.json
        }
    }

    public func deleteComment(_ postId: String, commentId: String) async throws {
        _ = try await call { try await $0.deleteComment(path: .init(postId: postId, commentId: commentId)).noContent }
    }

    public func setThreadState(_ postId: String, open: Bool) async throws -> ThreadSummary {
        try await call { try await $0.setThreadState(path: .init(postId: postId), body: .json(.init(state: open ? .open : .closed))).ok.body.json }
    }

    public func markThreadRead(_ postId: String, upTo commentId: String) async throws {
        _ = try await call { try await $0.markThreadRead(path: .init(postId: postId), body: .json(.init(upTo: commentId))).noContent }
    }

    public func setThreadMute(_ postId: String, muted: Bool) async throws {
        _ = try await call { try await $0.setThreadMute(path: .init(postId: postId), body: .json(.init(muted: muted))).noContent }
    }

    /// Tag a post as an answer to a weekly prompt, or untag it (null needs raw JSON).
    public func tagPostPrompt(_ postId: String, promptId: String?) async throws -> TimelinePost {
        let payload = try JSONEncoder().encode(JSONValue.object(["prompt_id": promptId.map { .string($0) } ?? .null]))
        let data = try await raw("PUT", "v1/posts/\(postId)/prompt", body: payload)
        return try OutboxRunner.decoder.decode(TimelinePost.self, from: data)
    }

    // MARK: Reactions

    public func reactions(_ kind: ReactionTargetKind, _ targetId: String, postId: String? = nil) async throws -> ReactionList {
        try await call {
            try await $0.listReactions(path: .init(targetKind: kind, targetId: targetId), query: .init(postId: postId)).ok.body.json
        }
    }

    /// The caller's reaction on a post, one item of a post, a comment or a message; nil removes it. Sent as raw JSON
    /// because removing needs an explicit `"emoji": null`.
    public func setReaction(_ kind: ReactionTargetKind, _ targetId: String, emoji: PersonReaction?, postId: String? = nil) async throws -> ReactionSummary {
        let payload = try JSONEncoder().encode(JSONValue.object(["emoji": emoji.map { .string($0.rawValue) } ?? .null]))
        var path = "v1/reactions/\(kind.rawValue)/\(targetId)"
        if let postId { path += "?post_id=\(postId)" }
        let data = try await raw("PUT", path, body: payload)
        return try OutboxRunner.decoder.decode(ReactionSummary.self, from: data)
    }

    // MARK: Circles

    public func circles(_ feedId: String) async throws -> [FamilyCircle] {
        try await call { try await $0.listCircles(path: .init(feedId: feedId)).ok.body.json }
    }

    public func circle(_ feedId: String, circleId: String) async throws -> FamilyCircle {
        try await call { try await $0.getCircle(path: .init(feedId: feedId, circleId: circleId)).ok.body.json }
    }

    public func createCircle(_ feedId: String, name: String, memberIds: [String], frameIds: [String] = []) async throws -> FamilyCircle {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.CircleCreate(name: String(name.prefix(CircleLogic.maxName)), memberIds: memberIds,
                                                   frameIds: frameIds.isEmpty ? nil : frameIds)
        return try await call {
            try await $0.createCircle(path: .init(feedId: feedId), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
        }
    }

    public func updateCircle(_ feedId: String, circleId: String, name: String? = nil, frameIds: [String]? = nil) async throws -> FamilyCircle {
        try await call {
            try await $0.updateCircle(path: .init(feedId: feedId, circleId: circleId),
                                      body: .json(.init(name: name.map { String($0.prefix(CircleLogic.maxName)) }, frameIds: frameIds))).ok.body.json
        }
    }

    public func deleteCircle(_ feedId: String, circleId: String) async throws {
        _ = try await call { try await $0.deleteCircle(path: .init(feedId: feedId, circleId: circleId)).noContent }
    }

    public func addCircleMembers(_ feedId: String, circleId: String, userIds: [String]) async throws -> FamilyCircle {
        try await call {
            try await $0.addCircleMembers(path: .init(feedId: feedId, circleId: circleId), body: .json(.init(userIds: userIds))).ok.body.json
        }
    }

    public func setCircleRole(_ feedId: String, circleId: String, userId: String, admin: Bool) async throws -> FamilyCircle {
        try await call {
            try await $0.updateCircleMember(path: .init(feedId: feedId, circleId: circleId, userId: userId),
                                            body: .json(.init(role: admin ? .admin : .member))).ok.body.json
        }
    }

    /// Remove someone, or leave (yourself).
    public func removeCircleMember(_ feedId: String, circleId: String, userId: String) async throws {
        _ = try await call { try await $0.removeCircleMember(path: .init(feedId: feedId, circleId: circleId, userId: userId)).noContent }
    }

    // MARK: Families (feeds) in family social

    /// `admin`, `poster` or `member` (owner and admins; admins only change posters and members).
    public func setFeedMemberRole(_ feedId: String, userId: String, role: FeedRole) async throws -> FeedMember {
        let r: Operations.UpdateFeedMember.Input.Body.JsonPayload.RolePayload = switch role {
        case .admin: .admin
        case .member: .member
        case .poster, .owner: .poster
        }
        return try await call { try await $0.updateFeedMember(path: .init(feedId: feedId, userId: userId), body: .json(.init(role: r))).ok.body.json }
    }

    /// The new family settings of family social.
    public func updateFeedSocial(_ feedId: String, membersSeeReceipts: Bool? = nil, newMembersCanPost: Bool? = nil,
                                 membersCanCreateCircles: Bool? = nil, membersSeeReactions: Bool? = nil) async throws -> Feed {
        let body = Components.Schemas.FeedUpdate(membersSeeReactions: membersSeeReactions, membersSeeReceipts: membersSeeReceipts,
                                                 newMembersCanPost: newMembersCanPost, membersCanCreateCircles: membersCanCreateCircles)
        return try await call { try await $0.updateFeed(path: .init(feedId: feedId), body: .json(body)).ok.body.json }
    }

    /// The caller's own mute for one family: posts, chat, everything or none.
    public func setFeedMute(_ feedId: String, _ mute: FeedMute) async throws -> Feed {
        try await call { try await $0.updateFeedPreferences(path: .init(feedId: feedId), body: .json(.init(mute: mute))).ok.body.json }
    }
}
