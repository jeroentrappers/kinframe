import KinframeKit
import SwiftUI

/// A feed invite from a link (`/fi/<token>`) or a code: preview, then join the feed (`join`) or subscribe frames
/// you manage to it (`subscribe`, pre-approved, without joining) (docs/api/README.md §5.12).
struct FeedInviteView: View {
    enum Source {
        case token(String)
        case preview(FeedInvitePreview)
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let source: Source
    /// From the code field: back to entering a code.
    var onBack: (() -> Void)?
    @State private var preview: FeedInvitePreview?
    @State private var result: FeedInviteResult?
    @State private var error: String?
    @State private var busy = false
    @State private var chosen: Set<String> = []
    /// No question (docs/spec/simple-app.md §5): the last 50 posts come along, changeable in Frame settings.
    private let history = SimpleDefaults.addFrameHistory
    @State private var birthdays = true
    @State private var idempotencyKey = UUID().uuidString.lowercased()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error {
                    Text(L("invite.invalidTitle")).font(.display(.title))
                    Callout(error, kind: .error)
                    if let onBack {
                        Button(L("ios.feeds.codeAgain"), action: onBack).buttonStyle(.kf(.secondary, fill: true))
                    }
                    Button(L("common.close")) { dismiss() }.buttonStyle(.kf(.ghost, fill: true))
                } else if let result {
                    outcome(result)
                } else if let p = preview {
                    header(p)
                    switch p.purpose {
                    case .join: join(p)
                    case .subscribe: subscribe(p)
                    }
                } else {
                    LoadingView()
                }
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .navigationTitle(L("invite.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } } }
        .task { await load() }
    }

    private func header(_ p: FeedInvitePreview) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            FeedCover(url: p.coverUrl, name: p.feedName, size: 72)
            Text(L("invite.from", ["name": p.invitedByName])).font(.subheadline).foregroundStyle(Palette.textMuted)
            Text(p.purpose == .join ? L("ios.feeds.invite.joinTitle", ["name": p.feedName]) : L("ios.feeds.invite.subscribeTitle", ["name": p.feedName]))
                .font(.display(.largeTitle))
                .accessibilityAddTraits(.isHeader)
            Text([p.ownerName.map { L("ios.feeds.invite.owner", ["name": $0]) }, p.memberCount.map { Lp("ios.feeds.memberCount", count: $0) }]
                .compactMap { $0 }.joined(separator: " · "))
                .font(.subheadline)
                .foregroundStyle(Palette.textMuted)
        }
    }

    // MARK: Join

    @ViewBuilder
    private func join(_ p: FeedInvitePreview) -> some View {
        if p.alreadyMember == true {
            Callout(L("ios.feeds.invite.already"), kind: .ok)
            Button(L("ios.feeds.open")) { openFeedByName(p.feedName) }.buttonStyle(.kf(.primary, large: true, fill: true))
        } else {
            Text(L("ios.feeds.invite.joinBody")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            Text(p.requiresApproval ? L("ios.feeds.invite.needsApproval", ["owner": p.ownerName ?? p.invitedByName]) : L("ios.feeds.invite.instant"))
                .foregroundStyle(Palette.textMuted)
            Button {
                Task { await accept(frameIds: []) }
            } label: {
                HStack { if busy { ProgressView().tint(Palette.onPrimary) }; Text(L("ios.feeds.invite.join")) }
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            .disabled(busy)
            Button(L("invite.notNow")) { dismiss() }.buttonStyle(.kf(.ghost, fill: true))
        }
    }

    // MARK: Subscribe

    @ViewBuilder
    private func subscribe(_ p: FeedInvitePreview) -> some View {
        let frames = p.frames ?? []
        Text(L("ios.feeds.invite.subscribeBody")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
        if frames.isEmpty {
            Callout(L("ios.feeds.subscribe.noFrames"), kind: .warn)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                SectionHeader(L("ios.feeds.subscribe.whichFrames"))
                VStack(spacing: 0) {
                    ForEach(Array(frames.enumerated()), id: \.element.frameId) { n, f in
                        if n > 0 { Divider().overlay(Palette.border).padding(.leading, 56) }
                        let live = f.subscriptionStatus.map(SubscriptionLogic.isLive) ?? false
                        let on = live || chosen.contains(f.frameId)
                        Button {
                            guard !live else { return }
                            if chosen.contains(f.frameId) { chosen.remove(f.frameId) } else { chosen.insert(f.frameId) }
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                    .font(.title2)
                                    .foregroundStyle(live ? Palette.textFaint : on ? Palette.primary : Palette.borderStrong)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.frameName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    if let status = f.subscriptionStatus, live {
                                        Text(SubscriptionStatusPill.label(status)).font(.footnote).foregroundStyle(Palette.textMuted)
                                    }
                                }
                                Spacer()
                            }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 56)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(live)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .card(padding: 0)
            }
            if app.birthdaysEnabled { BirthdaysSwitch(on: $birthdays) }
            Callout(L("ios.feeds.subscribe.privacy", ["feed": p.feedName]))
            Button {
                Task { await accept(frameIds: Array(chosen)) }
            } label: {
                HStack { if busy { ProgressView().tint(Palette.onPrimary) }; Text(Lp("ios.feeds.subscribe.submitN", count: max(1, chosen.count))) }
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            .disabled(busy || chosen.isEmpty)
        }
        Button(L("invite.notNow")) { dismiss() }.buttonStyle(.kf(.ghost, fill: true))
    }

    // MARK: Outcome

    @ViewBuilder
    private func outcome(_ r: FeedInviteResult) -> some View {
        switch r.status {
        case .pending:
            Text(L("invite.pendingTitle")).font(.display(.title))
            Text(L("ios.feeds.invite.pendingBody", ["name": r.feed.name])).foregroundStyle(Palette.textMuted)
            Button(L("common.done")) { dismiss() }.buttonStyle(.kf(.primary, large: true, fill: true))
        case .joined, .alreadyMember:
            Text(L("ios.feeds.invite.joined", ["name": r.feed.name])).font(.display(.title))
            if ViewMode.asked() {
                Text(L("ios.feeds.invite.joinedBody")).foregroundStyle(Palette.textMuted)
                Button(L("simple.joined.open")) {
                    dismiss()
                    app.tab = .home
                }
                .buttonStyle(.kf(.primary, large: true, fill: true))
            } else {
                // Asked once on this device (§6): the normal app, or big photos and a heart.
                ViewModeQuestion { mode in
                    app.accounts?.viewMode = mode
                    app.tab = .home
                    dismiss()
                }
            }
        case .subscribed:
            Text(L("ios.feeds.invite.subscribed", ["name": r.feed.name])).font(.display(.title))
            ForEach(r.subscriptions ?? []) { s in
                HStack {
                    Text(s.frame.name).font(.body.weight(.semibold))
                    Spacer()
                    SubscriptionStatusPill(status: s.status)
                }
                .card(padding: 12)
            }
            Text(L("ios.feeds.invite.subscribedBody")).foregroundStyle(Palette.textMuted)
            Button(L("common.done")) { dismiss() }.buttonStyle(.kf(.primary, large: true, fill: true))
        }
    }

    // MARK: Actions

    private func load() async {
        guard preview == nil, error == nil else { return }
        switch source {
        case .preview(let p):
            preview = p
        case .token(let token):
            do {
                preview = try await api.previewFeedInvite(token: token)
            } catch {
                self.error = errorMessage(error)
            }
        }
        // One frame you manage: pick it.
        if let frames = preview?.frames {
            let open = frames.filter { !($0.subscriptionStatus.map(SubscriptionLogic.isLive) ?? false) }
            if open.count == 1 { chosen = [open[0].frameId] }
        }
    }

    private func accept(frameIds: [String]) async {
        guard let token = preview?.token else { return }
        busy = true
        defer { busy = false }
        do {
            let r = try await api.acceptFeedInvite(token: token, frameIds: frameIds, history: frameIds.isEmpty ? nil : history,
                                                   birthdays: app.birthdaysEnabled ? birthdays : true, idempotencyKey: idempotencyKey)
            result = r
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            if r.status == .joined || r.status == .alreadyMember { app.updateFeed(r.feed) }
        } catch {
            idempotencyKey = UUID().uuidString.lowercased()
            app.toasts.error(error)
        }
    }

    /// "Already a member": the preview has no feed id, so find the feed by name in your list.
    private func openFeedByName(_ name: String) {
        dismiss()
        if let feed = app.feeds.first(where: { $0.name == name }) {
            app.openFamily(feed.id)
        } else {
            app.tab = .family
        }
    }
}
