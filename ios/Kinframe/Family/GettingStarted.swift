import KinframeKit
import SwiftUI

/// Getting started (docs/spec/simple-app.md §8): signed in without a family, the app offers only these two choices.
struct GettingStartedView: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                LogoMark(size: 56).padding(.top, 32)
                Text(L("simple.start.title")).font(.display(.largeTitle)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                Text(L("simple.start.body")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                choice(symbol: "house.fill", title: L("simple.start.family"), body: L("simple.start.familyBody")) {
                    accounts.startSheet = .start
                }
                choice(symbol: "link", title: L("simple.start.join"), body: L("simple.start.joinBody")) {
                    accounts.startSheet = .join
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .refreshable { await accounts.loadFeeds() }
    }

    private func choice(symbol: String, title: String, body: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: symbol).font(.title2).foregroundStyle(Palette.primarySoftText)
                    .frame(width: 52, height: 52).background(Circle().fill(Palette.primarySoft))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.display(.title3)).foregroundStyle(Palette.text)
                    Text(body).font(.subheadline).foregroundStyle(Palette.textMuted).multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(Palette.textFaint)
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .card(padding: 0)
        }
        .buttonStyle(CardButtonStyle())
    }
}

/// "Start a family": a name (prefilled "<surname> family" when known), then "Add Oma's frame now?" (pair with its
/// code, the frame shows the family at once) or "Later", then the invite to share.
struct StartFamilySheet: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    @Environment(\.dismiss) private var dismiss
    private enum Step { case name, frame, invite }
    @State private var step: Step = .name
    @State private var name = ""
    @State private var busy = false
    @State private var feed: Feed?
    @State private var invite: FeedInvite?
    @FocusState private var focused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .name: nameStep
                case .frame:
                    if let api = app.api, let feed {
                        AddFrameView(api: api, context: .onboarding, family: feed.summary, onFinish: { _ in
                            withAnimation { step = .invite }
                        })
                        .safeAreaInset(edge: .top) {
                            Text(L("simple.start.addFrameNow")).font(.display(.title2)).foregroundStyle(Palette.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 20).padding(.top, 8)
                        }
                    }
                case .invite: inviteStep
                }
            }
            .pageBackground()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(step == .name ? L("common.cancel") : L("common.done")) { dismiss() }
                }
            }
        }
        .interactiveDismissDisabled(busy)
        .onAppear {
            if name.isEmpty { name = FamilyText.suggestedName(for: app.me?.displayName) ?? "" }
        }
        .onDisappear(perform: commit)
    }

    private var nameStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L("simple.start.family")).font(.display(.largeTitle)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                Text(L("simple.start.nameBody")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                TextField(L("simple.start.namePlaceholder"), text: $name)
                    .textFieldStyle(KFFieldStyle())
                    .focused($focused)
                    .submitLabel(.done)
                    .onSubmit { Task { await create() } }
                    .onChange(of: name) { if name.count > 40 { name = String(name.prefix(40)) } }
                Button { Task { await create() } } label: {
                    HStack { if busy { ProgressView().tint(Palette.onPrimary) }; Text(L("simple.start.create")) }
                }
                .buttonStyle(.kf(.primary, large: true, fill: true))
                .disabled(trimmed.isEmpty || busy)
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .onAppear { focused = true }
    }

    private var inviteStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L("simple.start.inviteTitle")).font(.display(.largeTitle)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                Text(L("simple.start.inviteBody", ["family": feed?.name ?? ""])).foregroundStyle(Palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                if let invite, let url = URL(string: invite.url) {
                    ShareLink(item: url, subject: Text(L("ios.feeds.shareJoinTitle", ["name": feed?.name ?? ""])),
                              message: Text(L("ios.feeds.shareJoinText", ["name": feed?.name ?? "", "code": CodeEntry.displayFeedCode(invite.code)]))) {
                        Label(L("simple.family.invite"), systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.kf(.primary, large: true, fill: true))
                    Text(L("simple.start.inviteCode", ["code": CodeEntry.displayFeedCode(invite.code)]))
                        .font(.footnote).foregroundStyle(Palette.textMuted).textSelection(.enabled)
                } else {
                    LoadingView()
                }
                Button(L("simple.start.inviteLater")) { dismiss() }.buttonStyle(.kf(.ghost, fill: true))
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .task {
            guard invite == nil, let api = app.api, let feed else { return }
            invite = try? await api.createFeedInvite(feed.id, purpose: .join, hours: 720, maxUses: nil, note: "")
        }
    }

    private func create() async {
        guard !trimmed.isEmpty, let api = app.api, feed == nil else { return }
        busy = true
        defer { busy = false }
        do {
            // Everyone is family: members see the frames' reactions and may invite (changeable in Settings).
            feed = try await api.createFeed(name: trimmed, autoApprove: false, invitesNeedApproval: true,
                                            membersSeeReactions: true, postersCanInvite: true)
            withAnimation { step = .frame }
        } catch {
            app.toasts.error(error)
        }
    }

    /// The new family becomes the current one once the sheet is gone (the app then leaves getting started).
    private func commit() {
        guard let feed else { return }
        app.updateFeed(feed.summary)
        app.tab = .home
        Task { await app.loadFeeds() }
    }
}

/// "Normal view" or "Simple view: big photos and a heart, for Oma", asked once after joining a family (§6).
struct ViewModeQuestion: View {
    let choose: (ViewMode) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("simple.view.question")).font(.headline).foregroundStyle(Palette.text)
            Button { choose(.normal) } label: {
                Text(L("simple.view.normal")).frame(maxWidth: .infinity)
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            Button { choose(.simple) } label: {
                Text(L("simple.view.simpleChoice")).frame(maxWidth: .infinity).multilineTextAlignment(.center)
            }
            .buttonStyle(.kf(.secondary, large: true, fill: true))
        }
    }
}
