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

    /// Name and location (owner and admins, `PATCH /v1/frames/{id}`).
    func saveDetails(name: String, location: String) async throws {
        let id = id
        let body = Operations.UpdateFrame.Input.Body.JsonPayload(name: FrameDetailsForm.clean(name), location: FrameDetailsForm.clean(location))
        frame = try await api.call { try await $0.updateFrame(path: .init(frameId: id), body: .json(body)).ok.body.json }
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
                ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header(frame)
                        sectionPicker(frame)
                        switch section {
                        case .overview: FrameOverviewSection(model: model)
                        case .photos: FrameLibrarySection(api: model.api, frame: frame)
                        case .people: FramePeopleSection(api: model.api, frame: model.frame!)
                        case .calendar: FrameCalendarSection(api: model.api, frame: frame)
                        case .settings: FrameSettingsSection(api: model.api, frame: frame)
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
                }
                .refreshable { await model.load() }
                #if DEBUG
                .task {
                    if let target = UserDefaults.standard.string(forKey: "kfScrollTo"), !target.isEmpty {
                        try? await Task.sleep(for: .seconds(2.5))
                        withAnimation { proxy.scrollTo(target, anchor: .top) }
                    }
                }
                #endif
                }
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

    /// The sections as a segmented control; with five of them (Calendar) a scrolling row of capsules, so long labels
    /// (Dutch "Instellingen") aren't cut off on a phone.
    @ViewBuilder
    private func sectionPicker(_ frame: FrameSummary) -> some View {
        let list = sections(frame)
        if list.count <= 4 {
            Picker(L("frame.sections"), selection: $section) {
                ForEach(list, id: \.self) { s in Text(title(s)).tag(s) }
            }
            .pickerStyle(.segmented)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(list, id: \.self) { s in
                        let on = section == s
                        Button { withAnimation(.snappy) { section = s } } label: {
                            Text(title(s))
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 13)
                                .frame(minHeight: 34)
                                .foregroundStyle(on ? Palette.text : Palette.textMuted)
                                .background(Capsule().fill(on ? Palette.surface : .clear))
                                .shadow(color: on ? .black.opacity(0.08) : .clear, radius: 2, y: 1)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
                    }
                }
                .padding(3)
            }
            .background(Capsule().fill(Palette.surface2))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(L("frame.sections"))
        }
    }

    private func sections(_ frame: FrameSummary) -> [FrameSection] {
        var list: [FrameSection] = [.overview, .photos, .people]
        // Calendars, sent events and countdowns (web: the Calendar tab), when the server has them.
        if app.discovery?.calendar == true, frame.can(\.changeSettings) || frame.can(\.send) { list.append(.calendar) }
        if frame.can(\.changeSettings) { list.append(.settings) }
        return list
    }

    private func title(_ s: FrameSection) -> String {
        switch s {
        case .overview: L("frame.tabOverview")
        case .photos: L("frame.tabLibrary")
        case .people: L("frame.tabPeople")
        case .calendar: L("frame.tabCalendar")
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
    @State private var transferring: TransferRequest?
    @State private var leavingAsOwner = false
    @State private var replacing = false
    @State private var editName = ""
    @State private var editLocation = ""
    @State private var savingDetails = false

    var body: some View {
        if let frame = model.summary {
            VStack(alignment: .leading, spacing: 16) {
                if frame.can(\.changeSettings) { remote(frame) }
                if let health = model.health { healthCard(health) }
                details(frame).id("details")
                if frame.role == .owner { ownership(frame).id("ownership") }
                danger(frame)
            }
            .sheet(item: $transferring) { _ in
                TransferSheet(api: model.api, frame: frame) { _ in Task { await model.load() } }
            }
            .sheet(isPresented: $leavingAsOwner) {
                TransferSheet(api: model.api, frame: frame, leaving: true)
            }
            .sheet(isPresented: $replacing) {
                ReplaceDeviceSheet(api: model.api, frame: frame) { Task { await model.load() } }
            }
            .onAppear {
                editName = frame.name
                editLocation = frame.location ?? ""
            }
            #if DEBUG
            .task {
                try? await Task.sleep(for: .seconds(1.5))
                if DebugLaunch.takeSheet("transfer") { transferring = TransferRequest() }
                if DebugLaunch.takeSheet("replace") { replacing = true }
                if DebugLaunch.takeSheet("leave") { leavingAsOwner = true }
            }
            #endif
            .onChange(of: frame.name) { editName = frame.name }
            .onChange(of: frame.location) { editLocation = frame.location ?? "" }
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
                    if model.summary?.deviceKind != .browser, let rssi = hb.wifiRssiDbm {
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
            if frame.isManager { detailsForm(frame) }
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

    /// Name and location, for the owner and admins (web: the details form).
    private func detailsForm(_ frame: FrameSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("frame.name")).font(.subheadline.weight(.semibold))
                TextField(L("frame.name"), text: $editName)
                    .textFieldStyle(KFFieldStyle())
                    .submitLabel(.done)
                    .onChange(of: editName) { if editName.count > FrameDetailsForm.maxLength { editName = String(editName.prefix(FrameDetailsForm.maxLength)) } }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(L("frame.location")).font(.subheadline.weight(.semibold))
                TextField(L("add.locationPlaceholder"), text: $editLocation)
                    .textFieldStyle(KFFieldStyle())
                    .submitLabel(.done)
                    .onChange(of: editLocation) { if editLocation.count > FrameDetailsForm.maxLength { editLocation = String(editLocation.prefix(FrameDetailsForm.maxLength)) } }
            }
            Button {
                Task { await saveDetails() }
            } label: {
                HStack(spacing: 6) {
                    if savingDetails { ProgressView().tint(Palette.onPrimary) }
                    Text(L("common.save"))
                }
            }
            .buttonStyle(.kf(.primary))
            .disabled(savingDetails || !FrameDetailsForm.canSave(name: editName, location: editLocation, current: frame))
        }
        .card(padding: 16)
    }

    private func saveDetails() async {
        savingDetails = true
        defer { savingDetails = false }
        do {
            try await model.saveDetails(name: editName, location: editLocation)
            if let s = model.summary { app.updateFrame(s) }
            app.toasts.show(L("common.saved"))
        } catch {
            app.toasts.error(error)
        }
    }

    /// Owner only: hand the frame over, or move it to a new device.
    private func ownership(_ frame: FrameSummary) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("overview.ownership"))
            VStack(alignment: .leading, spacing: 12) {
                ownershipRow(L("transfer.title"), L("overview.transferBody"), button: L("overview.transferButton"), symbol: "crown") {
                    transferring = TransferRequest()
                }
                Divider().overlay(Palette.border)
                ownershipRow(L("replace.short"), L("overview.replaceBody"), button: L("overview.replaceButton"), symbol: "arrow.triangle.swap") {
                    replacing = true
                }
            }
            .card(padding: 16)
        }
    }

    private func ownershipRow(_ title: String, _ body: String, button: String, symbol: String, action: @escaping () -> Void) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Palette.accent).frame(width: 28).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(body).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                }
                Button(button, action: action).buttonStyle(.kf(.secondary)).accessibilityLabel(title)
            }
            Spacer(minLength: 0)
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
                    Text(L("overview.leaveBody")).font(.subheadline).foregroundStyle(Palette.textMuted)
                    Button(L("overview.leaveButton"), role: .destructive) { leavingAsOwner = true }.buttonStyle(.kf(.dangerGhost))
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
