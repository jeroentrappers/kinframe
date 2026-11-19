import KinframeKit
import SwiftUI

/// Home (docs/spec/family-social.md §4): the strip of frames and families with freshness rings and photo requests,
/// your milestones, On this day, this week's prompts, then every family's posts, newest first.
struct HomeTab: View {
    @Environment(AppModel.self) private var app
    @State private var model: TimelineModel?
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
        .onLiveEvent(app.events) { model?.apply($0) }
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
            } else if model.posts.isEmpty {
                EmptyStateView(systemImage: "house", title: L("social.home.emptyTitle"), message: L("social.home.emptyBody")) {
                    Button(L("nav.sendPhotos")) { app.tab = .send }.buttonStyle(.kf(.primary, large: true))
                }
                .card(padding: 8)
            } else {
                ForEach(model.entries) { entry in
                    if let h = entry.promptHeader {
                        Label(Lp("engagement.prompt.answers", count: h.count, ["title": h.title]), systemImage: "sparkles")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Palette.primarySoftText)
                    }
                    TimelinePostCard(post: entry.post, model: model) { app.homePath.append($0) }
                        .onAppear {
                            if entry.id == model.posts.last?.id { Task { await model.loadMore() } }
                        }
                }
                if model.loadingMore { ProgressView().frame(maxWidth: .infinity).padding() }
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
    /// The screens Home, Chats and the families push: a post's thread, a chat, a person, a circle.
    func socialDestinations() -> some View {
        navigationDestination(for: PostRoute.self) { PostDetailView(route: $0) }
            .navigationDestination(for: ChatRoute.self) { ConversationView(route: $0) }
            .navigationDestination(for: PersonRoute.self) { PersonView(userId: $0.userId) }
            .navigationDestination(for: CircleRoute.self) { CircleDetailView(route: $0) }
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
                            Button(L("engagement.otd.open")) { app.homePath.append(PostRoute(postId: first)) }.buttonStyle(.kf(.secondary))
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
