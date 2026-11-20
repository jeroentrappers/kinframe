import KinframeKit
import SwiftUI

/// The simple view (docs/spec/simple-app.md §6), for Oma on her own phone: all her families' posts together, one at a
/// time and large, the family under the poster, with a big "Send a heart" and "Next" (or swipe up and down). No tab
/// bar; a small "Settings" opens the way back to the normal view and the account.
struct SimpleView: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    /// Each account's timeline, by account id.
    @State private var timelines: [String: TimelineModel] = [:]
    @State private var index = 0
    @State private var page = 0
    @State private var settings = false

    private struct Row {
        let owner: AppModel
        let timeline: TimelineModel
        let post: TimelinePost
    }

    private var sources: [(AppModel, TimelineModel)] {
        accounts.active.compactMap { m in timelines[m.accountId].map { (m, $0) } }
    }

    private var rows: [Row] {
        let sources = sources
        let lists = sources.map { m, t in HomeTimeline.families(t.posts).filter { !$0.items.isEmpty }.map { Row(owner: m, timeline: t, post: $0) } }
        return AccountMerge.merged(lists, hasMore: sources.map { $0.1.nextBefore != nil }) { $0.post.createdAt }
    }

    var body: some View {
        let rows = rows
        VStack(spacing: 16) {
            HStack {
                Text(L("simple.nav.home")).font(.system(size: 22, weight: .bold)).foregroundStyle(Palette.text).lineLimit(1)
                Spacer()
                Button(L("simple.view.settings")) { settings = true }
                    .font(.system(size: 20, weight: .semibold))
                    .frame(minHeight: 44)
            }
            if rows.indices.contains(index) {
                content(rows[index], count: rows.count)
            } else if !sources.isEmpty, sources.allSatisfy(\.1.loaded) {
                Spacer()
                Text(L("simple.view.empty")).font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.textMuted)
                    .multilineTextAlignment(.center)
                Spacer()
            } else {
                Spacer()
                LoadingView()
                Spacer()
            }
        }
        .padding(16)
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .pageBackground()
        .gesture(DragGesture(minimumDistance: 40).onEnded { v in
            guard abs(v.translation.height) > abs(v.translation.width) else { return }
            step(v.translation.height < 0 ? 1 : -1, count: rows.count)
        })
        .task(id: accounts.active.map(\.accountId)) {
            var next: [String: TimelineModel] = [:]
            for m in accounts.active {
                if let api = m.api { next[m.accountId] = timelines[m.accountId] ?? TimelineModel(api: api) }
            }
            timelines = next
            await withTaskGroup(of: Void.self) { g in
                for t in next.values { g.addTask { await t.load() } }
            }
        }
        .onLiveEvent(app.events) { timelines[app.accountId]?.apply($0) }
        .onOtherAccountsLiveEvents(accounts) { m, e in timelines[m.accountId]?.apply(e) }
        .sheet(isPresented: $settings) { SimpleSettingsSheet() }
    }

    @ViewBuilder
    private func content(_ row: Row, count: Int) -> some View {
        let post = row.post
        VStack(alignment: .leading, spacing: 4) {
            Text(post.poster.userId == row.owner.me?.id ? L("social.you") : PersonName.display(post.poster))
                .font(.system(size: 26, weight: .bold)).foregroundStyle(Palette.text)
            Text([post.feedName, Format.relative(post.createdAt, locale: L10n.locale)].compactMap { $0 }.joined(separator: " · "))
                .font(.system(size: 20)).foregroundStyle(Palette.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        MediaPager(items: post.items, height: 420, index: $page)
            .environment(row.owner)
            .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        if let caption = TimelineLogic.caption(post) {
            Text(caption).font(.system(size: 22)).foregroundStyle(Palette.text).frame(maxWidth: .infinity, alignment: .leading)
        }
        Spacer(minLength: 0)
        let loved = post.thread.reactions.mine == .love
        HStack(spacing: 12) {
            Button {
                Task { try? await row.timeline.react(post, loved ? nil : .love) }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            } label: {
                Label(loved ? L("simple.view.hearted") : L("simple.view.heart"), systemImage: loved ? "heart.fill" : "heart")
                    .font(.system(size: 22, weight: .bold))
                    .frame(maxWidth: .infinity, minHeight: 72)
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            Button { step(1, count: count) } label: {
                Label(L("simple.view.next"), systemImage: "arrow.down")
                    .font(.system(size: 22, weight: .bold))
                    .frame(maxWidth: .infinity, minHeight: 72)
            }
            .buttonStyle(.kf(.secondary, large: true, fill: true))
            .disabled(index >= count - 1 && !sources.contains { $0.1.nextBefore != nil })
        }
    }

    private func step(_ delta: Int, count: Int) {
        let next = SimpleViewLogic.step(index, by: delta, count: count)
        withAnimation(.snappy) {
            index = next
            page = 0
        }
        // Near the end: load the next pages.
        if next >= count - 2 {
            let more = sources.filter { $0.1.nextBefore != nil }.map(\.1)
            Task { for t in more { await t.loadMore() } }
        }
    }
}

/// The simple view's settings: back to the normal view, and the account.
private struct SimpleSettingsSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        accounts.viewMode = .normal
                        dismiss()
                    } label: {
                        Label(L("simple.view.normal"), systemImage: "square.grid.2x2").font(.system(size: 20, weight: .semibold))
                            .frame(minHeight: 64)
                    }
                }
                .listRowBackground(Palette.surface)
                Section(L("simple.settings.account")) {
                    if let name = app.me?.displayName {
                        Text(L("settings.signedInAs", ["name": name])).font(.system(size: 20))
                    }
                    NavigationLink {
                        AppSettingsView()
                    } label: {
                        Text(L("simple.settings.account")).font(.system(size: 20)).frame(minHeight: 64)
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("simple.view.settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
    }
}
