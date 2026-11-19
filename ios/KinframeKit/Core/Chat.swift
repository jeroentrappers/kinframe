import Foundation

// Chats (docs/api/README.md §5.19): conversations, messages, read receipts, typing, blocks and reports. Short names
// for the generated schemas and the pure rules the screens share. Unit-tested in ChatTests.

public typealias Conversation = Components.Schemas.Conversation
public typealias ConversationKind = Components.Schemas.ConversationKind
public typealias ConversationMember = Components.Schemas.ConversationMember
public typealias ConversationRole = Components.Schemas.ConversationRole
public typealias Message = Components.Schemas.Message
public typealias MessagePage = Components.Schemas.MessagePage
public typealias MessageQuote = Components.Schemas.MessageQuote
public typealias PostCard = Components.Schemas.PostCard
public typealias LinkPreview = Components.Schemas.LinkPreview
public typealias SystemMessage = Components.Schemas.SystemMessage
public typealias VoiceClip = Components.Schemas.VoiceClip
public typealias MuteFor = Components.Schemas.MuteFor
public typealias Person = Components.Schemas.Person
public typealias Badge = Components.Schemas.Badge
public typealias Block = Components.Schemas.Block
public typealias Report = Components.Schemas.Report
public typealias ReportReason = Components.Schemas.ReportReason
public typealias ReportTargetKind = Components.Schemas.ReportTargetKind

extension Components.Schemas.Conversation: Identifiable {}
extension Components.Schemas.Message: Identifiable {}
extension Components.Schemas.Report: Identifiable {}
extension Components.Schemas.Person: Identifiable {
    public var id: String { person.userId }
}
extension Components.Schemas.Block: Identifiable {
    public var id: String { person.userId }
}
extension Components.Schemas.ConversationMember: Identifiable {
    public var id: String { person.userId }
}

// MARK: The list

public enum ChatList {
    /// Most recent activity first (the server's order, kept when live events move a chat up).
    public static func sorted(_ list: [Conversation]) -> [Conversation] {
        list.sorted { a, b in a.lastActivityAt != b.lastActivityAt ? a.lastActivityAt > b.lastActivityAt : a.id > b.id }
    }

    public static func upsert(_ c: Conversation, into list: [Conversation]) -> [Conversation] {
        var out = list.filter { $0.id != c.id }
        if !c.archived { out.append(c) }
        return sorted(out)
    }

    /// A new message moves its chat to the top with the message as its preview, and counts as unread unless it is the
    /// caller's own or the chat is open.
    public static func applying(_ m: Message, to list: [Conversation], me: String?, open: String?) -> [Conversation] {
        guard let i = list.firstIndex(where: { $0.id == m.conversationId }) else { return list }
        var out = list
        var c = out[i]
        if c.lastMessage.map({ $0.id <= m.id }) ?? true {
            c.lastMessage = m
            c.lastActivityAt = max(c.lastActivityAt, m.createdAt)
        }
        if m.author?.userId != me, m.kind == .person, open != c.id, !m.deleted {
            c.unreadCount += 1
            if Mentions.mentions(m.mentions, user: me) { c.unreadMentions = (c.unreadMentions ?? 0) + 1 }
        }
        out[i] = c
        return sorted(out)
    }

    /// The badge the list itself shows: chats with unread messages that aren't muted.
    public static func unreadChats(_ list: [Conversation]) -> Int {
        list.filter { $0.unreadCount > 0 && !$0.muted }.count
    }

    /// The chat's name for the caller: the other person (or "Former member") for a direct chat.
    public static func title(_ c: Conversation) -> String {
        if c.kind == .direct, let other = c.other { return PersonName.display(other) }
        return c.name
    }

    /// A short subtitle for the kind: "Family chat", "Circle", "Group · 5 people".
    public static func kindLabel(_ c: Conversation) -> String {
        switch c.kind {
        case .family: L("chat.kind.family")
        case .circle: L("chat.kind.circle")
        case .group: Lp("chat.kind.group", count: c.memberCount)
        case .direct: L("chat.kind.direct")
        }
    }
}

// MARK: Messages

public enum MessageText {
    /// The preview in the chat list: "Anna: Look at this", "You: 📷 3 photos", "This message was deleted".
    public static func preview(_ m: Message, me: String?, kind: ConversationKind) -> String {
        if m.kind == .system, let s = m.system { return system(s, me: me) }
        let content = summary(m)
        guard m.kind == .person, !m.deleted else { return content }
        if m.author?.userId == me { return L("chat.preview.you", ["text": content]) }
        if kind == .direct { return content }
        return L("chat.preview.person", ["name": PersonName.first(m.author), "text": content])
    }

    /// What a message holds, in a few words.
    public static func summary(_ m: Message) -> String {
        if m.deleted { return L(m.removedByAdmin == true ? "chat.message.removed" : "chat.message.deleted") }
        if let body = m.body?.trimmingCharacters(in: .whitespacesAndNewlines), !body.isEmpty { return body }
        if m.voice != nil { return L("chat.preview.voice") }
        if !m.items.isEmpty {
            let videos = m.items.filter { $0.kind == .video }.count
            if videos == m.items.count { return Lp("chat.preview.videos", count: videos) }
            return Lp("chat.preview.photos", count: m.items.count)
        }
        if m.sharedPost != nil { return L("chat.preview.post") }
        return ""
    }

    /// A system message in the viewer's language.
    public static func system(_ s: SystemMessage, me: String?) -> String {
        let who = PersonName.display(s.person, me: me, you: true)
        switch s._type {
        case .putOnFrame:
            let places = (s.frameNames ?? []) + (s.feedNames ?? [])
            let list = ListText.join(places)
            return Lp("chat.system.putOnFrame", count: s.itemCount ?? 0, ["name": who, "places": list])
        case .streakMilestone:
            return Lp("chat.system.streak", count: s.weeks ?? 0)
        case .monthlyRecap:
            return L("chat.system.recap", ["month": MonthText.name(s.month)])
        case .prompt:
            return L("chat.system.prompt", ["title": s.promptTitle ?? ""])
        case .memberJoined:
            return L("chat.system.joined", ["name": who])
        case .memberLeft:
            return L("chat.system.left", ["name": who])
        }
    }

    /// The quoted message above a reply.
    public static func quote(_ q: MessageQuote) -> String {
        if q.deleted { return L("chat.quote.unavailable") }
        if let e = q.excerpt, !e.isEmpty { return e }
        if q.voice == true { return L("chat.preview.voice") }
        if let kind = q.mediaKind { return kind == .video ? L("chat.quote.video") : L("chat.quote.photo") }
        return ""
    }
}

public enum MessageLogic {
    /// Keep the page ordered by id (UUIDv7, server time) and replace a pending bubble by its `client_id`.
    public static func upsert(_ m: Message, into list: [Message]) -> [Message] {
        var out = list
        if let i = out.firstIndex(where: { $0.id == m.id }) {
            out[i] = m
        } else if let cid = m.clientId, let i = out.firstIndex(where: { $0.clientId == cid && $0.id.hasPrefix(pendingPrefix) }) {
            out[i] = m
        } else {
            out.append(m)
        }
        return sorted(out)
    }

    /// Pending bubbles sort after every real message.
    public static func sorted(_ list: [Message]) -> [Message] {
        list.sorted { a, b in
            let pa = a.id.hasPrefix(pendingPrefix), pb = b.id.hasPrefix(pendingPrefix)
            if pa != pb { return pb }
            return pa ? a.createdAt < b.createdAt : a.id < b.id
        }
    }

    /// Prepend an older page, skipping what's already there.
    public static func merging(_ page: [Message], into list: [Message]) -> [Message] {
        var out = list
        for m in page { out = upsert(m, into: out) }
        return out
    }

    /// The local id of a message that is still being sent.
    public static let pendingPrefix = "pending-"

    public static func isPending(_ m: Message) -> Bool { m.id.hasPrefix(pendingPrefix) }

    /// The author edits text within `hours` of sending; never system messages, tombstones or pending ones.
    public static func canEdit(_ m: Message, me: String?, hours: Int, now: Date = Date()) -> Bool {
        guard m.kind == .person, !m.deleted, !isPending(m), m.author?.userId == me, m.voice == nil || m.body != nil else { return false }
        return now.timeIntervalSince(m.createdAt) < Double(hours) * 3600
    }

    public static func canDelete(_ m: Message, me: String?, moderator: Bool) -> Bool {
        guard m.kind == .person, !m.deleted, !isPending(m) else { return false }
        return m.author?.userId == me || moderator
    }

    /// People react to person and system messages, not to tombstones or pending bubbles.
    public static func canReact(_ m: Message) -> Bool {
        !m.deleted && !isPending(m)
    }

    /// Photos and videos of the message that can be put on a frame (voice never).
    public static func frameable(_ m: Message) -> [Item] {
        guard !m.deleted else { return [] }
        return m.items.filter { $0.status == .ready }
    }

    /// The newest message that isn't pending, to mark the chat read up to it.
    public static func lastReadable(_ list: [Message]) -> String? {
        list.last { !isPending($0) }?.id
    }

    /// Messages that start a new day get a date line.
    public static func startsDay(_ list: [Message], at i: Int, calendar: Calendar = .current) -> Bool {
        guard i > 0 else { return true }
        return !calendar.isDate(list[i].createdAt, inSameDayAs: list[i - 1].createdAt)
    }

    /// Consecutive messages of one author within 5 minutes share the name line.
    public static func continues(_ list: [Message], at i: Int) -> Bool {
        guard i > 0 else { return false }
        let a = list[i - 1], b = list[i]
        guard a.kind == .person, b.kind == .person, a.author?.userId == b.author?.userId else { return false }
        return b.createdAt.timeIntervalSince(a.createdAt) < 300 && !startsDay(list, at: i)
    }
}

// MARK: Read receipts

public enum ReadReceipts {
    /// The other members who read up to (or past) `message` ("Seen" in direct chats, "Seen by Anna, Ben" in small
    /// groups). Only when the conversation shows receipts; the server leaves positions null otherwise.
    public static func seenBy(_ message: Message, in c: Conversation, me: String?) -> [PersonRef] {
        guard c.readReceipts == true, !MessageLogic.isPending(message) else { return [] }
        return (c.members ?? []).filter { m in
            m.person.userId != me && (m.lastReadMessageId.map { $0 >= message.id } ?? false)
        }.map(\.person)
    }

    public static func text(_ people: [PersonRef], kind: ConversationKind) -> String? {
        guard !people.isEmpty else { return nil }
        if kind == .direct { return L("chat.seen") }
        return L("chat.seenBy", ["names": ListText.join(people.map { PersonName.first($0) })])
    }

    /// Apply a `read.updated` event to the member list.
    public static func applying(userId: String, lastRead: String?, to c: Conversation) -> Conversation {
        var out = c
        if let i = out.members?.firstIndex(where: { $0.person.userId == userId }) {
            out.members?[i].lastReadMessageId = lastRead
        }
        return out
    }
}

// MARK: Typing

/// Who is typing or recording in one conversation, kept from `typing` events until they expire.
public struct TypingState: Equatable, Sendable {
    public struct Entry: Equatable, Sendable {
        public var name: String
        public var recording: Bool
        public var until: Date
    }

    public private(set) var entries: [String: Entry] = [:]

    public init() {}

    public mutating func note(userId: String, name: String, recording: Bool, until: Date) {
        entries[userId] = Entry(name: name, recording: recording, until: until)
    }

    /// A message from them ends their typing.
    public mutating func clear(userId: String) {
        entries[userId] = nil
    }

    public mutating func expire(now: Date = Date()) {
        entries = entries.filter { $0.value.until > now }
    }

    /// "Anna is typing…", "Anna and Ben are typing…", "Anna is recording…".
    public func text(now: Date = Date()) -> String? {
        let live = entries.values.filter { $0.until > now }.sorted { $0.name < $1.name }
        guard !live.isEmpty else { return nil }
        if live.count == 1 {
            let e = live[0]
            return L(e.recording ? "chat.typing.recordingOne" : "chat.typing.one", ["name": e.name])
        }
        if live.count == 2 { return L("chat.typing.two", ["a": live[0].name, "b": live[1].name]) }
        return L("chat.typing.many")
    }

    /// Sending `typing` at most every 3 s while typing (README §5.19).
    public static func shouldSend(last: Date?, now: Date = Date()) -> Bool {
        guard let last else { return true }
        return now.timeIntervalSince(last) >= 3
    }
}

// MARK: Mute

extension Components.Schemas.MuteFor {
    public var label: String {
        switch self {
        case .off: L("chat.mute.off")
        case ._1h: L("chat.mute.hour")
        case ._8h: L("chat.mute.eightHours")
        case ._1w: L("chat.mute.week")
        case .always: L("chat.mute.always")
        }
    }

    /// The choices of the mute menu.
    public static let choices: [MuteFor] = [._1h, ._8h, ._1w, .always]
}

public enum MuteText {
    /// "Muted until 18:30", "Muted".
    public static func state(_ c: Conversation, now: Date = Date()) -> String? {
        guard c.muted else { return nil }
        guard let until = c.mutedUntil, until > now else { return L("chat.muted") }
        return L("chat.mutedUntil", ["when": Format.relative(until, locale: L10n.locale)])
    }
}

// MARK: Who may do what

public enum ChatAbilities {
    /// Group admins rename, change the photo, remove people and make admins.
    public static func canManageGroup(_ c: Conversation) -> Bool {
        c.kind == .group && c.myRole == .admin
    }

    /// Moderators remove anyone's message: group admins; in family and circle chats the family's owner and admins
    /// (circle admins too, but the conversation doesn't say who they are: the circle does).
    public static func isModerator(_ c: Conversation, feedRole: FeedRole?, circleRole: CircleRole? = nil) -> Bool {
        switch c.kind {
        case .group: c.myRole == .admin
        case .family: feedRole == .owner || feedRole == .admin
        case .circle: feedRole == .owner || feedRole == .admin || circleRole == .admin
        case .direct: false
        }
    }

    /// Anyone leaves a group; direct, family and circle chats can't be left (archive or mute them).
    public static func canLeave(_ c: Conversation) -> Bool { c.kind == .group }

    public static func canAddPeople(_ c: Conversation, limit: Int) -> Bool {
        c.kind == .group && c.memberCount < limit && c.readOnly != true
    }

    public static func canSend(_ c: Conversation) -> Bool {
        c.readOnly != true
    }
}

/// A new group: at least two other people and a name.
public enum GroupRules {
    public static let maxName = 60

    public static func problem(memberCount others: Int, name: String, limit: Int) -> String? {
        if others < 2 { return L("chat.new.groupNeedsTwo") }
        if others + 1 > limit { return L("chat.new.groupTooBig", ["max": limit]) }
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return L("chat.new.groupNeedsName") }
        return nil
    }
}

// MARK: Composer

public enum ChatComposer {
    /// What may be sent: text of 1–max characters (code points), or photos, or a shared post.
    public static func canSend(body: String, itemCount: Int, sharedPost: Bool, limits: Limits) -> Bool {
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.unicodeScalars.count > limits.maxMessageChars { return false }
        if itemCount > limits.maxMessageItems { return false }
        return !t.isEmpty || itemCount > 0 || sharedPost
    }

    /// How many characters are left, shown near the limit.
    public static func remaining(_ body: String, max: Int) -> Int? {
        let n = body.unicodeScalars.count
        return n > max - 200 ? max - n : nil
    }
}

// MARK: Voice

public enum VoiceLogic {
    /// "0:42", "4:05".
    public static func duration(_ ms: Int?) -> String {
        let s = max(0, (ms ?? 0) / 1000)
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// The waveform scaled to `bars` values 0…1 (the server sends 64 values 0–100; recordings use meter levels).
    public static func bars(_ waveform: [Int]?, count bars: Int) -> [Double] {
        let w = waveform ?? []
        guard !w.isEmpty, bars > 0 else { return Array(repeating: 0.15, count: max(0, bars)) }
        return (0..<bars).map { i in
            let lo = i * w.count / bars, hi = max(lo + 1, (i + 1) * w.count / bars)
            let slice = w[lo..<min(hi, w.count)]
            let v = Double(slice.max() ?? 0) / 100
            return max(0.08, min(1, v))
        }
    }

    /// A meter reading (dBFS, −160…0) as 0–100 for the live waveform.
    public static func level(decibels: Float) -> Int {
        let clamped = max(-50, min(0, decibels))
        return Int(((clamped + 50) / 50 * 100).rounded())
    }

    /// Recordings shorter than a second aren't sent (the server refuses them).
    public static func isLongEnough(seconds: Double) -> Bool { seconds >= 1 }
}

// MARK: Reports

extension Components.Schemas.ReportReason {
    public var label: String { L("chat.report.reason.\(rawValue)") }
}

public enum ReportText {
    public static func status(_ r: Report) -> String {
        switch r.status {
        case .open: L("chat.report.status.open")
        case .removed: L("chat.report.status.removed")
        case .dismissed: L("chat.report.status.dismissed")
        }
    }

    public static func kind(_ r: Report) -> String {
        L("chat.report.kind.\(r.targetKind.rawValue)")
    }
}

// MARK: Small text helpers

public enum ListText {
    /// "Oma's frame", "Oma's frame and Peeters", "A, B and C".
    public static func join(_ parts: [String]) -> String {
        let p = parts.filter { !$0.isEmpty }
        switch p.count {
        case 0: return ""
        case 1: return p[0]
        default: return L("social.list.and", ["list": p.dropLast().joined(separator: ", "), "last": p.last!])
        }
    }
}

public enum MonthText {
    /// "2026-10" → "October 2026" in the app's language.
    public static func name(_ month: String?) -> String {
        guard let month, month.count == 7, let y = Int(month.prefix(4)), let m = Int(month.suffix(2)) else { return month ?? "" }
        var c = DateComponents()
        c.year = y
        c.month = m
        c.day = 1
        guard let date = Calendar(identifier: .gregorian).date(from: c) else { return month }
        let f = DateFormatter()
        f.locale = L10n.locale
        f.setLocalizedDateFormatFromTemplate("MMMMyyyy")
        return f.string(from: date)
    }
}
