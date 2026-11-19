import Foundation

// What email to frame (docs/spec/email-inbox.md) and the Telegram bridge (docs/spec/telegram.md) share: the frame or
// feed an address or chat fills, discovery's switches, join requests that came through an outside channel, and where
// their notifications lead. Unit-tested in ChannelTests.

/// The frame or feed an inbox address or a Telegram chat fills.
public enum ChannelTarget: Hashable, Sendable {
    case frame(String)
    case feed(String)

    public var id: String {
        switch self {
        case .frame(let id), .feed(let id): id
        }
    }

    public var isFeed: Bool {
        if case .feed = self { return true }
        return false
    }

    /// `EmailAddressTarget` / `TelegramChatTarget`: `kind` with `frame_id` or `feed_id` (nil for a kind this app
    /// doesn't know).
    public static func from(kind: String, frameId: String?, feedId: String?) -> ChannelTarget? {
        switch kind {
        case "frame": frameId.map { .frame($0) }
        case "feed": feedId.map { .feed($0) }
        default: nil
        }
    }
}

/// Discovery's email and Telegram switches (README §5.16, §5.17: clients hide what discovery doesn't offer).
public struct ChannelFeatures: Equatable, Sendable {
    /// "Email photos to this frame/feed" is offered.
    public var email: Bool
    public var emailDomain: String?
    /// Every Telegram section is offered.
    public var telegram: Bool
    public var telegramBot: String?
    /// The bot can't see photos in a group until it is an admin: the connect sheet says so up front.
    public var telegramPrivacyMode: Bool
    public var telegramMaxFileBytes: Int64?

    public init(_ d: Discovery?) {
        email = d?.emailInbox == true
        emailDomain = email ? d?.emailInboxDomain : nil
        let bot = d?.telegramBotUsername?.trimmingCharacters(in: .whitespacesAndNewlines)
        telegram = d?.telegram == true
        telegramBot = telegram ? (bot?.isEmpty == false ? bot : nil) : nil
        telegramPrivacyMode = telegram && d?.telegramPrivacyMode == true
        telegramMaxFileBytes = telegram ? d?.telegramMaxFileBytes : nil
    }
}

// MARK: Join requests

/// How a join request came (`JoinRequest.via`, an open enum: later channels add values).
public enum JoinVia: String, CaseIterable, Sendable {
    case frameCode = "frame_code"
    case invite
    case email
    case telegram

    /// An outside channel: the request carries `via_name` (and for email `via_matches`).
    public var isChannel: Bool { self == .email || self == .telegram }
}

public extension Components.Schemas.JoinRequest {
    var channel: JoinVia? { JoinVia(rawValue: via) }
}

public enum JoinRequestOrigin {
    /// "Asked to join from the email link", "Asked to join from Telegram", "Invited by Anna", "Used the frame's code".
    public static func line(_ r: JoinRequest) -> SourceLine {
        switch r.channel {
        case .email: SourceLine("ios.join.viaEmail")
        case .telegram: SourceLine("ios.join.viaTelegram")
        case .frameCode: SourceLine("people.viaCode")
        case .invite: SourceLine("people.viaInvite", ["name": r.invitedByName ?? "?"])
        case nil: r.invitedByName.map { SourceLine("people.viaInvite", ["name": $0]) } ?? SourceLine("ios.join.viaOther")
        }
    }

    /// Whom the channel invited ("Jan · jan.peeters@telenet.be") and whether the requester is that person: a check
    /// mark when `via_matches` is true, a warning when it is false (the account's verified email is another one),
    /// nothing when the channel can't tell (Telegram).
    public static func channelName(_ r: JoinRequest) -> (name: String, match: SourceLine?)? {
        guard let name = r.viaName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        switch r.viaMatches {
        case true?: return (name, SourceLine("ios.join.matches", tone: .ok))
        case false?: return (name, SourceLine(r.channel == .email ? "ios.join.noMatchEmail" : "ios.join.noMatch", tone: .attention))
        case nil: return (name, nil)
        }
    }
}

// MARK: Push

/// `email_pending_sender` (also with the text `email_pending_expiring`) and `telegram_attention` (README §6.3).
public enum ChannelPush {
    public static func route(key: String, id: (String) -> String?) -> PushRoute? {
        switch key {
        case "email_pending_sender", "email_pending_expiring":
            guard let address = id("address_id") else { return nil }
            return .emailAddress(addressId: address, frameId: id("frame_id"), feedId: id("feed_id"))
        case "telegram_attention":
            guard let chat = id("chat_id") else { return nil }
            return .telegramChat(chatId: chat, frameId: id("frame_id"), feedId: id("feed_id"))
        default:
            return nil
        }
    }
}
