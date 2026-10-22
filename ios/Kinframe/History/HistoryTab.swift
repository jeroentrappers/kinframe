import KinframeKit
import SwiftUI

struct HistoryRoute: Hashable {
    var sendId: String
}

struct HistoryTab: View {
    @Environment(AppModel.self) private var app
    @State private var model: HistoryModel?

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.historyPath) {
            Group {
                if let model {
                    HistoryList(model: model)
                } else {
                    LoadingView()
                }
            }
            .pageBackground()
            .navigationTitle(L("history.title"))
            .navigationDestination(for: HistoryRoute.self) { route in
                if let api = app.api {
                    SendDetailView(model: SendDetailModel(api: api, id: route.sendId, send: model?.sends.first { $0.id == route.sendId }),
                                   onRemoved: { model?.remove(route.sendId) })
                }
            }
        }
        .task(id: app.api?.baseURL) {
            guard let api = app.api else { return }
            if model == nil { model = HistoryModel(api: api) }
            await model?.load()
        }
        .onChange(of: app.historyVersion) { Task { await model?.load() } }
        .onLiveEvent(app.events) { model?.apply($0) }
    }
}

private struct HistoryList: View {
    @Environment(AppModel.self) private var app
    @Bindable var model: HistoryModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                Text(L("history.lede")).foregroundStyle(Palette.textMuted)
                Picker(L("history.filter"), selection: $model.filter) {
                    ForEach(HistoryModel.Filter.allCases) { f in
                        Text(L("history.filter_\(f.rawValue)")).tag(f)
                    }
                }
                .pickerStyle(.segmented)
                .onChange(of: model.filter) { Task { await model.load() } }

                ForEach(app.outbox.active) { job in
                    SendingCard(job: job, onRetry: { app.outbox.retry(job.id) }, onCancel: { app.outbox.cancel(job.id) })
                }

                if model.loading {
                    ForEach(0..<3, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: Radius.lg).fill(Palette.surface2).frame(height: 180)
                    }
                    .accessibilityLabel(L("common.loading"))
                } else if let error = model.error, model.sends.isEmpty {
                    ErrorRetryView(message: error) { Task { await model.load() } }
                } else if model.sends.isEmpty, app.outbox.active.isEmpty {
                    if model.filter == .all {
                        EmptyStateView(systemImage: "paperplane", title: L("history.emptyTitle"), message: L("history.emptyBody")) {
                            Button(L("nav.sendPhotos")) { app.tab = .send }.buttonStyle(.kf(.primary, large: true))
                        }
                        .card(padding: 8)
                    } else {
                        EmptyStateView(systemImage: "tray", title: L("history.emptyFiltered"), message: "")
                    }
                } else {
                    ForEach(model.groups) { group in
                        Text(label(group.bucket))
                            .font(.footnote.weight(.semibold))
                            .textCase(.uppercase)
                            .foregroundStyle(Palette.textFaint)
                            .padding(.top, 6)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(group.sends) { send in
                            NavigationLink(value: HistoryRoute(sendId: send.id)) {
                                SendCard(send: send)
                            }
                            .buttonStyle(CardButtonStyle())
                            .accessibilityHint(L("history.openSend", ["when": Format.dateTime(send.createdAt, locale: L10n.locale)]))
                        }
                    }
                    if model.cursor != nil {
                        Button {
                            Task { await model.loadMore() }
                        } label: {
                            if model.loadingMore { ProgressView() } else { Text(L("common.loadMore")) }
                        }
                        .buttonStyle(.kf(.secondary, fill: true))
                        .onAppear { Task { await model.loadMore() } }
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await model.load() }
    }

    private func label(_ bucket: Format.DayBucket) -> String {
        switch bucket {
        case .today: L("history.today")
        case .yesterday: L("history.yesterday")
        case .day(let d): d.formatted(Date.FormatStyle().weekday(.wide).day().month(.wide).locale(L10n.locale))
        }
    }
}

/// "3 photos and 1 video" (web: kindSummary).
func kindSummary(_ items: [Item]) -> String {
    let (photos, videos) = Delivery.kindCounts(items)
    let parts = [photos > 0 ? Lp("history.photos", count: photos) : nil, videos > 0 ? Lp("history.videos", count: videos) : nil].compactMap { $0 }
    return parts.isEmpty ? Lp("history.photos", count: 0) : parts.joined(separator: " · ")
}

/// A send in the history: thumbnails, what and when, the caption, and per-frame delivery with reactions.
struct SendCard: View {
    let send: Send

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            thumbs
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(kindSummary(send.items)).font(.headline).foregroundStyle(Palette.text)
                    Spacer()
                    Text(Format.relative(send.createdAt, locale: L10n.locale)).font(.subheadline).foregroundStyle(Palette.textMuted)
                }
                if let caption = send.items.compactMap(\.caption).first(where: { !$0.isEmpty }) {
                    Text("“\(caption)”").font(.subheadline).italic().foregroundStyle(Palette.text).lineLimit(2)
                }
                ForEach(Delivery.frames(of: send)) { d in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(d.frameName).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
                            Spacer()
                            StagePill(stage: d.stage, label: pillLabel(d))
                        }
                        ProgressView(value: d.progress).tint(d.stage == .failed ? Palette.danger : Palette.accent)
                            .accessibilityHidden(true)
                        ReactionChips(reactions: d.reactions, replies: d.replies)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(16)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private func pillLabel(_ d: FrameDelivery) -> String? {
        if d.stage == .seen || d.stage == .delivered { return nil }
        if d.counts.arrived > 0 { return L("history.partial", ["done": d.counts.arrived, "total": d.counts.total]) }
        return nil
    }

    private var thumbs: some View {
        let shown = Array(send.items.prefix(4))
        let more = send.items.count - shown.count
        return HStack(spacing: 2) {
            ForEach(Array(shown.enumerated()), id: \.element.id) { n, item in
                ItemThumb(item: item)
                    .overlay {
                        if n == shown.count - 1, more > 0 {
                            ZStack {
                                Color.black.opacity(0.45)
                                Text("+\(more)").font(.title3.weight(.bold).monospacedDigit()).foregroundStyle(.white)
                            }
                        }
                    }
            }
        }
        .frame(height: shown.count == 1 ? 200 : 140)
        .clipped()
        .accessibilityHidden(true)
    }
}

/// An item's thumbnail with processing and failure states.
struct ItemThumb: View {
    let item: Item

    var body: some View {
        Palette.surface2
            .overlay {
                switch item.status {
                case .failed:
                    Image(systemName: "exclamationmark.triangle").foregroundStyle(Palette.danger)
                case .processing:
                    ProgressView()
                case .ready:
                    RemoteThumb(url: item.thumbUrl, kind: item.kind == .video ? .video : .photo)
                }
            }
            .clipped()
    }
}

/// Delivery stage label (web: StagePill).
struct StagePill: View {
    let stage: Stage
    var label: String?

    var body: some View {
        let (icon, fg, bg): (String, Color, Color) = switch stage {
        case .processing: ("clock", Palette.textMuted, Palette.surface2)
        case .onItsWay: ("paperplane", Palette.warn, Palette.warnSoft)
        case .delivered: ("checkmark", Palette.accentText, Palette.accentSoft)
        case .seen: ("eye", Palette.surface, Palette.accent)
        case .failed: ("exclamationmark.triangle", Palette.danger, Palette.dangerSoft)
        case .removed: ("xmark", Palette.textMuted, Palette.surface2)
        }
        Pill(label ?? L("stage.\(stage.rawValue)"), systemImage: icon, foreground: fg, background: bg)
    }
}

/// Reactions and quick replies from frames (web: ReactionChips).
struct ReactionChips: View {
    let reactions: [Reaction?]
    let replies: [ReplyPreset]

    var body: some View {
        let tally = Delivery.tally(reactions)
        let replyTally = ReplyPreset.allCases.compactMap { p -> (ReplyPreset, Int)? in
            let n = replies.filter { $0 == p }.count
            return n > 0 ? (p, n) : nil
        }
        if !tally.isEmpty || !replyTally.isEmpty {
            FlowLayout(spacing: 6) {
                ForEach(tally) { r in
                    HStack(spacing: 4) {
                        Text(r.reaction.emoji)
                        if r.count > 1 { Text("\(r.count)").font(.caption.weight(.semibold).monospacedDigit()) }
                    }
                    .chip()
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L("reaction.\(r.reaction.rawValue)") + (r.count > 1 ? " ×\(r.count)" : ""))
                }
                ForEach(replyTally, id: \.0) { preset, n in
                    HStack(spacing: 4) {
                        Text("💬").accessibilityHidden(true)
                        Text(L("reply.\(preset.rawValue)")).font(.caption.weight(.medium))
                        if n > 1 { Text("×\(n)").font(.caption.monospacedDigit()) }
                    }
                    .chip()
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(L("history.reactions"))
        }
    }
}

extension View {
    func chip() -> some View {
        padding(.horizontal, 10)
            .frame(minHeight: 30)
            .background(Capsule().fill(Palette.surface2))
            .overlay(Capsule().strokeBorder(Palette.border))
            .foregroundStyle(Palette.text)
    }
}

/// Wraps children onto new lines.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0, x + s.width > width { x = 0; y += row + spacing; row = 0 }
            x += s.width + spacing
            maxX = max(maxX, x - spacing)
            row = max(row, s.height)
        }
        return CGSize(width: min(maxX, width), height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX, x + s.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            row = max(row, s.height)
        }
    }
}
