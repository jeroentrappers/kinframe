import Foundation
import OpenAPIRuntime

/// Conversations, messages, contacts, blocks and reports (tags `chats` and `safety` in openapi.yaml).
extension API {
    // MARK: Conversations

    public func conversations(archived: Bool = false) async throws -> [Conversation] {
        try await call { try await $0.listConversations(query: .init(archived: archived)).ok.body.json }
    }

    public func conversation(_ id: String) async throws -> Conversation {
        try await call { try await $0.getConversation(path: .init(conversationId: id)).ok.body.json }
    }

    /// A direct chat (the existing one when there is one) or a group with a name.
    public func createConversation(direct userId: String) async throws -> Conversation {
        try await createConversation(kind: .direct, memberIds: [userId], name: nil)
    }

    public func createGroup(name: String, memberIds: [String]) async throws -> Conversation {
        try await createConversation(kind: .group, memberIds: memberIds, name: String(name.prefix(GroupRules.maxName)))
    }

    private func createConversation(kind: Components.Schemas.ConversationCreate.KindPayload, memberIds: [String], name: String?) async throws -> Conversation {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.ConversationCreate(kind: kind, memberIds: memberIds, name: name)
        let out = try await call { try await $0.createConversation(headers: .init(idempotencyKey: key), body: .json(body)) }
        switch out {
        case .ok(let ok): return try ok.body.json
        case .created(let created): return try created.body.json
        default: throw APIError.network
        }
    }

    public func renameConversation(_ id: String, name: String) async throws -> Conversation {
        try await call {
            try await $0.updateConversation(path: .init(conversationId: id), body: .json(.init(name: String(name.prefix(GroupRules.maxName))))).ok.body.json
        }
    }

    /// A square JPEG, like the avatar; the server stores 256×256 WebP.
    public func setConversationPhoto(_ id: String, jpeg: Data) async throws -> Conversation {
        try await call { try await $0.putConversationPhoto(path: .init(conversationId: id), body: .jpeg(HTTPBody(jpeg))).ok.body.json }
    }

    public func removeConversationPhoto(_ id: String) async throws {
        _ = try await call { try await $0.deleteConversationPhoto(path: .init(conversationId: id)).noContent }
    }

    public func addConversationMembers(_ id: String, userIds: [String]) async throws -> Conversation {
        try await call { try await $0.addConversationMembers(path: .init(conversationId: id), body: .json(.init(userIds: userIds))).ok.body.json }
    }

    public func setConversationRole(_ id: String, userId: String, admin: Bool) async throws -> Conversation {
        try await call {
            try await $0.updateConversationMember(path: .init(conversationId: id, userId: userId), body: .json(.init(role: admin ? .admin : .member))).ok.body.json
        }
    }

    /// Remove someone (group admins) or leave (yourself).
    public func removeConversationMember(_ id: String, userId: String) async throws {
        _ = try await call { try await $0.removeConversationMember(path: .init(conversationId: id, userId: userId)).noContent }
    }

    public func setConversationMute(_ id: String, _ mute: MuteFor) async throws -> Conversation {
        try await call { try await $0.updateConversationPreferences(path: .init(conversationId: id), body: .json(.init(mute: mute))).ok.body.json }
    }

    public func setConversationArchived(_ id: String, _ archived: Bool) async throws -> Conversation {
        try await call { try await $0.updateConversationPreferences(path: .init(conversationId: id), body: .json(.init(archived: archived))).ok.body.json }
    }

    // MARK: Messages

    /// Oldest first within the page; no cursor = the newest messages.
    public func messages(_ id: String, before: String? = nil, after: String? = nil, limit: Int = 50) async throws -> MessagePage {
        try await call {
            try await $0.listMessages(path: .init(conversationId: id), query: .init(before: before, after: after, limit: limit)).ok.body.json
        }
    }

    public func message(_ id: String, messageId: String) async throws -> Message {
        try await call { try await $0.getMessage(path: .init(conversationId: id, messageId: messageId)).ok.body.json }
    }

    /// What one send of the composer holds.
    public struct OutgoingMessage: Sendable {
        public var clientId: String
        public var body: String?
        public var mentions: [Mention]
        public var items: [Components.Schemas.MessageItemCreate]
        public var voice: (uploadId: String, durationMs: Int)?
        public var replyToId: String?
        public var sharedPostId: String?
        public var linkPreview: Bool

        public init(clientId: String = UUID().uuidString.lowercased(), body: String? = nil, mentions: [Mention] = [],
                    items: [Components.Schemas.MessageItemCreate] = [], voice: (uploadId: String, durationMs: Int)? = nil,
                    replyToId: String? = nil, sharedPostId: String? = nil, linkPreview: Bool = true) {
            self.clientId = clientId
            self.body = body
            self.mentions = mentions
            self.items = items
            self.voice = voice
            self.replyToId = replyToId
            self.sharedPostId = sharedPostId
            self.linkPreview = linkPreview
        }
    }

    /// Idempotent on `client_id` (and the same `Idempotency-Key`), so a retry returns the first message.
    public func sendMessage(_ id: String, _ m: OutgoingMessage) async throws -> Message {
        let body = Components.Schemas.MessageCreate(
            clientId: m.clientId,
            body: m.body?.isEmpty == false ? m.body : nil,
            mentions: m.mentions.isEmpty ? nil : m.mentions,
            items: m.items.isEmpty ? nil : m.items,
            voice: m.voice.map { .init(uploadId: $0.uploadId, durationMs: max(1, $0.durationMs)) },
            replyToId: m.replyToId,
            sharedPostId: m.sharedPostId,
            linkPreview: m.linkPreview ? nil : false
        )
        return try await call {
            try await $0.sendMessage(path: .init(conversationId: id), headers: .init(idempotencyKey: "msg-" + m.clientId), body: .json(body)).created.body.json
        }
    }

    public func updateMessage(_ id: String, messageId: String, body: String, mentions: [Mention]) async throws -> Message {
        try await call {
            try await $0.updateMessage(path: .init(conversationId: id, messageId: messageId), body: .json(.init(body: body, mentions: mentions))).ok.body.json
        }
    }

    public func deleteMessage(_ id: String, messageId: String) async throws {
        _ = try await call { try await $0.deleteMessage(path: .init(conversationId: id, messageId: messageId)).noContent }
    }

    /// "Put on a frame": a normal send of the message's photos and videos (no new upload).
    public func putMessageOnFrame(_ id: String, messageId: String, frameIds: [String], feedIds: [String] = [], circleIds: [String] = [],
                                  itemIds: [String]? = nil) async throws -> Send {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.PutOnFrameRequest(frameIds: frameIds, feedIds: feedIds.isEmpty ? nil : feedIds,
                                                        circleIds: circleIds.isEmpty ? nil : circleIds, itemIds: itemIds)
        return try await call {
            try await $0.putMessageOnFrame(path: .init(conversationId: id, messageId: messageId), headers: .init(idempotencyKey: key),
                                           body: .json(body)).accepted.body.json
        }
    }

    public func markConversationRead(_ id: String, upTo messageId: String) async throws {
        _ = try await call { try await $0.markConversationRead(path: .init(conversationId: id), body: .json(.init(messageId: messageId))).noContent }
    }

    public func sendTyping(_ id: String, recording: Bool = false) async throws {
        _ = try await call {
            try await $0.sendTyping(path: .init(conversationId: id), body: .json(.init(activity: recording ? .recording : .typing))).noContent
        }
    }

    public func linkPreview(_ url: URL) async throws -> LinkPreview {
        try await call { try await $0.createLinkPreview(body: .json(.init(url: url.absoluteString))).ok.body.json }
    }

    // MARK: People

    public func contacts() async throws -> [Person] {
        try await call { try await $0.listContacts().ok.body.json }
    }

    public func person(_ userId: String) async throws -> Person {
        try await call { try await $0.getPerson(path: .init(userId: userId)).ok.body.json }
    }

    public func badge() async throws -> Badge {
        try await call { try await $0.getBadge().ok.body.json }
    }

    /// `PATCH /v1/me {show_read_receipts}`.
    public func setShowReadReceipts(_ on: Bool) async throws -> User {
        try await call { try await $0.updateMe(body: .json(.init(showReadReceipts: on))).ok.body.json }
    }

    // MARK: Blocks and reports

    public func blocks() async throws -> [Block] {
        try await call { try await $0.listBlocks().ok.body.json }
    }

    public func block(_ userId: String) async throws {
        _ = try await call { try await $0.blockUser(path: .init(userId: userId)).noContent }
    }

    public func unblock(_ userId: String) async throws {
        _ = try await call { try await $0.unblockUser(path: .init(userId: userId)).noContent }
    }

    public func report(_ kind: ReportTargetKind, _ targetId: String, reason: ReportReason, note: String?) async throws -> Report {
        let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = Components.Schemas.ReportCreate(targetKind: kind, targetId: targetId, reason: reason,
                                                   note: trimmed?.isEmpty == false ? String(trimmed!.prefix(500)) : nil)
        return try await call { try await $0.createReport(body: .json(body)).created.body.json }
    }

    public func feedReports(_ feedId: String) async throws -> [Report] {
        try await call { try await $0.listFeedReports(path: .init(feedId: feedId)).ok.body.json }
    }

    public func operatorReports(all: Bool = false) async throws -> [Report] {
        try await call { try await $0.listOperatorReports(query: .init(status: all ? .all : .open)).ok.body.json }
    }

    public func resolveReport(_ id: String, remove: Bool) async throws -> Report {
        try await call { try await $0.resolveReport(path: .init(reportId: id), body: .json(.init(action: remove ? .remove : .dismiss))).ok.body.json }
    }
}
