import Foundation

// Email to frame (docs/spec/email-inbox.md, docs/api/README.md §5.16): short names for the generated schemas and the
// pure rules the screens share: the open enums, the friendly part of an address, the ready-made share message, status
// and outcome texts, request bodies and problem texts. Unit-tested in EmailInboxTests.
//
// The enums arrive as plain strings (tools/openapi specprep opens them): a newer server may add a status, outcome or
// reason, and that must never make a list of addresses, senders or mail fail to decode. Each has a Swift enum here
// whose `init?(rawValue:)` is nil for values this app doesn't know; screens show a neutral fallback for those.

public typealias EmailAddress = Components.Schemas.EmailAddress
public typealias EmailSender = Components.Schemas.EmailSender
public typealias EmailPendingSender = Components.Schemas.EmailPendingSender
public typealias EmailHeldMessage = Components.Schemas.EmailHeldMessage
public typealias EmailHeldMessageSummary = Components.Schemas.EmailHeldMessageSummary
public typealias EmailMessage = Components.Schemas.EmailMessage
public typealias EmailMessagePage = Components.Schemas.EmailMessagePage

extension Components.Schemas.EmailAddress: Identifiable {}
extension Components.Schemas.EmailSender: Identifiable {}
extension Components.Schemas.EmailPendingSender: Identifiable {}
extension Components.Schemas.EmailHeldMessageSummary: Identifiable {}
extension Components.Schemas.EmailHeldMessage: Identifiable {}
extension Components.Schemas.EmailMessage: Identifiable {}

// MARK: Open enums

public enum InboxStatus: String, CaseIterable, Sendable {
    case active
    case paused
}

public enum InboxSenderState: String, CaseIterable, Sendable {
    case allowed
    case blocked
    case pending
}

public enum InboxSenderOrigin: String, CaseIterable, Sendable {
    case manual
    case approved
    case mail
}

public enum InboxHeldReason: String, CaseIterable, Sendable {
    case unknownSender = "unknown_sender"
    case unverifiedSender = "unverified_sender"
}

public enum InboxOutcome: String, CaseIterable, Sendable {
    case delivered
    case held
    case noMedia = "no_media"
    case tooLarge = "too_large"
    case encrypted
    case duplicate
    case blocked
    case rejectedDmarc = "rejected_dmarc"
    case ignored
    case rateLimited = "rate_limited"
    case failed
}

public enum InboxReply: String, CaseIterable, Sendable {
    case delivered
    case held
    case noMedia = "no_media"
    case tooLarge = "too_large"
    case encrypted
}

public extension Components.Schemas.EmailAddress {
    var state: InboxStatus? { InboxStatus(rawValue: status) }
    var isPaused: Bool { state == .paused }
    var channelTarget: ChannelTarget? { ChannelTarget.from(kind: target.kind, frameId: target.frameId, feedId: target.feedId) }
    var isFeedTarget: Bool { target.kind == "feed" }
    /// "Oma's frame" / "Peeters kids": the target's name, else the address's label.
    var targetName: String { (isFeedTarget ? target.feedName : target.frameName) ?? name }
    var ownerName: String? { owner?.displayName }
    var pendingCount: Int { counts.pendingSenders }
    /// Only the owner may change `notify_reactions` (their own switch).
    var mayChangeNotifyReactions: Bool { isOwner == true }
    /// False only when the server said it can't send replies.
    var hasReplies: Bool { repliesAvailable ?? true }
}

public extension Components.Schemas.EmailSender {
    var knownState: InboxSenderState? { InboxSenderState(rawValue: state) }
    var knownOrigin: InboxSenderOrigin? { InboxSenderOrigin(rawValue: origin) }
    var isBlocked: Bool { knownState == .blocked }
    /// "Jan (jan.peeters@telenet.be)", or just the address.
    var label: String { EmailText.person(displayName, email) }
}

public extension Components.Schemas.EmailPendingSender {
    var label: String { EmailText.person(displayName, email) }
    var mediaCount: Int { messages.reduce(0) { $0 + $1.mediaCount } }
    /// The earliest moment held mail of theirs is deleted.
    var expiresAt: Date? { messages.map(\.expiresAt).min() }
    /// Approving delivers only authenticated mail; unverified mail stays held (deliver it one by one).
    var hasUnverified: Bool { messages.contains { $0.isUnverified } }
}

public extension Components.Schemas.EmailHeldMessageSummary {
    var heldReason: InboxHeldReason? { InboxHeldReason(rawValue: reason) }
    /// Not authenticated: the From can't be trusted; only "Deliver this one" and "Delete" (spec §5.3).
    var isUnverified: Bool { heldReason == .unverifiedSender || !authenticated }
}

public extension Components.Schemas.EmailHeldMessage {
    var heldReason: InboxHeldReason? { InboxHeldReason(rawValue: reason) }
    var isUnverified: Bool { heldReason == .unverifiedSender || !auth.authenticated }
    var senderLabel: String { EmailText.person(senderName, senderEmail) }
}

public extension Components.Schemas.EmailMessage {
    var knownOutcome: InboxOutcome? { InboxOutcome(rawValue: outcome) }
    var knownReply: InboxReply? { reply.flatMap(InboxReply.init(rawValue:)) }
}

// MARK: The friendly part

/// The friendly part of an address (`slug`: `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`, at most 24 characters).
public enum EmailSlug {
    public static let maxLength = 24

    /// Made from the frame's or feed's name: lower case, accents removed, the possessive dropped, other characters as
    /// dashes. "Oma's frame" → "oma-frame", "Peeters kids 📸" → "peeters-kids", "Café Zoë" → "cafe-zoe".
    public static func suggest(from name: String) -> String {
        let folded = name.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en"))
            .lowercased()
        var words: [String] = []
        for raw in folded.split(whereSeparator: { $0.isWhitespace }) {
            var word = String(raw)
            // "oma's" / "oma’s" → "oma"
            if let apostrophe = word.firstIndex(where: { $0 == "'" || $0 == "’" }) { word = String(word[..<apostrophe]) }
            words.append(word)
        }
        let slug = clean(words.joined(separator: "-"))
        return slug.isEmpty ? "photos" : slug
    }

    /// What may be typed: lower case letters, digits and single dashes, no dash at either end, at most 24.
    public static func clean(_ input: String) -> String {
        var out = ""
        for ch in input.lowercased() {
            if ch.isASCII, ch.isLetter || ch.isNumber {
                out.append(ch)
            } else if !out.isEmpty, out.last != "-" {
                out.append("-")
            }
        }
        out = String(out.prefix(maxLength))
        while out.hasSuffix("-") { out.removeLast() }
        return out
    }

    /// As typed, keeping a trailing dash so "oma-" can become "oma-jan" (for the text field).
    public static func typing(_ input: String) -> String {
        var out = ""
        for ch in input.lowercased() {
            if ch.isASCII, ch.isLetter || ch.isNumber {
                out.append(ch)
            } else if (ch == "-" || ch == " " || ch == "_" || ch == "."), !out.isEmpty, out.last != "-" {
                out.append("-")
            }
        }
        return String(out.prefix(maxLength))
    }

    public static func isValid(_ slug: String) -> Bool {
        guard (1...maxLength).contains(slug.count), slug.first != "-", slug.last != "-" else { return false }
        return slug.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == "-") }
    }

    /// "oma-······@in.kinframe.appmire.be": what the address will look like (the random part comes from the server).
    public static func preview(_ slug: String, domain: String?) -> String {
        let s = slug.isEmpty ? "…" : slug
        guard let domain, !domain.isEmpty else { return "\(s)-······" }
        return "\(s)-······@\(domain)"
    }
}

// MARK: Sharing

public enum EmailShare {
    /// The ready-made message for a relative, in the user's language (README §5.16): "You can now email photos to
    /// Oma's frame: oma-k7q2m9@in.kinframe.appmire.be. Just attach them; the subject becomes the caption. The first
    /// time, Anna approves your address." With *anyone may send* the approval sentence is left out.
    public static func message(_ a: EmailAddress, ownerName: String? = nil, template: String? = nil, openTemplate: String? = nil) -> String {
        let owner = ownerName ?? a.ownerName ?? "?"
        let open = a.anyoneMaySend
        let raw = open ? (openTemplate ?? L("ios.em.shareTextOpen")) : (template ?? L("ios.em.shareText"))
        let fallback = open
            ? "You can now email photos to {name}: {address}. Just attach them; the subject becomes the caption."
            : "You can now email photos to {name}: {address}. Just attach them; the subject becomes the caption. The first time, {owner} approves your address."
        let t = raw.contains("{address}") ? raw : fallback
        return t.replacingOccurrences(of: "{name}", with: a.name)
            .replacingOccurrences(of: "{address}", with: a.address)
            .replacingOccurrences(of: "{owner}", with: owner)
    }

    public static func subject(_ a: EmailAddress) -> String {
        L("ios.em.shareSubject", ["name": a.name])
    }
}

// MARK: Texts

public enum EmailText {
    /// "Jan (jan@x.be)", or the address alone.
    public static func person(_ name: String?, _ email: String) -> String {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty, n.lowercased() != email.lowercased() else { return email }
        return "\(n) (\(email))"
    }

    /// The first word of a person's line: the name, else the address.
    public static func shortName(_ name: String?, _ email: String?) -> String {
        if let n = name?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty { return n }
        if let e = email, !e.isEmpty { return e }
        return "?"
    }

    /// Green "Ready" with the last mail, grey "Paused: mail to it bounces" (spec §5.1).
    public static func headline(_ a: EmailAddress, now: Date = Date(), locale: Locale = L10n.locale) -> SourceLine {
        switch a.state {
        case .paused: return SourceLine("ios.em.status.paused", tone: .paused)
        case .active:
            if let last = a.lastReceivedAt {
                return SourceLine("ios.em.status.readyLast", ["when": Format.relative(last, locale: locale, now: now)], tone: .ok)
            }
            return SourceLine("ios.em.status.ready", tone: .ok)
        case nil: return SourceLine("ios.em.status.unknown")
        }
    }

    /// "2 waiting for your OK", "12 delivered in 30 days", "3 allowed senders".
    public static func counts(_ a: EmailAddress) -> [SourceLine] {
        var out: [SourceLine] = []
        if a.counts.pendingSenders > 0 { out.append(SourceLine("ios.em.count.pending", tone: .attention, count: a.counts.pendingSenders)) }
        if let d = a.counts.delivered30d, d > 0 { out.append(SourceLine("ios.em.count.delivered", count: d)) }
        if a.counts.allowedSenders > 0 { out.append(SourceLine("ios.em.count.allowed", count: a.counts.allowedSenders)) }
        return out
    }

    /// "Members of Oma's frame can send from the email address of their account" (they are never listed, spec §4.4).
    public static func membersNote(_ a: EmailAddress) -> SourceLine {
        SourceLine(a.isFeedTarget ? "ios.em.membersNoteFeed" : "ios.em.membersNote", ["name": a.targetName])
    }

    /// "Rita (rita@gmail.com) wants to send 3 photos" for a pending sender.
    public static func pending(_ p: EmailPendingSender) -> SourceLine {
        SourceLine("ios.em.pending.wants", ["name": p.label], count: p.mediaCount)
    }

    /// "Deleted in 3 days unless you decide" (held mail goes after 14 days).
    public static func expiry(_ date: Date, now: Date = Date()) -> SourceLine {
        let days = max(0, Int((date.timeIntervalSince(now) / 86_400).rounded(.up)))
        if days <= 1 { return SourceLine("ios.em.held.expiresToday", tone: .attention) }
        return SourceLine("ios.em.held.expires", tone: days <= 3 ? .attention : .muted, count: days)
    }

    /// "We couldn't verify that this really comes from rita@gmail.com" (unverified held mail).
    public static func unverified(_ email: String) -> SourceLine {
        SourceLine("ios.em.held.unverified", ["email": email], tone: .attention)
    }

    /// What became of a mail, with the sender (spec §13): "Jan · 4 photos delivered", "Rita · waiting for your
    /// approval", "Jan · no photos found", …
    public static func outcome(_ m: EmailMessage) -> SourceLine {
        let name = shortName(m.senderName, m.senderEmail)
        let items = m.items ?? 0
        switch m.knownOutcome {
        case .delivered: return SourceLine("ios.em.outcome.delivered", ["name": name], tone: .ok, count: items)
        case .held: return SourceLine("ios.em.outcome.held", ["name": name], tone: .attention)
        case .noMedia: return SourceLine("ios.em.outcome.no_media", ["name": name], tone: .error)
        case .tooLarge: return SourceLine("ios.em.outcome.too_large", ["name": name], tone: .error)
        case .encrypted: return SourceLine("ios.em.outcome.encrypted", ["name": name], tone: .error)
        case .duplicate: return SourceLine("ios.em.outcome.duplicate", ["name": name])
        case .blocked: return SourceLine("ios.em.outcome.blocked")
        case .rejectedDmarc:
            let domain = m.senderEmail.flatMap { $0.split(separator: "@").last.map(String.init) } ?? "?"
            return SourceLine("ios.em.outcome.rejected_dmarc", ["domain": domain], tone: .error)
        case .ignored: return SourceLine("ios.em.outcome.ignored")
        case .rateLimited: return SourceLine("ios.em.outcome.rate_limited", tone: .error)
        case .failed: return SourceLine("ios.em.outcome.failed", tone: .error)
        case nil: return SourceLine("ios.em.outcome.unknown", ["name": name])
        }
    }

    /// Files a mail left out: "2 too small", "1 video too long", … (`EmailSkipCounts`).
    public static func skipped(_ s: Components.Schemas.EmailSkipCounts?) -> [SourceLine] {
        guard let s else { return [] }
        let parts: [(String, Int?)] = [("too_small", s.tooSmall), ("duplicate", s.duplicate), ("unsupported", s.unsupported),
                                       ("too_large", s.tooLarge), ("too_long", s.tooLong), ("over_limit", s.overLimit)]
        return parts.compactMap { key, n in
            guard let n, n > 0 else { return nil }
            return SourceLine("ios.em.skip.\(key)", count: n)
        }
    }

    /// The reply the sender got, if any: "Reply: delivered".
    public static func reply(_ m: EmailMessage) -> SourceLine? {
        guard let raw = m.reply else { return nil }
        guard let r = InboxReply(rawValue: raw) else { return SourceLine("ios.em.reply.unknown") }
        return SourceLine("ios.em.reply.\(r.rawValue)")
    }

    /// The problem behind a failed call, for the problems these screens expect.
    public static func problem(_ error: Error, limits: Limits = .defaults) -> String? {
        guard let e = APIError.wrap(error) as? APIError else { return nil }
        switch e.code {
        case "duplicate_sender": return L("ios.em.err.duplicateSender")
        case "already_decided": return L("ios.em.err.alreadyDecided")
        case "limit_reached": return L("ios.em.err.limit", ["count": "\(limits.maxEmailAddressesPerTarget)"])
        case "validation_failed": return L("ios.em.err.invalid")
        case "rate_limited": return L("ios.em.err.rateLimited")
        case "feature_disabled": return L("ios.em.err.disabled")
        default: return nil
        }
    }
}

// MARK: Request bodies

public struct EmailAddressDraft: Equatable, Sendable {
    public var slug: String
    public var name: String
    public var anyoneMaySend = false
    public var sendConfirmations = true
    public var offerAppInvite = true
    public var notifyReactions = true

    /// Prefilled from the frame's or feed's name.
    public init(targetName: String) {
        slug = EmailSlug.suggest(from: targetName)
        name = targetName
    }

    public static let nameMax = 60

    public var problem: SourceLine? {
        if !EmailSlug.isValid(EmailSlug.clean(slug)) { return SourceLine("ios.em.err.slug", tone: .error) }
        if name.trimmingCharacters(in: .whitespacesAndNewlines).count > Self.nameMax { return SourceLine("ios.em.err.nameLong", tone: .error) }
        return nil
    }

    public var body: Components.Schemas.EmailAddressCreate {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return .init(slug: EmailSlug.clean(slug), name: n.isEmpty ? nil : String(n.prefix(Self.nameMax)), anyoneMaySend: anyoneMaySend,
                     sendConfirmations: sendConfirmations, offerAppInvite: offerAppInvite, notifyReactions: notifyReactions)
    }
}

/// One change to an address's settings (`EmailAddressUpdate`, omitted fields are kept).
public enum EmailAddressChange: Equatable, Sendable {
    case name(String)
    case slug(String)
    case paused(Bool)
    case anyoneMaySend(Bool)
    case sendConfirmations(Bool)
    case offerAppInvite(Bool)
    case notifyReactions(Bool)
    case takeOver

    public var body: Components.Schemas.EmailAddressUpdate {
        var b = Components.Schemas.EmailAddressUpdate()
        switch self {
        case .name(let n): b.name = n.trimmingCharacters(in: .whitespacesAndNewlines)
        case .slug(let s): b.slug = EmailSlug.clean(s)
        case .paused(let p): b.paused = p
        case .anyoneMaySend(let v): b.anyoneMaySend = v
        case .sendConfirmations(let v): b.sendConfirmations = v
        case .offerAppInvite(let v): b.offerAppInvite = v
        case .notifyReactions(let v): b.notifyReactions = v
        case .takeOver: b.takeOver = true
        }
        return b
    }
}

public enum EmailSenderInput {
    /// A plausible address: something@domain.tld, no spaces, at most 254 characters.
    public static func isValid(_ email: String) -> Bool {
        let e = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard e.count <= 254, !e.contains(where: \.isWhitespace) else { return false }
        let parts = e.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, parts[1].contains("."), !parts[1].hasPrefix("."), !parts[1].hasSuffix(".") else { return false }
        return true
    }

    public static func body(email: String, name: String, blocked: Bool = false) -> Components.Schemas.EmailSenderCreate {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return .init(email: email.trimmingCharacters(in: .whitespacesAndNewlines), displayName: n.isEmpty ? nil : String(n.prefix(40)),
                     blocked: blocked ? true : nil)
    }
}
