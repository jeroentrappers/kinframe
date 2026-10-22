import KinframeKit
import SwiftUI

/// One frame: Overview, Photos, People and (with change_settings) Settings, like the web's frame page.
@MainActor
@Observable
final class FrameDetailModel {
    let api: API
    let id: String
    private(set) var frame: FrameDetail?
    private(set) var health: FrameHealth?
    var error: String?

    init(api: API, id: String) {
        self.api = api
        self.id = id
    }

    var summary: FrameSummary? { frame?.summary }

    func load() async {
        let id = id
        do {
            frame = try await api.call { try await $0.getFrame(path: .init(frameId: id)).ok.body.json }
            error = nil
        } catch is CancellationError {
        } catch {
            if frame == nil { self.error = errorMessage(error) }
        }
        if let s = summary, s.can(\.changeSettings) {
            health = try? await api.call { try await $0.getFrameHealth(path: .init(frameId: id)).ok.body.json }
        }
    }

    func apply(_ event: LiveEvent) {
        switch event {
        case .frameStatus(let frameId, let online, let lastSeen) where frameId == id:
            frame?.value1.online = online
            if let lastSeen { frame?.value1.lastSeenAt = lastSeen }
        case .membershipUpdated(let frameId) where frameId == id:
            Task { await load() }
        default:
            break
        }
    }

    enum Command: String, CaseIterable, Identifiable {
        case previous, next, sleep, wake, identify, syncNow = "sync_now"
        var id: String { rawValue }

        var api: Components.Schemas.FrameCommand._TypePayload {
            switch self {
            case .previous: .previous
            case .next: .next
            case .sleep: .sleep
            case .wake: .wake
            case .identify: .identify
            case .syncNow: .syncNow
            }
        }

        var symbol: String {
            switch self {
            case .previous: "backward.fill"
            case .next: "forward.fill"
            case .sleep: "moon.fill"
            case .wake: "sun.max.fill"
            case .identify: "text.viewfinder"
            case .syncNow: "arrow.triangle.2.circlepath"
            }
        }

        var label: String {
            switch self {
            case .previous: L("cmd.previous")
            case .next: L("cmd.next")
            case .sleep: L("cmd.sleep")
            case .wake: L("cmd.wake")
            case .identify: L("cmd.identify")
            case .syncNow: L("ios.cmd.sync")
            }
        }

        func done(_ name: String) -> String {
            switch self {
            case .previous: L("cmd.donePrevious")
            case .next: L("cmd.doneNext")
            case .sleep: L("cmd.doneSleep", ["name": name])
            case .wake: L("cmd.doneWake", ["name": name])
            case .identify: L("cmd.doneIdentify", ["name": name])
            case .syncNow: L("ios.cmd.doneSync", ["name": name])
            }
        }
    }

    /// Remote command, delivered over the frame's websocket (never queued: `409 frame_offline`).
    func send(_ command: Command) async throws {
        let id = id
        let result = try await api.call {
            try await $0.sendFrameCommand(path: .init(frameId: id), body: .json(.init(_type: command.api))).ok.body.json
        }
        if !result.ok { throw APIError(status: 0, code: "command_failed") }
    }

    func leave(removeMine: Bool) async throws {
        let id = id
        _ = try await api.call { try await $0.leaveFrame(path: .init(frameId: id), body: .json(.init(removeMyItems: removeMine))).noContent }
    }

    func remove() async throws {
        let id = id
        _ = try await api.call { try await $0.removeFrame(path: .init(frameId: id)).noContent }
    }

    func transfer(to userId: String) async throws {
        let id = id
        _ = try await api.call { try await $0.transferOwnership(path: .init(frameId: id), body: .json(.init(userId: userId))).noContent }
        await load()
    }
}

struct FrameDetailView: View {
    @Environment(AppModel.self) private var app
    let route: FrameRoute
    @State private var model: FrameDetailModel?
    @State private var section: FrameSection = .overview

    var body: some View {
        Group {
            if let model, let frame = model.summary {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header(frame)
                        Picker(L("frame.sections"), selection: $section) {
                            ForEach(sections(frame), id: \.self) { s in
                                Text(title(s)).tag(s)
                            }
                        }
                        .pickerStyle(.segmented)
                        switch section {
                        case .overview: FrameOverviewSection(model: model)
                        case .photos: FrameLibrarySection(api: model.api, frame: frame)
                        case .people: FramePeopleSection(api: model.api, frame: model.frame!)
                        case .settings: FrameSettingsSection(api: model.api, frame: frame)
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
                }
                .refreshable { await model.load() }
            } else if let error = model?.error {
                ErrorRetryView(message: error) { Task { await model?.load() } }.padding(16)
            } else {
                LoadingView()
            }
        }
        .pageBackground()
        .navigationTitle(model?.summary?.name ?? app.frame(route.id)?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let frame = model?.summary, frame.can(\.send) {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        app.composeFrameIds = [frame.id]
                        app.tab = .send
                    } label: {
                        Label(L("nav.send"), systemImage: "paperplane")
                    }
                }
            }
        }
        .task {
            guard model == nil, let api = app.api else { return }
            section = route.section
            let m = FrameDetailModel(api: api, id: route.id)
            model = m
            await m.load()
            if let s = m.summary { app.updateFrame(s) }
        }
        .onLiveEvent(app.events) { model?.apply($0) }
    }

    private func header(_ frame: FrameSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(frame.name).font(.display(.largeTitle)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                Spacer()
                RoleBadge(role: frame.role)
            }
            HStack(spacing: 14) {
                OnlineStatus(online: frame.online, lastSeen: frame.lastSeenAt)
                if let location = frame.location, !location.isEmpty {
                    Label(location, systemImage: "mappin").labelStyle(TightLabelStyle()).font(.subheadline).foregroundStyle(Palette.textMuted)
                }
            }
        }
    }

    private func sections(_ frame: FrameSummary) -> [FrameSection] {
        frame.can(\.changeSettings) ? [.overview, .photos, .people, .settings] : [.overview, .photos, .people]
    }

    private func title(_ s: FrameSection) -> String {
        switch s {
        case .overview: L("frame.tabOverview")
        case .photos: L("frame.tabLibrary")
        case .people: L("frame.tabPeople")
        case .settings: L("frame.tabSettings")
        }
    }
}

// MARK: Overview

struct FrameOverviewSection: View {
    @Environment(AppModel.self) private var app
    let model: FrameDetailModel
    @State private var running: FrameDetailModel.Command?
    @State private var confirmLeave = false
    @State private var confirmRemove = false
    @State private var removeMine = false
    @State private var typedName = ""
    @State private var transferring = false

    var body: some View {
        if let frame = model.summary {
            VStack(alignment: .leading, spacing: 16) {
                if frame.can(\.changeSettings) { remote(frame) }
                if let health = model.health { healthCard(health) }
                details(frame)
                danger(frame)
            }
            .sheet(isPresented: $transferring) {
                TransferSheet(model: model)
            }
        }
    }

    private func remote(_ frame: FrameSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(L("overview.remote"))
            if !frame.online {
                Callout(L("overview.offlineControl"), kind: .warn)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
                ForEach(FrameDetailModel.Command.allCases) { cmd in
                    Button {
                        Task { await run(cmd, frame: frame) }
                    } label: {
                        VStack(spacing: 6) {
                            if running == cmd { ProgressView() } else { Image(systemName: cmd.symbol).font(.title3) }
                            Text(cmd.label).font(.footnote.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity, minHeight: 72)
                        .foregroundStyle(Palette.text)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.border))
                    }
                    .buttonStyle(CardButtonStyle())
                    .disabled(!frame.online || running != nil)
                    .opacity(frame.online ? 1 : 0.5)
                }
            }
        }
    }

    private func run(_ cmd: FrameDetailModel.Command, frame: FrameSummary) async {
        running = cmd
        defer { running = nil }
        do {
            try await model.send(cmd)
            app.toasts.show(cmd.done(frame.name))
        } catch let e as APIError where e.code == "command_failed" {
            app.toasts.show(L("cmd.failed"), isError: true)
        } catch {
            app.toasts.error(error)
        }
    }

    private func healthCard(_ h: FrameHealth) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("overview.health"))
            VStack(spacing: 0) {
                if let hb = h.lastHeartbeat {
                    if let rssi = hb.wifiRssiDbm {
                        row(L("health.wifi"), value: [hb.wifiSsid, "\(rssi) dBm"].compactMap { $0 }.joined(separator: " · "), trailing: AnyView(SignalBars(bars: Format.signalBars(rssi))))
                    }
                    if let free = hb.storageFreeBytes, let total = hb.storageTotalBytes {
                        row(L("health.storage"), value: L("health.free", ["free": Format.bytes(Int64(free), locale: L10n.locale), "total": Format.bytes(Int64(total), locale: L10n.locale)]))
                    }
                    if let cached = hb.placementsCached {
                        let missing = hb.placementsMissing ?? 0
                        row(L("health.cached"), value: "\(cached)" + (missing > 0 ? " · " + Lp("health.missing", count: missing) : ""))
                    }
                    row(L("health.uptime"), value: Format.uptime(hb.uptimeS))
                    row(L("health.version"), value: hb.appVersion)
                }
                row(L("health.lastSeen"), value: Format.relative(h.lastSeenAt, locale: L10n.locale), last: true)
            }
            .card(padding: 0)
            if let unclean = h.lastUncleanShutdownAt {
                Callout(L("health.unclean", ["when": Format.relative(unclean, locale: L10n.locale)]), kind: .warn)
            }
            if let crashes = h.lastHeartbeat?.crashCount24h, crashes > 0 {
                Callout(Lp("health.crashes", count: crashes), kind: .warn)
            }
        }
    }

    private func details(_ frame: FrameSummary) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("overview.details"))
            VStack(spacing: 0) {
                row(L("overview.owner"), value: frame.ownerName ?? "–")
                if let n = model.frame?.value2.memberCount { row(L("overview.members"), value: "\(n)") }
                if let n = model.frame?.value2.placementCount { row(L("overview.photos"), value: "\(n)") }
                if let s = frame.screen { row(L("overview.screen"), value: "\(s.width) × \(s.height)") }
                if let created = model.frame?.value2.createdAt {
                    row(L("overview.since"), value: created.formatted(date: .long, time: .omitted), last: true)
                }
            }
            .card(padding: 0)
        }
    }

    private func row(_ label: String, value: String, trailing: AnyView? = nil, last: Bool = false) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text(label).foregroundStyle(Palette.textMuted)
                Spacer()
                trailing
                Text(value).foregroundStyle(Palette.text).multilineTextAlignment(.trailing)
            }
            .font(.subheadline)
            .padding(.horizontal, 16)
            .frame(minHeight: 46)
            .accessibilityElement(children: .combine)
            if !last { Divider().overlay(Palette.border).padding(.leading, 16) }
        }
    }

    private func danger(_ frame: FrameSummary) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(frame.role == .owner ? L("overview.dangerTitle") : L("overview.leaveTitle"))
            VStack(alignment: .leading, spacing: 12) {
                if frame.role == .owner {
                    Text(L("overview.transferBody")).font(.subheadline).foregroundStyle(Palette.textMuted)
                    Button(L("overview.transferButton")) { transferring = true }.buttonStyle(.kf(.secondary))
                    Divider().overlay(Palette.border)
                    Text(L("overview.removeBody")).font(.subheadline).foregroundStyle(Palette.textMuted)
                    Button(L("overview.removeButton"), role: .destructive) { confirmRemove = true }.buttonStyle(.kf(.dangerGhost))
                } else {
                    Text(L("overview.leaveBody")).font(.subheadline).foregroundStyle(Palette.textMuted)
                    Button(L("overview.leaveButton"), role: .destructive) { confirmLeave = true }.buttonStyle(.kf(.dangerGhost))
                }
            }
            .card(padding: 16)
        }
        .alert(L("overview.leaveConfirm", ["name": frame.name]), isPresented: $confirmLeave) {
            Button(L("overview.leaveButton"), role: .destructive) { Task { await leave(frame, removeMine: false) } }
            Button(L("overview.removeMine"), role: .destructive) { Task { await leave(frame, removeMine: true) } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("overview.leaveDialogBody"))
        }
        .alert(L("overview.removeConfirm", ["name": frame.name]), isPresented: $confirmRemove) {
            TextField(frame.name, text: $typedName)
            Button(L("overview.removeButton"), role: .destructive) { Task { await remove(frame) } }
                .disabled(typedName.trimmingCharacters(in: .whitespaces) != frame.name)
            Button(L("common.cancel"), role: .cancel) { typedName = "" }
        } message: {
            Text(L("overview.removeDialogBody") + "\n\n" + L("overview.typeName", ["name": frame.name]))
        }
    }

    private func leave(_ frame: FrameSummary, removeMine: Bool) async {
        do {
            try await model.leave(removeMine: removeMine)
            app.removeFrame(frame.id)
            app.framesPath = NavigationPath()
            app.toasts.show(L("overview.left", ["name": frame.name]))
        } catch {
            app.toasts.error(error)
        }
    }

    private func remove(_ frame: FrameSummary) async {
        guard typedName.trimmingCharacters(in: .whitespaces) == frame.name else { return }
        do {
            try await model.remove()
            app.removeFrame(frame.id)
            app.framesPath = NavigationPath()
            app.toasts.show(L("overview.removed", ["name": frame.name]))
        } catch {
            app.toasts.error(error)
        }
    }
}

struct SignalBars: View {
    let bars: Int

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < bars ? Palette.accent : Palette.surface3)
                    .frame(width: 4, height: CGFloat(5 + i * 3))
            }
        }
        .padding(.trailing, 6)
        .accessibilityHidden(true)
    }
}

/// Hand the frame to another member (owner only). The previous owner becomes admin.
private struct TransferSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: FrameDetailModel
    @State private var members: [Member] = []
    @State private var choice: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L("transfer.body")).font(.subheadline).foregroundStyle(Palette.textMuted)
                }
                let eligible = members.filter { $0.role != .owner }
                if eligible.isEmpty {
                    Text(L("transfer.noOne")).foregroundStyle(Palette.textMuted)
                } else {
                    Section(L("transfer.pick")) {
                        ForEach(eligible) { m in
                            Button {
                                choice = m.userId
                            } label: {
                                HStack {
                                    AvatarView(name: m.displayName, url: m.avatarUrl, size: 32)
                                    Text(m.displayName).foregroundStyle(Palette.text)
                                    RoleBadge(role: m.role)
                                    Spacer()
                                    if choice == m.userId { Image(systemName: "checkmark").foregroundStyle(Palette.primary) }
                                }
                            }
                            .accessibilityAddTraits(choice == m.userId ? .isSelected : [])
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("transfer.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("transfer.do")) { Task { await transfer() } }
                        .disabled(choice == nil || busy)
                }
            }
            .task {
                let id = model.id
                members = (try? await model.api.call { try await $0.listMembers(path: .init(frameId: id)).ok.body.json }) ?? []
                choice = members.first { $0.role == .admin }?.userId
            }
        }
    }

    private func transfer() async {
        guard let choice, let name = members.first(where: { $0.userId == choice })?.displayName else { return }
        busy = true
        defer { busy = false }
        do {
            try await model.transfer(to: choice)
            if let s = model.summary { app.updateFrame(s) }
            app.toasts.show(L("transfer.done", ["name": name]))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}
