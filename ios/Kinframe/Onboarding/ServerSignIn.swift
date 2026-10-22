import KinframeKit
import SwiftUI

/// The build's default server, or "Use your own server" with a URL that is validated through discovery.
@MainActor
@Observable
final class ServerChoiceModel {
    enum Choice { case standard, custom }
    enum Check: Equatable {
        case idle, checking
        case found(URL, Discovery)
        case failed(String)
    }

    var choice: Choice = .standard
    var address = ""
    private(set) var standard: Check = .idle
    private(set) var custom: Check = .idle
    private let session: URLSession

    init(session: URLSession = .shared, current: ServerConfig? = nil) {
        self.session = session
        if let current, current.url != ServerAddress.defaultServer {
            choice = .custom
            address = ServerAddress.display(current.url)
            custom = .found(current.url, current.discovery)
        }
    }

    var check: Check { choice == .standard ? standard : custom }

    var found: (URL, Discovery)? {
        if case .found(let url, let d) = check { return (url, d) }
        return nil
    }

    func loadStandard() async {
        guard standard == .idle || isFailed(standard) else { return }
        standard = .checking
        standard = await validate(ServerAddress.defaultServer)
    }

    func checkCustom() async {
        guard let url = ServerAddress.normalize(address) else {
            custom = .failed(L("ios.server.invalid"))
            return
        }
        custom = .checking
        custom = await validate(url)
    }

    func addressChanged() {
        if case .found(let url, _) = custom, ServerAddress.normalize(address) == url { return }
        custom = .idle
    }

    private func isFailed(_ c: Check) -> Bool {
        if case .failed = c { return true }
        return false
    }

    private func validate(_ url: URL) async -> Check {
        do {
            return .found(url, try await DiscoveryClient.fetch(url, session: session))
        } catch DiscoveryError.notKinframe {
            return .failed(L("ios.server.notKinframe"))
        } catch {
            return .failed(L("ios.server.unreachable"))
        }
    }
}

struct ServerChoiceView: View {
    @Environment(AppModel.self) private var app
    @State private var model = ServerChoiceModel()
    @FocusState private var addressFocused: Bool
    var back: () -> Void
    var next: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(L("ios.server.title")).font(.display(.largeTitle)).foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 8)
                Text(L("ios.server.body")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)

                option(.standard) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(ServerAddress.display(ServerAddress.defaultServer)).font(.headline).foregroundStyle(Palette.text)
                        status(model.standard, fallback: L("ios.server.defaultHint"))
                    }
                }

                option(.custom) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(L("ios.server.custom")).font(.headline).foregroundStyle(Palette.text)
                        if model.choice == .custom {
                            HStack(spacing: 8) {
                                TextField(L("ios.server.placeholder"), text: $model.address)
                                    .textFieldStyle(KFFieldStyle())
                                    .keyboardType(.URL)
                                    .textContentType(.URL)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .submitLabel(.go)
                                    .focused($addressFocused)
                                    .onSubmit { Task { await model.checkCustom() } }
                                    .onChange(of: model.address) { model.addressChanged() }
                                    .accessibilityLabel(L("ios.server.addressLabel"))
                                Button(L("ios.server.check")) { Task { await model.checkCustom() } }
                                    .buttonStyle(.kf(.soft))
                                    .disabled(model.address.trimmingCharacters(in: .whitespaces).isEmpty || model.custom == .checking)
                            }
                            status(model.custom, fallback: L("ios.server.customHint"))
                        } else {
                            Text(L("ios.server.customHint")).font(.subheadline).foregroundStyle(Palette.textMuted)
                        }
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 12) {
                Button(action: back) {
                    Image(systemName: "chevron.left").accessibilityLabel(L("ios.back"))
                }
                .buttonStyle(.kf(.secondary, large: true))
                Button(L("ios.continue")) {
                    Task { await proceed() }
                }
                .buttonStyle(.kf(.primary, large: true, fill: true))
                .disabled(model.check == .checking || (model.choice == .custom && model.address.trimmingCharacters(in: .whitespaces).isEmpty))
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .frame(maxWidth: 560)
            .background(Palette.bg.opacity(0.94))
        }
        .pageBackground()
        .task { await model.loadStandard() }
    }

    private func option(_ choice: ServerChoiceModel.Choice, @ViewBuilder content: () -> some View) -> some View {
        let selected = model.choice == choice
        return Button {
            withAnimation(.snappy) { model.choice = choice }
            if choice == .custom { addressFocused = true }
        } label: {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Palette.primary : Palette.borderStrong)
                    .accessibilityHidden(true)
                content()
                Spacer(minLength: 0)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(selected ? Palette.primary : Palette.border, lineWidth: selected ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func status(_ check: ServerChoiceModel.Check, fallback: String) -> some View {
        switch check {
        case .idle:
            Text(fallback).font(.subheadline).foregroundStyle(Palette.textMuted)
        case .checking:
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text(L("add.checking")).font(.subheadline).foregroundStyle(Palette.textMuted)
            }
        case .found(_, let d):
            Label(L("landing.server", ["name": d.name]) + (d.version.isEmpty ? "" : " · \(d.version)"), systemImage: "checkmark.circle.fill")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.accentText)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.circle.fill")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.danger)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func proceed() async {
        if model.choice == .custom, model.found == nil { await model.checkCustom() }
        if model.choice == .standard, model.found == nil { await model.loadStandard() }
        guard let (url, discovery) = model.found else { return }
        await app.choose(server: url, discovery: discovery)
        next()
    }
}

// MARK: Sign in

struct SignInView: View {
    @Environment(AppModel.self) private var app
    @State private var busy = false
    @State private var error: String?
    @State private var devEmail = ""
    @State private var devName = ""
    var changeServer: () -> Void
    var signedIn: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 10) {
                    LogoMark(size: 34)
                    Text("kinframe").font(.display(.title3)).foregroundStyle(Palette.text)
                }
                .padding(.top, 16)
                .accessibilityHidden(true)

                if let d = app.discovery, let url = app.server?.url {
                    HStack(spacing: 10) {
                        StatusDot(online: true)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(L("landing.server", ["name": d.name])).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                            Text(ServerAddress.display(url)).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                        Spacer()
                        Button(L("location.change"), action: changeServer)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Palette.primary)
                            .frame(minHeight: 44)
                    }
                    .card(padding: 14)
                    .accessibilityElement(children: .combine)
                }

                Text(L("landing.signIn")).font(.display(.largeTitle)).foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)

                if app.expired {
                    Callout(L("landing.expired"), kind: .warn)
                }
                if let link = app.pendingLink {
                    Callout(L(link.isInvite ? "landing.reasonInvite" : "landing.reasonFrame"))
                }

                if let d = app.discovery {
                    if d.logtoConfigured {
                        Text(L("landing.signInHint")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                        Button {
                            Task { await signIn() }
                        } label: {
                            HStack(spacing: 8) {
                                if busy { ProgressView().tint(Palette.onPrimary) }
                                Text(L("landing.signIn"))
                            }
                        }
                        .buttonStyle(.kf(.primary, large: true, fill: true))
                        .disabled(busy)
                    } else if !d.devAuth {
                        Callout(L("landing.noAuth"), kind: .warn)
                    }
                    if d.devAuth { devForm }
                }

                if let error {
                    Callout(error, kind: .error)
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .pageBackground()
    }

    private var devForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("landing.devTitle")).font(.headline)
            Text(L("landing.devHint")).font(.subheadline).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            TextField(L("landing.devEmail"), text: $devEmail)
                .textFieldStyle(KFFieldStyle())
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField(L("landing.devName"), text: $devName)
                .textFieldStyle(KFFieldStyle())
                .textContentType(.name)
            Button(L("landing.devSubmit")) { Task { await devSignIn() } }
                .buttonStyle(.kf(.secondary, fill: true))
                .disabled(busy || !devEmail.contains("@"))
        }
        .card(padding: 16)
    }

    private func signIn() async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            let name = try await app.auth.signIn()
            await app.didSignIn(idTokenName: name)
            signedIn()
        } catch {
            if (error as NSError).domain == "com.apple.AuthenticationServices.WebAuthenticationSession", (error as NSError).code == 1 {
                return // Cancelled by the person.
            }
            if String(describing: error).contains("canceled") || String(describing: error).contains("cancelled") { return }
            self.error = L("callback.failed")
        }
    }

    private func devSignIn() async {
        guard let server = app.server else { return }
        busy = true
        error = nil
        defer { busy = false }
        do {
            let api = API(baseURL: server.apiBase, tokens: app.auth)
            let email = devEmail.trimmingCharacters(in: .whitespaces)
            let name = devName.trimmingCharacters(in: .whitespaces)
            let token = try await api.call {
                try await $0.devToken(body: .json(.init(email: email, name: name.isEmpty ? nil : name))).ok.body.json.accessToken
            }
            app.auth.devSignIn(token: token)
            await app.didSignIn(idTokenName: nil)
            signedIn()
        } catch {
            self.error = errorMessage(error)
        }
    }
}

extension DeepLink {
    var isInvite: Bool {
        if case .invite = self { return true }
        return false
    }
}
