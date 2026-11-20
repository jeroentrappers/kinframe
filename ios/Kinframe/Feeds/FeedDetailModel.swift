import KinframeKit
import SwiftUI

/// One family: its posts, members (with join requests and invites), the frames that show it, its birthdays and
/// (owner and admins) its settings, for the Family profile and its settings. Kept fresh from the user websocket.
@MainActor
@Observable
final class FeedDetailModel {
    let api: API
    let id: String
    private(set) var feed: Feed?
    private(set) var posts: [FeedPost] = []
    private(set) var cursor: String?
    private(set) var postsLoaded = false
    private(set) var loadingMore = false
    private(set) var members: [FeedMember] = []
    private(set) var requests: [JoinRequest] = []
    private(set) var invites: [FeedInvite] = []
    private(set) var subscriptions: [FeedSubscription] = []
    /// Birthday people the caller may see, and recent greetings from the frames.
    private(set) var birthdays: [BirthdayPerson] = []
    private(set) var greetings: [FeedBirthdayGreeting] = []
    private(set) var birthdaysLoaded = false
    let birthdaysEnabled: Bool
    var error: String?

    init(api: API, id: String, birthdaysEnabled: Bool = false) {
        self.api = api
        self.id = id
        self.birthdaysEnabled = birthdaysEnabled
    }

    var abilities: FeedAbilities? { feed.map(FeedAbilities.init) }
    var birthdayAbilities: BirthdayAbilities? { feed.map(BirthdayAbilities.init) }

    func load() async {
        do {
            feed = try await api.feed(id)
            error = nil
        } catch is CancellationError {
        } catch {
            if feed == nil { self.error = errorMessage(error) }
            return
        }
        async let posts: Void = loadPosts()
        async let people: Void = loadPeople()
        async let subs: Void = loadSubscriptions()
        async let birthdays: Void = loadBirthdays()
        _ = await (posts, people, subs, birthdays)
    }

    func loadPosts() async {
        if let page = try? await api.feedPosts(id) {
            posts = page.posts
            cursor = page.next
        }
        postsLoaded = true
    }

    func loadMorePosts() async {
        guard let cursor, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        if let page = try? await api.feedPosts(id, cursor: cursor) {
            let known = Set(posts.map(\.id))
            posts.append(contentsOf: page.posts.filter { !known.contains($0.id) })
            self.cursor = page.next
        }
    }

    func loadPeople() async {
        members = (try? await api.feedMembers(id)) ?? members
        guard let a = abilities else { return }
        if a.canManage { requests = (try? await api.feedJoinRequests(id)) ?? [] }
        if a.canSeeInvites { invites = (try? await api.feedInvites(id)) ?? [] }
    }

    func loadSubscriptions() async {
        subscriptions = SubscriptionLogic.sorted((try? await api.feedSubscriptions(id)) ?? subscriptions)
    }

    func loadBirthdays() async {
        guard birthdaysEnabled else { return }
        if let list = try? await api.feedBirthdays(id) { birthdays = list }
        if let recent = try? await api.feedBirthdayGreetings(id) { greetings = recent }
        birthdaysLoaded = true
    }

    func apply(_ event: LiveEvent) {
        switch event {
        case .birthdaySuggested(let feedId, let b) where feedId == id, .birthdayDecided(let feedId, let b) where feedId == id,
             .birthdayUpdated(let feedId, let b) where feedId == id:
            if let b { upsert(b) } else { Task { await loadBirthdays() } }
            Task { await refreshFeed() }
        case .birthdayRemoved(let feedId, let birthdayId) where feedId == id:
            birthdays.removeAll { $0.id == birthdayId }
            Task { await refreshFeed() }
        case .birthdayGreeting(let feedId, let g) where feedId == id:
            if let g {
                greetings = BirthdayGreetings.merge(g, into: greetings)
                if let i = birthdays.firstIndex(where: { $0.id == g.birthdayId }) {
                    birthdays[i].greetingsToday = (birthdays[i].greetingsToday ?? 0) + 1
                }
            }
        case .feedPostUpdated(let feedId, let postId, let summary) where feedId == id:
            if let summary, let i = posts.firstIndex(where: { $0.id == postId }), posts[i].receipts != nil {
                posts[i].receipts = summary
            }
        case .feedReaction(let feedId, let postId, _, _, _) where feedId == id, .feedReply(let feedId, let postId, _, _) where feedId == id:
            Task { await refreshPost(postId) }
        case .subscriptionRequest(let feedId, _) where feedId == id, .subscriptionUpdated(let feedId, _, _) where feedId == id:
            Task {
                await loadSubscriptions()
                if let fresh = try? await api.feed(id) { feed = fresh }
            }
        case .feedJoinRequest(let feedId, _) where feedId == id:
            Task { await loadPeople() }
        case .feedMembershipUpdated(let feedId) where feedId == id:
            Task { await load() }
        default:
            break
        }
    }

    func refreshPost(_ postId: String) async {
        if let fresh = try? await api.feedPost(id, postId: postId), let i = posts.firstIndex(where: { $0.id == postId }) {
            posts[i] = fresh
        }
    }

    // MARK: Posts

    func hide(post: FeedPost) { posts.removeAll { $0.id == post.id } }
    func restore(posts list: [FeedPost]) { posts = list }

    func remove(post: FeedPost) async throws {
        try await api.removeFeedPost(id, postId: post.id)
    }

    func hide(item: Item, of post: FeedPost) {
        guard let i = posts.firstIndex(where: { $0.id == post.id }) else { return }
        posts[i].items.removeAll { $0.id == item.id }
        if posts[i].items.isEmpty { posts.remove(at: i) }
    }

    func remove(item: Item, of post: FeedPost) async throws {
        try await api.removeFeedPostItem(id, postId: post.id, itemId: item.id)
    }

    // MARK: Members

    func decide(_ r: JoinRequest, approve: Bool) async throws {
        try await api.decideFeedJoinRequest(id, requestId: r.id, approve: approve)
        requests.removeAll { $0.id == r.id }
        if approve { await loadPeople() }
    }

    func setRole(_ m: FeedMember, role: FeedRole) async throws {
        let updated = try await api.setFeedMemberRole(id, userId: m.userId, role: role)
        if let i = members.firstIndex(where: { $0.userId == m.userId }) { members[i] = updated }
    }

    func remove(member m: FeedMember, removePosts: Bool) async throws {
        try await api.removeFeedMember(id, userId: m.userId, removePosts: removePosts)
        members.removeAll { $0.userId == m.userId }
        if removePosts { await loadPosts() }
    }

    func transfer(to m: FeedMember) async throws {
        try await api.transferFeed(id, to: m.userId)
        await load()
    }

    func leave(removeMyPosts: Bool) async throws {
        try await api.leaveFeed(id, removeMyPosts: removeMyPosts)
    }

    func createInvite(purpose: FeedInvitePurpose, hours: Int, singleUse: Bool, note: String, allowRemote: Bool = false) async throws -> FeedInvite {
        let invite = try await api.createFeedInvite(id, purpose: purpose, hours: hours, maxUses: singleUse ? 1 : nil, note: note,
                                                    allowRemote: allowRemote)
        invites.insert(invite, at: 0)
        return invite
    }

    func hide(invite: FeedInvite) { invites.removeAll { $0.id == invite.id } }
    func restore(invites list: [FeedInvite]) { invites = list }

    func revoke(_ invite: FeedInvite) async throws {
        try await api.revokeFeedInvite(id, inviteId: invite.id)
    }

    // MARK: Subscriptions

    func replace(_ old: FeedSubscription, with new: FeedSubscription?) {
        if let new, let i = subscriptions.firstIndex(where: { $0.id == old.id }) {
            subscriptions[i] = new
        } else if new == nil {
            subscriptions.removeAll { $0.id == old.id }
            Task { await loadSubscriptions() }
        }
        subscriptions = SubscriptionLogic.sorted(subscriptions)
    }

    func added(_ s: FeedSubscription) {
        subscriptions.removeAll { $0.id == s.id }
        subscriptions = SubscriptionLogic.sorted(subscriptions + [s])
    }

    // MARK: Settings

    func save(name: String, autoApprove: Bool, invitesNeedApproval: Bool, membersSeeReactions: Bool, postersCanInvite: Bool,
              postersCanAddBirthdays: Bool? = nil) async throws {
        feed = try await api.updateFeed(id, name: name, autoApprove: autoApprove, invitesNeedApproval: invitesNeedApproval,
                                        membersSeeReactions: membersSeeReactions, postersCanInvite: postersCanInvite,
                                        postersCanAddBirthdays: postersCanAddBirthdays)
    }

    func setCover(_ itemId: String?) async throws {
        feed = try await api.setFeedCover(id, itemId: itemId)
    }

    func delete() async throws {
        try await api.deleteFeed(id)
    }

    func refreshFeed() async {
        if let fresh = try? await api.feed(id) { feed = fresh }
    }

    // MARK: Birthdays

    func upsert(_ b: BirthdayPerson) {
        if let i = birthdays.firstIndex(where: { $0.id == b.id }) {
            birthdays[i] = b
        } else {
            birthdays.append(b)
        }
    }

    func addBirthday(_ draft: BirthdayDraft) async throws -> BirthdayPerson {
        let b = try await api.createFeedBirthday(id, draft)
        upsert(b)
        await refreshFeed()
        return b
    }

    func updateBirthday(_ b: BirthdayPerson, patch: [String: JSONValue]) async throws -> BirthdayPerson {
        guard !patch.isEmpty else { return b }
        let updated = try await api.updateFeedBirthday(id, birthdayId: b.id, patch: patch)
        upsert(updated)
        return updated
    }

    func hide(birthday b: BirthdayPerson) { birthdays.removeAll { $0.id == b.id } }

    func removeBirthday(_ b: BirthdayPerson) async throws {
        try await api.removeFeedBirthday(id, birthdayId: b.id)
        hide(birthday: b)
        await refreshFeed()
    }

    func decide(_ b: BirthdayPerson, approve: Bool) async throws -> BirthdayPerson {
        let updated = approve ? try await api.approveFeedBirthday(id, birthdayId: b.id) : try await api.rejectFeedBirthday(id, birthdayId: b.id)
        upsert(updated)
        await refreshFeed()
        return updated
    }

    func setPhoto(_ b: BirthdayPerson, jpeg: Data) async throws -> BirthdayPerson {
        let updated = try await api.setFeedBirthdayPhoto(id, birthdayId: b.id, jpeg: jpeg)
        upsert(updated)
        return updated
    }

    func removePhoto(_ b: BirthdayPerson) async throws -> BirthdayPerson {
        let updated = try await api.removeFeedBirthdayPhoto(id, birthdayId: b.id)
        upsert(updated)
        return updated
    }

    func setBirthdayNotifications(_ on: Bool) async throws {
        feed = try await api.setBirthdayNotifications(id, on: on)
    }
}
