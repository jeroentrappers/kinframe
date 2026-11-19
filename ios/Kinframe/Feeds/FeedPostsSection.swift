import KinframeKit
import SwiftUI

/// The feed's posts, newest first. Receipts and reactions show where the server includes them: your own posts,
/// every post for owners and admins, everyone's with "members see reactions" (docs/spec/feeds.md §7).
struct FeedPostsSection: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    let openReceipts: (FeedPost) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 14) {
            if !model.postsLoaded {
                LoadingView()
            } else if model.posts.isEmpty {
                EmptyStateView(systemImage: "photo.stack", title: L("ios.feeds.noPostsTitle"), message: L("ios.feeds.noPostsBody")) {
                    Button(L("ios.feeds.post")) {
                        app.composeFeedIds = [model.id]
                        app.tab = .send
                    }
                    .buttonStyle(.kf(.primary, large: true))
                }
                .card(padding: 8)
            } else {
                ForEach(model.posts) { post in
                    FeedPostCard(post: post, abilities: model.abilities, me: app.me?.id,
                                 openReceipts: { openReceipts(post) },
                                 removePost: { remove(post) },
                                 removeItem: { remove($0, of: post) })
                }
                if model.cursor != nil {
                    Button {
                        Task { await model.loadMorePosts() }
                    } label: {
                        if model.loadingMore { ProgressView() } else { Text(L("common.loadMore")) }
                    }
                    .buttonStyle(.kf(.secondary, fill: true))
                    .onAppear { Task { await model.loadMorePosts() } }
                }
            }
        }
    }

    /// Undo instead of "are you sure": the post leaves the list now and every frame when the toast is gone.
    private func remove(_ post: FeedPost) {
        let before = model.posts
        let own = post.poster.userId == app.me?.id
        app.toasts.deferred(L(own ? "ios.feeds.unsent" : "ios.feeds.postRemoved"), apply: {
            withAnimation { model.hide(post: post) }
        }, rollback: {
            withAnimation { model.restore(posts: before) }
        }, commit: {
            try await model.remove(post: post)
        })
    }

    private func remove(_ item: Item, of post: FeedPost) {
        let before = model.posts
        app.toasts.deferred(L("ios.feeds.itemRemoved"), apply: {
            withAnimation { model.hide(item: item, of: post) }
        }, rollback: {
            withAnimation { model.restore(posts: before) }
        }, commit: {
            try await model.remove(item: item, of: post)
        })
    }
}

/// One post: who and when, the photos, the caption, and (when visible) "Seen on 4 of 5 frames" with reactions by
/// frame.
struct FeedPostCard: View {
    @Environment(AppModel.self) private var app
    let post: FeedPost
    let abilities: FeedAbilities?
    let me: String?
    let openReceipts: () -> Void
    let removePost: () -> Void
    let removeItem: (Item) -> Void

    private var canRemove: Bool { abilities?.canRemove(posterId: post.poster.userId, me: me) ?? false }
    private var isOwn: Bool { post.poster.userId == me }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                AvatarView(name: post.poster.displayName, url: post.poster.avatarUrl, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(Credit.sender(isOwn ? L("ios.feeds.postedByYou") : post.poster.displayName, via: post.via)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(Format.relative(post.createdAt, locale: L10n.locale) + " · " + kindSummary(post.items))
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
                Spacer()
                if canRemove || post.receipts != nil {
                    menu
                }
            }
            .padding(14)
            grid
            VStack(alignment: .leading, spacing: 10) {
                if let caption = post.items.compactMap(\.caption).first(where: { !$0.isEmpty }) {
                    Text("“\(caption)”").font(.subheadline).italic().foregroundStyle(Palette.text).lineLimit(3)
                }
                if let r = post.receipts {
                    Button(action: openReceipts) {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(FeedReceipts.text(r)).font(.subheadline.weight(.medium)).foregroundStyle(Palette.text)
                                Spacer()
                                StagePill(stage: FeedReceipts.stage(r))
                            }
                            ProgressView(value: FeedReceipts.progress(r)).tint(Palette.accent).accessibilityHidden(true)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(L("ios.feeds.receiptsOpen"))
                }
                if let reactions = post.reactions, !reactions.isEmpty {
                    FeedReactionLines(reactions: reactions)
                }
            }
            .padding(14)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private var menu: some View {
        Menu {
            if post.receipts != nil {
                Button(action: openReceipts) { Label(L("ios.feeds.receipts"), systemImage: "checklist") }
            }
            if canRemove {
                if post.items.count > 1 {
                    Menu {
                        ForEach(Array(post.items.enumerated()), id: \.element.id) { n, item in
                            Button(role: .destructive) { removeItem(item) } label: {
                                Text(item.caption?.isEmpty == false ? item.caption! : L("detail.itemAlt", ["n": n + 1]))
                            }
                        }
                    } label: {
                        Label(L("ios.feeds.removeOne"), systemImage: "minus.circle")
                    }
                }
                Button(role: .destructive, action: removePost) {
                    Label(isOwn ? L("detail.unsend") : L("ios.feeds.removePost"), systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle").font(.title3).frame(width: 44, height: 44)
        }
        .accessibilityLabel(L("ios.feeds.postActions"))
    }

    private var grid: some View {
        let shown = Array(post.items.prefix(4))
        let more = post.items.count - shown.count
        return HStack(spacing: 2) {
            ForEach(Array(shown.enumerated()), id: \.element.id) { n, item in
                ItemThumb(item: item)
                    .livePlayback(api: app.api, item: item)
                    .overlay {
                        if n == shown.count - 1, more > 0 {
                            ZStack {
                                Color.black.opacity(0.45)
                                Text("+\(more)").font(.title3.weight(.bold).monospacedDigit()).foregroundStyle(.white)
                            }
                        }
                    }
            }
        }
        .frame(height: shown.count == 1 ? 220 : 150)
        .clipped()
        .accessibilityHidden(true)
    }
}

/// "❤️ Oma's frame · 😂 Kitchen", with quick replies.
struct FeedReactionLines: View {
    let reactions: [FeedReaction]

    var body: some View {
        let lines = FeedReceipts.reactionLines(reactions)
        FlowLayout(spacing: 6) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                HStack(spacing: 4) {
                    if let r = line.reaction { Text(r.emoji) }
                    Text(line.frame).font(.caption.weight(.medium)).lineLimit(1)
                    ForEach(Array(Set(line.replies)).sorted { $0.rawValue < $1.rawValue }, id: \.self) { preset in
                        Text("· 💬 " + L("reply.\(preset.rawValue)")).font(.caption)
                    }
                }
                .chip()
                .accessibilityElement(children: .combine)
            }
        }
        .accessibilityLabel(L("history.reactions"))
    }
}

/// Per-frame receipts of one post: delivered, seen, awaiting approval, paused or removed per photo, with reactions
/// and replies. Hidden and trashed are never revealed.
struct FeedReceiptsSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: FeedDetailModel
    let postId: String
    @State private var receipts: FeedPostReceipts?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let error {
                        Callout(error, kind: .error)
                    } else if let r = receipts {
                        Text(FeedReceipts.text(r.summary)).font(.headline)
                        if r.frames.isEmpty {
                            Text(L("ios.feeds.receipts.noFrames")).foregroundStyle(Palette.textMuted)
                        }
                        ForEach(r.frames, id: \.subscriptionId) { f in frame(f) }
                    } else {
                        LoadingView()
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(L("ios.feeds.receipts"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task { await load() }
            .refreshable { await load() }
            .onLiveEvent(app.events) { event in
                if case .feedPostUpdated(_, let p, _) = event, p == postId { Task { await load() } }
            }
        }
    }

    private func frame(_ f: FeedFrameReceipt) -> some View {
        let status = f.subscriptionStatus == .paused ? FeedDeliveryStatus.suspended : FeedReceipts.frameStatus(f.items)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                StatusDot(online: f.online ?? false)
                VStack(alignment: .leading, spacing: 1) {
                    Text(f.frameName).font(.body.weight(.semibold))
                    Text([f.frameLocation, (f.online ?? false) ? nil : f.lastSeenAt.map { L("ios.feeds.lastSeen", ["when": Format.relative($0, locale: L10n.locale)]) }]
                        .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
                Spacer()
                FeedDeliveryPill(status: status)
            }
            if f.items.count > 1 {
                ForEach(Array(f.items.enumerated()), id: \.element.itemId) { n, item in
                    HStack(spacing: 6) {
                        Text(L("detail.itemAlt", ["n": n + 1])).font(.caption).foregroundStyle(Palette.textMuted)
                        Spacer()
                        if let r = item.reaction { Text(r.emoji).accessibilityLabel(L("reaction.\(r.rawValue)")) }
                        FeedDeliveryPill(status: item.status)
                    }
                }
            }
            let reactions = f.items.map(\.reaction)
            let replies = f.items.flatMap { $0.replies ?? [] }.compactMap(\.preset)
            ReactionChips(reactions: reactions, replies: replies)
        }
        .card(padding: 14)
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        do {
            receipts = try await model.api.feedPostReceipts(model.id, postId: postId)
            error = nil
        } catch is CancellationError {
        } catch {
            if receipts == nil { self.error = errorMessage(error) }
        }
    }
}

struct FeedDeliveryPill: View {
    let status: FeedDeliveryStatus

    var body: some View {
        let (icon, fg, bg): (String, Color, Color) = switch status {
        case .pending: ("clock", Palette.textMuted, Palette.surface2)
        case .ready: ("paperplane", Palette.warn, Palette.warnSoft)
        case .delivered: ("checkmark", Palette.accentText, Palette.accentSoft)
        case .seen: ("eye", Palette.surface, Palette.accent)
        case .failed: ("exclamationmark.triangle", Palette.danger, Palette.dangerSoft)
        case .awaitingApproval: ("hourglass", Palette.warn, Palette.warnSoft)
        case .suspended: ("pause.fill", Palette.textMuted, Palette.surface2)
        case .removed: ("xmark", Palette.textMuted, Palette.surface2)
        }
        Pill(L("ios.feeds.delivery.\(status.rawValue)"), systemImage: icon, foreground: fg, background: bg)
    }
}
