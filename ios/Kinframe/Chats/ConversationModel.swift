import KinframeKit
import SwiftUI

/// One conversation: its messages (oldest first), sending with optimistic bubbles, edits, deletes, reactions, read
/// state, and the live events that keep it current (README §5.19).
@MainActor
@Observable
final class ConversationModel {
    let api: API
    let id: String
    private(set) var conversation: Conversation?
    private(set) var messages: [Message] = []
    private(set) var hasMoreBefore = false
    private(set) var loaded = false
    private(set) var loadingOlder = false
    private(set) var gone = false
    /// Pending bubbles that failed, by client id, with what to retry.
    private(set) var failed: [String: () async -> Void] = [:]
    /// Upload progress of a pending bubble (0…1), by client id.
    private(set) var progress: [String: Double] = [:]
    var error: String?
    private var lastTyping: Date?
    var me: PersonRef?

    init(api: API, id: String) {
        self.api = api
        self.id = id
    }

    func load() async {
        do {
            async let c = api.conversation(id)
            async let page = api.messages(id)
            conversation = try await c
            let p = try await page
            messages = MessageLogic.sorted(p.messages + messages.filter(MessageLogic.isPending))
            hasMoreBefore = p.hasMoreBefore
            error = nil
        } catch is CancellationError {
        } catch let e as APIError where e.status == 404 {
            gone = true
        } catch {
            if conversation == nil { self.error = errorMessage(error) }
        }
        loaded = true
    }

    func loadOlder() async {
        guard hasMoreBefore, !loadingOlder, let first = messages.first(where: { !MessageLogic.isPending($0) }) else { return }
        loadingOlder = true
        defer { loadingOlder = false }
        if let page = try? await api.messages(id, before: first.id) {
            messages = MessageLogic.merging(page.messages, into: messages)
            hasMoreBefore = page.hasMoreBefore
        }
    }

    /// After a reconnect or returning to the app: the messages after the newest we have.
    func catchUp() async {
        guard let last = MessageLogic.lastReadable(messages) else { return await load() }
        var after = last
        while let page = try? await api.messages(id, after: after) {
            messages = MessageLogic.merging(page.messages, into: messages)
            guard page.hasMoreAfter, let next = page.messages.last?.id else { break }
            after = next
        }
        if let c = try? await api.conversation(id) { conversation = c }
    }

    func markRead() async {
        guard let last = MessageLogic.lastReadable(messages), (conversation?.readUpTo ?? "") < last else { return }
        try? await api.markConversationRead(id, upTo: last)
        conversation?.readUpTo = last
        conversation?.unreadCount = 0
    }

    // MARK: Live

    func apply(_ event: LiveEvent) {
        guard case .social(let e) = event else { return }
        switch e {
        case .messageCreated(let cid, let m) where cid == id, .messageUpdated(let cid, let m) where cid == id:
            messages = MessageLogic.upsert(m, into: messages)
            if let cid = m.clientId { failed[cid] = nil; progress[cid] = nil }
        case .conversationUpdated(let c) where c.id == id:
            conversation = c
        case .conversationRemoved(let cid) where cid == id:
            gone = true
        case .readUpdated(let cid, let user, let last) where cid == id:
            if let c = conversation { conversation = ReadReceipts.applying(userId: user, lastRead: last, to: c) }
        case .reactionUpdated(.message, let target, _, let cid, let summary) where cid == id || cid == nil:
            if let summary, let i = messages.firstIndex(where: { $0.id == target }) { messages[i].reactions = summary }
        default:
            break
        }
    }

    // MARK: Sending

    private func pending(_ clientId: String, body: String?, mentions: [Mention], replyTo: Message?, items: [Item] = [],
                         voice: VoiceClip? = nil, sharedPostId: String? = nil) -> Message {
        Message(id: MessageLogic.pendingPrefix + clientId, conversationId: id, kind: .person, author: me, clientId: clientId, body: body,
                mentions: mentions, items: items, voice: voice,
                replyTo: replyTo.map { MessageQuote(id: $0.id, author: $0.author, excerpt: $0.body.map { String($0.prefix(200)) }, deleted: false) },
                sharedPost: sharedPostId.map { PostCard(postId: $0, available: true) },
                reactions: ReactionRow.empty, createdAt: Date(), deleted: false)
    }

    private func run(_ clientId: String, _ work: @escaping () async throws -> Message) async {
        do {
            let m = try await work()
            messages = MessageLogic.upsert(m, into: messages)
            failed[clientId] = nil
            progress[clientId] = nil
            await markRead()
        } catch {
            failed[clientId] = { [weak self] in await self?.run(clientId, work) }
            self.error = errorMessage(error)
        }
    }

    func sendText(_ body: String, mentions: [Mention], replyTo: Message?, linkPreview: Bool) async {
        let out = API.OutgoingMessage(body: body, mentions: mentions, replyToId: replyTo?.id, linkPreview: linkPreview)
        messages = MessageLogic.upsert(pending(out.clientId, body: body, mentions: mentions, replyTo: replyTo), into: messages)
        await run(out.clientId) { [api, id] in try await api.sendMessage(id, out) }
    }

    func sharePost(_ postId: String, body: String?) async {
        let out = API.OutgoingMessage(body: body, sharedPostId: postId)
        messages = MessageLogic.upsert(pending(out.clientId, body: body, mentions: [], replyTo: nil, sharedPostId: postId), into: messages)
        await run(out.clientId) { [api, id] in try await api.sendMessage(id, out) }
    }

    /// Photos and videos: each prepared and uploaded (three at a time), then one message with all of them.
    func sendMedia(_ files: [(url: URL, filename: String)], body: String?, replyTo: Message?, maxVideoSeconds: Int) async {
        let clientId = UUID().uuidString.lowercased()
        messages = MessageLogic.upsert(pending(clientId, body: body, mentions: [], replyTo: replyTo), into: messages)
        progress[clientId] = 0
        let api = api, id = id
        itemProgress[clientId] = Array(repeating: 0, count: files.count)
        let report: @Sendable (Int, Double) -> Void = { [weak self] n, p in
            Task { @MainActor in self?.report(clientId, item: n, p) }
        }
        await run(clientId) {
            let items = try await Self.upload(files, api: api, maxVideoSeconds: maxVideoSeconds, progress: report)
            for f in files { try? FileManager.default.removeItem(at: f.url) }
            let out = API.OutgoingMessage(clientId: clientId, body: body, items: items, replyToId: replyTo?.id)
            return try await api.sendMessage(id, out)
        }
    }

    /// Prepare and upload, three at a time, keeping the picked order.
    nonisolated private static func upload(_ files: [(url: URL, filename: String)], api: API, maxVideoSeconds: Int,
                                           progress: @escaping @Sendable (Int, Double) -> Void) async throws -> [Components.Schemas.MessageItemCreate] {
        var items = Array<Components.Schemas.MessageItemCreate?>(repeating: nil, count: files.count)
        try await withThrowingTaskGroup(of: (Int, Components.Schemas.MessageItemCreate).self) { group in
            var next = 0
            while next < min(3, files.count) {
                let n = next, f = files[n]
                group.addTask { (n, try await ChatUpload.item(f.url, filename: f.filename, api: api, maxVideoSeconds: maxVideoSeconds) { progress(n, $0) }) }
                next += 1
            }
            while let (n, item) = try await group.next() {
                items[n] = item
                if next < files.count {
                    let m = next, f = files[m]
                    group.addTask { (m, try await ChatUpload.item(f.url, filename: f.filename, api: api, maxVideoSeconds: maxVideoSeconds) { progress(m, $0) }) }
                    next += 1
                }
            }
        }
        return items.compactMap { $0 }
    }

    private var itemProgress: [String: [Double]] = [:]

    private func report(_ clientId: String, item n: Int, _ p: Double) {
        guard var list = itemProgress[clientId], list.indices.contains(n) else { return }
        list[n] = p
        itemProgress[clientId] = list
        progress[clientId] = list.reduce(0, +) / Double(max(1, list.count))
    }

    func sendVoice(_ file: URL, durationMs: Int, replyTo: Message?) async {
        let clientId = UUID().uuidString.lowercased()
        let clip = VoiceClip(itemId: "pending", status: .processing, durationMs: durationMs, waveform: [])
        messages = MessageLogic.upsert(pending(clientId, body: nil, mentions: [], replyTo: replyTo, voice: clip), into: messages)
        let api = api, id = id
        await run(clientId) {
            let upload = try await ChatUpload.voice(file, api: api)
            try? FileManager.default.removeItem(at: file)
            return try await api.sendMessage(id, API.OutgoingMessage(clientId: clientId, voice: (upload, durationMs), replyToId: replyTo?.id))
        }
    }

    func retry(_ m: Message) async {
        guard let cid = m.clientId, let again = failed[cid] else { return }
        failed[cid] = nil
        await again()
    }

    func discard(_ m: Message) {
        if let cid = m.clientId { failed[cid] = nil; progress[cid] = nil }
        messages.removeAll { $0.id == m.id }
    }

    // MARK: Changing

    func edit(_ m: Message, body: String, mentions: [Mention]) async throws {
        let updated = try await api.updateMessage(id, messageId: m.id, body: body, mentions: mentions)
        messages = MessageLogic.upsert(updated, into: messages)
    }

    func delete(_ m: Message) async throws {
        try await api.deleteMessage(id, messageId: m.id)
        if let fresh = try? await api.message(id, messageId: m.id) { messages = MessageLogic.upsert(fresh, into: messages) }
    }

    func react(_ m: Message, _ emoji: PersonReaction?) async throws {
        guard let i = messages.firstIndex(where: { $0.id == m.id }) else { return }
        let before = messages[i].reactions
        messages[i].reactions = ReactionRow.applying(before, mine: emoji)
        do {
            let s = try await api.setReaction(.message, m.id, emoji: emoji)
            if let j = messages.firstIndex(where: { $0.id == m.id }) { messages[j].reactions = s }
        } catch {
            if let j = messages.firstIndex(where: { $0.id == m.id }) { messages[j].reactions = before }
            throw error
        }
    }

    /// `typing` at most every 3 s while typing or recording.
    func typing(recording: Bool = false) {
        guard TypingState.shouldSend(last: lastTyping) else { return }
        lastTyping = Date()
        Task { try? await api.sendTyping(id, recording: recording) }
    }

    func setMute(_ m: MuteFor) async throws {
        conversation = try await api.setConversationMute(id, m)
    }

    func setConversation(_ c: Conversation) { conversation = c }
}
