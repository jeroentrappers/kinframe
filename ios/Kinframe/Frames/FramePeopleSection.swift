import KinframeKit
import SwiftUI

/// Members and roles, join requests and invite links (web: FramePeople).
@MainActor
@Observable
final class PeopleModel {
    let api: API
    let frameId: String
    private(set) var members: [Member] = []
    private(set) var requests: [JoinRequest] = []
    private(set) var invites: [Invite] = []
    private(set) var loaded = false
    var error: String?

    init(api: API, frameId: String) {
        self.api = api
        self.frameId = frameId
    }

    func load(manage: Bool, invite: Bool) async {
        let id = frameId
        do {
            members = try await api.call { try await $0.listMembers(path: .init(frameId: id)).ok.body.json }
            if manage {
                requests = (try? await api.call { try await $0.listJoinRequests(path: .init(frameId: id)).ok.body.json }) ?? []
            }
            if invite {
                invites = (try? await api.call { try await $0.listInvites(path: .init(frameId: id)).ok.body.json }) ?? []
            }
            loaded = true
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    func decide(_ request: JoinRequest, approve: Bool) async throws {
        let id = frameId, rid = request.id
        _ = try await api.call {
            try await $0.decideJoinRequest(path: .init(frameId: id, requestId: rid, decision: approve ? .approve : .deny)).noContent
        }
        requests.removeAll { $0.id == request.id }
    }

    func update(_ member: Member, role: Member.RolePayloadForUpdate?, permissions: Permissions?) async throws {
        let id = frameId, uid = member.userId
        let body = Operations.UpdateMember.Input.Body.JsonPayload(role: role?.api, permissions: permissions)
        let updated = try await api.call { try await $0.updateMember(path: .init(frameId: id, userId: uid), body: .json(body)).ok.body.json }
        if let i = members.firstIndex(where: { $0.userId == uid }) { members[i] = updated }
    }

    func remove(_ member: Member, removeItems: Bool) async throws {
        let id = frameId, uid = member.userId
        _ = try await api.call { try await $0.removeMember(path: .init(frameId: id, userId: uid), query: .init(removeItems: removeItems)).noContent }
        members.removeAll { $0.userId == uid }
    }

    func createInvite(hours: Int, singleUse: Bool, note: String) async throws -> Invite {
        let id = frameId
        let key = UUID().uuidString
        let note = note.trimmingCharacters(in: .whitespaces)
        let invite = try await api.call {
            try await $0.createInvite(path: .init(frameId: id), headers: .init(idempotencyKey: key),
                                      body: .json(.init(maxUses: singleUse ? 1 : nil, expiresInHours: hours, note: note.isEmpty ? nil : String(note.prefix(80))))).created.body.json
        }
        invites.insert(invite, at: 0)
        return invite
    }

    func revoke(_ invite: Invite) async throws {
        let id = frameId, iid = invite.id
        _ = try await api.call { try await $0.revokeInvite(path: .init(frameId: id, inviteId: iid)).noContent }
        invites.removeAll { $0.id == iid }
    }
}

extension Member {
    enum RolePayloadForUpdate: Hashable {
        case admin, member
        var api: Operations.UpdateMember.Input.Body.JsonPayload.RolePayload {
            self == .admin ? .admin : .member
        }
    }
}

struct FramePeopleSection: View {
    @Environment(AppModel.self) private var app
    let frame: FrameDetail
    @State private var model: PeopleModel
    @State private var editing: Member?
    @State private var inviting = false

    init(api: API, frame: FrameDetail) {
        self.frame = frame
        _model = State(initialValue: PeopleModel(api: api, frameId: frame.id))
    }

    private var summary: FrameSummary { frame.summary }
    private var manage: Bool { summary.isManager }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let error = model.error {
                ErrorRetryView(message: error) { Task { await load() } }
            }
            if !model.requests.isEmpty { requests }
            members
            if summary.can(\.invite) { invites }
        }
        .task { await load() }
        .onLiveEvent(app.events) { e in
            if case .joinRequest(let id, _) = e, id == frame.id { Task { await load() } }
        }
        .sheet(item: $editing) { m in
            MemberSheet(model: model, member: m, viewerRole: summary.role)
        }
        .sheet(isPresented: $inviting) {
            InviteCreateSheet(model: model, frameName: summary.name, approval: frame.value2.inviteRequiresApproval ?? true)
        }
    }

    private func load() async {
        await model.load(manage: manage, invite: summary.can(\.invite))
    }

    private var requests: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("people.requests"))
            ForEach(model.requests) { r in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        AvatarView(name: r.user.displayName, url: r.user.avatarUrl ?? nil, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.user.displayName ?? "?").font(.headline)
                            Text(r.via == .invite ? L("people.viaInvite", ["name": r.invitedByName ?? "?"]) : L("people.viaCode"))
                                .font(.subheadline).foregroundStyle(Palette.textMuted)
                            if let note = r.note, !note.isEmpty { Text("“\(note)”").font(.subheadline).italic() }
                        }
                    }
                    HStack {
                        Button(L("people.approve")) { Task { await decide(r, true) } }.buttonStyle(.kf(.primary))
                        Button(L("people.deny")) { Task { await decide(r, false) } }.buttonStyle(.kf(.ghost))
                    }
                }
                .card(padding: 14)
            }
        }
    }

    private func decide(_ r: JoinRequest, _ approve: Bool) async {
        do {
            try await model.decide(r, approve: approve)
            let name = r.user.displayName ?? "?"
            app.toasts.show(approve ? L("people.approved", ["name": name]) : L("people.denied", ["name": name]))
            if approve { await load() }
        } catch {
            app.toasts.error(error)
        }
    }

    private var members: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("people.members"))
            VStack(spacing: 0) {
                ForEach(Array(model.members.enumerated()), id: \.element.id) { n, m in
                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 64) }
                    Button {
                        if canEdit(m) { editing = m }
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(name: m.displayName, url: m.avatarUrl, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(m.displayName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    if m.userId == app.me?.id { Text("(\(L("people.you")))").font(.subheadline).foregroundStyle(Palette.textMuted) }
                                }
                                Text(m.lastSentAt.map { L("people.lastSent", ["when": Format.relative($0, locale: L10n.locale)]) } ?? L("people.neverSent"))
                                    .font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                            Spacer()
                            RoleBadge(role: m.role)
                            if canEdit(m) { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint) }
                        }
                        .padding(.horizontal, 14)
                        .frame(minHeight: 64)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(canEdit(m) ? L("people.editMember", ["name": m.displayName]) : "")
                }
            }
            .card(padding: 0)
        }
    }

    private func canEdit(_ m: Member) -> Bool {
        guard manage, m.role != .owner, m.userId != app.me?.id else { return false }
        if summary.role == .admin, m.role == .admin { return false }
        return true
    }

    private var invites: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("people.invite"))
            VStack(alignment: .leading, spacing: 12) {
                Text((frame.value2.inviteRequiresApproval ?? true) ? L("people.inviteHintApproval") : L("people.inviteHint"))
                    .font(.subheadline).foregroundStyle(Palette.textMuted)
                Button {
                    inviting = true
                } label: {
                    Label(L("people.create"), systemImage: "link.badge.plus")
                }
                .buttonStyle(.kf(.primary))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card(padding: 16)
            if !model.invites.isEmpty {
                Text(L("people.activeInvites")).font(.subheadline.weight(.semibold)).padding(.top, 4)
                ForEach(model.invites) { inv in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(inv.note?.isEmpty == false ? inv.note! : L("people.inviteLink")).font(.body.weight(.semibold))
                            Text([
                                inv.createdByName.map { L("people.byName", ["name": $0]) },
                                inv.maxUses.map { L("people.usesOf", ["n": inv.uses ?? 0, "max": $0]) } ?? L("people.usesN", ["n": inv.uses ?? 0]),
                                L("people.expiresIn", ["when": Format.relative(inv.expiresAt, locale: L10n.locale)]),
                            ].compactMap { $0 }.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                        Spacer()
                        if let url = URL(string: inv.url) {
                            ShareLink(item: url, subject: Text(L("people.shareTitle", ["name": summary.name])), message: Text(L("people.shareText", ["name": summary.name]))) {
                                Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
                            }
                            .accessibilityLabel(L("people.share"))
                        }
                        Button(role: .destructive) {
                            Task {
                                do {
                                    try await model.revoke(inv)
                                    app.toasts.show(L("people.revoked"))
                                } catch { app.toasts.error(error) }
                            }
                        } label: {
                            Image(systemName: "xmark.circle").frame(width: 44, height: 44)
                        }
                        .foregroundStyle(Palette.danger)
                        .accessibilityLabel(L("people.revoke"))
                    }
                    .card(padding: 12)
                }
            }
        }
    }
}

/// Role, permissions and removal for one member.
private struct MemberSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: PeopleModel
    let member: Member
    let viewerRole: Role
    @State private var role: Member.RolePayloadForUpdate = .member
    @State private var permissions = Permissions()
    @State private var confirmRemove = false
    @State private var saving = false

    private static let keys: [(WritableKeyPath<Permissions, Bool?>, String)] = [
        (\.send, "send"), (\.invite, "invite"), (\.seeLibrary, "see_library"), (\.manageLibrary, "manage_library"), (\.changeSettings, "change_settings"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                if viewerRole == .owner {
                    Section {
                        Picker(L("people.role"), selection: $role) {
                            Text(L("role.member")).tag(Member.RolePayloadForUpdate.member)
                            Text(L("role.admin")).tag(Member.RolePayloadForUpdate.admin)
                        }
                        .pickerStyle(.segmented)
                    } footer: {
                        Text(role == .admin ? L("people.adminHint") : L("people.memberHint"))
                    }
                }
                if role == .member {
                    Section(L("people.permissions")) {
                        ForEach(Self.keys, id: \.1) { key, name in
                            Toggle(isOn: Binding(get: { permissions[keyPath: key] ?? false }, set: { permissions[keyPath: key] = $0 })) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L("perm.\(name)"))
                                    Text(L("perm.\(name)Hint")).font(.footnote).foregroundStyle(Palette.textMuted)
                                }
                            }
                            .tint(Palette.accent)
                        }
                    }
                }
                Section {
                    Button(L("people.remove"), role: .destructive) { confirmRemove = true }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(member.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.save")) { Task { await save() } }.disabled(saving)
                }
            }
            .alert(L("people.removeConfirm", ["name": member.displayName]), isPresented: $confirmRemove) {
                Button(L("people.remove"), role: .destructive) { Task { await remove(false) } }
                Button(L("people.removeItems"), role: .destructive) { Task { await remove(true) } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(L("people.removeBody"))
            }
            .onAppear {
                role = member.role == .admin ? .admin : .member
                permissions = member.permissions
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await model.update(member, role: viewerRole == .owner ? role : nil, permissions: role == .member ? permissions : nil)
            app.toasts.show(L("common.saved"))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }

    private func remove(_ items: Bool) async {
        do {
            try await model.remove(member, removeItems: items)
            app.toasts.show(L("people.removed", ["name": member.displayName]))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Create an invite link and hand it to the share sheet.
private struct InviteCreateSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: PeopleModel
    let frameName: String
    let approval: Bool
    @State private var hours = 168
    @State private var singleUse = true
    @State private var note = ""
    @State private var created: Invite?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                if let created, let url = URL(string: created.url) {
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            Label(L("people.linkReady"), systemImage: "checkmark.circle.fill").foregroundStyle(Palette.accentText).font(.headline)
                            Text(created.url).font(.footnote.monospaced()).textSelection(.enabled).foregroundStyle(Palette.textMuted)
                            ShareLink(item: url, subject: Text(L("people.shareTitle", ["name": frameName])), message: Text(L("people.shareText", ["name": frameName]))) {
                                Label(L("people.share"), systemImage: "square.and.arrow.up")
                            }
                            .buttonStyle(.kf(.primary, fill: true))
                            Button {
                                UIPasteboard.general.url = url
                                app.toasts.show(L("people.copied"))
                            } label: {
                                Label(L("people.copy"), systemImage: "doc.on.doc")
                            }
                            .buttonStyle(.kf(.secondary, fill: true))
                        }
                        .padding(.vertical, 6)
                    }
                } else {
                    Section {
                        Text(approval ? L("people.inviteHintApproval") : L("people.inviteHint")).font(.subheadline).foregroundStyle(Palette.textMuted)
                    }
                    Section(L("people.expires")) {
                        Picker(L("people.expires"), selection: $hours) {
                            Text(L("people.exp1d")).tag(24)
                            Text(L("people.exp7d")).tag(168)
                            Text(L("people.exp30d")).tag(720)
                        }
                        .pickerStyle(.segmented)
                    }
                    Section(L("people.uses")) {
                        Picker(L("people.uses"), selection: $singleUse) {
                            Text(L("people.usesSingle")).tag(true)
                            Text(L("people.usesUnlimited")).tag(false)
                        }
                        .pickerStyle(.inline)
                        .labelsHidden()
                    }
                    Section(L("people.note") + " (" + L("common.optional") + ")") {
                        TextField(L("people.notePlaceholder"), text: $note)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("people.invite"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(created == nil ? L("common.cancel") : L("common.done")) { dismiss() } }
                if created == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("people.create")) { Task { await create() } }.disabled(busy)
                    }
                }
            }
        }
    }

    private func create() async {
        busy = true
        defer { busy = false }
        do {
            created = try await model.createInvite(hours: hours, singleUse: singleUse, note: note)
        } catch {
            app.toasts.error(error)
        }
    }
}
