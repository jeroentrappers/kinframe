import Foundation

// Photo sources and Google Photos imports (docs/spec/photo-sources.md, docs/api/README.md §5.14): short names for the
// generated schemas and the pure rules the screens share: the open enums, link parsing and validation, defaults,
// status and error texts with the fix for each error, credits ("Anna via Immich"), the Google import flow, push
// routes. Unit-tested in PhotoSourceTests.
//
// The enums below arrive as plain strings (tools/openapi specprep opens them): a newer server may add a provider,
// status, warning or error code, and that must never make a list of sources or an item's `via` fail to decode. Each
// has a Swift enum here whose `init?(rawValue:)` is nil for values this app doesn't know; screens show a neutral
// fallback for those.

public typealias PhotoSource = Components.Schemas.PhotoSource
public typealias PhotoSourceTarget = Components.Schemas.PhotoSourceTarget
public typealias PhotoSourceCounts = Components.Schemas.PhotoSourceCounts
public typealias PhotoSourceRun = Components.Schemas.PhotoSourceRun
public typealias PhotoSourceTestResult = Components.Schemas.PhotoSourceTestResult
public typealias ImmichAlbum = Components.Schemas.ImmichAlbum
public typealias ImmichAlbumList = Components.Schemas.ImmichAlbumList
public typealias GoogleImport = Components.Schemas.GoogleImport
public typealias ItemVia = Components.Schemas.ItemVia

extension Components.Schemas.PhotoSource: Identifiable {}
extension Components.Schemas.GoogleImport: Identifiable {}
extension Components.Schemas.ImmichAlbum: Identifiable {}

// MARK: Open enums

public enum SourceProvider: String, CaseIterable, Sendable {
    case immichLink = "immich_link"
    case immichApi = "immich_api"
    case icloudLink = "icloud_link"

    /// The service the photos come from, as `ItemVia.service`.
    public var service: PhotoService {
        switch self {
        case .immichLink, .immichApi: .immich
        case .icloudLink: .icloud
        }
    }

    /// iCloud has no official API (spec §6.5).
    public var bestEffort: Bool { self == .icloudLink }
}

public enum SourceStatus: String, CaseIterable, Sendable {
    case active
    case paused
    case error
    case needsAttention = "needs_attention"
}

public enum SourceErrorCode: String, CaseIterable, Sendable {
    case unreachable
    case tlsError = "tls_error"
    case privateAddressBlocked = "private_address_blocked"
    case authFailed = "auth_failed"
    case passwordRequired = "password_required"
    case permissionMissing = "permission_missing"
    case linkNotFound = "link_not_found"
    case linkExpired = "link_expired"
    case albumNotFound = "album_not_found"
    case unsupportedLink = "unsupported_link"
    case tooLarge = "too_large"
    case formatChanged = "format_changed"
    case rateLimitedUpstream = "rate_limited_upstream"
    case upstreamError = "upstream_error"
    case ownerLostAccess = "owner_lost_access"
    case credentialsUnreadable = "credentials_unreadable"
    case massRemoval = "mass_removal"
}

public enum SourceWarning: String, CaseIterable, Sendable {
    case downloadsDisabled = "downloads_disabled"
    case metadataHidden = "metadata_hidden"
    case itemLimitReached = "item_limit_reached"
}

public enum SourceHistory: String, CaseIterable, Sendable {
    case none
    case lastN = "last_n"
    case all
}

public enum SourceDeletePolicy: String, CaseIterable, Sendable {
    case mirror
    case keep
}

public enum PhotoService: String, CaseIterable, Sendable {
    case immich
    case icloud
    case googlePhotos = "google_photos"
    /// Mailed to an inbox address (docs/spec/email-inbox.md §9).
    case email
    /// Posted in a connected Telegram chat (docs/spec/telegram.md §8).
    case telegram

    /// The name to show; not translated (`ItemVia.label` is the same for the photo services).
    public var label: String {
        switch self {
        case .immich: "Immich"
        case .icloud: "iCloud"
        case .googlePhotos: "Google Photos"
        case .email: "email"
        case .telegram: "Telegram"
        }
    }

    /// Email and Telegram: `ItemVia.label` is the person who mailed or posted it, shown **instead of** the sender
    /// (who may be the address's or chat's owner sending on their behalf): "Jan via email".
    public var labelIsPerson: Bool { self == .email || self == .telegram }
}

public enum GoogleImportState: String, CaseIterable, Sendable {
    case needsAuth = "needs_auth"
    case picking
    case importing
    case done
    case failed
    case cancelled
    case expired

    public var isFinal: Bool {
        switch self {
        case .done, .failed, .cancelled, .expired: true
        case .needsAuth, .picking, .importing: false
        }
    }
}

public enum GoogleImportErrorCode: String, CaseIterable, Sendable {
    case consentDenied = "consent_denied"
    case authFailed = "auth_failed"
    case pickerExpired = "picker_expired"
    case googleUnavailable = "google_unavailable"
    case targetGone = "target_gone"
    case downloadFailed = "download_failed"
    case serverError = "server_error"
}

public extension Components.Schemas.PhotoSource {
    var kind: SourceProvider? { SourceProvider(rawValue: provider) }
    var state: SourceStatus? { SourceStatus(rawValue: status) }
    var errorCode: SourceErrorCode? { lastErrorCode.flatMap(SourceErrorCode.init(rawValue:)) }
    var knownWarnings: [SourceWarning] { (warnings ?? []).compactMap(SourceWarning.init(rawValue:)) }
    var historyKind: SourceHistory? { SourceHistory(rawValue: history) }
    var deletePolicy: SourceDeletePolicy? { SourceDeletePolicy(rawValue: onUpstreamDelete) }
    var isBestEffort: Bool { bestEffort ?? (kind?.bestEffort ?? false) }
    var isPaused: Bool { state == .paused }
    var isFeedTarget: Bool { target.kind == .feed }
    /// The service name for "Synced from Immich".
    var serviceLabel: String { kind?.service.label ?? provider }
}

public extension Components.Schemas.GoogleImport {
    var state: GoogleImportState? { GoogleImportState(rawValue: status) }
    var errorKind: GoogleImportErrorCode? { errorCode.flatMap(GoogleImportErrorCode.init(rawValue:)) }
}

public extension Components.Schemas.ItemVia {
    var knownService: PhotoService? { PhotoService(rawValue: service) }
    /// `label` from the server ("Immich"), else the known service's name, else the raw value.
    var displayLabel: String {
        let l = label.trimmingCharacters(in: .whitespaces)
        if !l.isEmpty { return l }
        return knownService?.label ?? service
    }
}

// MARK: Links

/// What someone pasted or typed into the connect sheet (spec §6.3–6.5).
public enum SourceLink: Equatable, Sendable {
    /// `https://<host>[/<prefix>]/share/<key>` or `/s/<slug>`: an Immich shared link.
    case immichShare(URL)
    /// `https://www.icloud.com/sharedalbum/#<token>` (also without www, with a language segment).
    case icloudSharedAlbum(URL)
    /// `https://www.icloud.com/photos/#…` ("Copy iCloud Link"): a snapshot of a few photos, not supported.
    case icloudPhotosLink
    /// Any other web address: possibly an Immich server for the API-key connection.
    case server(URL)

    /// The first web address in pasted text ("Look at our album: https://…"), trimmed of trailing punctuation.
    public static func extract(from text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let words = trimmed.split(whereSeparator: { $0.isWhitespace || $0 == "<" || $0 == ">" || $0 == "\"" })
        for w in words {
            var s = String(w)
            while let last = s.last, ").,;!?»”'".contains(last) { s.removeLast() }
            if s.lowercased().hasPrefix("https://") || s.lowercased().hasPrefix("http://") { return s }
        }
        // A bare host like "photos.example.org/share/abc".
        if words.count == 1, trimmed.contains("."), !trimmed.contains("@") { return trimmed }
        return nil
    }

    public static func parse(_ input: String) -> SourceLink? {
        guard var s = extract(from: input) else { return nil }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://" + s }
        guard let url = URL(string: s), let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = c.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = c.host?.lowercased(), !host.isEmpty else { return nil }
        let parts = c.path.split(separator: "/").map(String.init)
        if host == "icloud.com" || host == "www.icloud.com" {
            if parts.first?.lowercased() == "sharedalbum" {
                guard let token = c.fragment, !token.isEmpty, token.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == ";" || $0 == "-" || $0 == "_") })
                else { return nil }
                return .icloudSharedAlbum(url)
            }
            if parts.first?.lowercased() == "photos" || parts.first?.lowercased() == "iclouddrive" { return .icloudPhotosLink }
            return nil
        }
        // Immich: …/share/<key> or …/s/<slug>, possibly under a path prefix.
        if parts.count >= 2, ["share", "s"].contains(parts[parts.count - 2].lowercased()), !parts[parts.count - 1].isEmpty {
            return .immichShare(url)
        }
        return .server(url)
    }

    /// The host, for hints ("Can't reach photos.peeters.be").
    public var host: String? {
        switch self {
        case .immichShare(let u), .icloudSharedAlbum(let u), .server(let u): u.host()?.lowercased()
        case .icloudPhotosLink: "www.icloud.com"
        }
    }
}

/// Why a typed connection can't be tried yet; screens show the key's text under the field.
public enum SourceInputProblem: Equatable, Sendable {
    case empty
    case notALink
    /// An iCloud link was pasted for Immich, or the other way round: offer to switch.
    case wrongProvider(SourceProvider)
    /// "Copy iCloud Link" (a few photos) instead of a shared album with Public Website.
    case icloudPhotosLink
    /// An Immich server address where a shared link was expected (the link has /share/ in it).
    case notAShareLink
    /// Plain http:// is only accepted for a home-network address.
    case httpNeedsHomeNetwork
    /// A home-network address on a server that may not reach them (`photo_sources_private_networks: false`).
    case privateAddressBlocked

    public var key: String {
        switch self {
        case .empty: "ios.ps.input.empty"
        case .notALink: "ios.ps.input.notALink"
        case .wrongProvider(let p): p == .icloudLink ? "ios.ps.input.isIcloud" : "ios.ps.input.isImmich"
        case .icloudPhotosLink: "ios.ps.err.unsupported_link"
        case .notAShareLink: "ios.ps.input.notAShareLink"
        case .httpNeedsHomeNetwork: "ios.ps.input.httpPublic"
        case .privateAddressBlocked: "ios.ps.err.private_address_blocked"
        }
    }
}

public enum SourceInput {
    /// Checks a link for `immich_link` or `icloud_link`, or a server address for `immich_api`, before the server
    /// tests it. `privateNetworks` is discovery's `photo_sources_private_networks`.
    public static func problem(_ input: String, provider: SourceProvider, privateNetworks: Bool) -> SourceInputProblem? {
        if input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .empty }
        guard let link = SourceLink.parse(input) else { return .notALink }
        switch (provider, link) {
        case (.immichLink, .immichShare(let url)):
            return networkProblem(url, privateNetworks: privateNetworks)
        case (.immichLink, .server):
            return .notAShareLink
        case (.immichLink, .icloudSharedAlbum), (.immichApi, .icloudSharedAlbum):
            return .wrongProvider(.icloudLink)
        case (.icloudLink, .icloudSharedAlbum(let url)):
            return url.scheme?.lowercased() == "https" ? nil : .notALink
        case (.icloudLink, .immichShare):
            return .wrongProvider(.immichLink)
        case (.icloudLink, .server):
            return .notALink
        case (_, .icloudPhotosLink):
            return .icloudPhotosLink
        case (.immichApi, .immichShare):
            return .wrongProvider(.immichLink)
        case (.immichApi, .server(let url)):
            return networkProblem(url, privateNetworks: privateNetworks)
        }
    }

    private static func networkProblem(_ url: URL, privateNetworks: Bool) -> SourceInputProblem? {
        let home = HomeNetwork.isPrivate(host: url.host() ?? "")
        if home, !privateNetworks { return .privateAddressBlocked }
        if url.scheme?.lowercased() == "http", !home { return .httpNeedsHomeNetwork }
        return nil
    }

    /// What is sent as `url` (links): the extracted link, with https:// added when it was left out.
    public static func cleanLink(_ input: String) -> String {
        guard var s = SourceLink.extract(from: input) else { return input.trimmingCharacters(in: .whitespacesAndNewlines) }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://" + s }
        return s
    }

    /// What is sent as `server_url`: https:// added when missing, no query or fragment, no trailing slash (an /api
    /// suffix is tolerated by the server and kept).
    public static func cleanServer(_ input: String) -> String {
        let s = cleanLink(input)
        guard var c = URLComponents(string: s) else { return s }
        c.query = nil
        c.fragment = nil
        c.user = nil
        c.password = nil
        var out = c.string ?? s
        while out.hasSuffix("/") { out.removeLast() }
        return out
    }
}

/// Addresses that only work on a home network: what `photo_sources_private_networks` is about (spec §12.3).
public enum HomeNetwork {
    public static func isPrivate(host raw: String) -> Bool {
        var host = raw.lowercased()
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        if host.isEmpty { return false }
        if host == "localhost" || host.hasSuffix(".local") || host.hasSuffix(".lan") || host.hasSuffix(".home.arpa")
            || host.hasSuffix(".internal") || host.hasSuffix(".home") || host.hasSuffix(".ts.net") {
            return true
        }
        if host.contains(":") {
            // IPv6: loopback, unique local (fc00::/7), link-local (fe80::/10).
            return host == "::1" || host.hasPrefix("fc") || host.hasPrefix("fd") || host.hasPrefix("fe8") || host.hasPrefix("fe9")
                || host.hasPrefix("fea") || host.hasPrefix("feb")
        }
        let octets = host.split(separator: ".").compactMap { Int($0) }
        if octets.count == 4, host.split(separator: ".").count == 4, octets.allSatisfy({ (0...255).contains($0) }) {
            switch (octets[0], octets[1]) {
            case (10, _), (127, _), (192, 168), (169, 254): return true
            case (172, 16...31): return true
            case (100, 64...127): return true   // CGNAT, used by Tailscale
            default: return false
            }
        }
        // A single label ("immich", "nas") only resolves on a home network.
        return !host.contains(".")
    }
}

// MARK: Defaults and the connect form

public enum SourceTargetRef: Equatable, Hashable, Sendable {
    case album(frameId: String, albumId: String)
    case feed(String)

    public var isFeed: Bool {
        if case .feed = self { return true }
        return false
    }
}

/// The first import (spec §2 `history`): everything, the newest N, or only what's added from now on.
public enum SourceHistoryChoice: Equatable, Hashable, Sendable {
    case all
    case lastN(Int)
    case none

    public static let countRange = 1...10_000
    public static let presets = [20, 50, 100, 500]

    public var history: SourceHistory {
        switch self {
        case .all: .all
        case .lastN: .lastN
        case .none: .none
        }
    }

    public var count: Int? {
        if case .lastN(let n) = self { return min(max(n, Self.countRange.lowerBound), Self.countRange.upperBound) }
        return nil
    }
}

public enum SourceDefaults {
    /// Albums: import everything, mirror removals. Feeds: the last 20, keep what was removed upstream.
    public static func history(for target: SourceTargetRef) -> SourceHistoryChoice {
        target.isFeed ? .lastN(20) : .all
    }

    public static func deletePolicy(for target: SourceTargetRef) -> SourceDeletePolicy {
        target.isFeed ? .keep : .mirror
    }

    /// Which providers the connect sheet offers: discovery's list, in the recommended order. Old servers that sent
    /// no list but have `photo_sources` get the two Immich ones.
    public static func providers(_ discovery: [String], enabled: Bool) -> [SourceProvider] {
        guard enabled else { return [] }
        let listed = Set(discovery.compactMap(SourceProvider.init(rawValue:)))
        let base: Set<SourceProvider> = discovery.isEmpty ? [.immichLink, .immichApi] : listed
        return SourceProvider.allCases.filter(base.contains)
    }
}

/// One connection as the connect sheet holds it.
public struct SourceConnection: Equatable, Sendable {
    public var provider: SourceProvider
    public var link: String
    public var password: String
    public var serverURL: String
    public var apiKey: String
    public var immichAlbumId: String?

    public init(provider: SourceProvider, link: String = "", password: String = "", serverURL: String = "", apiKey: String = "",
                immichAlbumId: String? = nil) {
        self.provider = provider
        self.link = link
        self.password = password
        self.serverURL = serverURL
        self.apiKey = apiKey
        self.immichAlbumId = immichAlbumId
    }

    public static let apiKeyMin = 8

    /// What the request carries: links need `url` (and a password when given), Immich API needs `server_url`,
    /// `api_key` and (to create) the album.
    public var body: Components.Schemas.PhotoSourceConnection {
        switch provider {
        case .immichLink, .icloudLink:
            let pw = password.trimmingCharacters(in: .whitespacesAndNewlines)
            return .init(provider: provider.rawValue, url: SourceInput.cleanLink(link),
                         password: provider == .immichLink && !pw.isEmpty ? String(pw.prefix(200)) : nil)
        case .immichApi:
            return .init(provider: provider.rawValue, serverUrl: SourceInput.cleanServer(serverURL),
                         apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines), immichAlbumId: immichAlbumId)
        }
    }

    /// Ready to test: the link or server checks out locally and (API) a key of the minimum length is there.
    public func problem(privateNetworks: Bool) -> SourceInputProblem? {
        switch provider {
        case .immichLink, .icloudLink:
            return SourceInput.problem(link, provider: provider, privateNetworks: privateNetworks)
        case .immichApi:
            if let p = SourceInput.problem(serverURL, provider: .immichApi, privateNetworks: privateNetworks) { return p }
            return apiKey.trimmingCharacters(in: .whitespacesAndNewlines).count < Self.apiKeyMin ? .empty : nil
        }
    }

    /// The host the hints and error texts name.
    public var host: String? {
        SourceLink.parse(provider == .immichApi ? serverURL : link)?.host
    }
}

/// Everything the connect sheet sends to create a source.
public struct SourceDraft: Equatable, Sendable {
    public var connection: SourceConnection
    public var target: SourceTargetRef
    public var name: String
    public var history: SourceHistoryChoice
    public var deletePolicy: SourceDeletePolicy
    public var includeVideos: Bool
    public var notifyReactions: Bool

    public init(connection: SourceConnection, target: SourceTargetRef) {
        self.connection = connection
        self.target = target
        name = ""
        history = SourceDefaults.history(for: target)
        deletePolicy = SourceDefaults.deletePolicy(for: target)
        includeVideos = true
        notifyReactions = true
    }

    public static let nameMax = 60

    public var createBody: Components.Schemas.PhotoSourceCreate {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        var albumId: String?, feedId: String?
        switch target {
        case .album(_, let a): albumId = a
        case .feed(let f): feedId = f
        }
        return .init(value1: connection.body,
                     value2: .init(albumId: albumId, feedId: feedId, name: trimmed.isEmpty ? nil : String(trimmed.prefix(Self.nameMax)),
                                   history: history.history.rawValue, historyCount: history.count,
                                   onUpstreamDelete: deletePolicy.rawValue, includeVideos: includeVideos, notifyReactions: notifyReactions))
    }
}

/// The edit sheet's changes as a JSON merge body for `PATCH /v1/photo-sources/{id}` (raw JSON: removing a link
/// password is an explicit null).
public enum SourcePatch {
    public static func body(_ source: PhotoSource, name: String, deletePolicy: SourceDeletePolicy, includeVideos: Bool,
                            notifyReactions: Bool, newLink: String, newPassword: String?, removePassword: Bool,
                            newServerURL: String, newApiKey: String, newAlbumId: String?, takeOver: Bool = false) -> [String: JSONValue] {
        var out: [String: JSONValue] = [:]
        let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(SourceDraft.nameMax))
        if !n.isEmpty, n != source.name { out["name"] = .string(n) }
        if deletePolicy.rawValue != source.onUpstreamDelete { out["on_upstream_delete"] = .string(deletePolicy.rawValue) }
        if includeVideos != source.includeVideos { out["include_videos"] = .bool(includeVideos) }
        // Only the owner may change it (spec §10).
        if source.isOwner == true, notifyReactions != (source.notifyReactions ?? true) { out["notify_reactions"] = .bool(notifyReactions) }
        let link = newLink.trimmingCharacters(in: .whitespacesAndNewlines)
        if !link.isEmpty { out["url"] = .string(SourceInput.cleanLink(link)) }
        if removePassword {
            out["password"] = .null
        } else if let pw = newPassword?.trimmingCharacters(in: .whitespacesAndNewlines), !pw.isEmpty {
            out["password"] = .string(String(pw.prefix(200)))
        }
        let server = newServerURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if !server.isEmpty, SourceInput.cleanServer(server) != source.serverUrl { out["server_url"] = .string(SourceInput.cleanServer(server)) }
        let key = newApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !key.isEmpty { out["api_key"] = .string(key) }
        if let a = newAlbumId, a != source.immichAlbumId { out["immich_album_id"] = .string(a) }
        if takeOver { out["take_over"] = .bool(true) }
        return out
    }
}

// MARK: Status texts

/// A localized line as a key and its arguments, so the rules are testable without the string catalog.
public struct SourceLine: Equatable, Sendable {
    public enum Tone: Sendable { case ok, muted, error, attention, paused }

    public var key: String
    public var args: [String: String]
    public var tone: Tone
    /// Set for plural texts: `text` looks up `key.one` / `key.other` with `{count}`.
    public var count: Int?

    public init(_ key: String, _ args: [String: String] = [:], tone: Tone = .muted, count: Int? = nil) {
        self.key = key
        self.args = args
        self.tone = tone
        self.count = count
    }

    public var text: String { count.map { Lp(key, count: $0, args) } ?? L(key, args) }
}

/// What a person can do about an error (the fix shown as a button, spec §5.1 "Amber, with the fix as a button").
public enum SourceFix: Equatable, Sendable {
    case replaceLink
    case enterPassword
    case replaceKey
    case pickAlbum
    case takeOver
    case confirmRemovals
    /// Nothing to do but wait (we keep trying) or sync now.
    case syncNow
    /// Nothing helps but stopping the sync.
    case none
}

public enum SourceStatusText {
    /// The host to name in error texts: from `server_url` or `url_hint`.
    public static func host(_ s: PhotoSource) -> String {
        for raw in [s.serverUrl, s.urlHint].compactMap({ $0 }) {
            if let h = URLComponents(string: raw.replacingOccurrences(of: "…", with: ""))?.host, !h.isEmpty { return h }
        }
        return s.serviceLabel
    }

    /// The first line: synced, syncing, importing, paused, or the error.
    public static func headline(_ s: PhotoSource, now: Date = Date(), locale: Locale = L10n.locale) -> SourceLine {
        guard let state = s.state else { return SourceLine("ios.ps.status.unknown") }
        if state == .paused { return SourceLine("ios.ps.status.paused", tone: .paused) }
        if state == .needsAttention || state == .error {
            return error(s, locale: locale) ?? SourceLine(state == .error ? "ios.ps.status.errorGeneric" : "ios.ps.status.attentionGeneric",
                                                          tone: state == .error ? .error : .attention)
        }
        if s.running {
            let pending = s.counts.pending
            if pending > 0 {
                let done = s.counts.imported
                return SourceLine("ios.ps.status.importing", ["done": "\(done)", "total": "\(done + pending)"], tone: .ok)
            }
            return SourceLine("ios.ps.status.syncing", tone: .ok)
        }
        if let synced = s.lastSyncedAt {
            return SourceLine("ios.ps.status.synced", ["service": s.serviceLabel, "when": Format.relative(synced, locale: locale, now: now)], tone: .ok)
        }
        return SourceLine("ios.ps.status.firstSync", tone: .muted)
    }

    /// The error text for `last_error_code` with its details (host, owner, counts), or nil without one.
    public static func error(_ s: PhotoSource, locale: Locale = L10n.locale) -> SourceLine? {
        guard let raw = s.lastErrorCode else { return nil }
        let tone: SourceLine.Tone = s.state == .error ? .error : (s.state == .active ? .muted : .attention)
        guard let code = SourceErrorCode(rawValue: raw) else { return SourceLine("ios.ps.err.unknown", tone: tone) }
        var args = ["host": host(s), "service": s.serviceLabel]
        switch code {
        case .authFailed:
            return SourceLine(s.kind == .immichApi ? "ios.ps.err.auth_failed.key" : "ios.ps.err.auth_failed.password", args, tone: tone)
        case .ownerLostAccess:
            args["name"] = s.owner?.displayName ?? L("ios.ps.formerMember")
            return SourceLine(s.isFeedTarget ? "ios.ps.err.owner_lost_access.feed" : "ios.ps.err.owner_lost_access.album", args, tone: tone)
        case .massRemoval:
            args["count"] = "\(s.pendingRemovals ?? 0)"
            return SourceLine("ios.ps.err.mass_removal", args, tone: tone)
        case .unreachable, .upstreamError:
            if s.state == .error, let since = s.errorSince {
                args["since"] = Format.dateTime(since, locale: locale)
                return SourceLine("ios.ps.err.\(code.rawValue).since", args, tone: tone)
            }
            return SourceLine("ios.ps.err.\(code.rawValue)", args, tone: tone)
        case .albumNotFound, .linkNotFound:
            return SourceLine(s.kind == .icloudLink ? "ios.ps.err.\(code.rawValue).icloud" : "ios.ps.err.\(code.rawValue)", args, tone: tone)
        default:
            return SourceLine("ios.ps.err.\(code.rawValue)", args, tone: tone)
        }
    }

    /// The test and create errors (`PhotoSourceTestResult.error_code`, the `detail` of `422 source_test_failed`).
    public static func testError(_ raw: String?, provider: SourceProvider, host: String?) -> SourceLine {
        let args = ["host": host ?? provider.service.label, "service": provider.service.label]
        guard let raw, let code = SourceErrorCode(rawValue: raw) else { return SourceLine("ios.ps.err.unknown", args, tone: .error) }
        switch code {
        case .authFailed: return SourceLine(provider == .immichApi ? "ios.ps.err.auth_failed.key" : "ios.ps.err.auth_failed.password", args, tone: .error)
        case .linkNotFound, .albumNotFound:
            return SourceLine(provider == .icloudLink ? "ios.ps.err.\(code.rawValue).icloud" : "ios.ps.err.\(code.rawValue)", args, tone: .error)
        case .unreachable, .upstreamError: return SourceLine("ios.ps.err.\(code.rawValue)", args, tone: .error)
        default: return SourceLine("ios.ps.err.\(code.rawValue)", args, tone: .error)
        }
    }

    /// The fix for the current error.
    public static func fix(_ s: PhotoSource) -> SourceFix {
        guard let code = s.errorCode else { return s.lastErrorCode == nil ? .none : .syncNow }
        let api = s.kind == .immichApi
        switch code {
        case .unreachable, .rateLimitedUpstream, .upstreamError: return .syncNow
        case .tlsError, .privateAddressBlocked, .linkNotFound, .linkExpired, .unsupportedLink: return .replaceLink
        case .authFailed: return api ? .replaceKey : .enterPassword
        case .passwordRequired: return .enterPassword
        case .permissionMissing: return .replaceKey
        case .albumNotFound: return api ? .pickAlbum : .replaceLink
        case .credentialsUnreadable: return api ? .replaceKey : .replaceLink
        case .ownerLostAccess: return .takeOver
        case .massRemoval: return .confirmRemovals
        case .tooLarge, .formatChanged: return .none
        }
    }

    public static func fixKey(_ fix: SourceFix) -> String? {
        switch fix {
        case .replaceLink: "ios.ps.fix.replaceLink"
        case .enterPassword: "ios.ps.fix.enterPassword"
        case .replaceKey: "ios.ps.fix.replaceKey"
        case .pickAlbum: "ios.ps.fix.pickAlbum"
        case .takeOver: "ios.ps.fix.takeOver"
        case .confirmRemovals: "ios.ps.fix.confirmRemovals"
        case .syncNow: "ios.ps.syncNow"
        case .none: nil
        }
    }

    /// "Next sync in 28 minutes"; nil while paused or waiting for a person.
    public static func next(_ s: PhotoSource, now: Date = Date(), locale: Locale = L10n.locale) -> SourceLine? {
        guard s.state != .paused, s.state != .needsAttention, !s.running, let next = s.nextSyncAt else { return nil }
        if next.timeIntervalSince(now) < 60 { return SourceLine("ios.ps.status.nextSoon") }
        return SourceLine("ios.ps.status.next", ["when": Format.relative(next, locale: locale, now: now)])
    }

    /// The counts line: "220 photos · 3 skipped · 2 couldn't be copied · 14 waiting".
    public static func counts(_ s: PhotoSource) -> [SourceLine] {
        var out = [SourceLine("ios.ps.count.imported", ["count": "\(s.counts.imported)"])]
        if let u = s.counts.upstream, u > 0 { out.append(SourceLine("ios.ps.count.upstream", ["count": "\(u)", "service": s.serviceLabel])) }
        if s.counts.pending > 0 { out.append(SourceLine("ios.ps.count.pending", ["count": "\(s.counts.pending)"])) }
        if let d = s.counts.duplicates, d > 0 { out.append(SourceLine("ios.ps.count.duplicates", ["count": "\(d)"])) }
        if let k = s.counts.skipped, k > 0 { out.append(SourceLine("ios.ps.count.skipped", ["count": "\(k)"])) }
        if let f = s.counts.failed, f > 0 { out.append(SourceLine("ios.ps.count.failed", ["count": "\(f)"])) }
        return out
    }

    /// The warnings, which never stop syncing. Unknown ones are left out.
    public static func warnings(_ s: PhotoSource, limits: Limits = .defaults) -> [SourceLine] {
        s.knownWarnings.map { w in
            switch w {
            case .itemLimitReached: SourceLine("ios.ps.warn.item_limit_reached", ["count": "\(limits.maxPhotoSourceItems)"])
            default: SourceLine("ios.ps.warn.\(w.rawValue)")
            }
        }
    }

    public static func warnings(_ raw: [String]?, limits: Limits = .defaults) -> [SourceLine] {
        (raw ?? []).compactMap(SourceWarning.init(rawValue:)).map { w in
            w == .itemLimitReached ? SourceLine("ios.ps.warn.item_limit_reached", ["count": "\(limits.maxPhotoSourceItems)"]) : SourceLine("ios.ps.warn.\(w.rawValue)")
        }
    }

    /// Where it syncs to, for the "My photo sources" list: "Garden · Oma's frame" or "Feed Peeters kids".
    public static func target(_ t: PhotoSourceTarget) -> SourceLine {
        switch t.kind {
        case .album: SourceLine("ios.ps.target.album", ["album": t.albumName ?? "?", "frame": t.frameName ?? "?"])
        case .feed: SourceLine("ios.ps.target.feed", ["feed": t.feedName ?? "?"])
        }
    }

    /// The test result's summary: "Garden 2026 · 214 photos, 6 videos · about 1.1 GB".
    public static func testSummary(_ r: PhotoSourceTestResult, locale: Locale = L10n.locale) -> String {
        var parts: [String] = []
        if let n = r.albumName, !n.isEmpty { parts.append(n) }
        var kinds: [String] = []
        if let p = r.photos { kinds.append(Lp("ios.ps.photos", count: p)) }
        if let v = r.videos, v > 0 { kinds.append(Lp("ios.ps.videos", count: v)) }
        if !kinds.isEmpty { parts.append(kinds.joined(separator: ", ")) }
        if let b = r.estimateBytes, b > 0 {
            parts.append(L("ios.ps.about", ["size": ByteCountFormatter.string(fromByteCount: Int64(b), countStyle: .file)]))
        }
        return parts.joined(separator: " · ")
    }
}

/// Problems from creating or changing a source, as a line for the sheet.
public enum SourceErrors {
    public static func line(_ error: Error, provider: SourceProvider, host: String?) -> SourceLine? {
        guard let e = error as? APIError else { return nil }
        switch e.code {
        case "source_test_failed": return SourceStatusText.testError(e.detail, provider: provider, host: host)
        case "duplicate_source": return SourceLine("ios.ps.err.duplicate", tone: .error)
        case "limit_reached": return SourceLine("ios.ps.err.limit", tone: .error)
        case "feature_disabled": return SourceLine("ios.ps.err.disabled", tone: .error)
        case "validation_failed":
            if e.fieldErrors.contains(where: { $0.field.hasSuffix("url") || $0.field.hasSuffix("server_url") }) {
                return SourceLine("ios.ps.err.private_address_blocked", tone: .error)
            }
            return nil
        default: return nil
        }
    }
}

// MARK: Credit

/// "Anna via Immich" (album, direct), "Anna · Peeters kids via Immich" (feed), "12 photos via Immich" (history).
/// The service name only, never the outside album's name or host (spec §9.5). Email and Telegram name the person
/// instead of the sender: "Jan via email", "Jan · Peeters kids via Telegram" (docs/spec/email-inbox.md §9.2,
/// docs/spec/telegram.md §2).
public enum Credit {
    /// `template` is "{name} via {service}" in the app's language (`ios.ps.via`); `email` and `telegram` are
    /// "{name} via email" / "{name} via Telegram" (`ios.via.email`, `ios.via.telegram`).
    public static func sender(_ name: String, via: ItemVia?, feed: String? = nil, template: String = L("ios.ps.via"),
                              email: String = L("ios.via.email"), telegram: String = L("ios.via.telegram")) -> String {
        if let via, let person = personTemplate(via, email: email, telegram: telegram) {
            let label = via.label.trimmingCharacters(in: .whitespacesAndNewlines)
            var who = label.isEmpty ? name : label
            if let feed { who += " · \(feed)" }
            return person.replacingOccurrences(of: "{name}", with: who)
        }
        let who = feed.map { "\(name) · \($0)" } ?? name
        guard let via else { return who }
        let t = template.contains("{name}") ? template : "{name} via {service}"
        return t.replacingOccurrences(of: "{name}", with: who).replacingOccurrences(of: "{service}", with: via.displayLabel)
    }

    /// A send in the history: "12 photos via Immich", and for mail or Telegram whom it came from: "12 photos via email
    /// from Jan" (`ios.via.emailFrom`, `ios.via.telegramFrom`; the owner sees sends made on Jan's behalf this way).
    public static func summary(_ summary: String, via: ItemVia?, template: String = L("ios.ps.via"),
                               emailFrom: String = L("ios.via.emailFrom"), telegramFrom: String = L("ios.via.telegramFrom")) -> String {
        guard let via else { return summary }
        guard let service = via.knownService, service.labelIsPerson else { return sender(summary, via: via, template: template) }
        let label = via.label.trimmingCharacters(in: .whitespacesAndNewlines)
        let raw = service == .email ? emailFrom : telegramFrom
        let t = raw.contains("{summary}") ? raw : (service == .email ? "{summary} via email from {name}" : "{summary} via Telegram from {name}")
        return t.replacingOccurrences(of: "{summary}", with: summary).replacingOccurrences(of: "{name}", with: label.isEmpty ? "?" : label)
    }

    /// An item's sender with its credit: "Anna via Immich", "Jan via email" (just "Anna" without `via`).
    public static func item(_ item: Item, template: String = L("ios.ps.via"), email: String = L("ios.via.email"),
                            telegram: String = L("ios.via.telegram")) -> String? {
        let personLabel = item.via.flatMap { $0.knownService?.labelIsPerson == true ? $0.label : nil }
        guard let name = item.senderName ?? personLabel else { return nil }
        return sender(name, via: item.via, template: template, email: email, telegram: telegram)
    }

    private static func personTemplate(_ via: ItemVia, email: String, telegram: String) -> String? {
        switch via.knownService {
        case .email: email.contains("{name}") ? email : "{name} via email"
        case .telegram: telegram.contains("{name}") ? telegram : "{name} via Telegram"
        default: nil
        }
    }
}

// MARK: Google Photos import

/// The client side of "Add from Google Photos" (spec §7.2, §7.3): consent in a browser session, Google's picker,
/// progress, the result. Pure state: the screen feeds it events and performs the effects it returns.
public struct GoogleImportFlow: Equatable, Sendable {
    public static let callbackScheme = "app.kinframe"
    public static let returnURI = "app.kinframe:/google-import"
    /// Poll this often while picking or importing (the server says every 2–5 s).
    public static let pollSeconds: Double = 3

    public enum Phase: Equatable, Sendable {
        case idle
        case starting
        /// Waiting for Google's consent in the browser session.
        case consent(URL)
        /// The picker is open (or about to be): waiting for the person to tap Done in Google Photos.
        case picking(URL?)
        case importing(picked: Int, done: Int)
        case finished(Outcome)
    }

    public enum Outcome: Equatable, Sendable {
        case done(imported: Int, duplicates: Int, skipped: Int, failed: Int)
        case failed(String?)
        case expired
        case cancelled
    }

    public enum Event: Equatable, Sendable {
        case start
        case startFailed
        /// From the start response, a poll, the websocket or `GET /v1/google-imports` after a relaunch.
        case update(GoogleImport)
        /// The browser session ended with `app.kinframe:/google-import?import_id=…&status=…`.
        case consentReturned(importId: String?, status: String?)
        /// The person closed the browser session without finishing.
        case consentDismissed
        case pickerOpened
        case cancel
        case appActive
    }

    public enum Effect: Equatable, Sendable {
        case post
        case openConsent(URL)
        case openPicker(URL)
        case refresh
        case cancelOnServer
    }

    public private(set) var phase: Phase = .idle
    public private(set) var importId: String?
    public private(set) var sendIds: [String] = []
    private var pickerOpened = false
    private var consentOpened = false

    public init() {}

    /// Poll the import while the server is waiting for Google or copying.
    public var needsPolling: Bool {
        switch phase {
        case .picking, .importing: true
        case .consent: importId != nil
        default: false
        }
    }

    public var isFinished: Bool {
        if case .finished = phase { return true }
        return false
    }

    public var canCancel: Bool {
        switch phase {
        case .consent, .picking, .importing: importId != nil
        default: false
        }
    }

    public mutating func handle(_ event: Event) -> [Effect] {
        switch event {
        case .start:
            guard phase == .idle || isFinished else { return [] }
            self = GoogleImportFlow()
            phase = .starting
            return [.post]
        case .startFailed:
            phase = .idle
            return []
        case .update(let imp):
            if let importId, imp.id != importId { return [] }
            importId = imp.id
            sendIds = imp.sendIds ?? sendIds
            if isFinished { return [] }
            return apply(imp)
        case .consentReturned(let id, _):
            if let id, let importId, id != importId { return [] }
            consentOpened = false
            return importId == nil ? [] : [.refresh]
        case .consentDismissed:
            consentOpened = false
            guard case .consent = phase else { return [] }
            phase = .finished(.cancelled)
            return importId == nil ? [] : [.cancelOnServer]
        case .pickerOpened:
            pickerOpened = true
            return []
        case .cancel:
            guard canCancel else { return [] }
            phase = .finished(.cancelled)
            return [.cancelOnServer]
        case .appActive:
            return importId != nil && !isFinished ? [.refresh] : []
        }
    }

    /// Open the picker again ("Open Google Photos" while waiting).
    public mutating func reopenPicker() -> [Effect] {
        guard case .picking(let url?) = phase else { return [] }
        pickerOpened = true
        return [.openPicker(url)]
    }

    private mutating func apply(_ imp: GoogleImport) -> [Effect] {
        guard let state = imp.state else {
            // A status this app doesn't know: keep polling, it will become something we know.
            return []
        }
        let p = imp.progress
        switch state {
        case .needsAuth:
            guard let url = imp.authUrl.flatMap(URL.init(string:)) else { phase = .finished(.failed(nil)); return [] }
            phase = .consent(url)
            if consentOpened { return [] }
            consentOpened = true
            return [.openConsent(url)]
        case .picking:
            let url = imp.pickerUri.flatMap(URL.init(string:))
            phase = .picking(url)
            if pickerOpened || url == nil { return [] }
            pickerOpened = true
            return [.openPicker(url!)]
        case .importing:
            phase = .importing(picked: p.picked, done: GoogleImportText.handled(imp))
            return []
        case .done:
            phase = .finished(.done(imported: p.imported, duplicates: p.duplicates ?? 0, skipped: p.skipped ?? 0, failed: p.failed ?? 0))
            return []
        case .failed:
            phase = .finished(.failed(imp.errorCode))
            return []
        case .expired:
            phase = .finished(.expired)
            return []
        case .cancelled:
            phase = .finished(.cancelled)
            return []
        }
    }

    /// `app.kinframe:/google-import?import_id=…&status=…` (also `app.kinframe://google-import?…`).
    public static func parseReturn(_ url: URL) -> (importId: String?, status: String?)? {
        guard url.scheme?.lowercased() == callbackScheme, let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let path = ((c.host ?? "") + c.path).split(separator: "/").map(String.init)
        guard path == ["google-import"] else { return nil }
        let q = Dictionary((c.queryItems ?? []).compactMap { i in i.value.map { (i.name, $0) } }, uniquingKeysWith: { a, _ in a })
        return (q["import_id"], q["status"])
    }

    /// An import to pick up after the app was closed while the picker was open: the newest unfinished one.
    public static func resumable(_ imports: [GoogleImport], now: Date = Date()) -> GoogleImport? {
        imports
            .filter { ($0.state == .picking || $0.state == .importing) && now.timeIntervalSince($0.createdAt) < 2 * 3600 }
            .max { $0.createdAt < $1.createdAt }
    }
}

public enum GoogleImportText {
    /// Items the server is finished with (added, already there, skipped or failed).
    public static func handled(_ imp: GoogleImport) -> Int {
        let p = imp.progress
        return p.imported + (p.duplicates ?? 0) + (p.skipped ?? 0) + (p.failed ?? 0)
    }

    public static func outcome(_ o: GoogleImportFlow.Outcome) -> SourceLine {
        switch o {
        case .done(let imported, let duplicates, let skipped, let failed):
            if imported == 0, duplicates == 0, skipped == 0, failed == 0 { return SourceLine("ios.gi.doneNothing") }
            if duplicates == 0, skipped == 0, failed == 0 { return SourceLine("ios.gi.done", ["count": "\(imported)"], tone: .ok) }
            var args = ["count": "\(imported)", "duplicates": "\(duplicates)", "failed": "\(failed + skipped)"]
            args["skipped"] = "\(skipped)"
            if failed + skipped == 0 { return SourceLine("ios.gi.doneDuplicates", args, tone: .ok) }
            if duplicates == 0 { return SourceLine("ios.gi.doneFailed", args, tone: .attention) }
            return SourceLine("ios.gi.doneMixed", args, tone: .attention)
        case .failed(let code):
            let known = code.flatMap(GoogleImportErrorCode.init(rawValue:))
            return SourceLine("ios.gi.err.\(known?.rawValue ?? "server_error")", tone: .error)
        case .expired: return SourceLine("ios.gi.expired", tone: .attention)
        case .cancelled: return SourceLine("ios.gi.cancelled")
        }
    }
}

// MARK: Push

public enum PhotoSourcePush {
    /// `photo_source_attention` and `google_import_done` (docs/api/README.md §6.3).
    public static func route(key: String, id: (String) -> String?) -> PushRoute? {
        switch key {
        case "photo_source_attention":
            guard let source = id("source_id") else { return nil }
            return .photoSource(sourceId: source, frameId: id("frame_id"), albumId: id("album_id"), feedId: id("feed_id"))
        case "google_import_done":
            return id("import_id").map { .googleImport(importId: $0) }
        default:
            return nil
        }
    }
}

/// Discovery's photo-source switches, together (README §5.14: clients hide what discovery doesn't offer).
public struct PhotoSourceFeatures: Equatable, Sendable {
    public var providers: [SourceProvider]
    public var privateNetworks: Bool
    public var googleImport: Bool

    public init(_ d: Discovery?) {
        providers = SourceDefaults.providers(d?.photoSourceProviders ?? [], enabled: d?.photoSources == true)
        privateNetworks = d?.photoSourcesPrivateNetworks ?? false
        googleImport = d?.googlePhotosImport ?? false
    }

    public var sources: Bool { !providers.isEmpty }
}
