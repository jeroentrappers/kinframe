import KinframeKit
import SwiftUI

/// Settings → Accounts (docs/spec/multi-server.md §2.1): the servers the app is signed in to, one profile each, the
/// primary one first; "Add a server".
struct AccountsSection: View {
    @Environment(Accounts.self) private var accounts

    var body: some View {
        Section {
            ForEach(accounts.models, id: \.accountId) { m in
                NavigationLink {
                    AccountDetailView(model: m)
                } label: {
                    AccountRow(model: m, primary: m === accounts.primary)
                }
            }
            Button {
                accounts.startAdding()
            } label: {
                Label(L("servers.account.add"), systemImage: "plus.circle")
            }
        } header: {
            Text(L("servers.account.title"))
        } footer: {
            Text(L(accounts.several ? "servers.account.footerSeveral" : "servers.account.footer"))
        }
    }
}

private struct AccountRow: View {
    let model: AppModel
    let primary: Bool

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: model.me?.displayName ?? model.serverName, url: model.me?.avatarUrl, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(model.serverName).font(.body.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
                    if primary { Pill(L("servers.account.primary")) }
                }
                Text(subtitle).font(.footnote).foregroundStyle(model.api == nil ? Palette.warn : Palette.textMuted).lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var subtitle: String {
        if model.api == nil { return L("servers.account.signedOut") }
        let host = model.server.map { ServerAddress.display($0.url) } ?? ""
        guard let name = model.me?.displayName else { return host }
        return "\(name) · \(host)"
    }
}

/// One account: its own Settings page (profile, notifications, server, sign out, delete), plus making it primary.
private struct AccountDetailView: View {
    @Environment(Accounts.self) private var accounts
    let model: AppModel

    var body: some View {
        if model.api == nil {
            Form {
                Section {
                    Text(L("servers.account.signedOutBody", ["server": model.serverName])).foregroundStyle(Palette.textMuted)
                    Button(L("servers.account.signInAgain")) { accounts.signInAgain(model) }
                    Button(L("servers.account.remove"), role: .destructive) { Task { await accounts.signOut(model) } }
                }
            }
            .navigationTitle(model.serverName)
        } else {
            SettingsTab(embedded: true)
                .environment(model)
                .safeAreaInset(edge: .bottom) {
                    if accounts.several, model !== accounts.primary {
                        Button(L("servers.account.makePrimary")) { accounts.makePrimary(model) }
                            .buttonStyle(.kf(.secondary, fill: true))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Palette.bg.opacity(0.94))
                    }
                }
        }
    }
}

/// "Add a server": choose or type the address (skipped when a link named it), then sign in there. The new account's
/// model is in the environment, so the onboarding screens work unchanged.
struct AddServerSheet: View {
    @Environment(Accounts.self) private var accounts
    let request: Accounts.AddServerRequest
    @State private var step: Step = .choose
    @State private var error: String?

    enum Step { case choose, checking, signIn }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .choose:
                    ServerChoiceView(back: { accounts.cancelAdding(request) }, next: { checkDuplicate() })
                case .checking:
                    VStack(spacing: 14) {
                        if let error {
                            Callout(error, kind: .error)
                            Button(L("common.close")) { accounts.cancelAdding(request) }.buttonStyle(.kf(.secondary))
                        } else {
                            ProgressView()
                            Text(L("add.checking")).foregroundStyle(Palette.textMuted)
                        }
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .pageBackground()
                case .signIn:
                    VStack(spacing: 0) {
                        if let link = request.link, let server = request.server {
                            Callout(L(link.isInvite || link.isFeedInvite ? "servers.account.linkFamily" : "servers.account.linkFrame",
                                      ["server": ServerAddress.display(server)]))
                                .padding([.horizontal, .top], 24)
                        }
                        SignInView(changeServer: { step = .choose }, signedIn: { accounts.finishAdding(request) })
                    }
                }
            }
            .environment(request.model)
            .navigationTitle(L("servers.account.add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common.cancel")) { accounts.cancelAdding(request) }
                }
            }
        }
        .task {
            // A link (or "Sign in again") named the server: straight to signing in.
            guard step == .choose, let server = request.server else { return }
            step = .checking
            if request.model.server?.url == server {
                step = .signIn
                return
            }
            do {
                let discovery = try await DiscoveryClient.fetch(server)
                await request.model.choose(server: server, discovery: discovery)
                checkDuplicate()
            } catch {
                self.error = L("ios.server.unreachable")
            }
        }
        .interactiveDismissDisabled(step == .checking && error == nil)
    }

    private func isFeedInvite(_ link: DeepLink) -> Bool {
        if case .feedInvite = link { return true }
        return false
    }

    /// The same server under another address (`server_id`) or one already added: no second account.
    private func checkDuplicate() {
        guard let server = request.model.server else { return }
        if let other = accounts.existing(server.url, discovery: server.discovery), other !== request.model, other.api != nil {
            step = .checking
            error = L("servers.account.duplicate", ["server": other.serverName])
            return
        }
        error = nil
        step = .signIn
    }
}

/// Sheets of the other accounts (a link they handle, a family-social sheet, a person), each with that account's
/// model in the environment. The primary account's are on `MainTabs`.
struct SecondaryAccountPresenters: View {
    @Environment(Accounts.self) private var accounts

    var body: some View {
        ZStack {
            ForEach(accounts.models.dropFirst(), id: \.accountId) { m in
                SecondaryPresenter(model: m)
            }
        }
        .allowsHitTesting(false)
    }
}

private struct SecondaryPresenter: View {
    @Bindable var model: AppModel

    var body: some View {
        Color.clear
            .sheet(item: $model.activeLink) { link in
                DeepLinkSheet(link: link).environment(model)
            }
            .background {
                Color.clear
                    .sheet(item: Binding(get: { model.social.sheet }, set: { model.social.sheet = $0 })) { sheet in
                        Group {
                            switch sheet {
                            case .freshSend(let frameId, let requestId): FreshSendSheet(frameId: frameId, requestId: requestId)
                            case .weeklySummary(let week): WeeklySummarySheet(weekOf: week)
                            case .operatorReports: ReportsView(feedId: nil)
                            case .feedReports(let feedId): ReportsView(feedId: feedId)
                            }
                        }
                        .environment(model)
                    }
            }
            .background {
                Color.clear
                    .sheet(item: Binding(get: { model.social.personSheet }, set: { model.social.personSheet = $0 })) { route in
                        NavigationStack {
                            PersonView(userId: route.userId, inSheet: true)
                                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { model.social.personSheet = nil } } }
                        }
                        .environment(model)
                    }
            }
    }
}
