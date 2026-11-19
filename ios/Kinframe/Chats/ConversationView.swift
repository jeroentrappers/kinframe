import KinframeKit
import PhotosUI
import SwiftUI

/// A conversation: messages with replies, reactions, photos and videos ("Put on a frame"), voice messages, shared
/// posts and link previews; read receipts and typing; the composer with mentions, photos and the recorder.
struct ConversationView: View {
    @Environment(AppModel.self) private var app
    let route: ChatRoute
    @State private var model: ConversationModel?
    @State private var text = ""
    @State private var picks: [MentionCandidate] = []
    @State private var replyTo: Message?
    @State private var editing: Message?
    @State private var photoPicks: [PhotosPickerItem] = []
    @State private var preview: LinkPreview?
    @State private var previewOff = false
    @State private var previewTask: Task<Void, Never>?
    @State private var recorder = VoiceRecorder()
    @State private var putOnFrame: Message?
    @State private var reporting: ReportTarget?
    @State private var showInfo = false
    @State private var micDenied = false
    @FocusState private var focused: Bool

    private var me: String? { app.me?.id }
    private var features: SocialFeatures { app.social.features }

    var body: some View {
        Group {
            if let model, model.gone {
                EmptyStateView(systemImage: "bubble.left.and.exclamationmark.bubble.right", title: L("chat.goneTitle"), message: L("chat.goneBody"))
            } else if let model, let c = model.conversation {
                messageList(model, c)
                    .safeAreaInset(edge: .bottom) { composer(model, c) }
            } else if let error = model?.error {
                ErrorRetryView(message: error) { Task { await model?.load() } }.padding(16)
            } else {
                LoadingView()
            }
        }
        .pageBackground()
        .navigationTitle(model?.conversation.map(ChatList.title) ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let c = model?.conversation {
                    Button { showInfo = true } label: { ConversationAvatar(conversation: c, size: 32) }
                        .accessibilityLabel(L("chat.info.title"))
                }
            }
        }
        .task {
            guard model == nil, let api = app.api else { return }
            let m = ConversationModel(api: api, id: route.id)
            if let u = app.me { m.me = PersonRef(userId: u.id, displayName: u.displayName, avatarUrl: u.avatarUrl) }
            model = m
            await m.load()
            await m.markRead()
            app.social.markRead(route.id)
        }
        .onAppear { app.social.openConversationId = route.id }
        .onDisappear {
            if app.social.openConversationId == route.id { app.social.openConversationId = nil }
            recorder.cancel()
        }
        .onLiveEvent(app.events) { e in
            model?.apply(e)
            if case .social(.messageCreated(let cid, _)) = e, cid == route.id {
                Task {
                    await model?.markRead()
                    app.social.markRead(route.id)
                }
            }
        }
        .onChange(of: app.liveStatus) { _, s in if s == .open { Task { await model?.catchUp() } } }
        .onChange(of: photoPicks) { _, items in
            guard !items.isEmpty else { return }
            photoPicks = []
            Task { await sendPicked(items) }
        }
        .sheet(item: $putOnFrame) { m in PutOnFrameSheet(conversationId: route.id, message: m) }
        .sheet(item: $reporting) { t in ReportSheet(kind: t.kind, targetId: t.id, inFamily: t.inFamily) }
        .sheet(isPresented: $showInfo) {
            if let model { ConversationInfoView(model: model) }
        }
        .alert(L("chat.voice.deniedTitle"), isPresented: $micDenied) {
            Button(L("ios.account.openSettings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("chat.voice.deniedBody"))
        }
    }

    // MARK: Messages

    private func messageList(_ model: ConversationModel, _ c: Conversation) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    if model.hasMoreBefore {
                        Button(L("chat.loadOlder")) { Task { await model.loadOlder() } }
                            .buttonStyle(.kf(.ghost)).frame(maxWidth: .infinity)
                    } else {
                        header(c)
                    }
                    let list = model.messages
                    ForEach(Array(list.enumerated()), id: \.element.id) { n, m in
                        if MessageLogic.startsDay(list, at: n) {
                            Text(m.createdAt.formatted(.dateTime.weekday(.wide).day().month(.wide).locale(L10n.locale)))
                                .font(.caption.weight(.semibold)).foregroundStyle(Palette.textFaint)
                                .frame(maxWidth: .infinity).padding(.vertical, 8)
                        }
                        MessageRow(message: m, conversation: c, model: model, continues: MessageLogic.continues(list, at: n),
                                   isLastOwn: m.id == list.last(where: { $0.author?.userId == me && !MessageLogic.isPending($0) })?.id,
                                   actions: actions(model, c))
                            .id(m.id)
                    }
                    if let typing = app.social.typingText(c.id) {
                        Text(typing).font(.footnote).italic().foregroundStyle(Palette.accentText).padding(.horizontal, 6).padding(.top, 4)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: model.messages.last?.id) {
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: model.loaded) {
                if let target = route.messageId { proxy.scrollTo(target, anchor: .center) } else { proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
    }

    /// The top of the conversation: who's in it, the operator note, and in family chats the invite card.
    private func header(_ c: Conversation) -> some View {
        VStack(spacing: 10) {
            ConversationAvatar(conversation: c, size: 64)
            Text(ChatList.title(c)).font(.display(.title3))
            Text(ChatList.kindLabel(c)).font(.footnote).foregroundStyle(Palette.textMuted)
            if c.kind == .family, let feedId = c.feedId {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("chat.invite.title")).font(.subheadline.weight(.semibold))
                    Text(L("chat.invite.body")).font(.footnote).foregroundStyle(Palette.textMuted)
                    Button(L("chat.invite.action")) { app.openFeed(feedId, section: .members) }.buttonStyle(.kf(.soft))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card(padding: 12)
            }
            OperatorNote(server: app.server.map { ServerAddress.display($0.url) } ?? "")
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
    }

    private func actions(_ model: ConversationModel, _ c: Conversation) -> MessageActions {
        MessageActions(
            reply: { m in
                editing = nil
                replyTo = m
                focused = true
            },
            edit: { m in
                replyTo = nil
                editing = m
                text = m.body ?? ""
                picks = Mentions.picks(in: text, mentions: m.mentions ?? [])
                focused = true
            },
            putOnFrame: { putOnFrame = $0 },
            report: { m in reporting = ReportTarget(kind: .message, id: m.id, inFamily: c.kind == .family || c.kind == .circle) },
            moderator: ChatAbilities.isModerator(c, feedRole: app.feedRole(c.feedId),
                                                 circleRole: c.circleId.flatMap { cid in app.social.circles[c.feedId ?? ""]?.first { $0.id == cid }?.myRole })
        )
    }

    // MARK: Composer

    @ViewBuilder private func composer(_ model: ConversationModel, _ c: Conversation) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if !ChatAbilities.canSend(c) {
                Callout(LeavingText.readOnlyLine(c) ?? L(c.kind == .direct ? "chat.readOnlyDirect" : "chat.readOnly"))
            } else if recorder.state == .recording {
                recording(model)
            } else {
                if let r = replyTo {
                    banner(L("chat.replyingTo", ["name": PersonName.display(r.author, me: me, you: true)]) + " · " + MessageText.summary(r)) { replyTo = nil }
                }
                if editing != nil {
                    banner(L("chat.editing")) { cancelEdit() }
                }
                if let p = preview, !previewOff, editing == nil {
                    HStack(alignment: .top) {
                        LinkPreviewCard(preview: p)
                        Button { previewOff = true } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.textFaint) }
                            .accessibilityLabel(L("chat.preview.remove"))
                    }
                }
                HStack(alignment: .bottom, spacing: 8) {
                    if editing == nil {
                        PhotosPicker(selection: $photoPicks, maxSelectionCount: app.limits.maxMessageItems, selectionBehavior: .ordered,
                                     matching: .any(of: [.images, .videos]), preferredItemEncoding: .current) {
                            Image(systemName: "photo.on.rectangle").font(.title3).frame(width: 40, height: 44)
                        }
                        .accessibilityLabel(L("chat.attach"))
                    }
                    MentionField(placeholder: L("chat.placeholder"), text: $text, picks: $picks, candidates: candidates(c), me: me,
                                 maxLength: app.limits.maxMessageChars, focused: $focused)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(Palette.border))
                        .onChange(of: text) { _, new in
                            if !new.isEmpty { model.typing() }
                            schedulePreview(new)
                        }
                    if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, editing == nil, features.voiceMessages {
                        Button { Task { await startRecording(model) } } label: {
                            Image(systemName: "mic.fill").font(.title3).foregroundStyle(Palette.primary).frame(width: 44, height: 44)
                        }
                        .accessibilityLabel(L("chat.voice.record"))
                    } else {
                        Button { Task { await send(model) } } label: {
                            Image(systemName: "arrow.up.circle.fill").font(.title).foregroundStyle(Palette.primary).frame(width: 44, height: 44)
                        }
                        .disabled(!ChatComposer.canSend(body: text, itemCount: 0, sharedPost: false, limits: app.limits))
                        .accessibilityLabel(L(editing == nil ? "chat.send" : "common.save"))
                    }
                }
                if let left = ChatComposer.remaining(text, max: app.limits.maxMessageChars) {
                    Text(Lp("chat.charsLeft", count: max(0, left))).font(.caption).foregroundStyle(left < 0 ? Palette.danger : Palette.textMuted)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func recording(_ model: ConversationModel) -> some View {
        HStack(spacing: 12) {
            Button { recorder.cancel() } label: {
                Image(systemName: "trash").foregroundStyle(Palette.danger).frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("chat.voice.cancel"))
            Circle().fill(Palette.danger).frame(width: 10, height: 10).accessibilityHidden(true)
            Text(VoiceLogic.duration(Int(recorder.elapsed * 1000))).font(.body.monospacedDigit())
            WaveformView(values: VoiceLogic.bars(Array(recorder.levels.suffix(40)), count: 40), progress: 1, tint: Palette.danger)
                .frame(height: 28)
            Button { finishRecording(model) } label: {
                Image(systemName: "arrow.up.circle.fill").font(.title).foregroundStyle(Palette.primary).frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("chat.voice.send"))
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("chat.voice.recording"))
    }

    private func banner(_ text: String, cancel: @escaping () -> Void) -> some View {
        HStack {
            Text(text).font(.footnote.weight(.semibold)).foregroundStyle(Palette.textMuted).lineLimit(1)
            Spacer()
            Button(action: cancel) { Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.textFaint) }
                .accessibilityLabel(L("common.cancel"))
        }
    }

    private func candidates(_ c: Conversation) -> [MentionCandidate] {
        (c.members ?? []).filter { $0.person.formerMember != true }
            .map { MentionCandidate(id: $0.person.userId, name: $0.person.displayName, avatarUrl: $0.person.avatarUrl) }
    }

    // MARK: Doing

    private func cancelEdit() {
        editing = nil
        text = ""
        picks = []
    }

    private func send(_ model: ConversationModel) async {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let mentions = Mentions.spans(in: body, picks: picks, max: app.limits.maxMentions)
        if let m = editing {
            do {
                try await model.edit(m, body: body, mentions: mentions)
                cancelEdit()
            } catch {
                app.toasts.error(error)
            }
            return
        }
        let reply = replyTo
        let withPreview = features.linkPreviews && !previewOff
        text = ""
        picks = []
        replyTo = nil
        preview = nil
        previewOff = false
        await model.sendText(body, mentions: mentions, replyTo: reply, linkPreview: withPreview)
        if model.error != nil, let e = model.error { app.toasts.show(e, isError: true); model.error = nil }
    }

    private func sendPicked(_ items: [PhotosPickerItem]) async {
        guard let model else { return }
        var files: [(url: URL, filename: String)] = []
        for item in items.prefix(app.limits.maxMessageItems) {
            if let media = try? await item.loadTransferable(type: PickedMedia.self) { files.append((media.url, media.filename)) }
        }
        guard !files.isEmpty else { return }
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let reply = replyTo
        text = ""
        replyTo = nil
        await model.sendMedia(files, body: body.isEmpty ? nil : body, replyTo: reply, maxVideoSeconds: app.limits.maxVideoSeconds)
        if let e = model.error { app.toasts.show(e, isError: true); model.error = nil }
    }

    private func startRecording(_ model: ConversationModel) async {
        recorder.maxSeconds = app.limits.maxVoiceSeconds
        recorder.onLimit = { finishRecording(model) }
        if await recorder.start() {
            model.typing(recording: true)
            Task {
                while recorder.state == .recording {
                    try? await Task.sleep(for: .seconds(3))
                    if recorder.state == .recording { model.typing(recording: true) }
                }
            }
        } else if recorder.state == .denied {
            micDenied = true
        }
    }

    private func finishRecording(_ model: ConversationModel) {
        guard let (file, ms) = recorder.finish() else {
            app.toasts.show(L("chat.voice.tooShort"))
            return
        }
        let reply = replyTo
        replyTo = nil
        Task {
            await model.sendVoice(file, durationMs: ms, replyTo: reply)
            if let e = model.error { app.toasts.show(e, isError: true); model.error = nil }
        }
    }

    /// The composer shows the preview the server will attach (operator opt-in, README §5.19).
    private func schedulePreview(_ body: String) {
        guard features.linkPreviews else { return }
        let link = Links.first(in: body)
        guard link?.absoluteString != preview?.url else { return }
        previewTask?.cancel()
        guard let link, let api = app.api else {
            preview = nil
            return
        }
        previewTask = Task {
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled, let p = try? await api.linkPreview(link), p.status != .failed else { return }
            preview = p
        }
    }
}

/// What a message's menu can do, from the conversation.
struct MessageActions {
    var reply: (Message) -> Void
    var edit: (Message) -> Void
    var putOnFrame: (Message) -> Void
    var report: (Message) -> Void
    var moderator: Bool
}

/// The preview of a link (the server fetched it and serves the image itself; README §5.19).
struct LinkPreviewCard: View {
    let preview: LinkPreview

    var body: some View {
        Link(destination: URL(string: preview.url) ?? URL(string: "about:blank")!) {
            HStack(alignment: .top, spacing: 10) {
                if let img = preview.imageUrl {
                    RemoteThumb(url: img, cornerRadius: Radius.sm).frame(width: 56, height: 56)
                }
                VStack(alignment: .leading, spacing: 2) {
                    if let site = preview.siteName { Text(site).font(.caption2.weight(.semibold)).foregroundStyle(Palette.textFaint) }
                    Text(preview.title ?? preview.url).font(.footnote.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(2)
                    if let d = preview.description { Text(d).font(.caption).foregroundStyle(Palette.textMuted).lineLimit(2) }
                }
                Spacer(minLength: 0)
            }
            .padding(8)
            .background(Palette.surface2, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
