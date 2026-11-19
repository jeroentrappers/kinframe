import KinframeKit
import SwiftUI

/// The frames a feed is shown on. Everyone sees the active and paused ones; owners and admins also see who asked,
/// approve or reject requests, and remove subscriptions. "Show on a frame" subscribes a frame you manage.
struct FeedFramesSection: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var actions: SubscriptionActions
    @State private var subscribing = false

    init(model: FeedDetailModel) {
        self.model = model
        _actions = State(initialValue: SubscriptionActions(api: model.api))
    }

    private var manageable: [FrameSummary] { SubscriptionLogic.manageable(app.frames) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("ios.feeds.framesLede")).font(.subheadline).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                if !manageable.isEmpty {
                    Button {
                        subscribing = true
                    } label: {
                        Label(L("ios.feeds.showOnFrame"), systemImage: "photo.badge.plus")
                    }
                    .buttonStyle(.kf(.primary))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card(padding: 16)

            let pending = model.subscriptions.filter { $0.status == .pending }
            let live = model.subscriptions.filter { $0.status == .active || $0.status == .paused }
            let ended = model.subscriptions.filter { $0.status == .rejected || $0.status == .removed }
            if !pending.isEmpty {
                SectionHeader(L("ios.feeds.waiting"))
                ForEach(pending) { row($0) }
            }
            SectionHeader(L("ios.feeds.onFrames"))
            if live.isEmpty {
                Text(L("ios.feeds.noFrames")).foregroundStyle(Palette.textMuted)
            }
            ForEach(live) { row($0) }
            if !ended.isEmpty {
                SectionHeader(L("ios.feeds.ended"))
                ForEach(ended) { row($0) }
            }
        }
        .subscriptionActions(actions, maxAll: app.limits.maxFeedBackfillItems, toasts: app.toasts) { old, new in
            model.replace(old, with: new)
        }
        .sheet(isPresented: $subscribing) {
            if let feed = model.feed {
                SubscribeSheet(api: model.api, mode: .frameFor(feed: feed.summary), existing: model.subscriptions) { s in
                    model.added(s)
                }
            }
        }
    }

    private func row(_ s: FeedSubscription) -> some View {
        SubscriptionRow(subscription: s, side: .feed, busy: actions.busy == s.id) { action in
            actions.start(action, on: s, toasts: app.toasts) { old, new in model.replace(old, with: new) }
        }
    }
}
