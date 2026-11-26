import KinframeKit
import SwiftUI

/// Chats (README §5.19): family, circle, group and direct chats by latest message, with unread counts. Start a chat
/// with people you share a family or a frame with; archive or mute with a swipe.
struct ChatsTab: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    @State private var creating = false
    /// The account a new chat starts on (contacts are per server); the primary one unless picked.
    @State private var creatingOn: AppModel?
    @State private var showArchived = false

    /// The accounts whose server has chats (the tab shows when one has).
    private var chatAccounts: [AppModel] { accounts.active.filter(\.social.features.chats) }

    private func loadAll() async {
        let list = chatAccounts
        await withTaskGroup(of: Void.self) { g in for m in list { g.addTask { await m.social.loadConversations() } } }
    }

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.chatsPath) {
            List {
                if !chatAccounts.contains(where: \.social.conversationsLoaded) {
                    if let error = chatAccounts.lazy.compactMap(\.social.conversationsError).first {
                        ErrorRetryView(message: error) { Task { await loadAll() } }
                            .listRowBackground(Color.clear)
                    } else {
                        LoadingView().listRowBackground(Color.clear)
                    }
                } else if accounts.conversations.isEmpty {
                    EmptyStateView(systemImage: "bubble.left.and.bubble.right", title: L("chat.emptyTitle"), message: L("chat.list.emptyBody")) {
                        Button(L("chat.newChat")) { creating = true }.buttonStyle(.kf(.primary, large: true))
                    }
                    .listRowBackground(Color.clear)
                } else {
                    // Every account's chats by last message (docs/spec/multi-server.md §2.2); a chat lives on one server.
                    ForEach(accounts.conversations, id: \.value.id) { owned in
                        let c = owned.value, m = owned.model
                        NavigationLink(value: ChatRoute(id: c.id, account: m.accountId)) {
                            VStack(alignment: .leading, spacing: 4) {
                                ChatRow(conversation: c)
                                ServerChip(model: m).padding(.leading, 60)
                            }
                        }
                            .environment(m)
                            .listRowBackground(Palette.surface)
                            .swipeActions(edge: .trailing) {
                                Button { Task { await archive(c, true, on: m) } } label: { Label(L("chat.archive"), systemImage: "archivebox") }
                                    .tint(Palette.textMuted)
                            }
                            .swipeActions(edge: .leading) {
                                if c.muted {
                                    Button { Task { await mute(c, .off, on: m) } } label: { Label(L("chat.muteFor.off"), systemImage: "bell") }
                                } else {
                                    Button { Task { await mute(c, .always, on: m) } } label: { Label(L("chat.mute"), systemImage: "bell.slash") }
                                        .tint(Palette.accent)
                                }
                            }
                    }
                }
                Section {
                    OperatorNote(server: ListText.join(chatAccounts.compactMap { $0.server.map { ServerAddress.display($0.url) } }))
                }
                .listRowBackground(Color.clear)
            }
            .scrollContentBackground(.hidden)
            .refreshable {
                await loadAll()
            }
            .pageBackground()
            .pageTitle(L("chat.title"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showArchived = true } label: { Image(systemName: "archivebox").frame(width: 44, height: 44) }
                        .accessibilityLabel(L("chat.archived"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if chatAccounts.count > 1 {
                        // Contacts are per server: pick where the chat starts.
                        Menu {
                            ForEach(chatAccounts, id: \.accountId) { m in
                                Button(m.serverName) { creatingOn = m; creating = true }
                            }
                        } label: {
                            Image(systemName: "square.and.pencil").frame(width: 44, height: 44)
                        }
                        .accessibilityLabel(L("chat.newChat"))
                    } else {
                        Button { creatingOn = nil; creating = true } label: { Image(systemName: "square.and.pencil").frame(width: 44, height: 44) }
                            .accessibilityLabel(L("chat.newChat"))
                    }
                }
            }
            .socialDestinations()
        }
        .task(id: chatAccounts.map(\.accountId)) { await loadAll() }
        .sheet(isPresented: $creating) {
            let m = creatingOn ?? chatAccounts.first ?? app
            NewChatSheet { c in
                m.social.update(c)
                app.chatsPath.append(ChatRoute(id: c.id, account: m.accountId))
            }
            .environment(m)
        }
        .sheet(isPresented: $showArchived) { ArchivedChatsSheet(accounts: chatAccounts) }
    }

    private func archive(_ c: Conversation, _ on: Bool, on owner: AppModel? = nil) async {
        let m = owner ?? app
        guard let api = m.api else { return }
        do {
            let updated = try await api.setConversationArchived(c.id, on)
            m.social.update(updated)
            if on { m.social.remove(conversation: c.id) }
            app.toasts.show(L(on ? "chat.archivedToast" : "chat.unarchivedToast"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func mute(_ c: Conversation, _ mute: MuteFor, on owner: AppModel? = nil) async {
        let m = owner ?? app
        guard let api = m.api else { return }
        do { m.social.update(try await api.setConversationMute(c.id, mute)) } catch { app.toasts.error(error) }
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
/// Archived chats of every account with chats, each moving back on its own server.
private struct ArchivedChatsSheet: View {
    @Environment(\.dismiss) private var dismiss
    let accounts: [AppModel]
    @State private var lists: [String: [Conversation]] = [:]
    @State private var loaded = false

    private var rows: [(AppModel, Conversation)] {
        AccountMerge.interleave(accounts.map { m in (lists[m.accountId] ?? []).map { (m, $0) } }) { $0.1.lastActivityAt }
    }

    var body: some View {
        NavigationStack {
            List {
                if loaded {
                    let rows = rows
                    if rows.isEmpty {
                        Text(L("chat.noArchived")).foregroundStyle(Palette.textMuted).listRowBackground(Color.clear)
                    }
                    ForEach(rows, id: \.1.id) { m, c in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                ChatRow(conversation: c)
                                ServerChip(model: m).padding(.leading, 60)
                            }
                            if c.readOnly != true {
                                Button(L("chat.unarchive")) { Task { await unarchive(c, on: m) } }.buttonStyle(.kf(.secondary))
                            }
                        }
                        .environment(m)
                        .listRowBackground(Palette.surface)
                    }
                } else {
                    LoadingView().listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(L("chat.archived"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task {
                for m in accounts {
                    lists[m.accountId] = ((try? await m.api?.conversations(archived: true)) ?? []).filter(\.archived)
                }
                loaded = true
            }
        }
    }

    private func unarchive(_ c: Conversation, on m: AppModel) async {
        guard let api = m.api else { return }
        do {
            let updated = try await api.setConversationArchived(c.id, false)
            m.social.update(updated)
            lists[m.accountId]?.removeAll { $0.id == c.id }
        } catch {
            m.toasts.error(error)
        }
    }
}
