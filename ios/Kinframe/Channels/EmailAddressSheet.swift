import KinframeKit
import SwiftUI

/// One inbox address: the address with Copy and Share, people waiting for approval with their held mail, allowed and
/// blocked senders, settings, recent mail, and renaming, a new friendly part, pause, regenerate, take over and delete.
struct EmailAddressSheet: View {
    enum Change {
        case updated(EmailAddress)
        case deleted(String)
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let changed: (Change) -> Void
    @State private var address: EmailAddress
    @State private var senders: [EmailSender] = []
    @State private var pending: [EmailPendingSender] = []
    @State private var loaded = false
    @State private var busy = false
    @State private var addingSender = false
    @State private var editing: EmailEditSheet.Field?
    @State private var renamingSender: EmailSender?
    @State private var rejecting: EmailPendingSender?
    @State private var confirmRegenerate = false
    @State private var confirmDelete = false

    init(api: API, address: EmailAddress, changed: @escaping (Change) -> Void) {
        self.api = api
        self.changed = changed
        _address = State(initialValue: address)
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    header
                    if !pending.isEmpty { pendingSection }
                    sendersSection
                    settingsSection
                    Section {
                        NavigationLink {
                            EmailRecentMailView(api: api, address: address)
                        } label: {
                            Label(L("ios.em.recent"), systemImage: "clock.arrow.circlepath")
                        }
                    }
                    manageSection
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(address.name)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } }
            }
            .task { await load() }
            .refreshable { await load() }
            .onLiveEvent(app.events) { handle($0) }
            .sheet(isPresented: $addingSender) {
                EmailAddSenderSheet(api: api, address: address) { s in
                    senders.removeAll { $0.id == s.id || $0.email.lowercased() == s.email.lowercased() }
                    senders.append(s)
                    Task { await load() }
                }
            }
            .sheet(item: $editing) { field in
                EmailEditSheet(api: api, address: address, field: field) { update($0) }
            }
            .sheet(item: $renamingSender) { s in
                EmailRenameSenderSheet(api: api, addressId: address.id, sender: s) { renamed in
                    if let i = senders.firstIndex(where: { $0.id == renamed.id }) { senders[i] = renamed }
                }
            }
            .confirmationDialog(L("ios.em.rejectTitle", ["name": rejecting?.label ?? ""]), isPresented: Binding(get: { rejecting != nil }, set: { if !$0 { rejecting = nil } }),
                                titleVisibility: .visible, presenting: rejecting) { p in
                Button(L("ios.em.reject"), role: .destructive) { Task { await reject(p, block: false) } }
                Button(L("ios.em.rejectBlock"), role: .destructive) { Task { await reject(p, block: true) } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: { _ in
                Text(L("ios.em.rejectBody"))
            }
            .alert(L("ios.em.regenerateTitle"), isPresented: $confirmRegenerate) {
                Button(L("ios.em.regenerate"), role: .destructive) { Task { await regenerate() } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(L("ios.em.regenerateBody", ["address": address.address]))
            }
            .alert(L("ios.em.deleteTitle", ["address": address.address]), isPresented: $confirmDelete) {
                Button(L("ios.em.delete"), role: .destructive) { Task { await delete() } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(L("ios.em.deleteBody"))
            }
        }
    }

    // MARK: Sections

    private var header: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(address.address)
                    .font(.body.monospaced())
                    .foregroundStyle(Palette.text)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                let headline = EmailText.headline(address)
                Text(headline.text).font(.subheadline.weight(.medium)).foregroundStyle(headline.tone.color)
                EmailShareButtons(address: address)
            }
            .padding(.vertical, 4)
            if address.isPaused {
                Callout(L("ios.em.pausedNote"), kind: .warn)
            }
            if !address.hasReplies {
                Callout(L("ios.em.noReplies"), kind: .info)
            }
        } footer: {
            Text(address.isFeedTarget ? L("ios.em.explainFeed", ["name": address.targetName]) : L("ios.em.explainFrame", ["name": address.targetName]))
        }
    }

    /// Each pending sender as a few rows: who and how many, each held mail (opens it), then the decision.
    private var pendingSection: some View {
        Section {
            ForEach(pending) { p in
                VStack(alignment: .leading, spacing: 4) {
                    Text(EmailText.pending(p).text).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                    if !p.authenticated {
                        Text(EmailText.unverified(p.email).text).font(.footnote).foregroundStyle(Palette.warn)
                    }
                }
                .padding(.top, 4)
                ForEach(p.messages) { m in
                    NavigationLink {
                        EmailHeldMessageView(api: api, addressId: address.id, summary: m, pendingSenderId: p.authenticated ? p.id : nil) {
                            Task { await load() }
                        }
                    } label: {
                        HeldMessageRow(message: m)
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    if p.authenticated {
                        HStack {
                            Button(L("ios.em.approve")) { Task { await approve(p) } }.buttonStyle(.kf(.primary))
                            Button(L("ios.em.reject")) { rejecting = p }.buttonStyle(.kf(.ghost))
                        }
                        .disabled(busy)
                        if p.hasUnverified {
                            Text(L("ios.em.approveUnverifiedNote")).font(.caption).foregroundStyle(Palette.textMuted)
                        }
                    } else {
                        Text(L("ios.em.unverifiedOnly")).font(.caption).foregroundStyle(Palette.textMuted)
                    }
                }
                .padding(.bottom, 4)
            }
        } header: {
            Text(L("ios.em.pendingTitle"))
        }
    }

    private var sendersSection: some View {
        Section {
            Text(EmailText.membersNote(address).text).font(.footnote).foregroundStyle(Palette.textMuted)
            if !loaded {
                ProgressView().frame(maxWidth: .infinity)
            }
            ForEach(senders) { s in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.displayName ?? s.email).foregroundStyle(s.isBlocked ? Palette.textMuted : Palette.text)
                        if s.displayName != nil {
                            Text(s.email).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                    Spacer(minLength: 0)
                    if s.isBlocked {
                        Pill(L("ios.em.blocked"), foreground: Palette.danger, background: Palette.dangerSoft)
                    }
                    Menu {
                        Button { renamingSender = s } label: { Label(L("ios.em.renameSender"), systemImage: "pencil") }
                        if s.isBlocked {
                            Button { Task { await setBlocked(s, false) } } label: { Label(L("ios.em.unblock"), systemImage: "hand.thumbsup") }
                        } else {
                            Button { Task { await setBlocked(s, true) } } label: { Label(L("ios.em.block"), systemImage: "hand.raised") }
                        }
                        Button(role: .destructive) { Task { await removeSender(s) } } label: { Label(L("ios.em.removeSender"), systemImage: "trash") }
                    } label: {
                        Image(systemName: "ellipsis.circle").font(.title3).frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("ios.em.senderActions", ["name": s.label]))
                }
            }
            Button { addingSender = true } label: { Label(L("ios.em.addSender"), systemImage: "person.badge.plus") }
                .disabled(senders.count >= app.limits.maxEmailSendersPerAddress)
        } header: {
            Text(L("ios.em.sendersTitle"))
        } footer: {
            Text(address.anyoneMaySend ? L("ios.em.sendersFooterOpen") : L("ios.em.sendersFooter"))
        }
    }

    private var settingsSection: some View {
        Section {
            toggle(L("ios.em.set.anyone"), L("ios.em.set.anyoneHint"), address.anyoneMaySend) { .anyoneMaySend($0) }
            toggle(L("ios.em.set.confirmations"), address.hasReplies ? L("ios.em.set.confirmationsHint") : L("ios.em.noReplies"),
                   address.sendConfirmations) { .sendConfirmations($0) }
                .disabled(!address.hasReplies)
            toggle(L("ios.em.set.invite"), L("ios.em.set.inviteHint", ["name": address.targetName]), address.offerAppInvite) { .offerAppInvite($0) }
                .disabled(!address.hasReplies)
            if address.mayChangeNotifyReactions {
                toggle(L("ios.em.set.reactions"), L("ios.em.set.reactionsHint"), address.notifyReactions) { .notifyReactions($0) }
            }
        } header: {
            Text(L("ios.em.settingsTitle"))
        } footer: {
            if let owner = address.ownerName {
                Text(address.isOwner == true ? L("ios.em.ownerYou") : L("ios.em.ownerOther", ["name": owner]))
            }
        }
    }

    private var manageSection: some View {
        Section {
            Button { editing = .name } label: { Label(L("ios.em.rename"), systemImage: "character.cursor.ibeam") }
            Button { editing = .slug } label: { Label(L("ios.em.changeSlug"), systemImage: "at") }
            if address.isPaused {
                Button { Task { await change(.paused(false), done: L("ios.em.resumed")) } } label: { Label(L("ios.em.resume"), systemImage: "play") }
            } else {
                Button { Task { await change(.paused(true), done: L("ios.em.pausedToast")) } } label: { Label(L("ios.em.pause"), systemImage: "pause") }
            }
            Button { confirmRegenerate = true } label: { Label(L("ios.em.regenerate"), systemImage: "arrow.triangle.2.circlepath") }
            if address.isOwner != true {
                Button { Task { await change(.takeOver, done: L("ios.em.tookOver")) } } label: { Label(L("ios.em.takeOver"), systemImage: "person.crop.circle.badge.checkmark") }
            }
            Button(role: .destructive) { confirmDelete = true } label: { Label(L("ios.em.delete"), systemImage: "trash") }
        }
        .disabled(busy)
    }

    private func toggle(_ title: String, _ hint: String, _ value: Bool, _ make: @escaping (Bool) -> EmailAddressChange) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { v in Task { await change(make(v)) } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .disabled(busy)
    }

    // MARK: Actions

    private func load() async {
        let api = self.api, id = address.id
        async let a = try? api.emailAddress(id)
        async let s = try? api.emailSenders(id)
        async let p = try? api.emailPendingSenders(id)
        if let fresh = await a { update(fresh) }
        if let list = await s { senders = list.sorted { ($0.isBlocked ? 1 : 0, $0.email) < ($1.isBlocked ? 1 : 0, $1.email) } }
        if let list = await p { pending = list }
        loaded = true
    }

    private func handle(_ event: LiveEvent) {
        switch event {
        case .emailAddressUpdated(let a) where a.id == address.id:
            update(a)
        case .emailAddressRemoved(let id, _, _) where id == address.id:
            changed(.deleted(id))
            dismiss()
        case .emailSenderPending(let id, _) where id == address.id, .emailSenderDecided(let id, _, _) where id == address.id:
            Task { await load() }
        default:
            break
        }
    }

    private func update(_ a: EmailAddress) {
        address = a
        changed(.updated(a))
    }

    private func perform(_ done: String? = nil, _ work: () async throws -> Void) async {
        busy = true
        defer { busy = false }
        do {
            try await work()
            if let done { app.toasts.show(done) }
        } catch {
            app.toasts.show(EmailText.problem(error, limits: app.limits) ?? errorMessage(error), isError: true)
            if (error as? APIError)?.code == "already_decided" { await load() }
        }
    }

    private func change(_ c: EmailAddressChange, done: String? = nil) async {
        await perform(done) { update(try await api.updateEmailAddress(address.id, c)) }
    }

    private func regenerate() async {
        await perform {
            let a = try await api.regenerateEmailAddress(address.id)
            update(a)
            app.toasts.show(L("ios.em.regenerated", ["address": a.address]))
        }
    }

    private func delete() async {
        let id = address.id
        await perform(L("ios.em.deleted")) {
            try await api.deleteEmailAddress(id)
            changed(.deleted(id))
            dismiss()
        }
    }

    private func approve(_ p: EmailPendingSender) async {
        await perform(L("ios.em.approved", ["name": p.label])) {
            _ = try await api.approveEmailSender(address.id, senderId: p.id, deliver: true)
            pending.removeAll { $0.id == p.id }
        }
        await load()
    }

    private func reject(_ p: EmailPendingSender, block: Bool) async {
        await perform(block ? L("ios.em.rejectedBlocked", ["name": p.label]) : L("ios.em.rejected", ["name": p.label])) {
            try await api.rejectEmailSender(address.id, senderId: p.id, block: block)
            pending.removeAll { $0.id == p.id }
        }
        await load()
    }

    private func setBlocked(_ s: EmailSender, _ blocked: Bool) async {
        await perform(blocked ? L("ios.em.blockedToast", ["name": s.label]) : L("ios.em.unblockedToast", ["name": s.label])) {
            let updated = try await api.updateEmailSender(address.id, senderId: s.id, blocked: blocked)
            if let i = senders.firstIndex(where: { $0.id == s.id }) { senders[i] = updated }
        }
    }

    private func removeSender(_ s: EmailSender) async {
        await perform(L("ios.em.removedSender", ["name": s.label])) {
            try await api.removeEmailSender(address.id, senderId: s.id)
            senders.removeAll { $0.id == s.id }
        }
    }
}

/// A held mail in the pending list: subject, when, how many photos, the first thumbnails and when it goes.
struct HeldMessageRow: View {
    let message: EmailHeldMessageSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(message.subject.flatMap { $0.isEmpty ? nil : $0 } ?? L("ios.em.noSubject")).font(.subheadline).foregroundStyle(Palette.text).lineLimit(1)
                Spacer()
                Text(Format.relative(message.receivedAt, locale: L10n.locale)).font(.caption).foregroundStyle(Palette.textMuted)
            }
            if let thumbs = message.thumbUrls, !thumbs.isEmpty {
                HStack(spacing: 4) {
                    ForEach(thumbs.prefix(4), id: \.self) { url in
                        RemoteThumb(url: url, cornerRadius: 6).frame(width: 52, height: 52)
                    }
                    if message.mediaCount > thumbs.count {
                        Text("+\(message.mediaCount - thumbs.count)").font(.caption.weight(.semibold)).foregroundStyle(Palette.textMuted).padding(.leading, 4)
                    }
                }
            }
            HStack(spacing: 6) {
                Text(Lp("ios.em.items", count: message.mediaCount)).font(.caption).foregroundStyle(Palette.textMuted)
                let expiry = EmailText.expiry(message.expiresAt)
                Text("· \(expiry.text)").font(.caption).foregroundStyle(expiry.tone.color)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One held mail in full: who it claims to be from, whether that could be checked, the caption it would get, every
/// photo and video; deliver this one, delete it, or (authenticated mail) approve the sender.
struct EmailHeldMessageView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let addressId: String
    let summary: EmailHeldMessageSummary
    /// Set when the sender can be approved from here (authenticated mail).
    let pendingSenderId: String?
    let decided: () -> Void
    @State private var message: EmailHeldMessage?
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Group {
                if let m = message {
                    Section {
                        LabeledContent(L("ios.em.held.from"), value: m.senderLabel)
                        LabeledContent(L("ios.em.held.subject"), value: m.subject.flatMap { $0.isEmpty ? nil : $0 } ?? L("ios.em.noSubject"))
                        if let caption = m.caption, !caption.isEmpty {
                            LabeledContent(L("ios.em.held.caption"), value: caption)
                        }
                        LabeledContent(L("ios.em.held.received"), value: Format.dateTime(m.receivedAt, locale: L10n.locale))
                        let expiry = EmailText.expiry(m.expiresAt)
                        Text(expiry.text).font(.footnote).foregroundStyle(expiry.tone.color)
                    }
                    if m.isUnverified {
                        Section { Callout(EmailText.unverified(m.senderEmail).text, kind: .warn) }
                    }
                    Section {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 6)], spacing: 6) {
                            ForEach(Array(m.media.enumerated()), id: \.offset) { _, item in
                                RemoteThumb(url: item.thumbUrl, kind: item.kind, cornerRadius: 8)
                                    .aspectRatio(1, contentMode: .fill)
                            }
                        }
                        .padding(.vertical, 4)
                        let skipped = EmailText.skipped(m.skipped)
                        if !skipped.isEmpty {
                            Text(L("ios.em.skippedPrefix") + " " + skipped.map(\.text).joined(separator: ", "))
                                .font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    } header: {
                        Text(Lp("ios.em.items", count: m.media.count))
                    }
                    Section {
                        if !m.isUnverified, let sid = pendingSenderId {
                            Button { Task { await approve(sid) } } label: { Label(L("ios.em.approveSender"), systemImage: "checkmark.circle") }
                        }
                        Button { Task { await deliver() } } label: { Label(L("ios.em.deliverOne"), systemImage: "paperplane") }
                        Button(role: .destructive) { confirmDelete = true } label: { Label(L("ios.em.deleteMail"), systemImage: "trash") }
                    } footer: {
                        if m.isUnverified { Text(L("ios.em.deliverUnverifiedNote")) }
                    }
                    .disabled(busy)
                } else if let error {
                    Section { ErrorRetryView(message: error) { Task { await load() } } }
                } else {
                    Section { LoadingView() }
                }
            }
            .listRowBackground(Palette.surface)
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .pageTitle(L("ios.em.heldTitle"))
        .task { await load() }
        .alert(L("ios.em.deleteMailTitle"), isPresented: $confirmDelete) {
            Button(L("ios.em.deleteMail"), role: .destructive) { Task { await delete() } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("ios.em.deleteMailBody"))
        }
    }

    private func load() async {
        do {
            message = try await api.emailHeldMessage(addressId, messageId: summary.id)
            error = nil
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func perform(_ done: String, _ work: () async throws -> Void) async {
        busy = true
        defer { busy = false }
        do {
            try await work()
            app.toasts.show(done)
            decided()
            dismiss()
        } catch {
            app.toasts.show(EmailText.problem(error, limits: app.limits) ?? errorMessage(error), isError: true)
        }
    }

    private func deliver() async {
        await perform(L("ios.em.deliveredOne")) { _ = try await api.deliverEmailHeldMessage(addressId, messageId: summary.id) }
    }

    private func delete() async {
        await perform(L("ios.em.deletedMail")) { try await api.deleteEmailHeldMessage(addressId, messageId: summary.id) }
    }

    private func approve(_ senderId: String) async {
        await perform(L("ios.em.approved", ["name": message?.senderLabel ?? ""])) {
            _ = try await api.approveEmailSender(addressId, senderId: senderId, deliver: true)
        }
    }
}

/// Recent mail (30 days): when, who, what became of it, files left out, the reply the sender got.
struct EmailRecentMailView: View {
    let api: API
    let address: EmailAddress
    @State private var items: [EmailMessage] = []
    @State private var cursor: String?
    @State private var loaded = false
    @State private var loadingMore = false
    @State private var error: String?

    var body: some View {
        List {
            Group {
                if !loaded {
                    LoadingView()
                } else if let error, items.isEmpty {
                    ErrorRetryView(message: error) { Task { await load(reset: true) } }
                } else if items.isEmpty {
                    Text(L("ios.em.recentEmpty")).foregroundStyle(Palette.textMuted)
                } else {
                    ForEach(items) { m in
                        let outcome = EmailText.outcome(m)
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(outcome.text).font(.subheadline.weight(.medium)).foregroundStyle(outcome.tone == .muted ? Palette.text : outcome.tone.color)
                                Spacer()
                                Text(Format.relative(m.receivedAt, locale: L10n.locale)).font(.caption).foregroundStyle(Palette.textMuted)
                            }
                            if let email = m.senderEmail, !email.isEmpty {
                                Text(m.authenticated ? email : L("ios.em.unverifiedShort", ["email": email]))
                                    .font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                            let skipped = EmailText.skipped(m.skipped)
                            if !skipped.isEmpty {
                                Text(L("ios.em.skippedPrefix") + " " + skipped.map(\.text).joined(separator: ", ")).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                            if let reply = EmailText.reply(m) {
                                Text(reply.text).font(.caption).foregroundStyle(Palette.textFaint)
                            }
                        }
                        .padding(.vertical, 2)
                        .accessibilityElement(children: .combine)
                    }
                    if cursor != nil {
                        Button {
                            Task { await load(reset: false) }
                        } label: {
                            if loadingMore { ProgressView() } else { Text(L("ios.em.loadMore")) }
                        }
                        .disabled(loadingMore)
                    }
                }
            }
            .listRowBackground(Palette.surface)
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .pageTitle(L("ios.em.recent"))
        .task { await load(reset: true) }
        .refreshable { await load(reset: true) }
    }

    private func load(reset: Bool) async {
        if !reset { loadingMore = true }
        defer { loadingMore = false }
        do {
            let page = try await api.emailMessages(address.id, cursor: reset ? nil : cursor)
            items = reset ? page.items : items + page.items
            cursor = page.nextCursor
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
        loaded = true
    }
}

/// Allow (or block) an address by hand, with the name for the credit.
struct EmailAddSenderSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let address: EmailAddress
    let added: (EmailSender) -> Void
    @State private var email = ""
    @State private var name = ""
    @State private var block = false
    @State private var saving = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        TextField(L("ios.em.senderEmail"), text: $email)
                            .keyboardType(.emailAddress)
                            .textContentType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField(L("ios.em.senderName"), text: $name)
                            .onChange(of: name) { if name.count > 40 { name = String(name.prefix(40)) } }
                    } footer: {
                        Text(L("ios.em.senderNameHint"))
                    }
                    Section {
                        Toggle(L("ios.em.senderBlock"), isOn: $block)
                    }
                    if let errorText {
                        Section { Text(errorText).foregroundStyle(Palette.danger) }
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(L("ios.em.addSender"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(block ? L("ios.em.block") : L("ios.em.allow")) { Task { await save() } }
                            .fontWeight(.semibold)
                            .disabled(!EmailSenderInput.isValid(email))
                    }
                }
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            let s = try await api.addEmailSender(address.id, email: email, name: name, blocked: block)
            added(s)
            app.toasts.show(block ? L("ios.em.blockedToast", ["name": s.label]) : L("ios.em.allowedToast", ["name": s.label]))
            dismiss()
        } catch {
            errorText = EmailText.problem(error, limits: app.limits) ?? errorMessage(error)
        }
    }
}

/// The name a sender is credited with when their mail has none.
struct EmailRenameSenderSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let addressId: String
    let sender: EmailSender
    let renamed: (EmailSender) -> Void
    @State private var name = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("ios.em.senderName"), text: $name)
                        .onChange(of: name) { if name.count > 40 { name = String(name.prefix(40)) } }
                } header: {
                    Text(sender.email)
                } footer: {
                    Text(L("ios.em.senderNameHint"))
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(L("ios.em.renameSender"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(L("common.save")) { Task { await save() } }.fontWeight(.semibold)
                    }
                }
            }
            .onAppear { name = sender.displayName ?? "" }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            renamed(try await api.updateEmailSender(addressId, senderId: sender.id, name: name))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// The label or the friendly part of an address. A new friendly part changes the address: the old one stops at once.
struct EmailEditSheet: View {
    enum Field: String, Identifiable {
        case name, slug
        var id: String { rawValue }
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let address: EmailAddress
    let field: Field
    let saved: (EmailAddress) -> Void
    @State private var value = ""
    @State private var saving = false
    @State private var errorText: String?

    private var valid: Bool {
        switch field {
        case .name: !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .slug: EmailSlug.isValid(EmailSlug.clean(value)) && EmailSlug.clean(value) != address.slug
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        if field == .slug {
                            TextField(L("ios.em.slugPlaceholder"), text: $value)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keyboardType(.asciiCapable)
                                .onChange(of: value) { let t = EmailSlug.typing(value); if t != value { value = t } }
                            Text(EmailSlug.preview(EmailSlug.clean(value), domain: ChannelFeatures(app.discovery).emailDomain))
                                .font(.footnote.monospaced()).foregroundStyle(Palette.textMuted)
                        } else {
                            TextField(address.targetName, text: $value)
                                .onChange(of: value) { if value.count > EmailAddressDraft.nameMax { value = String(value.prefix(EmailAddressDraft.nameMax)) } }
                        }
                    } footer: {
                        Text(field == .slug ? L("ios.em.changeSlugHint", ["address": address.address]) : L("ios.em.labelHint"))
                    }
                    if let errorText {
                        Section { Text(errorText).foregroundStyle(Palette.danger) }
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(field == .slug ? L("ios.em.changeSlug") : L("ios.em.rename"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(L("common.save")) { Task { await save() } }.fontWeight(.semibold).disabled(!valid)
                    }
                }
            }
            .onAppear { value = field == .slug ? address.slug : address.name }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            let a = try await api.updateEmailAddress(address.id, field == .slug ? .slug(value) : .name(value))
            saved(a)
            if field == .slug { app.toasts.show(L("ios.em.slugChanged", ["address": a.address])) }
            dismiss()
        } catch {
            errorText = EmailText.problem(error, limits: app.limits) ?? errorMessage(error)
        }
    }
}
