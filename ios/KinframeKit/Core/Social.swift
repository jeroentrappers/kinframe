import Foundation

// Family social (docs/spec/family-social.md, docs/api/README.md §5.18): the home timeline, threads with replies and
// mentions, people's reactions and circles. Short names for the generated schemas and the pure rules the screens
// share. Unit-tested in SocialTests.

public typealias TimelinePost = Components.Schemas.TimelinePost
public typealias TimelinePage = Components.Schemas.TimelinePage
public typealias ThreadSummary = Components.Schemas.ThreadSummary
public typealias ThreadState = Components.Schemas.ThreadState
public typealias Comment = Components.Schemas.Comment
public typealias CommentPage = Components.Schemas.CommentPage
public typealias PersonRef = Components.Schemas.PersonRef
public typealias PersonReaction = Components.Schemas.PersonReaction
public typealias ReactionSummary = Components.Schemas.ReactionSummary
public typealias ReactionList = Components.Schemas.ReactionList
public typealias ReactionTargetKind = Components.Schemas.ReactionTargetKind
public typealias FrameReactionRef = Components.Schemas.FrameReactionRef
public typealias Mention = Components.Schemas.Mention
public typealias PostSeen = Components.Schemas.PostSeen
public typealias FamilyCircle = Components.Schemas.Circle
public typealias CircleMember = Components.Schemas.CircleMember
public typealias CircleRole = Components.Schemas.CircleRole
public typealias FeedMute = Components.Schemas.FeedMute

extension Components.Schemas.TimelinePost: Identifiable {}
extension Components.Schemas.Comment: Identifiable {}
extension Components.Schemas.Circle: Identifiable {}
extension Components.Schemas.CircleMember: Identifiable {
    public var id: String { userId }
}

// MARK: Features

/// Which family-social areas the server has (discovery). Each area is hidden while its flag is off.
public struct SocialFeatures: Equatable, Sendable {
    public var social: Bool
    public var chats: Bool
    public var voiceMessages: Bool
    public var linkPreviews: Bool
    public var engagement: Bool
    public var emailDigest: Bool

    public init(_ d: Discovery?) {
        social = d?.social == true && d?.feeds == true
        chats = d?.chats == true
        voiceMessages = chats && d?.voiceMessages == true
        linkPreviews = chats && d?.linkPreviews == true
        engagement = d?.engagement == true
        emailDigest = engagement && d?.emailDigest == true
    }

    public init(social: Bool = false, chats: Bool = false, voiceMessages: Bool = false, linkPreviews: Bool = false,
                engagement: Bool = false, emailDigest: Bool = false) {
        self.social = social
        self.chats = chats
        self.voiceMessages = voiceMessages
        self.linkPreviews = linkPreviews
        self.engagement = engagement
        self.emailDigest = emailDigest
    }

    /// Anything of family social at all: the Home tab exists with the timeline (social) or the strip (engagement).
    public var home: Bool { social || engagement }
}

// MARK: People

public enum PersonName {
    /// The name next to a comment, message or reaction: "Former member" for deleted accounts, "You" for the caller
    /// when `you` is set.
    public static func display(_ p: PersonRef?, me: String? = nil, you: Bool = false) -> String {
        guard let p else { return L("social.formerMember") }
        if p.formerMember == true || p.displayName.isEmpty { return L("social.formerMember") }
        if you, let me, p.userId == me { return L("social.you") }
        return p.displayName
    }

    /// First name only ("Anna" of "Anna Peeters"), for compact lines.
    public static func first(_ p: PersonRef?) -> String {
        let full = display(p)
        return full.split(separator: " ").first.map(String.init) ?? full
    }
}

// MARK: Reactions

extension Components.Schemas.PersonReaction {
    public var emoji: String {
        switch self {
        case .love: "❤️"
        case .laugh: "😂"
        case .wow: "😮"
        case .sad: "😢"
        case .thumbsUp: "👍"
        case .pray: "🙏"
        }
    }

    /// The picker's order: the frame's four first, then the two people have.
    public static let ordered: [PersonReaction] = [.love, .laugh, .wow, .sad, .thumbsUp, .pray]

    /// A frame's reaction as the same name (counts merge).
    public init(_ frame: Reaction) {
        switch frame {
        case .love: self = .love
        case .laugh: self = .laugh
        case .wow: self = .wow
        case .sad: self = .sad
        }
    }

    public var label: String { L("social.reaction.\(rawValue)") }
}

public enum ReactionRow {
    /// The emojis to show in front of the row, most frequent first (at most three).
    public static func emojis(_ s: ReactionSummary) -> [String] {
        s.counts.filter { $0.count > 0 }.prefix(3).map(\.emoji.emoji)
    }

    /// "Oma's frame, Anna and 3 others" (frames first, they are the members everyone knows), "You and Anna",
    /// "Anna". Empty when nobody reacted.
    public static func names(_ s: ReactionSummary, me: String?) -> String {
        guard s.total > 0 else { return "" }
        var names: [String] = []
        if s.mine != nil { names.append(L("social.you")) }
        for f in s.frames where names.count < 2 { names.append(f.frameName) }
        for p in s.people where names.count < 2 && p.person.userId != me { names.append(PersonName.display(p.person)) }
        // Frames and the caller may also be counted in `people`: the rest are "others".
        let shown = names.count
        let others = max(0, s.total - shown)
        if names.isEmpty { return Lp("social.reactions.count", count: s.total) }
        if others == 0 {
            return names.count == 1 ? names[0] : L("social.reactions.two", ["a": names[0], "b": names[1]])
        }
        return Lp("social.reactions.others", count: others, ["names": names.joined(separator: ", ")])
    }

    /// The summary after the caller changed their own reaction (optimistic; the server's answer replaces it).
    public static func applying(_ s: ReactionSummary, mine new: PersonReaction?) -> ReactionSummary {
        var out = s
        var counts = Dictionary(uniqueKeysWithValues: s.counts.map { ($0.emoji, $0.count) })
        if let old = s.mine {
            counts[old, default: 0] -= 1
            out.total -= 1
        }
        if let new {
            counts[new, default: 0] += 1
            out.total += 1
        }
        out.mine = new
        out.total = max(0, out.total)
        let order = s.counts.map(\.emoji)
        out.counts = counts.filter { $0.value > 0 }
            .sorted { a, b in a.value != b.value ? a.value > b.value : (order.firstIndex(of: a.key) ?? 99) < (order.firstIndex(of: b.key) ?? 99) }
            .map { .init(emoji: $0.key, count: $0.value) }
        return out
    }

    public static let empty = ReactionSummary(total: 0, counts: [], people: [], frames: [])
}

// MARK: Threads

/// One top-level comment (or frame entry) with its replies, as the thread shows it.
public struct ThreadNode: Identifiable, Equatable, Sendable {
    public var comment: Comment
    public var replies: [Comment]
    public var id: String { comment.id }
}

public enum ThreadLogic {
    /// Nest the flat list (oldest first; replies after their parent): replies under their top comment, in order. A
    /// reply whose parent isn't in the list (still on an earlier page, or removed) is shown at the top level.
    public static func nest(_ flat: [Comment]) -> [ThreadNode] {
        var nodes: [ThreadNode] = []
        var index: [String: Int] = [:]
        for c in sorted(flat) {
            if let parent = c.parentId, let i = index[parent] {
                nodes[i].replies.append(c)
            } else {
                index[c.id] = nodes.count
                nodes.append(ThreadNode(comment: c, replies: []))
            }
        }
        return nodes
    }

    /// Oldest first by id (UUIDv7 sorts by time), then by creation time.
    public static func sorted(_ list: [Comment]) -> [Comment] {
        list.sorted { a, b in a.createdAt != b.createdAt ? a.createdAt < b.createdAt : a.id < b.id }
    }

    /// Add or replace one comment (live events, own posts).
    public static func upsert(_ c: Comment, into list: [Comment]) -> [Comment] {
        var out = list
        if let i = out.firstIndex(where: { $0.id == c.id }) { out[i] = c } else { out.append(c) }
        return sorted(out)
    }

    /// Remove a comment and, when it was a top comment, its replies (a moderator's removal takes them too).
    public static func remove(_ id: String, from list: [Comment]) -> [Comment] {
        list.filter { $0.id != id && $0.parentId != id }
    }

    /// Replying to `target`: the top comment it hangs under and, for a reply to a reply, the person quoted.
    public static func replyTarget(for target: Comment) -> (parentId: String, quotedUserId: String?) {
        if let parent = target.parentId {
            return (parent, target.author?.userId)
        }
        return (target.id, nil)
    }

    public enum Display: Equatable, Sendable {
        /// An ordinary comment or reply.
        case person
        /// Deleted by its author but kept for its replies: "Comment deleted".
        case tombstone
        /// Removed by a moderator; only its author still sees it: "Removed by a family admin".
        case removedByAdmin
        /// "Oma's frame loved this", "Oma's frame: Call me!".
        case frame
    }

    public static func display(_ c: Comment) -> Display {
        if c.kind == .frame { return .frame }
        if c.removedByAdmin == true { return .removedByAdmin }
        if c.deleted == true { return .tombstone }
        return .person
    }

    /// The line a frame entry shows.
    public static func frameEntryText(_ c: Comment) -> String {
        guard let f = c.frame else { return "" }
        if let reply = f.reply {
            return L("social.frameEntry.reply", ["frame": f.frameName, "reply": L("reply.\(reply.rawValue)")])
        }
        if let r = f.reaction {
            return L("social.frameEntry.\(r.rawValue)", ["frame": f.frameName])
        }
        return f.frameName
    }

    /// The newest person comment, to mark the thread read up to it.
    public static func lastReadable(_ list: [Comment]) -> String? {
        sorted(list).last { $0.kind == .person }?.id
    }

    /// "View all 6 comments" (only when there are more than the two shown).
    public static func viewAllText(_ t: ThreadSummary) -> String? {
        t.commentCount > t.latestComments.count ? Lp("social.thread.viewAll", count: t.commentCount) : nil
    }

    /// The thread after a comment of the caller was added: count and the two latest.
    public static func adding(_ c: Comment, to t: ThreadSummary) -> ThreadSummary {
        var out = t
        guard c.kind == .person, !t.latestComments.contains(where: { $0.id == c.id }) else { return out }
        out.commentCount += 1
        out.latestComments = Array(sorted(t.latestComments + [c]).suffix(2))
        return out
    }
}

// MARK: Mentions

/// Someone who can be mentioned: the people who see the post, or the conversation's members.
public struct MentionCandidate: Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var avatarUrl: String?

    public init(id: String, name: String, avatarUrl: String? = nil) {
        self.id = id
        self.name = name
        self.avatarUrl = avatarUrl
    }
}

public enum Mentions {
    /// The "@query" being typed at the end of `text` (where the cursor is while typing): the text after the last "@"
    /// that starts a word, without a line break and at most 30 characters. Nil when not mentioning.
    public static func activeQuery(_ text: String) -> String? {
        guard let at = text.lastIndex(of: "@") else { return nil }
        if at > text.startIndex {
            let before = text[text.index(before: at)]
            guard before.isWhitespace || before.isPunctuation else { return nil }
        }
        let query = String(text[text.index(after: at)...])
        guard !query.contains(where: \.isNewline), query.count <= 30 else { return nil }
        // "@Anna " ends the mention once a second space follows a whole word that matched nobody.
        if query.hasSuffix("  ") { return nil }
        return query
    }

    /// Candidates matching the query: names starting with it (any word), then containing it; at most 6.
    public static func filter(_ candidates: [MentionCandidate], query: String, excluding me: String?) -> [MentionCandidate] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let pool = candidates.filter { $0.id != me }
        guard !q.isEmpty else { return Array(pool.sorted { $0.name < $1.name }.prefix(6)) }
        let starts = pool.filter { c in c.name.lowercased().split(separator: " ").contains { $0.hasPrefix(q) } || c.name.lowercased().hasPrefix(q) }
        let contains = pool.filter { c in !starts.contains(c) && c.name.lowercased().contains(q) }
        return Array((starts.sorted { $0.name < $1.name } + contains.sorted { $0.name < $1.name }).prefix(6))
    }

    /// Replace the "@query" being typed with "@Name " and remember the pick.
    public static func complete(_ text: String, with c: MentionCandidate) -> String {
        guard let at = text.lastIndex(of: "@") else { return text + "@\(c.name) " }
        return String(text[..<at]) + "@\(c.name) "
    }

    /// The spans of the picked people in `text`, in UTF-16 code units (README §5.18): every "@Name" of a pick,
    /// longest names first so "@Anna Maria" wins over "@Anna", non-overlapping, at most `max`.
    public static func spans(in text: String, picks: [MentionCandidate], max: Int = 20) -> [Mention] {
        let ns = text as NSString
        var taken: [NSRange] = []
        var out: [Mention] = []
        var seen = Set<String>()
        for p in picks.sorted(by: { $0.name.count > $1.name.count }) where seen.insert(p.id).inserted && !p.name.isEmpty {
            let needle = "@" + p.name
            var search = NSRange(location: 0, length: ns.length)
            while search.length > 0 {
                let r = ns.range(of: needle, options: [], range: search)
                guard r.location != NSNotFound else { break }
                // A whole name: not followed by a letter ("@Annabel" isn't "@Anna").
                let end = r.location + r.length
                let followedByLetter = end < ns.length && CharacterSet.letters.contains(UnicodeScalar(ns.character(at: end)).map { $0 } ?? " ")
                if !followedByLetter, !taken.contains(where: { NSIntersectionRange($0, r).length > 0 }) {
                    taken.append(r)
                    out.append(Mention(userId: p.id, start: r.location, length: r.length))
                }
                search = NSRange(location: end, length: ns.length - end)
            }
        }
        return Array(out.sorted { $0.start < $1.start }.prefix(max))
    }

    /// The people mentioned in an existing text (editing a comment or message): the text under each span, without
    /// the "@".
    public static func picks(in text: String, mentions: [Mention]) -> [MentionCandidate] {
        let ns = text as NSString
        var out: [MentionCandidate] = []
        for m in mentions where m.start >= 0 && m.length > 1 && m.start + m.length <= ns.length {
            var name = ns.substring(with: NSRange(location: m.start, length: m.length))
            if name.hasPrefix("@") { name.removeFirst() }
            if !out.contains(where: { $0.id == m.userId }) { out.append(MentionCandidate(id: m.userId, name: name)) }
        }
        return out
    }

    /// The body with mentions highlighted and links made tappable.
    public static func attributed(_ body: String, mentions: [Mention]?) -> AttributedString {
        var out = AttributedString(body)
        let ns = body as NSString
        for m in mentions ?? [] where m.start >= 0 && m.length > 0 && m.start + m.length <= ns.length {
            let r = NSRange(location: m.start, length: m.length)
            guard let range = Range(r, in: body), let ar = Range(range, in: out) else { continue }
            out[ar].inlinePresentationIntent = .stronglyEmphasized
            out[ar].link = URL(string: "kinframe-person:\(m.userId)")
        }
        for (range, url) in Links.all(in: body) {
            guard let ar = Range(range, in: out) else { continue }
            out[ar].link = url
        }
        return out
    }

    /// Whether the text mentions this person.
    public static func mentions(_ mentions: [Mention]?, user: String?) -> Bool {
        guard let user else { return false }
        return mentions?.contains { $0.userId == user } ?? false
    }
}

/// Links in plain text (comments and messages stay plain text; clients render links).
public enum Links {
    public static func all(in text: String) -> [(Range<String.Index>, URL)] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        let ns = text as NSString
        return detector.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard let url = m.url, url.scheme == "http" || url.scheme == "https", let r = Range(m.range, in: text) else { return nil }
            return (r, url)
        }
    }

    /// The first http(s) link, for a link preview.
    public static func first(in text: String) -> URL? {
        all(in: text).first?.1
    }
}

// MARK: Timeline

/// What a row of the timeline is: a post, with a header over the first of consecutive answers to one prompt
/// ("6 answers to Your view right now").
public struct TimelineEntry: Identifiable, Equatable, Sendable {
    public var post: TimelinePost
    /// Set on the first post of a run of at least two answers to the same prompt.
    public var promptHeader: PromptHeader?
    public var id: String { post.id }

    public struct PromptHeader: Equatable, Sendable {
        public var title: String
        public var count: Int
    }
}

public enum TimelineLogic {
    public static func entries(_ posts: [TimelinePost]) -> [TimelineEntry] {
        var out: [TimelineEntry] = []
        var i = 0
        while i < posts.count {
            let p = posts[i]
            var j = i + 1
            if let prompt = p.prompt?.id {
                while j < posts.count, posts[j].prompt?.id == prompt { j += 1 }
            }
            let run = j - i
            for k in i..<j {
                let header = k == i && run >= 2 ? TimelineEntry.PromptHeader(title: p.prompt?.title ?? "", count: run) : nil
                out.append(TimelineEntry(post: posts[k], promptHeader: header))
            }
            i = j
        }
        return out
    }

    /// Append an older page, skipping posts already shown.
    public static func appending(_ page: [TimelinePost], to posts: [TimelinePost]) -> [TimelinePost] {
        let known = Set(posts.map(\.id))
        return posts + page.filter { !known.contains($0.id) }
    }

    /// The label above a post: the family (and circle), or "To Oma's frame".
    public static func label(_ p: TimelinePost) -> String {
        switch p.kind {
        case .frame:
            return L("social.post.toFrame", ["frame": p.frameName ?? ""])
        case .feed:
            if let circle = p.circleName, !circle.isEmpty {
                return L("social.post.inCircle", ["feed": p.feedName ?? "", "circle": circle])
            }
            return p.feedName ?? ""
        }
    }

    /// "Seen on 4 frames · ❤ from Oma's frame".
    public static func seenText(_ p: TimelinePost) -> String? {
        var parts: [String] = []
        if let s = p.seen, s.frames > 0 {
            parts.append(s.seen > 0 ? Lp("social.post.seenOn", count: s.seen) : Lp("social.post.deliveredTo", count: s.frames))
        }
        if let f = p.thread.reactions.frames.first(where: { $0.reaction == .love }) {
            parts.append(L("social.post.loveFrom", ["frame": f.frameName]))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// The caption of the post: the first item's caption.
    public static func caption(_ p: TimelinePost) -> String? {
        p.items.lazy.compactMap { $0.caption?.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
    }

    /// Who may react (the server says whether comments are possible; reactions follow visibility).
    public static func canComment(_ p: TimelinePost) -> Bool {
        p.thread.canComment ?? (p.thread.state == .open)
    }
}

// MARK: Circles

public enum CircleLogic {
    /// Circle admins and the feed's owner and admins manage a circle.
    public static func canManage(_ c: FamilyCircle, feedRole: FeedRole?) -> Bool {
        c.myRole == .admin || feedRole == .owner || feedRole == .admin
    }

    /// Owner and admins create circles, or any member when the family lets them.
    public static func canCreate(feedRole: FeedRole, membersCanCreate: Bool) -> Bool {
        feedRole == .owner || feedRole == .admin || membersCanCreate
    }

    public static let maxName = 40

    /// A trimmed name of 1–40 characters, unique in the family ignoring case.
    public static func nameProblem(_ name: String, existing: [FamilyCircle], editing: String? = nil) -> String? {
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return L("social.circle.nameEmpty") }
        if t.count > maxName { return L("social.circle.nameLong", ["max": maxName]) }
        if existing.contains(where: { $0.id != editing && $0.name.lowercased() == t.lowercased() }) { return L("social.circle.nameTaken") }
        return nil
    }

    /// The circles the caller can post to (they are in them), for "Share with".
    public static func audience(_ circles: [FamilyCircle]) -> [FamilyCircle] {
        circles.filter { $0.myRole != nil }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}

// MARK: Family mute

extension Components.Schemas.FeedMute {
    public var label: String { L("social.mute.\(rawValue)") }
}

// MARK: Roles

extension Components.Schemas.FeedRole {
    /// The role's name in lists and menus.
    public var label: String {
        switch self {
        case .owner: L("role.owner")
        case .admin: L("role.admin")
        case .poster: L("ios.feeds.rolePoster")
        case .member: L("social.role.member")
        }
    }
}
