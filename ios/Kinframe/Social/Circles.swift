import KinframeKit
import SwiftUI

/// A family's circles (README §5.18): smaller groups with their own chat, and an audience for posts. Shown as a
/// section of the family page when the server has `social`.
struct CirclesSection: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var circles: [FamilyCircle]?
    @State private var creating = false

    private var feed: Feed? { model.feed }
    private var canCreate: Bool {
        guard let feed else { return false }
        return CircleLogic.canCreate(feedRole: feed.role, membersCanCreate: feed.value2.membersCanCreateCircles ?? true)
            && (circles?.count ?? 0) < app.limits.maxCirclesPerFeed
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("social.circle.lede")).font(.subheadline).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            if let circles {
                if circles.isEmpty {
                    EmptyStateView(systemImage: "person.2.circle", title: L("social.circle.emptyTitle"), message: L("social.circle.emptyBody"))
                        .card(padding: 8)
                }
                VStack(spacing: 0) {
                    ForEach(Array(circles.enumerated()), id: \.element.id) { n, c in
                        if n > 0 { Divider().overlay(Palette.border) }
                        NavigationLink(value: CircleRoute(feedId: model.id, circleId: c.id, account: app.accountId)) {
                            HStack(spacing: 12) {
                                Image(systemName: "person.2.circle").font(.title3).foregroundStyle(Palette.accentText)
                                    .frame(width: 40, height: 40).background(Circle().fill(Palette.accentSoft))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    Text(Lp("social.circle.memberCount", count: c.memberCount) + (c.myRole == nil ? " · " + L("social.circle.notIn") : ""))
                                        .font(.footnote).foregroundStyle(Palette.textMuted)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint)
                            }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 60)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .card(padding: 0)
                if canCreate {
                    Button { creating = true } label: { Label(L("social.circle.new"), systemImage: "plus") }.buttonStyle(.kf(.secondary))
                }
            } else {
                LoadingView()
            }
        }
        .task { await load() }
        .onLiveEvent(app.events) { e in
            if case .social(.circleUpdated(let f, _)) = e, f == model.id { Task { await load() } }
            if case .social(.circleRemoved(let f, _)) = e, f == model.id { Task { await load() } }
        }
        .sheet(isPresented: $creating) {
            CircleEditSheet(feedId: model.id, members: model.members, existing: circles ?? []) { c in
                circles = (circles ?? []) + [c]
                app.social.setCircles(circles ?? [], feedId: model.id)
            }
        }
    }

    private func load() async {
        circles = await app.social.circles(of: model.id, reload: true)
    }
}

/// New circle: a name and the family members in it (you're in it and its admin).
struct CircleEditSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let feedId: String
    let members: [FeedMember]
    let existing: [FamilyCircle]
    let created: (FamilyCircle) -> Void
    @State private var name = ""
    @State private var chosen: Set<String> = []
    @State private var busy = false

    private var problem: String? { CircleLogic.nameProblem(name, existing: existing) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("social.circle.namePlaceholder"), text: $name)
                        .onChange(of: name) { if name.count > CircleLogic.maxName { name = String(name.prefix(CircleLogic.maxName)) } }
                } header: {
                    Text(L("social.circle.name"))
                } footer: {
                    if !name.isEmpty, let p = problem { Text(p) }
                }
                .listRowBackground(Palette.surface)
                Section(L("social.circle.members")) {
                    ForEach(members.filter { $0.userId != app.me?.id }) { m in
                        Button {
                            if chosen.contains(m.userId) { chosen.remove(m.userId) } else { chosen.insert(m.userId) }
                        } label: {
                            HStack(spacing: 12) {
                                AvatarView(name: m.displayName, url: m.avatarUrl, size: 32)
                                Text(m.displayName).foregroundStyle(Palette.text)
                                Spacer()
                                Image(systemName: chosen.contains(m.userId) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(chosen.contains(m.userId) ? Palette.primary : Palette.textFaint)
                            }
                        }
                        .accessibilityAddTraits(chosen.contains(m.userId) ? .isSelected : [])
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("social.circle.new"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("ios.feeds.create")) { Task { await create() } }.fontWeight(.semibold).disabled(problem != nil || busy)
                }
            }
        }
    }

    private func create() async {
        guard let api = app.api else { return }
        busy = true
        defer { busy = false }
        do {
            let c = try await api.createCircle(feedId, name: name.trimmingCharacters(in: .whitespacesAndNewlines), memberIds: Array(chosen))
            dismiss()
            created(c)
        } catch {
            app.toasts.error(error)
        }
    }
}

/// One circle: its members (add, remove, make admin), the frames its posts reach (extra frames), rename, its chat,
/// leave and delete.
struct CircleDetailView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let route: CircleRoute
    @State private var circle: FamilyCircle?
    @State private var members: [FeedMember] = []
    @State private var subscriptions: [FeedSubscription] = []
    @State private var name = ""
    @State private var adding = false
    @State private var confirmDelete = false
    @State private var error: String?

    private var feedRole: FeedRole? { app.feedRole(route.feedId) }
    private var canManage: Bool { circle.map { CircleLogic.canManage($0, feedRole: feedRole) } ?? false }

    var body: some View {
        Group {
            if let c = circle {
                Form {
                    Section {
                        if canManage {
                            TextField(L("social.circle.name"), text: $name).onSubmit { Task { await rename() } }
                        } else {
                            Text(c.name).font(.headline)
                        }
                        if let conv = c.conversationId, app.social.features.chats, c.myRole != nil {
                            Button { app.openChat(conv) } label: { Label(L("social.circle.chat"), systemImage: "bubble.left.and.bubble.right") }
                        }
                    }
                    .listRowBackground(Palette.surface)
                    Section {
                        ForEach(c.members) { m in
                            HStack(spacing: 12) {
                                AvatarView(name: m.displayName, url: m.avatarUrl, size: 32)
                                Text(m.userId == app.me?.id ? L("social.you") : m.displayName)
                                Spacer()
                                if m.role == .admin { Pill(L("role.admin"), foreground: Palette.accentText, background: Palette.accentSoft) }
                            }
                            .contextMenu {
                                if canManage, m.userId != app.me?.id {
                                    Button(L(m.role == .admin ? "chat.info.makeMember" : "chat.info.makeAdmin")) { Task { await setAdmin(m, m.role != .admin) } }
                                    Button(L("chat.info.remove"), role: .destructive) { Task { await remove(m.userId) } }
                                }
                            }
                        }
                        if canManage {
                            Button { adding = true } label: { Label(L("social.circle.add"), systemImage: "person.badge.plus") }
                        }
                    } header: {
                        Text(Lp("social.circle.memberCount", count: c.memberCount))
                    } footer: {
                        if canManage { Text(L("social.circle.membersHint")) }
                    }
                    .listRowBackground(Palette.surface)
                    Section {
                        ForEach(c.framesReached ?? [], id: \.frameId) { f in Label(f.frameName, systemImage: "photo.on.rectangle") }
                        if canManage {
                            ForEach(subscriptions.filter { $0.status == .active }) { s in
                                let on = (c.frameIds ?? []).contains(s.frame.id)
                                Toggle(s.frame.name, isOn: Binding(get: { on }, set: { new in Task { await setFrame(s.frame.id, new) } }))
                                    .tint(Palette.accent)
                            }
                        }
                    } header: {
                        Text(L("social.circle.frames"))
                    } footer: {
                        Text(L("social.circle.framesHint"))
                    }
                    .listRowBackground(Palette.surface)
                    Section {
                        if c.myRole != nil {
                            Button(L("social.circle.leave"), role: .destructive) { Task { await leave() } }
                        }
                        if canManage {
                            Button(L("social.circle.delete"), role: .destructive) { confirmDelete = true }
                        }
                    }
                    .listRowBackground(Palette.surface)
                }
                .scrollContentBackground(.hidden)
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding(16)
            } else {
                LoadingView()
            }
        }
        .pageBackground()
        .navigationTitle(circle?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .sheet(isPresented: $adding) {
            CircleAddSheet(members: members.filter { m in !(circle?.members.contains { $0.userId == m.userId } ?? false) }) { ids in
                Task { await add(ids) }
            }
        }
        .confirmationDialog(L("social.circle.deleteConfirm", ["name": circle?.name ?? ""]), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("social.circle.delete"), role: .destructive) { Task { await delete() } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("social.circle.deleteBody"))
        }
    }

    private func load() async {
        guard let api = app.api else { return }
        do {
            let c = try await api.circle(route.feedId, circleId: route.circleId)
            circle = c
            name = c.name
            error = nil
            if CircleLogic.canManage(c, feedRole: feedRole) {
                members = (try? await api.feedMembers(route.feedId)) ?? []
                subscriptions = (try? await api.feedSubscriptions(route.feedId)) ?? []
            }
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func apply(_ c: FamilyCircle) {
        circle = c
        var list = app.social.circles[route.feedId] ?? []
        if let i = list.firstIndex(where: { $0.id == c.id }) { list[i] = c } else { list.append(c) }
        app.social.setCircles(list, feedId: route.feedId)
    }

    private func rename() async {
        guard let api = app.api, let c = circle, name != c.name else { return }
        if let p = CircleLogic.nameProblem(name, existing: app.social.circles[route.feedId] ?? [], editing: c.id) {
            app.toasts.show(p, isError: true)
            name = c.name
            return
        }
        do { apply(try await api.updateCircle(route.feedId, circleId: c.id, name: name.trimmingCharacters(in: .whitespacesAndNewlines))) } catch { app.toasts.error(error) }
    }

    private func setFrame(_ frameId: String, _ on: Bool) async {
        guard let api = app.api, let c = circle else { return }
        var ids = Set(c.frameIds ?? [])
        if on { ids.insert(frameId) } else { ids.remove(frameId) }
        do { apply(try await api.updateCircle(route.feedId, circleId: c.id, frameIds: Array(ids))) } catch { app.toasts.error(error) }
    }

    private func add(_ ids: [String]) async {
        guard let api = app.api, let c = circle, !ids.isEmpty else { return }
        do { apply(try await api.addCircleMembers(route.feedId, circleId: c.id, userIds: ids)) } catch { app.toasts.error(error) }
    }

    private func setAdmin(_ m: CircleMember, _ admin: Bool) async {
        guard let api = app.api, let c = circle else { return }
        do { apply(try await api.setCircleRole(route.feedId, circleId: c.id, userId: m.userId, admin: admin)) } catch { app.toasts.error(error) }
    }

    private func remove(_ userId: String) async {
        guard let api = app.api, let c = circle else { return }
        do {
            try await api.removeCircleMember(route.feedId, circleId: c.id, userId: userId)
            await load()
        } catch { app.toasts.error(error) }
    }

    private func leave() async {
        guard let me = app.me?.id else { return }
        await remove(me)
        _ = await app.social.circles(of: route.feedId, reload: true)
        dismiss()
    }

    private func delete() async {
        guard let api = app.api, let c = circle else { return }
        do {
            try await api.deleteCircle(route.feedId, circleId: c.id)
            _ = await app.social.circles(of: route.feedId, reload: true)
            dismiss()
        } catch { app.toasts.error(error) }
    }
}

private struct CircleAddSheet: View {
    @Environment(\.dismiss) private var dismiss
    let members: [FeedMember]
    let done: ([String]) -> Void
    @State private var chosen: Set<String> = []

    var body: some View {
        NavigationStack {
            List(members) { m in
                Button {
                    if chosen.contains(m.userId) { chosen.remove(m.userId) } else { chosen.insert(m.userId) }
                } label: {
                    HStack(spacing: 12) {
                        AvatarView(name: m.displayName, url: m.avatarUrl, size: 32)
                        Text(m.displayName).foregroundStyle(Palette.text)
                        Spacer()
                        Image(systemName: chosen.contains(m.userId) ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(chosen.contains(m.userId) ? Palette.primary : Palette.textFaint)
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("social.circle.add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("chat.info.add")) { done(Array(chosen)); dismiss() }.disabled(chosen.isEmpty)
                }
            }
        }
    }
}
