import KinframeKit
import SwiftUI

/// Welcome → server → sign in → name (only when needed) → first frame. The step is persisted, so onboarding
/// resumes where it stopped.
struct OnboardingFlow: View {
    @Environment(AppModel.self) private var app
    @State private var step: Onboarding.Step = .welcome

    var body: some View {
        ZStack {
            switch step {
            case .welcome:
                WelcomeView { go(.server) }
                    .transition(.opacity)
            case .server:
                ServerChoiceView(back: { go(.welcome) }) { go(.signIn) }
                    .transition(slide)
            case .signIn:
                SignInView(changeServer: { go(.server) }) { Task { await afterSignIn() } }
                    .transition(slide)
            case .name:
                NamePromptView { go(nextAfterName) }
                    .transition(slide)
            case .firstFrame:
                if let api = app.api {
                    NavigationStack {
                        AddFrameView(api: api, context: .onboarding) { send in
                            app.finishOnboarding()
                            if send { app.tab = .send }
                        }
                    }
                    .transition(slide)
                }
            case .done:
                Color.clear.onAppear { app.finishOnboarding() }
            }
        }
        .animation(.spring(duration: 0.4), value: step)
        .onAppear { step = initialStep() }
    }

    private var slide: AnyTransition {
        .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .leading).combined(with: .opacity))
    }

    private func go(_ next: Onboarding.Step) {
        Onboarding.step = next
        step = next
    }

    private func initialStep() -> Onboarding.Step {
        if app.api != nil, app.auth.isSignedIn {
            if app.me?.displayName == PLACEHOLDER_NAME { return .name }
            return nextAfterName
        }
        if app.server == nil { return Onboarding.step == .server ? .server : .welcome }
        let stored = Onboarding.step
        return stored == .welcome || stored == .server ? stored : .signIn
    }

    /// Skip "add your first frame" for people who already have frames, or arrived with a frame or invite link.
    private var nextAfterName: Onboarding.Step {
        app.frames.isEmpty && app.pendingLink == nil ? .firstFrame : .done
    }

    private func afterSignIn() async {
        if app.me?.displayName == PLACEHOLDER_NAME {
            go(.name)
        } else {
            go(nextAfterName)
        }
    }
}

// MARK: Welcome

struct WelcomeView: View {
    var next: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                HStack(spacing: 10) {
                    LogoMark(size: 40)
                    Text("kinframe").font(.display(.title2)).foregroundStyle(Palette.text)
                }
                .padding(.top, 24)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("kinframe")

                VStack(alignment: .leading, spacing: 14) {
                    Text(L("landing.title"))
                        .font(.display(.largeTitle))
                        .foregroundStyle(Palette.text)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    Text(L("landing.lede"))
                        .font(.title3)
                        .foregroundStyle(Palette.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                VStack(alignment: .leading, spacing: 18) {
                    feature("paperplane.fill", L("landing.f1Title"), L("ios.welcome.f1Body"))
                    feature("face.smiling", L("landing.f2Title"), L("landing.f2Body"))
                    feature("lock.shield", L("landing.f3Title"), L("landing.f3Body"))
                }
                .card(padding: 18)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(L("landing.featuresLabel"))
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                Button(L("ios.welcome.start"), action: next)
                    .buttonStyle(.kf(.primary, large: true, fill: true))
                Text(L("landing.footer")).font(.caption).foregroundStyle(Palette.textFaint)
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .frame(maxWidth: 560)
            .background(Palette.bg.opacity(0.94))
        }
        .pageBackground()
    }

    private func feature(_ icon: String, _ title: String, _ body: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.body.weight(.semibold))
                .foregroundStyle(Palette.primarySoftText)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Palette.primarySoft))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline).foregroundStyle(Palette.text)
                Text(body).font(.subheadline).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: Name

/// "What should your family call you?", asked once when the account still has the placeholder name.
struct NamePromptView: View {
    @Environment(AppModel.self) private var app
    @State private var name = ""
    @State private var saving = false
    @FocusState private var focused: Bool
    var next: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: "hand.wave.fill")
                    .font(.title.weight(.semibold))
                    .foregroundStyle(Palette.sun)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(Palette.warnSoft))
                    .padding(.top, 40)
                    .accessibilityHidden(true)
                Text(L("settings.welcomeTitle")).font(.display(.largeTitle)).foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(L("settings.welcomeBody")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("settings.displayName")).font(.subheadline.weight(.semibold))
                    TextField(L("ios.name.placeholder"), text: $name)
                        .textFieldStyle(KFFieldStyle())
                        .textContentType(.givenName)
                        .submitLabel(.done)
                        .focused($focused)
                        .onSubmit { Task { await save() } }
                    Text(L("settings.displayNameHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                }
                Button {
                    Task { await save() }
                } label: {
                    HStack {
                        if saving { ProgressView().tint(Palette.onPrimary) }
                        Text(L("common.save"))
                    }
                }
                .buttonStyle(.kf(.primary, large: true, fill: true))
                .disabled(trimmed.isEmpty || saving)
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .onAppear { focused = true }
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func save() async {
        guard !trimmed.isEmpty else { return }
        saving = true
        await app.updateName(String(trimmed.prefix(40)))
        saving = false
        if app.me?.displayName != PLACEHOLDER_NAME {
            app.toasts.show(L("settings.nameSaved"))
            next()
        } else {
            app.toasts.show(L("problem.server_error"), isError: true)
        }
    }
}
