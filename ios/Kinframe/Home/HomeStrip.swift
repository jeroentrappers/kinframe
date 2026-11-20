import KinframeKit
import PhotosUI
import SwiftUI

/// The strip above the timeline: your frames and families with a freshness ring ("Fresh", "4 days", "2 weeks
/// quiet") and the badge when a frame asked for photos ("Oma would like new photos"). Tapping a frame opens "Send
/// something to Oma's frame"; a family opens the family.
struct HomeStrip: View {
    @Environment(AppModel.self) private var app

    private var frames: [FrameSummary] { app.frames.filter { $0.can(\.send) } }

    /// Open requests of frames you send to, then those that reached you through a family (frames you're not on).
    private var requests: [PhotoRequest] {
        frames.compactMap { f in PhotoRequestText.isOpen(f.photoRequest) ? f.photoRequest : nil }
            + app.social.familyRequests.values
                .filter { r in PhotoRequestText.isOpen(r) && !frames.contains { $0.id == r.frameId } }
                .sorted { $0.createdAt > $1.createdAt }
    }

    var body: some View {
        if !frames.isEmpty || !app.feeds.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(requests) { r in
                    if let text = PhotoRequestText.badge(r) {
                        Button { app.social.sheet = .freshSend(frameId: r.frameId, requestId: r.id) } label: {
                            HStack {
                                Text("💛")
                                Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                                Spacer()
                                Text(L("engagement.request.send")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.primary)
                            }
                            .padding(12)
                            .background(Palette.warnSoft, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(frames) { f in
                            Button { app.social.sheet = .freshSend(frameId: f.id, requestId: f.photoRequest?.id) } label: {
                                StripBubble(name: f.name, freshness: f.freshness, asking: PhotoRequestText.isOpen(f.photoRequest)) {
                                    Image(systemName: "photo.on.rectangle").font(.title3).foregroundStyle(Palette.text)
                                        .frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.tint(for: f.id).0)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                        ForEach(app.feeds) { feed in
                            Button { app.openFeed(feed.id) } label: {
                                StripBubble(name: feed.name, freshness: feed.freshness, asking: false, streak: feed.streak) {
                                    FeedCover(url: feed.coverUrl, name: feed.name, size: 54)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }
}

private struct StripBubble<Picture: View>: View {
    let name: String
    let freshness: Freshness?
    let asking: Bool
    var streak: FeedStreakSummary? = nil
    @ViewBuilder var picture: Picture

    var body: some View {
        VStack(spacing: 4) {
            FreshnessRing(freshness: freshness, size: 64) { picture }
                .overlay(alignment: .topTrailing) {
                    if asking { Text("💛").font(.caption).padding(3).background(Circle().fill(Palette.surface)) }
                }
            Text(name).font(.caption.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
            if let f = freshness {
                Text(FreshnessText.label(f)).font(.caption2).foregroundStyle(Palette.textMuted).lineLimit(1)
            }
            if let s = streak, let h = StreakText.headline(s) {
                Text(h).font(.caption2).foregroundStyle(Palette.textMuted).lineLimit(1)
            }
        }
        .frame(width: 84)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([name, freshness.map(FreshnessText.label), asking ? L("engagement.request.asking") : nil].compactMap { $0 }.joined(separator: ", "))
    }
}

/// "Send something to Oma's frame": who asked or how quiet it is, then the ways to send: pick photos (the newest
/// come first in the picker; nothing leaves the phone until picked), On this day and the week's prompt.
struct FreshSendSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let frameId: String
    let requestId: String?
    @State private var request: PhotoRequest?
    @State private var onThisDay: OnThisDay?
    /// Not on the frame: the families whose posts reach it (nil while looking them up).
    @State private var families: [String]?

    private var frame: FrameSummary? { app.frame(frameId) }
    /// On the frame with `send`: photos go to it directly.
    private var onFrame: Bool { frame?.can(\.send) == true }
    /// Families you may post to (not those where you are `member`).
    private var postable: [FeedSummary] { app.feedsEnabled ? app.feeds.filter { $0.role != .member } : [] }
    private var frameName: String { frame?.name ?? request?.frameName ?? app.social.familyRequests[frameId]?.frameName ?? "" }

    /// Where "Pick photos" sends: the frame, or the families reaching it (README §5.20, `PhotoRequest.feed_ids`).
    private var target: FrameRequestTargets.Target? {
        if onFrame { return .frame(frameId) }
        guard let families else { return nil }
        return FrameRequestTargets.target(frameId: frameId, sendableFrames: [], reaching: families, postable: postable.map(\.id))
    }

    /// The families reaching the frame: the request names them; else (a quiet-frame reminder, an older server) the
    /// families you post to that have an active subscription of it.
    private func reachingFamilies() async -> [String] {
        guard let api = app.api else { return [] }
        let r = request ?? app.social.familyRequests[frameId]
        if let ids = r?.feedIds, r?.frameId == frameId, !ids.isEmpty { return ids }
        let frameId = frameId
        return await withTaskGroup(of: String?.self) { g in
            for f in postable {
                let feedId = f.id
                g.addTask {
                    let subs = (try? await api.feedSubscriptions(feedId)) ?? []
                    return subs.contains { $0.status == .active && $0.frame.id == frameId } ? feedId : nil
                }
            }
            var out: [String] = []
            for await id in g { if let id { out.append(id) } }
            return out
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        if let r = request ?? frame?.photoRequest ?? app.social.familyRequests[frameId], r.state == .open {
                            Text(L("engagement.wouldLikeToast", ["frame": r.frameName])).foregroundStyle(Palette.text)
                        } else if let f = frame?.freshness {
                            Text(FreshnessText.label(f)).font(.headline)
                            if let last = FreshnessText.lastShared(f) { Text(last).foregroundStyle(Palette.textMuted) }
                        }
                        if let r = request, r.state == .answered, let by = r.answeredBy {
                            Callout(L("engagement.request.answeredBy", ["name": PersonName.first(by)]), kind: .ok)
                        }
                    }
                    switch target {
                    case .families(let ids)?:
                        let names = ids.compactMap { app.feed($0)?.name }
                        Text(L("engagement.fresh.viaFamilies", ["frame": frameName, "families": ListText.join(names)]))
                            .font(.subheadline).foregroundStyle(Palette.textMuted)
                    case .none?:
                        Callout(L("engagement.fresh.cannotSend", ["frame": frameName]), kind: .info)
                    default:
                        EmptyView()
                    }
                    Button {
                        switch target {
                        case .frame(let id)?: app.composeFrameIds = [id]
                        case .families(let ids)?: app.composeFeedIds = Set(ids)
                        default: return
                        }
                        app.tab = .send
                        dismiss()
                    } label: {
                        if target == nil {
                            ProgressView()
                        } else {
                            Label(L("engagement.choosePhotos"), systemImage: "photo.on.rectangle.angled")
                        }
                    }
                    .buttonStyle(.kf(.primary, large: true, fill: true))
                    .disabled(target == nil || target == .some(.none))
                    Text(L("engagement.fresh.privacy")).font(.footnote).foregroundStyle(Palette.textMuted)
                    if let day = onThisDay, !day.groups.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeader(L("engagement.onThisDay"))
                            ForEach(Array(day.groups.enumerated()), id: \.offset) { _, g in
                                Text(Lp("engagement.otd.yearsAgo", count: g.yearsAgo, ["feed": g.feedName])).font(.subheadline.weight(.semibold))
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 6) {
                                        ForEach(g.items) { item in
                                            ItemThumb(item: item).frame(width: 84, height: 84).clipShape(RoundedRectangle(cornerRadius: Radius.sm))
                                        }
                                    }
                                }
                                if let post = g.postIds.first {
                                    Button(L("engagement.otd.open")) {
                                        dismiss()
                                        app.tab = .home
                                        app.homePath = NavigationPath([PostRoute(postId: post, account: app.accountId)])
                                    }
                                    .buttonStyle(.kf(.secondary))
                                }
                            }
                        }
                    }
                    ForEach(app.feeds.compactMap { f in app.social.prompts[f.id].map { (f, $0) } }, id: \.0.id) { feed, prompt in
                        PromptCard(feed: feed, prompt: prompt)
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(L("engagement.sendSomething", ["frame": frameName]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } } }
            .task {
                guard let api = app.api else { return }
                if let requestId { request = try? await api.photoRequest(requestId) }
                if !onFrame { families = await reachingFamilies() }
                onThisDay = try? await api.onThisDay()
            }
            // Answered or expired meanwhile (also for family members not on the frame).
            .onLiveEvent(app.events) { e in
                guard case .social(let s) = e else { return }
                switch s {
                case .photoRequestCreated(let r), .photoRequestUpdated(let r):
                    if r.frameId == frameId, r.id == requestId || requestId == nil { request = r }
                default: break
                }
            }
        }
    }
}

/// "Your photos were seen 58 times on 3 frames this week; Oma loved 4".
struct WeeklySummarySheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let weekOf: String
    @State private var summary: WeeklySummary?
    @State private var missing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(WeekOf.label(weekOf)).font(.footnote).foregroundStyle(Palette.textMuted)
                    if let s = summary {
                        Text(SummaryText.seen(s)).font(.display(.title2)).foregroundStyle(Palette.text)
                        let details = SummaryText.details(s)
                        if !details.isEmpty { Text(details).foregroundStyle(Palette.textMuted) }
                        if let top = s.topItem {
                            ItemThumb(item: top, large: true).frame(height: 240).clipShape(RoundedRectangle(cornerRadius: Radius.md))
                        }
                        VStack(spacing: 0) {
                            ForEach(Array(s.frames.enumerated()), id: \.offset) { n, f in
                                if n > 0 { Divider().overlay(Palette.border) }
                                HStack {
                                    Text(f.frameName)
                                    Spacer()
                                    Text(Lp("engagement.summary.frameSeen", count: f.seenCount)).foregroundStyle(Palette.textMuted)
                                    if f.loveCount > 0 { Text("❤️ \(f.loveCount)") }
                                }
                                .font(.subheadline)
                                .padding(12)
                            }
                        }
                        .card(padding: 0)
                    } else if missing {
                        Callout(L("engagement.summary.none"))
                    } else {
                        LoadingView()
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(L("engagement.summary.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task {
                do { summary = try await app.api?.weeklySummary(weekOf) } catch { missing = true }
            }
        }
    }
}
