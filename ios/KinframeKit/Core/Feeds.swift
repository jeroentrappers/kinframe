import Foundation

// Feeds and subscriptions (docs/spec/feeds.md, docs/api/README.md §5.12): short names for the generated schemas and
// the pure rules the screens share. Unit-tested in FeedTests.

public typealias FeedSummary = Components.Schemas.FeedSummary
public typealias Feed = Components.Schemas.Feed
public typealias FeedRole = Components.Schemas.FeedRole
public typealias FeedMember = Components.Schemas.FeedMember
public typealias FeedInvite = Components.Schemas.FeedInvite
public typealias FeedInvitePurpose = Components.Schemas.FeedInvitePurpose
public typealias FeedInvitePreview = Components.Schemas.FeedInvitePreview
public typealias FeedInviteResult = Components.Schemas.FeedInviteResult
public typealias FeedPost = Components.Schemas.FeedPost
public typealias FeedPostReceipts = Components.Schemas.FeedPostReceipts
public typealias FeedFrameReceipt = Components.Schemas.FeedPostReceipts.FramesPayloadPayload
public typealias FeedItemReceipt = Components.Schemas.FeedPostReceipts.FramesPayloadPayload.ItemsPayloadPayload
public typealias FeedReceiptSummary = Components.Schemas.FeedReceiptSummary
public typealias FeedReaction = Components.Schemas.FeedReaction
public typealias FeedDeliveryStatus = Components.Schemas.FeedDeliveryStatus
/// Named apart from Combine's `Subscription`, which SwiftUI files also see.
public typealias FeedSubscription = Components.Schemas.Subscription
public typealias SubscriptionStatus = Components.Schemas.SubscriptionStatus
public typealias SubscriptionHistory = Components.Schemas.SubscriptionHistory
public typealias SendFeed = Components.Schemas.SendFeed
public typealias PlacementOrigin = Components.Schemas.PlacementOrigin

extension Components.Schemas.FeedSummary: Identifiable {}
extension Components.Schemas.FeedInvite: Identifiable {}
extension Components.Schemas.FeedPost: Identifiable {}
extension Components.Schemas.Subscription: Identifiable {}

extension Components.Schemas.Feed: Identifiable {
    public var id: String { value1.id }
    public var summary: FeedSummary { value1 }
    public var name: String { value1.name }
    public var role: FeedRole { value1.role }
}

extension Components.Schemas.FeedMember: Identifiable {
    public var id: String { userId }
}

extension Components.Schemas.SendFeed: Identifiable {
    public var id: String { postId }
}

// MARK: Codes

/// What the shared "Enter a code" field holds: an 8-digit frame code or an 8-character feed invite code
/// (Crockford base32, never all digits, shown as `KF7Q-M2XA`).
public enum EntryCode: Equatable, Sendable {
    case frame(String)
    case feed(String)
}

public enum CodeEntry {
    public static let length = 8
    /// Crockford base32: digits and letters without I, L, O and U.
    public static let alphabet = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// Normalize what was typed or pasted into the code field: up to 8 code characters, uppercase. Without
    /// `allowLetters` this is the frame code field (digits only). Long pasted text ("Code: 4821-3907",
    /// "Join with KF7Q-M2XA") gives the code inside it; Crockford's look-alikes (O, I, L) are read as 0 and 1.
    public static func normalize(_ input: String, allowLetters: Bool) -> String {
        guard allowLetters else { return Format.normalizeCode(input) }
        let upper = input.uppercased()
        let compact = upper.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        if compact.count <= length {
            return String(compact.compactMap(crockford).prefix(length))
        }
        // Longer text: a code-shaped word with letters, else a whole frame code's digits, else (one character too
        // many typed) the first eight.
        if let feed = findFeedCode(in: upper) { return feed }
        let digits = Format.normalizeCode(input)
        if digits.count == length { return digits }
        return String(compact.compactMap(crockford).prefix(length))
    }

    /// The complete code, or nil while it's still being typed.
    public static func classify(_ normalized: String) -> EntryCode? {
        guard normalized.count == length, normalized.allSatisfy({ alphabet.contains($0) }) else { return nil }
        return normalized.allSatisfy(\.isNumber) ? .frame(normalized) : .feed(normalized)
    }

    /// Whether this (possibly partial) code is a feed code: it has a letter.
    public static func isFeed(_ normalized: String) -> Bool {
        normalized.contains { $0.isLetter }
    }

    /// Display form: "4821 3907" for frame codes, "KF7Q-M2XA" for feed codes; partial input stays readable.
    public static func format(_ normalized: String) -> String {
        guard isFeed(normalized) else { return Format.formatCode(normalized) }
        guard normalized.count > 4 else { return normalized }
        return "\(normalized.prefix(4))-\(normalized.dropFirst(4))"
    }

    /// A feed invite code as the server sends it ("KF7QM2XA"), shown as "KF7Q-M2XA".
    public static func displayFeedCode(_ code: String) -> String {
        let clean = code.uppercased().filter { $0 != "-" && $0 != " " }
        guard clean.count == length else { return code.uppercased() }
        return "\(clean.prefix(4))-\(clean.dropFirst(4))"
    }

    static func crockford(_ c: Character) -> Character? {
        switch c {
        case "O": return "0"
        case "I", "L": return "1"
        default: return alphabet.contains(c) ? c : nil
        }
    }

    /// The first `XXXX-XXXX` / `XXXXXXXX` word that has a letter and only Crockford characters.
    static func findFeedCode(in upper: String) -> String? {
        let chars = Array(upper)
        func isWordChar(_ c: Character) -> Bool { c.isASCII && (c.isLetter || c.isNumber) }
        var i = 0
        while i < chars.count {
            guard isWordChar(chars[i]), i == 0 || !isWordChar(chars[i - 1]) else { i += 1; continue }
            var j = i
            var word: [Character] = []
            var hyphens = 0
            while j < chars.count, isWordChar(chars[j]) || (chars[j] == "-" && hyphens == 0 && word.count == 4) {
                if chars[j] == "-" { hyphens += 1 } else { word.append(chars[j]) }
                j += 1
            }
            if word.count == length {
                let mapped = word.compactMap(crockford)
                if mapped.count == length, mapped.contains(where: \.isLetter) { return String(mapped) }
            }
            i = max(j, i + 1)
        }
        return nil
    }
}

// MARK: Roles and visibility

/// What the signed-in member may do in a feed (docs/spec/feeds.md §4.1).
public struct FeedAbilities: Equatable, Sendable {
    public let role: FeedRole
    public let membersSeeReactions: Bool
    public let postersCanInvite: Bool

    public init(role: FeedRole, membersSeeReactions: Bool = false, postersCanInvite: Bool = false) {
        self.role = role
        self.membersSeeReactions = membersSeeReactions
        self.postersCanInvite = postersCanInvite
    }

    public init(_ feed: Feed) {
        self.init(role: feed.role, membersSeeReactions: feed.value2.membersSeeReactions ?? false,
                  postersCanInvite: feed.value2.postersCanInvite ?? false)
    }

    public var isAdmin: Bool { role == .owner || role == .admin }
    /// Rename, cover, the feed's settings, join requests and pending subscriptions.
    public var canManage: Bool { isAdmin }
    public var isOwner: Bool { role == .owner }
    /// Join invites: admins, or every member when the feed lets posters invite.
    public var canCreateJoinInvites: Bool { isAdmin || postersCanInvite }
    /// Subscribe invites (pre-approved) are for admins only.
    public var canCreateSubscribeInvites: Bool { isAdmin }
    public var canSeeInvites: Bool { canCreateJoinInvites }

    /// Receipts and reactions of a post (the server leaves them null otherwise).
    public func canSeeReceipts(posterId: String, me: String?) -> Bool {
        isAdmin || membersSeeReactions || posterId == me
    }

    /// Unsend (own) or remove (admins) a post or one of its items.
    public func canRemove(posterId: String, me: String?) -> Bool {
        isAdmin || posterId == me
    }

    /// Change a member's role or remove them. Owners manage everyone but themselves; admins manage posters.
    public func canEdit(_ member: FeedRole, isMe: Bool) -> Bool {
        guard !isMe, member != .owner else { return false }
        switch role {
        case .owner: return true
        case .admin: return member == .poster
        case .poster: return false
        }
    }
}

// MARK: Recipients

public enum Recipients {
    /// `SendCreate.feed_ids` takes at most 20 feeds.
    public static let maxFeeds = 20

    /// Frames and feeds together must not be empty; feeds are capped.
    public static func problem(frameCount: Int, feedCount: Int) -> ComposeRules.Blocker? {
        if frameCount + feedCount == 0 { return .noFrames }
        if feedCount > maxFeeds { return .tooManyFeeds(maxFeeds) }
        return nil
    }

    /// Only the ids still offered (a frame you lost `send` on, a feed you left, are dropped), in a stable order.
    public static func valid(_ chosen: Set<String>, among available: [String]) -> [String] {
        available.filter { chosen.contains($0) }
    }
}

// MARK: Receipts

public enum FeedReceipts {
    /// Frames that have every item (seen counts as delivered).
    public static func arrived(_ s: FeedReceiptSummary) -> Int { max(s.delivered, s.seen) }

    /// The headline stage of a post across its frames.
    public static func stage(_ s: FeedReceiptSummary) -> Stage {
        guard s.frames > 0 else { return .processing }
        if s.seen >= s.frames { return .seen }
        if arrived(s) >= s.frames { return .delivered }
        return .onItsWay
    }

    /// 0…1: delivered frames count two thirds, seen ones fully.
    public static func progress(_ s: FeedReceiptSummary) -> Double {
        guard s.frames > 0 else { return 0 }
        let seen = min(s.seen, s.frames)
        let arrived = min(arrived(s), s.frames)
        return (Double(arrived - seen) * 2 / 3 + Double(seen)) / Double(s.frames)
    }

    /// "Seen on 4 of 5 frames" and friends (keys in ios-strings-feeds.json).
    public static func text(_ s: FeedReceiptSummary) -> String {
        if s.frames == 0 {
            return (s.paused ?? 0) > 0 ? Lp("ios.feeds.receipts.allPaused", count: s.paused ?? 0) : L("ios.feeds.receipts.noFrames")
        }
        var parts: [String]
        if s.seen > 0 {
            parts = [Lp("ios.feeds.receipts.seen", count: s.frames, ["n": s.seen])]
        } else if arrived(s) > 0 {
            parts = [Lp("ios.feeds.receipts.delivered", count: s.frames, ["n": arrived(s)])]
        } else {
            parts = [Lp("ios.feeds.receipts.onItsWay", count: s.frames)]
        }
        if let n = s.awaitingApproval, n > 0 { parts.append(Lp("ios.feeds.receipts.awaiting", count: n)) }
        if let n = s.paused, n > 0 { parts.append(Lp("ios.feeds.receipts.paused", count: n)) }
        if let n = s.removed, n > 0 { parts.append(Lp("ios.feeds.receipts.removed", count: n)) }
        return parts.joined(separator: " · ")
    }

    /// One frame's state for a post: the least advanced item (failures and moderation first).
    public static func frameStatus(_ items: [FeedItemReceipt]) -> FeedDeliveryStatus {
        let order: [FeedDeliveryStatus] = [.failed, .awaitingApproval, .suspended, .pending, .ready, .delivered, .seen, .removed]
        let live = items.map(\.status).filter { $0 != .removed }
        guard !live.isEmpty else { return .removed }
        return order.first { live.contains($0) } ?? .pending
    }

    /// Reactions by frame, newest first ("❤️ Oma's frame").
    public static func reactionLines(_ reactions: [FeedReaction]) -> [(frame: String, reaction: Reaction?, replies: [ReplyPreset])] {
        var order: [String] = []
        var byFrame: [String: (frame: String, reaction: Reaction?, replies: [ReplyPreset])] = [:]
        for r in reactions {
            if byFrame[r.frameId] == nil {
                order.append(r.frameId)
                byFrame[r.frameId] = (r.frameName, r.reaction, r.replies ?? [])
            } else {
                if byFrame[r.frameId]?.reaction == nil { byFrame[r.frameId]?.reaction = r.reaction }
                byFrame[r.frameId]?.replies.append(contentsOf: r.replies ?? [])
            }
        }
        return order.compactMap { byFrame[$0] }
    }
}

// MARK: Subscriptions

/// The history option of a subscription (`history`, `history_count`).
public enum HistoryChoice: Equatable, Hashable, Sendable {
    case none
    case lastN(Int)
    case all

    public static let countRange = 1...1000
    public static let defaultCount = 50
    public static let presets = [10, 50, 100, 500]

    public init(history: SubscriptionHistory, count: Int?) {
        switch history {
        case .none: self = .none
        case .lastN: self = .lastN(Self.clamp(count ?? Self.defaultCount))
        case .all: self = .all
        }
    }

    public init(_ s: FeedSubscription) {
        self.init(history: s.history, count: s.historyCount)
    }

    public var history: SubscriptionHistory {
        switch self {
        case .none: .none
        case .lastN: .lastN
        case .all: .all
        }
    }

    /// `history_count`, only with `last_n`.
    public var count: Int? {
        if case .lastN(let n) = self { return Self.clamp(n) }
        return nil
    }

    public static func clamp(_ n: Int) -> Int { min(max(n, countRange.lowerBound), countRange.upperBound) }

    public var label: String {
        switch self {
        case .none: L("ios.feeds.history.none")
        case .lastN(let n): Lp("ios.feeds.history.lastN", count: n)
        case .all: L("ios.feeds.history.all")
        }
    }
}

/// What the caller can do with a subscription right now (docs/spec/feeds.md §4.2, §5.1).
public enum SubscriptionAction: String, CaseIterable, Sendable {
    case approve, reject, withdraw, pause, resume, changeHistory, unsubscribe, remove
}

public enum SubscriptionLogic {
    /// `can_manage`: the frame side (pause, resume, history, unsubscribe). `can_decide`: the feed side.
    public static func actions(_ s: FeedSubscription) -> [SubscriptionAction] {
        actions(status: s.status, canManage: s.canManage ?? false, canDecide: s.canDecide ?? false)
    }

    public static func actions(status: SubscriptionStatus, canManage: Bool, canDecide: Bool) -> [SubscriptionAction] {
        var out: [SubscriptionAction] = []
        switch status {
        case .pending:
            if canDecide { out += [.approve, .reject] }
            if canManage { out += [.changeHistory, .withdraw] }
        case .active:
            if canManage { out += [.pause, .changeHistory, .unsubscribe] } else if canDecide { out.append(.remove) }
        case .paused:
            if canManage { out += [.resume, .changeHistory, .unsubscribe] } else if canDecide { out.append(.remove) }
        case .rejected, .removed:
            break
        }
        return out
    }

    /// Live subscriptions block a second request for the same frame and feed.
    public static func isLive(_ status: SubscriptionStatus) -> Bool {
        status == .pending || status == .active || status == .paused
    }

    /// Sort order for lists: waiting first, then active, paused, then ended ones.
    public static func rank(_ status: SubscriptionStatus) -> Int {
        switch status {
        case .pending: 0
        case .active: 1
        case .paused: 2
        case .rejected: 3
        case .removed: 4
        }
    }

    public static func sorted(_ list: [FeedSubscription]) -> [FeedSubscription] {
        list.sorted { a, b in
            rank(a.status) != rank(b.status) ? rank(a.status) < rank(b.status) : a.createdAt > b.createdAt
        }
    }

    /// Frames this person manages (owner, admin, or `change_settings`): the ones they may subscribe.
    public static func manageable(_ frames: [FrameSummary]) -> [FrameSummary] {
        frames.filter { $0.can(\.changeSettings) }
    }

    /// Backfill progress (0…1) while history is being placed, nil when idle.
    public static func backfillProgress(_ s: FeedSubscription) -> Double? {
        guard let b = s.backfill, let state = b.state, state != .idle else { return nil }
        guard let total = b.total, total > 0 else { return 0 }
        return min(1, Double(b.placed ?? 0) / Double(total))
    }
}

// MARK: Push notifications

/// Where a tapped notification leads (docs/api/README.md §6.3: `{type, key, title, body, ids}`).
public enum PushRoute: Equatable, Sendable {
    case feed(feedId: String, section: FeedSectionHint)
    case feedPost(feedId: String, postId: String)
    case frameFeeds(frameId: String)
    case framePeople(frameId: String)
    case send(sendId: String)
    /// The Feeds tab (a morning notice that spans several feeds).
    case feeds
    /// A photo source needs its owner (`photo_source_attention`): its album or feed, or Settings → Photo sources.
    case photoSource(sourceId: String, frameId: String?, albumId: String?, feedId: String?)
    /// A Google Photos import finished (`google_import_done`).
    case googleImport(importId: String)
    /// Someone new is waiting at an inbox address (`email_pending_sender`): the address's pending list, in its frame's
    /// or feed's settings.
    case emailAddress(addressId: String, frameId: String?, feedId: String?)
    /// A Telegram chat needs its owner (`telegram_attention`): the chat, in its frame's or feed's settings.
    case telegramChat(chatId: String, frameId: String?, feedId: String?)

    public enum FeedSectionHint: String, Sendable {
        case posts, members, frames, birthdays, settings
    }

    /// Reads `key` (or `type`) and the ids, either in an `ids` dictionary or at the top level.
    public static func parse(_ payload: [AnyHashable: Any]) -> PushRoute? {
        let ids = (payload["ids"] as? [String: Any]) ?? [:]
        func id(_ name: String) -> String? {
            (ids[name] as? String) ?? (payload[name] as? String)
        }
        let key = (payload["key"] as? String) ?? (payload["type"] as? String) ?? ""
        switch key {
        case "subscription_request":
            return id("feed_id").map { .feed(feedId: $0, section: .frames) }
        case "subscription_decided", "subscription_removed":
            if let frame = id("frame_id") { return .frameFeeds(frameId: frame) }
            return id("feed_id").map { .feed(feedId: $0, section: .frames) }
        case "feed_join_request":
            return id("feed_id").map { .feed(feedId: $0, section: .members) }
        case "feed_membership":
            return id("feed_id").map { .feed(feedId: $0, section: .posts) }
        case "reaction", "reply":
            if let feed = id("feed_id"), let post = id("post_id") { return .feedPost(feedId: feed, postId: post) }
            return id("send_id").map { .send(sendId: $0) }
        case "delivered":
            return id("send_id").map { .send(sendId: $0) }
        case "join_request":
            return id("frame_id").map { .framePeople(frameId: $0) }
        case "birthday_suggestion", "birthday_decided", "birthday_greeting", "birthday_approved", "birthday_rejected":
            return id("feed_id").map { .feed(feedId: $0, section: .birthdays) }
        case "birthday_today":
            return id("feed_id").map { .feed(feedId: $0, section: .birthdays) } ?? .feeds
        default:
            return PhotoSourcePush.route(key: key, id: id) ?? ChannelPush.route(key: key, id: id)
        }
    }
}
