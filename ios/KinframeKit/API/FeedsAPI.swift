import Foundation
import OpenAPIRuntime

/// Feeds, members, invites, posts, receipts and subscriptions (tags `feeds` and `subscriptions` in openapi.yaml),
/// as one-line calls for the screens and the share extension. Errors arrive as `APIError` like every `call`.
extension API {
    // MARK: Feeds

    public func myFeeds() async throws -> [FeedSummary] {
        try await call { try await $0.listMyFeeds().ok.body.json }
    }

    public func createFeed(name: String, autoApprove: Bool = false, invitesNeedApproval: Bool = true,
                           membersSeeReactions: Bool = false, postersCanInvite: Bool = false) async throws -> Feed {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.FeedCreate(name: String(name.prefix(40)), autoApproveSubscriptions: autoApprove,
                                                 inviteRequiresApproval: invitesNeedApproval, membersSeeReactions: membersSeeReactions,
                                                 postersCanInvite: postersCanInvite)
        return try await call { try await $0.createFeed(headers: .init(idempotencyKey: key), body: .json(body)).created.body.json }
    }

    public func feed(_ id: String) async throws -> Feed {
        try await call { try await $0.getFeed(path: .init(feedId: id)).ok.body.json }
    }

    /// Rename and settings. The cover goes through `setFeedCover`, which can also clear it.
    public func updateFeed(_ id: String, name: String? = nil, autoApprove: Bool? = nil, invitesNeedApproval: Bool? = nil,
                           membersSeeReactions: Bool? = nil, postersCanInvite: Bool? = nil,
                           postersCanAddBirthdays: Bool? = nil) async throws -> Feed {
        let body = Components.Schemas.FeedUpdate(name: name.map { String($0.prefix(40)) }, autoApproveSubscriptions: autoApprove,
                                                 inviteRequiresApproval: invitesNeedApproval, membersSeeReactions: membersSeeReactions,
                                                 postersCanInvite: postersCanInvite, postersCanAddBirthdays: postersCanAddBirthdays)
        return try await call { try await $0.updateFeed(path: .init(feedId: id), body: .json(body)).ok.body.json }
    }

    /// `PATCH /v1/feeds/{id} {cover_item_id}`, sent as raw JSON because removing the cover needs an explicit null,
    /// which the generated `FeedUpdate` leaves out.
    public func setFeedCover(_ id: String, itemId: String?) async throws -> Feed {
        let payload = try JSONEncoder().encode(JSONValue.object(["cover_item_id": itemId.map { .string($0) } ?? .null]))
        let data = try await raw("PATCH", "v1/feeds/\(id)", body: payload)
        return try OutboxRunner.decoder.decode(Feed.self, from: data)
    }

    public func deleteFeed(_ id: String) async throws {
        _ = try await call { try await $0.deleteFeed(path: .init(feedId: id)).noContent }
    }

    public func leaveFeed(_ id: String, removeMyPosts: Bool) async throws {
        _ = try await call { try await $0.leaveFeed(path: .init(feedId: id), body: .json(.init(removeMyPosts: removeMyPosts))).noContent }
    }

    public func transferFeed(_ id: String, to userId: String) async throws {
        _ = try await call { try await $0.transferFeedOwnership(path: .init(feedId: id), body: .json(.init(userId: userId))).noContent }
    }

    // MARK: Members and join requests

    public func feedMembers(_ id: String) async throws -> [FeedMember] {
        try await call { try await $0.listFeedMembers(path: .init(feedId: id)).ok.body.json }
    }

    /// Posters ↔ admins (ownership moves with `transferFeed`).
    public func setFeedRole(_ id: String, userId: String, admin: Bool) async throws -> FeedMember {
        try await call {
            try await $0.updateFeedMember(path: .init(feedId: id, userId: userId), body: .json(.init(role: admin ? .admin : .poster))).ok.body.json
        }
    }

    public func removeFeedMember(_ id: String, userId: String, removePosts: Bool) async throws {
        _ = try await call {
            try await $0.removeFeedMember(path: .init(feedId: id, userId: userId), query: .init(removePosts: removePosts)).noContent
        }
    }

    public func feedJoinRequests(_ id: String) async throws -> [JoinRequest] {
        try await call { try await $0.listFeedJoinRequests(path: .init(feedId: id)).ok.body.json }
    }

    public func decideFeedJoinRequest(_ id: String, requestId: String, approve: Bool) async throws {
        _ = try await call {
            try await $0.decideFeedJoinRequest(path: .init(feedId: id, requestId: requestId, decision: approve ? .approve : .deny)).noContent
        }
    }

    // MARK: Invites

    public func feedInvites(_ id: String) async throws -> [FeedInvite] {
        try await call { try await $0.listFeedInvites(path: .init(feedId: id)).ok.body.json }
    }

    /// `maxUses` nil is unlimited.
    public func createFeedInvite(_ id: String, purpose: FeedInvitePurpose, hours: Int, maxUses: Int?, note: String) async throws -> FeedInvite {
        let key = UUID().uuidString.lowercased()
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = Components.Schemas.FeedInviteCreate(purpose: purpose, maxUses: maxUses, expiresInHours: hours,
                                                       note: trimmed.isEmpty ? nil : String(trimmed.prefix(80)))
        return try await call {
            try await $0.createFeedInvite(path: .init(feedId: id), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
        }
    }

    public func revokeFeedInvite(_ id: String, inviteId: String) async throws {
        _ = try await call { try await $0.revokeFeedInvite(path: .init(feedId: id, inviteId: inviteId)).noContent }
    }

    /// A code from "Enter a code" ("KF7QM2XA" or "KF7Q-M2XA") to its preview, which carries the token.
    public func lookupFeedInvite(code: String) async throws -> FeedInvitePreview {
        try await call { try await $0.lookupFeedInvite(body: .json(.init(code: code))).ok.body.json }
    }

    public func previewFeedInvite(token: String) async throws -> FeedInvitePreview {
        try await call { try await $0.previewFeedInvite(path: .init(token: token)).ok.body.json }
    }

    /// Join invites need no frames; subscribe invites subscribe `frameIds` (pre-approved) with a history option.
    /// `birthdays`: show the feed's birthday people on those frames (default on).
    public func acceptFeedInvite(token: String, frameIds: [String] = [], history: HistoryChoice? = nil, birthdays: Bool = true,
                                 idempotencyKey: String) async throws -> FeedInviteResult {
        let body: Components.Schemas.FeedInviteAccept? = frameIds.isEmpty ? nil
            : .init(frameIds: frameIds, history: history?.history, historyCount: history?.count, birthdays: birthdays ? nil : false)
        return try await call {
            try await $0.acceptFeedInvite(path: .init(token: token), headers: .init(idempotencyKey: idempotencyKey),
                                          body: body.map { .json($0) }).ok.body.json
        }
    }

    // MARK: Posts and receipts

    public func feedPosts(_ id: String, cursor: String? = nil, limit: Int = 20) async throws -> (posts: [FeedPost], next: String?) {
        let page = try await call { try await $0.listFeedPosts(path: .init(feedId: id), query: .init(cursor: cursor, limit: limit)).ok.body.json }
        return (page.posts, page.nextCursor)
    }

    public func feedPost(_ id: String, postId: String) async throws -> FeedPost {
        try await call { try await $0.getFeedPost(path: .init(feedId: id, postId: postId)).ok.body.json }
    }

    /// Unsend (own post) or remove (owner/admin) everywhere.
    public func removeFeedPost(_ id: String, postId: String) async throws {
        _ = try await call { try await $0.removeFeedPost(path: .init(feedId: id, postId: postId)).noContent }
    }

    public func removeFeedPostItem(_ id: String, postId: String, itemId: String) async throws {
        _ = try await call { try await $0.removeFeedPostItem(path: .init(feedId: id, postId: postId, itemId: itemId)).noContent }
    }

    public func feedPostReceipts(_ id: String, postId: String) async throws -> FeedPostReceipts {
        try await call { try await $0.getFeedPostReceipts(path: .init(feedId: id, postId: postId)).ok.body.json }
    }

    /// Post an existing send to feeds (no new upload). Feeds that already have it are skipped by the server.
    public func forwardSend(_ sendId: String, frameIds: [String] = [], feedIds: [String]) async throws -> Send {
        try await call {
            try await $0.forwardSend(path: .init(sendId: sendId), body: .json(.init(frameIds: frameIds, feedIds: feedIds))).accepted.body.json
        }
    }

    // MARK: Subscriptions

    public func feedSubscriptions(_ id: String) async throws -> [FeedSubscription] {
        try await call { try await $0.listFeedSubscriptions(path: .init(feedId: id)).ok.body.json }
    }

    public func frameSubscriptions(_ frameId: String) async throws -> [FeedSubscription] {
        try await call { try await $0.listFrameSubscriptions(path: .init(frameId: frameId)).ok.body.json }
    }

    /// Ask to show a feed on a frame you manage: active at once for feed admins or with auto-approve, else pending.
    /// `birthdays`: show the feed's birthday people on the frame (default on).
    public func requestSubscription(feedId: String, frameId: String, history: HistoryChoice, birthdays: Bool = true) async throws -> FeedSubscription {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.SubscriptionRequest(frameId: frameId, history: history.history, historyCount: history.count,
                                                          birthdays: birthdays ? nil : false)
        return try await call {
            try await $0.requestSubscription(path: .init(feedId: feedId), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
        }
    }

    public func subscription(_ id: String) async throws -> FeedSubscription {
        try await call { try await $0.getSubscription(path: .init(subscriptionId: id)).ok.body.json }
    }

    public func setSubscriptionHistory(_ id: String, _ history: HistoryChoice) async throws -> FeedSubscription {
        let body = Components.Schemas.SubscriptionUpdate(history: history.history, historyCount: history.count)
        return try await call { try await $0.updateSubscription(path: .init(subscriptionId: id), body: .json(body)).ok.body.json }
    }

    /// Unsubscribe, withdraw a pending request, or (feed admins) remove.
    public func removeSubscription(_ id: String) async throws {
        _ = try await call { try await $0.removeSubscription(path: .init(subscriptionId: id)).noContent }
    }

    public func approveSubscription(_ id: String) async throws -> FeedSubscription {
        try await call { try await $0.approveSubscription(path: .init(subscriptionId: id)).ok.body.json }
    }

    public func rejectSubscription(_ id: String) async throws -> FeedSubscription {
        try await call { try await $0.rejectSubscription(path: .init(subscriptionId: id)).ok.body.json }
    }

    public func pauseSubscription(_ id: String) async throws -> FeedSubscription {
        try await call { try await $0.pauseSubscription(path: .init(subscriptionId: id)).ok.body.json }
    }

    public func resumeSubscription(_ id: String) async throws -> FeedSubscription {
        try await call { try await $0.resumeSubscription(path: .init(subscriptionId: id)).ok.body.json }
    }

    // MARK: Birthdays

    /// Every entry the caller may see (approved; suggestions and declined ones for admins and their suggesters).
    public func feedBirthdays(_ id: String) async throws -> [BirthdayPerson] {
        try await call { try await $0.listFeedBirthdays(path: .init(feedId: id)).ok.body.json }
    }

    public func feedBirthday(_ id: String, birthdayId: String) async throws -> BirthdayPerson {
        try await call { try await $0.getFeedBirthday(path: .init(feedId: id, birthdayId: birthdayId)).ok.body.json }
    }

    /// Approved at once for owner, admins and (with `posters_can_add_birthdays`) posters; otherwise a suggestion.
    /// `409 duplicate_birthday` carries the existing entry's id in `detail`.
    public func createFeedBirthday(_ id: String, _ draft: BirthdayDraft) async throws -> BirthdayPerson {
        let key = UUID().uuidString.lowercased()
        return try await call {
            try await $0.createFeedBirthday(path: .init(feedId: id), headers: .init(idempotencyKey: key), body: .json(draft.createBody)).created.body.json
        }
    }

    /// `PATCH` as raw JSON (`BirthdayDraft.patch`): clearing the year and unlinking need explicit nulls.
    public func updateFeedBirthday(_ id: String, birthdayId: String, patch: [String: JSONValue]) async throws -> BirthdayPerson {
        let payload = try JSONEncoder().encode(JSONValue.object(patch))
        let data = try await raw("PATCH", "v1/feeds/\(id)/birthdays/\(birthdayId)", body: payload)
        return try OutboxRunner.decoder.decode(BirthdayPerson.self, from: data)
    }

    /// Remove an entry, withdraw a suggestion or remove yourself. Not reversible: Undo creates it again.
    public func removeFeedBirthday(_ id: String, birthdayId: String) async throws {
        _ = try await call { try await $0.removeFeedBirthday(path: .init(feedId: id, birthdayId: birthdayId)).noContent }
    }

    public func approveFeedBirthday(_ id: String, birthdayId: String) async throws -> BirthdayPerson {
        try await call { try await $0.approveFeedBirthday(path: .init(feedId: id, birthdayId: birthdayId)).ok.body.json }
    }

    public func rejectFeedBirthday(_ id: String, birthdayId: String) async throws -> BirthdayPerson {
        try await call { try await $0.rejectFeedBirthday(path: .init(feedId: id, birthdayId: birthdayId)).ok.body.json }
    }

    /// A square JPEG, like `PUT /v1/me/avatar`; the server stores 256×256 WebP.
    public func setFeedBirthdayPhoto(_ id: String, birthdayId: String, jpeg: Data) async throws -> BirthdayPerson {
        try await call {
            try await $0.putFeedBirthdayPhoto(path: .init(feedId: id, birthdayId: birthdayId), body: .jpeg(HTTPBody(jpeg))).ok.body.json
        }
    }

    /// Back to the linked member's avatar, if any.
    public func removeFeedBirthdayPhoto(_ id: String, birthdayId: String) async throws -> BirthdayPerson {
        try await call { try await $0.deleteFeedBirthdayPhoto(path: .init(feedId: id, birthdayId: birthdayId)).ok.body.json }
    }

    /// Greetings from the frames, newest first (one page).
    public func feedBirthdayGreetings(_ id: String, birthdayId: String? = nil, date: LocalDate? = nil, limit: Int = 50) async throws -> [FeedBirthdayGreeting] {
        try await call {
            try await $0.listFeedBirthdayGreetings(path: .init(feedId: id),
                                                   query: .init(limit: limit, birthdayId: birthdayId, date: date?.iso)).ok.body.json.greetings
        }
    }

    /// The caller's own "Birthday notifications" for this feed (morning notice and greetings).
    public func setBirthdayNotifications(_ id: String, on: Bool) async throws -> Feed {
        try await call {
            try await $0.updateFeedPreferences(path: .init(feedId: id), body: .json(.init(birthdayNotifications: on))).ok.body.json
        }
    }

    /// The frame side: show or hide the feed's birthday people on this frame.
    public func setSubscriptionBirthdays(_ id: String, _ on: Bool) async throws -> FeedSubscription {
        try await call { try await $0.updateSubscription(path: .init(subscriptionId: id), body: .json(.init(birthdays: on))).ok.body.json }
    }

    /// `PATCH /v1/me {timezone}` (the morning notification comes at 08:00 in it).
    public func setTimeZone(_ identifier: String) async throws -> User {
        try await call { try await $0.updateMe(body: .json(.init(timezone: identifier))).ok.body.json }
    }

    // MARK: Raw JSON

    /// A request outside the generated client (for explicit nulls), with the same auth, language and problem
    /// handling: one retry with a fresh token after a 401.
    func raw(_ method: String, _ path: String, body: Data?) async throws -> Data {
        let url = url(path)
        var refreshed = false
        while true {
            var request = URLRequest(url: url)
            request.httpMethod = method
            request.httpBody = body
            if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue(L10n.code, forHTTPHeaderField: "Accept-Language")
            request.identifyApp()
            guard let token = try await tokens.accessToken() else { throw APIError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let data: Data, response: URLResponse
            do {
                (data, response) = try await session.data(for: request)
            } catch {
                throw APIError.wrap(error)
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401, !refreshed, await tokens.refreshAfterUnauthorized() != nil {
                refreshed = true
                continue
            }
            guard (200..<300).contains(status) else { throw APIError.from(status: status, body: data) }
            return data
        }
    }
}
