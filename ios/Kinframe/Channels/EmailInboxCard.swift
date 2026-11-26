import KinframeKit
import SwiftUI

/// An inbox address or a Telegram chat to open from a notification or a toast (`AppModel.channelFocus`).
enum ChannelFocus: Equatable {
    case emailAddress(String)
    case telegramChat(String)
}

extension SourceLine.Tone {
    var color: Color {
        switch self {
        case .ok: Palette.accentText
        case .muted, .paused: Palette.textMuted
        case .error: Palette.danger
        case .attention: Palette.warn
        }
    }
}

/// The inbox addresses of one frame or feed, kept fresh from `email_address.*` and `email_sender.*`.
@MainActor
@Observable
final class EmailInboxModel {
    let api: API
    let target: ChannelTarget
    private(set) var addresses: [EmailAddress] = []
    private(set) var loaded = false
    var error: String?

    init(api: API, target: ChannelTarget) {
        self.api = api
        self.target = target
    }

    func load() async {
        do {
            addresses = try await api.emailAddresses(target)
            error = nil
        } catch is CancellationError {
        } catch {
            if !loaded { self.error = errorMessage(error) }
        }
        loaded = true
    }

    private func belongs(_ a: EmailAddress) -> Bool { a.channelTarget == target }

    func upsert(_ a: EmailAddress) {
        if let i = addresses.firstIndex(where: { $0.id == a.id }) {
            if belongs(a) { addresses[i] = a } else { addresses.remove(at: i) }
        } else if belongs(a) {
            addresses.append(a)
        }
    }

    func remove(_ id: String) { addresses.removeAll { $0.id == id } }

    func apply(_ event: LiveEvent) {
        switch event {
        case .emailAddressUpdated(let a): upsert(a)
        case .emailAddressRemoved(let id, _, _): remove(id)
        case .emailSenderPending(let id, _), .emailSenderDecided(let id, _, _):
            // Counts changed: the address event usually follows; read it to be sure.
            guard addresses.contains(where: { $0.id == id }) else { return }
            Task { if let a = try? await api.emailAddress(id) { upsert(a) } }
        default:
            break
        }
    }
}

/// "Email photos to this frame" (frame managers) or "Email photos to this feed" (feed owner and admins): the
/// addresses with their status, copy and share, and a way to create one. Tapping an address opens everything else.
/// Hidden by the caller unless discovery says `email_inbox`.
struct EmailInboxCard: View {
    @Environment(AppModel.self) private var app
    let targetName: String
    @State private var model: EmailInboxModel
    @State private var creating = false
    @State private var open: EmailAddress?

    init(api: API, target: ChannelTarget, targetName: String) {
        self.targetName = targetName
        _model = State(initialValue: EmailInboxModel(api: api, target: target))
    }

    private var atLimit: Bool { model.addresses.count >= app.limits.maxEmailAddressesPerTarget }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(model.target.isFeed ? L("ios.em.titleFeed") : L("ios.em.titleFrame"), trailing: !model.addresses.isEmpty && !atLimit ? AnyView(
                Button { creating = true } label: { Label(L("ios.em.new"), systemImage: "plus") }.buttonStyle(.kf(.soft))
            ) : nil)
            if !model.loaded {
                ProgressView().frame(maxWidth: .infinity)
            } else if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else if model.addresses.isEmpty {
                Text(model.target.isFeed ? L("ios.em.introFeed", ["name": targetName]) : L("ios.em.introFrame", ["name": targetName]))
                    .font(.subheadline).foregroundStyle(Palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Button { creating = true } label: { Label(L("ios.em.create"), systemImage: "envelope") }
                    .buttonStyle(.kf(.primary))
            } else {
                ForEach(model.addresses) { a in
                    EmailAddressRow(address: a) { open = a }
                    if a.id != model.addresses.last?.id { Divider() }
                }
                if atLimit {
                    Text(L("ios.em.limit", ["count": "\(app.limits.maxEmailAddressesPerTarget)"])).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
        }
        .card(padding: 16)
        .task { await model.load() }
        .onLiveEvent(app.events) { model.apply($0) }
        .onChange(of: model.loaded) { focusIfAsked() }
        .onChange(of: app.channelFocus) { focusIfAsked() }
        .sheet(isPresented: $creating) {
            EmailCreateSheet(api: model.api, target: model.target, targetName: targetName) { a in
                model.upsert(a)
                // Open the new address once the create sheet is gone.
                Task {
                    try? await Task.sleep(for: .milliseconds(450))
                    open = a
                }
            }
        }
        .sheet(item: $open) { a in
            EmailAddressSheet(api: model.api, address: a) { change in
                switch change {
                case .updated(let a): model.upsert(a)
                case .deleted(let id): model.remove(id)
                }
            }
        }
    }

    /// A notification asked for one of these addresses: open it.
    private func focusIfAsked() {
        guard model.loaded, case .emailAddress(let id)? = app.channelFocus, let a = model.addresses.first(where: { $0.id == id }) else { return }
        app.channelFocus = nil
        open = a
    }
}

/// One address: its label, the address itself (copy and share right here), status, waiting senders.
struct EmailAddressRow: View {
    @Environment(AppModel.self) private var app
    let address: EmailAddress
    let open: () -> Void

    var body: some View {
        let headline = EmailText.headline(address)
        VStack(alignment: .leading, spacing: 8) {
            Button(action: open) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: address.isPaused ? "envelope.badge.shield.half.filled" : "envelope.fill")
                        .font(.title3)
                        .foregroundStyle(Palette.primary)
                        .frame(width: 32, height: 32)
                        .background(Circle().fill(Palette.primarySoft))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(address.name).font(.headline).foregroundStyle(Palette.text)
                        Text(address.address).font(.footnote.monospaced()).foregroundStyle(Palette.textMuted).lineLimit(1).truncationMode(.middle)
                        Text(headline.text).font(.subheadline.weight(.medium)).foregroundStyle(headline.tone.color)
                        let counts = EmailText.counts(address)
                        if !counts.isEmpty {
                            Text(counts.map(\.text).joined(separator: " · ")).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                    Spacer(minLength: 0)
                    if address.pendingCount > 0 {
                        Pill("\(address.pendingCount)", systemImage: "person.badge.clock", foreground: Palette.warn, background: Palette.warnSoft)
                    }
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint).padding(.top, 8)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L("ios.em.openHint"))
            EmailShareButtons(address: address)
        }
        .padding(.vertical, 4)
    }
}

/// Copy (the address only) and Share (a ready-made message in the user's language).
struct EmailShareButtons: View {
    @Environment(AppModel.self) private var app
    let address: EmailAddress

    var body: some View {
        HStack {
            Button {
                UIPasteboard.general.string = address.address
                app.toasts.show(L("ios.em.copied"))
            } label: {
                Label(L("people.copy"), systemImage: "doc.on.doc")
            }
            .buttonStyle(.kf(.secondary))
            ShareLink(item: EmailShare.message(address), subject: Text(EmailShare.subject(address))) {
                Label(L("people.share"), systemImage: "square.and.arrow.up")
            }
            .buttonStyle(.kf(.secondary))
        }
    }
}

/// Create an address: the friendly part (prefilled from the name), the label, who may send; then it opens.
struct EmailCreateSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let target: ChannelTarget
    let targetName: String
    let created: (EmailAddress) -> Void
    @State private var draft: EmailAddressDraft
    @State private var saving = false
    @State private var errorText: String?

    init(api: API, target: ChannelTarget, targetName: String, created: @escaping (EmailAddress) -> Void) {
        self.api = api
        self.target = target
        self.targetName = targetName
        self.created = created
        _draft = State(initialValue: EmailAddressDraft(targetName: targetName))
    }

    private var domain: String? { ChannelFeatures(app.discovery).emailDomain }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        Text(target.isFeed ? L("ios.em.explainFeed", ["name": targetName]) : L("ios.em.explainFrame", ["name": targetName]))
                            .font(.subheadline).foregroundStyle(Palette.textMuted)
                    }
                    Section {
                        TextField(L("ios.em.slugPlaceholder"), text: $draft.slug)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.asciiCapable)
                            .onChange(of: draft.slug) { let t = EmailSlug.typing(draft.slug); if t != draft.slug { draft.slug = t } }
                        Text(EmailSlug.preview(EmailSlug.clean(draft.slug), domain: domain))
                            .font(.footnote.monospaced()).foregroundStyle(Palette.textMuted)
                    } header: {
                        Text(L("ios.em.slug"))
                    } footer: {
                        Text(L("ios.em.slugHint"))
                    }
                    Section {
                        TextField(targetName, text: $draft.name)
                            .onChange(of: draft.name) { if draft.name.count > EmailAddressDraft.nameMax { draft.name = String(draft.name.prefix(EmailAddressDraft.nameMax)) } }
                    } header: {
                        Text(L("ios.em.label"))
                    } footer: {
                        Text(L("ios.em.labelHint"))
                    }
                    Section {
                        Toggle(isOn: $draft.anyoneMaySend) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L("ios.em.set.anyone"))
                                Text(L("ios.em.set.anyoneHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                        }
                    }
                    if let message = errorText ?? draft.problem.map(\.text), !draft.slug.isEmpty || errorText != nil {
                        Section { Text(message).foregroundStyle(Palette.danger) }
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(L("ios.em.createTitle"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(L("ios.em.createButton")) { Task { await save() } }
                            .fontWeight(.semibold)
                            .disabled(draft.problem != nil)
                    }
                }
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            let a = try await api.createEmailAddress(target, draft)
            dismiss()
            app.toasts.show(L("ios.em.createdToast", ["address": a.address]))
            created(a)
        } catch {
            errorText = EmailText.problem(error, limits: app.limits) ?? errorMessage(error)
        }
    }
}

/// How a join request came: "Invited by Anna", "Used the frame's code", "Asked to join from the email link" with
/// whom the mail went to ("Jan · jan.peeters@telenet.be ✓" when their account has that email), "Asked to join from
/// Telegram" with the Telegram name and group.
struct JoinRequestOriginView: View {
    let request: JoinRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(JoinRequestOrigin.line(request).text).font(.subheadline).foregroundStyle(Palette.textMuted)
            if let via = JoinRequestOrigin.channelName(request) {
                HStack(spacing: 4) {
                    Text(via.name).font(.subheadline).foregroundStyle(Palette.text)
                    if via.match?.tone == .ok {
                        Image(systemName: "checkmark.seal.fill").foregroundStyle(Palette.accentText).accessibilityLabel(via.match?.text ?? "")
                    }
                }
                if let match = via.match, match.tone != .ok {
                    Text(match.text).font(.caption).foregroundStyle(match.tone.color)
                }
            }
        }
    }
}
