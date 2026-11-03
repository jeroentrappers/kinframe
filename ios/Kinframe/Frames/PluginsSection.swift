import KinframeKit
import SwiftUI

/// The Plugins section of the frame settings: where plugins show on the frame (off, corners, band, split), the
/// frame's plugin instances with on/off switches, and the catalog and editor sheets. Saved on its own, change by
/// change, apart from the settings form's save bar.
struct PluginsSection: View {
    @Environment(AppModel.self) private var app
    @State private var model: PluginsModel

    init(api: API, frame: FrameSummary) {
        _model = State(initialValue: PluginsModel(api: api, frameId: frame.id))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("plugins.title"), trailing: model.canAdd ? AnyView(
                Button {
                    model.adding = true
                } label: {
                    Label(L("plugins.add"), systemImage: "plus")
                }
                .buttonStyle(.kf(.soft))
            ) : nil)
            Text(L("plugins.hint")).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            if model.report == nil {
                if let error = model.loadError {
                    ErrorRetryView(message: error) { Task { await model.load() } }
                } else {
                    LoadingView().frame(minHeight: 120)
                }
            } else {
                if model.needsFrameUpdate {
                    Callout(L("plugins.updateApp") + ". " + L("plugins.updateAppBody"), kind: .warn)
                }
                layoutCard
                instancesCard
                if model.instances.count >= PluginSettings.maxInstances {
                    Text(L("plugins.max", ["n": PluginSettings.maxInstances])).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
        }
        .task {
            await model.load()
            #if DEBUG
            if DebugLaunch.takeSheet("pluginAdd") { model.adding = true }
            if DebugLaunch.takeSheet("pluginEdit"), let first = model.instances.first { model.edit(first.instanceId) }
            #endif
        }
        .sheet(isPresented: $model.adding) {
            PluginCatalogSheet(catalog: model.catalog, tag: model.tag) { model.add($0) }
        }
        .sheet(item: $model.confirming) { plugin in
            ConfirmAddSheet(plugin: plugin, tag: model.tag, add: { model.confirmAdd() }, cancel: { model.confirming = nil })
        }
        .sheet(isPresented: Binding(get: { model.editor != nil }, set: { if !$0 { model.editor = nil } })) {
            PluginEditorSheet(model: model)
        }
    }

    // MARK: Layout

    private var layoutCard: some View {
        let layout = model.layout
        return VStack(alignment: .leading, spacing: 12) {
            Text(L("plugins.layout")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
            ChipRow(options: [(PluginLayout.off, L("plugins.layoutOff")), (PluginLayout.overlay, L("plugins.layoutOverlay")),
                              (PluginLayout.band, L("plugins.layoutBand")), (PluginLayout.split, L("plugins.layoutSplit"))],
                    selected: layout.mode) { set("mode", $0) }
            Text(layoutHint(layout.mode)).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            if layout.mode == PluginLayout.split || layout.mode == PluginLayout.band {
                Text(layout.mode == PluginLayout.split ? L("plugins.splitSide") : L("plugins.bandSide"))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                ChipRow(options: [(PluginLayout.left, L("plugins.sideLeft")), (PluginLayout.right, L("plugins.sideRight"))],
                        selected: layout.side) { set("side", $0) }
            }
            if layout.mode == PluginLayout.band {
                Text(L("plugins.bandBackground")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                ChipRow(options: [(PluginLayout.paper, L("plugins.bgPaper")), (PluginLayout.photo, L("plugins.bgPhoto"))],
                        selected: layout.background) { set("background", $0) }
            }
        }
        .disabled(model.saving)
        .card(padding: 16)
    }

    private func layoutHint(_ mode: String) -> String {
        switch mode {
        case PluginLayout.overlay: L("plugins.layoutOverlayHint")
        case PluginLayout.band: L("plugins.layoutBandHint")
        case PluginLayout.split: L("plugins.layoutSplitHint")
        default: L("plugins.layoutOffHint")
        }
    }

    private func set(_ field: String, _ value: String) {
        Task {
            do {
                try await model.setLayout(field, value)
            } catch {
                app.toasts.error(error)
            }
        }
    }

    // MARK: Instances

    private var instancesCard: some View {
        VStack(spacing: 0) {
            if model.instances.isEmpty {
                Text(L("plugins.none")).foregroundStyle(Palette.textMuted).frame(maxWidth: .infinity, alignment: .leading).padding(16)
            }
            ForEach(Array(model.instances.enumerated()), id: \.element.id) { n, d in
                if n > 0 { Divider().overlay(Palette.border).padding(.leading, 16) }
                instanceRow(d)
            }
        }
        .card(padding: 0)
    }

    private func instanceRow(_ d: PluginInstanceDraft) -> some View {
        let plugin = model.plugin(d.pluginId)
        let name = plugin?.name(model.tag) ?? d.pluginId
        let status = PluginSettings.status(d, report: model.report, onFrame: plugin != nil)
        let update = model.pendingUpdate(d)
        return HStack(spacing: 12) {
            Button {
                model.edit(d.instanceId)
            } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text(name).font(.body.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
                    Text(slotLabel(d.slot)).font(.footnote).foregroundStyle(Palette.textMuted)
                    statusLine(status)
                    if update != nil, !isWithdrawn(status) {
                        Label(L("plugins.updateWaiting"), systemImage: "checkmark.shield")
                            .font(.footnote).foregroundStyle(Palette.primary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("plugins.edit", ["name": name]))
            Toggle(L("plugins.showOnFrame", ["name": name]), isOn: Binding(get: { d.enabled }, set: { on in
                Task {
                    do {
                        _ = try await model.setEnabled(d.instanceId, on)
                    } catch {
                        app.toasts.error(error)
                    }
                }
            }))
            .labelsHidden()
            .tint(Palette.accent)
            .disabled(model.saving)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 60)
    }

    @ViewBuilder
    private func statusLine(_ status: InstanceStatus) -> some View {
        switch status {
        case .failed(let message): Text(message).font(.footnote).foregroundStyle(Palette.danger).lineLimit(2)
        case .waiting: Text(L("plugins.statusWaiting")).font(.footnote).foregroundStyle(Palette.textFaint)
        case .notOnFrame: Text(L("plugins.statusNotOnFrame")).font(.footnote).foregroundStyle(Palette.warn)
        case .withdrawn(let reason): Text(L("plugins.withdrawn", ["reason": reason])).font(.footnote).foregroundStyle(Palette.danger).lineLimit(2)
        case .off, .running: EmptyView()
        }
    }

    private func isWithdrawn(_ s: InstanceStatus) -> Bool {
        if case .withdrawn = s { return true }
        return false
    }
}

func slotLabel(_ slot: String) -> String {
    slot == PluginSlot.topLeft ? L("plugins.slotTopLeft") : L("plugins.slotTopRight")
}

extension FormPlugin {
    /// "by Jane Doe · MIT · version 1.2.0" for a community plugin.
    func communityMeta(withVersion: Bool = true) -> String {
        [author.map { L("plugins.byAuthor", ["name": $0]) }, license,
         withVersion ? version.flatMap { $0.isEmpty ? nil : L("plugins.versionNumber", ["version": $0]) } : nil]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// A wrapping row of choice chips (one selected).
struct ChipRow: View {
    let options: [(value: String, label: String)]
    let selected: String?
    let pick: (String) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(options, id: \.value) { o in
                let on = o.value == selected
                Button {
                    if !on { pick(o.value) }
                    UISelectionFeedbackGenerator().selectionChanged()
                } label: {
                    Text(o.label)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(on ? Palette.onPrimary : Palette.text)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 40)
                        .background(Capsule().fill(on ? Palette.primary : Palette.surface2))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}

// MARK: - Catalog

/// "Add plugin": the plugins this frame can run, built-in first, then the community's.
struct PluginCatalogSheet: View {
    @Environment(\.dismiss) private var dismiss
    let catalog: [FormPlugin]
    let tag: String
    let pick: (FormPlugin) -> Void

    var body: some View {
        let sorted = catalog.sorted { $0.name(tag).localizedStandardCompare($1.name(tag)) == .orderedAscending }
        let builtin = sorted.filter { !$0.community }, community = sorted.filter(\.community)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if catalog.isEmpty {
                        Text(L("plugins.catalogEmpty")).foregroundStyle(Palette.textMuted)
                    }
                    if !community.isEmpty, !builtin.isEmpty { groupTitle(L("plugins.groupBuiltin"), icon: nil) }
                    ForEach(builtin) { entry($0) }
                    if !community.isEmpty {
                        groupTitle(L("plugins.groupCommunity"), icon: "person.2")
                        Text(L("plugins.groupCommunityHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        ForEach(community) { entry($0) }
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(L("plugins.add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func groupTitle(_ text: String, icon: String?) -> some View {
        HStack(spacing: 6) {
            if let icon { Image(systemName: icon).foregroundStyle(Palette.textMuted) }
            Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
        }
        .padding(.top, 8)
        .accessibilityAddTraits(.isHeader)
    }

    private func entry(_ p: FormPlugin) -> some View {
        Button {
            pick(p)
        } label: {
            VStack(alignment: .leading, spacing: 5) {
                Text(p.name(tag)).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                if p.community, case let meta = p.communityMeta(), !meta.isEmpty {
                    Text(meta).font(.caption).foregroundStyle(Palette.textFaint)
                }
                if let d = p.description(tag) {
                    Text(d).font(.footnote).foregroundStyle(Palette.textMuted).multilineTextAlignment(.leading)
                }
                let hosts = p.hosts.map { $0.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "") }
                Label(hosts.isEmpty ? L("plugins.ownAddressesOnly") : L("plugins.reachesInternet", ["hosts": hosts.joined(separator: ", ")]),
                      systemImage: hosts.isEmpty ? "house" : "globe")
                    .font(.footnote).foregroundStyle(Palette.textMuted)
                if p.location {
                    Label(L("plugins.usesLocation"), systemImage: "mappin.and.ellipse").font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.border))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("plugins.addNamed", ["name": p.name(tag)]))
    }
}

/// What a plugin may do, one line each; `plugin` names the address settings by their form labels.
struct PermissionList: View {
    let items: [PluginPermission]
    let plugin: FormPlugin?
    let tag: String
    var showNone = true

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, p in
                switch p {
                case .internet(let hosts): line("globe", L("plugins.reachesInternet", ["hosts": hosts.joined(separator: ", ")]))
                case .addresses(let keys):
                    line("wifi", L("plugins.permAddresses", ["settings": keys.map { k in plugin?.setting(k).map { plugin!.label($0, tag) } ?? k }.joined(separator: ", ")]))
                case .location: line("mappin.and.ellipse", L("plugins.usesLocation"))
                case .actions: line("power", L("plugins.permActions"))
                }
            }
            if items.isEmpty, showNone { line("checkmark", L("plugins.permNone")) }
        }
    }

    private func line(_ icon: String, _ text: String) -> some View {
        Label {
            Text(text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: icon).foregroundStyle(Palette.textMuted)
        }
        .font(.footnote)
        .foregroundStyle(Palette.text)
    }
}

/// Before a community plugin is added: who made it and exactly what it may do.
struct ConfirmAddSheet: View {
    let plugin: FormPlugin
    let tag: String
    let add: () -> Void
    let cancel: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if case let meta = plugin.communityMeta(), !meta.isEmpty {
                        Text(meta).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                    Text(L("plugins.confirmBody")).foregroundStyle(Palette.text)
                    PermissionList(items: plugin.permissions.items, plugin: plugin, tag: tag)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .pageBackground()
            .navigationTitle(L("plugins.confirmTitle", ["name": plugin.name(tag)]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel"), action: cancel) }
                ToolbarItem(placement: .confirmationAction) { Button(L("plugins.addOne"), action: add).fontWeight(.semibold) }
            }
        }
        .presentationDetents([.medium])
    }
}
