import KinframeKit
import SwiftUI

/// Enter the code shown on a frame. Used from Frames, from "Add a frame" in a family (the paired frame then shows
/// that family at once), when starting a family, for "Join my family" and for `/f/<code>` links. It also takes
/// family invite codes (letters, `KF7Q-M2XA`) and links, and continues as a family invite.
struct AddFrameView: View {
    enum Context {
        /// A sheet; `onboarding` is embedded in a flow (with "Later"); `join` is "Join my family" (a link or code).
        case sheet, onboarding, join
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model: AddFrameModel
    let context: Context
    /// Embedded in a flow (`.onboarding`): continue (to the composer when `send` is true).
    var onFinish: ((_ send: Bool) -> Void)?
    /// The family the paired frame starts showing (§3.4 "Add a frame").
    let family: FeedSummary?
    var onAdded: ((FeedSubscription) -> Void)?
    @State private var added: FeedSubscription?

    private let api: API

    init(api: API, prefill: String? = nil, context: Context = .sheet, family: FeedSummary? = nil,
         onFinish: ((Bool) -> Void)? = nil, onAdded: ((FeedSubscription) -> Void)? = nil) {
        // Family codes only where this server (the account `api` talks to) has families.
        let records = AccountStore.load()
        let server = records.first { $0.server.apiBase == api.baseURL }?.server ?? records.first?.server
        let feeds = server?.discovery.feeds == true
        _model = State(initialValue: AddFrameModel(api: api, prefill: prefill, allowsLetters: feeds))
        self.api = api
        self.context = context
        self.family = family
        self.onFinish = onFinish
        self.onAdded = onAdded
    }

    var body: some View {
        if let preview = model.feedPreview {
            FeedInviteView(api: api, source: .preview(preview), onBack: { model.reset() })
        } else {
            codeEntry
        }
    }

    private var codeEntry: some View {
        ScrollView {
            VStack(spacing: 20) {
                if let result = model.result {
                    ResultPanel(model: model, result: result, context: context, finish: finish)
                        .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
                } else {
                    entry
                        .transition(.opacity)
                }
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .animation(.spring(duration: 0.35), value: model.result != nil)
        }
        .scrollDismissesKeyboard(.interactively)
        .pageBackground()
        .pageTitle(context == .sheet ? L("add.title") : context == .join ? L("simple.start.join") : "")
        .toolbar {
            if context != .onboarding {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common.close")) { dismiss() }
                }
            }
        }
        .task {
            if model.isComplete, model.state == .entering { await submit() }
        }
    }

    private var entry: some View {
        VStack(alignment: .leading, spacing: 18) {
            FrameIllustration()
                .frame(height: 120)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
            Text(context == .join ? L("simple.join.title") : L("add.title"))
                .font(.display(.largeTitle))
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            Text(context == .join ? L("simple.join.body") : L("add.body"))
                .foregroundStyle(Palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            if model.allowsLetters {
                Text(L("ios.feeds.codeHint"))
                    .font(.subheadline)
                    .foregroundStyle(Palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            CodeField(model: model, onComplete: { Task { await submit() } }, onPaste: pasted)

            if let error = model.error {
                Label(error, systemImage: "exclamationmark.circle.fill")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Palette.danger)
                    .accessibilityLabel(error)
            } else {
                Text(L("add.codeHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            }

            Button {
                Task { await submit() }
            } label: {
                HStack(spacing: 8) {
                    if model.state == .checking {
                        ProgressView().tint(Palette.onPrimary)
                        Text(L("add.checking"))
                    } else {
                        Text(model.isFeedCode ? L("ios.feeds.codeSubmit") : L("add.submit"))
                    }
                }
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            .disabled(!model.canSubmit)

            if context == .onboarding {
                Button(L("add.later")) { onFinish?(false) }
                    .buttonStyle(.kf(.ghost, fill: true))
            }

            // Converting an old frame needs a computer with a USB cable, so the phone points to the web page.
            if let url = WebLinks.page("/convert", web: app.discovery?.web) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: "cable.connector").foregroundStyle(Palette.textMuted).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("add.convertHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                        Link(L("add.convertLink"), destination: url).font(.footnote.weight(.semibold))
                    }
                }
                .padding(.top, 8)
            }
        }
    }

    /// A pasted kinframe link: a frame code of this server fills the boxes; an invite, or a link of another server,
    /// opens the way links do (the account on that server, or adding it). Plain text (a code) isn't handled here.
    private func pasted(_ text: String) -> Bool {
        guard let url = DeepLink.find(in: text), let link = DeepLink.parse(url) else { return false }
        let here = link.server.map { s in app.server.map { AccountServers.matches(AccountRecord(id: app.accountId, server: $0), s) } ?? false } ?? true
        if here, case .frameCode(_, let code) = link {
            model.setCode(code)
            if model.isComplete { Task { await submit() } }
            return true
        }
        guard let accounts = app.accounts else { return false }
        if context != .onboarding { dismiss() }
        // After this sheet is gone, so the link's own sheet can show.
        Task {
            try? await Task.sleep(for: .milliseconds(500))
            accounts.openInApp(url)
        }
        return true
    }

    private func submit() async {
        guard let result = await model.redeem() else { return }
        if result.status != .pending { app.updateFrame(result.frame) }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        // A frame you manage now shows the family it was added for, at once (no questions, the last 50 posts).
        if let family, result.status != .pending, result.frame.can(\.changeSettings) {
            added = await FamilyFrames.add(frameId: result.frame.id, frameName: result.frame.name, to: family, app: app)
        }
    }

    private func finish(_ send: Bool, frameId: String?) {
        if let added { onAdded?(added) }
        if context == .onboarding {
            onFinish?(send)
            return
        }
        dismiss()
        if send {
            // Shared with the family it now shows, else sent to the frame only.
            if let family, added != nil { app.composeAfterSheet(family: family.id) } else if let frameId { app.composeAfterSheet(frame: frameId) }
        } else if let frameId, family == nil {
            app.openFrame(frameId)
        }
    }
}

/// The outcome: claimed (name the frame), joined, already a member, or waiting for approval.
private struct ResultPanel: View {
    @Environment(AppModel.self) private var app
    @Bindable var model: AddFrameModel
    let result: RedeemResult
    let context: AddFrameView.Context
    let finish: (_ send: Bool, _ frameId: String?) -> Void
    @FocusState private var nameFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            icon
            switch result.status {
            case .claimed:
                title(L("add.claimedTitle"))
                body(L("add.claimedBody"))
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("frame.name")).font(.subheadline.weight(.semibold))
                    TextField(L("add.namePlaceholder"), text: $model.name)
                        .textFieldStyle(KFFieldStyle())
                        .focused($nameFocused)
                        .submitLabel(.next)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("frame.location")).font(.subheadline.weight(.semibold))
                    TextField(L("add.locationPlaceholder"), text: $model.location)
                        .textFieldStyle(KFFieldStyle())
                        .submitLabel(.done)
                }
                Button {
                    Task { await save() }
                } label: {
                    HStack {
                        if model.saving { ProgressView().tint(Palette.onPrimary) }
                        Text(L("add.saveAndSend"))
                    }
                }
                .buttonStyle(.kf(.primary, large: true, fill: true))
                .disabled(model.saving || model.name.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(L("add.later")) { finish(false, result.frame.id) }
                    .buttonStyle(.kf(.ghost, fill: true))
            case .joined:
                title(L("add.joinedTitle", ["name": result.frame.name]))
                body(L("add.joinedBody", ["owner": result.frame.ownerName ?? L("role.owner")]))
                actions
            case .alreadyMember:
                title(L("add.alreadyTitle"))
                body(L("add.alreadyBody", ["name": result.frame.name]))
                actions
            case .pending:
                title(L("add.pendingTitle"))
                body(L("add.pendingBody", ["name": result.frame.name]))
                Button(context == .onboarding ? L("common.done") : L("add.backToFrames")) { finish(false, nil) }
                    .buttonStyle(.kf(.primary, large: true, fill: true))
                if context == .sheet {
                    Button(L("add.another")) { model.reset() }
                        .buttonStyle(.kf(.ghost, fill: true))
                }
            }
        }
        .card(padding: 20)
        .accessibilityElement(children: .contain)
        .onAppear { if result.status == .claimed { nameFocused = true } }
    }

    private var icon: some View {
        let (symbol, fg, bg): (String, Color, Color) = switch result.status {
        case .claimed: ("sparkles", Palette.accentText, Palette.accentSoft)
        case .joined, .alreadyMember: ("checkmark", Palette.accentText, Palette.accentSoft)
        case .pending: ("hourglass", Palette.warn, Palette.warnSoft)
        }
        return Image(systemName: symbol)
            .font(.title2.weight(.semibold))
            .foregroundStyle(fg)
            .frame(width: 56, height: 56)
            .background(Circle().fill(bg))
            .accessibilityHidden(true)
    }

    private var actions: some View {
        VStack(spacing: 10) {
            if result.frame.permissions.send != false {
                Button(L("add.sendFirst")) { finish(true, result.frame.id) }
                    .buttonStyle(.kf(.primary, large: true, fill: true))
            }
            Button(context == .onboarding ? L("common.done") : L("add.viewFrame")) { finish(false, result.frame.id) }
                .buttonStyle(.kf(.secondary, fill: true))
        }
    }

    private func title(_ text: String) -> some View {
        Text(text).font(.display(.title)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
    }

    private func body(_ text: String) -> some View {
        Text(text).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
    }

    private func save() async {
        do {
            if let frame = try await model.saveName() {
                app.updateFrame(frame)
                app.toasts.show(L("add.named", ["name": frame.name]))
                finish(true, frame.id)
            }
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Eight boxes in two groups over a hidden text field: tap to type, long-press or use Paste.
/// What the 8-character code field edits: adding a frame (or a feed code, letters allowed), or moving a frame to a
/// new device (digits only).
@MainActor
protocol CodeEntryModel: AnyObject, Observable {
    var digits: String { get }
    var formatted: String { get }
    var isComplete: Bool { get }
    var error: String? { get }
    var isBusy: Bool { get }
    /// Feed invite codes (letters) are accepted besides 8-digit frame codes.
    var allowsLetters: Bool { get }
    func setCode(_ input: String)
}

extension CodeEntryModel {
    var allowsLetters: Bool { false }
}

extension AddFrameModel: CodeEntryModel {
    var isBusy: Bool { state == .checking }
}

struct CodeField<Model: CodeEntryModel>: View {
    let model: Model
    var onComplete: () -> Void
    /// Pasted text first goes here; true when it was handled (a link).
    var onPaste: ((String) -> Bool)? = nil
    @FocusState private var focused: Bool
    /// The letter keyboard, for feed codes; frame codes use the number pad.
    @State private var letters = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                TextField("", text: Binding(get: { model.formatted }, set: { new in
                    let wasComplete = model.isComplete
                    model.setCode(new)
                    if model.isComplete, !wasComplete { onComplete() }
                }))
                .keyboardType(letters ? .asciiCapable : .numberPad)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .textContentType(.none)
                .focused($focused)
                .opacity(0.02)
                .accessibilityLabel(L("add.codeLabel"))
                .accessibilityValue(model.formatted)
                .disabled(model.isBusy)

                HStack(spacing: 6) {
                    ForEach(0..<8, id: \.self) { i in
                        if i == 4 { Spacer().frame(width: 10) }
                        box(i)
                    }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
            .onTapGesture { focused = true }

            HStack {
                PasteButton(payloadType: String.self) { strings in
                    guard let text = strings.first else { return }
                    Task { @MainActor in
                        // A pasted link (of this or another server) goes where links go.
                        if onPaste?(text) == true { return }
                        model.setCode(text)
                        if model.isComplete { onComplete() }
                    }
                }
                .labelStyle(.titleAndIcon)
                .buttonBorderShape(.capsule)
                .tint(Palette.primarySoft)
                .foregroundStyle(Palette.primarySoftText)
                .controlSize(.regular)
                Spacer()
                if model.allowsLetters {
                    // Switch between the number pad (frame codes) and letters (feed codes like KF7Q-M2XA).
                    Button {
                        letters.toggle()
                        focused = false
                        Task { @MainActor in focused = true }
                    } label: {
                        Label(letters ? L("ios.feeds.codeDigits") : L("ios.feeds.codeLetters"), systemImage: letters ? "number" : "textformat.abc")
                    }
                    .buttonStyle(.kf(.ghost))
                    .accessibilityHint(L("ios.feeds.codeKeyboardHint"))
                }
            }
        }
        .onAppear {
            if model.digits.isEmpty { focused = true }
            if CodeEntry.isFeed(model.digits) { letters = true }
        }
    }

    private func box(_ i: Int) -> some View {
        let chars = Array(model.digits)
        let char = i < chars.count ? String(chars[i]) : ""
        let active = focused && (i == chars.count || (i == 7 && chars.count == 8))
        return Text(char)
            .font(.system(size: 26, weight: .semibold, design: .rounded).monospacedDigit())
            .foregroundStyle(Palette.text)
            .frame(maxWidth: 44)
            .frame(height: 56)
            .frame(maxWidth: .infinity)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.md, style: .continuous)
                    .strokeBorder(model.error != nil ? Palette.danger : active ? Palette.primary : Palette.borderStrong, lineWidth: active ? 2 : 1)
            )
            .animation(.easeOut(duration: 0.12), value: active)
    }
}

/// A frame with a sun and hills (web: Illustration kind="frame").
struct FrameIllustration: View {
    var portrait = false

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let w = portrait ? h * 0.7 : h * 1.45
            ZStack {
                RoundedRectangle(cornerRadius: h * 0.12, style: .continuous)
                    .fill(Palette.primary)
                RoundedRectangle(cornerRadius: h * 0.07, style: .continuous)
                    .fill(Color(red: 251 / 255, green: 241 / 255, blue: 230 / 255))
                    .padding(h * 0.1)
                    .overlay(alignment: .topTrailing) {
                        Circle().fill(Palette.sun).frame(width: h * 0.2).padding(.top, h * 0.22).padding(.trailing, w * 0.22)
                    }
                    .overlay(alignment: .bottom) {
                        Hills().fill(Palette.hill).frame(height: h * 0.42).padding([.horizontal, .bottom], h * 0.1)
                            .clipShape(RoundedRectangle(cornerRadius: h * 0.07, style: .continuous).inset(by: 0))
                    }
            }
            .frame(width: w, height: h)
            .shadow(color: .black.opacity(0.12), radius: 12, y: 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityHidden(true)
    }
}

struct Hills: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.maxY - r.height * 0.3))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.3, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.52, y: r.minY + r.height * 0.55))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.66, y: r.minY + r.height * 0.3))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        p.closeSubpath()
        return p
    }
}

/// Text field look (web: .input).
struct KFFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.borderStrong))
    }
}
