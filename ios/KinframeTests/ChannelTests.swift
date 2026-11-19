@testable import KinframeKit
import XCTest

/// Email to frame and the Telegram bridge: decoding of the contract's schemas (also with enum values a newer server
/// may send), the friendly part, the share message, status and outcome texts, request bodies, t.me links, following a
/// code, credits, join requests, live events, push routes and discovery. Fixtures follow docs/api/openapi.yaml and the
/// examples in docs/spec/email-inbox.md and docs/spec/telegram.md.
enum ChannelFixtures {
    static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }

    static let frameTarget = #"{"kind":"frame","frame_id":"f1","frame_name":"Oma’s frame","feed_id":null,"feed_name":null}"#
    static let feedTarget = #"{"kind":"feed","frame_id":null,"frame_name":null,"feed_id":"feed1","feed_name":"Peeters kids"}"#

    static func address(status: String = "active", target: String = frameTarget, pending: Int = 0, anyone: Bool = false,
                        last: String? = "2026-11-19T10:42:00Z", isOwner: Bool = true, replies: Bool = true) -> String {
        """
        {"id":"ad1","address":"oma-k7q2m9@in.kinframe.appmire.be","slug":"oma","name":"Oma’s frame","target":\(target),
         "owner":{"user_id":"u1","display_name":"Anna","avatar_url":null},"status":"\(status)","anyone_may_send":\(anyone),
         "send_confirmations":true,"offer_app_invite":true,"notify_reactions":true,"replies_available":\(replies),
         "counts":{"allowed_senders":3,"blocked_senders":1,"pending_senders":\(pending),"held_messages":\(pending * 2),"delivered_30d":12},
         "last_received_at":\(last.map { "\"\($0)\"" } ?? "null"),"regenerated_at":null,"can_manage":true,"is_owner":\(isOwner),
         "created_at":"2026-11-01T09:00:00Z"}
        """
    }

    static func held(id: String = "m1", reason: String = "unknown_sender", authenticated: Bool = true, count: Int = 3,
                     expires: String = "2026-12-03T10:42:00Z") -> String {
        """
        {"id":"\(id)","reason":"\(reason)","subject":"Garden party","received_at":"2026-11-19T10:42:00Z","expires_at":"\(expires)",
         "media_count":\(count),"authenticated":\(authenticated),"thumb_urls":["https://cdn.example/t1.jpg","https://cdn.example/t2.jpg"]}
        """
    }

    static func pending(authenticated: Bool = true, messages: [String] = [held(), held(id: "m2", count: 2)]) -> String {
        """
        {"id":"ps1","email":"rita@gmail.com","display_name":"Rita","authenticated":\(authenticated),
         "first_held_at":"2026-11-18T09:00:00Z","last_held_at":"2026-11-19T10:42:00Z","messages":[\(messages.joined(separator: ","))]}
        """
    }

    static func message(outcome: String, items: Int = 4, name: String? = "Jan", email: String? = "jan.peeters@telenet.be",
                        reply: String? = "delivered", skipped: String = "null") -> String {
        """
        {"id":"em1","received_at":"2026-11-18T18:03:00Z","sender_email":\(email.map { "\"\($0)\"" } ?? "null"),
         "sender_name":\(name.map { "\"\($0)\"" } ?? "null"),"authenticated":true,"outcome":"\(outcome)","items":\(items),
         "skipped":\(skipped),"send_ids":["s1"],"reply":\(reply.map { "\"\($0)\"" } ?? "null")}
        """
    }

    static func chat(status: String = "active", kind: String = "group", error: String? = nil, ended: String? = nil, endedBy: String? = nil,
                     warnings: [String] = [], target: String = frameTarget, isOwner: Bool = true, last: String? = "2026-11-19T10:32:00Z",
                     id: String = "c1", created: String = "2026-11-10T09:00:00Z", endedAt: String? = nil) -> String {
        """
        {"id":"\(id)","chat_kind":"\(kind)","title":"Peeters family","is_forum":true,"target":\(target),
         "owner":{"user_id":"u1","display_name":"Anna","avatar_url":null},"status":"\(status)",
         "error_code":\(error.map { "\"\($0)\"" } ?? "null"),"warnings":[\(warnings.map { "\"\($0)\"" }.joined(separator: ","))],
         "ended_reason":\(ended.map { "\"\($0)\"" } ?? "null"),"ended_by_name":\(endedBy.map { "\"\($0)\"" } ?? "null"),
         "ended_at":\(endedAt.map { "\"\($0)\"" } ?? "null"),"bot_is_admin":false,"sees_all_messages":\(error != "privacy_mode"),
         "contributors":"everyone","frame_replies":false,"notify_reactions":false,"offer_app_invite":true,"topic_id":null,
         "topics":[{"id":1,"name":"General"},{"id":42,"name":"Photos"}],
         "counts":{"sent":48,"duplicates":2,"skipped":1,"failed":0,"contributors":5},
         "last_delivery_at":\(last.map { "\"\($0)\"" } ?? "null"),"can_manage":true,"is_owner":\(isOwner),"created_at":"\(created)"}
        """
    }

    static func code(status: String = "open", error: String? = nil, chatId: String? = nil, kind: String = "group",
                     url: String? = "https://t.me/kinframe_bot?startgroup=cA7f9QxK2mPz4LwN8", expires: String = "2026-11-19T11:00:00Z") -> String {
        """
        {"id":"code1","purpose":"connect","chat_kind":"\(kind)","link_account":false,"target":\(frameTarget),"code":"cA7f9QxK2mPz4LwN8",
         "url":\(url.map { "\"\($0)\"" } ?? "null"),"status":"\(status)","expires_at":"\(expires)",
         "chat_id":\(chatId.map { "\"\($0)\"" } ?? "null"),"error_code":\(error.map { "\"\($0)\"" } ?? "null")}
        """
    }

    static func joinRequest(via: String, viaName: String? = nil, matches: Bool? = nil, invitedBy: String? = nil) -> String {
        """
        {"id":"jr1","user":{"id":"u9","display_name":"Jan","avatar_url":null},"via":"\(via)",
         "invited_by_name":\(invitedBy.map { "\"\($0)\"" } ?? "null"),"note":null,
         "via_name":\(viaName.map { "\"\($0)\"" } ?? "null"),"via_matches":\(matches.map { "\($0)" } ?? "null"),
         "created_at":"2026-11-19T10:00:00Z"}
        """
    }

    static let now = FlexibleDateTranscoder.parse("2026-11-19T10:50:00Z")!
}

final class EmailInboxDecodingTests: XCTestCase {
    func testAddressDecodes() throws {
        let a = try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(pending: 2))
        XCTAssertEqual(a.state, .active)
        XCTAssertFalse(a.isPaused)
        XCTAssertEqual(a.channelTarget, .frame("f1"))
        XCTAssertEqual(a.targetName, "Oma’s frame")
        XCTAssertEqual(a.ownerName, "Anna")
        XCTAssertEqual(a.pendingCount, 2)
        XCTAssertEqual(a.counts.delivered30d, 12)
        XCTAssertTrue(a.hasReplies)
        XCTAssertTrue(a.mayChangeNotifyReactions)
        let feed = try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(target: ChannelFixtures.feedTarget, isOwner: false))
        XCTAssertEqual(feed.channelTarget, .feed("feed1"))
        XCTAssertTrue(feed.isFeedTarget)
        XCTAssertEqual(feed.targetName, "Peeters kids")
        XCTAssertFalse(feed.mayChangeNotifyReactions, "notify_reactions is the owner's own switch")
    }

    func testUnknownEnumValuesDoNotBreakDecoding() throws {
        let future = ChannelFixtures.address(status: "archived", target: #"{"kind":"album","frame_id":"f1"}"#)
        let a = try ChannelFixtures.decode(EmailAddress.self, future)
        XCTAssertNil(a.state)
        XCTAssertNil(a.channelTarget)
        XCTAssertEqual(EmailText.headline(a).key, "ios.em.status.unknown")
        XCTAssertEqual(try ChannelFixtures.decode([EmailAddress].self, "[\(future),\(ChannelFixtures.address())]").count, 2)

        let sender = try ChannelFixtures.decode(EmailSender.self, """
        {"id":"s1","email":"jan@x.be","display_name":"Jan","state":"muted","origin":"import","added_by_name":"Anna",
         "last_message_at":null,"created_at":"2026-11-01T09:00:00Z"}
        """)
        XCTAssertNil(sender.knownState)
        XCTAssertNil(sender.knownOrigin)
        XCTAssertFalse(sender.isBlocked)

        let p = try ChannelFixtures.decode(EmailPendingSender.self, ChannelFixtures.pending(messages: [ChannelFixtures.held(reason: "quarantined")]))
        XCTAssertNil(p.messages[0].heldReason)

        let page = try ChannelFixtures.decode(EmailMessagePage.self, """
        {"items":[\(ChannelFixtures.message(outcome: "virus", reply: "bounced")),\(ChannelFixtures.message(outcome: "delivered"))],"next_cursor":"c2"}
        """)
        XCTAssertNil(page.items[0].knownOutcome)
        XCTAssertNil(page.items[0].knownReply)
        XCTAssertEqual(EmailText.outcome(page.items[0]).key, "ios.em.outcome.unknown")
        XCTAssertEqual(EmailText.reply(page.items[0])?.key, "ios.em.reply.unknown")
        XCTAssertEqual(page.nextCursor, "c2")

        let held = try ChannelFixtures.decode(EmailHeldMessage.self, """
        {"id":"m1","address_id":"ad1","sender_id":"ps1","sender_email":"rita@gmail.com","sender_name":"Rita","reason":"unverified_sender",
         "subject":"Garden party","caption":"Garden party","received_at":"2026-11-19T10:42:00Z","expires_at":"2026-12-03T10:42:00Z",
         "auth":{"authenticated":false,"from_domain":"gmail.com","spf":"future_result","dkim":"none","dkim_domain":null,"dmarc":"fail",
                 "dmarc_policy":"none","aligned":null},
         "media":[{"kind":"photo","width":4032,"height":3024,"duration_ms":null,"bytes":2400000,"filename":"IMG_1.jpg","thumb_url":"https://cdn.example/t1.jpg"},
                  {"kind":"video","width":1920,"height":1080,"duration_ms":12000,"bytes":9000000,"filename":"v.mp4","thumb_url":null}],
         "skipped":{"too_small":2,"duplicate":0,"too_long":1}}
        """)
        XCTAssertTrue(held.isUnverified)
        XCTAssertEqual(held.senderLabel, "Rita (rita@gmail.com)")
        XCTAssertEqual(held.media.count, 2)
        XCTAssertEqual(EmailText.skipped(held.skipped).map(\.key), ["ios.em.skip.too_small", "ios.em.skip.too_long"])
        XCTAssertEqual(EmailText.skipped(held.skipped).map(\.count), [2, 1])
    }

    func testJoinRequestWithFutureChannelDecodes() throws {
        let r = try ChannelFixtures.decode(JoinRequest.self, ChannelFixtures.joinRequest(via: "whatsapp", viaName: "Ben · WhatsApp"))
        XCTAssertNil(r.channel)
        XCTAssertEqual(JoinRequestOrigin.line(r).key, "ios.join.viaOther")
        XCTAssertEqual(JoinRequestOrigin.channelName(r)?.name, "Ben · WhatsApp")
    }
}

final class EmailInboxLogicTests: XCTestCase {
    func testSlugSuggestions() {
        XCTAssertEqual(EmailSlug.suggest(from: "Oma's frame"), "oma-frame")
        XCTAssertEqual(EmailSlug.suggest(from: "Oma’s frame"), "oma-frame")
        XCTAssertEqual(EmailSlug.suggest(from: "Peeters kids 📸"), "peeters-kids")
        XCTAssertEqual(EmailSlug.suggest(from: "Café Zoë"), "cafe-zoe")
        XCTAssertEqual(EmailSlug.suggest(from: "  "), "photos")
        XCTAssertEqual(EmailSlug.suggest(from: "Η κορνίζα"), "photos", "nothing usable in ASCII")
        XCTAssertEqual(EmailSlug.suggest(from: "The frame in grandma's living room at the seaside").count, 24)
        XCTAssertFalse(EmailSlug.suggest(from: "abcdefghijklmnopqrstuvw xyz").hasSuffix("-"))
        for name in ["Oma's frame", "Peeters kids 📸", "Café Zoë", "A--B", "-x-"] {
            XCTAssertTrue(EmailSlug.isValid(EmailSlug.suggest(from: name)), name)
        }
    }

    func testSlugInput() {
        XCTAssertEqual(EmailSlug.clean("Oma Jan!"), "oma-jan")
        XCTAssertEqual(EmailSlug.clean("oma-"), "oma")
        XCTAssertEqual(EmailSlug.typing("oma-"), "oma-", "a trailing dash survives while typing")
        XCTAssertEqual(EmailSlug.typing("Oma Jan"), "oma-jan")
        XCTAssertEqual(EmailSlug.typing("-oma"), "oma")
        XCTAssertTrue(EmailSlug.isValid("oma"))
        XCTAssertTrue(EmailSlug.isValid("o"))
        XCTAssertFalse(EmailSlug.isValid(""))
        XCTAssertFalse(EmailSlug.isValid("oma-"))
        XCTAssertFalse(EmailSlug.isValid("-oma"))
        XCTAssertFalse(EmailSlug.isValid("Oma"))
        XCTAssertFalse(EmailSlug.isValid(String(repeating: "a", count: 25)))
        XCTAssertEqual(EmailSlug.preview("oma", domain: "in.kinframe.appmire.be"), "oma-······@in.kinframe.appmire.be")
        XCTAssertEqual(EmailSlug.preview("", domain: nil), "…-······")
    }

    func testShareMessage() throws {
        let a = try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address())
        XCTAssertEqual(EmailShare.message(a, template: "ios.em.shareText"),
                       "You can now email photos to Oma’s frame: oma-k7q2m9@in.kinframe.appmire.be. Just attach them; the subject becomes the caption. The first time, Anna approves your address.",
                       "a missing catalog falls back to English")
        let nl = "Je kunt nu foto’s mailen naar {name}: {address}. Voeg ze gewoon toe; het onderwerp wordt het bijschrift. De eerste keer keurt {owner} je adres goed."
        XCTAssertEqual(EmailShare.message(a, template: nl),
                       "Je kunt nu foto’s mailen naar Oma’s frame: oma-k7q2m9@in.kinframe.appmire.be. Voeg ze gewoon toe; het onderwerp wordt het bijschrift. De eerste keer keurt Anna je adres goed.")
        let open = try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(anyone: true))
        XCTAssertFalse(EmailShare.message(open, openTemplate: "x").contains("approves"), "anyone may send: no approval sentence")
        XCTAssertTrue(EmailShare.message(open, openTemplate: "x").contains("oma-k7q2m9@"))
    }

    func testHeadlineAndCounts() throws {
        let a = try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(pending: 2))
        let h = EmailText.headline(a, now: ChannelFixtures.now, locale: Locale(identifier: "en"))
        XCTAssertEqual(h.key, "ios.em.status.readyLast")
        XCTAssertEqual(h.tone, .ok)
        XCTAssertNotNil(h.args["when"])
        XCTAssertEqual(EmailText.headline(try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(last: nil))).key, "ios.em.status.ready")
        let paused = try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(status: "paused"))
        XCTAssertTrue(paused.isPaused)
        XCTAssertEqual(EmailText.headline(paused).key, "ios.em.status.paused")
        XCTAssertEqual(EmailText.headline(paused).tone, .paused)
        XCTAssertEqual(EmailText.counts(a).map(\.key), ["ios.em.count.pending", "ios.em.count.delivered", "ios.em.count.allowed"])
        XCTAssertEqual(EmailText.counts(a).map(\.count), [2, 12, 3])
        XCTAssertEqual(EmailText.membersNote(a).key, "ios.em.membersNote")
        XCTAssertFalse(try ChannelFixtures.decode(EmailAddress.self, ChannelFixtures.address(replies: false)).hasReplies)
    }

    func testPendingSenders() throws {
        let p = try ChannelFixtures.decode(EmailPendingSender.self, ChannelFixtures.pending())
        XCTAssertEqual(p.label, "Rita (rita@gmail.com)")
        XCTAssertEqual(p.mediaCount, 5)
        XCTAssertFalse(p.hasUnverified)
        let line = EmailText.pending(p)
        XCTAssertEqual(line.key, "ios.em.pending.wants")
        XCTAssertEqual(line.count, 5)
        XCTAssertEqual(line.args["name"], "Rita (rita@gmail.com)")
        let mixed = try ChannelFixtures.decode(EmailPendingSender.self, ChannelFixtures.pending(messages: [
            ChannelFixtures.held(), ChannelFixtures.held(id: "m2", reason: "unverified_sender", authenticated: false),
        ]))
        XCTAssertTrue(mixed.hasUnverified, "approving leaves unverified mail held")
        XCTAssertTrue(mixed.messages[1].isUnverified)
        XCTAssertEqual(EmailText.person(nil, "rita@gmail.com"), "rita@gmail.com")
        XCTAssertEqual(EmailText.person("rita@gmail.com", "rita@gmail.com"), "rita@gmail.com")
    }

    func testExpiry() {
        let now = ChannelFixtures.now
        XCTAssertEqual(EmailText.expiry(now.addingTimeInterval(14 * 86_400), now: now), SourceLine("ios.em.held.expires", count: 14))
        let soon = EmailText.expiry(now.addingTimeInterval(2.5 * 86_400), now: now)
        XCTAssertEqual(soon.count, 3)
        XCTAssertEqual(soon.tone, .attention)
        XCTAssertEqual(EmailText.expiry(now.addingTimeInterval(3_600), now: now).key, "ios.em.held.expiresToday")
        XCTAssertEqual(EmailText.unverified("rita@gmail.com"), SourceLine("ios.em.held.unverified", ["email": "rita@gmail.com"], tone: .attention))
    }

    func testOutcomes() throws {
        func outcome(_ raw: String, _ extra: (inout String) -> Void = { _ in }) throws -> SourceLine {
            var json = ChannelFixtures.message(outcome: raw)
            extra(&json)
            return EmailText.outcome(try ChannelFixtures.decode(EmailMessage.self, json))
        }
        let delivered = try outcome("delivered")
        XCTAssertEqual(delivered, SourceLine("ios.em.outcome.delivered", ["name": "Jan"], tone: .ok, count: 4))
        XCTAssertEqual(try outcome("held").key, "ios.em.outcome.held")
        XCTAssertEqual(try outcome("no_media").tone, .error)
        XCTAssertEqual(try outcome("rejected_dmarc").args["domain"], "telenet.be")
        XCTAssertEqual(try outcome("blocked").args, [:], "blocked senders are not named")
        for raw in InboxOutcome.allCases.map(\.rawValue) {
            XCTAssertEqual(try outcome(raw).key, "ios.em.outcome.\(raw)")
        }
        let anonymous = EmailText.outcome(try ChannelFixtures.decode(EmailMessage.self, ChannelFixtures.message(outcome: "no_media", name: nil)))
        XCTAssertEqual(anonymous.args["name"], "jan.peeters@telenet.be", "no display name: the address")
        let m = try ChannelFixtures.decode(EmailMessage.self, ChannelFixtures.message(outcome: "delivered", reply: "no_media"))
        XCTAssertEqual(EmailText.reply(m)?.key, "ios.em.reply.no_media")
        XCTAssertNil(EmailText.reply(try ChannelFixtures.decode(EmailMessage.self, ChannelFixtures.message(outcome: "blocked", reply: nil))))
    }

    func testBodies() throws {
        var draft = EmailAddressDraft(targetName: "Oma's frame")
        XCTAssertEqual(draft.slug, "oma-frame")
        XCTAssertNil(draft.problem)
        draft.slug = "Oma Jan"
        let body = draft.body
        XCTAssertEqual(body.slug, "oma-jan")
        XCTAssertEqual(body.name, "Oma's frame")
        XCTAssertEqual(body.anyoneMaySend, false)
        XCTAssertEqual(body.sendConfirmations, true)
        draft.slug = "--"
        XCTAssertEqual(draft.problem?.key, "ios.em.err.slug")
        draft.slug = "oma"
        draft.name = "  "
        XCTAssertNil(draft.body.name, "an empty label leaves the default to the server")

        XCTAssertEqual(EmailAddressChange.paused(true).body.paused, true)
        XCTAssertNil(EmailAddressChange.paused(true).body.slug, "omitted fields are kept")
        XCTAssertEqual(EmailAddressChange.slug("Oma 2").body.slug, "oma-2")
        XCTAssertEqual(EmailAddressChange.takeOver.body.takeOver, true)
        let json = String(decoding: try JSONEncoder().encode(EmailAddressChange.anyoneMaySend(true).body), as: UTF8.self)
        XCTAssertEqual(json, #"{"anyone_may_send":true}"#)

        XCTAssertTrue(EmailSenderInput.isValid("jan.peeters@telenet.be"))
        XCTAssertTrue(EmailSenderInput.isValid(" jan@x.be "))
        for bad in ["jan", "jan@", "@x.be", "jan@x", "jan @x.be", "jan@x.be.", "a@b@c.be"] {
            XCTAssertFalse(EmailSenderInput.isValid(bad), bad)
        }
        let sender = EmailSenderInput.body(email: " jan@x.be ", name: " Jan ")
        XCTAssertEqual(sender.email, "jan@x.be")
        XCTAssertEqual(sender.displayName, "Jan")
        XCTAssertNil(sender.blocked)
        XCTAssertEqual(EmailSenderInput.body(email: "a@b.be", name: "", blocked: true).blocked, true)
    }

    func testProblems() {
        XCTAssertEqual(EmailText.problem(APIError(status: 409, code: "duplicate_sender")), L("ios.em.err.duplicateSender"))
        XCTAssertEqual(EmailText.problem(APIError(status: 409, code: "already_decided")), L("ios.em.err.alreadyDecided"))
        XCTAssertNil(EmailText.problem(APIError(status: 500, code: "server_error")))
    }
}

final class TelegramTests: XCTestCase {
    func testChatDecodes() throws {
        let c = try ChannelFixtures.decode(TelegramChat.self, ChannelFixtures.chat(warnings: ["cannot_post", "slow_mode"]))
        XCTAssertEqual(c.kind, .group)
        XCTAssertEqual(c.state, .active)
        XCTAssertEqual(c.knownWarnings, [.cannotPost], "unknown warnings are left out")
        XCTAssertEqual(c.contributorsMode, .everyone)
        XCTAssertEqual(c.channelTarget, .frame("f1"))
        XCTAssertEqual(c.targetName, "Oma’s frame")
        XCTAssertTrue(c.isLive)
        XCTAssertTrue(c.isEditable)
        XCTAssertTrue(c.isForumGroup)
        XCTAssertEqual(c.topics?.map(\.id), [1, 42])
        XCTAssertEqual(c.counts.sent, 48)
    }

    func testUnknownEnumValuesDoNotBreakDecoding() throws {
        let future = ChannelFixtures.chat(status: "archived", kind: "channel", error: "rights_missing", ended: "banned")
            .replacingOccurrences(of: #""contributors":"everyone""#, with: #""contributors":"admins_only""#)
        let c = try ChannelFixtures.decode(TelegramChat.self, future)
        XCTAssertNil(c.state)
        XCTAssertNil(c.kind)
        XCTAssertNil(c.error)
        XCTAssertNil(c.ended)
        XCTAssertNil(c.contributorsMode)
        XCTAssertEqual(TelegramText.headline(c).key, "ios.tg.status.unknown")
        XCTAssertEqual(try ChannelFixtures.decode([TelegramChat].self, "[\(future),\(ChannelFixtures.chat())]").count, 2)
        let code = try ChannelFixtures.decode(TelegramCode.self, ChannelFixtures.code(status: "paused", error: "flood_wait")
            .replacingOccurrences(of: #""purpose":"connect""#, with: #""purpose":"join""#))
        XCTAssertNil(code.state)
        XCTAssertEqual(TelegramCodeProgress.of(code, now: ChannelFixtures.now), .waiting(problem: SourceLine("ios.tg.codeErr.unknown", tone: .error)))
    }

    func testStatusTexts() throws {
        func chat(_ json: String) throws -> TelegramChat { try ChannelFixtures.decode(TelegramChat.self, json) }
        let active = try chat(ChannelFixtures.chat())
        XCTAssertEqual(TelegramText.headline(active, now: ChannelFixtures.now).key, "ios.tg.status.activeLast")
        XCTAssertEqual(TelegramText.headline(try chat(ChannelFixtures.chat(last: nil))).key, "ios.tg.status.active")
        let privacy = try chat(ChannelFixtures.chat(status: "needs_attention", error: "privacy_mode"))
        XCTAssertEqual(TelegramText.headline(privacy), SourceLine("ios.tg.status.privacy_mode", ["title": "Peeters family"], tone: .attention))
        XCTAssertEqual(TelegramText.fix(privacy), .makeAdmin)
        let changed = try chat(ChannelFixtures.chat(status: "needs_attention", error: "bot_changed"))
        XCTAssertEqual(TelegramText.fix(changed), .reconnect)
        XCTAssertNil(TelegramText.fix(active))
        XCTAssertEqual(TelegramText.headline(try chat(ChannelFixtures.chat(status: "paused"))).tone, .paused)
        XCTAssertEqual(TelegramText.warnings(try chat(ChannelFixtures.chat(warnings: ["reactions_off"]))).map(\.key), ["ios.tg.warn.reactions_off"])

        func ended(_ reason: String, by: String? = nil, kind: String = "group") throws -> SourceLine {
            TelegramText.headline(try chat(ChannelFixtures.chat(status: "disconnected", kind: kind, ended: reason, endedBy: by)))
        }
        XCTAssertEqual(try ended("disconnected", by: "Anna"), SourceLine("ios.tg.ended.disconnectedBy", ["name": "Anna"]))
        XCTAssertEqual(try ended("disconnected").key, "ios.tg.ended.disconnected")
        XCTAssertEqual(try ended("stopped", by: "Ben"), SourceLine("ios.tg.ended.stoppedBy", ["name": "Ben"]))
        XCTAssertEqual(try ended("bot_removed").key, "ios.tg.ended.bot_removed")
        XCTAssertEqual(try ended("chat_gone").key, "ios.tg.ended.chat_gone")
        XCTAssertEqual(try ended("bot_blocked", kind: "private").args["name"], "Peeters family")
        XCTAssertEqual(try ended("target_deleted").key, "ios.tg.ended.disconnected")
        let gone = try chat(ChannelFixtures.chat(status: "disconnected", ended: "bot_removed", warnings: ["cannot_post"]))
        XCTAssertFalse(gone.isEditable)
        XCTAssertTrue(TelegramText.warnings(gone).isEmpty, "no warnings on a disconnected chat")

        XCTAssertEqual(TelegramText.counts(active).map(\.key), ["ios.tg.count.sent", "ios.tg.count.people"])
        XCTAssertEqual(TelegramText.subtitle(active).map(\.key), ["ios.tg.kind.group"])
        let other = try chat(ChannelFixtures.chat(kind: "private", isOwner: false))
        XCTAssertEqual(TelegramText.subtitle(other), [SourceLine("ios.tg.kind.private"), SourceLine("ios.tg.connectedBy", ["name": "Anna"])])
        XCTAssertFalse(other.mayChangeNotifyReactions)
    }

    func testOrderAndLimit() throws {
        let chats = try [
            ChannelFixtures.chat(id: "a", created: "2026-11-01T09:00:00Z"),
            ChannelFixtures.chat(status: "disconnected", ended: "stopped", id: "b", endedAt: "2026-11-10T09:00:00Z"),
            ChannelFixtures.chat(status: "needs_attention", error: "privacy_mode", id: "c", created: "2026-11-05T09:00:00Z"),
            ChannelFixtures.chat(status: "disconnected", ended: "chat_gone", id: "d", endedAt: "2026-11-12T09:00:00Z"),
        ].map { try ChannelFixtures.decode(TelegramChat.self, $0) }
        XCTAssertEqual(TelegramChats.live(chats).map(\.id), ["c", "a"], "needing attention first")
        XCTAssertEqual(TelegramChats.earlier(chats).map(\.id), ["d", "b"], "newest first")
        var limits = Limits.defaults
        limits.maxTelegramChatsPerTarget = 2
        XCTAssertTrue(TelegramChats.atLimit(chats, limits: limits))
        limits.maxTelegramChatsPerTarget = 3
        XCTAssertFalse(TelegramChats.atLimit(chats, limits: limits), "disconnected chats don't count")
    }

    func testLinks() throws {
        XCTAssertEqual(TelegramLinks.bot("@kinframe_bot")?.absoluteString, "https://t.me/kinframe_bot")
        XCTAssertNil(TelegramLinks.bot("bad name"))
        let group = try XCTUnwrap(TelegramLinks.web("https://t.me/kinframe_bot?startgroup=cA7f9QxK2mPz4LwN8"))
        XCTAssertEqual(TelegramLinks.app(group)?.absoluteString, "tg://resolve?domain=kinframe_bot&startgroup=cA7f9QxK2mPz4LwN8")
        let start = try XCTUnwrap(TelegramLinks.web("https://t.me/kinframe_bot?start=lQ2w"))
        XCTAssertEqual(TelegramLinks.app(start)?.absoluteString, "tg://resolve?domain=kinframe_bot&start=lQ2w")
        XCTAssertNil(TelegramLinks.web("https://evil.example/kinframe_bot?start=x"), "only t.me links are opened")
        XCTAssertNil(TelegramLinks.web("http://t.me/kinframe_bot?start=x"))
        XCTAssertNil(TelegramLinks.web("https://t.me/a/b"))
        XCTAssertNil(TelegramLinks.web(nil))
        let odd = try XCTUnwrap(TelegramLinks.web("https://t.me/kinframe_bot?start=a%20b&x=1"))
        XCTAssertEqual(TelegramLinks.app(odd)?.absoluteString, "tg://resolve?domain=kinframe_bot", "unexpected parameters are dropped")
    }

    func testFollowingACode() throws {
        let now = ChannelFixtures.now
        func progress(_ json: String) throws -> TelegramCodeProgress {
            TelegramCodeProgress.of(try ChannelFixtures.decode(TelegramCode.self, json), targetName: "Oma’s frame", now: now)
        }
        XCTAssertEqual(try progress(ChannelFixtures.code()), .waiting(problem: nil))
        XCTAssertEqual(try progress(ChannelFixtures.code(status: "used", chatId: "c1")), .done(chatId: "c1"))
        XCTAssertEqual(try progress(ChannelFixtures.code(status: "expired")), .expired)
        XCTAssertEqual(try progress(ChannelFixtures.code(status: "revoked")), .expired)
        XCTAssertEqual(try progress(ChannelFixtures.code(expires: "2026-11-19T10:00:00Z")), .expired, "past expires_at")
        XCTAssertEqual(try progress(ChannelFixtures.code(error: "already_connected")),
                       .waiting(problem: SourceLine("ios.tg.codeErr.already_connected", tone: .attention)), "the code may still work")
        XCTAssertEqual(try progress(ChannelFixtures.code(error: "wrong_chat_kind")),
                       .waiting(problem: SourceLine("ios.tg.codeErr.wrong_chat_kind_group", tone: .attention)))
        XCTAssertEqual(try progress(ChannelFixtures.code(error: "wrong_chat_kind", kind: "private")),
                       .waiting(problem: SourceLine("ios.tg.codeErr.wrong_chat_kind_private", tone: .attention)))
        XCTAssertEqual(try progress(ChannelFixtures.code(error: "limit_reached")),
                       .waiting(problem: SourceLine("ios.tg.codeErr.limit_reached", ["name": "Oma’s frame"], tone: .error)))
        XCTAssertTrue(TelegramCodeProgress.expired.isFinal)
        XCTAssertFalse(TelegramCodeProgress.waiting(problem: nil).isFinal)
        let link = try ChannelFixtures.decode(TelegramCode.self, ChannelFixtures.code(url: nil))
        XCTAssertNil(TelegramLinks.web(link.url), "the url is only in the create response")
    }

    func testChangesAndLink() throws {
        XCTAssertEqual(TelegramChatChange.topic(nil).patch, ["topic_id": .null], "all topics is an explicit null")
        XCTAssertEqual(TelegramChatChange.topic(42).patch, ["topic_id": .number(42)])
        XCTAssertEqual(TelegramChatChange.contributors(.linkedOnly).patch, ["contributors": .string("linked_only")])
        XCTAssertEqual(TelegramChatChange.takeOver.patch, ["take_over": .bool(true)])
        let l = try ChannelFixtures.decode(TelegramLink.self, #"{"display_name":"Carla Peeters","username":"carla","can_message":false,"linked_at":"2026-11-19T10:00:00Z"}"#)
        XCTAssertEqual(TelegramText.link(l), "Carla Peeters (@carla)")
        XCTAssertFalse(l.canMessage)
        let noName = try ChannelFixtures.decode(TelegramLink.self, #"{"display_name":"Opa","username":null,"can_message":true,"linked_at":"2026-11-19T10:00:00Z"}"#)
        XCTAssertEqual(TelegramText.link(noName), "Opa")
        let p = try ChannelFixtures.decode(TelegramContributor.self, """
        {"id":"p1","display_name":"Ben","linked":true,"user_id":"u2","blocked":false,"sent_count":12,
         "first_seen_at":"2026-11-01T09:00:00Z","last_seen_at":"2026-11-19T10:32:00Z"}
        """)
        XCTAssertEqual(TelegramText.contributor(p, now: ChannelFixtures.now).count, 12)
        XCTAssertEqual(TelegramShare.inviteMessage(targetName: "Oma’s frame", url: "https://t.me/kinframe_bot?start=p1", template: "ios.tg.inviteText"),
                       "Send photos to Oma’s frame with Telegram: open this link and tap Start. https://t.me/kinframe_bot?start=p1")
        XCTAssertEqual(TelegramText.problem(APIError(status: 409, code: "telegram_already_linked")), L("ios.tg.err.alreadyLinked"))
    }
}

final class ChannelSharedTests: XCTestCase {
    private let via = "{name} via {service}"
    private let email = "{name} via email"
    private let telegram = "{name} via Telegram"

    func testCredits() throws {
        let mail = try ChannelFixtures.decode(ItemVia.self, #"{"service":"email","label":"Jan","address_id":"ad1"}"#)
        XCTAssertEqual(mail.knownService, .email)
        XCTAssertEqual(mail.addressId, "ad1")
        XCTAssertEqual(Credit.sender("Anna", via: mail, template: via, email: email, telegram: telegram), "Jan via email", "the label replaces the sender")
        XCTAssertEqual(Credit.sender("Anna", via: mail, feed: "Peeters kids", template: via, email: email, telegram: telegram), "Jan · Peeters kids via email")
        let tg = try ChannelFixtures.decode(ItemVia.self, #"{"service":"telegram","label":"Ben","telegram_chat_id":"c1"}"#)
        XCTAssertEqual(tg.telegramChatId, "c1")
        XCTAssertEqual(Credit.sender("Anna", via: tg, template: via, email: email, telegram: telegram), "Ben via Telegram")
        XCTAssertEqual(Credit.sender("Anna", via: tg, template: via, email: "ios.via.email", telegram: "ios.via.telegram"), "Ben via Telegram",
                       "a missing catalog falls back to English")
        let unlabeled = try ChannelFixtures.decode(ItemVia.self, #"{"service":"email","label":" "}"#)
        XCTAssertEqual(Credit.sender("Anna", via: unlabeled, template: via, email: email, telegram: telegram), "Anna via email")
        let immich = try ChannelFixtures.decode(ItemVia.self, #"{"service":"immich","label":"Immich"}"#)
        XCTAssertEqual(Credit.sender("Anna", via: immich, template: via, email: email, telegram: telegram), "Anna via Immich", "photo services unchanged")

        XCTAssertEqual(Credit.summary("4 photos", via: mail, emailFrom: "{summary} via email from {name}"), "4 photos via email from Jan")
        XCTAssertEqual(Credit.summary("4 photos", via: tg, telegramFrom: "ios.via.telegramFrom"), "4 photos via Telegram from Ben")
        XCTAssertEqual(Credit.summary("12 photos", via: immich, template: via), "12 photos via Immich")
        XCTAssertEqual(Credit.summary("12 photos", via: nil), "12 photos")

        let item = try ChannelFixtures.decode(Item.self, SchemaFixtures.item.replacingOccurrences(
            of: #""thumb_url""#, with: #""via":{"service":"email","label":"Jan","address_id":null},"thumb_url""#))
        XCTAssertEqual(Credit.item(item, template: via, email: email, telegram: telegram), "Jan via email")
    }

    func testJoinRequests() throws {
        func request(_ json: String) throws -> JoinRequest { try ChannelFixtures.decode(JoinRequest.self, json) }
        let mail = try request(ChannelFixtures.joinRequest(via: "email", viaName: "Jan · jan.peeters@telenet.be", matches: true))
        XCTAssertEqual(mail.channel, .email)
        XCTAssertTrue(mail.channel?.isChannel == true)
        XCTAssertEqual(JoinRequestOrigin.line(mail).key, "ios.join.viaEmail")
        XCTAssertEqual(JoinRequestOrigin.channelName(mail)?.name, "Jan · jan.peeters@telenet.be")
        XCTAssertEqual(JoinRequestOrigin.channelName(mail)?.match?.tone, .ok)
        let other = try request(ChannelFixtures.joinRequest(via: "email", viaName: "Jan · jan.peeters@telenet.be", matches: false))
        XCTAssertEqual(JoinRequestOrigin.channelName(other)?.match?.key, "ios.join.noMatchEmail")
        let tg = try request(ChannelFixtures.joinRequest(via: "telegram", viaName: "Ben · Telegram, Peeters family"))
        XCTAssertEqual(JoinRequestOrigin.line(tg).key, "ios.join.viaTelegram")
        XCTAssertNil(JoinRequestOrigin.channelName(tg)?.match, "Telegram can't tell")
        let invite = try request(ChannelFixtures.joinRequest(via: "invite", invitedBy: "Anna"))
        XCTAssertEqual(JoinRequestOrigin.line(invite), SourceLine("people.viaInvite", ["name": "Anna"]))
        XCTAssertNil(JoinRequestOrigin.channelName(invite))
        XCTAssertEqual(JoinRequestOrigin.line(try request(ChannelFixtures.joinRequest(via: "frame_code"))).key, "people.viaCode")
    }

    func testPushRoutes() {
        XCTAssertEqual(PushRoute.parse(["key": "email_pending_sender", "ids": ["address_id": "ad1", "sender_id": "ps1", "frame_id": "f1"]]),
                       .emailAddress(addressId: "ad1", frameId: "f1", feedId: nil))
        XCTAssertEqual(PushRoute.parse(["key": "email_pending_expiring", "address_id": "ad1", "feed_id": "feed1"]),
                       .emailAddress(addressId: "ad1", frameId: nil, feedId: "feed1"))
        XCTAssertNil(PushRoute.parse(["key": "email_pending_sender", "ids": ["sender_id": "ps1"]]))
        XCTAssertEqual(PushRoute.parse(["key": "telegram_attention", "ids": ["chat_id": "c1", "feed_id": "feed1"]]),
                       .telegramChat(chatId: "c1", frameId: nil, feedId: "feed1"))
        XCTAssertNil(PushRoute.parse(["key": "telegram_attention"]))
        XCTAssertEqual(PushRoute.parse(["key": "photo_source_attention", "ids": ["source_id": "src1", "feed_id": "feed1"]]),
                       .photoSource(sourceId: "src1", frameId: nil, albumId: nil, feedId: "feed1"), "photo sources still route first")
    }

    func testLiveEvents() throws {
        func parse(_ json: String) -> LiveEvent? { LiveEvent.parse(Data(json.utf8)) }
        guard case .emailAddressUpdated(let a)? = parse(#"{"type":"email_address.updated","address":\#(ChannelFixtures.address())}"#) else {
            return XCTFail("email_address.updated")
        }
        XCTAssertEqual(a.id, "ad1")
        XCTAssertEqual(parse(#"{"type":"email_address.removed","address_id":"ad1","frame_id":"f1"}"#),
                       .emailAddressRemoved(addressId: "ad1", frameId: "f1", feedId: nil))
        guard case .emailSenderPending("ad1", let p?)? = parse(#"{"type":"email_sender.pending","address_id":"ad1","pending":\#(ChannelFixtures.pending())}"#) else {
            return XCTFail("email_sender.pending")
        }
        XCTAssertEqual(p.id, "ps1")
        XCTAssertEqual(parse(#"{"type":"email_sender.decided","address_id":"ad1","sender_id":"ps1","decision":"approved"}"#),
                       .emailSenderDecided(addressId: "ad1", senderId: "ps1", decision: "approved"))
        guard case .telegramChatUpdated(let c)? = parse(#"{"type":"telegram_chat.updated","chat":\#(ChannelFixtures.chat())}"#) else {
            return XCTFail("telegram_chat.updated")
        }
        XCTAssertEqual(c.id, "c1")
        XCTAssertEqual(parse(#"{"type":"telegram_chat.removed","chat_id":"c1","feed_id":"feed1"}"#),
                       .telegramChatRemoved(chatId: "c1", frameId: nil, feedId: "feed1"))
        guard case .telegramCodeUpdated(let code)? = parse(#"{"type":"telegram_code.updated","code":\#(ChannelFixtures.code(status: "used", chatId: "c1"))}"#) else {
            return XCTFail("telegram_code.updated")
        }
        XCTAssertEqual(code.chatId, "c1")
        XCTAssertEqual(parse(#"{"type":"telegram_link.updated","link":null}"#), .telegramLinkUpdated(nil))
        guard case .telegramLinkUpdated(let l?)? = parse(#"{"type":"telegram_link.updated","link":{"display_name":"Carla","username":null,"can_message":true,"linked_at":"2026-11-19T10:00:00Z"}}"#) else {
            return XCTFail("telegram_link.updated")
        }
        XCTAssertEqual(l.displayName, "Carla")
    }

    func testDiscoveryAndLimits() throws {
        let off = ChannelFeatures(try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1"}"#.utf8)))
        XCTAssertFalse(off.email)
        XCTAssertFalse(off.telegram)
        XCTAssertFalse(ChannelFeatures(nil).email)
        let on = try JSONDecoder().decode(Discovery.self, from: Data("""
        {"name":"k","api":"https://k/v1","email_inbox":true,"email_inbox_domain":"in.kinframe.appmire.be","telegram":true,
         "telegram_bot_username":"kinframe_bot","telegram_privacy_mode":true,"telegram_max_file_bytes":20971520,
         "limits":{"max_email_addresses_per_target":3,"max_email_senders_per_address":50,"max_telegram_chats_per_target":2}}
        """.utf8))
        let f = ChannelFeatures(on)
        XCTAssertTrue(f.email)
        XCTAssertEqual(f.emailDomain, "in.kinframe.appmire.be")
        XCTAssertTrue(f.telegram)
        XCTAssertEqual(f.telegramBot, "kinframe_bot")
        XCTAssertTrue(f.telegramPrivacyMode)
        XCTAssertEqual(f.telegramMaxFileBytes, 20_971_520)
        XCTAssertEqual(on.limits.maxEmailAddressesPerTarget, 3)
        XCTAssertEqual(on.limits.maxEmailSendersPerAddress, 50)
        XCTAssertEqual(on.limits.maxTelegramChatsPerTarget, 2)
        let partly = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1","email_inbox":false,"email_inbox_domain":"x.be","telegram_privacy_mode":true}"#.utf8))
        XCTAssertNil(partly.emailInboxDomain, "no domain without the switch")
        XCTAssertFalse(partly.telegramPrivacyMode)
        XCTAssertEqual(Limits.defaults.maxEmailAddressesPerTarget, 5)
        XCTAssertEqual(Limits.defaults.maxTelegramChatsPerTarget, 5)
    }

    func testTargets() {
        XCTAssertEqual(ChannelTarget.from(kind: "frame", frameId: "f1", feedId: nil), .frame("f1"))
        XCTAssertEqual(ChannelTarget.from(kind: "feed", frameId: nil, feedId: "feed1"), .feed("feed1"))
        XCTAssertNil(ChannelTarget.from(kind: "feed", frameId: "f1", feedId: nil))
        XCTAssertNil(ChannelTarget.from(kind: "album", frameId: "f1", feedId: nil))
        XCTAssertTrue(ChannelTarget.feed("x").isFeed)
        XCTAssertEqual(ChannelTarget.frame("f1").id, "f1")
    }
}
