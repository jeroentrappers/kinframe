import KinframeKit
import SwiftUI

/// A feed to open in the Feeds tab, optionally on a section.
struct FeedRoute: Hashable {
    var id: String
    var section: FeedSection = .posts
}

/// A feed post whose receipts should open (from a notification).
struct FeedPostRoute: Hashable, Identifiable {
    var feedId: String
    var postId: String
    var id: String { postId }
}

enum FeedSection: String, Hashable, CaseIterable {
    case posts, members, frames, birthdays, settings

    init(_ hint: PushRoute.FeedSectionHint) {
        switch hint {
        case .posts: self = .posts
        case .members: self = .members
        case .frames: self = .frames
        case .birthdays: self = .birthdays
        case .settings: self = .settings
        }
    }

    var title: String {
        switch self {
        case .posts: L("ios.feeds.tabPosts")
        case .members: L("ios.feeds.tabMembers")
        case .frames: L("ios.feeds.tabFrames")
        case .birthdays: L("ios.bday.tab")
        case .settings: L("frame.tabSettings")
        }
    }
}

/// Your feeds: private family channels that frames subscribe to (docs/spec/feeds.md). Create one, join one with a
/// code, open one.
struct FeedsTab: View {
    @Environment(AppModel.self) private var app
    @State private var creating = false
    @State private var enteringCode = false

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.feedsPath) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    Text(L("ios.feeds.lede")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                    if !app.feedsLoaded {
                        LoadingView()
                    } else if app.feeds.isEmpty {
                        EmptyStateView(systemImage: "rectangle.stack.badge.person.crop", title: L("ios.feeds.emptyTitle"), message: L("ios.feeds.emptyBody")) {
                            VStack(spacing: 10) {
                                Button(L("ios.feeds.new")) { creating = true }.buttonStyle(.kf(.primary, large: true))
                                Button(L("ios.feeds.enterCode")) { enteringCode = true }.buttonStyle(.kf(.secondary))
                            }
                        }
                        .card(padding: 8)
                    } else {
                        ForEach(app.feeds) { feed in
                            NavigationLink(value: FeedRoute(id: feed.id)) {
                                FeedCard(feed: feed)
                            }
                            .buttonStyle(CardButtonStyle())
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await app.loadFeeds() }
            .pageBackground()
            .navigationTitle(L("ios.feeds.title"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { creating = true } label: { Label(L("ios.feeds.new"), systemImage: "plus") }
                        Button { enteringCode = true } label: { Label(L("ios.feeds.enterCode"), systemImage: "number") }
                    } label: {
                        Image(systemName: "plus").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("ios.feeds.new"))
                }
            }
            .navigationDestination(for: FeedRoute.self) { route in
                FeedDetailView(route: route)
            }
        }
        .task(id: app.api?.baseURL) { await app.loadFeeds() }
        .sheet(isPresented: $creating) {
            if let api = app.api {
                FeedCreateSheet(api: api) { feed in
                    app.updateFeed(feed.summary)
                    app.feedsPath.append(FeedRoute(id: feed.id))
                }
            }
        }
        .sheet(isPresented: $enteringCode) {
            if let api = app.api {
                NavigationStack { AddFrameView(api: api) }
            }
        }
    }
}

/// One feed in the list: cover, name, your role, how many frames and members.
struct FeedCard: View {
    let feed: FeedSummary

    var body: some View {
        HStack(spacing: 14) {
            FeedCover(url: feed.coverUrl, name: feed.name, size: 64)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(feed.name).font(.headline).foregroundStyle(Palette.text).lineLimit(1)
                    Spacer()
                    FeedRoleBadge(role: feed.role)
                }
                Text(Lp("ios.feeds.frameCount", count: feed.subscriptionCount) + " · " + Lp("ios.feeds.memberCount", count: feed.memberCount))
                    .font(.subheadline).foregroundStyle(Palette.textMuted)
                if let last = feed.lastPostAt {
                    Text(L("ios.feeds.lastPost", ["when": Format.relative(last, locale: L10n.locale)]))
                        .font(.footnote).foregroundStyle(Palette.textFaint)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(padding: 0)
        .accessibilityElement(children: .combine)
    }
}

/// A feed's cover (one of its own photos), or its initials on a warm tint.
struct FeedCover: View {
    let url: String?
    let name: String
    var size: CGFloat = 56

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).fill(Palette.tint(for: name).1)
            Text(Format.initials(name))
                .font(.system(size: size * 0.34, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.text)
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        .accessibilityHidden(true)
    }
}

struct FeedRoleBadge: View {
    let role: FeedRole

    var body: some View {
        switch role {
        case .owner: Pill(L("role.owner"), foreground: Palette.primarySoftText, background: Palette.primarySoft)
        case .admin: Pill(L("role.admin"), foreground: Palette.accentText, background: Palette.accentSoft)
        case .poster: Pill(L("ios.feeds.rolePoster"))
        }
    }
}

/// New feed: a name and the three choices the owner makes up front (all can change later).
struct FeedCreateSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let created: (Feed) -> Void
    @State private var name = ""
    @State private var invitesNeedApproval = true
    @State private var autoApprove = false
    @State private var membersSeeReactions = false
    @State private var postersCanInvite = false
    @State private var busy = false
    @FocusState private var focused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("ios.feeds.namePlaceholder"), text: $name)
                        .focused($focused)
                        .submitLabel(.done)
                        .onChange(of: name) { if name.count > 40 { name = String(name.prefix(40)) } }
                } header: {
                    Text(L("ios.feeds.name"))
                } footer: {
                    Text(L("ios.feeds.newHint"))
                }
                FeedSettingToggles(invitesNeedApproval: $invitesNeedApproval, autoApprove: $autoApprove,
                                   membersSeeReactions: $membersSeeReactions, postersCanInvite: $postersCanInvite)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("ios.feeds.new"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("ios.feeds.create")) { Task { await create() } }
                        .fontWeight(.semibold)
                        .disabled(trimmed.isEmpty || busy)
                }
            }
            .onAppear { focused = true }
        }
    }

    private func create() async {
        busy = true
        defer { busy = false }
        do {
            let feed = try await api.createFeed(name: trimmed, autoApprove: autoApprove, invitesNeedApproval: invitesNeedApproval,
                                                membersSeeReactions: membersSeeReactions, postersCanInvite: postersCanInvite)
            dismiss()
            created(feed)
        } catch {
            app.toasts.error(error)
        }
    }
}

/// The feed's switches, shared by "New feed" and the feed's settings.
struct FeedSettingToggles: View {
    @Binding var invitesNeedApproval: Bool
    @Binding var autoApprove: Bool
    @Binding var membersSeeReactions: Bool
    @Binding var postersCanInvite: Bool
    var disabled = false

    var body: some View {
        Section(L("ios.feeds.settingsMembers")) {
            toggle($invitesNeedApproval, L("ios.feeds.setInviteApproval"), L("ios.feeds.setInviteApprovalHint"))
            toggle($postersCanInvite, L("ios.feeds.setPostersInvite"), L("ios.feeds.setPostersInviteHint"))
            toggle($membersSeeReactions, L("ios.feeds.setSeeReactions"), L("ios.feeds.setSeeReactionsHint"))
        }
        Section(L("ios.feeds.settingsFrames")) {
            toggle($autoApprove, L("ios.feeds.setAutoApprove"), L("ios.feeds.setAutoApproveHint"))
        }
    }

    private func toggle(_ value: Binding<Bool>, _ title: String, _ hint: String) -> some View {
        Toggle(isOn: value) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .tint(Palette.accent)
        .disabled(disabled)
        .listRowBackground(Palette.surface)
    }
}
