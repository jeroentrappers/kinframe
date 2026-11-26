import KinframeKit
import SwiftUI

/// One send: delivery per frame, each item with its caption (editable) and status, and unsend with undo.
struct SendDetailView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State var model: SendDetailModel
    var onRemoved: () -> Void
    @State private var editing: Item?
    @State private var removed = false
    @State private var commitUnsend: Task<Void, Never>?
    @State private var sharing: ShareRequest?
    @State private var tv: TvRequest?
    @State private var posting = false
    @State private var receipts: FeedPostRoute?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let error = model.error {
                    Callout(error, kind: .error)
                } else if removed {
                    removedCard
                } else if let send = model.send {
                    header(send)
                    if !send.frames.isEmpty { delivery(send) }
                    if let feeds = send.feeds, !feeds.isEmpty { feedDelivery(feeds) }
                    if let source = shareSource(send) {
                        Button {
                            sharing = ShareRequest(source: source)
                        } label: {
                            Label(L("share.action"), systemImage: "arrowshape.turn.up.right")
                        }
                        .buttonStyle(.kf(.secondary))
                    }
                    if !forwardableFeeds(send).isEmpty {
                        Button {
                            posting = true
                        } label: {
                            Label(L("ios.feeds.forward"), systemImage: "rectangle.stack.badge.plus")
                        }
                        .buttonStyle(.kf(.secondary))
                    }
                    if let onTv = TvLogic.sendOnTv(send) {
                        Button {
                            tv = TvRequest(frameId: onTv.frameId, frameName: onTv.frameName, scope: .selection(onTv.placementIds))
                        } label: {
                            Label(L("tv.show"), systemImage: "tv")
                        }
                        .buttonStyle(.kf(.secondary))
                    }
                    ForEach(Array(send.items.enumerated()), id: \.element.id) { n, item in
                        itemRow(item, n: n + 1, send: send)
                    }
                } else {
                    LoadingView()
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .pageTitle(L("detail.title"))
        .toolbar {
            if model.send != nil, !removed {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        unsend()
                    } label: {
                        Label(L("detail.unsend"), systemImage: "trash")
                    }
                    .tint(Palette.danger)
                }
            }
        }
        .task { await model.load() }
        .refreshable { await model.load() }
        .onLiveEvent(app.events) { model.apply($0) }
        .sheet(item: $sharing) { request in
            if let api = app.api {
                ShareToFrameSheet(api: api, source: request.source) { Task { await model.load() } }
            }
        }
        .sheet(isPresented: $posting) {
            if let send = model.send {
                ForwardToFeedSheet(feeds: forwardableFeeds(send)) { ids in
                    do {
                        try await model.forward(toFeeds: ids)
                        app.toasts.show(Lp("ios.feeds.forwarded", count: ids.count))
                        return true
                    } catch {
                        app.toasts.error(error)
                        return false
                    }
                }
            }
        }
        .sheet(item: $receipts) { r in
            if let api = app.api { SendFeedReceiptsSheet(api: api, route: r, feedName: model.send?.feeds?.first { $0.postId == r.postId }?.feedName ?? "") }
        }
        .sheet(item: $tv) { r in
            if let api = app.api { TvSheet(api: api, frameId: r.frameId, frameName: r.frameName, scope: r.scope) }
        }
        .sheet(item: $editing) { item in
            CaptionEditor(item: item, number: (model.send?.items.firstIndex { $0.id == item.id } ?? 0) + 1) { text in
                do {
                    try await model.saveCaption(itemId: item.id, caption: text)
                    app.toasts.show(L("detail.captionSaved"))
                    return true
                } catch {
                    app.toasts.error(error)
                    return false
                }
            }
        }
    }

    private func header(_ send: Send) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Credit.summary(kindSummary(send.items), via: send.via)).font(.display(.title)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            Text(L("detail.sentAt", ["when": Format.dateTime(send.createdAt, locale: L10n.locale)])).foregroundStyle(Palette.textMuted)
        }
    }

    private func delivery(_ send: Send) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Delivery.frames(of: send)) { d in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        StatusDot(online: d.online)
                        Text(d.frameName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                        Spacer()
                        StagePill(stage: d.stage)
                    }
                    Text(L("detail.counts", ["seen": d.counts.seen, "delivered": d.counts.arrived, "total": d.counts.total])
                        + (!d.online && d.stage != .seen ? " · " + L("detail.offlineWaiting") : ""))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(Palette.textMuted)
                    ProgressView(value: d.progress).tint(Palette.accent).accessibilityHidden(true)
                    if let f = send.frames.first(where: { $0.frameId == d.frameId }), case let names = Sharing.sharedBy(f), !names.isEmpty {
                        Label(L("share.sharedBy", ["name": names.joined(separator: ", ")]), systemImage: "arrowshape.turn.up.right")
                            .font(.footnote)
                            .foregroundStyle(Palette.textMuted)
                    }
                    ReactionChips(reactions: d.reactions, replies: d.replies)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .card(padding: 16)
        .accessibilityLabel(L("detail.deliveryLabel"))
    }

    /// The feeds this send was posted to, with their receipts; tap for the per-frame detail.
    private func feedDelivery(_ feeds: [SendFeed]) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(feeds) { f in
                Button {
                    if f.removed != true { receipts = FeedPostRoute(feedId: f.feedId, postId: f.postId, account: app.accountId) }
                } label: {
                    FeedRecipientRow(feed: f).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(f.removed == true ? "" : L("ios.feeds.receiptsOpen"))
            }
        }
        .card(padding: 16)
        .accessibilityLabel(L("ios.feeds.deliveryLabel"))
    }

    /// Feeds you're in that don't have this send yet.
    private func forwardableFeeds(_ send: Send) -> [FeedSummary] {
        guard app.feedsEnabled, send.items.contains(where: { $0.status != .failed }) else { return [] }
        let posted = Set((send.feeds ?? []).filter { $0.removed != true }.map(\.feedId))
        return app.feeds.filter { !posted.contains($0.id) }
    }

    private func itemRow(_ item: Item, n: Int, send: Send) -> some View {
        HStack(alignment: .top, spacing: 14) {
            ItemThumb(item: item)
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay(alignment: .bottomLeading) {
                    if item.kind == .video, let ms = item.durationMs {
                        Text(Format.duration(Double(ms) / 1000)).font(.caption2.weight(.bold).monospacedDigit())
                            .foregroundStyle(.white).padding(.horizontal, 5).padding(.vertical, 2)
                            .background(Capsule().fill(.black.opacity(0.5))).padding(5)
                    }
                }
                .livePlayback(api: app.api, item: item)
                .accessibilityLabel((item.caption ?? L("detail.itemAlt", ["n": n])) + (item.kind == .live ? ". " + L("ios.live.a11y") : ""))
            VStack(alignment: .leading, spacing: 8) {
                if item.status == .failed {
                    Text(failureMessage(item.failureCode))
                        .font(.subheadline.weight(.medium)).foregroundStyle(Palette.danger)
                }
                if let caption = item.caption, !caption.isEmpty {
                    Text(caption).foregroundStyle(Palette.text)
                } else {
                    Text(L("detail.noCaption")).italic().foregroundStyle(Palette.textFaint)
                }
                HStack(spacing: 8) {
                    if item.captionEdited == true { Pill(L("detail.edited")) }
                    Spacer()
                    Button {
                        editing = item
                    } label: {
                        Label(L("common.edit"), systemImage: "pencil")
                    }
                    .buttonStyle(.kf(.ghost))
                    .accessibilityLabel(L("detail.editCaptionN", ["n": n]))
                    // One item of a bigger send, on its own (web: share.actionShort).
                    if send.items.count > 1, item.status != .failed, let source = shareSource(send, items: [item.id]) {
                        Button {
                            sharing = ShareRequest(source: source)
                        } label: {
                            Label(L("share.actionShort"), systemImage: "arrowshape.turn.up.right")
                        }
                        .buttonStyle(.kf(.ghost))
                        .accessibilityLabel("\(L("share.action")) (\(n))")
                    }
                }
                ForEach(send.frames, id: \.frameId) { f in
                    if let p = f.placements.first(where: { $0.itemId == item.id }) {
                        HStack(spacing: 6) {
                            Text(f.frameName).font(.caption).foregroundStyle(Palette.textMuted).lineLimit(1)
                            Spacer()
                            StagePill(stage: Stage(status: p.status, itemStatus: item.status))
                            if let r = p.reaction { Text(r.emoji).accessibilityLabel(L("reaction.\(r.rawValue)")) }
                        }
                    }
                }
            }
        }
        .card(padding: 12)
    }

    /// "Share to another frame": the first frame that still holds every item, when there's another frame to send to.
    private func shareSource(_ send: Send, items: [String]? = nil) -> Sharing.Source? {
        guard let source = Sharing.source(of: send, itemIds: items ?? Sharing.shareableItems(of: send)),
              !Sharing.targets(app.frames, excluding: source.frameId).isEmpty else { return nil }
        return source
    }

    private var removedCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "checkmark.circle.fill").font(.largeTitle).foregroundStyle(Palette.accent).accessibilityHidden(true)
            Text(L("detail.removedTitle")).font(.display(.title2))
            Text(L("detail.removedBody")).foregroundStyle(Palette.textMuted)
            HStack {
                Button(L("detail.removeNow")) { commitNow() }.buttonStyle(.kf(.secondary))
                Button(L("history.title")) { dismiss() }.buttonStyle(.kf(.primary))
            }
        }
        .card(padding: 20)
    }

    /// Undo instead of "are you sure": remove from view now, delete on the server when the toast goes away.
    private func unsend() {
        withAnimation { removed = true }
        app.toasts.show(L("detail.unsent"), action: .init(title: L("common.undo")) {
            commitUnsend?.cancel()
            commitUnsend = nil
            withAnimation { removed = false }
        })
        commitUnsend = Task {
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            await commit()
        }
    }

    private func commitNow() {
        commitUnsend?.cancel()
        commitUnsend = Task { await commit() }
        app.toasts.dismiss()
    }

    private func commit() async {
        do {
            try await model.unsend()
            onRemoved()
        } catch {
            withAnimation { removed = false }
            app.toasts.error(error)
        }
    }
}

private struct CaptionEditor: View {
    @Environment(\.dismiss) private var dismiss
    let item: Item
    let number: Int
    let save: (String) async -> Bool
    @State private var text = ""
    @State private var saving = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                TextField(L("send.captionPlaceholder"), text: $text, axis: .vertical)
                    .lineLimit(3...8)
                    .focused($focused)
                    .padding(12)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Format.captionRemaining(text) < 0 ? Palette.danger : Palette.borderStrong))
                    .accessibilityLabel(L("send.captionFor", ["n": number]))
                Text(L("send.charsLeft", ["n": Format.captionRemaining(text), "max": Format.captionMax]))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Format.captionRemaining(text) < 0 ? Palette.danger : Palette.textMuted)
                Spacer()
            }
            .padding(16)
            .pageBackground()
            .pageTitle(L("detail.editCaptionN", ["n": number]))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.save")) {
                        saving = true
                        Task {
                            if await save(text) { dismiss() }
                            saving = false
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(saving || Format.captionRemaining(text) < 0)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            text = item.caption ?? ""
            focused = true
        }
    }
}

extension Components.Schemas.Item: Identifiable {}

/// Why an item failed (web: failureMessage).
func failureMessage(_ code: String?) -> String {
    let key = "failure.\(code ?? "unknown")"
    return L10n.has(key) ? L(key) : L("failure.unknown")
}

/// "Post to a feed": pick feeds to post an existing send to, without uploading again.
private struct ForwardToFeedSheet: View {
    @Environment(\.dismiss) private var dismiss
    let feeds: [FeedSummary]
    let post: ([String]) async -> Bool
    @State private var chosen: Set<String> = []
    @State private var busy = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("ios.feeds.forwardHint")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                    VStack(spacing: 0) {
                        ForEach(Array(feeds.enumerated()), id: \.element.id) { n, feed in
                            if n > 0 { Divider().overlay(Palette.border).padding(.leading, 60) }
                            let on = chosen.contains(feed.id)
                            Button {
                                if on { chosen.remove(feed.id) } else { chosen.insert(feed.id) }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                        .font(.title2)
                                        .foregroundStyle(on ? Palette.primary : Palette.borderStrong)
                                    FeedCover(url: feed.coverUrl, name: feed.name, size: 36)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(feed.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                        Text(Lp("ios.feeds.frameCount", count: feed.subscriptionCount)).font(.footnote).foregroundStyle(Palette.textMuted)
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal, 14)
                                .frame(minHeight: 60)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                    .card(padding: 0)
                }
                .padding(16)
            }
            .pageBackground()
            .pageTitle(L("ios.feeds.forward"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("ios.feeds.forwardButton")) {
                        busy = true
                        Task {
                            if await post(Recipients.valid(chosen, among: feeds.map(\.id))) { dismiss() }
                            busy = false
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(chosen.isEmpty || chosen.count > Recipients.maxFeeds || busy)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// The per-frame receipts of one of your feed posts, opened from the sent history.
/// A family post whose receipts open from the sent history.
struct FeedPostRoute: Hashable, Identifiable {
    var feedId: String
    var postId: String
    /// The account (`AccountRecord.id`) the screen talks to; nil is the primary account.
    var account: String? = nil
    var id: String { postId }
}

private struct SendFeedReceiptsSheet: View {
    @Environment(\.dismiss) private var dismiss
    let api: API
    let route: FeedPostRoute
    let feedName: String
    @State private var receipts: FeedPostReceipts?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let error {
                        Callout(error, kind: .error)
                    } else if let r = receipts {
                        Text(FeedReceipts.text(r.summary)).font(.headline)
                        ForEach(r.frames, id: \.subscriptionId) { f in
                            HStack(spacing: 8) {
                                StatusDot(online: f.online ?? false)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(f.frameName).font(.body.weight(.semibold))
                                    if let loc = f.frameLocation, !loc.isEmpty { Text(loc).font(.footnote).foregroundStyle(Palette.textMuted) }
                                }
                                Spacer()
                                if let r = f.items.compactMap(\.reaction).first { Text(r.emoji).accessibilityLabel(L("reaction.\(r.rawValue)")) }
                                FeedDeliveryPill(status: f.subscriptionStatus == .paused ? .suspended : FeedReceipts.frameStatus(f.items))
                            }
                            .card(padding: 12)
                            .accessibilityElement(children: .combine)
                        }
                    } else {
                        LoadingView()
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .pageTitle(feedName)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task {
                do {
                    receipts = try await api.feedPostReceipts(route.feedId, postId: route.postId)
                } catch {
                    self.error = errorMessage(error)
                }
            }
        }
    }
}
