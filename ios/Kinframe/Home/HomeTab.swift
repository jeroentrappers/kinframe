import KinframeKit
import SwiftUI

/// Home (docs/spec/family-social.md §4): the strip of frames and families with freshness rings and photo requests,
/// your milestones, On this day, this week's prompts, then every family's posts, newest first.
struct HomeTab: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    @State private var model: TimelineModel?
    /// The other accounts' timelines, merged into the primary one by time (docs/spec/multi-server.md §2.2).
    @State private var others: [String: TimelineModel] = [:]
    @State private var onThisDay: OnThisDay?
    @State private var summary: WeeklySummary?

    private var features: SocialFeatures { app.social.features }

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.homePath) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if features.engagement {
                        HomeStrip()
                        // The other accounts' frames and families.
                        ForEach(accounts.active.dropFirst(), id: \.accountId) { m in
                            if m.social.features.engagement { HomeStrip().environment(m) }
                        }
                        ForEach(MilestoneLogic.unseen(app.social.milestones).prefix(1)) { m in MilestoneCard(milestone: m) }
                        ForEach(promptCards, id: \.0) { feed, prompt in PromptCard(feed: feed, prompt: prompt) }
                        if let day = onThisDay, !day.groups.isEmpty { OnThisDayCard(day: day) }
                        if let s = summary, s.seenCount > 0 {
                            Button { app.social.sheet = .weeklySummary(weekOf: s.weekOf) } label: {
                                HStack(spacing: 12) {
                                    Text("❤️").font(.title2)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(L("engagement.summary.title")).font(.headline).foregroundStyle(Palette.text)
                                        Text(SummaryText.seen(s)).font(.footnote).foregroundStyle(Palette.textMuted).multilineTextAlignment(.leading)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundStyle(Palette.textFaint)
                                }
                                .card()
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if features.social { timeline } else if features.engagement { framesOnlyNote }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await reload() }
            .pageBackground()
            .navigationTitle(L("social.home.title"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { app.tab = .send } label: { Image(systemName: "plus").frame(width: 44, height: 44) }
                        .accessibilityLabel(L("nav.sendPhotos"))
                }
            }
            .socialDestinations()
        }
        .task(id: app.api?.baseURL) { await reload() }
        .task(id: accounts.active.map(\.accountId)) { await reloadOthers() }
        .onLiveEvent(app.events) { model?.apply($0) }
        .onOtherAccountsLiveEvents(accounts) { m, e in others[m.accountId]?.apply(e) }
    }

    /// One account's posts, or every account's posts interleaved by time, each with its account.
    private var merged: [(AppModel, TimelineModel, TimelineEntry)] {
        guard let model else { return [] }
        var lists: [[(AppModel, TimelineModel, TimelineEntry)]] = [model.entries.map { (app, model, $0) }]
        for m in accounts.active.dropFirst() {
            if let t = others[m.accountId] { lists.append(t.entries.map { (m, t, $0) }) }
        }
        return lists.count == 1 ? lists[0] : AccountMerge.interleave(lists) { $0.2.post.createdAt }
    }

    private func reloadOthers() async {
        var next: [String: TimelineModel] = [:]
        for m in accounts.active.dropFirst() where m.social.features.social {
            guard let api = m.api else { continue }
            next[m.accountId] = others[m.accountId] ?? TimelineModel(api: api)
        }
        others = next
        await withTaskGroup(of: Void.self) { g in
            for t in next.values { g.addTask { await t.load() } }
        }
    }

    private func loadMoreAll() async {
        await model?.loadMore()
        for t in others.values where t.nextBefore != nil { await t.loadMore() }
    }

    private var promptCards: [(FeedSummary, FeedPrompt)] {
        app.feeds.compactMap { f in app.social.prompts[f.id].map { (f, $0) } }
    }

    @ViewBuilder private var timeline: some View {
        if let model {
            if !model.loaded {
                LoadingView()
            } else if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else if model.posts.isEmpty, others.values.allSatisfy(\.posts.isEmpty) {
                EmptyStateView(systemImage: "house", title: L("social.home.emptyTitle"), message: L("social.home.emptyBody")) {
                    Button(L("nav.sendPhotos")) { app.tab = .send }.buttonStyle(.kf(.primary, large: true))
                }
                .card(padding: 8)
            } else {
                let rows = merged
                ForEach(rows, id: \.2.id) { owner, timeline, entry in
                    if let h = entry.promptHeader {
                        Label(Lp("engagement.prompt.answers", count: h.count, ["title": h.title]), systemImage: "sparkles")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Palette.primarySoftText)
                    }
                    TimelinePostCard(post: entry.post, model: timeline) { app.homePath.append($0) }
                        .overlay(alignment: .topTrailing) { ServerChip(model: owner).padding(10) }
                        .environment(owner)
                        .onAppear {
                            if entry.id == rows.last?.2.id { Task { await loadMoreAll() } }
                        }
                }
                if model.loadingMore || others.values.contains(where: \.loadingMore) { ProgressView().frame(maxWidth: .infinity).padding() }
            }
        } else {
            LoadingView()
        }
    }

    private var framesOnlyNote: some View {
        Text(L("social.home.stripOnly")).font(.footnote).foregroundStyle(Palette.textMuted)
    }

    private func reload() async {
        guard let api = app.api else { return }
        if features.social {
            if model == nil { model = TimelineModel(api: api) }
            await model?.load()
            for t in others.values { await t.load() }
        }
        if features.engagement {
            onThisDay = try? await api.onThisDay()
            summary = try? await api.weeklySummary(WeekOf.previousMonday())
            await app.social.loadMilestones()
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
                        Button(L("engagement.milestone.show")) { Task { await update(shown: true, seen: true) } }.buttonStyle(.kf(.soft))
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
                    Button(L("engagement.prompt.answer")) {
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
            SectionHeader(L("engagement.otd.title"))
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
