import KinframeKit
import SwiftUI

/// Owner and admins: the feed's name, its cover (one of its own photos), who gets in and what members see; the owner
/// can delete the feed (it disappears from every frame).
struct FeedSettingsSection: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var name = ""
    @State private var invitesNeedApproval = true
    @State private var autoApprove = false
    @State private var membersSeeReactions = false
    @State private var postersCanInvite = false
    @State private var postersCanAddBirthdays = false
    @State private var saving = false
    @State private var pickingCover = false
    @State private var confirmDelete = false
    @State private var typedName = ""
    @State private var addingSource = false

    private var feed: Feed? { model.feed }

    private var dirty: Bool {
        guard let f = feed else { return false }
        return name.trimmingCharacters(in: .whitespacesAndNewlines) != f.name
            || invitesNeedApproval != (f.value2.inviteRequiresApproval ?? true)
            || autoApprove != (f.value2.autoApproveSubscriptions ?? false)
            || membersSeeReactions != (f.value2.membersSeeReactions ?? false)
            || postersCanInvite != (f.value2.postersCanInvite ?? false)
            || postersCanAddBirthdays != (f.value2.postersCanAddBirthdays ?? false)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if PhotoSourceFeatures(app.discovery).sources, let feed {
                // Photo sources post into the feed (owner and admins, docs/spec/photo-sources.md §4.2).
                TargetPhotoSources(api: model.api, target: .feed(feed.id), targetName: feed.name, adding: $addingSource) {
                    Task { await model.loadPosts() }
                }
            }
            // Owner and admins (this section is): mail and Telegram chats that post to the feed.
            if ChannelFeatures(app.discovery).email, let feed {
                EmailInboxCard(api: model.api, target: .feed(feed.id), targetName: feed.name)
            }
            if ChannelFeatures(app.discovery).telegram, let feed {
                TelegramCard(api: model.api, target: .feed(feed.id), targetName: feed.name)
            }
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(L("ios.feeds.name"))
                TextField(L("ios.feeds.namePlaceholder"), text: $name)
                    .textFieldStyle(KFFieldStyle())
                    .onChange(of: name) { if name.count > 40 { name = String(name.prefix(40)) } }
                HStack(spacing: 14) {
                    FeedCover(url: feed?.summary.coverUrl, name: feed?.name ?? "", size: 64)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("ios.feeds.cover")).font(.subheadline.weight(.semibold))
                        HStack {
                            Button(L("ios.feeds.coverChoose")) { pickingCover = true }
                                .buttonStyle(.kf(.secondary))
                                .disabled(model.posts.isEmpty)
                            if feed?.value2.coverItemId != nil {
                                Button(L("ios.feeds.coverRemove")) { Task { await setCover(nil) } }.buttonStyle(.kf(.ghost))
                            }
                        }
                        if model.posts.isEmpty {
                            Text(L("ios.feeds.coverHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                }
            }
            .card(padding: 16)

            group(L("ios.feeds.settingsMembers")) {
                toggle($invitesNeedApproval, L("ios.feeds.setInviteApproval"), L("ios.feeds.setInviteApprovalHint"))
                Divider().overlay(Palette.border)
                toggle($postersCanInvite, L("ios.feeds.setPostersInvite"), L("ios.feeds.setPostersInviteHint"))
                Divider().overlay(Palette.border)
                toggle($membersSeeReactions, L("ios.feeds.setSeeReactions"),
                       L(app.social.features.social ? "social.family.seeReactionsHint" : "ios.feeds.setSeeReactionsHint"))
            }
            group(L("ios.feeds.settingsFrames")) {
                toggle($autoApprove, L("ios.feeds.setAutoApprove"), L("ios.feeds.setAutoApproveHint"))
            }
            // Frames on other servers and blocked servers (Remote/RemoteFamilySettings.swift, README §5.22).
            if app.remoteFamiliesEnabled { RemoteFamilySettings(model: model) }
            if app.birthdaysEnabled {
                group(L("ios.bday.settingsTitle")) {
                    toggle($postersCanAddBirthdays, L("ios.bday.setPosters"), L("ios.bday.setPostersHint"))
                }
            }

            // Family social and encouragement settings save on change (Social/FamilySocial.swift).
            FamilySocialSettings(model: model)

            if feed?.role == .owner { danger }
        }
        .safeAreaInset(edge: .bottom) {
            if dirty {
                Button {
                    Task { await save() }
                } label: {
                    HStack { if saving { ProgressView().tint(Palette.onPrimary) }; Text(L("common.save")) }
                }
                .buttonStyle(.kf(.primary, large: true, fill: true))
                .disabled(saving || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .padding(.vertical, 8)
                .background(.bar)
            }
        }
        .onAppear(perform: reset)
        .onChange(of: feed) { reset() }
        .sheet(isPresented: $pickingCover) {
            CoverPicker(posts: model.posts, current: feed?.value2.coverItemId) { itemId in
                Task { await setCover(itemId) }
            }
        }
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title)
            VStack(spacing: 0) { content() }.card(padding: 0)
        }
    }

    private func toggle(_ value: Binding<Bool>, _ title: String, _ hint: String) -> some View {
        Toggle(isOn: value) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Palette.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private var danger: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("ios.feeds.delete"))
            Text(L("ios.feeds.deleteBody")).font(.subheadline).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            TextField(L("ios.feeds.deleteType", ["name": feed?.name ?? ""]), text: $typedName)
                .textFieldStyle(KFFieldStyle())
                .autocorrectionDisabled()
            Button(role: .destructive) { confirmDelete = true } label: {
                Label(L("ios.feeds.delete"), systemImage: "trash")
            }
            .buttonStyle(.kf(.secondary))
            .disabled(typedName.trimmingCharacters(in: .whitespaces) != feed?.name)
            .confirmationDialog(L("ios.feeds.deleteConfirm", ["name": feed?.name ?? ""]), isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(L("ios.feeds.delete"), role: .destructive) { Task { await delete() } }
                Button(L("common.cancel"), role: .cancel) {}
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(padding: 16)
    }

    private func reset() {
        guard let f = feed else { return }
        name = f.name
        invitesNeedApproval = f.value2.inviteRequiresApproval ?? true
        autoApprove = f.value2.autoApproveSubscriptions ?? false
        membersSeeReactions = f.value2.membersSeeReactions ?? false
        postersCanInvite = f.value2.postersCanInvite ?? false
        postersCanAddBirthdays = f.value2.postersCanAddBirthdays ?? false
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await model.save(name: name.trimmingCharacters(in: .whitespacesAndNewlines), autoApprove: autoApprove,
                                 invitesNeedApproval: invitesNeedApproval, membersSeeReactions: membersSeeReactions,
                                 postersCanInvite: postersCanInvite,
                                 postersCanAddBirthdays: app.birthdaysEnabled ? postersCanAddBirthdays : nil)
            if let f = model.feed { app.updateFeed(f.summary) }
            app.toasts.show(L("common.saved"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func setCover(_ itemId: String?) async {
        do {
            try await model.setCover(itemId)
            if let f = model.feed { app.updateFeed(f.summary) }
        } catch {
            app.toasts.error(error)
        }
    }

    private func delete() async {
        let name = feed?.name ?? ""
        do {
            try await model.delete()
            app.removeFeed(model.id)
            app.familyPath = NavigationPath()
            app.toasts.show(L("ios.feeds.deleted", ["name": name]))
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Pick the cover among the feed's photos (live posts only).
private struct CoverPicker: View {
    @Environment(\.dismiss) private var dismiss
    let posts: [FeedPost]
    let current: String?
    let pick: (String) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                let items = posts.flatMap(\.items).filter { $0.status == .ready }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 4)], spacing: 4) {
                    ForEach(items) { item in
                        Button {
                            pick(item.id)
                            dismiss()
                        } label: {
                            RemoteThumb(url: item.thumbUrl, kind: item.kind)
                                .aspectRatio(1, contentMode: .fill)
                                .overlay {
                                    if item.id == current {
                                        RoundedRectangle(cornerRadius: 4).strokeBorder(Palette.primary, lineWidth: 3)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(item.caption ?? L("ios.feeds.coverChoose"))
                        .accessibilityAddTraits(item.id == current ? .isSelected : [])
                    }
                }
                .padding(4)
            }
            .pageBackground()
            .pageTitle(L("ios.feeds.cover"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } } }
        }
    }
}
