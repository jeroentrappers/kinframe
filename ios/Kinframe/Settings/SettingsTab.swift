import KinframeKit
import OpenAPIRuntime
import PhotosUI
import SwiftUI

/// Profile (name, photo), language, server and sign-out.
struct SettingsTab: View {
    @Environment(AppModel.self) private var app
    @State private var name = ""
    @State private var avatarPick: PhotosPickerItem?
    @State private var savingName = false
    @State private var uploadingAvatar = false
    @State private var confirmSignOut = false
    @State private var confirmChangeServer = false
    @State private var language = L10n.language
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    profile
                    preferences
                    server
                    Section {
                        Button(L("settings.signOut"), role: .destructive) { confirmSignOut = true }
                    } footer: {
                        Text(L("settings.about")).frame(maxWidth: .infinity).padding(.top, 12)
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("settings.title"))
            .confirmationDialog(L("settings.signOut"), isPresented: $confirmSignOut, titleVisibility: .hidden) {
                Button(L("settings.signOut"), role: .destructive) { Task { await app.signOut() } }
            }
            .alert(L("ios.server.changeTitle"), isPresented: $confirmChangeServer) {
                Button(L("ios.server.change"), role: .destructive) {
                    Task {
                        await app.signOut(keepServer: false)
                        Onboarding.step = .server
                    }
                }
                Button(L("common.cancel"), role: .cancel) {}
            } message: {
                Text(L("ios.server.changeBody"))
            }
            .onAppear { name = app.me?.displayName ?? "" }
            .onChange(of: app.me?.displayName) { name = app.me?.displayName ?? "" }
            .onChange(of: avatarPick) { _, item in
                guard let item else { return }
                avatarPick = nil
                Task { await uploadAvatar(item) }
            }
        }
    }

    private var profile: some View {
        Section(L("settings.profile")) {
            HStack(spacing: 16) {
                ZStack {
                    AvatarView(name: app.me?.displayName, url: app.me?.avatarUrl, size: 72)
                    if uploadingAvatar { ProgressView().frame(width: 72, height: 72).background(Circle().fill(.black.opacity(0.3))) }
                }
                VStack(alignment: .leading, spacing: 8) {
                    PhotosPicker(selection: $avatarPick, matching: .images) {
                        Text(L("settings.changePhoto"))
                    }
                    .buttonStyle(.kf(.soft))
                    if app.me?.avatarUrl != nil {
                        Button(L("settings.removePhoto"), role: .destructive) { Task { await removeAvatar() } }
                            .buttonStyle(.kf(.dangerGhost))
                    }
                }
            }
            .padding(.vertical, 6)
            VStack(alignment: .leading, spacing: 6) {
                Text(L("settings.displayName")).font(.subheadline.weight(.semibold))
                HStack {
                    TextField(L("ios.name.placeholder"), text: $name)
                        .focused($nameFocused)
                        .submitLabel(.done)
                        .onSubmit { Task { await saveName() } }
                    if nameChanged {
                        Button(L("common.save")) { Task { await saveName() } }
                            .buttonStyle(.kf(.primary))
                            .disabled(savingName)
                    }
                }
                Text(L("settings.displayNameHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            if let email = app.me?.email {
                LabeledContent(L("settings.email"), value: email)
            }
        }
    }

    private var nameChanged: Bool {
        let t = name.trimmingCharacters(in: .whitespaces)
        return !t.isEmpty && t != app.me?.displayName
    }

    private var preferences: some View {
        Section {
            Picker(L("settings.language"), selection: $language) {
                ForEach(AppLanguage.allCases) { Text($0.nativeName).tag($0) }
            }
            .onChange(of: language) { _, lang in
                L10n.setLanguage(lang)
                NotificationCenter.default.post(name: .kinframeLanguageChanged, object: nil)
                let code = L10n.code
                if let api = app.api {
                    Task { _ = try? await api.call { try await $0.updateMe(body: .json(.init(locale: code))).ok } }
                }
            }
        } header: {
            Text(L("settings.preferences"))
        } footer: {
            Text(L("ios.settings.languageHint"))
        }
    }

    private var server: some View {
        Section(L("settings.server")) {
            if let d = app.discovery, let url = app.server?.url {
                LabeledContent(L("settings.serverName"), value: d.name)
                LabeledContent(L("settings.version"), value: d.version)
                LabeledContent(L("settings.address"), value: ServerAddress.display(url))
                LabeledContent(L("settings.maxItems"), value: "\(d.limits.maxItemsPerSend)")
                LabeledContent(L("settings.maxVideo"), value: Format.duration(Double(d.limits.maxVideoSeconds)))
                LabeledContent(L("settings.maxUpload"), value: Format.bytes(d.limits.maxUploadBytes, locale: L10n.locale))
            }
            if app.auth.mode == .dev {
                Text(L("settings.devMode")).font(.footnote).foregroundStyle(Palette.warn)
            }
            Button(L("ios.server.change")) { confirmChangeServer = true }
        }
    }

    private func saveName() async {
        guard nameChanged else { return }
        savingName = true
        await app.updateName(String(name.trimmingCharacters(in: .whitespaces).prefix(40)))
        savingName = false
        nameFocused = false
        app.toasts.show(L("settings.nameSaved"))
    }

    /// `PUT /v1/me/avatar` with a square JPEG; the server crops to 256×256.
    private func uploadAvatar(_ item: PhotosPickerItem) async {
        guard let api = app.api, let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
              let jpeg = squareJPEG(image, side: 512) else { return }
        uploadingAvatar = true
        defer { uploadingAvatar = false }
        do {
            app.me = try await api.call { try await $0.putAvatar(body: .jpeg(HTTPBody(jpeg))).ok.body.json }
            app.toasts.show(L("settings.avatarSaved"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func removeAvatar() async {
        guard let api = app.api else { return }
        do {
            _ = try await api.call { try await $0.deleteAvatar().noContent }
            await app.loadMe()
        } catch {
            app.toasts.error(error)
        }
    }

    private func squareJPEG(_ image: UIImage, side: CGFloat) -> Data? {
        let s = min(image.size.width, image.size.height)
        let scale = side / s
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let out = renderer.image { _ in
            let w = image.size.width * scale, h = image.size.height * scale
            image.draw(in: CGRect(x: (side - w) / 2, y: (side - h) / 2, width: w, height: h))
        }
        return out.jpegData(compressionQuality: 0.85)
    }
}
