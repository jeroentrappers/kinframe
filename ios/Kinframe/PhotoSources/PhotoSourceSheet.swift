import KinframeKit
import SwiftUI

/// Connect an outside album to an album or a feed, or change a source (web: the "Sync from…" sheet). Immich shared
/// link (recommended, optional password), Immich server + API key with an album picker, or an iCloud shared album
/// link (best effort, with how to turn on Public Website in Photos). Test connection shows the album's name, size and
/// warnings; then the first import, what happens when a photo is removed upstream, videos and reaction notifications.
struct PhotoSourceSheet: View {
    enum Mode {
        case add(SourceTargetRef, targetName: String)
        case edit(PhotoSource, Focus)
    }

    enum Focus: Equatable {
        case settings
        case connection(SourceFix)
        case takeOver
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let mode: Mode
    /// Album targets: the album, for the "Only in album mode" suggestion when the first import is large.
    var album: Album?
    var setAlbumOnly: (() async -> Void)?
    let saved: (PhotoSource) -> Void

    init(api: API, mode: Mode, album: Album? = nil, setAlbumOnly: (() async -> Void)? = nil, saved: @escaping (PhotoSource) -> Void) {
        self.api = api
        self.mode = mode
        self.album = album
        self.setAlbumOnly = setAlbumOnly
        self.saved = saved
    }

    @State private var provider: SourceProvider = .immichLink
    @State private var link = ""
    @State private var password = ""
    @State private var removePassword = false
    @State private var serverURL = ""
    @State private var apiKey = ""
    @State private var albums: [ImmichAlbum] = []
    @State private var albumId: String?
    @State private var albumsBusy = false
    @State private var albumsError: SourceLine?
    @State private var name = ""
    @State private var history: SourceHistoryChoice = .all
    @State private var historyCount = 20
    @State private var deletePolicy: SourceDeletePolicy = .mirror
    @State private var includeVideos = true
    @State private var notifyReactions = true
    @State private var testing = false
    @State private var test: PhotoSourceTestResult?
    @State private var testedConnection: SourceConnection?
    @State private var saving = false
    @State private var error: SourceLine?
    @State private var errorText: String?
    @State private var prepared = false
    @State private var albumOnly = false
    @State private var albumOnlyOffered = false

    /// The first import would put many photos into the everyday slideshow: suggest "Only in album mode".
    private var suggestAlbumOnly: Bool {
        guard editing == nil, setAlbumOnly != nil, test?.ok == true else { return false }
        let upstream = test.map { ($0.photos ?? 0) + (includeVideos ? ($0.videos ?? 0) : 0) }
        return AlbumLogic.suggestsOnlyInAlbumMode(album: album, history: history, upstream: upstream)
    }

    private var features: PhotoSourceFeatures { PhotoSourceFeatures(app.discovery) }

    private var editing: PhotoSource? {
        if case .edit(let s, _) = mode { return s }
        return nil
    }

    private var focus: Focus {
        if case .edit(_, let f) = mode { return f }
        return .settings
    }

    private var target: SourceTargetRef? {
        switch mode {
        case .add(let t, _): t
        case .edit(let s, _): s.target.feedId.map { .feed($0) } ?? s.target.albumId.map { .album(frameId: s.target.frameId ?? "", albumId: $0) }
        }
    }

    private var isFeed: Bool { target?.isFeed ?? false }

    private var connection: SourceConnection {
        SourceConnection(provider: provider, link: link, password: password, serverURL: serverURL, apiKey: apiKey, immichAlbumId: albumId)
    }

    /// The connection is being changed (always when adding; when editing only if something was typed).
    private var changesConnection: Bool {
        guard editing != nil else { return true }
        switch provider {
        case .immichLink, .icloudLink: return !link.trimmingCharacters(in: .whitespaces).isEmpty
        case .immichApi:
            return !apiKey.trimmingCharacters(in: .whitespaces).isEmpty || albumId != editing?.immichAlbumId
                || (!serverURL.isEmpty && SourceInput.cleanServer(serverURL) != editing?.serverUrl)
        }
    }

    private var inputProblem: SourceInputProblem? {
        guard changesConnection else { return nil }
        if let editing, provider == .immichApi {
            // Editing: the stored key stays unless a new one is typed (taking over needs one).
            if apiKey.trimmingCharacters(in: .whitespaces).isEmpty, focus != .takeOver, editing.hasApiKey == true {
                return SourceInput.problem(serverURL.isEmpty ? (editing.serverUrl ?? "") : serverURL, provider: .immichApi,
                                           privateNetworks: features.privateNetworks)
            }
        }
        return connection.problem(privateNetworks: features.privateNetworks)
    }

    private var canTest: Bool {
        guard changesConnection, inputProblem == nil, !testing else { return false }
        if provider == .immichApi, apiKey.trimmingCharacters(in: .whitespaces).count < SourceConnection.apiKeyMin { return false }
        return true
    }

    private var canSave: Bool {
        guard !saving else { return false }
        if editing == nil {
            guard inputProblem == nil else { return false }
            if provider == .immichApi, albumId == nil { return false }
            return true
        }
        if focus == .takeOver, provider == .immichApi, apiKey.trimmingCharacters(in: .whitespaces).count < SourceConnection.apiKeyMin { return false }
        return inputProblem == nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    if editing == nil { providerSection }
                    if let editing { currentSection(editing) }
                    connectionSection
                    if provider == .immichApi, !albums.isEmpty || albumsBusy || albumsError != nil { albumSection }
                    if test != nil || testing { testSection }
                    settingsSection
                    if suggestAlbumOnly {
                        Section {
                            Toggle(L("ios.albums.onlyInAlbumMode"), isOn: $albumOnly)
                        } footer: {
                            Text(L("ios.ps.albumOnlySuggest", ["count": "\(AlbumLogic.albumOnlySuggestionThreshold)"]))
                        }
                    }
                    if let message = error?.text ?? errorText {
                        Section { Text(message).foregroundStyle(Palette.danger) }
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(editing == nil ? L("ios.ps.connect") : (focus == .takeOver ? L("ios.ps.fix.takeOver") : L("common.save"))) {
                            Task { await save() }
                        }
                        .fontWeight(.semibold)
                        .disabled(!canSave)
                    }
                }
            }
            .onAppear(perform: prepare)
            .onChange(of: provider) { test = nil; error = nil; errorText = nil }
            .onChange(of: suggestAlbumOnly) {
                // Suggested once, switched on; the person can still turn it off.
                if suggestAlbumOnly, !albumOnlyOffered { albumOnlyOffered = true; albumOnly = true }
            }
        }
    }

    private var title: String {
        switch mode {
        case .add: L("ios.ps.addTitle")
        case .edit(_, .takeOver): L("ios.ps.takeOverTitle")
        case .edit(let s, _): s.name
        }
    }

    // MARK: Sections

    private var providerSection: some View {
        Section {
            Picker(L("ios.ps.providerPick"), selection: $provider) {
                ForEach(features.providers, id: \.self) { p in
                    Text(providerTitle(p)).tag(p)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } header: {
            Text(L("ios.ps.providerPick"))
        } footer: {
            Text(providerHint(provider))
        }
    }

    private func providerTitle(_ p: SourceProvider) -> String {
        switch p {
        case .immichLink: L("ios.ps.provider.immichLink") + " · " + L("ios.ps.recommended")
        case .immichApi: L("ios.ps.provider.immichApi")
        case .icloudLink: L("ios.ps.provider.icloudLink") + " · " + L("ios.ps.bestEffort")
        }
    }

    private func providerHint(_ p: SourceProvider) -> String {
        switch p {
        case .immichLink: L("ios.ps.immichLinkWhy")
        case .immichApi: L("ios.ps.immichApiWhy")
        case .icloudLink: L("ios.ps.bestEffortNote")
        }
    }

    private func currentSection(_ s: PhotoSource) -> some View {
        Section {
            LabeledContent(L("ios.ps.provider"), value: providerTitle(s.kind ?? .immichLink))
            if let hint = s.urlHint { LabeledContent(L("ios.ps.link"), value: hint) }
            if let server = s.serverUrl { LabeledContent(L("ios.ps.server"), value: server) }
            LabeledContent(L("ios.ps.syncsTo"), value: SourceStatusText.target(s.target).text)
            if let owner = s.owner { LabeledContent(L("ios.ps.owner"), value: owner.displayName) }
        } footer: {
            if focus == .takeOver {
                Text(s.kind == .immichApi ? L("ios.ps.takeOverKey") : L("ios.ps.takeOverBody"))
            }
        }
    }

    @ViewBuilder
    private var connectionSection: some View {
        let replacing = editing != nil
        switch provider {
        case .immichLink, .icloudLink:
            Section {
                HStack {
                    TextField(provider == .icloudLink ? L("ios.ps.icloudPlaceholder") : L("ios.ps.immichLinkPlaceholder"), text: $link, axis: .vertical)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(1...3)
                        .accessibilityLabel(replacing ? L("ios.ps.newLink") : L("ios.ps.link"))
                    PasteButton(payloadType: String.self) { strings in
                        let text = strings.first ?? ""
                        Task { @MainActor in usePasted(text) }
                    }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.capsule)
                }
                if provider == .immichLink {
                    if replacing, editing?.hasPassword == true {
                        Toggle(L("ios.ps.removePassword"), isOn: $removePassword)
                    }
                    if !removePassword {
                        SecureField(replacing ? L("ios.ps.newPassword") : L("ios.ps.password"), text: $password)
                            .textContentType(.password)
                    }
                }
                problemRow
                testButton
            } header: {
                Text(replacing ? L("ios.ps.newLink") : L("ios.ps.link"))
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if provider == .icloudLink {
                        Text(L("ios.ps.icloudHowTo"))
                        Text(L("ios.ps.bestEffortNote")).foregroundStyle(Palette.warn)
                    } else {
                        Text(L("ios.ps.immichLinkHowTo"))
                        if replacing { Text(L("ios.ps.newLinkHint")) }
                        privateHint
                    }
                }
            }
        case .immichApi:
            Section {
                TextField(L("ios.ps.serverPlaceholder"), text: $serverURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityLabel(L("ios.ps.server"))
                SecureField(replacing ? L("ios.ps.newApiKey") : L("ios.ps.apiKey"), text: $apiKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                problemRow
                Button {
                    Task { await findAlbums() }
                } label: {
                    HStack {
                        Text(L("ios.ps.findAlbums"))
                        if albumsBusy { Spacer(); ProgressView() }
                    }
                }
                .disabled(albumsBusy || !canTest)
            } header: {
                Text(L("ios.ps.provider.immichApi"))
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("ios.ps.apiKeyHowTo"))
                    privateHint
                }
            }
        }
    }

    @ViewBuilder
    private var problemRow: some View {
        if changesConnection, let p = inputProblem, p != .empty {
            VStack(alignment: .leading, spacing: 6) {
                Text(L(p.key, ["host": connection.host ?? ""])).font(.footnote).foregroundStyle(Palette.danger)
                if case .wrongProvider(let other) = p, features.providers.contains(other), editing == nil {
                    Button(L("ios.ps.switchTo", ["name": L(other == .icloudLink ? "ios.ps.provider.icloudLink" : "ios.ps.provider.immichLink")])) {
                        provider = other
                    }
                    .buttonStyle(.kf(.soft))
                }
            }
        }
    }

    @ViewBuilder
    private var privateHint: some View {
        if !features.privateNetworks {
            Text(L("ios.ps.privateHint"))
        } else {
            Text(L("ios.ps.privateAllowed"))
        }
    }

    @ViewBuilder
    private var testButton: some View {
        Button {
            Task { await runTest() }
        } label: {
            HStack {
                Text(L("ios.ps.test"))
                if testing { Spacer(); ProgressView() }
            }
        }
        .disabled(!canTest)
    }

    private var albumSection: some View {
        Section {
            if let albumsError {
                Text(albumsError.text).foregroundStyle(Palette.danger)
            }
            ForEach(albums) { a in
                Button {
                    albumId = a.id
                    if name.isEmpty || albums.contains(where: { $0.name == name }) { name = String(a.name.prefix(SourceDraft.nameMax)) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(a.name).foregroundStyle(Palette.text)
                            Text([Lp("ios.ps.items", count: a.assetCount), a.shared == true ? a.ownerName.map { L("ios.ps.sharedBy", ["name": $0]) } : nil]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                        Spacer()
                        if albumId == a.id { Image(systemName: "checkmark").foregroundStyle(Palette.primary) }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(albumId == a.id ? .isSelected : [])
            }
        } header: {
            Text(L("ios.ps.pickAlbum"))
        }
    }

    private var testSection: some View {
        Section {
            if testing {
                HStack { ProgressView(); Text(L("ios.ps.testing")).foregroundStyle(Palette.textMuted) }
            } else if let test {
                if test.ok {
                    Label(SourceStatusText.testSummary(test), systemImage: "checkmark.circle").foregroundStyle(Palette.accentText)
                    if let v = test.serverVersion { Text(L("ios.ps.serverVersion", ["version": v])).font(.footnote).foregroundStyle(Palette.textMuted) }
                } else {
                    Label(SourceStatusText.testError(test.errorCode, provider: provider, host: connection.host).text, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(Palette.danger)
                }
                ForEach(SourceStatusText.warnings(test.warnings, limits: app.limits), id: \.key) { w in
                    Text(w.text).font(.footnote).foregroundStyle(Palette.warn)
                }
                if test.bestEffort == true || provider == .icloudLink {
                    Text(L("ios.ps.bestEffortNote")).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
        } header: {
            Text(L("ios.ps.testTitle"))
        }
    }

    @ViewBuilder
    private var settingsSection: some View {
        if focus == .settings || editing == nil {
            Section {
                TextField(L("ios.ps.namePlaceholder"), text: $name)
                    .onChange(of: name) { if name.count > SourceDraft.nameMax { name = String(name.prefix(SourceDraft.nameMax)) } }
            } header: {
                Text(L("ios.ps.name"))
            }
            if editing == nil {
                Section {
                    Picker(L("ios.ps.history"), selection: $history) {
                        Text(L("ios.ps.historyAll")).tag(SourceHistoryChoice.all)
                        Text(L("ios.ps.historyLast", ["count": "\(historyCount)"])).tag(SourceHistoryChoice.lastN(historyCount))
                        Text(L("ios.ps.historyNone")).tag(SourceHistoryChoice.none)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    if case .lastN = history {
                        Stepper(value: $historyCount, in: SourceHistoryChoice.countRange, step: historyCount < 50 ? 5 : 50) {
                            Text(Lp("ios.ps.photos", count: historyCount)).monospacedDigit()
                        }
                        .onChange(of: historyCount) { history = .lastN(historyCount) }
                    }
                } header: {
                    Text(L("ios.ps.history"))
                } footer: {
                    Text(L("ios.ps.historyHint"))
                }
            }
            Section {
                Picker(L("ios.ps.removed"), selection: $deletePolicy) {
                    Text(isFeed ? L("ios.ps.mirrorFeed") : L("ios.ps.mirrorAlbum")).tag(SourceDeletePolicy.mirror)
                    Text(isFeed ? L("ios.ps.keepFeed") : L("ios.ps.keepAlbum")).tag(SourceDeletePolicy.keep)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text(L("ios.ps.removed", ["service": provider.service.label]))
            } footer: {
                Text(L("ios.ps.removedHint"))
            }
            Section {
                Toggle(L("ios.ps.includeVideos"), isOn: $includeVideos)
                if editing == nil || editing?.isOwner == true {
                    Toggle(L("ios.ps.notifyReactions"), isOn: $notifyReactions)
                }
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if editing == nil || editing?.isOwner == true { Text(L("ios.ps.notifyHint")) }
                    if isFeed { Text(L("ios.ps.feedBatching")) } else { Text(L("ios.ps.albumTiming")) }
                    Text(isFeed ? L("ios.ps.visibleFeed", ["name": targetName]) : L("ios.ps.visibleAlbum", ["name": targetName]))
                }
            }
        }
    }

    private var targetName: String {
        switch mode {
        case .add(_, let n): n
        case .edit(let s, _): s.target.feedName ?? s.target.frameName ?? ""
        }
    }

    // MARK: Actions

    private func prepare() {
        guard !prepared else { return }
        prepared = true
        if let s = editing {
            provider = s.kind ?? .immichLink
            name = s.name
            deletePolicy = s.deletePolicy ?? .mirror
            includeVideos = s.includeVideos
            notifyReactions = s.notifyReactions ?? true
            serverURL = s.serverUrl ?? ""
            albumId = s.immichAlbumId
        } else if let target {
            provider = features.providers.first ?? .immichLink
            history = SourceDefaults.history(for: target)
            if case .lastN(let n) = history { historyCount = n }
            deletePolicy = SourceDefaults.deletePolicy(for: target)
        }
    }

    /// A pasted link picks the matching provider when adding (an iCloud link switches to iCloud).
    private func usePasted(_ text: String) {
        guard let found = SourceLink.extract(from: text) else { return }
        let parsed = SourceLink.parse(found)
        if editing == nil {
            switch parsed {
            case .icloudSharedAlbum: if features.providers.contains(.icloudLink) { provider = .icloudLink }
            case .immichShare: if features.providers.contains(.immichLink) { provider = .immichLink }
            default: break
            }
        }
        if provider == .immichApi { serverURL = found } else { link = found }
    }

    private func runTest() async {
        testing = true
        error = nil
        errorText = nil
        defer { testing = false }
        do {
            let result = try await api.testPhotoSource(connection)
            test = result
            testedConnection = connection
            if result.ok, name.isEmpty, let n = result.albumName { name = String(n.prefix(SourceDraft.nameMax)) }
            if provider == .immichApi, let list = result.albums, !list.isEmpty { albums = list }
        } catch {
            errorText = errorMessage(error)
        }
    }

    private func findAlbums() async {
        albumsBusy = true
        albumsError = nil
        defer { albumsBusy = false }
        do {
            let list = try await api.immichAlbums(serverURL: serverURL, apiKey: apiKey)
            if list.ok {
                albums = list.albums
                if albums.isEmpty { albumsError = SourceLine("ios.ps.noAlbums") }
            } else {
                albumsError = SourceStatusText.testError(list.errorCode, provider: .immichApi, host: connection.host)
            }
        } catch {
            albumsError = SourceLine(errorMessage(error), tone: .error)
        }
    }

    private func save() async {
        saving = true
        error = nil
        errorText = nil
        defer { saving = false }
        do {
            let result: PhotoSource
            if let s = editing {
                let patch = SourcePatch.body(s, name: name, deletePolicy: deletePolicy, includeVideos: includeVideos, notifyReactions: notifyReactions,
                                             newLink: provider == .immichApi ? "" : link,
                                             newPassword: provider == .immichLink ? password : nil, removePassword: removePassword,
                                             newServerURL: provider == .immichApi ? serverURL : "", newApiKey: provider == .immichApi ? apiKey : "",
                                             newAlbumId: provider == .immichApi ? albumId : nil, takeOver: focus == .takeOver)
                guard !patch.isEmpty else { dismiss(); return }
                result = try await api.updatePhotoSource(s.id, patch: patch)
            } else if let target {
                var draft = SourceDraft(connection: connection, target: target)
                draft.name = name
                draft.history = history
                draft.deletePolicy = deletePolicy
                draft.includeVideos = includeVideos
                draft.notifyReactions = notifyReactions
                result = try await api.createPhotoSource(draft)
                if suggestAlbumOnly, albumOnly, let setAlbumOnly { await setAlbumOnly() }
            } else {
                return
            }
            saved(result)
            dismiss()
        } catch {
            if let line = SourceErrors.line(error, provider: provider, host: connection.host) {
                self.error = line
            } else {
                errorText = errorMessage(error)
            }
        }
    }
}
