import Foundation
import OpenAPIRuntime

/// Families on other servers (tag `remote` in openapi.yaml, README §5.22): frame managers following a family on
/// another server, family admins allowing remote frames and blocking servers.
extension API {
    // MARK: Frame managers (B)

    public func previewRemoteInvite(_ inviteURL: URL) async throws -> RemoteInvitePreview {
        try await call {
            try await $0.previewRemoteInvite(body: .json(.init(inviteUrl: inviteURL.absoluteString))).ok.body.json
        }
    }

    public func remoteSubscriptions(frameId: String) async throws -> [RemoteSubscription] {
        try await call { try await $0.listRemoteSubscriptions(path: .init(frameId: frameId)).ok.body.json }
    }

    public func createRemoteSubscription(frameId: String, inviteURL: URL, history: HistoryChoice, birthdays: Bool,
                                         onEnd: RemoteOnEnd) async throws -> RemoteSubscription {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.RemoteSubscriptionCreate(inviteUrl: inviteURL.absoluteString, history: history.history,
                                                               historyCount: history.count, birthdays: birthdays, onEnd: onEnd)
        return try await call {
            try await $0.createRemoteSubscription(path: .init(frameId: frameId), headers: .init(idempotencyKey: key), body: .json(body))
                .created.body.json
        }
    }

    public func updateRemoteSubscription(frameId: String, id: String, onEnd: RemoteOnEnd? = nil,
                                         birthdays: Bool? = nil) async throws -> RemoteSubscription {
        try await call {
            try await $0.updateRemoteSubscription(path: .init(frameId: frameId, remoteSubscriptionId: id),
                                                  body: .json(.init(onEnd: onEnd, birthdays: birthdays))).ok.body.json
        }
    }

    public func deleteRemoteSubscription(frameId: String, id: String, placements: RemoteOnEnd?) async throws {
        _ = try await call {
            try await $0.deleteRemoteSubscription(path: .init(frameId: frameId, remoteSubscriptionId: id),
                                                  query: .init(placements: placements)).noContent
        }
    }

    // MARK: Family admins (A)

    /// `PATCH /v1/feeds/{id} {allow_remote_frames}`. Turning it off ends every remote subscription.
    public func setAllowRemoteFrames(feedId: String, _ on: Bool) async throws -> Feed {
        let body = Components.Schemas.FeedUpdate(allowRemoteFrames: on)
        return try await call { try await $0.updateFeed(path: .init(feedId: feedId), body: .json(body)).ok.body.json }
    }

    public func feedBlockedServers(_ feedId: String) async throws -> [BlockedServer] {
        try await call { try await $0.listFeedBlockedServers(path: .init(feedId: feedId)).ok.body.json }
    }

    public func blockFeedServer(_ feedId: String, host: String, reason: String?) async throws -> BlockedServer {
        let r = reason?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = Components.Schemas.BlockedServerCreate(host: host, reason: r?.isEmpty == false ? String(r!.prefix(300)) : nil)
        return try await call { try await $0.blockFeedServer(path: .init(feedId: feedId), body: .json(body)).created.body.json }
    }

    public func unblockFeedServer(_ feedId: String, id: String) async throws {
        _ = try await call { try await $0.unblockFeedServer(path: .init(feedId: feedId, blockedServerId: id)).noContent }
    }
}
