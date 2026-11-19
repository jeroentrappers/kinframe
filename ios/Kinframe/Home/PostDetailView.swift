import KinframeKit
import SwiftUI

/// One post and its thread (README §5.18): comments oldest first with one level of replies, frame entries, reactions,
/// mentions, edit and delete, moderation, close, mute and read state.
@MainActor
@Observable
final class ThreadModel {
    let api: API
    let postId: String
    private(set) var post: TimelinePost?
    private(set) var comments: [Comment] = []
    private(set) var hasMore = false
    private(set) var loaded = false
    private(set) var candidates: [MentionCandidate] = []
    private(set) var removed = false
    var error: String?

    init(api: API, postId: String) {
        self.api = api
        self.postId = postId
    }

    var nodes: [ThreadNode] { ThreadLogic.nest(comments) }

    func load() async {
        do {
            post = try await api.post(postId)
            var all: [Comment] = []
            var after: String?
            // Family-sized threads: load them whole, a page at a time.
            repeat {
                let page = try await api.comments(postId, after: after)
                all += page.comments
                hasMore = page.hasMore
                after = page.comments.last?.id
            } while hasMore && all.count < 1000 && after != nil
            comments = ThreadLogic.sorted(all)
            error = nil
        } catch is CancellationError {
        } catch let e as APIError where e.status == 404 {
            removed = true
        } catch {
            if post == nil { self.error = errorMessage(error) }
        }
        loaded = true
        await loadCandidates()
    }

    /// The people who see the post, for @mentions: the family (or circle) members, else the people in the thread.
    private func loadCandidates() async {
        guard let post else { return }
        var people: [MentionCandidate] = []
        if let circle = post.circleId, let feed = post.feedId, let c = try? await api.circle(feed, circleId: circle) {
            people = c.members.map { MentionCandidate(id: $0.userId, name: $0.displayName, avatarUrl: $0.avatarUrl) }
        } else if let feed = post.feedId, let members = try? await api.feedMembers(feed) {
            people = members.map { MentionCandidate(id: $0.userId, name: $0.displayName, avatarUrl: $0.avatarUrl) }
        }
        let inThread = ([post.poster] + comments.compactMap(\.author)).filter { $0.formerMember != true }
        for p in inThread where !people.contains(where: { $0.id == p.userId }) {
            people.append(MentionCandidate(id: p.userId, name: p.displayName, avatarUrl: p.avatarUrl))
        }
        candidates = people
    }

    /// Mark read up to the newest person comment (never backwards; the server ignores older ones).
    func markRead() async {
        guard let last = ThreadLogic.lastReadable(comments), last != post?.thread.readUpTo else { return }
        try? await api.markThreadRead(postId, upTo: last)
        post?.thread.readUpTo = last
        post?.thread.unreadCount = 0
        post?.thread.unreadMentions = 0
    }

    // MARK: Live

    func apply(_ event: LiveEvent) {
        guard case .social(let e) = event else { return }
        switch e {
        case .commentCreated(let p, let c) where p == postId, .commentUpdated(let p, let c) where p == postId:
            comments = ThreadLogic.upsert(c, into: comments)
        case .commentDeleted(let p, let id) where p == postId:
            comments = ThreadLogic.remove(id, from: comments)
        case .reactionUpdated(let kind, let target, _, _, let summary):
            guard let summary else { break }
            if kind == .comment, let i = comments.firstIndex(where: { $0.id == target }) {
                comments[i].reactions = summary
            } else if kind == .post, target == postId {
                post?.thread.reactions = summary
            }
        case .postUpdated(let p, _, _, let thread) where p == postId:
            if let thread { post?.thread = thread }
        case .postRemoved(let p) where p == postId:
            removed = true
        default:
            break
        }
    }

    // MARK: Writing

    func send(_ body: String, mentions: [Mention], replyTo: Comment?) async throws {
        let target = replyTo.map(ThreadLogic.replyTarget)
        let c = try await api.createComment(postId, body: body, mentions: mentions, parentId: target?.parentId, quotedUserId: target?.quotedUserId)
        comments = ThreadLogic.upsert(c, into: comments)
        if var t = post?.thread { t = ThreadLogic.adding(c, to: t); post?.thread = t }
        try? await api.markThreadRead(postId, upTo: c.id)
    }

    func edit(_ c: Comment, body: String, mentions: [Mention]) async throws {
        let updated = try await api.updateComment(postId, commentId: c.id, body: body, mentions: mentions)
        comments = ThreadLogic.upsert(updated, into: comments)
    }

    func delete(_ c: Comment) async throws {
        try await api.deleteComment(postId, commentId: c.id)
        // The server answers with the event (tombstone or gone); reload to be exact.
        if let page = try? await api.comments(postId) { comments = ThreadLogic.sorted(page.comments) } else { comments = ThreadLogic.remove(c.id, from: comments) }
    }

    func react(_ c: Comment, _ emoji: PersonReaction?) async throws {
        guard let i = comments.firstIndex(where: { $0.id == c.id }) else { return }
        let before = comments[i].reactions ?? ReactionRow.empty
        comments[i].reactions = ReactionRow.applying(before, mine: emoji)
        do {
            let s = try await api.setReaction(.comment, c.id, emoji: emoji)
            if let j = comments.firstIndex(where: { $0.id == c.id }) { comments[j].reactions = s }
        } catch {
            if let j = comments.firstIndex(where: { $0.id == c.id }) { comments[j].reactions = before }
            throw error
        }
    }

    func reactToPost(_ emoji: PersonReaction?) async throws {
        guard let before = post?.thread.reactions else { return }
        post?.thread.reactions = ReactionRow.applying(before, mine: emoji)
        do {
            post?.thread.reactions = try await api.setReaction(.post, postId, emoji: emoji)
        } catch {
            post?.thread.reactions = before
            throw error
        }
    }

    func setMuted(_ muted: Bool) async throws {
        try await api.setThreadMute(postId, muted: muted)
        post?.thread.muted = muted
    }

    func setOpen(_ open: Bool) async throws {
        post?.thread = try await api.setThreadState(postId, open: open)
    }
}

struct PostDetailView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let route: PostRoute
    @State private var model: ThreadModel?
    @State private var index = 0
    @State private var text = ""
    @State private var picks: [MentionCandidate] = []
    @State private var replyTo: Comment?
    @State private var editing: Comment?
    @State private var sending = false
    @State private var reporting: ReportTarget?
    @State private var sharing = false
    @FocusState private var focused: Bool

    private var me: String? { app.me?.id }

    var body: some View {
        Group {
            if let model, model.removed {
                EmptyStateView(systemImage: "rectangle.slash", title: L("social.post.goneTitle"), message: L("social.post.goneBody"))
            } else if let model, let post = model.post {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            postHeader(post)
                            Divider().overlay(Palette.border)
                            if model.nodes.isEmpty {
                                Text(L("social.noCommentsYet")).font(.subheadline).foregroundStyle(Palette.textMuted)
                            }
                            ForEach(model.nodes) { node in
                                CommentRow(comment: node.comment, model: model, reply: { startReply($0) }, edit: { startEdit($0) }, report: { reporting = $0 })
                                    .id(node.comment.id)
                                ForEach(node.replies) { r in
                                    CommentRow(comment: r, model: model, reply: { startReply($0) }, edit: { startEdit($0) }, report: { reporting = $0 })
                                        .padding(.leading, 44)
                                        .id(r.id)
                                }
                            }
                        }
                        .padding(16)
                        .frame(maxWidth: 720)
                        .frame(maxWidth: .infinity)
                    }
                    .onChange(of: model.loaded) {
                        if let c = route.commentId { withAnimation { proxy.scrollTo(c, anchor: .center) } }
                    }
                    .onChange(of: model.comments.count) { old, new in
                        if new > old, let last = model.comments.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                }
                .safeAreaInset(edge: .bottom) { composer(post) }
            } else if let error = model?.error {
                ErrorRetryView(message: error) { Task { await model?.load() } }.padding(16)
            } else {
                LoadingView()
            }
        }
        .pageBackground()
        .navigationTitle(L("social.post"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { if let post = model?.post { menu(post) } } }
        .task {
            guard model == nil, let api = app.api else { return }
            let m = ThreadModel(api: api, postId: route.postId)
            model = m
            await m.load()
            await m.markRead()
            app.social.clearNotifications(postId: route.postId)
        }
        .onLiveEvent(app.events) { e in
            model?.apply(e)
            if case .social(.commentCreated(let p, _)) = e, p == route.postId { Task { await model?.markRead() } }
        }
        .sheet(item: $reporting) { t in ReportSheet(kind: t.kind, targetId: t.id, inFamily: t.inFamily) }
        .sheet(isPresented: $sharing) { ShareInChatSheet(postId: route.postId) }
    }

    // MARK: The post

    private func postHeader(_ post: TimelinePost) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                PersonChip(person: post.poster, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(Credit.sender(post.poster.userId == me ? L("social.you") : PersonName.display(post.poster), via: post.via))
                        .font(.subheadline.weight(.semibold))
                    Text(TimelineLogic.label(post) + " · " + Format.relative(post.createdAt, locale: L10n.locale))
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            if !post.items.isEmpty {
                MediaPager(items: post.items, height: 360, index: $index)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            }
            if let caption = TimelineLogic.caption(post) { Text(caption).font(.body) }
            HStack(spacing: 16) {
                ReactButton(mine: post.thread.reactions.mine) { e in
                    Task { do { try await model?.reactToPost(e) } catch { app.toasts.error(error) } }
                }
                Spacer()
                if post.thread.muted == true { Image(systemName: "bell.slash").foregroundStyle(Palette.textFaint).accessibilityLabel(L("social.thread.muted")) }
            }
            ReactionSummaryRow(summary: post.thread.reactions, kind: .post, targetId: post.id)
            if let seen = TimelineLogic.seenText(post) { Text(seen).font(.footnote).foregroundStyle(Palette.textMuted) }
        }
    }

    private func menu(_ post: TimelinePost) -> some View {
        Menu {
            if app.social.features.chats {
                Button { sharing = true } label: { Label(L("social.shareInChat"), systemImage: "bubble.left.and.bubble.right") }
            }
            Button {
                Task { try? await model?.setMuted(!(post.thread.muted ?? false)) }
            } label: {
                post.thread.muted == true ? Label(L("social.thread.unmute"), systemImage: "bell") : Label(L("social.thread.mute"), systemImage: "bell.slash")
            }
            if post.thread.canModerate == true || post.poster.userId == me {
                Button {
                    Task { do { try await model?.setOpen(post.thread.state == .closed) } catch { app.toasts.error(error) } }
                } label: {
                    post.thread.state == .closed ? Label(L("social.openComments"), systemImage: "text.bubble") : Label(L("social.closeComments"), systemImage: "nosign")
                }
            }
            if post.poster.userId != me {
                Button { reporting = ReportTarget(kind: .post, id: post.id, inFamily: post.kind == .feed) } label: {
                    Label(L("chat.report"), systemImage: "flag")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle").frame(width: 44, height: 44)
        }
        .accessibilityLabel(L("ios.feeds.postActions"))
    }

    // MARK: Composer

    @ViewBuilder private func composer(_ post: TimelinePost) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if !TimelineLogic.canComment(post), editing == nil {
                Callout(L(post.thread.state == .closed ? "problem.comments_closed" : "social.cannotComment"))
            } else {
                if let r = replyTo {
                    banner(L("social.replyingTo", ["name": PersonName.display(r.author)])) { replyTo = nil }
                }
                if editing != nil {
                    banner(L("social.thread.editing")) { cancelEdit() }
                }
                HStack(alignment: .bottom, spacing: 10) {
                    MentionField(placeholder: L("social.thread.placeholder"), text: $text, picks: $picks, candidates: model?.candidates ?? [],
                                 me: me, maxLength: app.limits.maxCommentChars, focused: $focused)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(Palette.border))
                    Button { Task { await send() } } label: {
                        Image(systemName: sending ? "hourglass" : "arrow.up.circle.fill").font(.title).foregroundStyle(Palette.primary)
                    }
                    .frame(width: 44, height: 44)
                    .disabled(sending || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel(L(editing == nil ? "social.thread.send" : "common.save"))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func banner(_ text: String, cancel: @escaping () -> Void) -> some View {
        HStack {
            Text(text).font(.footnote.weight(.semibold)).foregroundStyle(Palette.textMuted)
            Spacer()
            Button(action: cancel) { Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.textFaint) }
                .accessibilityLabel(L("common.cancel"))
        }
    }

    private func startReply(_ c: Comment) {
        editing = nil
        replyTo = c
        if let a = c.author, a.userId != me, a.formerMember != true {
            let cand = MentionCandidate(id: a.userId, name: a.displayName, avatarUrl: a.avatarUrl)
            if !text.contains("@\(a.displayName)") { text = "@\(a.displayName) " + text }
            if !picks.contains(cand) { picks.append(cand) }
        }
        focused = true
    }

    private func startEdit(_ c: Comment) {
        replyTo = nil
        editing = c
        text = c.body ?? ""
        picks = Mentions.picks(in: text, mentions: c.mentions ?? [])
        focused = true
    }

    private func cancelEdit() {
        editing = nil
        text = ""
        picks = []
    }

    private func send() async {
        guard let model else { return }
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let mentions = Mentions.spans(in: body, picks: picks, max: app.limits.maxMentions)
        sending = true
        defer { sending = false }
        do {
            if let c = editing {
                try await model.edit(c, body: body, mentions: mentions)
                editing = nil
            } else {
                try await model.send(body, mentions: mentions, replyTo: replyTo)
                replyTo = nil
            }
            text = ""
            picks = []
        } catch {
            app.toasts.error(error)
        }
    }
}

/// One comment, reply or frame entry.
private struct CommentRow: View {
    @Environment(AppModel.self) private var app
    let comment: Comment
    let model: ThreadModel
    let reply: (Comment) -> Void
    let edit: (Comment) -> Void
    let report: (ReportTarget) -> Void
    @State private var confirmDelete = false

    private var me: String? { app.me?.id }

    var body: some View {
        switch ThreadLogic.display(comment) {
        case .frame:
            HStack(spacing: 10) {
                Image(systemName: "photo.on.rectangle").font(.footnote).foregroundStyle(Palette.textMuted)
                    .frame(width: 32, height: 32).background(Circle().fill(Palette.surface2))
                Text(ThreadLogic.frameEntryText(comment)).font(.subheadline).foregroundStyle(Palette.textMuted)
                Spacer()
            }
            .accessibilityElement(children: .combine)
        case .tombstone:
            Text(L("social.commentDeleted")).font(.subheadline).italic().foregroundStyle(Palette.textFaint)
                .padding(.leading, 42)
        case .person, .removedByAdmin:
            person
        }
    }

    private var person: some View {
        HStack(alignment: .top, spacing: 10) {
            Button { app.social.personSheet = PersonRoute(userId: comment.author?.userId ?? "", account: app.accountId) } label: {
                PersonChip(person: comment.author, size: 32)
            }
            .buttonStyle(.plain)
            .disabled(comment.author == nil || comment.author?.userId == me || !app.social.features.chats)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(PersonName.display(comment.author, me: me, you: true)).font(.subheadline.weight(.semibold))
                    Text(Format.relative(comment.createdAt, locale: L10n.locale)).font(.caption).foregroundStyle(Palette.textFaint)
                    if comment.editedAt != nil { Text(L("social.edited")).font(.caption).foregroundStyle(Palette.textFaint) }
                }
                if let q = comment.quoted {
                    Text("↪︎ " + PersonName.display(q)).font(.caption.weight(.semibold)).foregroundStyle(Palette.primarySoftText)
                }
                if ThreadLogic.display(comment) == .removedByAdmin {
                    Text(L("social.comment.removedByAdmin")).font(.caption.weight(.semibold)).foregroundStyle(Palette.danger)
                }
                MentionText(comment.body ?? "", mentions: comment.mentions)
                    .font(.subheadline)
                    .foregroundStyle(ThreadLogic.display(comment) == .removedByAdmin ? Palette.textFaint : Palette.text)
                    .textSelection(.enabled)
                HStack(spacing: 14) {
                    if ThreadLogic.display(comment) == .person {
                        Button(L("social.reply")) { reply(comment) }
                            .font(.caption.weight(.semibold)).foregroundStyle(Palette.textMuted).buttonStyle(.plain)
                            .disabled(model.post.map { !TimelineLogic.canComment($0) } ?? true)
                    }
                    if let r = comment.reactions {
                        ReactionSummaryRow(summary: r, kind: .comment, targetId: comment.id, compact: true)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .contextMenu { menu }
        .confirmationDialog(L("social.comment.deleteConfirm"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("social.deleteComment"), role: .destructive) {
                Task { do { try await model.delete(comment) } catch { app.toasts.error(error) } }
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L(comment.author?.userId == me ? "social.comment.deleteBody" : "social.comment.removeBody"))
        }
    }

    @ViewBuilder private var menu: some View {
        if ThreadLogic.display(comment) == .person {
            ReactionMenuItems(mine: comment.reactions?.mine) { e in
                Task { do { try await model.react(comment, e) } catch { app.toasts.error(error) } }
            }
            Button { reply(comment) } label: { Label(L("social.reply"), systemImage: "arrowshape.turn.up.left") }
            Button { UIPasteboard.general.string = comment.body } label: { Label(L("social.comment.copy"), systemImage: "doc.on.doc") }
            if comment.canEdit == true {
                Button { edit(comment) } label: { Label(L("common.edit"), systemImage: "pencil") }
            }
            if comment.author?.userId != me {
                Button { report(ReportTarget(kind: .comment, id: comment.id, inFamily: model.post?.kind == .feed)) } label: {
                    Label(L("chat.report"), systemImage: "flag")
                }
            }
        }
        if comment.canDelete == true {
            Button(role: .destructive) { confirmDelete = true } label: {
                Label(L(comment.author?.userId == me ? "social.deleteComment" : "social.removeComment"), systemImage: "trash")
            }
        }
    }
}
