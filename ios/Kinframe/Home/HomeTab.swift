import KinframeKit
import SwiftUI

/// Home (docs/spec/simple-app.md §3.1): one timeline of all the person's families (of every account), newest first.
/// With several families, filter chips (All · each family, remembered on this device) and a family label on each
/// post; a post sent to several families shows once. Under the chips the people and frames of the filtered family
/// (with All: the home family) and "Invite".
struct HomeTab: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    /// Each account's timeline (or the filtered family's), by account id.
    @State private var timelines: [String: TimelineModel] = [:]

    private var filter: FamilyChoice? { accounts.homeFilter }
    /// The family of the people row and the empty state.
    private var rowFamily: Accounts.Owned<FeedSummary>? { filter.flatMap(accounts.family) ?? accounts.homeFamily }
    private var key: String { accounts.active.map(\.accountId).joined(separator: ",") + "|" + (filter.map { $0.account + "/" + $0.feedId } ?? "all") }

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.homePath) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if accounts.hasSeveralFamilies { chips }
                    if let fam = rowFamily {
                        HomeFamilyHeader(feed: fam.value)
                            .environment(fam.model)
                            .id(fam.model.accountId + "/" + fam.value.id)
                    }
                    timeline
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await reload(fresh: false) }
            .pageBackground()
            .navigationTitle(L("simple.nav.home"))
            .navigationBarTitleDisplayMode(.inline)
            .socialDestinations()
        }
        .task(id: key) { await reload(fresh: true) }
        .onLiveEvent(app.events) { timelines[app.accountId]?.apply($0) }
        .onOtherAccountsLiveEvents(accounts) { m, e in timelines[m.accountId]?.apply(e) }
    }

    // MARK: Chips

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(L("simple.home.all"), on: filter == nil) { accounts.homeFilter = nil }
                ForEach(accounts.orderedFamilies, id: \.value.id) { o in
                    chip(o.value.name, on: filter == o.choice) { accounts.homeFilter = o.choice }
                }
            }
        }
    }

    private func chip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(minHeight: 36)
                .foregroundStyle(on ? Palette.onPrimary : Palette.text)
                .background(Capsule().fill(on ? Palette.primary : Palette.surface2))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
    }

    // MARK: Timeline

    private struct Row {
        let owner: AppModel
        let timeline: TimelineModel
        let entry: TimelineEntry
        var id: String { owner.accountId + "/" + entry.id }
    }

    private var sources: [(AppModel, TimelineModel)] {
        accounts.active.compactMap { m in timelines[m.accountId].map { (m, $0) } }
    }

    private func entries(_ t: TimelineModel) -> [TimelineEntry] { TimelineLogic.entries(HomeTimeline.families(t.posts)) }

    /// Every account's family posts interleaved by time, only as far back as every timeline has loaded.
    private var rows: [Row] {
        let sources = sources
        let lists = sources.map { m, t in entries(t).map { Row(owner: m, timeline: t, entry: $0) } }
        return AccountMerge.merged(lists, hasMore: sources.map { $0.1.nextBefore != nil }) { $0.entry.post.createdAt }
    }

    private func loadMoreAll() async {
        let sources = sources
        let next = AccountMerge.toLoad(sources.map { entries($0.1) }, hasMore: sources.map { $0.1.nextBefore != nil }) { $0.post.createdAt }
        let chosen = next.map { sources[$0].1 }
        await withTaskGroup(of: Void.self) { g in
            for t in chosen { g.addTask { await t.loadMore() } }
        }
    }

    @ViewBuilder private var timeline: some View {
        let sources = sources
        if sources.isEmpty || !sources.allSatisfy(\.1.loaded) {
            LoadingView()
        } else if sources.allSatisfy({ $0.1.error != nil }), let error = sources.first?.1.error {
            ErrorRetryView(message: error) { Task { await reload(fresh: false) } }
        } else if sources.allSatisfy({ HomeTimeline.families($0.1.posts).isEmpty }) {
            EmptyStateView(systemImage: "photo.on.rectangle.angled", title: L("simple.home.emptyTitle"), message: L("simple.home.emptyBody")) {
                Button(L("simple.home.share")) { app.compose(family: filter?.feedId) }.buttonStyle(.kf(.primary, large: true))
            }
            .card(padding: 8)
        } else {
            let rows = rows
            ForEach(rows, id: \.id) { row in
                if let h = row.entry.promptHeader {
                    Label(Lp("engagement.prompt.answers", count: h.count, ["title": h.title]), systemImage: "sparkles")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Palette.primarySoftText)
                }
                TimelinePostCard(post: row.entry.post, model: row.timeline, showFamily: accounts.hasSeveralFamilies && filter == nil) {
                    app.homePath.append($0)
                }
                .environment(row.owner)
                .onAppear {
                    if row.id == rows.last?.id { Task { await loadMoreAll() } }
                }
            }
            if sources.contains(where: \.1.loadingMore) {
                ProgressView().frame(maxWidth: .infinity).padding()
            } else if rows.count < 3, sources.contains(where: { $0.1.nextBefore != nil }) {
                // Few rows above the cutoff: page on without waiting for a scroll.
                Color.clear.frame(height: 1).onAppear { Task { await loadMoreAll() } }
            }
        }
    }

    /// One timeline per account (All), or the filtered family's.
    private func reload(fresh: Bool) async {
        var next: [String: TimelineModel] = [:]
        for m in accounts.active {
            guard let api = m.api else { continue }
            if let filter {
                guard filter.account == m.accountId else { continue }
                next[m.accountId] = !fresh ? timelines[m.accountId] ?? TimelineModel(api: api, feedId: filter.feedId)
                    : TimelineModel(api: api, feedId: filter.feedId)
            } else {
                let existing = timelines[m.accountId]
                next[m.accountId] = !fresh && existing?.feedId == nil ? existing ?? TimelineModel(api: api) : TimelineModel(api: api)
            }
        }
        timelines = next
        await withTaskGroup(of: Void.self) { g in
            for t in next.values { g.addTask { await t.load() } }
        }
        // What Home shows of your own posts counts towards the home family.
        var own: [FamilyChoice: Int] = [:]
        for (id, t) in next {
            guard let me = accounts.model(for: id).me?.id else { continue }
            for p in t.posts where p.kind == .feed && p.poster.userId == me {
                if let f = p.feedId { own[FamilyChoice(account: id, feedId: f), default: 0] += 1 }
            }
        }
        accounts.noteOwnPosts(own)
    }
}

/// The people and frames of one family with "Invite", and its prompt of the week.
private struct HomeFamilyHeader: View {
    @Environment(AppModel.self) private var app
    let feed: FeedSummary
    @State private var family: FeedDetailModel?
    @State private var inviter = FamilyInviter()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let family { FamilyRow(model: family) { invite() } }
            if feed.role != .member, let prompt = app.social.prompts[feed.id] {
                PromptCard(feed: feed, prompt: prompt)
            }
        }
        .task {
            guard family == nil, let api = app.api else { return }
            let m = FeedDetailModel(api: api, id: feed.id)
            family = m
            await m.load()
        }
        .onLiveEvent(app.events) { family?.apply($0) }
        .sheet(item: $inviter.shown) { invite in
            FeedInviteShareSheet(invite: invite, feedName: feed.name)
        }
    }

    private func invite() {
        guard let family else { return }
        Task { await inviter.invite(family, toasts: app.toasts) }
    }
}

// MARK: Navigation

extension View {
    /// The screens Home, Chats and the families push: a post's thread, a chat, a person, a circle, each talking to
    /// its route's account.
    func socialDestinations() -> some View {
        modifier(SocialDestinations())
    }

    /// Live events of the accounts other than the primary one (merged lists).
    func onOtherAccountsLiveEvents(_ accounts: Accounts, perform handler: @escaping (AppModel, LiveEvent) -> Void) -> some View {
        modifier(OtherAccountsLiveEvents(accounts: accounts, handler: handler))
    }
}

private struct SocialDestinations: ViewModifier {
    @Environment(Accounts.self) private var accounts

    func body(content: Content) -> some View {
        content
            .navigationDestination(for: PostRoute.self) { PostDetailView(route: $0).account($0.account, in: accounts) }
            .navigationDestination(for: ChatRoute.self) { ConversationView(route: $0).account($0.account, in: accounts) }
            .navigationDestination(for: PersonRoute.self) { PersonView(userId: $0.userId).account($0.account, in: accounts) }
            .navigationDestination(for: CircleRoute.self) { CircleDetailView(route: $0).account($0.account, in: accounts) }
    }
}

private struct OtherAccountsLiveEvents: ViewModifier {
    let accounts: Accounts
    let handler: (AppModel, LiveEvent) -> Void
    @State private var tokens: [(EventHub, UUID)] = []

    func body(content: Content) -> some View {
        content
            .task(id: accounts.active.map(\.accountId)) {
                for (hub, id) in tokens { hub.unsubscribe(id) }
                tokens = accounts.active.dropFirst().map { m in
                    (m.events, m.events.subscribe { [weak m] e in if let m { handler(m, e) } })
                }
            }
            .onDisappear {
                for (hub, id) in tokens { hub.unsubscribe(id) }
                tokens = []
            }
    }
}

// MARK: Cards

/// "This week in Peeters: Your view right now", with Answer (the composer, tagged with the prompt).
struct PromptCard: View {
    @Environment(AppModel.self) private var app
    let feed: FeedSummary
    let prompt: FeedPrompt

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "sparkles").font(.title3).foregroundStyle(Palette.primarySoftText)
                .frame(width: 44, height: 44).background(Circle().fill(Palette.primarySoft))
            VStack(alignment: .leading, spacing: 6) {
                Text(L("engagement.prompt.inFamily", ["feed": feed.name])).font(.footnote).foregroundStyle(Palette.textMuted)
                Text(prompt.title).font(.headline).foregroundStyle(Palette.text)
                if let n = prompt.answerCount, n > 0 {
                    Text(Lp("engagement.prompt.answerCount", count: n)).font(.footnote).foregroundStyle(Palette.textMuted)
                }
                if feed.role != .member {
                    Button(L("engagement.answerPrompt")) {
                        app.compose(family: feed.id, prompt: prompt.id)
                    }
                    .buttonStyle(.kf(.soft))
                }
            }
            Spacer(minLength: 0)
        }
        .card()
    }
}
