import KinframeKit
import SwiftUI

/// A timeline: Home (every family and your frame posts) or one family's posts, newest first, kept fresh from the
/// websocket (README §5.18).
@MainActor
@Observable
final class TimelineModel {
    let api: API
    let feedId: String?
    let promptId: String?
    private(set) var posts: [TimelinePost] = []
    private(set) var nextBefore: String?
    private(set) var loaded = false
    private(set) var loadingMore = false
    var error: String?

    init(api: API, feedId: String? = nil, promptId: String? = nil) {
        self.api = api
        self.feedId = feedId
        self.promptId = promptId
    }

    var entries: [TimelineEntry] { TimelineLogic.entries(posts) }

    func load() async {
        do {
            let page = try await api.timeline(feedId: feedId, promptId: promptId)
            posts = page.posts
            nextBefore = page.nextBefore
            error = nil
        } catch is CancellationError {
        } catch {
            if posts.isEmpty { self.error = errorMessage(error) }
        }
        loaded = true
    }

    func loadMore() async {
        guard let cursor = nextBefore, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        if let page = try? await api.timeline(before: cursor, feedId: feedId, promptId: promptId) {
            posts = TimelineLogic.appending(page.posts, to: posts)
            nextBefore = page.nextBefore
        }
    }

    func post(_ id: String) -> TimelinePost? { posts.first { $0.id == id } }

    func replace(_ p: TimelinePost) {
        if let i = posts.firstIndex(where: { $0.id == p.id }) { posts[i] = p }
    }

    private func refresh(_ postId: String) async {
        guard posts.contains(where: { $0.id == postId }) else { return }
        if let fresh = try? await api.post(postId) { replace(fresh) }
    }

    // MARK: Live

    func apply(_ event: LiveEvent) {
        switch event {
        case .social(let e):
            apply(e)
        case .feedMembershipUpdated(let id) where feedId == nil || id == feedId:
            Task { await load() }
        default:
            break
        }
    }

    private func apply(_ e: SocialEvent) {
        switch e {
        case .postUpdated(let postId, let feed, _, let thread):
            if let thread, let i = posts.firstIndex(where: { $0.id == postId }) {
                posts[i].thread = thread
            } else if posts.contains(where: { $0.id == postId }) {
                Task { await refresh(postId) }
            } else if feedId == nil || feed == feedId, posts.first.map({ postId > $0.id }) ?? true {
                // A new post: load the newest page again so it comes in on top.
                Task { await load() }
            }
        case .postRemoved(let postId):
            posts.removeAll { $0.id == postId }
        case .commentCreated(let postId, let c), .commentUpdated(let postId, let c):
            guard let i = posts.firstIndex(where: { $0.id == postId }) else { break }
            if posts[i].thread.latestComments.contains(where: { $0.id == c.id }) || c.kind == .person {
                Task { await refresh(postId) }
            }
        case .commentDeleted(let postId, _):
            Task { await refresh(postId) }
        case .reactionUpdated(let kind, let target, let postId, _, let summary):
            guard kind == .post, let summary, let i = posts.firstIndex(where: { $0.id == (postId ?? target) }) else { break }
            posts[i].thread.reactions = summary
        case .threadRead(let postId, let upTo):
            if let i = posts.firstIndex(where: { $0.id == postId }) {
                posts[i].thread.readUpTo = upTo
                posts[i].thread.unreadCount = 0
                posts[i].thread.unreadMentions = 0
            }
        default:
            break
        }
    }

    // MARK: Actions

    /// The caller's reaction on a post (optimistic; the server's summary replaces it).
    func react(_ post: TimelinePost, _ emoji: PersonReaction?) async throws {
        guard let i = posts.firstIndex(where: { $0.id == post.id }) else { return }
        let before = posts[i].thread.reactions
        posts[i].thread.reactions = ReactionRow.applying(before, mine: emoji)
        do {
            let summary = try await api.setReaction(.post, post.id, emoji: emoji)
            if let j = posts.firstIndex(where: { $0.id == post.id }) { posts[j].thread.reactions = summary }
        } catch {
            if let j = posts.firstIndex(where: { $0.id == post.id }) { posts[j].thread.reactions = before }
            throw error
        }
    }

    func setMuted(_ post: TimelinePost, _ muted: Bool) async throws {
        try await api.setThreadMute(post.id, muted: muted)
        if let i = posts.firstIndex(where: { $0.id == post.id }) { posts[i].thread.muted = muted }
    }

    func setOpen(_ post: TimelinePost, _ open: Bool) async throws {
        let t = try await api.setThreadState(post.id, open: open)
        if let i = posts.firstIndex(where: { $0.id == post.id }) { posts[i].thread = t }
    }

    func hide(_ post: TimelinePost) { posts.removeAll { $0.id == post.id } }
    func restore(_ list: [TimelinePost]) { posts = list }

    /// Remove a family post (the poster or the family's admins), or unsend a frame post's whole send (the sender).
    func remove(_ post: TimelinePost) async throws {
        switch post.kind {
        case .feed:
            guard let feedId = post.feedId else { return }
            try await api.removeFeedPost(feedId, postId: post.id)
        case .frame:
            guard let sendId = post.sendId else { return }
            _ = try await api.call { try await $0.unsend(path: .init(sendId: sendId)).noContent }
        }
    }
}
