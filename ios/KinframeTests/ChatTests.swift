@testable import Kinframe
@testable import KinframeKit
import XCTest

// Chats (README §5.19) and encouragement (§5.20): the list, messages, receipts, typing, voice, composer limits,
// freshness, streaks, prompts, reminders and the conversation model against a stub server.

enum ChatFixtures {
    static func message(_ id: String, author: String? = "u2", name: String = "Anna", body: String? = "Hi", at: String = "2026-11-19T10:00:00Z",
                        conv: String = "k1", extra: String = "") -> String {
        let a = author.map { #""author":{"user_id":"\#($0)","display_name":"\#(name)"},"# } ?? ""
        let b = body.map { #""body":"\#($0)","# } ?? ""
        return #"{"id":"\#(id)","conversation_id":"\#(conv)","kind":"\#(author == nil ? "system" : "person")",\#(a)\#(b)"items":[],"reactions":\#(SocialFixtures.emptyReactions),"created_at":"\#(at)","deleted":false\#(extra)}"#
    }

    static func conversation(_ id: String = "k1", kind: String = "direct", unread: Int = 0, last: String? = nil, at: String = "2026-11-19T10:00:00Z",
                             extra: String = "") -> String {
        let l = last.map { #""last_message":\#($0),"# } ?? ""
        return #"{"id":"\#(id)","kind":"\#(kind)","name":"Anna","other":{"user_id":"u2","display_name":"Anna"},"member_count":2,"unread_count":\#(unread),\#(l)"last_activity_at":"\#(at)","muted":false,"archived":false\#(extra)}"#
    }
}

final class ChatLogicTests: XCTestCase {
    func testListOrderAndIncomingMessages() throws {
        let a = try SocialFixtures.decode(Conversation.self, ChatFixtures.conversation("k1", at: "2026-11-19T09:00:00Z"))
        let b = try SocialFixtures.decode(Conversation.self, ChatFixtures.conversation("k2", at: "2026-11-19T10:00:00Z"))
        XCTAssertEqual(ChatList.sorted([a, b]).map(\.id), ["k2", "k1"])
        let m = try SocialFixtures.decode(Message.self, ChatFixtures.message("m9", at: "2026-11-19T11:00:00Z", extra: #","mentions":[{"user_id":"me","start":0,"length":3}]"#))
        let after = ChatList.applying(m, to: [b, a], me: "me", open: nil)
        XCTAssertEqual(after.map(\.id), ["k1", "k2"], "a new message moves the chat up")
        XCTAssertEqual(after[0].unreadCount, 1)
        XCTAssertEqual(after[0].unreadMentions, 1)
        XCTAssertEqual(ChatList.applying(m, to: [a], me: "me", open: "k1")[0].unreadCount, 0, "the open chat doesn't count")
        let own = try SocialFixtures.decode(Message.self, ChatFixtures.message("m10", author: "me", at: "2026-11-19T12:00:00Z"))
        XCTAssertEqual(ChatList.applying(own, to: [a], me: "me", open: nil)[0].unreadCount, 0)
        XCTAssertEqual(ChatList.title(a), "Anna")
    }

    func testPreviews() throws {
        let text = try SocialFixtures.decode(Message.self, ChatFixtures.message("m1", body: "Look at this"))
        XCTAssertEqual(MessageText.preview(text, me: "me", kind: .group), "Anna: Look at this")
        XCTAssertEqual(MessageText.preview(text, me: "me", kind: .direct), "Look at this")
        let voice = try SocialFixtures.decode(Message.self, ChatFixtures.message("m2", body: nil, extra: #","voice":{"item_id":"i1","status":"ready","duration_ms":42000,"waveform":[]}"#))
        XCTAssertEqual(MessageText.summary(voice), "🎤 Voice message")
        let gone = try SocialFixtures.decode(Message.self, ChatFixtures.message("m3", body: nil, extra: #","removed_by_admin":true"#).replacingOccurrences(of: #""deleted":false"#, with: #""deleted":true"#))
        XCTAssertEqual(MessageText.summary(gone), "This message was removed")
        let system = try SocialFixtures.decode(Message.self, ChatFixtures.message("m4", author: nil, body: nil,
            extra: #","system":{"type":"put_on_frame","person":{"user_id":"u1","display_name":"Ben"},"item_count":12,"frame_names":["Oma’s frame"],"feed_names":["Peeters"]}"#))
        XCTAssertEqual(MessageText.preview(system, me: "me", kind: .group), "Ben put 12 photos on Oma’s frame and Peeters")
    }

    func testPendingBubblesAreReplacedByClientId() throws {
        var pending = try SocialFixtures.decode(Message.self, ChatFixtures.message("pending-abc", author: "me"))
        pending.clientId = "abc"
        let older = try SocialFixtures.decode(Message.self, ChatFixtures.message("0190a"))
        var list = MessageLogic.upsert(pending, into: [older])
        XCTAssertEqual(list.map(\.id), ["0190a", "pending-abc"])
        var sent = try SocialFixtures.decode(Message.self, ChatFixtures.message("0190b", author: "me"))
        sent.clientId = "abc"
        list = MessageLogic.upsert(sent, into: list)
        XCTAssertEqual(list.map(\.id), ["0190a", "0190b"])
        XCTAssertEqual(MessageLogic.lastReadable(list + [pending]), "0190b", "pending bubbles aren't read positions")
    }

    func testEditWindowDeleteAndDays() throws {
        let m = try SocialFixtures.decode(Message.self, ChatFixtures.message("m1", author: "me", at: "2026-11-19T10:00:00Z"))
        let soon = m.createdAt.addingTimeInterval(3600), late = m.createdAt.addingTimeInterval(25 * 3600)
        XCTAssertTrue(MessageLogic.canEdit(m, me: "me", hours: 24, now: soon))
        XCTAssertFalse(MessageLogic.canEdit(m, me: "me", hours: 24, now: late))
        XCTAssertFalse(MessageLogic.canEdit(m, me: "other", hours: 24, now: soon))
        XCTAssertTrue(MessageLogic.canDelete(m, me: "other", moderator: true))
        XCTAssertFalse(MessageLogic.canDelete(m, me: "other", moderator: false))
        let next = try SocialFixtures.decode(Message.self, ChatFixtures.message("m2", author: "me", at: "2026-11-19T10:02:00Z"))
        XCTAssertTrue(MessageLogic.continues([m, next], at: 1))
        XCTAssertTrue(MessageLogic.startsDay([m, next], at: 0))
    }

    func testReadReceipts() throws {
        let c = try SocialFixtures.decode(Conversation.self, ChatFixtures.conversation(kind: "group", extra: #","read_receipts":true,"members":[{"person":{"user_id":"u2","display_name":"Anna Peeters"},"role":"member","last_read_message_id":"m5"},{"person":{"user_id":"u3","display_name":"Ben"},"role":"member","last_read_message_id":"m1"},{"person":{"user_id":"me","display_name":"Me"},"role":"admin","last_read_message_id":"m9"}]"#))
        let m = try SocialFixtures.decode(Message.self, ChatFixtures.message("m3", author: "me"))
        let seen = ReadReceipts.seenBy(m, in: c, me: "me")
        XCTAssertEqual(seen.map(\.userId), ["u2"])
        XCTAssertEqual(ReadReceipts.text(seen, kind: .group), "Seen by Anna")
        XCTAssertEqual(ReadReceipts.text(seen, kind: .direct), "Seen")
        let moved = ReadReceipts.applying(userId: "u3", lastRead: "m4", to: c)
        XCTAssertEqual(ReadReceipts.seenBy(m, in: moved, me: "me").count, 2)
        var off = c
        off.readReceipts = false
        XCTAssertTrue(ReadReceipts.seenBy(m, in: off, me: "me").isEmpty)
    }

    func testTyping() {
        var t = TypingState()
        let now = Date()
        t.note(userId: "u2", name: "Anna", recording: false, until: now.addingTimeInterval(6))
        XCTAssertEqual(t.text(now: now), "Anna is typing…")
        t.note(userId: "u3", name: "Ben", recording: true, until: now.addingTimeInterval(6))
        XCTAssertEqual(t.text(now: now), "Anna and Ben are typing…")
        XCTAssertNil(t.text(now: now.addingTimeInterval(7)), "expired")
        t.clear(userId: "u3")
        XCTAssertEqual(t.text(now: now), "Anna is typing…")
        XCTAssertTrue(TypingState.shouldSend(last: nil))
        XCTAssertFalse(TypingState.shouldSend(last: now, now: now.addingTimeInterval(1)))
        XCTAssertTrue(TypingState.shouldSend(last: now, now: now.addingTimeInterval(3)))
    }

    func testComposerGroupsAndVoice() {
        let limits = Limits()
        XCTAssertFalse(ChatComposer.canSend(body: "  ", itemCount: 0, sharedPost: false, limits: limits))
        XCTAssertTrue(ChatComposer.canSend(body: "", itemCount: 2, sharedPost: false, limits: limits))
        XCTAssertFalse(ChatComposer.canSend(body: String(repeating: "a", count: 4001), itemCount: 0, sharedPost: false, limits: limits))
        XCTAssertEqual(ChatComposer.remaining(String(repeating: "a", count: 3900), max: 4000), 100)
        XCTAssertNil(ChatComposer.remaining("short", max: 4000))
        XCTAssertNotNil(GroupRules.problem(memberCount: 1, name: "x", limit: 100))
        XCTAssertNotNil(GroupRules.problem(memberCount: 2, name: " ", limit: 100))
        XCTAssertNil(GroupRules.problem(memberCount: 2, name: "Cousins", limit: 100))
        XCTAssertEqual(VoiceLogic.duration(65_000), "1:05")
        XCTAssertEqual(VoiceLogic.bars(Array(repeating: 50, count: 64), count: 32).count, 32)
        XCTAssertEqual(VoiceLogic.bars([], count: 4), [0.15, 0.15, 0.15, 0.15])
        XCTAssertEqual(VoiceLogic.level(decibels: 0), 100)
        XCTAssertEqual(VoiceLogic.level(decibels: -160), 0)
        XCTAssertFalse(VoiceLogic.isLongEnough(seconds: 0.5))
    }

    func testModerators() throws {
        let family = try SocialFixtures.decode(Conversation.self, ChatFixtures.conversation(kind: "family"))
        XCTAssertTrue(ChatAbilities.isModerator(family, feedRole: .admin))
        XCTAssertFalse(ChatAbilities.isModerator(family, feedRole: .poster))
        XCTAssertFalse(ChatAbilities.canLeave(family))
        let group = try SocialFixtures.decode(Conversation.self, ChatFixtures.conversation(kind: "group", extra: #","my_role":"admin""#))
        XCTAssertTrue(ChatAbilities.canManageGroup(group))
        XCTAssertTrue(ChatAbilities.canLeave(group))
    }
}

final class EngagementTests: XCTestCase {
    private func freshness(_ json: String) throws -> Freshness { try SocialFixtures.decode(Freshness.self, json) }

    func testFreshnessWords() throws {
        XCTAssertEqual(FreshnessText.label(try freshness(#"{"state":"fresh","level":1,"days_quiet":0}"#)), "Fresh")
        XCTAssertEqual(FreshnessText.label(try freshness(#"{"state":"recent","level":0.7,"days_quiet":4}"#)), "4 days")
        XCTAssertEqual(FreshnessText.label(try freshness(#"{"state":"quiet","level":0.3,"days_quiet":9}"#)), "9 days quiet")
        XCTAssertEqual(FreshnessText.label(try freshness(#"{"state":"quiet","level":0,"days_quiet":15}"#)), "2 weeks quiet")
        XCTAssertEqual(FreshnessText.label(try freshness(#"{"state":"never","level":0}"#)), "No photos yet")
        XCTAssertGreaterThan(FreshnessText.ring(try freshness(#"{"state":"quiet","level":0,"days_quiet":20}"#)), 0, "never invisible")
        XCTAssertTrue(FreshnessText.isPale(try freshness(#"{"state":"quiet","level":0.2,"days_quiet":10}"#)))
    }

    func testStreakAndPrompts() throws {
        let s = FeedStreakSummary(currentWeeks: 12, weekPhotos: 2, goal: 3, met: false)
        XCTAssertEqual(StreakText.headline(s), "🔥 12 weeks")
        XCTAssertEqual(StreakText.week(s), "2 of 3 photos this week")
        XCTAssertEqual(StreakText.progress(s), 2.0 / 3.0, accuracy: 0.001)
        XCTAssertNil(StreakText.headline(FeedStreakSummary(currentWeeks: 0, weekPhotos: 0, goal: 3, met: false)))
        let prompts = try SocialFixtures.decode([FeedPrompt].self, #"""
        [{"id":"a","feed_id":"f","week_of":"2026-11-16","status":"current","source":"server","title":"Your view right now"},
         {"id":"b","feed_id":"f","week_of":"2026-11-30","status":"planned","source":"server","title":"Cooked"},
         {"id":"c","feed_id":"f","week_of":"2026-11-23","status":"skipped","source":"custom","title":""},
         {"id":"d","feed_id":"f","week_of":"2026-11-09","status":"past","source":"server","title":"Old"}]
        """#)
        XCTAssertEqual(PromptLogic.current(prompts)?.id, "a")
        XCTAssertEqual(PromptLogic.planned(prompts).map(\.id), ["c", "b"])
        XCTAssertEqual(PromptLogic.past(prompts).map(\.id), ["d"])
    }

    func testWeeksAndQuietHours() {
        var cal = Calendar(identifier: .iso8601)
        cal.timeZone = TimeZone(identifier: "Europe/Brussels")!
        let thursday = cal.date(from: DateComponents(year: 2026, month: 11, day: 19, hour: 12))!
        XCTAssertEqual(WeekOf.previousMonday(thursday, calendar: cal), "2026-11-09")
        XCTAssertEqual(ReminderLogic.minutes("21:30"), 1290)
        XCTAssertNil(ReminderLogic.minutes("25:00"))
        XCTAssertEqual(ReminderLogic.hhmm(-30), "23:30")
        XCTAssertEqual(MonthText.name(nil), "")
    }

    func testSummaryAndMilestones() throws {
        let s = try SocialFixtures.decode(WeeklySummary.self, #"{"week_of":"2026-11-09","seen_count":58,"frame_count":3,"love_count":4,"reaction_count":0,"comment_count":2,"frames":[]}"#)
        XCTAssertEqual(SummaryText.seen(s), "Your photos were seen 58 times on 3 frames")
        XCTAssertEqual(SummaryText.details(s), "❤️ 4 loves from the frames · 2 comments")
        let ms = try SocialFixtures.decode([Milestone].self, #"[{"key":"first_photo","achieved_at":"2026-01-01T00:00:00Z","shown":false,"seen":true},{"key":"photos_10","achieved_at":"2026-02-01T00:00:00Z","shown":false,"seen":false}]"#)
        XCTAssertEqual(MilestoneLogic.unseen(ms).map(\.key), [.photos10])
        XCTAssertEqual(MilestoneKey.loves100.title, "100 hearts from the frames")
    }
}

/// The conversation screen's model against a stub server: load, send (optimistic bubble), mark read, live events.
@MainActor
final class ConversationModelTests: XCTestCase {
    func testSendsWithAPendingBubbleAndMarksRead() async throws {
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/conversations/k1") { _ in FakeAPI.json(ChatFixtures.conversation()) },
            FakeAPI.on("GET", "/v1/conversations/k1/messages") { _ in FakeAPI.json(#"{"messages":[\#(ChatFixtures.message("m1"))],"has_more_before":true,"has_more_after":false}"#) },
            FakeAPI.on("PUT", "/v1/conversations/k1/read") { _ in .response(status: 204) },
            FakeAPI.on("POST", "/v1/conversations/k1/messages") { r in
                let body = (try? JSONDecoder().decode(JSONValue.self, from: r.body)) ?? .null
                guard case .string(let cid)? = body["client_id"] else { return .response(status: 422) }
                return FakeAPI.json(ChatFixtures.message("m2", author: "me", name: "Me", body: "Hello", at: "2026-11-19T10:05:00Z", extra: #","client_id":"\#(cid)""#), status: 201)
            },
        ])
        let model = ConversationModel(api: fake.api, id: "k1")
        await model.load()
        XCTAssertEqual(model.messages.map(\.id), ["m1"])
        XCTAssertTrue(model.hasMoreBefore)
        await model.markRead()
        XCTAssertTrue(fake.requests.contains { $0.method == "PUT" && $0.url.path == "/v1/conversations/k1/read" })

        await model.sendText("Hello", mentions: [], replyTo: model.messages[0], linkPreview: false)
        XCTAssertEqual(model.messages.map(\.id), ["m1", "m2"], "the pending bubble became the message")
        let post = try XCTUnwrap(fake.requests.first { $0.method == "POST" })
        let body = try JSONDecoder().decode(JSONValue.self, from: post.body)
        XCTAssertEqual(body["reply_to_id"], .string("m1"))
        XCTAssertEqual(body["link_preview"], .bool(false))
        XCTAssertEqual(post.header("Idempotency-Key")?.hasPrefix("msg-"), true)

        // A live edit replaces it; a tombstone stays in place.
        let edited = try SocialFixtures.decode(Message.self, ChatFixtures.message("m2", author: "me", body: "Hello!", at: "2026-11-19T10:05:00Z", extra: #","edited_at":"2026-11-19T10:06:00Z""#))
        model.apply(.social(.messageUpdated(conversationId: "k1", message: edited)))
        XCTAssertEqual(model.messages.last?.body, "Hello!")
        model.apply(.social(.conversationRemoved(conversationId: "k1")))
        XCTAssertTrue(model.gone)
    }

    func testFailedSendCanBeRetried() async throws {
        let fake = FakeAPI([
            FakeAPI.on("POST", "/v1/conversations/k1/messages") { _ in FakeAPI.json(#"{"type":"x","title":"Nope","status":403,"code":"cannot_message"}"#, status: 403) },
        ])
        let model = ConversationModel(api: fake.api, id: "k1")
        await model.sendText("Hi", mentions: [], replyTo: nil, linkPreview: true)
        let pending = try XCTUnwrap(model.messages.first)
        XCTAssertTrue(MessageLogic.isPending(pending))
        XCTAssertNotNil(model.failed[pending.clientId ?? ""])
        XCTAssertEqual(model.error, "You can’t send this person a message.")
        model.discard(pending)
        XCTAssertTrue(model.messages.isEmpty)
    }
}
