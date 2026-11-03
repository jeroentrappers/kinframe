import KinframeKit
import SwiftUI

/// "Show on TV" (web: TvDialog): show photos at the TV's own pace, mirror the frame live, or add another screen as a
/// frame. On iOS the TV is reached over AirPlay (the app draws the slideshow on the mirrored external screen) or with
/// a private link opened in the TV's browser.
struct TvSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let api: API
    let frameId: String
    let frameName: String
    let scope: TvScope

    enum Mode: String, CaseIterable, Identifiable {
        case show, mirror, frame
        var id: String { rawValue }
    }

    @State private var mode: Mode = .show
    @State private var interval = TvLogic.defaultInterval
    @State private var busy: String?
    @State private var link: TvLink?
    @State private var active: [TvLink] = []
    @State private var add: AddFrameModel?
    private var cast: TvCast { .shared }

    private var frame: FrameSummary? { app.frame(frameId) }
    private var canMirror: Bool { frame?.can(\.seeLibrary) ?? true }
    private var canRemote: Bool { frame?.can(\.changeSettings) ?? false }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    #if DEBUG
                    if cast.isLive, UserDefaults.standard.bool(forKey: "kfTvStage") {
                        // What the TV shows, inside the app (screenshots without a TV).
                        Color.black
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .overlay { TvStageView(cast: cast) }
                            .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                            .accessibilityHidden(true)
                    }
                    #endif
                    if cast.isLive {
                        nowShowing
                    } else {
                        modes
                        switch mode {
                        case .show: showPhotos
                        case .mirror: mirror
                        case .frame: otherScreen
                        }
                    }
                    if mode == .mirror, canRemote, !cast.isLive || cast.mode == .mirror { remote }
                    if let link { linkCard(link).id("link") }
                    if !others.isEmpty { activeLinks }
                }
                .padding(16)
            }
            .onChange(of: link?.id) { _, id in
                if id != nil { withAnimation { proxy.scrollTo("link", anchor: .top) } }
            }
            }
            .pageBackground()
            .navigationTitle(L("tv.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.close")) { dismiss() } } }
            .task {
                if add == nil { add = AddFrameModel(api: api) }
                await loadActive()
                #if DEBUG
                await DebugTv.run(self)
                #endif
            }
        }
    }

    private var others: [TvLink] { TvLogic.activeLinks(active, excluding: link?.id ?? cast.linkId) }

    // MARK: Modes

    private var modes: some View {
        VStack(spacing: 8) {
            modeRow(.show, "photo.on.rectangle", L("tv.modeShow"), L("tv.modeShowHint"))
            modeRow(.mirror, "rectangle.on.rectangle", L("tv.modeMirror"), L("tv.modeMirrorHint", ["frame": frameName]))
            modeRow(.frame, "tv", L("tv.modeFrame"), L("tv.modeFrameHint"))
        }
    }

    private func modeRow(_ m: Mode, _ symbol: String, _ title: String, _ hint: String) -> some View {
        let on = mode == m
        return Button {
            withAnimation { mode = m; link = nil }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol).font(.title3).frame(width: 40, height: 40)
                    .foregroundStyle(on ? Palette.onPrimary : Palette.primary)
                    .background(Circle().fill(on ? Palette.primary : Palette.primarySoft))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(hint).font(.footnote).foregroundStyle(Palette.textMuted).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: on ? "checkmark.circle.fill" : "circle").font(.title3)
                    .foregroundStyle(on ? Palette.primary : Palette.borderStrong)
            }
            .padding(12)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(on ? Palette.primary : Palette.border, lineWidth: on ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }

    // MARK: Show photos

    private var scopeLabel: String {
        switch scope {
        case .all: L("tv.scopeAll", ["frame": frameName])
        case .album(_, let name): L("tv.scopeAlbum", ["name": name])
        case .selection(let ids): Lp("tv.scopeSelection", count: ids.count)
        }
    }

    private var showPhotos: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(scopeLabel, systemImage: { if case .album = scope { "rectangle.stack" } else { "photo" } }())
                .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
            VStack(alignment: .leading, spacing: 6) {
                Text(L("tv.interval")).font(.subheadline).foregroundStyle(Palette.textMuted)
                Picker(L("tv.interval"), selection: $interval) {
                    ForEach(TvLogic.intervals, id: \.self) { Text(L("tv.seconds", ["n": $0])).tag($0) }
                }
                .pickerStyle(.segmented)
            }
            way("airplayvideo", L("ios.tv.airplay"), L("ios.tv.airplayHint"), id: "cast") { await startShow() }
            way("globe", L("tv.link"), L("tv.linkHint"), id: "link") { await makeLink(.tv) }
            if !cast.screenConnected { Callout(L("ios.tv.noScreen")) }
        }
    }

    // MARK: Mirror

    @ViewBuilder
    private var mirror: some View {
        if !canMirror {
            Callout(L("tv.mirrorNeedsLibrary", ["frame": frameName]))
        } else {
            VStack(alignment: .leading, spacing: 12) {
                way("airplayvideo", L("ios.tv.airplay"), L("ios.tv.mirrorAirplayHint", ["frame": frameName]), id: "mirror") { await startMirror() }
                way("globe", L("tv.link"), L("tv.linkHint"), id: "link") { await makeLink(.mirror) }
                if !cast.screenConnected { Callout(L("ios.tv.noScreen")) }
            }
        }
    }

    private var remote: some View {
        HStack(spacing: 10) {
            Text(L("tv.mirrorRemote")).font(.subheadline).foregroundStyle(Palette.textMuted)
            Spacer()
            Button { Task { await command(.previous) } } label: { Image(systemName: "backward.fill").frame(width: 44, height: 44) }
                .accessibilityLabel(L("tv.prev"))
            Button { Task { await command(.next) } } label: { Image(systemName: "forward.fill").frame(width: 44, height: 44) }
                .accessibilityLabel(L("tv.next"))
        }
        .buttonStyle(.kf(.secondary))
        .card(padding: 12)
    }

    // MARK: Another screen as a frame

    @ViewBuilder
    private var otherScreen: some View {
        if let add {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("tv.frameElsewhere")).font(.headline)
                Text(L("tv.frameElsewhereHint", ["url": browserFrameHost])).font(.footnote).foregroundStyle(Palette.textMuted)
                HStack {
                    TextField("1234 5678", text: Binding(get: { add.formatted }, set: { add.setCode($0) }))
                        .keyboardType(.numberPad)
                        .font(.title3.monospacedDigit())
                        .padding(12)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.borderStrong))
                        .accessibilityLabel(L("tv.frameCode"))
                    Button {
                        Task { await claim(add) }
                    } label: {
                        if add.state == .checking { ProgressView().tint(Palette.onPrimary) } else { Text(L("tv.frameAdd")) }
                    }
                    .buttonStyle(.kf(.primary))
                    .disabled(!add.canSubmit)
                }
                if let error = add.error { Callout(error, kind: .error) }
            }
            .card(padding: 16)
            Text(L("tv.frameNote")).font(.footnote).foregroundStyle(Palette.textMuted)
        }
    }

    private var browserFrameHost: String {
        let web = app.discovery?.web ?? ""
        return (web + "/tv/frame").replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
    }

    // MARK: Now showing

    private var nowShowing: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                RemoteThumb(url: cast.current?.thumbUrl, kind: cast.current?.kind ?? .photo)
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    if cast.status == .connecting {
                        Label(L("tv.connecting"), systemImage: "hourglass").font(.subheadline.weight(.semibold))
                    } else if !cast.screenConnected {
                        Label(L("ios.tv.waiting"), systemImage: "airplayvideo").font(.subheadline.weight(.semibold))
                            .foregroundStyle(Palette.warn)
                    } else {
                        Label(cast.screenName.map { L("tv.castingTo", ["device": $0]) } ?? L("tv.castingNoName"), systemImage: "airplayvideo")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.accentText)
                    }
                    if cast.mode == .mirror {
                        Text(L("tv.mirrorLabel", ["frame": cast.frameName])).font(.subheadline)
                    } else if let s = cast.current {
                        Text(s.caption?.isEmpty == false ? s.caption! : s.senderName).font(.subheadline).lineLimit(2)
                        Text(L("tv.position", ["n": cast.index + 1, "total": cast.slides.count]))
                            .font(.footnote.monospacedDigit()).foregroundStyle(Palette.textMuted)
                    }
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            if !cast.screenConnected { Callout(L("ios.tv.noScreen"), kind: .warn) }
            HStack(spacing: 8) {
                if cast.mode == .show {
                    Button { cast.previous() } label: { Image(systemName: "backward.fill").frame(width: 44, height: 44) }
                        .buttonStyle(.kf(.secondary)).accessibilityLabel(L("tv.prev"))
                    Button { cast.toggle() } label: { Image(systemName: cast.playing ? "pause.fill" : "play.fill").frame(width: 44, height: 44) }
                        .buttonStyle(.kf(.primary)).accessibilityLabel(cast.playing ? L("tv.pause") : L("tv.play"))
                    Button { cast.next() } label: { Image(systemName: "forward.fill").frame(width: 44, height: 44) }
                        .buttonStyle(.kf(.secondary)).accessibilityLabel(L("tv.next"))
                    Menu {
                        ForEach(TvLogic.intervals, id: \.self) { s in
                            Button(L("tv.seconds", ["n": s])) { cast.setInterval(s) }
                        }
                    } label: {
                        Text(L("tv.seconds", ["n": cast.interval])).font(.subheadline.monospacedDigit()).frame(minHeight: 44)
                    }
                    .accessibilityLabel(L("tv.interval"))
                }
                Spacer(minLength: 0)
            }
            Button {
                Task {
                    await cast.stop()
                    app.toasts.show(L("tv.stopped"))
                }
            } label: {
                Label(L("tv.stop"), systemImage: "xmark")
            }
            .buttonStyle(.kf(.dangerGhost))
        }
        .card(padding: 16)
    }

    // MARK: Links

    private func linkCard(_ link: TvLink) -> some View {
        let url = TvLogic.pageURL(link, web: app.discovery?.web)
        return VStack(alignment: .leading, spacing: 10) {
            Text(L("tv.linkReady")).font(.headline)
            if let url {
                Text(url.absoluteString)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.surface2, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                HStack(spacing: 8) {
                    Button {
                        UIPasteboard.general.url = url
                        app.toasts.show(L("tv.copied"))
                    } label: { Label(L("tv.copy"), systemImage: "doc.on.doc") }
                    .buttonStyle(.kf(.secondary))
                    ShareLink(item: url) { Label(L("ios.tv.share"), systemImage: "square.and.arrow.up") }
                        .buttonStyle(.kf(.secondary))
                    Button { openURL(url) } label: { Label(L("tv.open"), systemImage: "safari") }
                        .buttonStyle(.kf(.ghost))
                }
                .labelStyle(.titleAndIcon)
                .font(.subheadline)
            }
            Text(L("tv.validUntil", ["when": Format.dateTime(link.expiresAt, locale: L10n.locale)]))
                .font(.footnote).foregroundStyle(Palette.textMuted)
        }
        .card(padding: 16)
    }

    private var activeLinks: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Lp("tv.activeLinks", count: others.count)).font(.headline)
            VStack(spacing: 0) {
                ForEach(Array(others.enumerated()), id: \.element.id) { n, l in
                    HStack(spacing: 10) {
                        Image(systemName: l.purpose == .mirror ? "rectangle.on.rectangle" : "tv").foregroundStyle(Palette.textMuted)
                        Text(L("tv.linkBy", ["name": l.createdByName ?? "?", "when": Format.dateTime(l.expiresAt, locale: L10n.locale)]))
                            .font(.subheadline)
                        Spacer()
                        Button(L("tv.revoke")) { Task { await revoke(l) } }.buttonStyle(.kf(.dangerGhost))
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 52)
                    if n < others.count - 1 { Divider().overlay(Palette.border).padding(.leading, 14) }
                }
            }
            .card(padding: 0)
        }
    }

    private func way(_ symbol: String, _ title: String, _ hint: String, id: String, action: @escaping () async -> Void) -> some View {
        Button {
            Task {
                busy = id
                await action()
                busy = nil
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol).font(.title3).foregroundStyle(Palette.primary).frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(hint).font(.footnote).foregroundStyle(Palette.textMuted).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                if busy == id { ProgressView() } else { Image(systemName: "chevron.right").foregroundStyle(Palette.textFaint) }
            }
            .padding(14)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.border))
        }
        .buttonStyle(CardButtonStyle())
        .disabled(busy != nil)
    }

    // MARK: Actions

    func loadActive() async {
        let id = frameId
        active = (try? await api.call { try await $0.listTvLinks(path: .init(frameId: id)).ok.body.json }) ?? []
    }

    func startShow() async {
        do {
            try await cast.start(api: api, frameId: frameId, frameName: frameName, scope: scope, interval: interval)
        } catch {
            let notFound = (error as? APIError)?.status == 404
            app.toasts.show(notFound ? L("tv.nothing") : L("tv.failed"), isError: true)
        }
    }

    func startMirror() async {
        do {
            try await cast.startMirror(api: api, frameId: frameId, frameName: frameName)
        } catch {
            app.toasts.error(error)
        }
    }

    func makeLink(_ purpose: TvLogic.Purpose) async {
        let id = frameId
        let body = TvLogic.linkBody(scope: scope, interval: interval, purpose: purpose)
        do {
            link = try await api.call { try await $0.createTvLink(path: .init(frameId: id), body: .json(body)).created.body.json }
            await loadActive()
        } catch {
            app.toasts.error(error)
        }
    }

    private func revoke(_ l: TvLink) async {
        let id = frameId
        do {
            _ = try await api.call { try await $0.revokeTvLink(path: .init(frameId: id, linkId: l.id)).noContent }
            if link?.id == l.id { link = nil }
            active.removeAll { $0.id == l.id }
            app.toasts.show(L("tv.revoked"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func command(_ type: Components.Schemas.FrameCommand._TypePayload) async {
        let id = frameId
        do {
            let r = try await api.call { try await $0.sendFrameCommand(path: .init(frameId: id), body: .json(.init(_type: type))).ok.body.json }
            if !r.ok { app.toasts.show(L("tv.mirrorFailed"), isError: true) }
        } catch {
            app.toasts.show(L("tv.mirrorFailed"), isError: true)
        }
    }

    private func claim(_ add: AddFrameModel) async {
        guard let result = await add.redeem() else { return }
        await app.loadFrames()
        app.toasts.show(L("tv.frameAdded", ["name": result.frame.name]))
        add.reset()
    }

    /// For DEBUG launch arguments.
    func select(_ m: Mode) { mode = m }
}

/// Which TV sheet is open, for `.sheet(item:)`.
struct TvRequest: Identifiable {
    let id = UUID()
    let frameId: String
    let frameName: String
    let scope: TvScope
}

#if DEBUG
/// `-kfTvMode show|mirror|frame` picks a mode, `-kfTvLink YES` makes a TV link, `-kfTvStart YES` starts the AirPlay
/// slideshow (also without a TV, to see the remote), `-kfTvStage YES` also shows the TV's picture in the app.
@MainActor
enum DebugTv {
    static func run(_ sheet: TvSheet) async {
        let args = UserDefaults.standard
        if let m = args.string(forKey: "kfTvMode").flatMap(TvSheet.Mode.init(rawValue:)) { sheet.select(m) }
        try? await Task.sleep(for: .milliseconds(300))
        if args.bool(forKey: "kfTvLink") {
            args.set(false, forKey: "kfTvLink")
            await sheet.makeLink(args.string(forKey: "kfTvMode") == "mirror" ? .mirror : .tv)
        }
        if args.bool(forKey: "kfTvStart") {
            args.set(false, forKey: "kfTvStart")
            if args.string(forKey: "kfTvMode") == "mirror" { await sheet.startMirror() } else { await sheet.startShow() }
        }
    }
}
#endif
