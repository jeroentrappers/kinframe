@testable import Kinframe
@testable import KinframeKit
import XCTest

// Family social (docs/spec/family-social.md, README §5.18–§5.20): discovery flags, reactions, threads, mentions,
// the timeline, circles, live events and push routes. Fixtures follow docs/api/openapi.yaml.

enum SocialFixtures {
    static func person(_ id: String, _ name: String) -> String {
        #"{"user_id":"\#(id)","display_name":"\#(name)"}"#
    }

    static let emptyReactions = #"{"total":0,"counts":[],"people":[],"frames":[]}"#

    static func comment(_ id: String, parent: String? = nil, author: String = "u2", name: String = "Anna", body: String = "Nice",
                        at: String = "2026-11-19T10:00:00Z", kind: String = "person", extra: String = "") -> String {
        let p = parent.map { #""parent_id":"\#($0)","# } ?? ""
        let who = kind == "person" ? #""author":\#(person(author, name)),"body":"\#(body)","# : #""frame":{"frame_id":"f1","frame_name":"Oma’s frame","reaction":"love"},"#
        return #"{"id":"\#(id)","post_id":"p1","kind":"\#(kind)",\#(p)\#(who)"reactions":\#(emptyReactions),"created_at":"\#(at)"\#(extra)}"#
    }

    static func post(_ id: String = "p1", prompt: String? = nil, comments: [String] = [], count: Int = 0) -> String {
        let pr = prompt.map { #""prompt":{"id":"\#($0)","title":"Your view"},"# } ?? ""
        return #"{"id":"\#(id)","kind":"feed","feed_id":"feed1","feed_name":"Peeters","poster":\#(person("u1", "Ben")),"created_at":"2026-11-19T09:00:00Z","items":[],\#(pr)"thread":{"post_id":"\#(id)","state":"open","comment_count":\#(count),"latest_comments":[\#(comments.joined(separator: ","))],"reactions":\#(emptyReactions),"can_comment":true}}"#
    }

    static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }
}

final class SocialFeatureTests: XCTestCase {
    func testFlagsFromDiscovery() throws {
        let d = try JSONDecoder().decode(Discovery.self, from: Data(#"""
        {"name":"k","api":"https://k.example/v1","feeds":true,"social":true,"chats":true,"voice_messages":true,"link_previews":false,
         "engagement":true,"email_digest":true,"limits":{"max_items_per_send":200,"max_video_seconds":300,"max_upload_bytes":1,"max_comment_chars":1500,"max_voice_seconds":120}}
        """#.utf8))
        let f = SocialFeatures(d)
        XCTAssertTrue(f.social && f.chats && f.voiceMessages && f.engagement && f.emailDigest && f.home)
        XCTAssertFalse(f.linkPreviews)
        XCTAssertEqual(d.limits.maxCommentChars, 1500)
        XCTAssertEqual(d.limits.maxVoiceSeconds, 120)
        XCTAssertEqual(d.limits.maxMessageChars, 4000, "defaults for limits the server didn't send")
        // The stored copy keeps the flags.
        let again = try JSONDecoder().decode(Discovery.self, from: JSONEncoder().encode(d))
        XCTAssertEqual(SocialFeatures(again), f)
    }

    func testSocialNeedsFeedsAndEverythingIsOffByDefault() throws {
        let d = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k.example/v1","social":true,"voice_messages":true}"#.utf8))
        let f = SocialFeatures(d)
        XCTAssertFalse(f.social, "social only together with feeds")
        XCTAssertFalse(f.voiceMessages, "voice only with chats")
        XCTAssertFalse(SocialFeatures(nil).home)
    }
}

final class ReactionTests: XCTestCase {
    func testEmojisAndFrameMapping() {
        XCTAssertEqual(PersonReaction.ordered.map(\.emoji), ["❤️", "😂", "😮", "😢", "👍", "🙏"])
        XCTAssertEqual(PersonReaction(Reaction.wow), .wow)
    }

    func testRowNamesFramesFirst() throws {
        let s = try SocialFixtures.decode(ReactionSummary.self, #"""
        {"total":5,"counts":[{"emoji":"love","count":4},{"emoji":"laugh","count":1}],
         "people":[{"person":{"user_id":"u2","display_name":"Anna"},"emoji":"love"}],
         "frames":[{"frame_id":"f1","frame_name":"Oma’s frame","reaction":"love"}]}
        """#)
        XCTAssertEqual(ReactionRow.emojis(s), ["❤️", "😂"])
        let text = ReactionRow.names(s, me: "me")
        XCTAssertTrue(text.hasPrefix("Oma’s frame, Anna"), text)
        XCTAssertTrue(text.contains("3"), text)
        XCTAssertEqual(ReactionRow.names(ReactionRow.empty, me: "me"), "")
    }

    func testApplyingTheCallersReaction() throws {
        let s = try SocialFixtures.decode(ReactionSummary.self, #"{"total":2,"counts":[{"emoji":"love","count":2}],"people":[],"frames":[],"mine":"love"}"#)
        let changed = ReactionRow.applying(s, mine: .laugh)
        XCTAssertEqual(changed.total, 2)
        XCTAssertEqual(changed.mine, .laugh)
        XCTAssertEqual(Set(changed.counts.map(\.emoji)), [.love, .laugh])
        let removed = ReactionRow.applying(s, mine: nil)
        XCTAssertEqual(removed.total, 1)
        XCTAssertNil(removed.mine)
        let added = ReactionRow.applying(ReactionRow.empty, mine: .pray)
        XCTAssertEqual(added.total, 1)
        XCTAssertEqual(added.counts.first?.emoji, .pray)
    }
}

final class ThreadTests: XCTestCase {
    func testNestingOneLevel() throws {
        let flat = try [
            SocialFixtures.comment("c1", at: "2026-11-19T10:00:00Z"),
            SocialFixtures.comment("c2", parent: "c1", at: "2026-11-19T10:01:00Z"),
            SocialFixtures.comment("c3", at: "2026-11-19T10:02:00Z"),
            SocialFixtures.comment("c4", parent: "c1", at: "2026-11-19T10:03:00Z"),
            SocialFixtures.comment("c5", parent: "gone", at: "2026-11-19T10:04:00Z"),
        ].map { try SocialFixtures.decode(Comment.self, $0) }
        let nodes = ThreadLogic.nest(flat.shuffled())
        XCTAssertEqual(nodes.map(\.id), ["c1", "c3", "c5"], "a reply whose parent isn't loaded stays visible")
        XCTAssertEqual(nodes[0].replies.map(\.id), ["c2", "c4"])
    }

    func testReplyToAReplyQuotesThePerson() throws {
        let top = try SocialFixtures.decode(Comment.self, SocialFixtures.comment("c1"))
        let reply = try SocialFixtures.decode(Comment.self, SocialFixtures.comment("c2", parent: "c1", author: "u3", name: "Ben"))
        XCTAssertEqual(ThreadLogic.replyTarget(for: top).parentId, "c1")
        XCTAssertNil(ThreadLogic.replyTarget(for: top).quotedUserId)
        XCTAssertEqual(ThreadLogic.replyTarget(for: reply).parentId, "c1")
        XCTAssertEqual(ThreadLogic.replyTarget(for: reply).quotedUserId, "u3")
    }

    func testDisplayStates() throws {
        XCTAssertEqual(ThreadLogic.display(try SocialFixtures.decode(Comment.self, SocialFixtures.comment("a", kind: "frame"))), .frame)
        XCTAssertEqual(ThreadLogic.display(try SocialFixtures.decode(Comment.self, SocialFixtures.comment("b", extra: #","deleted":true"#))), .tombstone)
        XCTAssertEqual(ThreadLogic.display(try SocialFixtures.decode(Comment.self, SocialFixtures.comment("c", extra: #","removed_by_admin":true"#))), .removedByAdmin)
        let frame = try SocialFixtures.decode(Comment.self, SocialFixtures.comment("a", kind: "frame"))
        XCTAssertTrue(ThreadLogic.frameEntryText(frame).contains("Oma’s frame"))
    }

    func testRemovingATopCommentTakesItsReplies() throws {
        let list = try [SocialFixtures.comment("c1"), SocialFixtures.comment("c2", parent: "c1"), SocialFixtures.comment("c3")]
            .map { try SocialFixtures.decode(Comment.self, $0) }
        XCTAssertEqual(ThreadLogic.remove("c1", from: list).map(\.id), ["c3"])
        XCTAssertEqual(ThreadLogic.lastReadable(list), "c3")
    }

    func testAddingToTheSummary() throws {
        let t = try SocialFixtures.decode(TimelinePost.self, SocialFixtures.post(comments: [SocialFixtures.comment("c1"), SocialFixtures.comment("c2", at: "2026-11-19T10:05:00Z")], count: 5)).thread
        XCTAssertEqual(ThreadLogic.viewAllText(t), "View all 5 comments")
        let c = try SocialFixtures.decode(Comment.self, SocialFixtures.comment("c9", at: "2026-11-19T11:00:00Z"))
        let after = ThreadLogic.adding(c, to: t)
        XCTAssertEqual(after.commentCount, 6)
        XCTAssertEqual(after.latestComments.map(\.id), ["c2", "c9"])
        XCTAssertEqual(ThreadLogic.adding(c, to: after).commentCount, 6, "adding twice doesn't count twice")
    }
}

final class MentionTests: XCTestCase {
    func testActiveQuery() {
        XCTAssertEqual(Mentions.activeQuery("Look @An"), "An")
        XCTAssertEqual(Mentions.activeQuery("@"), "")
        XCTAssertNil(Mentions.activeQuery("mail me@home"), "an @ inside a word isn't a mention")
        XCTAssertNil(Mentions.activeQuery("no mention"))
    }

    func testFilterAndComplete() {
        let people = [MentionCandidate(id: "u1", name: "Anna Peeters"), MentionCandidate(id: "u2", name: "Ben"), MentionCandidate(id: "me", name: "Annie")]
        XCTAssertEqual(Mentions.filter(people, query: "pe", excluding: "me").map(\.id), ["u1"], "matches the start of any word")
        XCTAssertEqual(Mentions.filter(people, query: "an", excluding: "me").map(\.id), ["u1"], "never yourself")
        XCTAssertEqual(Mentions.complete("Look @An", with: people[0]), "Look @Anna Peeters ")
    }

    func testSpansAreUTF16() {
        let text = "😂 @Anna Maria and @Anna, @Annabel"
        let picks = [MentionCandidate(id: "u1", name: "Anna"), MentionCandidate(id: "u2", name: "Anna Maria")]
        let spans = Mentions.spans(in: text, picks: picks)
        XCTAssertEqual(spans.map(\.userId), ["u2", "u1"], "the longer name wins, @Annabel isn't Anna")
        XCTAssertEqual(spans[0].start, 3, "the emoji is two UTF-16 code units")
        XCTAssertEqual(spans[0].length, 11)
        let ns = text as NSString
        XCTAssertEqual(ns.substring(with: NSRange(location: spans[1].start, length: spans[1].length)), "@Anna")
        // Editing an existing text finds the people back.
        XCTAssertEqual(Mentions.picks(in: text, mentions: spans).map(\.name), ["Anna Maria", "Anna"])
    }

    func testAttributedBodyLinksMentionsAndURLs() {
        let body = "Hi @Ben, see https://example.org/x"
        let a = Mentions.attributed(body, mentions: [Mention(userId: "u2", start: 3, length: 4)])
        let links = a.runs.compactMap(\.link)
        XCTAssertTrue(links.contains(URL(string: "kinframe-person:u2")!))
        XCTAssertTrue(links.contains(URL(string: "https://example.org/x")!))
        XCTAssertEqual(Links.first(in: body)?.absoluteString, "https://example.org/x")
    }
}

final class TimelineTests: XCTestCase {
    func testPromptAnswersAreGrouped() throws {
        let posts = try [
            SocialFixtures.post("p5"), SocialFixtures.post("p4", prompt: "pr1"), SocialFixtures.post("p3", prompt: "pr1"),
            SocialFixtures.post("p2", prompt: "pr1"), SocialFixtures.post("p1", prompt: "pr2"),
        ].map { try SocialFixtures.decode(TimelinePost.self, $0) }
        let entries = TimelineLogic.entries(posts)
        XCTAssertEqual(entries.map(\.id), ["p5", "p4", "p3", "p2", "p1"], "the server's order is kept")
        XCTAssertNil(entries[0].promptHeader)
        XCTAssertEqual(entries[1].promptHeader?.count, 3)
        XCTAssertNil(entries[2].promptHeader)
        XCTAssertNil(entries[4].promptHeader, "a single answer gets no header")
    }

    func testAppendingSkipsKnownPostsAndLabels() throws {
        let a = try SocialFixtures.decode(TimelinePost.self, SocialFixtures.post("p1"))
        let b = try SocialFixtures.decode(TimelinePost.self, SocialFixtures.post("p2"))
        XCTAssertEqual(TimelineLogic.appending([a, b], to: [a]).map(\.id), ["p1", "p2"])
        XCTAssertEqual(TimelineLogic.label(a), "Peeters")
        let frame = try SocialFixtures.decode(TimelinePost.self, #"{"id":"p9","kind":"frame","frame_id":"f1","frame_name":"Oma’s frame","poster":{"user_id":"u1","display_name":"Ben"},"created_at":"2026-11-19T09:00:00Z","items":[],"seen":{"frames":1,"seen":1},"thread":{"post_id":"p9","state":"closed","comment_count":0,"latest_comments":[],"reactions":{"total":0,"counts":[],"people":[],"frames":[]}}}"#)
        XCTAssertEqual(TimelineLogic.label(frame), "To Oma’s frame")
        XCTAssertEqual(TimelineLogic.seenText(frame), "Seen on 1 frame")
        XCTAssertFalse(TimelineLogic.canComment(frame), "closed without can_comment")
    }
}

final class CircleTests: XCTestCase {
    func testWhoManagesAndNames() throws {
        let c = try SocialFixtures.decode(FamilyCircle.self, #"{"id":"c1","feed_id":"feed1","name":"The cousins","my_role":"member","members":[],"member_count":3}"#)
        XCTAssertFalse(CircleLogic.canManage(c, feedRole: .poster))
        XCTAssertTrue(CircleLogic.canManage(c, feedRole: .admin))
        XCTAssertTrue(CircleLogic.canCreate(feedRole: .member, membersCanCreate: true))
        XCTAssertFalse(CircleLogic.canCreate(feedRole: .poster, membersCanCreate: false))
        XCTAssertNotNil(CircleLogic.nameProblem(" the COUSINS ", existing: [c]), "unique ignoring case")
        XCTAssertNil(CircleLogic.nameProblem("The cousins", existing: [c], editing: "c1"))
        XCTAssertNotNil(CircleLogic.nameProblem("  ", existing: []))
        XCTAssertEqual(CircleLogic.audience([c]).map(\.id), ["c1"])
    }

    func testMemberRole() {
        let member = FeedAbilities(role: .member, postersCanInvite: true)
        XCTAssertFalse(member.canPost)
        XCTAssertFalse(member.canCreateJoinInvites, "posters_can_invite doesn't apply to members")
        XCTAssertTrue(FeedAbilities(role: .admin).canEdit(.member, isMe: false))
        XCTAssertFalse(FeedAbilities(role: .member).canEdit(.poster, isMe: false))
        XCTAssertTrue(FeedAbilities(role: .poster, membersSeeReactions: true, membersSeeReceipts: false).canSeeReceipts(posterId: "me", me: "me"))
        XCTAssertFalse(FeedAbilities(role: .poster, membersSeeReactions: true, membersSeeReceipts: false).canSeeReceipts(posterId: "x", me: "me"),
                       "receipts follow members_see_receipts once the server has it")
    }

    func testSendBodyCarriesCircleAndPrompt() throws {
        var item = OutboxItem(upload: TusUpload(file: "a.jpg", size: 10, filename: "a.jpg", mime: "image/jpeg"), kind: .photo)
        item.upload.existingItemId = "up1"
        var job = OutboxJob(server: URL(string: "https://k.example")!, frameIds: [], frameNames: [], feedIds: ["feed1"], feedNames: ["P"], items: [item])
        job.circleIds = ["c1"]
        job.promptId = "pr1"
        let body = try JSONDecoder().decode(JSONValue.self, from: job.sendBody())
        XCTAssertEqual(body["circle_ids"], .array([.string("c1")]))
        XCTAssertEqual(body["prompt_id"], .string("pr1"))
        let plain = OutboxJob(server: URL(string: "https://k.example")!, frameIds: ["f1"], frameNames: ["Oma"], items: [item])
        let body2 = try JSONDecoder().decode(JSONValue.self, from: plain.sendBody())
        XCTAssertNil(body2["circle_ids"])
        XCTAssertNil(body2["prompt_id"])
    }
}

final class SocialLiveTests: XCTestCase {
    private func parse(_ s: String) -> LiveEvent? { LiveEvent.parse(Data(s.utf8)) }

    func testThreadEvents() {
        guard case .social(.commentCreated(let post, let c))? = parse(#"{"type":"comment.created","post_id":"p1","comment":\#(SocialFixtures.comment("c1"))}"#) else {
            return XCTFail("comment.created")
        }
        XCTAssertEqual(post, "p1")
        XCTAssertEqual(c.id, "c1")
        XCTAssertEqual(parse(#"{"type":"comment.deleted","post_id":"p1","comment_id":"c1"}"#), .social(.commentDeleted(postId: "p1", commentId: "c1")))
        XCTAssertEqual(parse(#"{"type":"post.removed","post_id":"p1"}"#), .social(.postRemoved(postId: "p1")))
        XCTAssertEqual(parse(#"{"type":"thread.read","post_id":"p1","read_up_to":"c9"}"#), .social(.threadRead(postId: "p1", readUpTo: "c9")))
        guard case .social(.reactionUpdated(.message, "m1", nil, "conv1", let s))? = parse(#"{"type":"reaction.updated","target_kind":"message","target_id":"m1","conversation_id":"conv1","summary":\#(SocialFixtures.emptyReactions)}"#) else {
            return XCTFail("reaction.updated")
        }
        XCTAssertEqual(s?.total, 0)
    }

    func testChatAndEngagementEvents() {
        guard case .social(.typing("conv1", "u2", "Anna", true, _))? = parse(#"{"type":"typing","conversation_id":"conv1","user_id":"u2","display_name":"Anna","activity":"recording","expires_at":"2026-11-19T10:00:06Z"}"#) else {
            return XCTFail("typing")
        }
        XCTAssertEqual(parse(#"{"type":"badge.updated","badge":{"total":3,"conversations":2,"threads":1}}"#), .social(.badgeUpdated(Badge(total: 3, conversations: 2, threads: 1))))
        XCTAssertEqual(parse(#"{"type":"read.updated","conversation_id":"c","user_id":"u","last_read_message_id":"m"}"#), .social(.readUpdated(conversationId: "c", userId: "u", lastReadMessageId: "m")))
        guard case .social(.photoRequestCreated(let r))? = parse(#"{"type":"photo_request.created","request":{"id":"r1","frame_id":"f1","frame_name":"Oma’s frame","state":"open","created_at":"2026-11-19T10:00:00Z","expires_at":"2026-11-26T10:00:00Z"}}"#) else {
            return XCTFail("photo_request.created")
        }
        XCTAssertEqual(PhotoRequestText.badge(r), "Oma’s frame would like new photos")
        XCTAssertNil(parse(#"{"type":"something.new"}"#), "unknown types are ignored")
    }
}

final class SocialPushTests: XCTestCase {
    private func route(_ key: String, _ ids: [String: String]) -> PushRoute? {
        PushRoute.parse(["key": key, "ids": ids])
    }

    func testRoutes() {
        XCTAssertEqual(route("comment", ["post_id": "p1", "comment_id": "c1", "feed_id": "f"]), .social(.post(postId: "p1", commentId: "c1")))
        XCTAssertEqual(route("mention", ["conversation_id": "k1", "message_id": "m1"]), .social(.conversation(conversationId: "k1", messageId: "m1")))
        XCTAssertEqual(route("mention", ["post_id": "p1", "comment_id": "c1"]), .social(.post(postId: "p1", commentId: "c1")))
        XCTAssertEqual(route("message", ["conversation_id": "k1", "message_id": "m1"]), .social(.conversation(conversationId: "k1", messageId: "m1")))
        XCTAssertEqual(route("reaction_person", ["target_kind": "comment", "target_id": "c1", "post_id": "p1"]), .social(.post(postId: "p1", commentId: "c1")))
        XCTAssertEqual(route("reaction_person", ["target_kind": "post", "target_id": "p1"]), .social(.post(postId: "p1", commentId: nil)))
        XCTAssertEqual(route("frame_request", ["frame_id": "f1", "request_id": "r1"]), .social(.frameNudge(frameId: "f1", requestId: "r1")))
        XCTAssertEqual(route("reminder_streak", ["feed_id": "feed1", "week_of": "2026-11-16"]), .social(.feedStreak(feedId: "feed1")))
        XCTAssertEqual(route("weekly_summary", ["week_of": "2026-11-16"]), .social(.weeklySummary(weekOf: "2026-11-16")))
        XCTAssertEqual(route("report", ["report_id": "r1"]), .social(.reports(feedId: nil)))
        XCTAssertEqual(route("reaction", ["send_id": "s1"]), .send(sendId: "s1"), "older keys keep their routes")
    }

    func testThreadsActionsAndMatching() {
        XCTAssertEqual(SocialPush.threadIdentifier(["tag": "conversation:k1", "key": "message"]), "conversation:k1")
        XCTAssertEqual(SocialPush.threadIdentifier(["key": "message", "ids": ["conversation_id": "k1"]]), "conversation:k1")
        XCTAssertEqual(SocialPush.actions(["key": "reminder_quiet"]), [.snoozeMonth, .fewerReminders])
        XCTAssertEqual(SocialPush.actions(["key": "x", "actions": ["fewer_reminders"]]), [.fewerReminders])
        XCTAssertEqual(ReminderAction.snoozeMonth.update.snoozeDays, 30)
        XCTAssertEqual(ReminderAction.fewerReminders.update.level, .fewer)
        XCTAssertTrue(SocialPush.matches(["ids": ["conversation_id": "k1"]], conversationId: "k1"))
        XCTAssertFalse(SocialPush.matches(["ids": ["post_id": "p2"]], postId: "p1"))
        XCTAssertEqual(SocialPush.conversationId(["ids": ["conversation_id": "k1"]]), "k1")
    }
}

/// The thread screen's model against a stub server.
@MainActor
final class ThreadModelTests: XCTestCase {
    func testLoadsMarksReadAndComments() async throws {
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/posts/p1") { _ in FakeAPI.json(SocialFixtures.post()) },
            FakeAPI.on("GET", "/v1/posts/p1/comments") { _ in FakeAPI.json(#"{"comments":[\#(SocialFixtures.comment("c1"))],"has_more":false}"#) },
            FakeAPI.on("GET", "/v1/feeds/feed1/members") { _ in FakeAPI.json(#"[{"user_id":"u2","display_name":"Anna","role":"poster"}]"#) },
            FakeAPI.on("PUT", "/v1/posts/p1/read") { _ in .response(status: 204) },
            FakeAPI.on("POST", "/v1/posts/p1/comments") { _ in FakeAPI.json(SocialFixtures.comment("c2", parent: "c1", author: "me", name: "Me", at: "2026-11-19T11:00:00Z"), status: 201) },
            FakeAPI.on("PUT", "/v1/reactions/comment/c1") { _ in FakeAPI.json(#"{"total":1,"counts":[{"emoji":"love","count":1}],"people":[],"frames":[],"mine":"love"}"#) },
        ])
        let model = ThreadModel(api: fake.api, postId: "p1")
        await model.load()
        XCTAssertEqual(model.comments.map(\.id), ["c1"])
        XCTAssertTrue(model.candidates.contains { $0.id == "u2" })
        await model.markRead()
        let read = try XCTUnwrap(fake.requests.first { $0.method == "PUT" && $0.url.path == "/v1/posts/p1/read" })
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: read.body), .object(["up_to": .string("c1")]))

        try await model.send("@Anna yes", mentions: [Mention(userId: "u2", start: 0, length: 5)], replyTo: model.comments[0])
        let post = try XCTUnwrap(fake.requests.first { $0.method == "POST" })
        let body = try JSONDecoder().decode(JSONValue.self, from: post.body)
        XCTAssertEqual(body["parent_id"], .string("c1"))
        XCTAssertNotNil(post.header("Idempotency-Key"))
        XCTAssertEqual(model.nodes.first?.replies.map(\.id), ["c2"])
        XCTAssertEqual(model.post?.thread.commentCount, 1)

        try await model.react(model.comments[0], .love)
        XCTAssertEqual(model.comments[0].reactions?.mine, .love)
    }

    func testRemovingAReactionSendsNull() async throws {
        let fake = FakeAPI([FakeAPI.on("PUT", "/v1/reactions/post/p1") { _ in FakeAPI.json(SocialFixtures.emptyReactions) }])
        _ = try await fake.api.setReaction(.post, "p1", emoji: nil)
        let req = try XCTUnwrap(fake.requests.first)
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: req.body), .object(["emoji": .null]))
    }

    func testRemovedPost() async {
        let fake = FakeAPI([])
        let model = ThreadModel(api: fake.api, postId: "gone")
        await model.load()
        XCTAssertTrue(model.removed)
    }

    // Post photos use the uncropped display rendition (README §5.3), else the square thumbnail.
    func testPostPhotosPreferTheDisplayRendition() throws {
        let item = try SocialFixtures.decode(Item.self, #"""
        {"id":"i1","kind":"photo","status":"ready","sender_id":"u1","created_at":"2026-11-19T09:00:00Z",
         "thumb_url":"https://x/t256","display_url":"https://x/d"}
        """#)
        XCTAssertEqual(item.displayUrl, "https://x/d")
        XCTAssertEqual(item.largeImageUrl, "https://x/d")
        XCTAssertEqual(item.thumbUrl, "https://x/t256")
        let old = Item(id: "i2", kind: .photo, status: .ready, senderId: "u1", createdAt: Date(), thumbUrl: "https://x/t256")
        XCTAssertNil(old.displayUrl)
        XCTAssertEqual(old.largeImageUrl, "https://x/t256")
    }
}
