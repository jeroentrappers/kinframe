@testable import KinframeKit
import XCTest

/// Feeds and subscriptions: the shared code field, recipients, visibility, receipts, subscription actions, links,
/// live events, push routes and decoding of the new schemas (fixtures follow docs/api/openapi.yaml).
final class FeedCodeTests: XCTestCase {
    func testFrameCodesStayDigits() {
        XCTAssertEqual(CodeEntry.normalize("4821 3907", allowLetters: true), "48213907")
        XCTAssertEqual(CodeEntry.normalize("Code: 4821-3907", allowLetters: true), "48213907")
        XCTAssertEqual(CodeEntry.classify("48213907"), .frame("48213907"))
        XCTAssertEqual(CodeEntry.format("48213907"), "4821 3907")
        // The frame-only field (moving a frame to a new device) ignores letters.
        XCTAssertEqual(CodeEntry.normalize("KF7Q-M2XA", allowLetters: false), "72")
    }

    func testFeedCodesHaveLetters() {
        XCTAssertEqual(CodeEntry.normalize("kf7q-m2xa", allowLetters: true), "KF7QM2XA")
        XCTAssertEqual(CodeEntry.classify("KF7QM2XA"), .feed("KF7QM2XA"))
        XCTAssertEqual(CodeEntry.format("KF7QM2XA"), "KF7Q-M2XA")
        XCTAssertEqual(CodeEntry.format("KF7QM"), "KF7Q-M")
        XCTAssertEqual(CodeEntry.format("KF7"), "KF7")
        XCTAssertTrue(CodeEntry.isFeed("1234K"))
        XCTAssertFalse(CodeEntry.isFeed("1234"))
        // Typing goes through the formatted text, hyphen included.
        XCTAssertEqual(CodeEntry.normalize("KF7Q-M2X" + "A", allowLetters: true), "KF7QM2XA")
        XCTAssertEqual(CodeEntry.normalize("KF7Q-M2XAB", allowLetters: true), "KF7QM2XA", "at most 8")
    }

    func testCrockfordLookAlikes() {
        // O reads as 0, I and L as 1; U isn't in the alphabet.
        XCTAssertEqual(CodeEntry.normalize("KFOQ-MIXL", allowLetters: true), "KF0QM1X1")
        XCTAssertEqual(CodeEntry.normalize("KUF", allowLetters: true), "KF")
    }

    func testPastedMessages() {
        XCTAssertEqual(CodeEntry.normalize("Join Peeters kids with code KF7Q-M2XA!", allowLetters: true), "KF7QM2XA")
        XCTAssertEqual(CodeEntry.normalize("Code KF7QM2XA", allowLetters: true), "KF7QM2XA")
        XCTAssertEqual(CodeEntry.normalize("The frame shows 4821 3907 now", allowLetters: true), "48213907")
    }

    func testIncompleteCodes() {
        XCTAssertNil(CodeEntry.classify("KF7QM2X"))
        XCTAssertNil(CodeEntry.classify("4821390"))
        XCTAssertNil(CodeEntry.classify(""))
    }

    func testInviteCodeDisplay() {
        XCTAssertEqual(CodeEntry.displayFeedCode("KF7QM2XA"), "KF7Q-M2XA")
        XCTAssertEqual(CodeEntry.displayFeedCode("kf7q-m2xa"), "KF7Q-M2XA")
    }
}

final class RecipientTests: XCTestCase {
    let limits = Limits(maxItemsPerSend: 3, maxVideoSeconds: 60, maxUploadBytes: 1000)

    func testAtLeastOneRecipient() {
        XCTAssertEqual(ComposeRules.blocker(itemCount: 1, frameCount: 0, feedCount: 0, issues: 0, longestCaption: 0, limits: limits), .noFrames)
        XCTAssertNil(ComposeRules.blocker(itemCount: 1, frameCount: 0, feedCount: 1, issues: 0, longestCaption: 0, limits: limits), "a feed alone is enough")
        XCTAssertNil(ComposeRules.blocker(itemCount: 1, frameCount: 2, feedCount: 1, issues: 0, longestCaption: 0, limits: limits))
    }

    func testFeedCap() {
        XCTAssertEqual(Recipients.problem(frameCount: 0, feedCount: 21), .tooManyFeeds(20))
        XCTAssertNil(Recipients.problem(frameCount: 0, feedCount: 20))
    }

    func testFeedsDontMultiplyTheItemLimit() {
        // Items are checked before recipients; one feed with many frames is one recipient.
        XCTAssertEqual(ComposeRules.blocker(itemCount: 4, frameCount: 0, feedCount: 1, issues: 0, longestCaption: 0, limits: limits), .overLimit(3))
        XCTAssertNil(ComposeRules.blocker(itemCount: 3, frameCount: 0, feedCount: 1, issues: 0, longestCaption: 0, limits: limits))
    }

    func testValidIdsKeepOrder() {
        XCTAssertEqual(Recipients.valid(["b", "x", "a"], among: ["a", "b", "c"]), ["a", "b"])
    }

    func testSendBodyCarriesFeeds() throws {
        var item = OutboxItem(upload: TusUpload(file: "a.jpg", size: 10, filename: "a.jpg", mime: "image/jpeg"), kind: .photo)
        item.upload.existingItemId = "up1"
        let both = OutboxJob(server: URL(string: "https://k.example")!, frameIds: ["f1"], frameNames: ["Oma"],
                             feedIds: ["feed1"], feedNames: ["Peeters kids"], items: [item])
        let body = try JSONDecoder().decode(JSONValue.self, from: both.sendBody())
        XCTAssertEqual(body["frame_ids"], .array([.string("f1")]))
        XCTAssertEqual(body["feed_ids"], .array([.string("feed1")]))
        XCTAssertEqual(both.recipientNames, ["Oma", "Peeters kids"])

        let feedOnly = OutboxJob(server: URL(string: "https://k.example")!, frameIds: [], frameNames: [], feedIds: ["feed1"], feedNames: ["P"], items: [item])
        let body2 = try JSONDecoder().decode(JSONValue.self, from: feedOnly.sendBody())
        XCTAssertEqual(body2["frame_ids"], .array([]), "frame_ids is required, empty with only feeds")

        let framesOnly = OutboxJob(server: URL(string: "https://k.example")!, frameIds: ["f1"], frameNames: ["Oma"], items: [item])
        let body3 = try JSONDecoder().decode(JSONValue.self, from: framesOnly.sendBody())
        XCTAssertNil(body3["feed_ids"], "no feed_ids without feeds")
    }

    func testOldJobsStillLoad() throws {
        // A job saved before feeds existed has no feedIds.
        let job = OutboxJob(server: URL(string: "https://k.example")!, frameIds: ["f1"], frameNames: ["Oma"], items: [])
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(job)) as? [String: Any])
        json.removeValue(forKey: "feedIds")
        json.removeValue(forKey: "feedNames")
        let decoded = try JSONDecoder().decode(OutboxJob.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(decoded.feedIds)
        XCTAssertEqual(decoded.recipientNames, ["Oma"])
    }
}

final class FeedAbilityTests: XCTestCase {
    func testPostersSeeOnlyTheirOwnReceipts() {
        let poster = FeedAbilities(role: .poster)
        XCTAssertTrue(poster.canSeeReceipts(posterId: "me", me: "me"))
        XCTAssertFalse(poster.canSeeReceipts(posterId: "ben", me: "me"))
        XCTAssertTrue(FeedAbilities(role: .poster, membersSeeReactions: true).canSeeReceipts(posterId: "ben", me: "me"))
        XCTAssertTrue(FeedAbilities(role: .admin).canSeeReceipts(posterId: "ben", me: "me"))
    }

    func testRemovingPosts() {
        XCTAssertTrue(FeedAbilities(role: .poster).canRemove(posterId: "me", me: "me"))
        XCTAssertFalse(FeedAbilities(role: .poster).canRemove(posterId: "ben", me: "me"))
        XCTAssertTrue(FeedAbilities(role: .owner).canRemove(posterId: "ben", me: "me"))
    }

    func testMemberManagement() {
        let owner = FeedAbilities(role: .owner), admin = FeedAbilities(role: .admin), poster = FeedAbilities(role: .poster)
        XCTAssertTrue(owner.canEdit(.admin, isMe: false))
        XCTAssertFalse(owner.canEdit(.owner, isMe: true))
        XCTAssertTrue(admin.canEdit(.poster, isMe: false))
        XCTAssertFalse(admin.canEdit(.admin, isMe: false), "admins can't change other admins")
        XCTAssertFalse(admin.canEdit(.owner, isMe: false))
        XCTAssertFalse(poster.canEdit(.poster, isMe: false))
    }

    func testInvites() {
        XCTAssertFalse(FeedAbilities(role: .poster).canCreateJoinInvites)
        XCTAssertTrue(FeedAbilities(role: .poster, postersCanInvite: true).canCreateJoinInvites)
        XCTAssertFalse(FeedAbilities(role: .poster, postersCanInvite: true).canCreateSubscribeInvites, "subscribe invites are pre-approved: admins only")
        XCTAssertTrue(FeedAbilities(role: .admin).canCreateSubscribeInvites)
    }
}

final class FeedReceiptTests: XCTestCase {
    func summary(_ frames: Int, delivered: Int, seen: Int, awaiting: Int? = nil, paused: Int? = nil, removed: Int? = nil) -> FeedReceiptSummary {
        FeedReceiptSummary(frames: frames, delivered: delivered, seen: seen, awaitingApproval: awaiting, paused: paused, removed: removed)
    }

    func testStageAndProgress() {
        XCTAssertEqual(FeedReceipts.stage(summary(5, delivered: 5, seen: 5)), .seen)
        XCTAssertEqual(FeedReceipts.stage(summary(5, delivered: 5, seen: 4)), .delivered)
        XCTAssertEqual(FeedReceipts.stage(summary(5, delivered: 2, seen: 0)), .onItsWay)
        XCTAssertEqual(FeedReceipts.progress(summary(3, delivered: 3, seen: 3)), 1, accuracy: 0.001)
        XCTAssertEqual(FeedReceipts.progress(summary(3, delivered: 3, seen: 0)), 2.0 / 3, accuracy: 0.001)
        XCTAssertEqual(FeedReceipts.progress(summary(0, delivered: 0, seen: 0)), 0)
    }

    func testFrameStatusIsTheLeastAdvancedItem() throws {
        let json = """
        [{"item_id":"i1","status":"seen"},{"item_id":"i2","status":"delivered"},{"item_id":"i3","status":"removed"}]
        """
        let items = try JSONDecoder().decode([FeedItemReceipt].self, from: Data(json.utf8))
        XCTAssertEqual(FeedReceipts.frameStatus(items), .delivered)
        let paused = try JSONDecoder().decode([FeedItemReceipt].self, from: Data(#"[{"item_id":"i1","status":"suspended"},{"item_id":"i2","status":"seen"}]"#.utf8))
        XCTAssertEqual(FeedReceipts.frameStatus(paused), .suspended)
        XCTAssertEqual(FeedReceipts.frameStatus([]), .removed)
    }

    func testReactionLinesGroupByFrame() throws {
        let json = """
        [{"frame_id":"f1","frame_name":"Oma","item_id":"i1","placement_id":"p1","reaction":"love","replies":[]},
         {"frame_id":"f2","frame_name":"Kitchen","item_id":"i1","placement_id":"p2","reaction":"laugh"},
         {"frame_id":"f1","frame_name":"Oma","item_id":"i2","placement_id":"p3","reaction":null,"replies":["miss_you"]}]
        """
        let lines = FeedReceipts.reactionLines(try JSONDecoder().decode([FeedReaction].self, from: Data(json.utf8)))
        XCTAssertEqual(lines.map(\.frame), ["Oma", "Kitchen"])
        XCTAssertEqual(lines[0].reaction, .love)
        XCTAssertEqual(lines[0].replies, [.missYou])
    }
}

final class SubscriptionLogicTests: XCTestCase {
    func testFrameSideActions() {
        XCTAssertEqual(SubscriptionLogic.actions(status: .active, canManage: true, canDecide: false), [.pause, .changeHistory, .unsubscribe])
        XCTAssertEqual(SubscriptionLogic.actions(status: .paused, canManage: true, canDecide: true), [.resume, .changeHistory, .unsubscribe])
        XCTAssertEqual(SubscriptionLogic.actions(status: .pending, canManage: true, canDecide: false), [.changeHistory, .withdraw])
    }

    func testFeedSideActions() {
        XCTAssertEqual(SubscriptionLogic.actions(status: .pending, canManage: false, canDecide: true), [.approve, .reject])
        XCTAssertEqual(SubscriptionLogic.actions(status: .active, canManage: false, canDecide: true), [.remove])
        XCTAssertEqual(SubscriptionLogic.actions(status: .removed, canManage: true, canDecide: true), [])
        XCTAssertEqual(SubscriptionLogic.actions(status: .active, canManage: false, canDecide: false), [], "other frame members only look")
    }

    func testHistoryChoice() {
        XCTAssertEqual(HistoryChoice(history: .lastN, count: 50), .lastN(50))
        XCTAssertEqual(HistoryChoice(history: .lastN, count: 5000).count, 1000)
        XCTAssertEqual(HistoryChoice(history: .lastN, count: nil), .lastN(50))
        XCTAssertNil(HistoryChoice.all.count)
        XCTAssertEqual(HistoryChoice.none.history, .none)
        XCTAssertEqual(HistoryChoice.lastN(0).count, 1)
    }

    func testManageableFrames() throws {
        let frames = try JSONDecoder().decode([FrameSummary].self, from: Data("""
        [{"id":"f1","name":"Oma","online":true,"role":"owner","permissions":{}},
         {"id":"f2","name":"Kitchen","online":false,"role":"member","permissions":{"send":true}},
         {"id":"f3","name":"Hall","online":false,"role":"member","permissions":{"change_settings":true}}]
        """.utf8))
        XCTAssertEqual(SubscriptionLogic.manageable(frames).map(\.id), ["f1", "f3"])
    }
}

final class FeedLinkTests: XCTestCase {
    let token = "AbCdEfGhIjKlMnOpQrStUv12"

    func testUniversalLink() {
        XCTAssertEqual(DeepLink.parse(URL(string: "https://kinframe.appmire.be/fi/\(token)")!),
                       .feedInvite(server: URL(string: "https://kinframe.appmire.be")!, token: token))
        XCTAssertNil(DeepLink.parse(URL(string: "https://kinframe.appmire.be/fi/short")!))
    }

    func testCustomScheme() {
        XCTAssertEqual(DeepLink.parse(URL(string: "app.kinframe://fi/\(token)?server=https://photos.example.org")!),
                       .feedInvite(server: URL(string: "https://photos.example.org")!, token: token))
        XCTAssertEqual(DeepLink.parse(URL(string: "app.kinframe://open?url=https://kinframe.appmire.be/fi/\(token)")!),
                       .feedInvite(server: URL(string: "https://kinframe.appmire.be")!, token: token))
        // Frame invites are unchanged.
        XCTAssertEqual(DeepLink.parse(URL(string: "https://kinframe.appmire.be/i/\(token)")!),
                       .invite(server: URL(string: "https://kinframe.appmire.be")!, token: token))
    }

    func testDiscoveryFlag() throws {
        let on = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1","feeds":true,"limits":{"max_items_per_send":200,"max_video_seconds":300,"max_upload_bytes":1,"max_feed_backfill_items":2000}}"#.utf8))
        XCTAssertTrue(on.feeds)
        XCTAssertEqual(on.limits.maxFeedBackfillItems, 2000)
        XCTAssertEqual(on.limits.maxFeedsPerUser, 20)
        let off = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1"}"#.utf8))
        XCTAssertFalse(off.feeds, "older servers have no feeds")
    }
}

final class FeedLiveEventTests: XCTestCase {
    func testFeedReactionsAreTheirOwnEvent() {
        let json = #"{"type":"reaction","placement_id":"p1","frame_id":"f1","reaction":"love","by_frame_name":"Oma","feed_id":"feed1","post_id":"post1"}"#
        XCTAssertEqual(LiveEvent.parse(Data(json.utf8)), .feedReaction(feedId: "feed1", postId: "post1", placementId: "p1", reaction: .love, byFrameName: "Oma"))
        let direct = #"{"type":"reaction","placement_id":"p1","frame_id":"f1","reaction":"love","by_frame_name":"Oma"}"#
        XCTAssertEqual(LiveEvent.parse(Data(direct.utf8)), .reaction(placementId: "p1", frameId: "f1", reaction: .love, byFrameName: "Oma"))
        let reply = #"{"type":"reply","placement_id":"p1","frame_id":"f1","preset":"thanks","feed_id":"feed1","post_id":"post1"}"#
        XCTAssertEqual(LiveEvent.parse(Data(reply.utf8)), .feedReply(feedId: "feed1", postId: "post1", placementId: "p1", preset: .thanks))
    }

    func testPostAndSubscriptionEvents() {
        let post = #"{"type":"feed_post.updated","feed_id":"feed1","post_id":"post1","summary":{"frames":5,"delivered":4,"seen":2}}"#
        guard case .feedPostUpdated("feed1", "post1", let summary)? = LiveEvent.parse(Data(post.utf8)) else { return XCTFail("not parsed") }
        XCTAssertEqual(summary?.frames, 5)
        XCTAssertEqual(summary?.seen, 2)

        let request = "{\"type\":\"subscription.request\",\"feed_id\":\"feed1\",\"subscription\":\(SchemaFixtures.subscription)}"
        guard case .subscriptionRequest("feed1", let sub)? = LiveEvent.parse(Data(request.utf8)) else { return XCTFail("not parsed") }
        XCTAssertEqual(sub?.status, .pending)
        XCTAssertEqual(sub?.frame.name, "Oma’s frame")

        let updated = #"{"type":"subscription.updated","feed_id":"feed1","frame_id":"f1"}"#
        XCTAssertEqual(LiveEvent.parse(Data(updated.utf8)), .subscriptionUpdated(feedId: "feed1", frameId: "f1", subscription: nil))
        XCTAssertEqual(LiveEvent.parse(Data(#"{"type":"feed.join_request","feed_id":"feed1","request":{"user":{"display_name":"Ben"}}}"#.utf8)),
                       .feedJoinRequest(feedId: "feed1", name: "Ben"))
        XCTAssertEqual(LiveEvent.parse(Data(#"{"type":"feed.membership_updated","feed_id":"feed1"}"#.utf8)), .feedMembershipUpdated(feedId: "feed1"))
        XCTAssertNil(LiveEvent.parse(Data(#"{"type":"feed_post.updated","feed_id":"feed1"}"#.utf8)))
    }

    func testPushRoutes() {
        XCTAssertEqual(PushRoute.parse(["key": "subscription_request", "ids": ["feed_id": "feed1", "subscription_id": "s1"]]), .feed(feedId: "feed1", section: .frames))
        XCTAssertEqual(PushRoute.parse(["key": "subscription_decided", "ids": ["feed_id": "feed1", "frame_id": "f1"]]), .frameFeeds(frameId: "f1"))
        XCTAssertEqual(PushRoute.parse(["key": "feed_join_request", "feed_id": "feed1"]), .feed(feedId: "feed1", section: .members))
        XCTAssertEqual(PushRoute.parse(["type": "reaction", "ids": ["feed_id": "feed1", "post_id": "post1", "placement_id": "p1"]]), .feedPost(feedId: "feed1", postId: "post1"))
        XCTAssertEqual(PushRoute.parse(["key": "reaction", "ids": ["send_id": "s1"]]), .send(sendId: "s1"))
        XCTAssertNil(PushRoute.parse(["key": "something_new"]))
    }
}

/// JSON shaped like the contract's schemas, decoded with the app's date handling.
enum SchemaFixtures {
    static let feed = """
    {"id":"feed1","name":"Peeters kids","cover_url":"https://cdn.example/c.jpg","role":"admin","member_count":4,"subscription_count":5,
     "last_post_at":"2026-11-17T18:03:12.25Z","created_at":"2026-10-01T09:00:00Z","owner_name":"Anna","cover_item_id":"item1",
     "auto_approve_subscriptions":false,"invite_requires_approval":true,"members_see_reactions":false,"posters_can_invite":true,
     "post_count":42,"pending_subscription_count":1,"pending_join_request_count":2}
    """

    static let subscription = """
    {"id":"s1","status":"pending","history":"last_n","history_count":50,
     "feed":{"id":"feed1","name":"Peeters kids","cover_url":null,"member_count":4,"admin_names":["Anna","Ben"]},
     "frame":{"id":"f1","name":"Oma’s frame","location":"Living room","online":true,"last_seen_at":"2026-11-18T08:00:00Z"},
     "requested_by_name":"Ben","via_invite":false,"created_at":"2026-11-18T08:00:00Z","activated_at":null,"paused_at":null,"ended_at":null,
     "removed_reason":null,"backfill":{"state":"running","placed":10,"total":40},"estimate":{"items":50,"bytes":131072000},
     "can_manage":true,"can_decide":false}
    """

    static let item = """
    {"id":"item1","kind":"photo","status":"ready","sender_id":"u2","sender_name":"Ben","caption":"At the beach","created_at":"2026-11-17T18:00:00Z",
     "thumb_url":"https://cdn.example/t.jpg"}
    """

    static let post = """
    {"id":"post1","feed_id":"feed1","poster":{"user_id":"u2","display_name":"Ben","avatar_url":null},"send_id":"send1",
     "created_at":"2026-11-17T18:03:12Z","items":[\(item)],
     "receipts":{"frames":5,"delivered":4,"seen":4,"awaiting_approval":1,"paused":1,"removed":0},
     "reactions":[{"frame_id":"f1","frame_name":"Oma’s frame","item_id":"item1","placement_id":"p1","reaction":"love","replies":[],"at":"2026-11-17T19:00:00Z"}]}
    """

    static let otherPost = """
    {"id":"post2","feed_id":"feed1","poster":{"user_id":"u3","display_name":"Carla"},"send_id":null,"created_at":"2026-11-16T10:00:00Z",
     "items":[\(item)],"receipts":null,"reactions":null}
    """

    static let receipts = """
    {"post_id":"post1","summary":{"frames":2,"delivered":1,"seen":1,"awaiting_approval":1,"paused":0,"removed":0},
     "frames":[{"subscription_id":"s1","frame_id":"f1","frame_name":"Oma’s frame","frame_location":"Living room","online":true,
       "last_seen_at":"2026-11-18T08:00:00Z","subscription_status":"active",
       "items":[{"item_id":"item1","placement_id":"p1","status":"seen","delivered_at":"2026-11-17T18:04:00Z","seen_at":"2026-11-17T18:30:00Z",
         "reaction":"love","replies":[{"preset":"miss_you","at":"2026-11-17T18:31:00Z"}]}]},
      {"subscription_id":"s2","frame_id":"f2","frame_name":"Kitchen","frame_location":null,"online":false,"last_seen_at":null,
       "subscription_status":"active","items":[{"item_id":"item1","placement_id":null,"status":"awaiting_approval"}]}]}
    """

    static let invite = """
    {"id":"inv1","purpose":"subscribe","token":"AbCdEfGhIjKlMnOpQrStUv12","code":"KF7QM2XA","url":"https://kinframe.appmire.be/fi/AbCdEfGhIjKlMnOpQrStUv12",
     "created_by_name":"Anna","max_uses":null,"uses":0,"note":null,"expires_at":"2026-11-25T09:00:00Z"}
    """

    static let preview = """
    {"token":"AbCdEfGhIjKlMnOpQrStUv12","purpose":"subscribe","feed_name":"Peeters kids","cover_url":null,"owner_name":"Anna",
     "invited_by_name":"Anna","member_count":4,"requires_approval":false,"already_member":false,
     "frames":[{"frame_id":"f1","frame_name":"Oma’s frame","subscription_status":null},{"frame_id":"f2","frame_name":"Kitchen","subscription_status":"active"}]}
    """

    static let send = """
    {"id":"send1","created_at":"2026-11-17T18:03:12Z","items":[\(item)],"frames":[],
     "feeds":[{"feed_id":"feed1","feed_name":"Peeters kids","post_id":"post1","removed":false,"summary":{"frames":5,"delivered":4,"seen":4}}]}
    """

    static let placement = """
    {"id":"p1","frame_id":"f1","item":\(item),"status":"seen","hidden":false,"trashed":false,"received_at":"2026-11-17T18:03:12Z",
     "origin":"feed","origin_feed_id":"feed1","origin_feed_name":"Peeters kids","feed_ids":["feed1"],"backfill":true}
    """

    static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }
}

final class FeedSchemaTests: XCTestCase {
    func testFeed() throws {
        let feed = try SchemaFixtures.decode(Feed.self, SchemaFixtures.feed)
        XCTAssertEqual(feed.id, "feed1")
        XCTAssertEqual(feed.role, .admin)
        XCTAssertEqual(feed.summary.subscriptionCount, 5)
        XCTAssertEqual(feed.value2.postersCanInvite, true)
        XCTAssertEqual(feed.value2.pendingJoinRequestCount, 2)
        XCTAssertNotNil(feed.summary.lastPostAt, "fractional seconds")
        let abilities = FeedAbilities(feed)
        XCTAssertTrue(abilities.canManage)
        XCTAssertTrue(abilities.postersCanInvite)
        // A summary (the list) decodes from the same document.
        XCTAssertEqual(try SchemaFixtures.decode(FeedSummary.self, SchemaFixtures.feed).memberCount, 4)
    }

    func testSubscription() throws {
        let s = try SchemaFixtures.decode(FeedSubscription.self, SchemaFixtures.subscription)
        XCTAssertEqual(s.status, .pending)
        XCTAssertEqual(HistoryChoice(s), .lastN(50))
        XCTAssertEqual(s.feed.adminNames ?? [], ["Anna", "Ben"])
        XCTAssertEqual(s.estimate?.bytes, 131_072_000)
        XCTAssertEqual(SubscriptionLogic.backfillProgress(s) ?? -1, 0.25, accuracy: 0.001)
        XCTAssertEqual(SubscriptionLogic.actions(s), [.changeHistory, .withdraw])
        let removed = try SchemaFixtures.decode(FeedSubscription.self, SchemaFixtures.subscription
            .replacingOccurrences(of: #""status":"pending""#, with: #""status":"removed""#)
            .replacingOccurrences(of: #""removed_reason":null"#, with: #""removed_reason":"feed_deleted""#))
        XCTAssertEqual(removed.removedReason, .feedDeleted)
    }

    func testPostsAndVisibility() throws {
        let own = try SchemaFixtures.decode(FeedPost.self, SchemaFixtures.post)
        XCTAssertEqual(own.poster.displayName, "Ben")
        XCTAssertEqual(own.receipts?.awaitingApproval, 1)
        XCTAssertEqual(own.reactions?.first?.reaction, .love)
        XCTAssertEqual(FeedReceipts.stage(try XCTUnwrap(own.receipts)), .onItsWay)
        // Someone else's post as a poster: receipts and reactions are null.
        let other = try SchemaFixtures.decode(FeedPost.self, SchemaFixtures.otherPost)
        XCTAssertNil(other.receipts)
        XCTAssertNil(other.reactions)
        XCTAssertNil(other.sendId)
        XCTAssertFalse(FeedAbilities(role: .poster).canSeeReceipts(posterId: other.poster.userId, me: "u2"))
    }

    func testReceipts() throws {
        let r = try SchemaFixtures.decode(FeedPostReceipts.self, SchemaFixtures.receipts)
        XCTAssertEqual(r.frames.count, 2)
        XCTAssertEqual(r.frames[0].items[0].replies?.first?.preset, .missYou)
        XCTAssertEqual(FeedReceipts.frameStatus(r.frames[1].items), .awaitingApproval)
        XCTAssertEqual(r.frames[0].subscriptionStatus, .active)
    }

    func testInvites() throws {
        let invite = try SchemaFixtures.decode(FeedInvite.self, SchemaFixtures.invite)
        XCTAssertEqual(invite.purpose, .subscribe)
        XCTAssertEqual(CodeEntry.displayFeedCode(invite.code), "KF7Q-M2XA")
        XCTAssertEqual(DeepLink.parse(URL(string: invite.url)!), .feedInvite(server: URL(string: "https://kinframe.appmire.be")!, token: invite.token))
        let preview = try SchemaFixtures.decode(FeedInvitePreview.self, SchemaFixtures.preview)
        XCTAssertEqual(preview.frames?.map(\.frameName), ["Oma’s frame", "Kitchen"])
        XCTAssertEqual(preview.frames?[1].subscriptionStatus, .active)
        XCTAssertFalse(preview.requiresApproval)
        let result = try SchemaFixtures.decode(FeedInviteResult.self, """
        {"status":"subscribed","feed":\(SchemaFixtures.feed),"subscriptions":[\(SchemaFixtures.subscription)]}
        """)
        XCTAssertEqual(result.status, .subscribed)
        XCTAssertEqual(result.subscriptions?.count, 1)
    }

    func testSendWithFeedsAndFeedPlacements() throws {
        let send = try SchemaFixtures.decode(Send.self, SchemaFixtures.send)
        XCTAssertTrue(send.frames.isEmpty, "feed placements aren't in frames[]")
        XCTAssertEqual(send.feeds?.first?.feedName, "Peeters kids")
        XCTAssertEqual(send.feeds?.first?.summary.map(FeedReceipts.text), FeedReceipts.text(FeedReceiptSummary(frames: 5, delivered: 4, seen: 4)))
        let p = try SchemaFixtures.decode(Placement.self, SchemaFixtures.placement)
        XCTAssertEqual(p.origin, .feed)
        XCTAssertEqual(p.originFeedName, "Peeters kids")
        XCTAssertEqual(p.feedIds, ["feed1"])
        XCTAssertEqual(p.backfill, true)
        // Older servers send neither.
        let plain = try SchemaFixtures.decode(Placement.self, """
        {"id":"p2","frame_id":"f1","item":\(SchemaFixtures.item),"status":"ready","hidden":false,"trashed":false}
        """)
        XCTAssertNil(plain.origin)
        XCTAssertNil(plain.feedIds)
    }

    func testCreateBodies() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let request = Components.Schemas.SubscriptionRequest(frameId: "f1", history: HistoryChoice.lastN(20).history, historyCount: HistoryChoice.lastN(20).count)
        XCTAssertEqual(String(decoding: try encoder.encode(request), as: UTF8.self), #"{"frame_id":"f1","history":"last_n","history_count":20}"#)
        let all = Components.Schemas.SubscriptionUpdate(history: HistoryChoice.all.history, historyCount: HistoryChoice.all.count)
        XCTAssertEqual(String(decoding: try encoder.encode(all), as: UTF8.self), #"{"history":"all"}"#)
        let create = Components.Schemas.SendCreate(frameIds: [], feedIds: ["feed1"], items: [.init(uploadId: "u1")])
        XCTAssertEqual(String(decoding: try encoder.encode(create), as: UTF8.self), #"{"feed_ids":["feed1"],"frame_ids":[],"items":[{"upload_id":"u1"}]}"#)
    }
}
