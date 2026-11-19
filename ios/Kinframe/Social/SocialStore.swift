import KinframeKit
import SwiftUI
import UserNotifications

/// App-wide family social state (docs/spec/family-social.md): the chat list, the badge, typing, reminder
/// preferences, milestones and the caches the composer needs (circles, this week's prompts). Fed by the user
/// websocket through `AppModel`; screens read it from `app.social`.
@MainActor
@Observable
final class SocialStore {
    weak var app: AppModel?

    private(set) var badge: Badge?
    private(set) var conversations: [Conversation] = []
    private(set) var conversationsLoaded = false
    private(set) var conversationsError: String?
    /// The chat on screen (its messages count as read, no unread bump).
    var openConversationId: String?
    private(set) var typing: [String: TypingState] = [:]
    private(set) var reminders: ReminderPreferences?
    private(set) var milestones: [Milestone] = []
    /// Circles per family, for "Share with" in the composer and the post card.
    private(set) var circles: [String: [FamilyCircle]] = [:]
    /// This week's prompt per family (only families with prompts on).
    private(set) var prompts: [String: FeedPrompt] = [:]
    /// A sheet to show over the tabs (notifications, toasts, the Home strip).
    var sheet: SocialSheet?
    /// A person's profile opened from a mention or a name.
    var personSheet: PersonRoute?
    /// The newest reports to handle (badge on family settings and the operator's list).
    private(set) var newReports = 0
    /// Open photo requests of frames you're not on that reached you through a family (`photo_request.*` without a
    /// push), by frame id: the Home strip shows them and "Send photos" posts to the families reaching the frame.
    private(set) var familyRequests: [String: PhotoRequest] = [:]

    /// Keep a request heard of for a frame you're not on (dropped once answered or expired).
    func noteFamilyRequest(_ r: PhotoRequest) {
        if r.state == .open { familyRequests[r.frameId] = r } else { familyRequests[r.frameId] = nil }
    }

    var features: SocialFeatures { SocialFeatures(app?.discovery) }
    private var api: API? { app?.api }
    private var me: String? { app?.me?.id }

    // MARK: Loading

    /// After sign-in, on returning to the foreground and when discovery changes.
    func refresh() async {
        let f = features
        async let chats: Void = f.chats ? loadConversations() : clearChats()
        async let badge: Void = (f.chats || f.social) ? loadBadge() : ()
        async let reminders: Void = f.engagement ? loadReminders() : ()
        async let milestones: Void = f.engagement ? loadMilestones() : ()
        async let prompts: Void = f.engagement ? loadPrompts() : ()
        _ = await (chats, badge, reminders, milestones, prompts)
    }

    private func clearChats() async {
        conversations = []
        conversationsLoaded = false
    }

    func loadConversations() async {
        guard let api, features.chats else { return }
        do {
            conversations = ChatList.sorted(try await api.conversations())
            conversationsLoaded = true
            conversationsError = nil
        } catch is CancellationError {
        } catch {
            conversationsError = errorMessage(error)
        }
    }

    func loadBadge() async {
        guard let api else { return }
        if let b = try? await api.badge() { setBadge(b) }
    }

    func loadReminders() async {
        guard let api, features.engagement else { return }
        if let r = try? await api.reminderPreferences() { reminders = r }
    }

    func loadMilestones() async {
        guard let api, features.engagement else { return }
        if let m = try? await api.milestones() { milestones = m }
    }

    /// This week's prompt of every family (an empty list while a family has prompts off).
    func loadPrompts() async {
        guard let api, features.engagement, let feeds = app?.feeds else { return }
        var out: [String: FeedPrompt] = [:]
        await withTaskGroup(of: (String, FeedPrompt?).self) { group in
            for f in feeds {
                group.addTask { (f.id, (try? await api.feedPrompts(f.id)).flatMap(PromptLogic.current)) }
            }
            for await (id, p) in group { if let p { out[id] = p } }
        }
        prompts = out
    }

    /// The circles of a family (cached; refreshed on `circle.*` events).
    func circles(of feedId: String, reload: Bool = false) async -> [FamilyCircle] {
        if !reload, let list = circles[feedId] { return list }
        guard let api, features.social else { return [] }
        let list = (try? await api.circles(feedId)) ?? []
        circles[feedId] = list
        return list
    }

    func reset() {
        badge = nil
        conversations = []
        conversationsLoaded = false
        typing = [:]
        reminders = nil
        milestones = []
        circles = [:]
        prompts = [:]
        newReports = 0
        familyRequests = [:]
        openConversationId = nil
        let total = app?.accounts?.badgeTotal ?? 0
        Task { try? await UNUserNotificationCenter.current().setBadgeCount(total) }
    }

    // MARK: Updates from screens

    func update(_ c: Conversation) {
        conversations = ChatList.upsert(c, into: conversations)
    }

    func remove(conversation id: String) {
        conversations.removeAll { $0.id == id }
    }

    /// The chat was read up to its newest message: no unread left in the list.
    func markRead(_ id: String) {
        guard let i = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[i].unreadCount = 0
        conversations[i].unreadMentions = 0
        clearNotifications(conversationId: id)
    }

    func setReminders(_ r: ReminderPreferences) { reminders = r }

    func setMilestone(_ m: Milestone) {
        if let i = milestones.firstIndex(where: { $0.key == m.key }) { milestones[i] = m } else { milestones.append(m) }
    }

    func setCircles(_ list: [FamilyCircle], feedId: String) { circles[feedId] = list }

    func setPrompt(_ p: FeedPrompt?, feedId: String) { prompts[feedId] = p }

    func seenReports() { newReports = 0 }

    // MARK: Badge

    /// This account's unread total (the app badge is the sum over all accounts).
    var badgeCount: Int { badge?.total ?? 0 }

    func setBadge(_ b: Badge) {
        badge = b
        let total = app?.accounts?.badgeTotal ?? b.total
        Task { try? await UNUserNotificationCenter.current().setBadgeCount(total) }
    }

    /// Delivered notifications of a chat or thread that is now read.
    func clearNotifications(conversationId: String? = nil, postId: String? = nil) {
        Task {
            let center = UNUserNotificationCenter.current()
            let ids = await center.deliveredNotifications()
                .filter { SocialPush.matches($0.request.content.userInfo, conversationId: conversationId, postId: postId) }
                .map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }

    // MARK: Typing

    func typingText(_ conversationId: String) -> String? {
        typing[conversationId]?.text()
    }

    // MARK: Live events

    func handle(_ e: SocialEvent) {
        switch e {
        case .conversationUpdated(let c):
            update(c)
        case .conversationRemoved(let id):
            remove(conversation: id)
        case .messageCreated(let cid, let m), .messageUpdated(let cid, let m):
            if case .messageCreated = e {
                if conversations.contains(where: { $0.id == cid }) {
                    conversations = ChatList.applying(m, to: conversations, me: me, open: openConversationId)
                } else {
                    Task { await loadConversations() }
                }
                if let author = m.author?.userId { typing[cid]?.clear(userId: author) }
            } else if let i = conversations.firstIndex(where: { $0.id == cid }), conversations[i].lastMessage?.id == m.id {
                conversations[i].lastMessage = m
            }
        case .readUpdated(let cid, let user, let last):
            guard let i = conversations.firstIndex(where: { $0.id == cid }) else { break }
            if user == me {
                // Read on another device.
                if let last, (conversations[i].lastMessage?.id ?? "") <= last {
                    conversations[i].unreadCount = 0
                    conversations[i].unreadMentions = 0
                    clearNotifications(conversationId: cid)
                }
            } else {
                conversations[i] = ReadReceipts.applying(userId: user, lastRead: last, to: conversations[i])
            }
        case .typing(let cid, let user, let name, let recording, let until):
            guard user != me else { break }
            var t = typing[cid] ?? TypingState()
            t.expire()
            t.note(userId: user, name: name, recording: recording, until: until)
            typing[cid] = t
            // Drop it when it expires, so the line goes away without a new event.
            Task {
                try? await Task.sleep(for: .seconds(max(0.5, until.timeIntervalSinceNow + 0.2)))
                self.typing[cid]?.expire()
            }
        case .badgeUpdated(let b):
            setBadge(b)
        case .reportCreated:
            newReports += 1
        case .circleUpdated(let feedId, let c):
            if let c {
                var list = circles[feedId] ?? []
                if let i = list.firstIndex(where: { $0.id == c.id }) { list[i] = c } else { list.append(c) }
                circles[feedId] = list
            } else {
                circles[feedId] = nil
            }
            // Circle chats follow circle membership.
            if features.chats { Task { await loadConversations() } }
        case .circleRemoved(let feedId, let id):
            circles[feedId]?.removeAll { $0.id == id }
            if features.chats { Task { await loadConversations() } }
        case .promptUpdated(let feedId, let p):
            prompts[feedId] = p.flatMap { $0.status == .current && !$0.title.isEmpty ? $0 : nil }
        case .milestoneAchieved(let m):
            setMilestone(m)
        case .threadRead(let postId, _):
            clearNotifications(postId: postId)
        case .postUpdated, .postRemoved, .commentCreated, .commentUpdated, .commentDeleted, .reactionUpdated,
             .photoRequestCreated, .photoRequestUpdated, .streakUpdated:
            break
        }
    }
}
