import KinframeKit
import SwiftUI

/// One post on the timeline: who and where, the photos large (swipe through), the caption, the reaction row (people
/// and frames together), "Seen on 4 frames" and the two newest comments with "View all N comments".
struct TimelinePostCard: View {
    @Environment(AppModel.self) private var app
    let post: TimelinePost
    let model: TimelineModel
    var openThread: (PostRoute) -> Void
    @State private var index = 0
    @State private var itemReactions: [String: ReactionSummary] = [:]
    @State private var sharing = false
    @State private var reporting: ReportTarget?

    private var me: String? { app.me?.id }
    private var isOwn: Bool { post.poster.userId == me }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header.padding(14)
            if !post.items.isEmpty {
                MediaPager(items: post.items, height: post.items.count == 1 ? 320 : 360, index: $index)
                    .overlay(alignment: .bottomTrailing) { itemReactionChip }
                    .contextMenu { itemMenu }
            }
            VStack(alignment: .leading, spacing: 10) {
                if let caption = TimelineLogic.caption(post) {
                    Text(caption).font(.subheadline).foregroundStyle(Palette.text).lineLimit(4)
                }
                actions
                ReactionSummaryRow(summary: post.thread.reactions, kind: .post, targetId: post.id)
                if let seen = TimelineLogic.seenText(post) {
                    Text(seen).font(.footnote).foregroundStyle(Palette.textMuted)
                }
                comments
            }
            .padding(14)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .sheet(isPresented: $sharing) { ShareInChatSheet(postId: post.id) }
        .sheet(item: $reporting) { t in ReportSheet(kind: t.kind, targetId: t.id, inFamily: t.inFamily) }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            Button { app.social.personSheet = PersonRoute(userId: post.poster.userId, account: app.accountId) } label: {
                PersonChip(person: post.poster, size: 38)
            }
            .buttonStyle(.plain)
            .disabled(isOwn || post.poster.formerMember == true || !app.social.features.chats)
            VStack(alignment: .leading, spacing: 1) {
                Text(Credit.sender(isOwn ? L("social.you") : PersonName.display(post.poster), via: post.via))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
                HStack(spacing: 4) {
                    if post.kind == .frame { Image(systemName: "photo.on.rectangle").font(.caption2) }
                    if post.circleId != nil { Image(systemName: "person.2.circle").font(.caption2) }
                    Text(TimelineLogic.label(post) + " · " + Format.relative(post.createdAt, locale: L10n.locale))
                        .lineLimit(1)
                }
                .font(.footnote).foregroundStyle(Palette.textMuted)
            }
            Spacer(minLength: 0)
            menu
        }
    }

    private var menu: some View {
        Menu {
            if app.social.features.chats {
                Button { sharing = true } label: { Label(L("social.shareInChat"), systemImage: "bubble.left.and.bubble.right") }
            }
            Button {
                Task { try? await model.setMuted(post, !(post.thread.muted ?? false)) }
            } label: {
                if post.thread.muted == true {
                    Label(L("social.thread.unmute"), systemImage: "bell")
                } else {
                    Label(L("social.thread.mute"), systemImage: "bell.slash")
                }
            }
            if post.thread.canModerate == true || isOwn {
                Button {
                    Task {
                        do { try await model.setOpen(post, post.thread.state == .closed) } catch { app.toasts.error(error) }
                    }
                } label: {
                    if post.thread.state == .closed {
                        Label(L("social.openComments"), systemImage: "text.bubble")
                    } else {
                        Label(L("social.closeComments"), systemImage: "nosign")
                    }
                }
            }
            if !isOwn {
                Button { reporting = ReportTarget(kind: .post, id: post.id, inFamily: post.kind == .feed) } label: {
                    Label(L("chat.report"), systemImage: "flag")
                }
            }
            if post.canRemove == true {
                Button(role: .destructive) { remove() } label: {
                    Label(isOwn ? L("detail.unsend") : L("ios.feeds.removePost"), systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis").font(.body.weight(.semibold)).foregroundStyle(Palette.textMuted).frame(width: 44, height: 44)
        }
        .accessibilityLabel(L("ios.feeds.postActions"))
    }

    // MARK: One photo's reactions

    private var currentItem: Item? { post.items.indices.contains(index) ? post.items[index] : nil }

    @ViewBuilder private var itemReactionChip: some View {
        if post.items.count > 1, let item = currentItem, let s = itemReactions[item.id], s.total > 0 {
            Text(ReactionRow.emojis(s).joined() + " \(s.total)")
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Capsule().fill(.ultraThinMaterial))
                .padding(10)
        }
    }

    @ViewBuilder private var itemMenu: some View {
        if post.items.count > 1, let item = currentItem {
            Text(L("social.post.reactToPhoto"))
            ReactionMenuItems(mine: itemReactions[item.id]?.mine) { emoji in
                Task {
                    do {
                        itemReactions[item.id] = try await app.api?.setReaction(.postItem, item.id, emoji: emoji, postId: post.id)
                    } catch {
                        app.toasts.error(error)
                    }
                }
            }
        }
    }

    // MARK: Actions and comments

    private var actions: some View {
        HStack(spacing: 18) {
            ReactButton(mine: post.thread.reactions.mine) { emoji in
                Task {
                    do { try await model.react(post, emoji) } catch { app.toasts.error(error) }
                }
            }
            Button { openThread(PostRoute(postId: post.id, account: app.accountId)) } label: {
                Label(post.thread.state == .closed ? L("social.commentsOff") : L("social.thread.comment"), systemImage: "bubble.left")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Palette.textMuted)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            if app.social.features.chats {
                Button { sharing = true } label: {
                    Image(systemName: "paperplane").foregroundStyle(Palette.textMuted).frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("social.shareInChat"))
            }
            Spacer()
            if let unread = post.thread.unreadCount, unread > 0 {
                Pill(Lp("social.thread.new", count: unread), foreground: Palette.primarySoftText, background: Palette.primarySoft)
            }
        }
    }

    @ViewBuilder private var comments: some View {
        let t = post.thread
        if let all = ThreadLogic.viewAllText(t) {
            Button { openThread(PostRoute(postId: post.id, account: app.accountId)) } label: {
                Text(all).font(.footnote.weight(.semibold)).foregroundStyle(Palette.textMuted)
            }
            .buttonStyle(.plain)
        }
        ForEach(t.latestComments) { c in
            Button { openThread(PostRoute(postId: post.id, commentId: c.id, account: app.accountId)) } label: {
                (Text(PersonName.display(c.author, me: me, you: true) + "  ").bold() + Text(c.body ?? ""))
                    .font(.footnote)
                    .foregroundStyle(Palette.text)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
        }
    }

    private func remove() {
        let snapshot = model.posts
        app.toasts.deferred(L(isOwn ? "detail.unsent" : "ios.feeds.postRemovedNote"), apply: {
            withAnimation { model.hide(post) }
        }, rollback: {
            withAnimation { model.restore(snapshot) }
        }, commit: {
            try await model.remove(post)
        })
    }
}
