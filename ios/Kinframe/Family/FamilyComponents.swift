import KinframeKit
import SwiftUI

// Pieces shared by Home, Family, Frames and getting started (docs/spec/simple-app.md §3).

/// "Invite family": one link for the family (a reusable join invite, made once and kept), in the share sheet.
@MainActor
@Observable
final class FamilyInviter {
    var shown: FeedInvite?
    var busy = false

    func invite(_ model: FeedDetailModel, toasts: ToastCenter) async {
        if let open = model.invites.first(where: { $0.purpose == .join && $0.maxUses == nil && $0.expiresAt > .now.addingTimeInterval(86_400) }) {
            shown = open
            return
        }
        busy = true
        defer { busy = false }
        do {
            shown = try await model.createInvite(purpose: .join, hours: 720, singleUse: false, note: "")
        } catch {
            toasts.error(error)
        }
    }
}

/// The row under Home's top bar: the family's people, then its frames (a green ring when online), then "Invite".
struct FamilyRow: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    let invite: () -> Void

    private var frames: [FeedSubscription] { model.subscriptions.filter { $0.status == .active } }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(model.members, id: \.userId) { m in
                    Button { if m.userId != app.me?.id { app.social.personSheet = PersonRoute(userId: m.userId, account: app.accountId) } } label: {
                        Bubble(name: m.userId == app.me?.id ? L("social.you") : (m.displayName.split(separator: " ").first.map(String.init) ?? m.displayName)) {
                            AvatarView(name: m.displayName, url: m.avatarUrl, size: 60)
                        }
                    }
                    .buttonStyle(.plain)
                }
                ForEach(frames) { s in
                    Button { if app.frame(s.frame.id) != nil { app.openFrame(s.frame.id) } } label: {
                        Bubble(name: s.frame.name, online: s.frame.online ?? false) {
                            FrameThumb(id: s.frame.id, size: 60, photo: FrameCards.photo(app.frame(s.frame.id)?.nowShowing))
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel([s.frame.name, (s.frame.online ?? false) ? L("frame.online") : L("frame.offline")].joined(separator: ", "))
                }
                if model.abilities?.canCreateJoinInvites == true {
                    Button(action: invite) {
                        Bubble(name: L("simple.home.invite")) {
                            Image(systemName: "plus").font(.title2.weight(.semibold)).foregroundStyle(Palette.primarySoftText)
                                .frame(width: 60, height: 60).background(Circle().fill(Palette.primarySoft))
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("simple.family.invite"))
                }
            }
            .padding(.vertical, 2)
        }
    }

    private struct Bubble<Picture: View>: View {
        let name: String
        var online = false
        @ViewBuilder var picture: Picture

        var body: some View {
            VStack(spacing: 4) {
                picture
                    .clipShape(Circle())
                    .padding(3)
                    .overlay(Circle().strokeBorder(online ? Palette.accent : .clear, lineWidth: 3))
                Text(name).font(.caption.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
            }
            .frame(width: 76)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(name)
        }
    }
}

/// A frame's picture in rows and cards: the photo it shows (`now_showing`, frames you're on) or its calm tint with a
/// miniature frame.
struct FrameThumb: View {
    let id: String
    var size: CGFloat = 60
    var portrait = false
    var photo: String? = nil

    var body: some View {
        let (a, b) = Palette.tint(for: id)
        RadialGradient(colors: [a, b], center: UnitPoint(x: 0.2, y: 0), startRadius: 0, endRadius: size * 1.6)
            .overlay {
                if let photo { RemoteThumb(url: photo) } else { FrameIllustration(portrait: portrait).frame(height: size * 0.55) }
            }
            .frame(width: size, height: size)
            .clipped()
            .accessibilityHidden(true)
    }
}

/// "Add a frame" (§3.4, §5): one of your frames that doesn't show this family yet (shown at once, the last 50
/// posts come along, no questions), or pair a new frame with its code.
struct AddFrameToFamilySheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let feed: FeedSummary
    /// Frames already showing the family (or waiting).
    let showing: Set<String>
    var added: (FeedSubscription) -> Void = { _ in }
    @State private var busy: String?
    @State private var pairing = false

    private var candidates: [FrameSummary] {
        SubscriptionLogic.manageable(app.frames).filter { !showing.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            List {
                if !candidates.isEmpty {
                    Section {
                        ForEach(candidates) { f in
                            Button { Task { await add(f) } } label: {
                                HStack(spacing: 12) {
                                    FrameThumb(id: f.id, size: 44, photo: FrameCards.photo(f.nowShowing)).clipShape(RoundedRectangle(cornerRadius: 10))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(f.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                        OnlineStatus(online: f.online, lastSeen: f.lastSeenAt)
                                    }
                                    Spacer()
                                    if busy == f.id { ProgressView() } else { Image(systemName: "plus.circle").foregroundStyle(Palette.primary) }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(busy != nil)
                        }
                    } header: {
                        Text(L("simple.addFrame.mine"))
                    } footer: {
                        Text(L("simple.addFrame.hint"))
                    }
                    .listRowBackground(Palette.surface)
                }
                Section {
                    Button { pairing = true } label: { Label(L("simple.addFrame.pair"), systemImage: "number") }
                } footer: {
                    Text(L("simple.addFrame.pairHint"))
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("simple.family.addFrame"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } } }
            .sheet(isPresented: $pairing) {
                if let api = app.api {
                    NavigationStack { AddFrameView(api: api, family: feed, onAdded: { s in added(s); dismiss() }) }
                }
            }
        }
    }

    private func add(_ f: FrameSummary) async {
        busy = f.id
        defer { busy = nil }
        if let s = await FamilyFrames.add(frameId: f.id, frameName: f.name, to: feed, app: app) {
            added(s)
            dismiss()
        }
    }
}

enum FamilyFrames {
    /// Ask for the frame to show the family with the default history, and say how it went ("Waiting for an admin of
    /// the family" when it needs approval).
    @MainActor
    static func add(frameId: String, frameName: String, to feed: FeedSummary, app: AppModel) async -> FeedSubscription? {
        guard let api = app.api else { return nil }
        do {
            let s = try await api.requestSubscription(feedId: feed.id, frameId: frameId, history: SimpleDefaults.addFrameHistory)
            app.toasts.show(AddFrameOutcome(s.status).message(frame: frameName, family: feed.name))
            await app.loadFeeds()
            return s
        } catch {
            app.toasts.error(error)
            return nil
        }
    }
}

/// Frame settings › Families on this frame › "Add a family" (§3.3): any family the manager is in that the frame
/// doesn't show yet. Active at once, the last 50 posts come along, no questions.
struct AddFamilyToFrameSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let frame: FrameSummary
    /// Families the frame shows already (or waits for).
    let showing: Set<String>
    var added: (FeedSubscription) -> Void = { _ in }
    @State private var busy: String?

    private var candidates: [FeedSummary] { app.feeds.filter { !showing.contains($0.id) } }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if candidates.isEmpty {
                        Text(L("simple.addFamily.none")).foregroundStyle(Palette.textMuted)
                    }
                    ForEach(candidates) { f in
                        Button { Task { await add(f) } } label: {
                            HStack(spacing: 12) {
                                FeedCover(url: f.coverUrl, name: f.name, size: 44, round: true)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    Text(FamilyText.counts(posts: nil, people: f.memberCount, frames: f.subscriptionCount))
                                        .font(.footnote).foregroundStyle(Palette.textMuted)
                                }
                                Spacer()
                                if busy == f.id { ProgressView() } else { Image(systemName: "plus.circle").foregroundStyle(Palette.primary) }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(busy != nil)
                    }
                } footer: {
                    Text(L("simple.addFamily.hint"))
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("simple.addFamily.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } } }
        }
    }

    private func add(_ f: FeedSummary) async {
        busy = f.id
        defer { busy = nil }
        if let s = await FamilyFrames.add(frameId: frame.id, frameName: frame.name, to: f, app: app) {
            added(s)
            dismiss()
        }
    }
}
