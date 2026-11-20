import CoreImage.CIFilterBuiltins
import KinframeKit
import SwiftUI

/// Who is in the feed: join requests (owner and admins), members with their roles, invites (link, QR and code; join
/// and subscribe invites), and leaving the feed.
struct FeedMembersSection: View {
    /// Family › People shows the people; Settings › Leave the family the way out.
    enum Part { case people, leave }

    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    var part: Part = .people
    @State private var editing: FeedMember?
    @State private var inviting = false
    @State private var showing: FeedInvite?
    @State private var confirmLeave = false
    @State private var removeMyPosts = false
    /// Leaving (README §5.21): the leave sheet with photos, comments and frames.
    @State private var leavingWithChoice = false

    private var abilities: FeedAbilities? { model.abilities }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            switch part {
            case .people:
                if !model.requests.isEmpty { requests }
                members
                if abilities?.canSeeInvites == true { invites }
                if app.leavingEnabled, abilities?.isAdmin == true { TakenAwayCard(api: model.api, scope: .feed(model.id)) }
            case .leave:
                leave
            }
        }
        .sheet(item: $editing) { m in
            FeedMemberSheet(model: model, member: m)
        }
        .sheet(isPresented: $leavingWithChoice) {
            LeaveFeedSheet(api: model.api, feedId: model.id, name: model.feed?.name ?? "") { line, leftFrames in
                app.removeFeed(model.id)
                leftFrames.forEach(app.removeFrame)
                app.familyPath = NavigationPath()
                app.toasts.show([L("ios.feeds.left", ["name": model.feed?.name ?? ""]), line].compactMap { $0 }.joined(separator: " "))
            }
        }
        .sheet(isPresented: $inviting) {
            FeedInviteCreateSheet(model: model) { showing = $0 }
        }
        .sheet(item: $showing) { invite in
            FeedInviteShareSheet(invite: invite, feedName: model.feed?.name ?? "")
        }
        .confirmationDialog(L("ios.feeds.leaveConfirm", ["name": model.feed?.name ?? ""]), isPresented: $confirmLeave, titleVisibility: .visible) {
            Button(L("ios.feeds.leave"), role: .destructive) { Task { await leaveNow() } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(removeMyPosts ? L("ios.feeds.leaveBodyPosts") : L("ios.feeds.leaveBody"))
        }
    }

    // MARK: Requests

    private var requests: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("people.requests"))
            ForEach(model.requests) { r in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        AvatarView(name: r.user.displayName, url: r.user.avatarUrl ?? nil, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.user.displayName ?? "?").font(.headline)
                            JoinRequestOriginView(request: r)
                            if let note = r.note, !note.isEmpty { Text("“\(note)”").font(.subheadline).italic() }
                        }
                    }
                    HStack {
                        Button(L("people.approve")) { Task { await decide(r, true) } }.buttonStyle(.kf(.primary))
                        Button(L("people.deny")) { Task { await decide(r, false) } }.buttonStyle(.kf(.ghost))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card(padding: 14)
            }
        }
    }

    private func decide(_ r: JoinRequest, _ approve: Bool) async {
        do {
            try await model.decide(r, approve: approve)
            let name = r.user.displayName ?? "?"
            app.toasts.show(approve ? L("ios.feeds.approvedMember", ["name": name]) : L("people.denied", ["name": name]))
        } catch {
            app.toasts.error(error)
        }
    }

    // MARK: Members

    private var members: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("ios.feeds.tabMembers"))
            VStack(spacing: 0) {
                ForEach(Array(model.members.enumerated()), id: \.element.id) { n, m in
                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 64) }
                    let editable = canEdit(m)
                    Button {
                        if editable { editing = m }
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(name: m.displayName, url: m.avatarUrl, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(m.displayName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    if m.userId == app.me?.id { Text("(\(L("people.you")))").font(.subheadline).foregroundStyle(Palette.textMuted) }
                                }
                                Text(m.lastPostedAt.map { L("ios.feeds.lastPosted", ["when": Format.relative($0, locale: L10n.locale)]) } ?? L("ios.feeds.neverPosted"))
                                    .font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                            Spacer()
                            FeedRoleBadge(role: m.role)
                            if editable { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint) }
                        }
                        .padding(.horizontal, 14)
                        .frame(minHeight: 64)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(editable ? L("people.editMember", ["name": m.displayName]) : "")
                }
            }
            .card(padding: 0)
            Text(L(app.social.features.social ? "social.role.rolesHint" : "ios.feeds.rolesHint")).font(.footnote).foregroundStyle(Palette.textMuted)
        }
    }

    private func canEdit(_ m: FeedMember) -> Bool {
        abilities?.canEdit(m.role, isMe: m.userId == app.me?.id) ?? false
    }

    // MARK: Invites

    private var invites: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("people.invite"))
            VStack(alignment: .leading, spacing: 12) {
                Text(model.feed?.value2.inviteRequiresApproval ?? true ? L("ios.feeds.inviteHintApproval") : L("ios.feeds.inviteHint"))
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
                        Button { showing = inv } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    Text(inv.note?.isEmpty == false ? inv.note! : L(inv.purpose == .join ? "ios.feeds.inviteJoin" : "ios.feeds.inviteSubscribe"))
                                        .font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    if inv.note?.isEmpty == false {
                                        Pill(L(inv.purpose == .join ? "ios.feeds.inviteJoinShort" : "ios.feeds.inviteSubscribeShort"))
                                    }
                                    // Frames on other servers may use this link (README §5.22).
                                    if inv.allowRemote == true {
                                        Pill(L("remote.inviteAllowsRemote"), systemImage: "globe")
                                    }
                                }
                                Text([
                                    CodeEntry.displayFeedCode(inv.code),
                                    inv.createdByName.map { L("people.byName", ["name": $0]) },
                                    inv.maxUses.map { L("people.usesOf", ["n": inv.uses ?? 0, "max": $0]) } ?? L("people.usesN", ["n": inv.uses ?? 0]),
                                    L("people.expiresIn", ["when": Format.relative(inv.expiresAt, locale: L10n.locale)]),
                                ].compactMap { $0 }.joined(separator: " · "))
                                .font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint(L("ios.feeds.inviteShowQr"))
                        if let url = URL(string: inv.url) {
                            ShareLink(item: url, subject: Text(shareTitle(inv)), message: Text(shareText(inv))) {
                                Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
                            }
                            .accessibilityLabel(L("people.share"))
                        }
                        Button(role: .destructive) {
                            let before = model.invites
                            app.toasts.deferred(L("people.revoked"), apply: {
                                withAnimation { model.hide(invite: inv) }
                            }, rollback: {
                                withAnimation { model.restore(invites: before) }
                            }, commit: {
                                try await model.revoke(inv)
                            })
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

    private func shareTitle(_ inv: FeedInvite) -> String {
        let name = model.feed?.name ?? ""
        return inv.purpose == .join ? L("ios.feeds.shareJoinTitle", ["name": name]) : L("ios.feeds.shareSubscribeTitle", ["name": name])
    }

    private func shareText(_ inv: FeedInvite) -> String {
        let name = model.feed?.name ?? ""
        let code = CodeEntry.displayFeedCode(inv.code)
        return inv.purpose == .join ? L("ios.feeds.shareJoinText", ["name": name, "code": code]) : L("ios.feeds.shareSubscribeText", ["name": name, "code": code])
    }

    // MARK: Leave

    @ViewBuilder
    private var leave: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("ios.feeds.leave"))
            if abilities?.isOwner == true {
                Text(L("ios.feeds.ownerLeaveHint")).font(.subheadline).foregroundStyle(Palette.textMuted)
            } else {
                if !app.leavingEnabled { Toggle(L("ios.feeds.removeMyPosts"), isOn: $removeMyPosts).tint(Palette.danger) }
                Button(role: .destructive) { if app.leavingEnabled { leavingWithChoice = true } else { confirmLeave = true } } label: {
                    Label(L("ios.feeds.leave"), systemImage: "rectangle.portrait.and.arrow.right")
                }
                .buttonStyle(.kf(.secondary))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(padding: 16)
    }

    private func leaveNow() async {
        do {
            try await model.leave(removeMyPosts: removeMyPosts)
            app.removeFeed(model.id)
            app.familyPath = NavigationPath()
            app.toasts.show(L("ios.feeds.left", ["name": model.feed?.name ?? ""]))
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Role and removal for one member; the owner can also hand the feed over.
private struct FeedMemberSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: FeedDetailModel
    let member: FeedMember
    @State private var role: FeedRole = .poster
    @State private var removePosts = false
    @State private var confirmRemove = false
    @State private var confirmTransfer = false
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        AvatarView(name: member.displayName, url: member.avatarUrl, size: 48)
                        VStack(alignment: .leading) {
                            Text(member.displayName).font(.headline)
                            if let joined = member.joinedAt {
                                Text(L("ios.feeds.joined", ["when": Format.relative(joined, locale: L10n.locale)])).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                        }
                    }
                }
                .listRowBackground(Palette.surface)
                Section {
                    Picker(L("people.role"), selection: $role) {
                        // Family social adds `member`: follows along, comments and chats, doesn't post photos.
                        if app.social.features.social || member.role == .member {
                            Text(L("social.role.member")).tag(FeedRole.member)
                        }
                        Text(L("ios.feeds.rolePoster")).tag(FeedRole.poster)
                        if model.abilities?.isOwner == true || member.role == .admin {
                            Text(L("role.admin")).tag(FeedRole.admin)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(L("people.role"))
                } footer: {
                    Text(role == .admin ? L("ios.feeds.adminHint") : role == .member ? L("social.role.memberHint") : L("ios.feeds.posterHint"))
                }
                .listRowBackground(Palette.surface)
                if model.abilities?.isOwner == true {
                    Section {
                        Button(L("people.makeOwner")) { confirmTransfer = true }
                    } footer: {
                        Text(L("ios.feeds.transferHint", ["name": member.displayName]))
                    }
                    .listRowBackground(Palette.surface)
                }
                Section {
                    Toggle(L("ios.feeds.removePosts"), isOn: $removePosts).tint(Palette.danger)
                    Button(L("ios.feeds.removeMember"), role: .destructive) { confirmRemove = true }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(member.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.save")) { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(busy || role == member.role)
                }
            }
            .confirmationDialog(L("people.removeConfirm", ["name": member.displayName]), isPresented: $confirmRemove, titleVisibility: .visible) {
                Button(L("ios.feeds.removeMember"), role: .destructive) { Task { await remove() } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(removePosts ? L("ios.feeds.removeBodyPosts") : L("ios.feeds.removeBody"))
            }
            .confirmationDialog(L("ios.feeds.transferConfirm", ["name": member.displayName]), isPresented: $confirmTransfer, titleVisibility: .visible) {
                Button(L("people.makeOwner")) { Task { await transfer() } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(L("ios.feeds.transferBody"))
            }
        }
        .onAppear { role = member.role }
    }

    private func save() async {
        busy = true
        defer { busy = false }
        do {
            try await model.setRole(member, role: role)
            app.toasts.show(L("common.saved"))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }

    private func remove() async {
        busy = true
        defer { busy = false }
        do {
            try await model.remove(member: member, removePosts: removePosts)
            app.toasts.show(L("people.removed", ["name": member.displayName]))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }

    private func transfer() async {
        busy = true
        defer { busy = false }
        do {
            try await model.transfer(to: member)
            if let f = model.feed { app.updateFeed(f.summary) }
            app.toasts.show(L("ios.feeds.transferred", ["name": member.displayName]))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// New invite: to join the feed as a poster, or (admins) to subscribe a frame without joining.
private struct FeedInviteCreateSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: FeedDetailModel
    let created: (FeedInvite) -> Void
    @State private var purpose: FeedInvitePurpose = .join
    @State private var hours = 168
    @State private var singleUse = true
    @State private var note = ""
    @State private var busy = false
    /// Frames on other servers may use this link (README §5.22).
    @State private var allowRemote = false

    var body: some View {
        NavigationStack {
            Form {
                if model.abilities?.canCreateSubscribeInvites == true {
                    Section {
                        Picker(L("ios.feeds.invitePurpose"), selection: $purpose) {
                            Text(L("ios.feeds.inviteJoinShort")).tag(FeedInvitePurpose.join)
                            Text(L("ios.feeds.inviteSubscribeShort")).tag(FeedInvitePurpose.subscribe)
                        }
                        .pickerStyle(.segmented)
                    } footer: {
                        Text(purpose == .join ? (model.feed?.value2.inviteRequiresApproval ?? true ? L("ios.feeds.inviteHintApproval") : L("ios.feeds.inviteHint"))
                                              : L("ios.feeds.inviteSubscribeHint"))
                    }
                }
                if purpose == .subscribe, app.remoteFamiliesEnabled, model.feed?.value2.allowRemoteFrames == true {
                    Section {
                        Toggle(L("remote.inviteAllow"), isOn: $allowRemote).tint(Palette.accent)
                    } footer: {
                        Text(L("remote.inviteAllowHint"))
                    }
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
                    TextField(L("ios.feeds.notePlaceholder"), text: $note)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("people.invite"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("people.create")) { Task { await create() } }.disabled(busy)
                }
            }
        }
    }

    private func create() async {
        busy = true
        defer { busy = false }
        do {
            let invite = try await model.createInvite(purpose: purpose, hours: hours, singleUse: singleUse, note: note,
                                                      allowRemote: allowRemote)
            dismiss()
            created(invite)
        } catch {
            app.toasts.error(error)
        }
    }
}

/// An invite to hand over: the QR (for a phone next to you), the link and the short code (for the "Enter a code"
/// field), with share and copy.
struct FeedInviteShareSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let invite: FeedInvite
    let feedName: String

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Label(L("people.linkReady"), systemImage: "checkmark.circle.fill").foregroundStyle(Palette.accentText).font(.headline)
                    Text(invite.purpose == .join ? L("ios.feeds.inviteJoinExplain", ["name": feedName]) : L("ios.feeds.inviteSubscribeExplain", ["name": feedName]))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Palette.textMuted)
                    if let image = QRCode.image(invite.url) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 240)
                            .padding(14)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                            .accessibilityLabel(L("ios.feeds.qrLabel"))
                    }
                    VStack(spacing: 4) {
                        Text(L("ios.feeds.code")).font(.caption.weight(.semibold)).textCase(.uppercase).foregroundStyle(Palette.textFaint)
                        Text(CodeEntry.displayFeedCode(invite.code))
                            .font(.system(size: 32, weight: .semibold, design: .monospaced))
                            .textSelection(.enabled)
                            .accessibilityLabel(CodeEntry.displayFeedCode(invite.code).map { String($0) }.joined(separator: " "))
                    }
                    Text(invite.url).font(.footnote.monospaced()).textSelection(.enabled).foregroundStyle(Palette.textMuted).multilineTextAlignment(.center)
                    if let url = URL(string: invite.url) {
                        ShareLink(item: url, subject: Text(invite.purpose == .join ? L("ios.feeds.shareJoinTitle", ["name": feedName]) : L("ios.feeds.shareSubscribeTitle", ["name": feedName])),
                                  message: Text(invite.purpose == .join
                                      ? L("ios.feeds.shareJoinText", ["name": feedName, "code": CodeEntry.displayFeedCode(invite.code)])
                                      : L("ios.feeds.shareSubscribeText", ["name": feedName, "code": CodeEntry.displayFeedCode(invite.code)]))) {
                            Label(L("people.share"), systemImage: "square.and.arrow.up")
                        }
                        .buttonStyle(.kf(.primary, fill: true))
                    }
                    HStack {
                        Button {
                            UIPasteboard.general.string = invite.url
                            app.toasts.show(L("people.copied"))
                        } label: {
                            Label(L("people.copy"), systemImage: "doc.on.doc")
                        }
                        .buttonStyle(.kf(.secondary, fill: true))
                        Button {
                            UIPasteboard.general.string = CodeEntry.displayFeedCode(invite.code)
                            app.toasts.show(L("ios.feeds.codeCopied"))
                        } label: {
                            Label(L("ios.feeds.copyCode"), systemImage: "number")
                        }
                        .buttonStyle(.kf(.secondary, fill: true))
                    }
                }
                .padding(20)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .navigationTitle(invite.purpose == .join ? L("ios.feeds.inviteJoin") : L("ios.feeds.inviteSubscribe"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
    }
}

/// A QR code for a link (Core Image), crisp at any size.
enum QRCode {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
