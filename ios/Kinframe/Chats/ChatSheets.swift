import KinframeKit
import PhotosUI
import SwiftUI

/// Start a chat: one person (a direct chat, the existing one if there is one) or several with a name (a group).
/// Only contacts: people you share a family or a frame with (decision C).
struct NewChatSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var adding: Conversation? = nil
    let done: (Conversation) -> Void
    @State private var contacts: [Person]?
    @State private var chosen: Set<String> = []
    @State private var name = ""
    @State private var busy = false

    private var isGroup: Bool { adding != nil || chosen.count > 1 }
    private var problem: String? {
        if adding != nil { return chosen.isEmpty ? "" : nil }
        if chosen.isEmpty { return "" }
        if chosen.count == 1 { return nil }
        return GroupRules.problem(memberCount: chosen.count, name: name, limit: app.limits.maxGroupMembers)
    }

    var body: some View {
        NavigationStack {
            List {
                if isGroup, adding == nil {
                    Section(L("chat.groupName")) {
                        TextField(L("chat.groupNamePlaceholder"), text: $name)
                            .onChange(of: name) { if name.count > GroupRules.maxName { name = String(name.prefix(GroupRules.maxName)) } }
                    }
                    .listRowBackground(Palette.surface)
                }
                Section {
                    if let contacts {
                        if available(contacts).isEmpty {
                            Text(L("chat.new.noContacts")).foregroundStyle(Palette.textMuted)
                        }
                        ForEach(available(contacts)) { p in
                            Button { toggle(p.person.userId) } label: {
                                HStack(spacing: 12) {
                                    PersonChip(person: p.person, size: 36)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(PersonName.display(p.person)).foregroundStyle(Palette.text)
                                        Text(ListText.join(p.feeds.map(\.feedName) + p.frames.map(\.frameName)))
                                            .font(.caption).foregroundStyle(Palette.textMuted).lineLimit(1)
                                    }
                                    Spacer()
                                    Image(systemName: chosen.contains(p.person.userId) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(chosen.contains(p.person.userId) ? Palette.primary : Palette.textFaint)
                                }
                            }
                            .accessibilityAddTraits(chosen.contains(p.person.userId) ? .isSelected : [])
                        }
                    } else {
                        LoadingView()
                    }
                } header: {
                    Text(L("chat.new.contacts"))
                } footer: {
                    Text(L("chat.new.contactsHint"))
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L(adding == nil ? "chat.newChat" : "chat.addPeople"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L(adding != nil ? "chat.add" : isGroup ? "chat.createGroup" : "chat.startChat")) { Task { await create() } }
                        .fontWeight(.semibold)
                        .disabled(problem != nil || busy)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if let p = problem, !p.isEmpty, chosen.count > 1 {
                    Text(p).font(.footnote).foregroundStyle(Palette.textMuted).padding(8).frame(maxWidth: .infinity).background(.bar)
                }
            }
            .task { contacts = (try? await app.api?.contacts()) ?? [] }
        }
    }

    private func available(_ list: [Person]) -> [Person] {
        let inChat = Set((adding?.members ?? []).map(\.person.userId))
        return list.filter { !$0.blocked && !inChat.contains($0.person.userId) && $0.person.formerMember != true }
    }

    private func toggle(_ id: String) {
        if chosen.contains(id) { chosen.remove(id) } else { chosen.insert(id) }
    }

    private func create() async {
        guard let api = app.api else { return }
        busy = true
        defer { busy = false }
        do {
            let c: Conversation
            if let adding {
                c = try await api.addConversationMembers(adding.id, userIds: Array(chosen))
            } else if chosen.count == 1, let only = chosen.first {
                c = try await api.createConversation(direct: only)
            } else {
                c = try await api.createGroup(name: name.trimmingCharacters(in: .whitespacesAndNewlines), memberIds: Array(chosen))
            }
            dismiss()
            done(c)
        } catch {
            app.toasts.error(error)
        }
    }
}

/// A chat's details: members (add, remove, make admin in groups), name and photo (group admins), mute, archive,
/// leave; for direct chats the person (block); for family and circle chats where they come from.
struct ConversationInfoView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: ConversationModel
    @State private var name = ""
    @State private var adding = false
    @State private var confirmLeave = false
    @State private var photoPick: PhotosPickerItem?

    private var c: Conversation? { model.conversation }

    var body: some View {
        NavigationStack {
            Form {
                if let c {
                    Section {
                        HStack(spacing: 14) {
                            ConversationAvatar(conversation: c, size: 64)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(ChatList.title(c)).font(.headline)
                                Text(ChatList.kindLabel(c)).font(.footnote).foregroundStyle(Palette.textMuted)
                                if let m = MuteText.state(c) { Text(m).font(.footnote).foregroundStyle(Palette.textMuted) }
                            }
                        }
                        if ChatAbilities.canManageGroup(c) {
                            TextField(L("chat.groupName"), text: $name)
                                .onSubmit { Task { await rename() } }
                            PhotosPicker(selection: $photoPick, matching: .images) { Label(L("chat.changePhoto"), systemImage: "photo") }
                            if c.photoUrl != nil {
                                Button(L("chat.removePhoto"), role: .destructive) { Task { await removePhoto() } }
                            }
                        }
                        if c.kind == .family, let feedId = c.feedId {
                            Button(L("chat.openFamily")) { dismiss(); app.openFeed(feedId) }
                        }
                        if c.kind == .direct, let other = c.other, other.formerMember != true {
                            Button(L("chat.viewProfile")) { dismiss(); app.social.personSheet = PersonRoute(userId: other.userId, account: app.accountId) }
                        }
                    }
                    .listRowBackground(Palette.surface)

                    Section(L("chat.notifications")) {
                        Picker(L("chat.mute"), selection: Binding(get: { muteChoice(c) }, set: { m in Task { await mute(m) } })) {
                            Text(L("chat.muteFor.off")).tag(MuteFor.off)
                            ForEach(MuteFor.choices, id: \.self) { m in Text(m.label).tag(m) }
                        }
                        Button(L(c.archived ? "chat.unarchive" : "chat.archive")) { Task { await archive(!c.archived) } }
                    }
                    .listRowBackground(Palette.surface)

                    Section {
                        ForEach(c.members ?? []) { m in
                            HStack(spacing: 12) {
                                PersonChip(person: m.person, size: 32)
                                Text(PersonName.display(m.person, me: app.me?.id, you: true))
                                Spacer()
                                if c.kind == .group, m.role == .admin { Pill(L("role.admin"), foreground: Palette.accentText, background: Palette.accentSoft) }
                            }
                            .contextMenu {
                                if ChatAbilities.canManageGroup(c), m.person.userId != app.me?.id {
                                    Button(L(m.role == .admin ? "chat.info.makeMember" : "chat.makeAdmin")) { Task { await setAdmin(m, m.role != .admin) } }
                                    Button(L("chat.info.remove"), role: .destructive) { Task { await remove(m) } }
                                }
                            }
                        }
                        if ChatAbilities.canAddPeople(c, limit: app.limits.maxGroupMembers) {
                            Button { adding = true } label: { Label(L("chat.addPeople"), systemImage: "person.badge.plus") }
                        }
                    } header: {
                        Text(Lp("chat.info.members", count: c.memberCount))
                    } footer: {
                        if c.kind == .family || c.kind == .circle { Text(L("chat.info.membersFollow")) }
                        else if ChatAbilities.canManageGroup(c) { Text(L("chat.info.membersHint")) }
                    }
                    .listRowBackground(Palette.surface)

                    if ChatAbilities.canLeave(c) {
                        Section {
                            Button(L("chat.leaveGroup"), role: .destructive) { confirmLeave = true }
                        }
                        .listRowBackground(Palette.surface)
                    }
                    Section {
                        OperatorNote(server: app.server.map { ServerAddress.display($0.url) } ?? "")
                    }
                    .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("chat.details"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { Task { await rename(); dismiss() } } } }
            .onAppear { name = c?.name ?? "" }
            .onChange(of: photoPick) { _, item in
                guard let item else { return }
                photoPick = nil
                Task { await setPhoto(item) }
            }
            .sheet(isPresented: $adding) {
                if let c { NewChatSheet(adding: c) { updated in apply(updated) } }
            }
            .confirmationDialog(L("chat.info.leaveConfirm"), isPresented: $confirmLeave, titleVisibility: .visible) {
                Button(L("chat.leaveGroup"), role: .destructive) { Task { await leave() } }
                Button(L("common.cancel"), role: .cancel) {}
            }
        }
    }

    private func muteChoice(_ c: Conversation) -> MuteFor {
        guard c.muted else { return .off }
        return c.mutedUntil == nil ? .always : ._1h
    }

    private func apply(_ updated: Conversation) {
        model.setConversation(updated)
        app.social.update(updated)
    }

    private func rename() async {
        guard let c, ChatAbilities.canManageGroup(c), let api = app.api else { return }
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, t != c.name else { return }
        do { apply(try await api.renameConversation(c.id, name: t)) } catch { app.toasts.error(error) }
    }

    private func setPhoto(_ item: PhotosPickerItem) async {
        guard let c, let api = app.api, let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data), let jpeg = squarePhotoJPEG(image, side: 512) else { return }
        do { apply(try await api.setConversationPhoto(c.id, jpeg: jpeg)) } catch { app.toasts.error(error) }
    }

    private func removePhoto() async {
        guard let c, let api = app.api else { return }
        do {
            try await api.removeConversationPhoto(c.id)
            apply(try await api.conversation(c.id))
        } catch { app.toasts.error(error) }
    }

    private func mute(_ m: MuteFor) async {
        do {
            try await model.setMute(m)
            if let c = model.conversation { app.social.update(c) }
        } catch { app.toasts.error(error) }
    }

    private func archive(_ on: Bool) async {
        guard let c, let api = app.api else { return }
        do {
            let updated = try await api.setConversationArchived(c.id, on)
            model.setConversation(updated)
            if on { app.social.remove(conversation: c.id) } else { app.social.update(updated) }
        } catch { app.toasts.error(error) }
    }

    private func setAdmin(_ m: ConversationMember, _ admin: Bool) async {
        guard let c, let api = app.api else { return }
        do { apply(try await api.setConversationRole(c.id, userId: m.person.userId, admin: admin)) } catch { app.toasts.error(error) }
    }

    private func remove(_ m: ConversationMember) async {
        guard let c, let api = app.api else { return }
        do {
            try await api.removeConversationMember(c.id, userId: m.person.userId)
            apply(try await api.conversation(c.id))
        } catch { app.toasts.error(error) }
    }

    private func leave() async {
        guard let c, let api = app.api, let me = app.me?.id else { return }
        do {
            try await api.removeConversationMember(c.id, userId: me)
            app.social.remove(conversation: c.id)
            dismiss()
            app.chatsPath = NavigationPath()
        } catch { app.toasts.error(error) }
    }
}

/// "Put on a frame": the message's photos and videos go through the normal send (frames, families and a circle),
/// without uploading again. Other people's photos go to frames only (credited to them).
struct PutOnFrameSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let conversationId: String
    let message: Message
    @State private var frameIds: Set<String> = []
    @State private var feedIds: Set<String> = []
    @State private var itemIds: Set<String> = []
    @State private var busy = false

    private var own: Bool { message.author?.userId == app.me?.id }
    private var frames: [FrameSummary] { app.frames.filter { $0.can(\.send) } }
    private var feeds: [FeedSummary] { own ? app.feeds.filter { $0.role != .member } : [] }
    private var items: [Item] { MessageLogic.frameable(message) }

    var body: some View {
        NavigationStack {
            List {
                if items.count > 1 {
                    Section(L("chat.put.photos")) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(items) { item in
                                    Button { if itemIds.contains(item.id) { itemIds.remove(item.id) } else { itemIds.insert(item.id) } } label: {
                                        ItemThumb(item: item).frame(width: 72, height: 72)
                                            .clipShape(RoundedRectangle(cornerRadius: Radius.sm))
                                            .overlay(alignment: .topTrailing) {
                                                Image(systemName: itemIds.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                                    .foregroundStyle(.white).shadow(radius: 2).padding(4)
                                            }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    .listRowBackground(Palette.surface)
                }
                Section(L("nav.frames")) {
                    if frames.isEmpty { Text(L("chat.put.noFrames")).foregroundStyle(Palette.textMuted) }
                    ForEach(frames) { f in check(f.name, on: frameIds.contains(f.id)) { toggle(&frameIds, f.id) } }
                }
                .listRowBackground(Palette.surface)
                if !feeds.isEmpty {
                    Section {
                        ForEach(feeds) { f in check(f.name, on: feedIds.contains(f.id)) { toggle(&feedIds, f.id) } }
                    } header: {
                        Text(L("ios.feeds.title"))
                    }
                    .listRowBackground(Palette.surface)
                } else if !own {
                    Section { Text(L("chat.put.othersNote")).font(.footnote).foregroundStyle(Palette.textMuted) }
                        .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("chat.putOnFrame"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("nav.send")) { Task { await send() } }
                        .fontWeight(.semibold)
                        .disabled(busy || (frameIds.isEmpty && feedIds.isEmpty) || itemIds.isEmpty)
                }
            }
            .onAppear { itemIds = Set(items.map(\.id)) }
        }
    }

    private func check(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(Palette.text)
                Spacer()
                Image(systemName: on ? "checkmark.circle.fill" : "circle").foregroundStyle(on ? Palette.primary : Palette.textFaint)
            }
        }
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func toggle(_ set: inout Set<String>, _ id: String) {
        if set.contains(id) { set.remove(id) } else { set.insert(id) }
    }

    private func send() async {
        guard let api = app.api else { return }
        busy = true
        defer { busy = false }
        do {
            let all = itemIds.count == items.count
            _ = try await api.putMessageOnFrame(conversationId, messageId: message.id, frameIds: Array(frameIds), feedIds: Array(feedIds),
                                                itemIds: all ? nil : items.map(\.id).filter(itemIds.contains))
            app.historyVersion += 1
            app.toasts.show(L("chat.put.done"))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// "Share in chat": drop a post card into one of your chats, with an optional line.
struct ShareInChatSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let postId: String
    @State private var note = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField(L("chat.share.note"), text: $note, axis: .vertical).lineLimit(1...3)
                }
                .listRowBackground(Palette.surface)
                Section(L("chat.share.to")) {
                    if app.social.conversations.isEmpty { Text(L("chat.emptyTitle")).foregroundStyle(Palette.textMuted) }
                    ForEach(app.social.conversations.filter { ChatAbilities.canSend($0) }) { c in
                        Button { Task { await share(to: c) } } label: {
                            HStack(spacing: 12) {
                                ConversationAvatar(conversation: c, size: 36)
                                Text(ChatList.title(c)).foregroundStyle(Palette.text)
                                Spacer()
                                Text(ChatList.kindLabel(c)).font(.caption).foregroundStyle(Palette.textMuted)
                            }
                        }
                        .disabled(busy)
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("social.shareInChat"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } } }
            .task { if !app.social.conversationsLoaded { await app.social.loadConversations() } }
        }
    }

    private func share(to c: Conversation) async {
        guard let api = app.api else { return }
        busy = true
        defer { busy = false }
        let t = note.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try await api.sendMessage(c.id, API.OutgoingMessage(body: t.isEmpty ? nil : t, sharedPostId: postId))
            app.toasts.show(L("chat.share.done", ["chat": ChatList.title(c)]))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}
