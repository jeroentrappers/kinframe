import KinframeKit
import SwiftUI

/// Frames (docs/spec/simple-app.md §3.3): a card per frame you manage or are a member of, then the frames that show
/// your families, then "Add a frame".
struct FramesTab: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    @State private var adding = false
    /// Which families each frame shows (live subscriptions), by frame id.
    @State private var shows: [String: [FeedSubscription]] = [:]
    /// Frames that show your families but that you aren't on, with the families they show of yours.
    @State private var familyFrames: [(frame: FeedSubscription.FramePayload, families: [String])] = []

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.framesPath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Every account's frames (docs/spec/multi-server.md §2.2), each opening on its own server.
                    if !app.framesLoaded, accounts.frames.isEmpty {
                        if let error = app.framesError {
                            ErrorRetryView(message: error) { Task { await app.loadFrames() } }
                        } else {
                            LazyVGrid(columns: cardColumns(), spacing: 16) {
                                ForEach(0..<2, id: \.self) { _ in FrameCardSkeleton() }
                            }
                        }
                    } else {
                        if accounts.frames.isEmpty {
                            Text(L("simple.frames.emptyBody")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                        }
                        // One column on phones; two or more on iPad, iPhone Duo unfolded and in landscape.
                        LazyVGrid(columns: cardColumns(), spacing: 16) {
                            ForEach(accounts.frames, id: \.value.id) { owned in
                                let frame = owned.value
                                NavigationLink(value: FrameRoute(id: frame.id, account: owned.model.accountId)) {
                                    FrameCard(frame: frame, shows: shows[frame.id]) { s in
                                        shows[frame.id, default: []].append(s)
                                    }
                                    .overlay(alignment: .topLeading) { ServerChip(model: owned.model).padding(10) }
                                    .environment(owned.model)
                                }
                                .buttonStyle(CardButtonStyle())
                                .accessibilityHint(L("frames.open", ["name": frame.name]))
                            }
                        }
                        if !familyFrames.isEmpty {
                            SectionHeader(L("simple.frames.ofFamilies"))
                            VStack(spacing: 0) {
                                ForEach(Array(familyFrames.enumerated()), id: \.element.frame.id) { n, f in
                                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 70) }
                                    HStack(spacing: 12) {
                                        FrameThumb(id: f.frame.id, size: 44).clipShape(RoundedRectangle(cornerRadius: 10))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(f.frame.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                            Text(FamilyText.shows(f.families)).font(.footnote).foregroundStyle(Palette.textMuted)
                                        }
                                        Spacer()
                                        OnlineStatus(online: f.frame.online ?? false, lastSeen: f.frame.lastSeenAt)
                                    }
                                    .padding(.horizontal, 14)
                                    .frame(minHeight: 64)
                                    .accessibilityElement(children: .combine)
                                }
                            }
                            .card(padding: 0)
                            .frame(maxWidth: PageWidth.text)
                        }
                        Button { adding = true } label: {
                            Label(L("simple.family.addFrame"), systemImage: "plus")
                        }
                        .buttonStyle(.kf(.secondary, large: true, fill: true))
                        .frame(maxWidth: PageWidth.text)
                    }
                }
                .pageColumn(PageWidth.wide)
            }
            .refreshable {
                await accounts.loadFrames()
                await loadShows()
            }
            .pageBackground()
            .navigationTitle(L("simple.nav.frames"))
            .navigationDestination(for: FrameRoute.self) { route in
                Group {
                    if let page = route.page { FramePageView(route: route, page: page) } else { FrameDetailView(route: route) }
                }
                .account(route.account, in: accounts)
            }
            .socialDestinations()
            .sheet(isPresented: $adding, onDismiss: { Task { await loadShows() } }) {
                // Several accounts: on which server the code is first (it's redeemed there).
                ServerChoiceSheet(accounts: accounts.frameAccounts, title: L("add.title"), message: L("servers.choose.code")) { _, api in
                    NavigationStack { AddFrameView(api: api) }
                }
            }
        }
        .task(id: accounts.frames.map(\.value.id)) { await loadShows() }
        .task(id: accounts.feeds.map(\.value.id)) { await loadFamilyFrames() }
        .onLiveEvent(app.events) { e in
            if case .subscriptionUpdated = e { Task { await loadShows() } }
        }
    }

    /// The frames showing your families that you aren't on (Oma's frame in the family of your parents).
    private func loadFamilyFrames() async {
        let mine = Set(accounts.frames.map(\.value.id))
        var found: [String: (frame: FeedSubscription.FramePayload, families: [String])] = [:]
        var order: [String] = []
        for o in accounts.feeds {
            guard let api = o.model.api, let subs = try? await api.feedSubscriptions(o.value.id) else { continue }
            for s in subs where s.status == .active && !mine.contains(s.frame.id) {
                if found[s.frame.id] == nil { order.append(s.frame.id); found[s.frame.id] = (s.frame, []) }
                found[s.frame.id]?.families.append(o.value.name)
            }
        }
        familyFrames = order.compactMap { found[$0] }
    }

    /// The families each frame shows (frames that refuse to say are left without the line).
    private func loadShows() async {
        let frames = accounts.frames
        var next: [String: [FeedSubscription]] = [:]
        await withTaskGroup(of: (String, [FeedSubscription]?).self) { g in
            for owned in frames {
                guard let api = owned.model.api else { continue }
                let id = owned.value.id
                g.addTask { (id, try? await api.frameSubscriptions(id)) }
            }
            for await (id, list) in g { if let list { next[id] = list.filter { SubscriptionLogic.isLive($0.status) } } }
        }
        shows = next
    }
}

struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A frame in the list: the photo it shows now (else its last placed one, else its drawing) with the status chip, the
/// name, today's hearts, which family it shows (or "Add a family" for its managers when it shows none), and what
/// needs doing. On the frame's screen (`large`) the photo is the display rendition.
struct FrameCard: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    let frame: FrameSummary
    var large = false
    /// The live subscriptions of the frame; nil while unknown.
    var shows: [FeedSubscription]? = nil
    var added: (FeedSubscription) -> Void = { _ in }
    @State private var addingFamily = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cover
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text(frame.name)
                        .font(.display(.title3))
                        .foregroundStyle(Palette.text)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    if frame.deviceKind == .browser {
                        Pill(L("frames.browserBadge"), systemImage: "globe")
                    }
                }
                if let loved = FrameCards.lovedTodayText(frame: frame.name, frame.reactionsToday) {
                    Label {
                        Text(loved).lineLimit(1)
                    } icon: {
                        Image(systemName: "heart.fill").foregroundStyle(Palette.primary)
                    }
                    .font(.subheadline)
                    .foregroundStyle(Palette.text)
                    .labelStyle(TightLabelStyle())
                }
                if let shows {
                    let active = shows.filter { $0.status == .active }
                    Label(FamilyText.shows(active.map(\.feed.name)), systemImage: "person.3")
                        .font(.subheadline)
                        .foregroundStyle(active.isEmpty ? Palette.textMuted : Palette.text)
                        .labelStyle(TightLabelStyle())
                    if frame.can(\.changeSettings), active.isEmpty, !app.feeds.isEmpty {
                        Button(L("simple.addFamily.title")) { addingFamily = true }
                            .buttonStyle(.kf(.soft))
                    }
                    ForEach(shows.filter { $0.status == .pending }) { pending in
                        Text(L("simple.addFrame.waiting", ["frame": frame.name, "family": pending.feed.name]))
                            .font(.footnote).foregroundStyle(Palette.warn)
                    }
                }
                if frame.needsUpdate == true {
                    Label(L("frames.needsUpdate"), systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(Palette.warn)
                }
            }
            .padding(16)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .accessibilityElement(children: .contain)
        .sheet(isPresented: $addingFamily) {
            AddFamilyToFrameSheet(frame: frame, showing: Set((shows ?? []).map(\.feed.id))) { added($0) }
        }
    }

    private var cover: some View {
        let (a, b) = Palette.tint(for: frame.id)
        let photo = FrameCards.photo(frame.nowShowing, large: large)
        return ZStack(alignment: .bottomLeading) {
            RadialGradient(colors: [a, b], center: UnitPoint(x: 0.2, y: 0), startRadius: 0, endRadius: 420)
            if let photo {
                RemoteThumb(url: photo).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                FrameIllustration(portrait: frame.screen?.orientation == .portrait)
                    .frame(height: 74)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            FrameStatusChip(frame: frame).padding(10)
        }
        .frame(height: photo == nil ? 128 : (large ? 240 : 180))
        .clipped()
    }
}

/// "Online · showing now" (a photo the frame reported on screen), "Online", or "Offline since 3 hours ago".
struct FrameStatusChip: View {
    let frame: FrameSummary

    var body: some View {
        HStack(spacing: 6) {
            StatusDot(online: frame.online)
            Text(text).font(.caption.weight(.semibold)).foregroundStyle(Palette.text)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(Palette.surface.opacity(0.9)))
    }

    private var text: String { FrameCards.status(online: frame.online, lastSeen: frame.lastSeenAt, frame.nowShowing) }
}

struct TightLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 5) {
            configuration.icon.font(.footnote)
            configuration.title
        }
    }
}

private struct FrameCardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Rectangle().fill(Palette.surface2).frame(height: 128)
            RoundedRectangle(cornerRadius: 6).fill(Palette.surface2).frame(width: 160, height: 18).padding(.horizontal, 16)
            RoundedRectangle(cornerRadius: 6).fill(Palette.surface2).frame(width: 110, height: 14).padding([.horizontal, .bottom], 16)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .redacted(reason: .placeholder)
        .accessibilityLabel(L("common.loading"))
    }
}
