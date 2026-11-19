import Foundation

// Families on other servers (docs/spec/multi-server.md §3, README §5.22): frames here following a family on another
// server, families here allowing frames on other servers, and how remote frames and remote photos are named.

public typealias RemoteSubscription = Components.Schemas.RemoteSubscription
public typealias RemoteSubscriptionStatus = Components.Schemas.RemoteSubscription.StatusPayload
public typealias RemoteInvitePreview = Components.Schemas.RemoteInvitePreview
public typealias RemoteServer = Components.Schemas.RemoteServer
public typealias RemoteOnEnd = Components.Schemas.RemoteOnEnd
public typealias RemoteEndReason = Components.Schemas.RemoteEndReason
public typealias BlockedServer = Components.Schemas.BlockedServer

extension Components.Schemas.RemoteSubscription: Identifiable {}
extension Components.Schemas.BlockedServer: Identifiable {}

public enum RemoteInvite {
    /// What someone pasted, as a subscribe invite URL of a kinframe server (`https://<host>/fi/<token>`), trimmed;
    /// nil when it can't be one. `app.kinframe://fi/<token>?server=<url>` is turned into the https form.
    public static func normalize(_ input: String) -> URL? {
        let s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty, !s.contains(" "), var c = URLComponents(string: s.contains("://") ? s : "https://" + s) else { return nil }
        if c.scheme?.lowercased() == "app.kinframe" {
            // app.kinframe://fi/<token>?server=https://host
            guard c.host == "fi", let server = c.queryItems?.first(where: { $0.name == "server" })?.value,
                  let base = ServerAddress.normalize(server) else { return nil }
            let token = c.path.split(separator: "/").first.map(String.init) ?? ""
            guard isToken(token) else { return nil }
            return base.appending(path: "fi/\(token)")
        }
        guard let scheme = c.scheme?.lowercased(), scheme == "https" || scheme == "http", let host = c.host, !host.isEmpty else { return nil }
        let parts = c.path.split(separator: "/").map(String.init)
        guard parts.count == 2, parts[0] == "fi", isToken(parts[1]) else { return nil }
        c.scheme = scheme
        c.host = host.lowercased()
        c.query = nil
        c.fragment = nil
        return c.url
    }

    /// Invite tokens are URL-safe base64, 22 to 64 characters (`FederationSubscribeRequest.invite_token`).
    static func isToken(_ s: String) -> Bool {
        (22...64).contains(s.count) && s.allSatisfy { $0.isLetter && $0.isASCII || $0.isNumber && $0.isASCII || $0 == "-" || $0 == "_" }
    }

    /// The host shown for a server: "kinframe.janssens.be".
    public static func host(_ server: RemoteServer) -> String {
        if let h = server.host, !h.isEmpty { return h }
        return URL(string: server.serverUrl).map(ServerAddress.display) ?? server.serverUrl
    }
}

/// How remote frames and remote photos are named in threads, reaction rows and the library.
public enum RemoteLabel {
    /// "Oma's frame (kinframe.appmire.be)" for a frame on another server, else just the frame's name.
    public static func frame(_ name: String, server: String?, template: String = L("remote.frameOnServer")) -> String {
        guard let server = server?.trimmingCharacters(in: .whitespaces), !server.isEmpty else { return name }
        let t = template.contains("{frame}") ? template : "{frame} ({server})"
        return t.replacingOccurrences(of: "{frame}", with: name).replacingOccurrences(of: "{server}", with: server)
    }

    /// "Lies · Janssens" for a copy of a post of a family on another server; nil for other placements.
    public static func credit(_ placement: Placement) -> String? {
        guard let r = placement.originRemote else { return nil }
        let name = r.senderName?.trimmingCharacters(in: .whitespaces) ?? placement.item.remoteSender?.name ?? ""
        return name.isEmpty ? r.feedName : "\(name) · \(r.feedName)"
    }
}

public enum RemoteSubscriptionLogic {
    /// Still following (or waiting): blocks following the same family again and is listed first.
    public static func isLive(_ s: RemoteSubscriptionStatus) -> Bool {
        s == .pending || s == .active || s == .error
    }

    public static func rank(_ s: RemoteSubscriptionStatus) -> Int {
        switch s {
        case .pending: 0
        case .error: 1
        case .active: 2
        case .rejected: 3
        case .removed: 4
        }
    }

    public static func sorted(_ list: [RemoteSubscription]) -> [RemoteSubscription] {
        list.sorted { a, b in rank(a.status) != rank(b.status) ? rank(a.status) < rank(b.status) : a.createdAt > b.createdAt }
    }

    /// Backfill progress (0…1) while history is being copied, nil when idle.
    public static func backfillProgress(_ s: RemoteSubscription) -> Double? {
        guard let b = s.backfill, b.state == .running else { return nil }
        guard let total = b.total, total > 0 else { return 0 }
        return min(1, Double(b.placed ?? 0) / Double(total))
    }

    /// The status line: "Waiting for the family's admins", "Ended: the family removed this frame", …
    public static func statusText(_ s: RemoteSubscription) -> String {
        switch s.status {
        case .pending: return L("remote.status.pending")
        case .active: return L("remote.status.active")
        case .rejected: return L("remote.status.rejected")
        case .error: return L("remote.status.error")
        case .removed:
            guard let r = s.removedReason else { return L("remote.status.removed") }
            return L("servers.remote.reason.\(r.rawValue)")
        }
    }

    /// "Janssens (on kinframe.janssens.be) — 3 frames. Photos from this family will be copied to this server
    /// (kinframe.appmire.be) and shown on Oma's frame."
    public static func confirmation(_ p: RemoteInvitePreview, frameName: String) -> String {
        let head = L("servers.remote.confirmHead", ["feed": p.feedName, "server": RemoteInvite.host(p.server)])
        let frames = p.frameCount.map { " — " + Lp("servers.remote.frameCount", count: $0) } ?? ""
        let here = p.thisServerHost.flatMap { $0.isEmpty ? nil : $0 } ?? p.thisServerName
        let body = L("remote.previewCopy", ["server": here, "frame": frameName])
        return head + frames + ". " + body
    }
}

/// A host someone typed to block ("kinframe.example.org", or a pasted URL): the lower-cased host, nil when empty
/// or not a host name.
public enum BlockedHost {
    public static func normalize(_ input: String) -> String? {
        guard let url = ServerAddress.normalize(input), let host = url.host(), host.contains(".") || host == "localhost" else { return nil }
        if let port = url.port { return "\(host):\(port)" }
        return host
    }
}
