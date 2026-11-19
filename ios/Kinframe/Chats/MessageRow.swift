import KinframeKit
import SwiftUI

/// One message: a bubble (own on the right), a system line in the middle, or a tombstone. Long-press for reactions,
/// reply, edit, delete, copy, report and "Put on a frame".
struct MessageRow: View {
    @Environment(AppModel.self) private var app
    let message: Message
    let conversation: Conversation
    let model: ConversationModel
    let continues: Bool
    let isLastOwn: Bool
    let actions: MessageActions
    @State private var confirmDelete = false
    @State private var recap: MonthlyRecap?
    @State private var playing: Item?

    private var me: String? { app.me?.id }
    private var own: Bool { message.author?.userId == me && message.kind == .person }

    var body: some View {
        if message.kind == .system {
            system
        } else {
            person
        }
    }

    // MARK: System messages

    private var system: some View {
        VStack(spacing: 6) {
            Text(message.system.map { MessageText.system($0, me: me) } ?? "")
                .font(.footnote)
                .foregroundStyle(Palette.textMuted)
                .multilineTextAlignment(.center)
            if let s = message.system, s._type == .monthlyRecap, let r = s.recap {
                Button { recap = r } label: { Text(SummaryText.recap(r)).font(.footnote.weight(.semibold)) }
                    .buttonStyle(.kf(.soft))
            }
            if message.reactions.total > 0 {
                ReactionSummaryRow(summary: message.reactions, kind: .message, targetId: message.id, compact: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .contextMenu {
            ReactionMenuItems(mine: message.reactions.mine) { e in react(e) }
        }
        .sheet(item: $recap) { r in RecapSheet(recap: r) }
    }

    // MARK: People's messages

    private var person: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if own { Spacer(minLength: 48) }
            if !own {
                if continues { Color.clear.frame(width: 30, height: 1) } else {
                    Button { if let a = message.author { app.social.personSheet = PersonRoute(userId: a.userId) } } label: {
                        PersonChip(person: message.author, size: 30)
                    }
                    .buttonStyle(.plain)
                    .disabled(message.author?.formerMember == true)
                }
            }
            VStack(alignment: own ? .trailing : .leading, spacing: 3) {
                if !own, !continues, conversation.kind != .direct {
                    Text(PersonName.display(message.author)).font(.caption.weight(.semibold)).foregroundStyle(Palette.textMuted)
                }
                bubble
                    .contextMenu { menu }
                if message.reactions.total > 0 {
                    ReactionSummaryRow(summary: message.reactions, kind: .message, targetId: message.id, compact: true)
                }
                frameActivity
                footer
            }
            if !own { Spacer(minLength: 48) }
        }
        .padding(.top, continues ? 1 : 8)
        .confirmationDialog(L(own ? "chat.message.deleteConfirm" : "chat.message.removeConfirm"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L(own ? "chat.message.delete" : "chat.message.remove"), role: .destructive) {
                Task { do { try await model.delete(message) } catch { app.toasts.error(error) } }
            }
            Button(L("common.cancel"), role: .cancel) {}
        }
        .sheet(item: $playing) { item in VideoSheet(itemId: item.id) }
    }

    private var bubbleFill: Color { own ? Palette.primarySoft : Palette.surface }

    @ViewBuilder private var bubble: some View {
        if message.deleted {
            Text(L(message.removedByAdmin == true ? "chat.message.removed" : "chat.message.deleted"))
                .font(.subheadline).italic().foregroundStyle(Palette.textFaint)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(Palette.border))
        } else {
            VStack(alignment: .leading, spacing: 6) {
                if let q = message.replyTo {
                    HStack(spacing: 6) {
                        Rectangle().fill(Palette.primary).frame(width: 3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(PersonName.display(q.author, me: me, you: true)).font(.caption.weight(.semibold)).foregroundStyle(Palette.primarySoftText)
                            Text(MessageText.quote(q)).font(.caption).foregroundStyle(Palette.textMuted).lineLimit(2)
                        }
                        if let t = q.thumbUrl { RemoteThumb(url: t, cornerRadius: 4).frame(width: 32, height: 32) }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                if !message.items.isEmpty { media }
                if let v = message.voice { VoiceBubble(clip: v) }
                if let p = message.sharedPost { SharedPostCard(card: p) }
                if let body = message.body, !body.isEmpty {
                    MentionText(body, mentions: message.mentions).font(.body).foregroundStyle(Palette.text).textSelection(.enabled)
                }
                if let lp = message.linkPreview, lp.status == .ok { LinkPreviewCard(preview: lp) }
                if MessageLogic.isPending(message) { pendingState }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(bubbleFill, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(own ? .clear : Palette.border))
        }
    }

    @ViewBuilder private var media: some View {
        let items = message.items
        let columns = items.count == 1 ? 1 : 2
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: columns), spacing: 3) {
            ForEach(items.prefix(4)) { item in
                Button { if item.kind == .video { playing = item } } label: {
                    ItemThumb(item: item)
                        .frame(height: items.count == 1 ? 220 : 110)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                        .overlay {
                            if item.id == items.prefix(4).last?.id, items.count > 4 {
                                ZStack {
                                    Color.black.opacity(0.45)
                                    Text("+\(items.count - 4)").font(.title3.weight(.bold)).foregroundStyle(.white)
                                }
                                .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                            }
                        }
                }
                .buttonStyle(.plain)
                .livePlayback(api: app.api, item: item)
            }
        }
        .frame(width: items.count == 1 ? 240 : 260)
        if !MessageLogic.frameable(message).isEmpty, !MessageLogic.isPending(message) {
            Button { actions.putOnFrame(message) } label: {
                Label(L("chat.putOnFrame"), systemImage: "photo.on.rectangle.angled").font(.footnote.weight(.semibold))
            }
            .buttonStyle(.kf(.soft))
        }
    }

    @ViewBuilder private var pendingState: some View {
        if let cid = message.clientId, model.failed[cid] != nil {
            HStack(spacing: 10) {
                Label(L("chat.failed"), systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(Palette.danger)
                Button(L("common.tryAgain")) { Task { await model.retry(message) } }.font(.caption.weight(.semibold))
                Button(L("chat.discard")) { model.discard(message) }.font(.caption)
            }
        } else if let cid = message.clientId, let p = model.progress[cid] {
            ProgressView(value: p).tint(Palette.primary).frame(width: 160)
            Text(L("chat.sendingMedia")).font(.caption).foregroundStyle(Palette.textMuted)
        } else {
            Text(L("chat.sending")).font(.caption).foregroundStyle(Palette.textFaint)
        }
    }

    /// "❤️ Oma's frame" under a message whose photos were put on a frame.
    @ViewBuilder private var frameActivity: some View {
        if let activity = message.frameActivity, !activity.isEmpty {
            FlowLayout(spacing: 4) {
                ForEach(Array(activity.enumerated()), id: \.offset) { _, a in
                    HStack(spacing: 3) {
                        if let r = a.reaction { Text(r.emoji) }
                        ForEach(a.replies ?? [], id: \.self) { rp in Text("💬 " + L("reply.\(rp.rawValue)")) }
                        Text(a.frameName)
                    }
                    .font(.caption)
                    .chip()
                }
            }
        }
    }

    @ViewBuilder private var footer: some View {
        HStack(spacing: 6) {
            if !continues || isLastOwn {
                Text(message.createdAt.formatted(date: .omitted, time: .shortened)).font(.caption2).foregroundStyle(Palette.textFaint)
            }
            if message.editedAt != nil, !message.deleted { Text(L("social.comment.edited")).font(.caption2).foregroundStyle(Palette.textFaint) }
            if isLastOwn, let seen = ReadReceipts.text(ReadReceipts.seenBy(message, in: model.conversation ?? conversation, me: me), kind: conversation.kind) {
                Text(seen).font(.caption2.weight(.semibold)).foregroundStyle(Palette.accentText)
            }
        }
    }

    // MARK: Menu

    @ViewBuilder private var menu: some View {
        if MessageLogic.canReact(message) {
            ReactionMenuItems(mine: message.reactions.mine) { e in react(e) }
            if ChatAbilities.canSend(conversation) {
                Button { actions.reply(message) } label: { Label(L("chat.reply"), systemImage: "arrowshape.turn.up.left") }
            }
            if let body = message.body, !body.isEmpty {
                Button { UIPasteboard.general.string = body } label: { Label(L("social.comment.copy"), systemImage: "doc.on.doc") }
            }
            if !MessageLogic.frameable(message).isEmpty {
                Button { actions.putOnFrame(message) } label: { Label(L("chat.putOnFrame"), systemImage: "photo.on.rectangle.angled") }
            }
            if MessageLogic.canEdit(message, me: me, hours: app.limits.messageEditHours), message.body != nil {
                Button { actions.edit(message) } label: { Label(L("common.edit"), systemImage: "pencil") }
            }
            if !own {
                Button { actions.report(message) } label: { Label(L("chat.report.action"), systemImage: "flag") }
            }
            if MessageLogic.canDelete(message, me: me, moderator: actions.moderator) {
                Button(role: .destructive) { confirmDelete = true } label: {
                    Label(L(own ? "chat.message.delete" : "chat.message.remove"), systemImage: "trash")
                }
            }
        }
    }

    private func react(_ e: PersonReaction?) {
        Task { do { try await model.react(message, e) } catch { app.toasts.error(error) } }
    }
}

/// A post shared in a chat: tap opens it ("This post isn't available" when the reader can't see it).
struct SharedPostCard: View {
    @Environment(AppModel.self) private var app
    let card: PostCard

    var body: some View {
        Button {
            guard card.available, app.social.features.social else { return }
            app.chatsPath.append(PostRoute(postId: card.postId))
        } label: {
            if card.available {
                VStack(alignment: .leading, spacing: 6) {
                    if let thumbs = card.thumbUrls, !thumbs.isEmpty {
                        HStack(spacing: 2) {
                            ForEach(thumbs.prefix(3), id: \.self) { t in RemoteThumb(url: t).frame(height: 90) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                    }
                    Text(PersonName.display(card.poster) + " · " + (card.feedName ?? card.frameName.map { L("social.post.toFrame", ["frame": $0]) } ?? ""))
                        .font(.caption.weight(.semibold)).foregroundStyle(Palette.textMuted)
                    if let c = card.caption, !c.isEmpty { Text(c).font(.footnote).foregroundStyle(Palette.text).lineLimit(2) }
                }
                .frame(width: 240, alignment: .leading)
            } else {
                Label(L("chat.post.unavailable"), systemImage: "eye.slash").font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .buttonStyle(.plain)
        .padding(8)
        .background(Palette.surface2, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
    }
}

/// The monthly family recap (also a system message in the family chat): about the family, never ranking people.
struct RecapSheet: View {
    @Environment(\.dismiss) private var dismiss
    let recap: MonthlyRecap

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(MonthText.name(recap.month)).font(.display(.title2))
                    Text(SummaryText.recap(recap)).foregroundStyle(Palette.textMuted)
                    if let loved = recap.mostLoved {
                        VStack(alignment: .leading, spacing: 6) {
                            SectionHeader(L("engagement.recap.mostLoved"))
                            ItemThumb(item: loved.item).frame(height: 240).clipShape(RoundedRectangle(cornerRadius: Radius.md))
                        }
                    }
                    if let frames = recap.freshestFrames, !frames.isEmpty {
                        SectionHeader(L("engagement.recap.freshest"))
                        ForEach(Array(frames.enumerated()), id: \.offset) { _, f in
                            HStack {
                                Text(f.frameName)
                                Spacer()
                                Text(Lp("engagement.recap.days", count: f.daysWithNewPhotos)).foregroundStyle(Palette.textMuted)
                            }
                            .font(.subheadline)
                        }
                    }
                    if let people = recap.newMembers, !people.isEmpty {
                        SectionHeader(L("engagement.recap.newMembers"))
                        Text(ListText.join(people.map { PersonName.display($0) }))
                    }
                    if let w = recap.streakWeeks, w > 0 {
                        Text("🔥 " + Lp("engagement.streak.weeks", count: w)).font(.headline)
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(L("engagement.recap.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
    }
}

extension Components.Schemas.MonthlyRecap: @retroactive Identifiable {
    public var id: String { feedId + month }
}
