import KinframeKit
import SwiftUI

/// Family › Frames: the frames that show the family. Everyone sees the active and paused ones; owners and admins also
/// see who asked, approve or reject requests, and remove frames. Frames are added with "Add a frame" in the header.
struct FeedFramesSection: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var actions: SubscriptionActions
    /// "Block this server" from a remote frame's row (README §5.22).
    @State private var blockingServer: FeedSubscription?

    init(model: FeedDetailModel) {
        self.model = model
        _actions = State(initialValue: SubscriptionActions(api: model.api))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
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
        .sheet(item: $blockingServer) { s in
            RemoteBlockServerSheet(api: model.api, feedId: model.id, host: s.remote?.host ?? s.remote?.serverUrl ?? "") { _ in
                Task { await model.loadSubscriptions() }
            }
        }
    }

    private func row(_ s: FeedSubscription) -> some View {
        SubscriptionRow(subscription: s, side: .feed, busy: actions.busy == s.id,
                        onBlockServer: app.remoteFamiliesEnabled && s.canDecide == true ? { blockingServer = $0 } : nil) { action in
            actions.start(action, on: s, toasts: app.toasts) { old, new in model.replace(old, with: new) }
        }
    }
}
