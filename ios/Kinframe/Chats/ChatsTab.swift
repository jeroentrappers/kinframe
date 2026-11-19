import KinframeKit
import SwiftUI

/// Chats (README §5.19): family, circle, group and direct chats by latest message, with unread counts. Start a chat
/// with people you share a family or a frame with; archive or mute with a swipe.
struct ChatsTab: View {
    @Environment(AppModel.self) private var app
    @State private var creating = false
    @State private var showArchived = false

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.chatsPath) {
            List {
                if !app.social.conversationsLoaded {
                    if let error = app.social.conversationsError {
                        ErrorRetryView(message: error) { Task { await app.social.loadConversations() } }
                            .listRowBackground(Color.clear)
                    } else {
                        LoadingView().listRowBackground(Color.clear)
                    }
                } else if app.social.conversations.isEmpty {
                    EmptyStateView(systemImage: "bubble.left.and.bubble.right", title: L("chat.list.emptyTitle"), message: L("chat.list.emptyBody")) {
                        Button(L("chat.new.title")) { creating = true }.buttonStyle(.kf(.primary, large: true))
                    }
                    .listRowBackground(Color.clear)
                } else {
                    ForEach(app.social.conversations) { c in
                        NavigationLink(value: ChatRoute(id: c.id)) { ChatRow(conversation: c) }
                            .listRowBackground(Palette.surface)
                            .swipeActions(edge: .trailing) {
                                Button { Task { await archive(c, true) } } label: { Label(L("chat.archive"), systemImage: "archivebox") }
                                    .tint(Palette.textMuted)
                            }
                            .swipeActions(edge: .leading) {
                                if c.muted {
                                    Button { Task { await mute(c, .off) } } label: { Label(L("chat.mute.off"), systemImage: "bell") }
                                } else {
                                    Button { Task { await mute(c, .always) } } label: { Label(L("chat.mute.action"), systemImage: "bell.slash") }
                                        .tint(Palette.accent)
                                }
                            }
                    }
                }
                Section {
                    OperatorNote(server: app.server.map { ServerAddress.display($0.url) } ?? "")
                }
                .listRowBackground(Color.clear)
            }
            .scrollContentBackground(.hidden)
            .refreshable { await app.social.loadConversations() }
            .pageBackground()
            .navigationTitle(L("chat.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showArchived = true } label: { Image(systemName: "archivebox").frame(width: 44, height: 44) }
                        .accessibilityLabel(L("chat.archived"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { creating = true } label: { Image(systemName: "square.and.pencil").frame(width: 44, height: 44) }
                        .accessibilityLabel(L("chat.new.title"))
                }
            }
            .socialDestinations()
        }
        .task(id: app.api?.baseURL) { await app.social.loadConversations() }
        .sheet(isPresented: $creating) {
            NewChatSheet { c in
                app.social.update(c)
                app.chatsPath.append(ChatRoute(id: c.id))
            }
        }
        .sheet(isPresented: $showArchived) { ArchivedChatsSheet() }
    }

    private func archive(_ c: Conversation, _ on: Bool) async {
        guard let api = app.api else { return }
        do {
            let updated = try await api.setConversationArchived(c.id, on)
            app.social.update(updated)
            if on { app.social.remove(conversation: c.id) }
            app.toasts.show(L(on ? "chat.archivedToast" : "chat.unarchivedToast"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func mute(_ c: Conversation, _ m: MuteFor) async {
        guard let api = app.api else { return }
        do { app.social.update(try await api.setConversationMute(c.id, m)) } catch { app.toasts.error(error) }
    }
}

/// One chat in the list: picture, name, the latest message, when, unread count.
struct ChatRow: View {
    @Environment(AppModel.self) private var app
    let conversation: Conversation

    var body: some View {
        let c = conversation
        HStack(spacing: 12) {
            ConversationAvatar(conversation: c, size: 48)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(ChatList.title(c)).font(.headline).foregroundStyle(Palette.text).lineLimit(1)
                    if c.muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(Palette.textFaint) }
                    Spacer()
                    Text(Format.relative(c.lastActivityAt, locale: L10n.locale)).font(.caption).foregroundStyle(Palette.textFaint)
                }
                HStack {
                    if let typing = app.social.typingText(c.id) {
                        Text(typing).font(.subheadline).italic().foregroundStyle(Palette.accentText).lineLimit(1)
                    } else {
                        Text(c.lastMessage.map { MessageText.preview($0, me: app.me?.id, kind: c.kind) } ?? ChatList.kindLabel(c))
                            .font(.subheadline).foregroundStyle(Palette.textMuted).lineLimit(2)
                    }
                    Spacer()
                    if c.unreadCount > 0 {
                        Text((c.unreadMentions ?? 0) > 0 ? "@" : "\(c.unreadCount)")
                            .font(.caption.weight(.bold).monospacedDigit())
                            .foregroundStyle(c.muted ? Palette.text : Palette.onPrimary)
                            .padding(.horizontal, 7).frame(minWidth: 22, minHeight: 22)
                            .background(Capsule().fill(c.muted ? Palette.surface3 : Palette.primary))
                            .accessibilityLabel(Lp("chat.unread", count: c.unreadCount))
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// A chat's picture: the group photo, the family's cover, the other person, or initials.
struct ConversationAvatar: View {
    let conversation: Conversation
    var size: CGFloat = 44

    var body: some View {
        switch conversation.kind {
        case .direct:
            PersonChip(person: conversation.other, size: size)
        case .family:
            FeedCover(url: conversation.photoUrl, name: conversation.name, size: size)
        case .circle:
            ZStack {
                Circle().fill(Palette.accentSoft)
                Image(systemName: "person.2.circle").font(.system(size: size * 0.45)).foregroundStyle(Palette.accentText)
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
        case .group:
            AvatarView(name: conversation.name, url: conversation.photoUrl, size: size)
        }
    }
}

/// Archived chats (yours, or of a deleted family or circle): open, or bring back to the list.
private struct ArchivedChatsSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var list: [Conversation]?

    var body: some View {
        NavigationStack {
            List {
                if let list {
                    if list.isEmpty {
                        Text(L("chat.archivedEmpty")).foregroundStyle(Palette.textMuted).listRowBackground(Color.clear)
                    }
                    ForEach(list) { c in
                        HStack {
                            ChatRow(conversation: c)
                            if c.readOnly != true {
                                Button(L("chat.unarchive")) { Task { await unarchive(c) } }.buttonStyle(.kf(.secondary))
                            }
                        }
                        .listRowBackground(Palette.surface)
                    }
                } else {
                    LoadingView().listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("chat.archived"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task { list = ((try? await app.api?.conversations(archived: true)) ?? []).filter(\.archived) }
        }
    }

    private func unarchive(_ c: Conversation) async {
        guard let api = app.api else { return }
        do {
            let updated = try await api.setConversationArchived(c.id, false)
            app.social.update(updated)
            list?.removeAll { $0.id == c.id }
        } catch {
            app.toasts.error(error)
        }
    }
}
