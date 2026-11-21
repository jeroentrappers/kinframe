import KinframeKit
import SwiftUI

/// Family (docs/spec/simple-app.md §3.4): with one family straight its profile; with several a list of them (home
/// family first, "Start a family" at the bottom, the gear opens the App settings), each opening its profile.
struct FamilyTab: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.familyPath) {
            Group {
                if accounts.hasSeveralFamilies {
                    FamilyListView()
                } else if let only = accounts.feeds.first {
                    FamilyProfileView(feed: only.value)
                        .environment(only.model)
                        .id(only.model.accountId + "/" + only.value.id)
                } else if accounts.familiesKnown {
                    // On a frame but in no family yet (§8).
                    GettingStartedView()
                } else {
                    LoadingView()
                }
            }
            .pageBackground()
            .navigationDestination(for: FamilyProfileRoute.self) { route in
                if let o = accounts.family(account: route.account ?? accounts.primary.accountId, id: route.feedId) {
                    FamilyProfileView(feed: o.value).environment(o.model).pageBackground()
                }
            }
            .navigationDestination(for: FamilyRoute.self) { route in
                FamilyPageView(route: route).account(route.account, in: accounts)
            }
            .settingsAndHistoryDestinations()
            .socialDestinations()
        }
    }
}

/// The person's families: cover, name, "12 people · 3 frames", the home family first.
private struct FamilyListView: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(accounts.orderedFamilies, id: \.value.id) { o in
                    NavigationLink(value: FamilyProfileRoute(feedId: o.value.id, account: o.model.accountId)) {
                        HStack(spacing: 14) {
                            FeedCover(url: o.value.coverUrl, name: o.value.name, size: 60, round: true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(o.value.name).font(.headline).foregroundStyle(Palette.text).lineLimit(1)
                                Text(FamilyText.counts(posts: nil, people: o.value.memberCount, frames: o.value.subscriptionCount))
                                    .font(.subheadline).foregroundStyle(Palette.textMuted)
                            }
                            Spacer()
                            ServerChip(model: o.model)
                            Image(systemName: "chevron.right").foregroundStyle(Palette.textFaint)
                        }
                        .padding(14)
                        .card(padding: 0)
                    }
                    .buttonStyle(CardButtonStyle())
                }
                Button { accounts.startSheet = .start } label: {
                    Label(L("simple.start.family"), systemImage: "plus")
                }
                .buttonStyle(.kf(.secondary, large: true, fill: true))
                .padding(.top, 4)
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await accounts.loadFeeds() }
        .navigationTitle(L("simple.nav.family"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: AppSettingsRoute(account: app.accountId)) {
                    Image(systemName: "gearshape").frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("simple.settings.account"))
            }
        }
    }
}

/// The profile: cover, counts, name and members, "Invite family" and "Add a frame", then Photos, People, Frames.
struct FamilyProfileView: View {
    @Environment(AppModel.self) private var app
    let feed: FeedSummary
    @State private var model: FeedDetailModel?
    @State private var inviter = FamilyInviter()
    @State private var addingFrame = false
    @State private var onlyMine = false

    var body: some View {
        @Bindable var app = app
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let model, let detail = model.feed {
                    header(detail, model: model)
                    FamilySocialHeader(model: model)
                    Picker(L("simple.family.sections"), selection: $app.familySegment) {
                        ForEach(FamilySegment.allCases, id: \.self) { s in Text(s.title).tag(s) }
                    }
                    .pickerStyle(.segmented)
                    switch app.familySegment {
                    case .photos: photos(model)
                    case .people: FeedMembersSection(model: model)
                    case .frames: FeedFramesSection(model: model)
                    }
                } else if let error = model?.error {
                    ErrorRetryView(message: error) { Task { await model?.load() } }
                } else {
                    LoadingView()
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await model?.load() }
        .navigationTitle(feed.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: FamilyRoute(feedId: feed.id, page: .settings, account: app.accountId)) {
                    Image(systemName: "gearshape").frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("simple.settings.title"))
            }
        }
        .task {
            guard model == nil, let api = app.api else { return }
            let m = FeedDetailModel(api: api, id: feed.id, birthdaysEnabled: app.birthdaysEnabled)
            model = m
            await m.load()
            if let f = m.feed { app.updateFeed(f.summary) }
        }
        .onLiveEvent(app.events) { model?.apply($0) }
        .sheet(item: $inviter.shown) { invite in
            FeedInviteShareSheet(invite: invite, feedName: feed.name)
        }
        .sheet(isPresented: $addingFrame) {
            if let model {
                AddFrameToFamilySheet(feed: model.feed?.summary ?? feed,
                                      showing: Set(model.subscriptions.filter { SubscriptionLogic.isLive($0.status) }.map(\.frame.id))) { s in
                    model.added(s)
                }
            }
        }
    }

    private func header(_ detail: Feed, model: FeedDetailModel) -> some View {
        let frames = model.subscriptions.filter { $0.status == .active }.count
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 18) {
                FeedCover(url: detail.summary.coverUrl, name: detail.name, size: 88, round: true)
                Text(FamilyText.counts(posts: detail.value2.postCount, people: model.members.isEmpty ? detail.summary.memberCount : model.members.count,
                                       frames: frames))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.text)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(detail.name).font(.display(.title2)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                if !model.members.isEmpty {
                    Text(FamilyText.memberNames(model.members.map { $0.userId == app.me?.id ? L("social.you") : $0.displayName }))
                        .font(.subheadline).foregroundStyle(Palette.textMuted)
                }
            }
            HStack(spacing: 10) {
                if model.abilities?.canCreateJoinInvites == true {
                    Button {
                        Task { await inviter.invite(model, toasts: app.toasts) }
                    } label: {
                        HStack(spacing: 6) {
                            if inviter.busy { ProgressView().tint(Palette.onPrimary) }
                            Label(L("simple.family.invite"), systemImage: "person.badge.plus")
                        }
                    }
                    .buttonStyle(.kf(.primary, fill: true))
                }
                Button { addingFrame = true } label: {
                    Label(L("simple.family.addFrame"), systemImage: "photo.badge.plus")
                }
                .buttonStyle(.kf(.secondary, fill: true))
            }
        }
    }

    // MARK: Photos

    @ViewBuilder
    private func photos(_ model: FeedDetailModel) -> some View {
        let posts = FamilyPhotos.filter(model.posts.filter { !$0.items.isEmpty }, onlyMine: onlyMine, me: app.me?.id) { $0.poster.userId }
        VStack(alignment: .leading, spacing: 12) {
            Toggle(L("simple.family.onlyMine"), isOn: $onlyMine).tint(Palette.accent)
            if !model.postsLoaded {
                LoadingView()
            } else if posts.isEmpty {
                EmptyStateView(systemImage: "photo.stack", title: L(onlyMine ? "simple.family.noMinePhotos" : "simple.home.emptyTitle"),
                               message: onlyMine ? "" : L("simple.home.emptyBody")) {
                    Button(L("simple.home.share")) { app.compose(family: feed.id) }.buttonStyle(.kf(.primary))
                }
                .card(padding: 8)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 4)], spacing: 4) {
                    ForEach(posts) { post in
                        NavigationLink(value: PostRoute(postId: post.id, account: app.accountId)) {
                            Color.clear
                                .aspectRatio(1, contentMode: .fit)
                                .overlay { ItemThumb(item: post.items[0]) }
                                .clipped()
                                .overlay(alignment: .topTrailing) {
                                    if post.items.count > 1 {
                                        Image(systemName: "square.on.square.fill").font(.caption).foregroundStyle(.white)
                                            .shadow(radius: 2).padding(6)
                                    }
                                }
                        }
                        .accessibilityLabel(L("simple.family.photoBy", ["name": post.poster.displayName,
                                                                        "when": Format.relative(post.createdAt, locale: L10n.locale)]))
                        .onAppear {
                            if post.id == posts.last?.id { Task { await model.loadMorePosts() } }
                        }
                    }
                }
                if model.loadingMore { ProgressView().frame(maxWidth: .infinity) }
            }
        }
    }
}

/// A page behind the family's gear: Settings itself, or one of its pages.
struct FamilyPageView: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    let route: FamilyRoute
    @State private var model: FeedDetailModel?

    var body: some View {
        Group {
            if let model, let feed = model.feed {
                switch route.page {
                case .settings:
                    FamilySettingsList(model: model, feed: feed)
                default:
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            switch route.page {
                            case .details: FeedSettingsSection(model: model)
                            case .circles: CirclesSection(model: model)
                            case .birthdays: FeedBirthdaysSection(model: model)
                            case .leave: FeedMembersSection(model: model, part: .leave)
                            case .settings: EmptyView()
                            }
                        }
                        .padding(16)
                        .frame(maxWidth: 720)
                        .frame(maxWidth: .infinity)
                    }
                    .refreshable { await model.load() }
                }
            } else if let error = model?.error {
                ErrorRetryView(message: error) { Task { await model?.load() } }.padding(16)
            } else {
                LoadingView()
            }
        }
        .pageBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard model == nil, let api = app.api else { return }
            let m = FeedDetailModel(api: api, id: route.feedId, birthdaysEnabled: app.birthdaysEnabled)
            model = m
            await m.load()
            if let f = m.feed { app.updateFeed(f.summary) }
        }
        .onLiveEvent(app.events) { model?.apply($0) }
    }

    private var title: String {
        switch route.page {
        case .settings: L("simple.settings.title")
        case .details: L("simple.settings.details")
        case .circles: L("social.tabCircles")
        case .birthdays: L("ios.bday.tab")
        case .leave: L("ios.feeds.leave")
        }
    }
}

/// Settings (§3.4): *This family* (name and cover, circles, birthdays, more ways to add photos, approvals, leave or
/// delete) and *App* (accounts and servers, notifications, privacy, simple view, sent history, about).
private struct FamilySettingsList: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    let model: FeedDetailModel
    let feed: Feed

    var body: some View {
        Form {
            Section {
                if model.abilities?.canManage == true {
                    link(.details, L("simple.settings.details"), L("simple.settings.detailsHint"), "slider.horizontal.3")
                }
                link(.circles, L("social.tabCircles"), L("simple.settings.circlesHint"), "person.2.circle")
                if app.birthdaysEnabled {
                    link(.birthdays, L("ios.bday.tab"), L("simple.settings.birthdaysHint"), "gift")
                }
                link(.leave, feed.role == .owner ? L("simple.settings.leaveOrDelete") : L("ios.feeds.leave"), nil,
                     "rectangle.portrait.and.arrow.right")
            } header: {
                Text(L("simple.settings.thisFamily", ["family": feed.name]))
            }
            .listRowBackground(Palette.surface)
            Section {
                NavigationLink(value: AppSettingsRoute(account: app.accountId)) {
                    row(L("simple.settings.account"), L("simple.settings.accountHint"), "person.crop.circle")
                }
            } header: {
                Text(L("simple.settings.app"))
            } footer: {
                Text(L("settings.about")).frame(maxWidth: .infinity).padding(.top, 12)
            }
            .listRowBackground(Palette.surface)
        }
        .scrollContentBackground(.hidden)
        .readableFormWidth()
    }

    private func link(_ page: FamilyRoute.Page, _ title: String, _ hint: String?, _ symbol: String) -> some View {
        NavigationLink(value: FamilyRoute(feedId: feed.id, page: page, account: app.accountId)) {
            row(title, hint, symbol)
        }
    }

    private func row(_ title: String, _ hint: String?, _ symbol: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let hint { Text(hint).font(.footnote).foregroundStyle(Palette.textMuted) }
            }
        } icon: {
            Image(systemName: symbol).foregroundStyle(Palette.primary)
        }
    }
}

extension View {
    /// Sent history, a send in it and the App settings: from Family's gear, and from getting started (§8).
    func settingsAndHistoryDestinations() -> some View {
        modifier(SettingsAndHistoryDestinations())
    }
}

private struct SettingsAndHistoryDestinations: ViewModifier {
    @Environment(Accounts.self) private var accounts

    func body(content: Content) -> some View {
        content
            .navigationDestination(for: SentHistoryRoute.self) { _ in SentHistoryView() }
            .navigationDestination(for: AppSettingsRoute.self) { _ in AppSettingsView() }
            .navigationDestination(for: HistoryRoute.self) { route in
                let owner = accounts.model(for: route.account)
                if let api = owner.api {
                    SendDetailView(model: SendDetailModel(api: api, id: route.sendId), onRemoved: { owner.historyVersion += 1 })
                        .environment(owner)
                }
            }
    }
}
