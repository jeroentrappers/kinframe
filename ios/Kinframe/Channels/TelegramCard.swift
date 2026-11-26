import KinframeKit
import SwiftUI

/// Opens a t.me link: in the Telegram app when it is installed (`tg://`), else in the browser (which offers the app).
@MainActor
enum TelegramOpener {
    static func open(_ web: URL, fallback: OpenURLAction) {
        guard let app = TelegramLinks.app(web) else {
            fallback(web)
            return
        }
        Task { @MainActor in
            if await UIApplication.shared.open(app, options: [:]) == false { fallback(web) }
        }
    }
}

/// The Telegram chats of one frame or feed, kept fresh from `telegram_chat.*`.
@MainActor
@Observable
final class TelegramChatsModel {
    let api: API
    let target: ChannelTarget
    private(set) var chats: [TelegramChat] = []
    private(set) var loaded = false
    var error: String?

    init(api: API, target: ChannelTarget) {
        self.api = api
        self.target = target
    }

    var live: [TelegramChat] { TelegramChats.live(chats) }
    var earlier: [TelegramChat] { TelegramChats.earlier(chats) }

    func load() async {
        do {
            chats = try await api.telegramChats(target)
            error = nil
        } catch is CancellationError {
        } catch {
            if !loaded { self.error = errorMessage(error) }
        }
        loaded = true
    }

    func upsert(_ c: TelegramChat) {
        guard c.channelTarget == target else { return }
        if let i = chats.firstIndex(where: { $0.id == c.id }) { chats[i] = c } else { chats.append(c) }
    }

    func apply(_ event: LiveEvent) {
        switch event {
        case .telegramChatUpdated(let c): upsert(c)
        case .telegramChatRemoved(let id, _, _): chats.removeAll { $0.id == id }
        default: break
        }
    }
}

/// "Telegram" in frame settings (frame managers) and feed settings (owner and admins): connected chats with their
/// status and the fix when something is off, disconnected ones under "Earlier", and the three ways in: connect a
/// group, send from my Telegram, invite someone. Hidden by the caller unless discovery says `telegram`.
struct TelegramCard: View {
    @Environment(AppModel.self) private var app
    let targetName: String
    @State private var model: TelegramChatsModel
    @State private var connecting: TelegramConnectSheet.Mode?
    @State private var open: TelegramChat?
    @State private var showEarlier = false

    init(api: API, target: ChannelTarget, targetName: String) {
        self.targetName = targetName
        _model = State(initialValue: TelegramChatsModel(api: api, target: target))
    }

    private var atLimit: Bool { TelegramChats.atLimit(model.chats, limits: app.limits) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(L("ios.tg.title"))
            if !model.loaded {
                ProgressView().frame(maxWidth: .infinity)
            } else if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else {
                if model.live.isEmpty {
                    Text(model.target.isFeed ? L("ios.tg.introFeed", ["name": targetName]) : L("ios.tg.introFrame", ["name": targetName]))
                        .font(.subheadline).foregroundStyle(Palette.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ForEach(model.live) { c in
                    TelegramChatRow(chat: c) { open = c }
                    Divider()
                }
                if atLimit {
                    Text(L("ios.tg.limit", ["count": "\(app.limits.maxTelegramChatsPerTarget)"])).font(.footnote).foregroundStyle(Palette.textMuted)
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Button { connecting = .group } label: { Label(L("ios.tg.connectGroup"), systemImage: "person.3") }
                            .buttonStyle(.kf(model.live.isEmpty ? .primary : .secondary))
                        HStack {
                            Button { connecting = .mine } label: { Label(L("ios.tg.sendMine"), systemImage: "paperplane") }
                                .buttonStyle(.kf(.ghost))
                            Button { connecting = .invite } label: { Label(L("ios.tg.invite"), systemImage: "person.badge.plus") }
                                .buttonStyle(.kf(.ghost))
                        }
                    }
                }
                if !model.earlier.isEmpty {
                    DisclosureGroup(isExpanded: $showEarlier) {
                        ForEach(model.earlier) { c in
                            TelegramChatRow(chat: c) { open = c }
                        }
                    } label: {
                        Text(L("ios.tg.earlier")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.textMuted)
                    }
                }
            }
        }
        .card(padding: 16)
        .task { await model.load() }
        .onLiveEvent(app.events) { model.apply($0) }
        .onChange(of: model.loaded) { focusIfAsked() }
        .onChange(of: app.channelFocus) { focusIfAsked() }
        .sheet(item: $connecting) { mode in
            TelegramConnectSheet(api: model.api, target: model.target, targetName: targetName, mode: mode) { chat in
                if let chat { model.upsert(chat) }
                Task { await model.load() }
            }
        }
        .sheet(item: $open) { c in
            TelegramChatSheet(api: model.api, chat: c, targetName: targetName) { updated in
                if let updated { model.upsert(updated) } else { Task { await model.load() } }
            } reconnect: {
                Task {
                    try? await Task.sleep(for: .milliseconds(450))
                    connecting = c.kind == .private ? .mine : .group
                }
            }
        }
    }

    private func focusIfAsked() {
        guard model.loaded, case .telegramChat(let id)? = app.channelFocus, let c = model.chats.first(where: { $0.id == id }) else { return }
        app.channelFocus = nil
        open = c
    }
}

/// One chat: its title, kind and owner, status line in its colour, counts.
struct TelegramChatRow: View {
    let chat: TelegramChat
    let open: () -> Void

    var body: some View {
        let headline = TelegramText.headline(chat)
        Button(action: open) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: chat.kind == .private ? "person.crop.circle" : "bubble.left.and.bubble.right.fill")
                    .font(.title3)
                    .foregroundStyle(chat.isLive ? Palette.primary : Palette.textFaint)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(chat.isLive ? Palette.primarySoft : Palette.surface2))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(chat.title).font(.headline).foregroundStyle(chat.isLive ? Palette.text : Palette.textMuted)
                    Text(TelegramText.subtitle(chat).map(\.text).joined(separator: " · ")).font(.footnote).foregroundStyle(Palette.textMuted)
                    Text(headline.text).font(.subheadline.weight(.medium)).foregroundStyle(headline.tone.color)
                        .fixedSize(horizontal: false, vertical: true)
                    if chat.isLive {
                        Text(TelegramText.counts(chat).map(\.text).joined(separator: " · ")).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint).padding(.top, 8)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.vertical, 4)
    }
}

/// Connect a group, send from my Telegram, or invite someone: makes a one-time code, opens it in Telegram (or shows
/// it as a QR code for another device), and follows it until the chat is connected.
struct TelegramConnectSheet: View {
    enum Mode: String, Identifiable {
        /// Connect a Telegram group (`?startgroup=`).
        case group
        /// Send from my Telegram: a private chat with the bot that also links my account.
        case mine
        /// Invite someone to send by Telegram: share the `?start=` link.
        case invite
        var id: String { rawValue }

        var kind: TelegramKind { self == .group ? .group : .private }
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let api: API
    let target: ChannelTarget
    let targetName: String
    let mode: Mode
    let finished: (TelegramChat?) -> Void
    @State private var code: TelegramCode?
    @State private var url: URL?
    @State private var progress: TelegramCodeProgress = .waiting(problem: nil)
    @State private var chat: TelegramChat?
    @State private var error: String?
    @State private var creating = false

    init(api: API, target: ChannelTarget, targetName: String, mode: Mode, finished: @escaping (TelegramChat?) -> Void) {
        self.api = api
        self.target = target
        self.targetName = targetName
        self.mode = mode
        self.finished = finished
    }

    private var features: ChannelFeatures { ChannelFeatures(app.discovery) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    explanation
                    if case .done = progress {
                        done
                    } else if let error {
                        ErrorRetryView(message: error) { Task { await create() } }
                    } else if progress == .expired {
                        Callout(L("ios.tg.codeExpired"), kind: .warn)
                        Button(L("ios.tg.newLink")) { Task { await create() } }.buttonStyle(.kf(.primary))
                    } else if let url {
                        link(url)
                    } else {
                        LoadingView()
                    }
                }
                .padding(20)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .pageTitle(title)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(progress.isFinal ? L("common.done") : L("common.cancel")) { close() }
                }
            }
            .task { await create() }
            .task(id: code?.id) { await follow() }
            .onLiveEvent(app.events) { event in
                if case .telegramCodeUpdated(let c) = event, c.id == code?.id { Task { await apply(c) } }
            }
            .interactiveDismissDisabled(creating)
        }
    }

    private var title: String {
        switch mode {
        case .group: L("ios.tg.connectGroup")
        case .mine: L("ios.tg.sendMine")
        case .invite: L("ios.tg.invite")
        }
    }

    @ViewBuilder private var explanation: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch mode {
            case .group:
                Text(target.isFeed ? L("ios.tg.explainGroupFeed", ["name": targetName]) : L("ios.tg.explainGroupFrame", ["name": targetName]))
                if features.telegramPrivacyMode {
                    Callout(L("ios.tg.privacyUpFront"), kind: .info)
                }
            case .mine:
                Text(L("ios.tg.explainMine", ["name": targetName]))
            case .invite:
                Text(L("ios.tg.explainInvite", ["name": targetName]))
            }
        }
        .foregroundStyle(Palette.textMuted)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func link(_ url: URL) -> some View {
        VStack(spacing: 14) {
            if mode != .invite {
                Button {
                    TelegramOpener.open(url, fallback: openURL)
                } label: {
                    Label(L("ios.tg.openTelegram"), systemImage: "paperplane.fill")
                }
                .buttonStyle(.kf(.primary, fill: true))
            } else {
                ShareLink(item: url, subject: Text(L("ios.tg.inviteSubject", ["name": targetName])),
                          message: Text(TelegramShare.inviteMessage(targetName: targetName, url: url.absoluteString))) {
                    Label(L("people.share"), systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.kf(.primary, fill: true))
            }
            if let image = QRCode.image(url.absoluteString) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 220)
                    .padding(14)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .accessibilityLabel(L("ios.tg.qrLabel"))
                Text(mode == .invite ? L("ios.tg.qrInvite") : L("ios.tg.qrHint")).font(.footnote).foregroundStyle(Palette.textMuted).multilineTextAlignment(.center)
            }
            Button {
                UIPasteboard.general.string = url.absoluteString
                app.toasts.show(L("people.copied"))
            } label: {
                Label(L("people.copy"), systemImage: "doc.on.doc")
            }
            .buttonStyle(.kf(.secondary))
            HStack(spacing: 8) {
                ProgressView()
                Text(L("ios.tg.waiting")).font(.subheadline).foregroundStyle(Palette.textMuted)
            }
            if case .waiting(let problem?) = progress {
                Callout(problem.text, kind: problem.tone == .error ? .error : .warn)
            }
            if let code {
                Text(L("ios.tg.expires", ["when": Format.dateTime(code.expiresAt, locale: L10n.locale)])).font(.caption).foregroundStyle(Palette.textFaint)
            }
        }
    }

    @ViewBuilder private var done: some View {
        VStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 44)).foregroundStyle(Palette.accentText).accessibilityHidden(true)
            if let chat {
                Text(L("ios.tg.connectedTo", ["title": chat.title])).font(.headline).multilineTextAlignment(.center)
                let headline = TelegramText.headline(chat)
                if chat.state == .needsAttention {
                    Callout(headline.text, kind: .warn)
                }
            } else {
                Text(mode == .mine ? L("ios.tg.linkedDone") : L("ios.tg.connectedDone")).font(.headline).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func create() async {
        creating = true
        defer { creating = false }
        error = nil
        do {
            let c = try await api.createTelegramCode(target, kind: mode.kind, linkAccount: mode == .mine)
            guard let u = TelegramLinks.web(c.url) else {
                error = L("ios.tg.err.noLink")
                return
            }
            code = c
            url = u
            progress = .waiting(problem: nil)
            // The group and "my Telegram" flows go straight to Telegram; the invite is for sharing.
            if mode != .invite { TelegramOpener.open(u, fallback: openURL) }
        } catch {
            self.error = TelegramText.problem(error, limits: app.limits) ?? errorMessage(error)
        }
    }

    /// Poll as well as listening, in case the websocket is down.
    private func follow() async {
        guard let id = code?.id else { return }
        while !Task.isCancelled, !progress.isFinal {
            try? await Task.sleep(for: .seconds(TelegramCodeProgress.pollSeconds))
            guard !Task.isCancelled, !progress.isFinal else { return }
            if let c = try? await api.telegramCode(id) { await apply(c) }
        }
    }

    private func apply(_ c: TelegramCode) async {
        guard c.id == code?.id else { return }
        let p = TelegramCodeProgress.of(c, targetName: targetName)
        progress = p
        if case .done(let chatId?) = p, chat == nil {
            chat = try? await api.telegramChat(chatId)
            finished(chat)
        } else if case .done = p {
            finished(nil)
        }
    }

    private func close() {
        // An unused code is revoked: nobody can connect with it later.
        if !progress.isFinal, let id = code?.id {
            let api = self.api
            Task { try? await api.revokeTelegramCode(id) }
        }
        dismiss()
    }
}

/// One chat: status and the fix, warnings, who can send, frame replies, the join link, reaction notifications (the
/// owner), the topic (forum groups), pause, take over, the people in it (block, unblock) and disconnect.
struct TelegramChatSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let targetName: String
    let changed: (TelegramChat?) -> Void
    let reconnect: () -> Void
    @State private var chat: TelegramChat
    @State private var people: [TelegramContributor] = []
    @State private var peopleLoaded = false
    @State private var busy = false
    @State private var confirmDisconnect = false

    init(api: API, chat: TelegramChat, targetName: String, changed: @escaping (TelegramChat?) -> Void, reconnect: @escaping () -> Void) {
        self.api = api
        self.targetName = targetName
        self.changed = changed
        self.reconnect = reconnect
        _chat = State(initialValue: chat)
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    statusSection
                    if chat.isEditable { settingsSection }
                    peopleSection
                    if chat.isEditable { manageSection }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(chat.title)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task { await load() }
            .refreshable { await load() }
            .onLiveEvent(app.events) { event in
                switch event {
                case .telegramChatUpdated(let c) where c.id == chat.id:
                    update(c)
                case .telegramChatRemoved(let id, _, _) where id == chat.id:
                    changed(nil)
                    dismiss()
                default:
                    break
                }
            }
            .alert(L("ios.tg.disconnectTitle", ["title": chat.title]), isPresented: $confirmDisconnect) {
                Button(L("ios.tg.disconnect"), role: .destructive) { Task { await disconnect() } }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(chat.kind == .private ? L("ios.tg.disconnectBodyPrivate") : L("ios.tg.disconnectBody", ["name": targetName]))
            }
        }
    }

    private var statusSection: some View {
        Section {
            let headline = TelegramText.headline(chat)
            Text(headline.text).font(.subheadline.weight(.medium)).foregroundStyle(headline.tone.color)
                .fixedSize(horizontal: false, vertical: true)
            switch TelegramText.fix(chat) {
            case .makeAdmin?:
                Text(L("ios.tg.fix.makeAdmin")).font(.footnote).foregroundStyle(Palette.textMuted)
            case .reconnect?:
                Button(L("ios.tg.fix.reconnect")) {
                    dismiss()
                    reconnect()
                }
                .buttonStyle(.kf(.primary))
            case nil:
                EmptyView()
            }
            ForEach(TelegramText.warnings(chat), id: \.key) { w in
                Callout(w.text, kind: .warn)
            }
            if chat.isLive {
                Text(TelegramText.counts(chat).map(\.text).joined(separator: " · ")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        } footer: {
            Text(TelegramText.subtitle(chat).map(\.text).joined(separator: " · "))
        }
    }

    private var settingsSection: some View {
        Section {
            if chat.kind != .private {
                Picker(L("ios.tg.set.who"), selection: Binding(get: { chat.contributorsMode ?? .everyone },
                                                                set: { m in Task { await change(.contributors(m)) } })) {
                    Text(L("ios.tg.set.everyone")).tag(TelegramContributors.everyone)
                    Text(chat.isFeedTarget ? L("ios.tg.set.linkedOnlyFeed", ["name": targetName]) : L("ios.tg.set.linkedOnly", ["name": targetName]))
                        .tag(TelegramContributors.linkedOnly)
                }
            }
            if !chat.isFeedTarget {
                toggle(L("ios.tg.set.frameReplies"), L("ios.tg.set.frameRepliesHint"), chat.frameReplies) { .frameReplies($0) }
            }
            toggle(L("ios.tg.set.invite"), L("ios.tg.set.inviteHint", ["name": targetName]), chat.offerAppInvite) { .offerAppInvite($0) }
            if chat.mayChangeNotifyReactions {
                toggle(L("ios.tg.set.reactions"), L("ios.tg.set.reactionsHint"), chat.notifyReactions) { .notifyReactions($0) }
            }
            if chat.isForumGroup, let topics = chat.topics, !topics.isEmpty {
                Picker(L("ios.tg.set.topic"), selection: Binding(get: { chat.topicId }, set: { id in Task { await change(.topic(id)) } })) {
                    Text(L("ios.tg.set.allTopics")).tag(Int64?.none)
                    ForEach(topics) { t in Text(t.name).tag(Int64?.some(t.id)) }
                }
            }
        } header: {
            Text(L("ios.tg.settingsTitle"))
        }
        .disabled(busy)
    }

    private var peopleSection: some View {
        Section {
            if !peopleLoaded {
                ProgressView().frame(maxWidth: .infinity)
            } else if people.isEmpty {
                Text(L("ios.tg.peopleEmpty")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            ForEach(people) { p in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(p.displayName).foregroundStyle(p.blocked ? Palette.textMuted : Palette.text)
                            if p.linked {
                                Pill(L("ios.tg.linked"), foreground: Palette.accentText, background: Palette.accentSoft)
                            }
                        }
                        Text(TelegramText.contributor(p).text).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                    Spacer(minLength: 0)
                    if chat.isEditable {
                        Button(p.blocked ? L("ios.tg.unblock") : L("ios.tg.block")) { Task { await setBlocked(p, !p.blocked) } }
                            .buttonStyle(.kf(p.blocked ? .secondary : .dangerGhost))
                            .disabled(busy)
                    } else if p.blocked {
                        Pill(L("ios.tg.blocked"), foreground: Palette.danger, background: Palette.dangerSoft)
                    }
                }
            }
        } header: {
            Text(L("ios.tg.peopleTitle"))
        } footer: {
            Text(L("ios.tg.peopleFooter"))
        }
    }

    private var manageSection: some View {
        Section {
            if chat.isPaused {
                Button { Task { await change(.paused(false), done: L("ios.tg.resumed")) } } label: { Label(L("ios.tg.resume"), systemImage: "play") }
            } else {
                Button { Task { await change(.paused(true), done: L("ios.tg.pausedToast")) } } label: { Label(L("ios.tg.pause"), systemImage: "pause") }
            }
            if chat.isOwner != true {
                Button { Task { await change(.takeOver, done: L("ios.tg.tookOver")) } } label: { Label(L("ios.tg.takeOver"), systemImage: "person.crop.circle.badge.checkmark") }
            }
            Button(role: .destructive) { confirmDisconnect = true } label: { Label(L("ios.tg.disconnect"), systemImage: "xmark.circle") }
        } footer: {
            if chat.isOwner != true, let owner = chat.owner?.displayName {
                Text(L("ios.tg.takeOverHint", ["name": owner]))
            }
        }
        .disabled(busy)
    }

    private func toggle(_ title: String, _ hint: String, _ value: Bool, _ make: @escaping (Bool) -> TelegramChatChange) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { v in Task { await change(make(v)) } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
    }

    private func load() async {
        let api = self.api, id = chat.id
        async let c = try? api.telegramChat(id)
        async let p = try? api.telegramContributors(id)
        if let fresh = await c { update(fresh) }
        if let list = await p { people = list }
        peopleLoaded = true
    }

    private func update(_ c: TelegramChat) {
        chat = c
        changed(c)
    }

    private func perform(_ done: String? = nil, _ work: () async throws -> Void) async {
        busy = true
        defer { busy = false }
        do {
            try await work()
            if let done { app.toasts.show(done) }
        } catch {
            app.toasts.show(TelegramText.problem(error, limits: app.limits) ?? errorMessage(error), isError: true)
        }
    }

    private func change(_ c: TelegramChatChange, done: String? = nil) async {
        await perform(done) { update(try await api.updateTelegramChat(chat.id, c)) }
    }

    private func setBlocked(_ p: TelegramContributor, _ blocked: Bool) async {
        await perform(blocked ? L("ios.tg.blockedToast", ["name": p.displayName]) : L("ios.tg.unblockedToast", ["name": p.displayName])) {
            let updated = try await api.setTelegramContributorBlocked(chat.id, contributorId: p.id, blocked)
            if let i = people.firstIndex(where: { $0.id == p.id }) { people[i] = updated }
        }
    }

    private func disconnect() async {
        await perform(L("ios.tg.disconnected", ["title": chat.title])) {
            try await api.disconnectTelegramChat(chat.id)
            if let fresh = try? await api.telegramChat(chat.id) { update(fresh) } else { changed(nil) }
            dismiss()
        }
    }
}

/// Settings → Telegram: link my Telegram (opens `?start=` in Telegram), the linked name, unlink. Hidden unless
/// discovery says `telegram`.
struct TelegramLinkSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    let api: API
    @State private var link: TelegramLink?
    @State private var loaded = false
    @State private var busy = false
    @State private var waitingCode: String?
    @State private var confirmUnlink = false

    var body: some View {
        Section {
            if !loaded {
                ProgressView().frame(maxWidth: .infinity)
            } else if let link {
                LabeledContent(L("ios.tg.linkedAs"), value: TelegramText.link(link))
                if !link.canMessage {
                    Text(L("ios.tg.botBlocked")).font(.footnote).foregroundStyle(Palette.warn)
                }
                Button(L("ios.tg.unlink"), role: .destructive) { confirmUnlink = true }.disabled(busy)
            } else {
                Button {
                    Task { await startLink() }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("ios.tg.linkMine"))
                            Text(waitingCode == nil ? L("ios.tg.linkHint") : L("ios.tg.linkWaiting")).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                        Spacer()
                        if busy || waitingCode != nil { ProgressView() }
                    }
                }
                .disabled(busy)
            }
        } header: {
            Text(L("ios.tg.title"))
        }
        .task { await load() }
        .onChange(of: scenePhase) { if scenePhase == .active, waitingCode != nil { Task { await load() } } }
        .onLiveEvent(app.events) { event in
            if case .telegramLinkUpdated(let l) = event {
                link = l
                if l != nil {
                    waitingCode = nil
                    app.toasts.show(L("ios.tg.linkedToast"))
                }
            }
        }
        .alert(L("ios.tg.unlinkTitle"), isPresented: $confirmUnlink) {
            Button(L("ios.tg.unlink"), role: .destructive) { Task { await unlink() } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("ios.tg.unlinkBody"))
        }
    }

    private func load() async {
        do {
            link = try await api.telegramLink()
            if link != nil { waitingCode = nil }
        } catch {}
        loaded = true
    }

    private func startLink() async {
        busy = true
        defer { busy = false }
        do {
            let code = try await api.createTelegramLinkCode()
            guard let url = TelegramLinks.web(code.url) else {
                app.toasts.show(L("ios.tg.err.noLink"), isError: true)
                return
            }
            waitingCode = code.id
            TelegramOpener.open(url, fallback: openURL)
        } catch {
            app.toasts.show(TelegramText.problem(error, limits: app.limits) ?? errorMessage(error), isError: true)
            if (error as? APIError)?.code == "telegram_already_linked" { await load() }
        }
    }

    private func unlink() async {
        busy = true
        defer { busy = false }
        do {
            try await api.unlinkTelegram()
            link = nil
            app.toasts.show(L("ios.tg.unlinked"))
        } catch {
            app.toasts.error(error)
        }
    }
}
