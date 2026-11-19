import KinframeKit
import SwiftUI

/// Frame settings → Feeds: the feeds this frame subscribes to, with their status and history option. Frame managers
/// pause (the feed's photos leave the frame until resumed), resume, change the history option, show or hide the
/// feed's birthdays (with a hint when the frame app is too old for them) or unsubscribe, and
/// add a feed they're in or one from a subscribe invite (docs/spec/feeds.md §3 story 8, §4.2).
struct FrameFeedsCard: View {
    @Environment(AppModel.self) private var app
    let api: API
    let frame: FrameSummary
    @Binding var subscriptions: [FeedSubscription]
    @State private var actions: SubscriptionActions
    @State private var adding = false
    @State private var enteringCode = false
    @State private var showEnded = false

    init(api: API, frame: FrameSummary, subscriptions: Binding<[FeedSubscription]>) {
        self.api = api
        self.frame = frame
        _subscriptions = subscriptions
        _actions = State(initialValue: SubscriptionActions(api: api))
    }

    private var current: [FeedSubscription] { subscriptions.filter { SubscriptionLogic.isLive($0.status) } }
    private var ended: [FeedSubscription] { subscriptions.filter { !SubscriptionLogic.isLive($0.status) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("ios.feeds.title"), trailing: frame.can(\.changeSettings) ? AnyView(addMenu) : nil)
            if current.isEmpty {
                Text(L("ios.feeds.frameNone", ["frame": frame.name]))
                    .font(.subheadline)
                    .foregroundStyle(Palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .card(padding: 14)
            }
            ForEach(current) { s in
                SubscriptionRow(subscription: s, side: .frame, busy: actions.busy == s.id, showBirthdays: app.birthdaysEnabled,
                                onBirthdays: { on in Task { await setBirthdays(s, on) } }) { action in
                    actions.start(action, on: s, toasts: app.toasts, apply: replace)
                }
            }
            if current.contains(where: { $0.status == .paused }) {
                Text(L("ios.feeds.pausedHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            if !ended.isEmpty {
                DisclosureGroup(isExpanded: $showEnded) {
                    ForEach(ended) { s in
                        SubscriptionRow(subscription: s, side: .frame) { _ in }
                    }
                } label: {
                    Text(Lp("ios.feeds.endedCount", count: ended.count)).font(.subheadline).foregroundStyle(Palette.textMuted)
                }
            }
        }
        .subscriptionActions(actions, maxAll: app.limits.maxFeedBackfillItems, toasts: app.toasts, apply: replace)
        .onLiveEvent(app.events) { event in
            if case .subscriptionUpdated(_, let frameId, _) = event, frameId == frame.id {
                Task { await reload() }
            }
        }
        .sheet(isPresented: $adding) {
            SubscribeSheet(api: api, mode: .feedFor(frame: frame), existing: subscriptions) { s in
                subscriptions = SubscriptionLogic.sorted(subscriptions.filter { $0.id != s.id } + [s])
            }
        }
        .sheet(isPresented: $enteringCode, onDismiss: { Task { await reload() } }) {
            NavigationStack { AddFrameView(api: api) }
        }
    }

    private var addMenu: some View {
        Menu {
            Button { adding = true } label: { Label(L("ios.feeds.addFromMine"), systemImage: "rectangle.stack") }
                .disabled(app.feeds.isEmpty)
            Button { enteringCode = true } label: { Label(L("ios.feeds.subscribe.withCode"), systemImage: "number") }
        } label: {
            Label(L("ios.feeds.addFeed"), systemImage: "plus")
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: 44)
        }
        .accessibilityLabel(L("ios.feeds.addFeed"))
    }

    private func replace(_ old: FeedSubscription, _ new: FeedSubscription?) {
        if let new, let i = subscriptions.firstIndex(where: { $0.id == old.id }) {
            subscriptions[i] = new
        } else {
            Task { await reload() }
        }
        subscriptions = SubscriptionLogic.sorted(subscriptions)
    }

    /// Show or hide the feed's birthday people on this frame.
    private func setBirthdays(_ s: FeedSubscription, _ on: Bool) async {
        do {
            let updated = try await actions.setBirthdays(s, on)
            replace(s, updated)
            app.toasts.show(L(on ? "ios.bday.on" : "ios.bday.off", ["feed": s.feed.name]))
        } catch {
            app.toasts.error(error)
        }
    }

    private func reload() async {
        if let list = try? await api.frameSubscriptions(frame.id) {
            subscriptions = SubscriptionLogic.sorted(list)
        }
    }
}
