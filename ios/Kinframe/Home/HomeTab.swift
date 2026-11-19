import KinframeKit
import SwiftUI

/// Home (docs/spec/family-social.md §4): the strip of frames and families with freshness rings and photo requests,
/// your milestones, On this day, this week's prompts, then every family's posts, newest first.
///
/// Several accounts (docs/spec/multi-server.md §2.2): every account with family social contributes: its strip, its
/// milestones, prompts, On this day and weekly summary (each card talks to its own account), and its posts, merged
/// strictly by time.
struct HomeTab: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    /// Each account's timeline (accounts with `social`), by account id.
    @State private var timelines: [String: TimelineModel] = [:]
    @State private var onThisDay: [String: OnThisDay] = [:]
    @State private var summaries: [String: WeeklySummary] = [:]

    private var features: SocialFeatures { accounts.features }
    /// Accounts whose server has the timeline / the engagement cards.
    private var socialAccounts: [AppModel] { accounts.active.filter(\.social.features.social) }
    private var engagementAccounts: [AppModel] { accounts.active.filter(\.social.features.engagement) }

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.homePath) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if features.engagement { engagementCards }
                    if features.social { timeline } else if features.engagement { framesOnlyNote }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await reload() }
            .pageBackground()
            .navigationTitle(L("social.homeTitle"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { app.tab = .send } label: { Image(systemName: "plus").frame(width: 44, height: 44) }
                        .accessibilityLabel(L("nav.sendPhotos"))
                }
            }
            .socialDestinations()
        }
        .task(id: accounts.active.map(\.accountId)) { await reload() }
        .onLiveEvent(app.events) { timelines[app.accountId]?.apply($0) }
        .onOtherAccountsLiveEvents(accounts) { m, e in timelines[m.accountId]?.apply(e) }
    }

    // MARK: Engagement

    @ViewBuilder private var engagementCards: some View {
        ForEach(engagementAccounts, id: \.accountId) { m in
            HomeStrip().environment(m)
        }
        // The newest milestone not seen yet, of any account.
        if let pick = newestMilestone {
            MilestoneCard(milestone: pick.1).environment(pick.0)
        }
        ForEach(promptCards, id: \.id) { card in
            PromptCard(feed: card.feed, prompt: card.prompt)
                .overlay(alignment: .topTrailing) { ServerChip(model: card.model).padding(10) }
                .environment(card.model)
        }
        ForEach(engagementAccounts, id: \.accountId) { m in
            if let day = onThisDay[m.accountId], !day.groups.isEmpty {
                OnThisDayCard(day: day)
                    .overlay(alignment: .topTrailing) { ServerChip(model: m).padding(10) }
                    .environment(m)
            }
        }
        ForEach(engagementAccounts, id: \.accountId) { m in
            if let s = summaries[m.accountId], s.seenCount > 0 {
                Button { m.social.sheet = .weeklySummary(weekOf: s.weekOf) } label: {
                    HStack(spacing: 12) {
                        Text("❤️").font(.title2)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("engagement.summary.title")).font(.headline).foregroundStyle(Palette.text)
                            Text(SummaryText.seen(s)).font(.footnote).foregroundStyle(Palette.textMuted).multilineTextAlignment(.leading)
                        }
                        Spacer()
                        ServerChip(model: m)
                        Image(systemName: "chevron.right").foregroundStyle(Palette.textFaint)
                    }
                    .card()
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var newestMilestone: (AppModel, Milestone)? {
        engagementAccounts
            .compactMap { m in MilestoneLogic.unseen(m.social.milestones).first.map { (m, $0) } }
            .max { $0.1.achievedAt < $1.1.achievedAt }
    }

    private struct PromptCardRow {
        let model: AppModel
        let feed: FeedSummary
        let prompt: FeedPrompt
        var id: String { model.accountId + "/" + feed.id }
    }

    private var promptCards: [PromptCardRow] {
        engagementAccounts.flatMap { m in
            m.feeds.compactMap { f in m.social.prompts[f.id].map { PromptCardRow(model: m, feed: f, prompt: $0) } }
        }
    }

    // MARK: Timeline

    private struct Row {
        let owner: AppModel
        let timeline: TimelineModel
        let entry: TimelineEntry
        var id: String { owner.accountId + "/" + entry.id }
    }

    /// Every account's posts interleaved by time, only as far back as every timeline has loaded.
    private var rows: [Row] {
        let sources = sources
        let lists = sources.map { m, t in t.entries.map { Row(owner: m, timeline: t, entry: $0) } }
        return AccountMerge.merged(lists, hasMore: sources.map { $0.1.nextBefore != nil }) { $0.entry.post.createdAt }
    }

    private var sources: [(AppModel, TimelineModel)] {
        socialAccounts.compactMap { m in timelines[m.accountId].map { (m, $0) } }
    }

    /// Page the timelines that hold the merged list back.
    private func loadMoreAll() async {
        let sources = sources
        let next = AccountMerge.toLoad(sources.map { $0.1.entries }, hasMore: sources.map { $0.1.nextBefore != nil }) { $0.post.createdAt }
        let timelines = next.map { sources[$0].1 }
        await withTaskGroup(of: Void.self) { g in
            for t in timelines { g.addTask { await t.loadMore() } }
        }
    }

    @ViewBuilder private var timeline: some View {
        let sources = sources
        if sources.isEmpty || !sources.allSatisfy(\.1.loaded) {
            LoadingView()
        } else if sources.allSatisfy({ $0.1.error != nil }), let error = sources.first?.1.error {
            ErrorRetryView(message: error) { Task { await reload() } }
        } else if sources.allSatisfy(\.1.posts.isEmpty) {
            EmptyStateView(systemImage: "house", title: L("social.emptyTitle"), message: L("social.home.emptyBody")) {
                Button(L("nav.sendPhotos")) { app.tab = .send }.buttonStyle(.kf(.primary, large: true))
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
                TimelinePostCard(post: row.entry.post, model: row.timeline) { app.homePath.append($0) }
                    .overlay(alignment: .topTrailing) { ServerChip(model: row.owner).padding(10) }
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

    private var framesOnlyNote: some View {
        Text(L("social.home.stripOnly")).font(.footnote).foregroundStyle(Palette.textMuted)
    }

    private func reload() async {
        let social = socialAccounts
        var next: [String: TimelineModel] = [:]
        for m in social {
            guard let api = m.api else { continue }
            let existing = timelines[m.accountId]
            next[m.accountId] = existing?.api.baseURL == api.baseURL ? existing : TimelineModel(api: api)
        }
        timelines = next
        async let loaded: Void = Self.load(Array(next.values))
        async let cards = Self.engagement(engagementAccounts)
        _ = await loaded
        for (id, day, summary) in await cards {
            onThisDay[id] = day
            summaries[id] = summary
        }
    }

    private static func load(_ timelines: [TimelineModel]) async {
        await withTaskGroup(of: Void.self) { g in
            for t in timelines { g.addTask { await t.load() } }
        }
    }

    /// On this day and last week's summary of each account, and its milestones.
    private static func engagement(_ accounts: [AppModel]) async -> [(String, OnThisDay?, WeeklySummary?)] {
        await withTaskGroup(of: (String, OnThisDay?, WeeklySummary?).self) { g in
            for m in accounts {
                g.addTask {
                    guard let api = await m.api else { return (await m.accountId, nil, nil) }
                    async let day = try? api.onThisDay()
                    async let summary = try? api.weeklySummary(WeekOf.previousMonday())
                    await m.social.loadMilestones()
                    return (await m.accountId, await day, await summary)
                }
            }
            var out: [(String, OnThisDay?, WeeklySummary?)] = []
            for await r in g { out.append(r) }
            return out
        }
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

/// A milestone you just reached, private to you: dismiss, or show it on your profile.
struct MilestoneCard: View {
    @Environment(AppModel.self) private var app
    let milestone: Milestone

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: milestone.key.symbol)
                .font(.title3).foregroundStyle(Palette.accentText)
                .frame(width: 44, height: 44).background(Circle().fill(Palette.accentSoft))
            VStack(alignment: .leading, spacing: 6) {
                Text(milestone.key.title).font(.headline).foregroundStyle(Palette.text)
                Text(L("engagement.milestone.private")).font(.footnote).foregroundStyle(Palette.textMuted)
                HStack {
                    if !milestone.shown {
                        Button(L("engagement.milestoneShow")) { Task { await update(shown: true, seen: true) } }.buttonStyle(.kf(.soft))
                    }
                    Button(L("common.dismiss")) { Task { await update(shown: nil, seen: true) } }.buttonStyle(.kf(.ghost))
                }
            }
            Spacer(minLength: 0)
        }
        .card()
    }

    private func update(shown: Bool?, seen: Bool) async {
        guard let api = app.api else { return }
        do {
            app.social.setMilestone(try await api.updateMilestone(milestone.key, shown: shown, seen: seen))
        } catch {
            app.toasts.error(error)
        }
    }
}

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
                        app.composeFeedIds = [feed.id]
                        app.composePromptId = prompt.id
                        app.tab = .send
                    }
                    .buttonStyle(.kf(.soft))
                }
            }
            Spacer(minLength: 0)
        }
        .card()
    }
}

/// "1 year ago in Peeters" with the photos of that day; one tap opens the post to share it again.
struct OnThisDayCard: View {
    @Environment(AppModel.self) private var app
    let day: OnThisDay
    /// Leaving: memories hidden with "Don't show this memory again" leave the card at once.
    @State private var hiddenPosts: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(L("engagement.onThisDay"))
            ForEach(Array(day.groups.filter { !$0.postIds.allSatisfy(hiddenPosts.contains) }.enumerated()), id: \.offset) { _, g in
                VStack(alignment: .leading, spacing: 8) {
                    Text(Lp("engagement.otd.yearsAgo", count: g.yearsAgo, ["feed": g.feedName]))
                        .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(g.items) { item in
                                ItemThumb(item: item).frame(width: 96, height: 96).clipShape(RoundedRectangle(cornerRadius: Radius.sm))
                            }
                        }
                    }
                    HStack {
                        if let first = g.postIds.first {
                            Button(L("engagement.otd.open")) { app.homePath.append(PostRoute(postId: first, account: app.accountId)) }.buttonStyle(.kf(.secondary))
                        }
                        Spacer()
                        HideMemoryButton(postIds: g.postIds) { hiddenPosts.formUnion(g.postIds) }
                    }
                }
            }
        }
        .card()
    }
}
