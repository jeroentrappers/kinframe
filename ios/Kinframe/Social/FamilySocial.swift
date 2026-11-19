import KinframeKit
import SwiftUI

/// Under a family's header: the streak ("🔥 12 weeks", this week's progress and who helped), the family chat and the
/// caller's mute for this family.
struct FamilySocialHeader: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var streak: FeedStreak?

    private var features: SocialFeatures { app.social.features }

    var body: some View {
        if let feed = model.feed, features.social || features.chats || features.engagement {
            VStack(alignment: .leading, spacing: 10) {
                if features.engagement, let s = streak, s.enabled { streakCard(s) }
                HStack(spacing: 10) {
                    if features.chats, let conv = feed.summary.conversationId {
                        Button { app.openChat(conv) } label: { Label(L("social.family.chat"), systemImage: "bubble.left.and.bubble.right") }
                            .buttonStyle(.kf(.secondary))
                    }
                    if features.social || features.chats {
                        Menu {
                            Picker(L("social.mute.title"), selection: Binding(get: { feed.value2.mute ?? .none }, set: { m in Task { await setMute(m) } })) {
                                ForEach(FeedMute.allCases, id: \.self) { m in Text(m.label).tag(m) }
                            }
                        } label: {
                            Label((feed.value2.mute ?? .none) == .none ? L("social.mute.title") : (feed.value2.mute ?? .none).label,
                                  systemImage: (feed.value2.mute ?? .none) == .none ? "bell" : "bell.slash")
                        }
                        .buttonStyle(.kf(.ghost))
                    }
                }
            }
            .task(id: feed.id) {
                if features.engagement { streak = try? await model.api.feedStreak(feed.id) }
            }
            .onLiveEvent(app.events) { e in
                if case .social(.streakUpdated(let f, _)) = e, f == feed.id {
                    Task { streak = try? await model.api.feedStreak(feed.id) }
                }
            }
        }
    }

    private func streakCard(_ s: FeedStreak) -> some View {
        let summary = FeedStreakSummary(currentWeeks: s.currentWeeks, weekPhotos: s.week.photos, goal: s.goal, met: s.week.met)
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(StreakText.headline(summary) ?? L("engagement.streak.start")).font(.headline)
                Spacer()
                if s.freezesLeft > 0 { Text(Lp("engagement.streak.freezes", count: s.freezesLeft)).font(.caption).foregroundStyle(Palette.textMuted) }
            }
            ProgressView(value: StreakText.progress(summary)).tint(Palette.primary)
            Text(StreakText.week(summary)).font(.footnote).foregroundStyle(Palette.textMuted)
            if !s.week.helpers.isEmpty {
                HStack(spacing: -6) {
                    ForEach(s.week.helpers.prefix(8), id: \.userId) { p in PersonChip(person: p, size: 26).overlay(Circle().stroke(Palette.surface, lineWidth: 2)) }
                    Text(L("engagement.streak.helpers")).font(.caption).foregroundStyle(Palette.textMuted).padding(.leading, 12)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L("engagement.streak.helpersA11y", ["names": ListText.join(s.week.helpers.map { PersonName.display($0) })]))
            }
        }
        .card(padding: 14)
    }

    private func setMute(_ m: FeedMute) async {
        do {
            _ = try await model.api.setFeedMute(model.id, m)
            await model.refreshFeed()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// The family's timeline (social servers): its posts with threads and reactions, like Home.
struct FamilyTimelineSection: View {
    @Environment(AppModel.self) private var app
    let feedId: String
    @State private var model: TimelineModel?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let model {
                if !model.loaded {
                    LoadingView()
                } else if model.posts.isEmpty {
                    EmptyStateView(systemImage: "photo.stack", title: L("ios.feeds.noPostsTitle"), message: L("ios.feeds.noPostsBody")).card(padding: 8)
                } else {
                    ForEach(model.entries) { e in
                        if let h = e.promptHeader {
                            Label(Lp("engagement.prompt.answers", count: h.count, ["title": h.title]), systemImage: "sparkles")
                                .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.primarySoftText)
                        }
                        TimelinePostCard(post: e.post, model: model) { route in
                            app.tab = .home
                            app.homePath = NavigationPath([route])
                        }
                        .onAppear { if e.id == model.posts.last?.id { Task { await model.loadMore() } } }
                    }
                }
            } else {
                LoadingView()
            }
        }
        .task {
            guard model == nil, let api = app.api else { return }
            let m = TimelineModel(api: api, feedId: feedId)
            model = m
            await m.load()
        }
        .onLiveEvent(app.events) { model?.apply($0) }
    }
}

/// Owner and admins: the family-social settings (who sees receipts, who may post and create circles), the
/// encouragement settings (streak, prompts, quiet frames, recap, time zone), this week's and the coming prompts, and
/// the reports to handle.
struct FamilySocialSettings: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var engagement: FeedEngagement?
    @State private var prompts: [FeedPrompt] = []
    @State private var library: [PromptLibraryEntry] = []
    @State private var writing: FeedPrompt?
    @State private var ownTitle = ""
    @State private var reports = false
    @State private var zone = ""

    private var features: SocialFeatures { app.social.features }
    private var feed: Feed? { model.feed }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if features.social, let feed {
                group(L("social.family.settings")) {
                    toggle(feed.value2.membersSeeReceipts ?? false, L("social.family.seeReceipts"), L("social.family.seeReceiptsHint")) {
                        try await model.api.updateFeedSocial(model.id, membersSeeReceipts: $0)
                    }
                    Divider().overlay(Palette.border)
                    toggle(feed.value2.newMembersCanPost ?? true, L("social.family.newCanPost"), L("social.family.newCanPostHint")) {
                        try await model.api.updateFeedSocial(model.id, newMembersCanPost: $0)
                    }
                    Divider().overlay(Palette.border)
                    toggle(feed.value2.membersCanCreateCircles ?? true, L("social.family.circles"), L("social.family.circlesHint")) {
                        try await model.api.updateFeedSocial(model.id, membersCanCreateCircles: $0)
                    }
                }
            }
            if features.engagement, let e = engagement {
                group(L("engagement.family.title")) {
                    engagementToggle(e.streakEnabled, L("engagement.family.streak"), L("engagement.family.streakHint")) { .init(streakEnabled: $0) }
                    if e.streakEnabled {
                        Divider().overlay(Palette.border)
                        Stepper(value: Binding(get: { e.streakGoal }, set: { v in Task { await update(.init(streakGoal: v)) } }), in: StreakText.goalRange) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Lp("engagement.family.goal", count: e.streakGoal))
                                Text(L("engagement.family.goalHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                        }
                        .padding(14)
                    }
                    Divider().overlay(Palette.border)
                    engagementToggle(e.promptsEnabled, L("engagement.family.prompts"), L("engagement.family.promptsHint")) { .init(promptsEnabled: $0) }
                    Divider().overlay(Palette.border)
                    Stepper(value: Binding(get: { e.quietAfterDays }, set: { v in Task { await update(.init(quietAfterDays: v)) } }), in: 3...30) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Lp("engagement.family.quietAfter", count: e.quietAfterDays))
                            Text(L("engagement.family.quietAfterHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                    .padding(14)
                    Divider().overlay(Palette.border)
                    engagementToggle(e.monthlyRecap, L("engagement.family.recap"), L("engagement.family.recapHint")) { .init(monthlyRecap: $0) }
                    Divider().overlay(Palette.border)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("engagement.family.timezone"))
                        HStack {
                            TextField("Europe/Brussels", text: $zone)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .textFieldStyle(KFFieldStyle())
                            if zone != e.timezone {
                                Button(L("common.save")) { Task { await saveZone() } }.buttonStyle(.kf(.soft))
                            }
                        }
                        Button(L("engagement.family.useMine", ["zone": TimeZone.current.identifier])) { zone = TimeZone.current.identifier }
                            .font(.footnote).buttonStyle(.plain).foregroundStyle(Palette.primary)
                    }
                    .padding(14)
                }
                if e.promptsEnabled { promptsCard }
            }
            if features.chats || features.social {
                Button { reports = true } label: {
                    Label(L("chat.report.familyTitle"), systemImage: "flag")
                }
                .buttonStyle(.kf(.secondary))
            }
        }
        .task(id: model.id) { await load() }
        .sheet(isPresented: $reports) { ReportsView(feedId: model.id) }
        .alert(L("engagement.prompt.writeTitle"), isPresented: Binding(get: { writing != nil }, set: { if !$0 { writing = nil } })) {
            TextField(L("engagement.prompt.writePlaceholder"), text: $ownTitle)
            Button(L("common.save")) {
                if let w = writing { Task { await setPrompt(w, .own(ownTitle)) } }
            }
            Button(L("common.cancel"), role: .cancel) {}
        }
    }

    private var promptsCard: some View {
        group(L("engagement.prompt.manage")) {
            let shown = (PromptLogic.current(prompts).map { [$0] } ?? []) + PromptLogic.planned(prompts)
            ForEach(Array(shown.enumerated()), id: \.element.id) { n, p in
                if n > 0 { Divider().overlay(Palette.border) }
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(WeekOf.label(p.weekOf)).font(.caption).foregroundStyle(Palette.textMuted)
                        Text(p.status == .skipped || p.title.isEmpty ? L("engagement.prompt.skipped") : p.title).font(.subheadline)
                    }
                    Spacer()
                    Menu {
                        ForEach(library) { entry in
                            Button(entry.title) { Task { await setPrompt(p, .library(entry.key)) } }
                        }
                        Divider()
                        Button(L("engagement.prompt.writeOwn")) { ownTitle = ""; writing = p }
                        Button(L("engagement.prompt.skip")) { Task { await setPrompt(p, .skip) } }
                        if p.setBy != nil || p.status == .skipped {
                            Button(L("engagement.prompt.reset")) { Task { await resetPrompt(p) } }
                        }
                    } label: {
                        Text(L("common.edit")).font(.subheadline.weight(.semibold))
                    }
                }
                .padding(14)
            }
        }
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title)
            VStack(spacing: 0) { content() }.card(padding: 0)
        }
    }

    private func toggle(_ value: Bool, _ title: String, _ hint: String, save: @escaping (Bool) async throws -> Feed) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { v in
            Task {
                do { _ = try await save(v); await model.refreshFeed() } catch { app.toasts.error(error) }
            }
        })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .tint(Palette.accent)
        .padding(14)
    }

    private func engagementToggle(_ value: Bool, _ title: String, _ hint: String,
                                  _ body: @escaping (Bool) -> Components.Schemas.FeedEngagementUpdate) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { v in Task { await update(body(v)) } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .tint(Palette.accent)
        .padding(14)
    }

    private func load() async {
        guard features.engagement else { return }
        engagement = try? await model.api.feedEngagement(model.id)
        zone = engagement?.timezone ?? ""
        if engagement?.promptsEnabled == true {
            prompts = (try? await model.api.feedPrompts(model.id)) ?? []
            if library.isEmpty { library = (try? await model.api.promptLibrary()) ?? [] }
        }
    }

    private func update(_ u: Components.Schemas.FeedEngagementUpdate) async {
        do {
            engagement = try await model.api.updateFeedEngagement(model.id, u)
            if u.promptsEnabled != nil { await load(); await app.social.loadPrompts() }
        } catch {
            app.toasts.error(error)
        }
    }

    private func saveZone() async {
        let z = zone.trimmingCharacters(in: .whitespaces)
        guard TimeZone(identifier: z) != nil else {
            app.toasts.show(L("engagement.family.timezoneInvalid"), isError: true)
            return
        }
        await update(.init(timezone: z))
    }

    private func setPrompt(_ p: FeedPrompt, _ choice: API.PromptChoice) async {
        do {
            let updated = try await model.api.setFeedPrompt(model.id, weekOf: p.weekOf, choice)
            if let i = prompts.firstIndex(where: { $0.weekOf == p.weekOf }) { prompts[i] = updated }
            if updated.status == .current { app.social.setPrompt(updated.title.isEmpty ? nil : updated, feedId: model.id) }
        } catch {
            app.toasts.error(error)
        }
    }

    private func resetPrompt(_ p: FeedPrompt) async {
        do {
            try await model.api.resetFeedPrompt(model.id, weekOf: p.weekOf)
            prompts = (try? await model.api.feedPrompts(model.id)) ?? prompts
            await app.social.loadPrompts()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// The family page's sections: segmented up to four, a scrolling row of capsules with more (circles, birthdays), so
/// Dutch labels aren't cut off on a phone (like the frame page).
struct FeedSectionPicker: View {
    let sections: [FeedSection]
    @Binding var section: FeedSection

    var body: some View {
        if sections.count <= 4 {
            Picker(L("ios.feeds.sections"), selection: $section) {
                ForEach(sections, id: \.self) { s in Text(s.title).tag(s) }
            }
            .pickerStyle(.segmented)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(sections, id: \.self) { s in
                        let on = section == s
                        Button { withAnimation(.snappy) { section = s } } label: {
                            Text(s.title)
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 13)
                                .frame(minHeight: 34)
                                .foregroundStyle(on ? Palette.text : Palette.textMuted)
                                .background(Capsule().fill(on ? Palette.surface : .clear))
                                .shadow(color: on ? .black.opacity(0.08) : .clear, radius: 2, y: 1)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
                    }
                }
                .padding(3)
            }
            .background(Capsule().fill(Palette.surface2))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(L("ios.feeds.sections"))
        }
    }
}
