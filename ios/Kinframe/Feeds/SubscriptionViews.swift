import KinframeKit
import SwiftUI

/// "Only new posts", "The last 50", "All": what a subscription places from before it started (history only, never a
/// rolling limit, docs/spec/feeds.md §5.2).
struct HistoryPicker: View {
    @Binding var choice: HistoryChoice
    /// `limits.max_feed_backfill_items`: "All" is capped there, newest first.
    var maxAll: Int
    /// Shown under "All" when the frame is low on storage.
    var storageWarning = false

    private enum Kind: Hashable { case none, lastN, all }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("ios.feeds.history.title"))
            VStack(alignment: .leading, spacing: 12) {
                Picker(L("ios.feeds.history.title"), selection: Binding(get: { kind }, set: { setKind($0) })) {
                    Text(L("ios.feeds.history.noneShort")).tag(Kind.none)
                    Text(L("ios.feeds.history.lastNShort")).tag(Kind.lastN)
                    Text(L("ios.feeds.history.allShort")).tag(Kind.all)
                }
                .pickerStyle(.segmented)
                switch choice {
                case .none:
                    Text(L("ios.feeds.history.noneHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                case .lastN(let n):
                    Stepper(value: Binding(get: { n }, set: { choice = .lastN(HistoryChoice.clamp($0)) }), in: HistoryChoice.countRange,
                            step: n >= 100 ? 50 : 10) {
                        Text(Lp("ios.feeds.history.lastN", count: n)).monospacedDigit()
                    }
                    HStack(spacing: 6) {
                        ForEach(HistoryChoice.presets, id: \.self) { preset in
                            Button("\(preset)") { choice = .lastN(preset) }
                                .buttonStyle(.kf(preset == n ? .primary : .secondary))
                                .accessibilityLabel(Lp("ios.feeds.history.lastN", count: preset))
                        }
                    }
                    Text(L("ios.feeds.history.lastNHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                case .all:
                    Text(L("ios.feeds.history.allHint", ["max": maxAll])).font(.footnote).foregroundStyle(Palette.textMuted)
                    if storageWarning { Callout(L("ios.feeds.history.storage"), kind: .warn) }
                }
            }
            .card(padding: 14)
        }
    }

    private var kind: Kind {
        switch choice {
        case .none: .none
        case .lastN: .lastN
        case .all: .all
        }
    }

    private func setKind(_ k: Kind) {
        switch k {
        case .none: choice = .none
        case .lastN: if case .lastN = choice {} else { choice = .lastN(HistoryChoice.defaultCount) }
        case .all: choice = .all
        }
    }
}

struct SubscriptionStatusPill: View {
    let status: SubscriptionStatus

    static func label(_ s: SubscriptionStatus) -> String {
        L("ios.feeds.status.\(s.rawValue)")
    }

    var body: some View {
        let (icon, fg, bg): (String, Color, Color) = switch status {
        case .pending: ("hourglass", Palette.warn, Palette.warnSoft)
        case .active: ("checkmark", Palette.accentText, Palette.accentSoft)
        case .paused: ("pause.fill", Palette.textMuted, Palette.surface2)
        case .rejected: ("xmark", Palette.danger, Palette.dangerSoft)
        case .removed: ("minus", Palette.textMuted, Palette.surface2)
        }
        Pill(Self.label(status), systemImage: icon, foreground: fg, background: bg)
    }
}

/// One subscription, from the feed side (shows the frame) or the frame side (shows the feed), with what the caller
/// may do with it.
struct SubscriptionRow: View {
    enum Side { case feed, frame }

    let subscription: FeedSubscription
    let side: Side
    var busy = false
    /// Frame side, when the server has birthdays: the "Show birthdays" switch (nil: shown without a switch).
    var showBirthdays = false
    var onBirthdays: ((Bool) -> Void)?
    /// Feed side, a frame on another server (README §5.22): "Block this server".
    var onBlockServer: ((FeedSubscription) -> Void)? = nil
    let perform: (SubscriptionAction) -> Void

    var body: some View {
        let s = subscription
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                if side == .frame {
                    FeedCover(url: s.feed.coverUrl, name: s.feed.name, size: 44)
                } else if s.remote != nil {
                    Image(systemName: "globe").foregroundStyle(Palette.textMuted).accessibilityHidden(true)
                } else {
                    StatusDot(online: s.frame.online ?? false)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(side == .frame ? s.feed.name : s.frame.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(subtitle).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                SubscriptionStatusPill(status: s.status)
            }
            if let p = SubscriptionLogic.backfillProgress(s) {
                ProgressView(value: p) {
                    Text(L("ios.feeds.backfill", ["placed": s.backfill?.placed ?? 0, "total": s.backfill?.total ?? 0]))
                        .font(.caption).foregroundStyle(Palette.textMuted)
                }
                .tint(Palette.accent)
            }
            if showBirthdays, side == .frame, SubscriptionLogic.isLive(s.status) { birthdays }
            let actions = SubscriptionLogic.actions(s)
            if !actions.isEmpty {
                FlowLayout(spacing: 8) {
                    ForEach(actions, id: \.self) { action in
                        Button(role: action.isDestructive ? .destructive : nil) { perform(action) } label: {
                            Label(action.title(side: side), systemImage: action.symbol)
                        }
                        .buttonStyle(.kf(action == .approve ? .primary : action.isDestructive ? .dangerGhost : .secondary))
                        .disabled(busy)
                    }
                }
            }
            if side == .feed, s.remote != nil, let onBlockServer {
                Button(role: .destructive) { onBlockServer(s) } label: {
                    Label(L("servers.remote.block"), systemImage: "nosign")
                }
                .buttonStyle(.kf(.dangerGhost))
                .disabled(busy)
            }
        }
        .card(padding: 14)
        .opacity(s.status == .removed || s.status == .rejected ? 0.7 : 1)
        .accessibilityElement(children: .contain)
    }

    /// "Show birthdays · 12 birthdays", and a hint when the frame app is too old to show them.
    @ViewBuilder
    private var birthdays: some View {
        let s = subscription
        let on = s.birthdays ?? true
        let detail = [Lp("ios.bday.count", count: s.birthdayCount ?? 0), L("ios.bday.subShowHint")].joined(separator: " · ")
        if let onBirthdays, s.canManage == true {
            Toggle(isOn: Binding(get: { on }, set: { onBirthdays($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("ios.bday.frameShow")).font(.subheadline)
                    Text(detail).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Palette.accent)
            .disabled(busy)
        }
        if on, s.frame.birthdaysSupported == false {
            Callout(L("ios.bday.unsupported"), kind: .warn)
        }
    }

    private var subtitle: String {
        let s = subscription
        var parts: [String] = []
        if side == .feed {
            if let r = s.remote { parts.append(L("remote.onServer", ["server": r.host ?? r.serverName])) }
            if let loc = s.frame.location, !loc.isEmpty { parts.append(loc) }
            if s.status == .pending, let who = s.requestedByName ?? s.remote?.requestedByName { parts.append(L("ios.feeds.requestedBy", ["name": who])) }
            if s.viaInvite == true { parts.append(L("ios.feeds.viaInvite")) }
        } else {
            if let admins = s.feed.adminNames, !admins.isEmpty { parts.append(L("ios.feeds.byAdmins", ["names": admins.joined(separator: ", ")])) }
        }
        if SubscriptionLogic.isLive(s.status) { parts.append(HistoryChoice(s).label) }
        if s.status == .removed, let reason = s.removedReason { parts.append(L("ios.feeds.removedReason.\(reason.rawValue)")) }
        return parts.joined(separator: " · ")
    }
}

extension SubscriptionAction {
    var isDestructive: Bool { self == .reject || self == .withdraw || self == .unsubscribe || self == .remove }

    var symbol: String {
        switch self {
        case .approve: "checkmark"
        case .reject: "xmark"
        case .withdraw: "arrow.uturn.backward"
        case .pause: "pause"
        case .resume: "play"
        case .changeHistory: "clock.arrow.circlepath"
        case .unsubscribe, .remove: "minus.circle"
        }
    }

    func title(side: SubscriptionRow.Side) -> String {
        switch self {
        case .approve: L("people.approve")
        case .reject: L("people.deny")
        case .withdraw: L("ios.feeds.act.withdraw")
        case .pause: L("ios.feeds.act.pause")
        case .resume: L("ios.feeds.act.resume")
        case .changeHistory: L("ios.feeds.act.history")
        case .unsubscribe: L("ios.feeds.act.unsubscribe")
        case .remove: L("ios.feeds.act.remove")
        }
    }
}

/// Runs a subscription action against the API and reports back; shared by the feed's Frames section and a frame's
/// Feeds card. Destructive ones ask first (they remove photos from a frame).
@MainActor
@Observable
final class SubscriptionActions {
    let api: API
    private(set) var busy: String?
    var editingHistory: FeedSubscription?
    var confirming: (FeedSubscription, SubscriptionAction)?

    init(api: API) {
        self.api = api
    }

    /// The updated subscription (nil when it was removed), or throws.
    func run(_ action: SubscriptionAction, on s: FeedSubscription) async throws -> FeedSubscription? {
        busy = s.id
        defer { busy = nil }
        switch action {
        case .approve: return try await api.approveSubscription(s.id)
        case .reject: return try await api.rejectSubscription(s.id)
        case .pause: return try await api.pauseSubscription(s.id)
        case .resume: return try await api.resumeSubscription(s.id)
        case .withdraw, .unsubscribe, .remove:
            try await api.removeSubscription(s.id)
            return nil
        case .changeHistory:
            editingHistory = s
            return s
        }
    }

    func setBirthdays(_ s: FeedSubscription, _ on: Bool) async throws -> FeedSubscription {
        busy = s.id
        defer { busy = nil }
        return try await api.setSubscriptionBirthdays(s.id, on)
    }

    func setHistory(_ s: FeedSubscription, _ choice: HistoryChoice) async throws -> FeedSubscription {
        busy = s.id
        defer { busy = nil }
        return try await api.setSubscriptionHistory(s.id, choice)
    }

    static func confirmation(_ action: SubscriptionAction, _ s: FeedSubscription) -> (title: String, message: String)? {
        switch action {
        case .unsubscribe:
            return (L("ios.feeds.confirm.unsubscribeTitle", ["feed": s.feed.name]), L("ios.feeds.confirm.unsubscribeBody", ["frame": s.frame.name]))
        case .remove:
            return (L("ios.feeds.confirm.removeTitle", ["frame": s.frame.name]), L("ios.feeds.confirm.removeBody", ["feed": s.feed.name]))
        case .pause:
            return (L("ios.feeds.confirm.pauseTitle", ["feed": s.feed.name]), L("ios.feeds.confirm.pauseBody", ["frame": s.frame.name]))
        default:
            return nil
        }
    }

    static func done(_ action: SubscriptionAction, _ s: FeedSubscription) -> String {
        switch action {
        case .approve: L("ios.feeds.done.approved", ["frame": s.frame.name])
        case .reject: L("ios.feeds.done.rejected", ["frame": s.frame.name])
        case .pause: L("ios.feeds.done.paused", ["feed": s.feed.name])
        case .resume: L("ios.feeds.done.resumed", ["feed": s.feed.name])
        case .withdraw: L("ios.feeds.done.withdrawn")
        case .unsubscribe, .remove: L("ios.feeds.done.removed", ["feed": s.feed.name, "frame": s.frame.name])
        case .changeHistory: L("common.saved")
        }
    }
}

extension View {
    /// The confirm dialog and the history sheet of `SubscriptionActions`; `apply` gets the result of each action.
    func subscriptionActions(_ actions: SubscriptionActions, maxAll: Int, toasts: ToastCenter,
                             apply: @escaping (FeedSubscription, FeedSubscription?) -> Void) -> some View {
        modifier(SubscriptionActionsModifier(actions: actions, maxAll: maxAll, toasts: toasts, apply: apply))
    }
}

private struct SubscriptionActionsModifier: ViewModifier {
    @Bindable var actions: SubscriptionActions
    let maxAll: Int
    let toasts: ToastCenter
    let apply: (FeedSubscription, FeedSubscription?) -> Void

    func body(content: Content) -> some View {
        content
            .confirmationDialog(
                actions.confirming.flatMap { SubscriptionActions.confirmation($0.1, $0.0)?.title } ?? "",
                isPresented: Binding(get: { actions.confirming != nil }, set: { if !$0 { actions.confirming = nil } }),
                titleVisibility: .visible,
                presenting: actions.confirming
            ) { pair in
                Button(pair.1.title(side: .frame), role: .destructive) {
                    Task { await actions.perform(pair.1, on: pair.0, toasts: toasts, apply: apply) }
                }
                Button(L("common.cancel"), role: .cancel) {}
            } message: { pair in
                Text(SubscriptionActions.confirmation(pair.1, pair.0)?.message ?? "")
            }
            .sheet(item: $actions.editingHistory) { s in
                HistorySheet(subscription: s, maxAll: maxAll) { choice in
                    do {
                        let updated = try await actions.setHistory(s, choice)
                        apply(s, updated)
                        toasts.show(L("ios.feeds.done.history", ["feed": s.feed.name]))
                        return true
                    } catch {
                        toasts.error(error)
                        return false
                    }
                }
            }
    }

}

extension SubscriptionActions {
    /// Ask first when the action needs it, else run it.
    func start(_ action: SubscriptionAction, on s: FeedSubscription, toasts: ToastCenter,
               apply: @escaping (FeedSubscription, FeedSubscription?) -> Void) {
        if SubscriptionActions.confirmation(action, s) != nil {
            confirming = (s, action)
            return
        }
        Task { await perform(action, on: s, toasts: toasts, apply: apply) }
    }

    /// Run it and say how it went (the history option opens its sheet instead).
    func perform(_ action: SubscriptionAction, on s: FeedSubscription, toasts: ToastCenter,
                 apply: (FeedSubscription, FeedSubscription?) -> Void) async {
        do {
            let updated = try await run(action, on: s)
            guard action != .changeHistory else { return }
            apply(s, updated)
            toasts.show(SubscriptionActions.done(action, s))
        } catch {
            toasts.error(error)
        }
    }
}

/// Change the history option of a subscription: going up places older photos quietly, going down takes history
/// items off the frame again (never posts made after it started).
private struct HistorySheet: View {
    @Environment(\.dismiss) private var dismiss
    let subscription: FeedSubscription
    let maxAll: Int
    let save: (HistoryChoice) async -> Bool
    @State private var choice: HistoryChoice = .none
    @State private var saving = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(L("ios.feeds.history.changeBody", ["feed": subscription.feed.name, "frame": subscription.frame.name]))
                        .foregroundStyle(Palette.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                    HistoryPicker(choice: $choice, maxAll: maxAll)
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(L("ios.feeds.act.history"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.save")) {
                        saving = true
                        Task {
                            if await save(choice) { dismiss() }
                            saving = false
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(saving || choice == HistoryChoice(subscription))
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { choice = HistoryChoice(subscription) }
    }
}

/// "Show on a frame": subscribe a frame you manage to a feed. From a feed (pick the frame) or from a frame's
/// settings (pick one of your feeds). Feed admins and auto-approving feeds make it active at once; otherwise a feed
/// admin approves it.
struct SubscribeSheet: View {
    enum Mode {
        /// From a feed: choose among the frames you manage.
        case frameFor(feed: FeedSummary)
        /// From a frame: choose among your feeds.
        case feedFor(frame: FrameSummary)
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let mode: Mode
    /// Subscriptions that already exist, so their frames or feeds are marked.
    var existing: [FeedSubscription] = []
    let done: (FeedSubscription) -> Void
    @State private var chosen: String?
    @State private var history: HistoryChoice = .none
    @State private var birthdays = true
    @State private var busy = false
    @State private var enteringCode = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(intro).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                    choices
                    if chosen != nil {
                        HistoryPicker(choice: $history, maxAll: app.limits.maxFeedBackfillItems)
                        if app.birthdaysEnabled { BirthdaysSwitch(on: $birthdays) }
                        Callout(L("ios.feeds.subscribe.privacy", ["feed": feedName ?? ""]))
                        Text(approvalNote).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                    if case .feedFor = mode {
                        Button {
                            enteringCode = true
                        } label: {
                            Label(L("ios.feeds.subscribe.withCode"), systemImage: "number")
                        }
                        .buttonStyle(.kf(.ghost))
                    }
                }
                .padding(16)
                .frame(maxWidth: 620)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .navigationTitle(L("ios.feeds.subscribe.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("ios.feeds.subscribe.submit")) { Task { await submit() } }
                        .fontWeight(.semibold)
                        .disabled(chosen == nil || busy)
                }
            }
            .sheet(isPresented: $enteringCode) {
                NavigationStack { AddFrameView(api: api) }
            }
        }
    }

    private var intro: String {
        switch mode {
        case .frameFor(let feed): L("ios.feeds.subscribe.introFeed", ["feed": feed.name])
        case .feedFor(let frame): L("ios.feeds.subscribe.introFrame", ["frame": frame.name])
        }
    }

    private var feedName: String? {
        switch mode {
        case .frameFor(let feed): feed.name
        case .feedFor: app.feeds.first { $0.id == chosen }?.name
        }
    }

    private var approvalNote: String {
        switch mode {
        case .frameFor(let feed):
            return feed.role == .poster ? L("ios.feeds.subscribe.needsApproval") : L("ios.feeds.subscribe.instant")
        case .feedFor:
            let role = app.feeds.first { $0.id == chosen }?.role
            return role == .poster ? L("ios.feeds.subscribe.needsApproval") : L("ios.feeds.subscribe.instant")
        }
    }

    /// A frame or a feed already subscribed (pending, active or paused) can't be picked again.
    private func isTaken(_ id: String) -> Bool {
        existing.contains { s in
            guard SubscriptionLogic.isLive(s.status) else { return false }
            if case .frameFor = mode { return s.frame.id == id }
            return s.feed.id == id
        }
    }

    private struct Choice: Identifiable {
        let id: String
        let name: String
        let detail: String
    }

    private var rows: [Choice] {
        switch mode {
        case .frameFor:
            SubscriptionLogic.manageable(app.frames).map { Choice(id: $0.id, name: $0.name, detail: $0.location ?? "") }
        case .feedFor:
            app.feeds.map { Choice(id: $0.id, name: $0.name, detail: Lp("ios.feeds.frameCount", count: $0.subscriptionCount)) }
        }
    }

    @ViewBuilder
    private var choices: some View {
        let list = rows
        if list.isEmpty {
            switch mode {
            case .frameFor: Callout(L("ios.feeds.subscribe.noFrames"), kind: .warn)
            case .feedFor: Callout(L("ios.feeds.subscribe.noFeeds"), kind: .warn)
            }
        } else {
            VStack(spacing: 0) {
                ForEach(Array(list.enumerated()), id: \.element.id) { n, row in
                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 56) }
                    let taken = isTaken(row.id)
                    let on = chosen == row.id
                    Button {
                        chosen = row.id
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: on ? "largecircle.fill.circle" : "circle")
                                .font(.title2)
                                .foregroundStyle(taken ? Palette.textFaint : on ? Palette.primary : Palette.borderStrong)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                Text(taken ? L("ios.feeds.subscribe.already") : row.detail).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(taken)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .card(padding: 0)
        }
    }

    private func submit() async {
        guard let chosen else { return }
        let (feedId, frameId): (String, String) = switch mode {
        case .frameFor(let feed): (feed.id, chosen)
        case .feedFor(let frame): (chosen, frame.id)
        }
        busy = true
        defer { busy = false }
        do {
            let s = try await api.requestSubscription(feedId: feedId, frameId: frameId, history: history,
                                                      birthdays: app.birthdaysEnabled ? birthdays : true)
            app.toasts.show(s.status == .pending ? L("ios.feeds.subscribe.requested", ["feed": s.feed.name])
                                                 : L("ios.feeds.subscribe.active", ["feed": s.feed.name, "frame": s.frame.name]))
            done(s)
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// "Show the feed's birthdays" when subscribing a frame (on unless turned off here).
struct BirthdaysSwitch: View {
    @Binding var on: Bool

    var body: some View {
        Toggle(isOn: $on) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("ios.bday.subShow"))
                Text(L("ios.bday.subShowHint")).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Palette.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .card(padding: 0)
    }
}
