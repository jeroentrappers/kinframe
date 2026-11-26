import KinframeKit
import SwiftUI

/// Home › the activity bell (docs/spec/simple-app.md §3.1), as on the web and Android: frames asking for new photos,
/// what waits for you as an admin of a family (people who want to join, frames waiting for approval), then posts with
/// new comments or a mention of you. Rows open the existing screens: the send sheet, the family's People or Frames
/// (approve or decline there) and the post. Every account, or only Home's filtered family.
struct ActivityRoute: Hashable {}

struct ActivityView: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts

    private struct PostRow: Identifiable {
        let owner: AppModel
        let post: TimelinePost
        var id: String { owner.accountId + "/" + post.id }
    }

    private struct PendingRow: Identifiable {
        let owner: AppModel
        let pending: ActivityList.Pending
        var id: String { pending.id }
    }

    private struct RequestRow: Identifiable {
        let owner: AppModel
        let request: PhotoRequest
        var id: String { owner.accountId + "/" + request.frameId }
    }

    @State private var posts: [PostRow] = []
    @State private var pending: [PendingRow] = []
    @State private var loaded = false

    private var filter: FamilyChoice? { accounts.homeFilter }

    private func scope(_ m: AppModel) -> Set<String>? { accounts.activityScope(m) }

    private var requests: [RequestRow] {
        accounts.activityRequests.map { RequestRow(owner: $0.0, request: $0.1) }
    }

    var body: some View {
        let requests = requests
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                if !loaded && posts.isEmpty && pending.isEmpty && requests.isEmpty {
                    LoadingView()
                } else if posts.isEmpty && pending.isEmpty && requests.isEmpty {
                    Text(L("simple.activity.none"))
                        .foregroundStyle(Palette.textMuted)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 32)
                } else {
                    ForEach(requests) { row in
                        item(icon: "heart.fill", tint: Palette.primarySoftText,
                             title: L("engagement.request.open", ["frame": row.request.frameName]),
                             detail: row.request.freshness.map(FreshnessText.label)) {
                            row.owner.social.sheet = .freshSend(frameId: row.request.frameId, requestId: row.request.id)
                        }
                    }
                    ForEach(pending) { row in
                        item(icon: joinIcon(row.pending), tint: Palette.primarySoftText, title: row.pending.text,
                             detail: accounts.hasSeveralFamilies ? row.pending.family : nil) {
                            open(row)
                        }
                    }
                    ForEach(posts) { row in
                        postItem(row)
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await load() }
        .pageBackground()
        .pageTitle(L("simple.activity.title"))
        // Again after coming back from a post (its comments are read now).
        .task { await load() }
    }

    private func joinIcon(_ p: ActivityList.Pending) -> String {
        if case .joinRequests = p { return "person.badge.plus" }
        return "photo.on.rectangle"
    }

    private func open(_ row: PendingRow) {
        switch row.pending {
        case .joinRequests(let c, _, _): row.owner.openFamily(c.feedId, section: .members)
        case .frameRequests(let c, _, _): row.owner.openFamily(c.feedId, section: .frames)
        }
    }

    private func item(icon: String, tint: Color, title: String, detail: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Palette.primarySoft))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                    if let detail { Text(detail).font(.footnote).foregroundStyle(Palette.textMuted) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint)
            }
            .contentShape(Rectangle())
            .card(padding: 12)
        }
        .buttonStyle(.plain)
    }

    private func postItem(_ row: PostRow) -> some View {
        let p = row.post
        let when = Format.relative(p.createdAt, locale: L10n.locale)
        let detail = accounts.hasSeveralFamilies ? TimelineLogic.label(p) + " · " + when : when
        return Button {
            app.homePath.append(PostRoute(postId: p.id, account: row.owner.accountId))
        } label: {
            HStack(spacing: 12) {
                RemoteThumb(url: p.items.first?.thumbUrl, kind: p.items.first?.kind, cornerRadius: 10)
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ActivityList.text(p, me: row.owner.me?.id)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(detail).font(.footnote).foregroundStyle(Palette.textMuted).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint)
            }
            .contentShape(Rectangle())
            .card(padding: 12)
        }
        .buttonStyle(.plain)
    }

    /// Each account's newest 50 posts (the filtered family's) and its admin families' details, side by side.
    private func load() async {
        let models = accounts.active.filter { scope($0).map { !$0.isEmpty } ?? true }
        var nextPosts: [PostRow] = []
        var nextPending: [PendingRow] = []
        await withTaskGroup(of: (String, [TimelinePost], [Feed]).self) { g in
            for m in models {
                guard let api = m.api else { continue }
                let only = scope(m)
                let admin = m.feeds.filter { ActivityList.Family.isAdmin($0.role) && (only?.contains($0.id) ?? true) }.map(\.id)
                let account = m.accountId
                g.addTask {
                    let (posts, families) = await Self.fetch(api, feedId: only?.first, admin: admin)
                    return (account, posts, families)
                }
            }
            for await (account, posts, families) in g {
                guard let m = models.first(where: { $0.accountId == account }) else { continue }
                nextPosts += ActivityList.posts(posts, in: scope(m)).map { PostRow(owner: m, post: $0) }
                nextPending += ActivityList.pending(families.map { ActivityList.Family($0, account: account) })
                    .map { PendingRow(owner: m, pending: $0) }
            }
        }
        // Families in Home's order (the home family first), posts newest first over all accounts.
        let order = accounts.orderedFamilies.map(\.choice)
        posts = nextPosts.sorted { $0.post.createdAt > $1.post.createdAt }
        let rank = { (r: PendingRow) in order.firstIndex(of: r.pending.choice) ?? order.count }
        pending = nextPending.enumerated().sorted { (rank($0.element), $0.offset) < (rank($1.element), $1.offset) }.map(\.element)
        loaded = true
    }

    /// One account's timeline page and the details of the given families (in that order), side by side.
    private nonisolated static func fetch(_ api: API, feedId: String?, admin: [String]) async -> ([TimelinePost], [Feed]) {
        async let page = try? api.timeline(limit: 50, feedId: feedId)
        let feeds = await withTaskGroup(of: (Int, Feed?).self) { g in
            for (i, id) in admin.enumerated() { g.addTask { (i, try? await api.feed(id)) } }
            var out: [(Int, Feed)] = []
            for await (i, f) in g { if let f { out.append((i, f)) } }
            return out.sorted { $0.0 < $1.0 }.map(\.1)
        }
        return (await page?.posts ?? [], feeds)
    }
}

extension Accounts {
    /// The families Activity looks at in an account: nil is all of them, empty none (Home is filtered to a family of
    /// another account).
    func activityScope(_ m: AppModel) -> Set<String>? {
        guard let filter = homeFilter else { return nil }
        return filter.account == m.accountId ? [filter.feedId] : []
    }

    /// Open photo requests of every account's frames and of the families' frames heard about.
    var activityRequests: [(AppModel, PhotoRequest)] {
        active.flatMap { m -> [(AppModel, PhotoRequest)] in
            let scope = activityScope(m)
            if let scope, scope.isEmpty { return [] }
            let list = m.frames.compactMap(\.photoRequest) + Array(m.social.familyRequests.values)
            return ActivityList.requests(list, in: scope).map { (m, $0) }
        }
    }

    /// The number on Home's bell: unread comment threads and open photo requests.
    var activityBadge: Int {
        let threads = active.filter { activityScope($0).map { !$0.isEmpty } ?? true }.reduce(0) { $0 + ($1.social.badge?.threads ?? 0) }
        return ActivityList.badge(threads: threads, requests: activityRequests.count)
    }
}
