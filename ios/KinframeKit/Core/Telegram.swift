import Foundation

// The Telegram bridge (docs/spec/telegram.md, docs/api/README.md §5.17): short names for the generated schemas and the
// pure rules the screens share: the open enums, t.me links and the Telegram app, following a connect or link code,
// status texts per code (spec §15), settings changes and the share message. Unit-tested in TelegramTests.
//
// As for email, the enums arrive as plain strings (tools/openapi specprep opens them); unknown values map to nil.

public typealias TelegramChat = Components.Schemas.TelegramChat
public typealias TelegramCode = Components.Schemas.TelegramCode
public typealias TelegramContributor = Components.Schemas.TelegramContributor
public typealias TelegramLink = Components.Schemas.TelegramLink

extension Components.Schemas.TelegramChat: Identifiable {}
extension Components.Schemas.TelegramContributor: Identifiable {}
extension Components.Schemas.TelegramCode: Identifiable {}
extension Components.Schemas.TelegramTopic: Identifiable {}

// MARK: Open enums

public enum TelegramKind: String, CaseIterable, Sendable {
    case group
    case `private`
}

public enum TelegramChatState: String, CaseIterable, Sendable {
    case active
    case needsAttention = "needs_attention"
    case paused
    case disconnected
}

public enum TelegramChatError: String, CaseIterable, Sendable {
    case privacyMode = "privacy_mode"
    case botChanged = "bot_changed"
}

public enum TelegramWarning: String, CaseIterable, Sendable {
    case cannotPost = "cannot_post"
    case reactionsOff = "reactions_off"
}

public enum TelegramEnded: String, CaseIterable, Sendable {
    case disconnected
    case stopped
    case botRemoved = "bot_removed"
    case chatGone = "chat_gone"
    case botBlocked = "bot_blocked"
    case targetDeleted = "target_deleted"
}

public enum TelegramContributors: String, CaseIterable, Sendable {
    case everyone
    case linkedOnly = "linked_only"
}

public enum TelegramCodeState: String, CaseIterable, Sendable {
    case open
    case used
    case expired
    case revoked
}

public enum TelegramCodeError: String, CaseIterable, Sendable {
    case codeExpired = "code_expired"
    case codeUsed = "code_used"
    case alreadyConnected = "already_connected"
    case limitReached = "limit_reached"
    case wrongChatKind = "wrong_chat_kind"
    case alreadyLinked = "already_linked"
    case missingPermission = "missing_permission"
}

public extension Components.Schemas.TelegramChat {
    var kind: TelegramKind? { TelegramKind(rawValue: chatKind) }
    var state: TelegramChatState? { TelegramChatState(rawValue: status) }
    var error: TelegramChatError? { errorCode.flatMap(TelegramChatError.init(rawValue:)) }
    var ended: TelegramEnded? { endedReason.flatMap(TelegramEnded.init(rawValue:)) }
    var knownWarnings: [TelegramWarning] { (warnings ?? []).compactMap(TelegramWarning.init(rawValue:)) }
    var contributorsMode: TelegramContributors? { TelegramContributors(rawValue: contributors) }
    var channelTarget: ChannelTarget? { ChannelTarget.from(kind: target.kind, frameId: target.frameId, feedId: target.feedId) }
    var isFeedTarget: Bool { target.kind == "feed" }
    var targetName: String { (isFeedTarget ? target.feedName : target.frameName) ?? "" }
    var isLive: Bool { state != .disconnected }
    var isPaused: Bool { state == .paused }
    /// Settings can change until the chat is disconnected (`409 invalid_transition` after).
    var isEditable: Bool { isLive && canManage != false }
    /// Only the owner may change `notify_reactions`.
    var mayChangeNotifyReactions: Bool { isOwner == true }
    var isForumGroup: Bool { isForum == true }
}

public extension Components.Schemas.TelegramCode {
    var state: TelegramCodeState? { TelegramCodeState(rawValue: status) }
    var error: TelegramCodeError? { errorCode.flatMap(TelegramCodeError.init(rawValue:)) }
    var kind: TelegramKind? { chatKind.flatMap(TelegramKind.init(rawValue:)) }
}

// MARK: Links

public enum TelegramLinks {
    /// `https://t.me/<bot>`.
    public static func bot(_ username: String) -> URL? {
        let u = username.trimmingCharacters(in: CharacterSet(charactersIn: "@ \n"))
        guard !u.isEmpty, u.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") }) else { return nil }
        return URL(string: "https://t.me/\(u)")
    }

    /// A `https://t.me/<bot>?startgroup=<code>` or `?start=<code>` link from the server, checked: only t.me links with
    /// a bot name are opened.
    public static func web(_ raw: String?) -> URL? {
        guard let raw, let c = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme?.lowercased() == "https", let host = c.host?.lowercased(),
              host == "t.me" || host == "telegram.me" else { return nil }
        let parts = c.path.split(separator: "/")
        guard parts.count == 1, bot(String(parts[0])) != nil else { return nil }
        return c.url
    }

    /// The same link for the Telegram app (`tg://resolve?domain=<bot>&startgroup=<code>`), tried first so an installed
    /// Telegram opens straight away; nil when it isn't a bot link.
    public static func app(_ web: URL) -> URL? {
        guard let c = URLComponents(url: web, resolvingAgainstBaseURL: false),
              let bot = c.path.split(separator: "/").first.map(String.init) else { return nil }
        var out = URLComponents()
        out.scheme = "tg"
        out.host = "resolve"
        var items = [URLQueryItem(name: "domain", value: bot)]
        for item in c.queryItems ?? [] where ["start", "startgroup"].contains(item.name) {
            guard let v = item.value, !v.isEmpty, v.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") }) else { continue }
            items.append(URLQueryItem(name: item.name, value: v))
        }
        out.queryItems = items
        return out.url
    }
}

// MARK: Following a code

/// Where a connect or link code stands while the sheet waits for Telegram (spec §5.1).
public enum TelegramCodeProgress: Equatable, Sendable {
    /// Still open; `problem` is the last failed attempt in Telegram (the code may still work).
    case waiting(problem: SourceLine?)
    /// Used: the chat it connected (connect codes) or the link it made.
    case done(chatId: String?)
    /// Expired or revoked: make a new one.
    case expired

    public static let pollSeconds: Double = 3

    public static func of(_ code: TelegramCode, targetName: String = "", now: Date = Date()) -> TelegramCodeProgress {
        switch code.state {
        case .used: return .done(chatId: code.chatId)
        case .expired, .revoked: return .expired
        case .open, nil:
            if code.expiresAt <= now { return .expired }
            return .waiting(problem: code.errorCode.map { TelegramText.codeError($0, kind: code.kind, targetName: targetName) })
        }
    }

    public var isFinal: Bool {
        if case .waiting = self { return false }
        return true
    }
}

// MARK: Texts

public enum TelegramText {
    /// The status line (spec §5.2, §15.1): green "Connected · last photo 10:32", amber with the fix, grey "Paused",
    /// the reason it ended.
    public static func headline(_ c: TelegramChat, now: Date = Date(), locale: Locale = L10n.locale) -> SourceLine {
        switch c.state {
        case .active:
            if let last = c.lastDeliveryAt {
                return SourceLine("ios.tg.status.activeLast", ["when": Format.relative(last, locale: locale, now: now)], tone: .ok)
            }
            return SourceLine("ios.tg.status.active", tone: .ok)
        case .needsAttention:
            switch c.error {
            case .privacyMode: return SourceLine("ios.tg.status.privacy_mode", ["title": c.title], tone: .attention)
            case .botChanged: return SourceLine("ios.tg.status.bot_changed", tone: .attention)
            case nil: return SourceLine("ios.tg.status.attention", tone: .attention)
            }
        case .paused: return SourceLine("ios.tg.status.paused", tone: .paused)
        case .disconnected: return ended(c)
        case nil: return SourceLine("ios.tg.status.unknown")
        }
    }

    /// "Disconnected by Anna", "Disconnected with /stop by Ben", "The bot was removed from the group", …
    public static func ended(_ c: TelegramChat) -> SourceLine {
        let by = c.endedByName?.trimmingCharacters(in: .whitespacesAndNewlines)
        switch c.ended {
        case .disconnected:
            if let by, !by.isEmpty { return SourceLine("ios.tg.ended.disconnectedBy", ["name": by]) }
            return SourceLine("ios.tg.ended.disconnected")
        case .stopped:
            if let by, !by.isEmpty { return SourceLine("ios.tg.ended.stoppedBy", ["name": by]) }
            return SourceLine("ios.tg.ended.stopped")
        case .botRemoved: return SourceLine("ios.tg.ended.bot_removed", tone: .attention)
        case .chatGone: return SourceLine("ios.tg.ended.chat_gone", tone: .attention)
        case .botBlocked: return SourceLine("ios.tg.ended.bot_blocked", ["name": c.title], tone: .attention)
        case .targetDeleted, nil: return SourceLine("ios.tg.ended.disconnected")
        }
    }

    /// What the person can do about `needs_attention`.
    public enum Fix: Equatable, Sendable {
        /// Make the bot an admin of the group (instructions, nothing to tap in the app).
        case makeAdmin
        /// Connect the group again with a new code.
        case reconnect
    }

    public static func fix(_ c: TelegramChat) -> Fix? {
        guard c.state == .needsAttention else { return nil }
        switch c.error {
        case .privacyMode: return .makeAdmin
        case .botChanged: return .reconnect
        case nil: return nil
        }
    }

    public static func warnings(_ c: TelegramChat) -> [SourceLine] {
        guard c.isLive else { return [] }
        return c.knownWarnings.map { SourceLine("ios.tg.warn.\($0.rawValue)", tone: .attention) }
    }

    /// "Group" / "Private chat", the owner when it isn't you.
    public static func subtitle(_ c: TelegramChat) -> [SourceLine] {
        var out = [SourceLine(c.kind == .private ? "ios.tg.kind.private" : "ios.tg.kind.group")]
        if let owner = c.owner?.displayName, c.isOwner != true { out.append(SourceLine("ios.tg.connectedBy", ["name": owner])) }
        return out
    }

    /// "48 photos and videos sent · 5 people".
    public static func counts(_ c: TelegramChat) -> [SourceLine] {
        var out = [SourceLine("ios.tg.count.sent", count: c.counts.sent)]
        if c.counts.contributors > 0 { out.append(SourceLine("ios.tg.count.people", count: c.counts.contributors)) }
        return out
    }

    /// "12 sent · last 10:32" for one person in a chat.
    public static func contributor(_ p: TelegramContributor, now: Date = Date(), locale: Locale = L10n.locale) -> SourceLine {
        SourceLine("ios.tg.person.line", ["when": Format.relative(p.lastSeenAt, locale: locale, now: now)], count: p.sentCount)
    }

    /// A failed attempt to redeem a code (spec §15.2, `TelegramCode.error_code`).
    public static func codeError(_ raw: String, kind: TelegramKind?, targetName: String) -> SourceLine {
        switch TelegramCodeError(rawValue: raw) {
        case .wrongChatKind:
            return SourceLine(kind == .private ? "ios.tg.codeErr.wrong_chat_kind_private" : "ios.tg.codeErr.wrong_chat_kind_group", tone: .attention)
        case .limitReached: return SourceLine("ios.tg.codeErr.limit_reached", ["name": targetName], tone: .error)
        case .some(let e): return SourceLine("ios.tg.codeErr.\(e.rawValue)", tone: e == .alreadyConnected ? .attention : .error)
        case nil: return SourceLine("ios.tg.codeErr.unknown", tone: .error)
        }
    }

    /// The linked account: "Carla Peeters (@carla)".
    public static func link(_ l: TelegramLink) -> String {
        guard let u = l.username?.trimmingCharacters(in: CharacterSet(charactersIn: "@ ")), !u.isEmpty else { return l.displayName }
        return "\(l.displayName) (@\(u))"
    }

    /// The problem behind a failed call, for the problems these screens expect.
    public static func problem(_ error: Error, limits: Limits = .defaults) -> String? {
        guard let e = APIError.wrap(error) as? APIError else { return nil }
        switch e.code {
        case "telegram_already_linked": return L("ios.tg.err.alreadyLinked")
        case "limit_reached": return L("ios.tg.err.limit", ["count": "\(limits.maxTelegramChatsPerTarget)"])
        case "invalid_transition": return L("ios.tg.err.disconnected")
        case "rate_limited": return L("ios.tg.err.rateLimited")
        case "feature_disabled": return L("ios.tg.err.disabled")
        default: return nil
        }
    }
}

// MARK: Sharing

public enum TelegramShare {
    /// "Invite someone to send by Telegram": the `?start=` link with a short note in the user's language.
    public static func inviteMessage(targetName: String, url: String, template: String = L("ios.tg.inviteText")) -> String {
        let t = template.contains("{url}") ? template : "Send photos to {name} with Telegram: open this link and tap Start. {url}"
        return t.replacingOccurrences(of: "{name}", with: targetName).replacingOccurrences(of: "{url}", with: url)
    }
}

// MARK: Changes

/// One change to a chat (`TelegramChatUpdate`). Sent as raw JSON: `topic_id: null` (all topics) is an explicit null.
public enum TelegramChatChange: Equatable, Sendable {
    case paused(Bool)
    case contributors(TelegramContributors)
    case frameReplies(Bool)
    case notifyReactions(Bool)
    case offerAppInvite(Bool)
    case topic(Int64?)
    case takeOver

    public var patch: [String: JSONValue] {
        switch self {
        case .paused(let v): ["paused": .bool(v)]
        case .contributors(let m): ["contributors": .string(m.rawValue)]
        case .frameReplies(let v): ["frame_replies": .bool(v)]
        case .notifyReactions(let v): ["notify_reactions": .bool(v)]
        case .offerAppInvite(let v): ["offer_app_invite": .bool(v)]
        case .topic(let id): ["topic_id": id.map { .number(Double($0)) } ?? .null]
        case .takeOver: ["take_over": .bool(true)]
        }
    }
}

/// Chats in the order the screens list them: live ones (needing attention first), then the disconnected ones under
/// "Earlier", newest first.
public enum TelegramChats {
    public static func live(_ chats: [TelegramChat]) -> [TelegramChat] {
        chats.filter(\.isLive).sorted { a, b in
            let aa = a.state == .needsAttention, bb = b.state == .needsAttention
            if aa != bb { return aa }
            return a.createdAt < b.createdAt
        }
    }

    public static func earlier(_ chats: [TelegramChat]) -> [TelegramChat] {
        chats.filter { !$0.isLive }.sorted { ($0.endedAt ?? $0.createdAt) > ($1.endedAt ?? $1.createdAt) }
    }

    /// Adding chats stops at the limit (live ones count).
    public static func atLimit(_ chats: [TelegramChat], limits: Limits) -> Bool {
        chats.filter(\.isLive).count >= limits.maxTelegramChatsPerTarget
    }
}
