import KinframeKit
import SwiftUI

/// One plugin instance: its settings form (from the manifest), presets, corner and visibility, on/off, a waiting
/// registry update, and remove. While it's open the frame's report is refreshed every 3 s (status, entities, finds).
struct PluginEditorSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: PluginsModel
    @State private var confirmRemove = false
    @State private var presetAsk: FormPreset?

    var body: some View {
        NavigationStack {
            if let e = model.editor {
                let plugin = model.plugin(e.draft.pluginId)
                ScrollView {
                    content(e, plugin)
                        .padding(16)
                        .frame(maxWidth: 640)
                        .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                .pageBackground()
                .navigationTitle(plugin?.name(model.tag) ?? e.draft.pluginId)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { model.editor = nil } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button {
                            Task { await save() }
                        } label: {
                            if model.saving { ProgressView() } else { Text(L("common.save")) }
                        }
                        .fontWeight(.semibold)
                        .disabled(model.saving)
                    }
                }
                .confirmationDialog(L("plugins.removeConfirm", ["name": plugin?.name(model.tag) ?? e.draft.pluginId]),
                                    isPresented: $confirmRemove, titleVisibility: .visible) {
                    Button(L("plugins.remove"), role: .destructive) { Task { await remove() } }
                } message: {
                    Text(L("plugins.removeBody"))
                }
                .sheet(item: $presetAsk) { preset in
                    if let plugin {
                        PresetInputsSheet(plugin: plugin, preset: preset, draft: e.draft, tag: model.tag) { values in
                            model.update { d in
                                for (k, v) in values { d = PluginSettings.withValue(d, k, .string(v.trimmingCharacters(in: .whitespaces))) }
                                if let filled = plugin.applyPreset(preset.id, to: d.settings) { d.settings = filled }
                            }
                            app.toasts.show(L("plugins.presetApplied", ["name": plugin.presetLabel(preset, model.tag)]))
                        }
                    }
                }
                .task(id: e.draft.instanceId) {
                    // Status, entities and network finds while the form is open.
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .seconds(3))
                        guard !Task.isCancelled else { break }
                        await model.refreshReport()
                    }
                }
            }
        }
        .interactiveDismissDisabled(model.saving)
    }

    @ViewBuilder
    private func content(_ e: PluginsModel.Editor, _ plugin: FormPlugin?) -> some View {
        let d = e.draft
        let report = model.report?.instance(d.instanceId)
        let status: InstanceStatus? = e.isNew ? nil : PluginSettings.status(d, report: model.report, onFrame: plugin != nil)
        VStack(alignment: .leading, spacing: 18) {
            if plugin == nil { Callout(L("plugins.statusNotOnFrame"), kind: .warn) }
            if case .withdrawn(let reason)? = status {
                VStack(alignment: .leading, spacing: 8) {
                    Label(L("plugins.withdrawn", ["reason": reason]), systemImage: "exclamationmark.triangle.fill")
                        .font(.body.weight(.semibold)).foregroundStyle(Palette.danger)
                    Text(L("plugins.withdrawnHint")).font(.footnote).foregroundStyle(Palette.text)
                    Button(L("plugins.remove"), role: .destructive) { confirmRemove = true }.buttonStyle(.kf(.dangerGhost))
                }
                .padding(14)
                .background(Palette.dangerSoft, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            } else if !e.isNew, let u = model.pendingUpdate(d) {
                updateCard(u, plugin, d)
            }
            switch status {
            case .failed(let message)?: Callout(message, kind: .error)
            case .waiting?: Callout(L("plugins.statusWaiting"))
            default: EmptyView()
            }
            ForEach(Array(e.errors.filter { $0.key == nil }.enumerated()), id: \.offset) { _, err in
                Callout(errorText(err), kind: .error)
            }
            if let text = plugin?.description(model.tag) {
                Text(text).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            }

            if let plugin, !plugin.presets.isEmpty {
                Menu {
                    ForEach(plugin.presets, id: \.id) { p in
                        Button(plugin.presetLabel(p, model.tag)) { applyPreset(p, plugin) }
                    }
                } label: {
                    Label(L("plugins.fillInFor"), systemImage: "bolt.fill")
                }
                .buttonStyle(.kf(.secondary))
            }

            if let plugin, !plugin.settings.isEmpty {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(plugin.settings, id: \.key) { s in
                        PluginSettingField(plugin: plugin, setting: s, draft: d, tag: model.tag,
                                           error: e.errors.first { $0.key == s.key }.map(errorText),
                                           entities: report?.entities, services: s.discover.flatMap { model.report?.services[$0] } ?? []) { change in
                            model.update(change)
                        }
                    }
                }
                .card(padding: 16)
            }

            SectionHeader(L("ios.plugins.placement"))
            VStack(alignment: .leading, spacing: 12) {
                Text(L("plugins.corner")).font(.subheadline.weight(.semibold))
                ChipRow(options: (plugin?.slots ?? PluginSlot.all).map { ($0, slotLabel($0)) }, selected: d.slot) { slot in
                    model.update { $0.slot = slot }
                }
                Text(L("plugins.when")).font(.subheadline.weight(.semibold))
                ChipRow(options: [(PluginVisibility.always, L("plugins.visAlways")), (PluginVisibility.onChange, L("plugins.visOnChange")),
                                  (PluginVisibility.onTap, L("plugins.visOnTap"))], selected: d.visibility) { v in
                    model.update { $0.visibility = v }
                }
                if e.isNew {
                    Text(L("ios.plugins.newHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                } else {
                    Toggle(isOn: Binding(get: { d.enabled }, set: { on in model.update { $0.enabled = on } })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("plugins.showSwitch"))
                            Text(L("ios.plugins.enabledHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                    .tint(Palette.accent)
                }
            }
            .card(padding: 16)

            if let plugin, plugin.community || report?.version != nil {
                FlowLayout(spacing: 8) {
                    if plugin.community {
                        Pill(L("plugins.groupCommunity"))
                        if case let meta = plugin.communityMeta(withVersion: false), !meta.isEmpty {
                            Text(meta).font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                    if let v = report?.version {
                        Text(L("plugins.runsVersion", ["version": v])).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
            }
            if !e.isNew {
                Button(role: .destructive) {
                    confirmRemove = true
                } label: {
                    Label(L("plugins.remove"), systemImage: "trash")
                }
                .buttonStyle(.kf(.dangerGhost))
                .disabled(model.saving)
            }
        }
    }

    /// A registry update that asks for other permissions: what it asks for, Approve / Not now.
    private func updateCard(_ u: PendingUpdate, _ plugin: FormPlugin?, _ d: PluginInstanceDraft) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(u.fewer ? L("plugins.updateFewer", ["version": u.version]) : L("plugins.updateAsks", ["version": u.version]),
                  systemImage: "checkmark.shield")
                .font(.body.weight(.semibold)).foregroundStyle(Palette.text)
            if !u.fewer { PermissionList(items: u.asks, plugin: plugin, tag: model.tag) }
            HStack {
                Button(L("plugins.updateApprove")) {
                    Task {
                        do {
                            _ = try await model.approveUpdate(d.instanceId, u.version)
                            app.toasts.show(L("plugins.updateApproved", ["version": u.version]))
                        } catch {
                            app.toasts.error(error)
                        }
                    }
                }
                .buttonStyle(.kf(.primary))
                Button(L("plugins.updateDismiss")) { model.dismissUpdate(d.instanceId, u.version) }.buttonStyle(.kf(.ghost))
            }
        }
        .padding(14)
        .background(Palette.warnSoft, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
    }

    private func applyPreset(_ p: FormPreset, _ plugin: FormPlugin) {
        if p.inputs.isEmpty {
            model.update { d in if let s = plugin.applyPreset(p.id, to: d.settings) { d.settings = s } }
            app.toasts.show(L("plugins.presetApplied", ["name": plugin.presetLabel(p, model.tag)]))
        } else {
            presetAsk = p
        }
    }

    private func save() async {
        do {
            switch try await model.saveEditor() {
            case .saved(let name)?: app.toasts.show(L("plugins.saved", ["name": name]))
            case nil: app.toasts.show(L("ios.plugins.fixFields"), isError: true)
            default: break
            }
        } catch is PluginsModel.FieldsRejected {
            app.toasts.show(L("ios.plugins.fixFields"), isError: true)
        } catch {
            app.toasts.error(error)
        }
    }

    private func remove() async {
        do {
            if case .removed(let name)? = try await model.remove() {
                app.toasts.show(L("plugins.removed", ["name": name]))
            }
        } catch {
            app.toasts.error(error)
        }
    }
}

func errorText(_ e: InstanceFieldError) -> String {
    switch e.code {
    case InstanceFieldError.required: L("plugins.errRequired")
    case InstanceFieldError.type: L("plugins.errType")
    case InstanceFieldError.timezone: L("plugins.errTimezone")
    case "enum": e.field == "slot" ? L("ios.plugins.errSlot") : L("plugins.errEnum")
    case "unknown": L("plugins.errUnknown")
    case "secret": L("ios.plugins.errSecret")
    case "not_on_frame": L("ios.plugins.errNotOnFrame")
    case "duplicate": L("ios.plugins.errDuplicate")
    default: L("ios.plugins.errOther", ["code": e.code])
    }
}

/// A preset with `{placeholders}`: asks for the text settings it needs, then fills everything in.
private struct PresetInputsSheet: View {
    @Environment(\.dismiss) private var dismiss
    let plugin: FormPlugin
    let preset: FormPreset
    let draft: PluginInstanceDraft
    let tag: String
    let apply: ([String: String]) -> Void
    @State private var values: [String: String] = [:]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(preset.inputs, id: \.self) { k in
                        let s = plugin.setting(k)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(s.map { plugin.label($0, tag) } ?? k).font(.subheadline.weight(.semibold))
                            TextField(s.flatMap { plugin.placeholder($0, tag) } ?? "", text: Binding(get: { values[k] ?? "" }, set: { values[k] = $0 }))
                            if let help = s.flatMap({ plugin.help($0, tag) }) {
                                Text(help).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                        }
                    }
                } header: {
                    Text(L("plugins.presetNeeds"))
                }
            }
            .navigationTitle(plugin.presetLabel(preset, tag))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("plugins.presetApply")) {
                        apply(values)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(preset.inputs.contains { (values[$0] ?? "").trimmingCharacters(in: .whitespaces).isEmpty })
                }
            }
        }
        .presentationDetents([.medium])
        .onAppear {
            for k in preset.inputs { values[k] = PluginSettings.text(draft.settings[k]) }
        }
    }
}

// MARK: - Fields

/// One setting of the form, by type: switch, choice, secret, entity picker, time zone picker or a text field (text,
/// number, url, colour, duration) with network finds as suggestions for urls.
private struct PluginSettingField: View {
    let plugin: FormPlugin
    let setting: FormSetting
    let draft: PluginInstanceDraft
    let tag: String
    let error: String?
    let entities: [DiscoveredEntity]?
    let services: [DiscoveredService]
    let change: ((inout PluginInstanceDraft) -> Void) -> Void

    private var label: String { plugin.label(setting, tag) }
    private var help: String? { plugin.help(setting, tag) }
    private var value: JSONValue? { PluginSettings.displayValue(setting, draft) }

    private func set(_ v: JSONValue?) {
        let key = setting.key
        change { $0 = PluginSettings.withValue($0, key, v) }
    }

    var body: some View {
        switch setting.type {
        case SettingType.boolean:
            Toggle(isOn: Binding(get: { value?.bool ?? false }, set: { set(.bool($0)) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                    if let note = error ?? help { Text(note).font(.footnote).foregroundStyle(error != nil ? Palette.danger : Palette.textMuted) }
                }
            }
            .tint(Palette.accent)
        case SettingType.choice:
            VStack(alignment: .leading, spacing: 6) {
                FieldLabel(label: label, help: help, error: error)
                let current = value?.string
                if setting.options.count <= 4 {
                    ChipRow(options: setting.options.map { ($0, plugin.optionLabel(setting, $0, tag)) }, selected: current) { set(.string($0)) }
                } else {
                    Picker(label, selection: Binding(get: { current ?? "" }, set: { set($0.isEmpty ? nil : .string($0)) })) {
                        if current == nil { Text(L("plugins.fillInForPick")).tag("") }
                        ForEach(setting.options, id: \.self) { Text(plugin.optionLabel(setting, $0, tag)).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                }
            }
        case SettingType.secret:
            SecretField(key: setting.key, label: label, help: help, error: error, draft: draft, change: change)
        case SettingType.entity:
            EntityField(setting: setting, label: label, help: help, error: error, ids: PluginSettings.entityIds(value), entities: entities) { ids in
                set(PluginSettings.entityValue(setting, ids))
            }
        case SettingType.timezone:
            TimeZoneField(label: label, help: help, error: error, id: value?.string.flatMap { $0.isEmpty ? nil : $0 }) { id in
                set(id.map(JSONValue.string))
            }
        default:
            TextSettingField(setting: setting, label: label, placeholder: plugin.placeholder(setting, tag), help: help, error: error,
                             value: value, instanceId: draft.instanceId, services: services, set: set)
        }
    }
}

private struct FieldLabel: View {
    let label: String
    let help: String?
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
            if let help { Text(help).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true) }
            if let error { Text(error).font(.footnote).foregroundStyle(Palette.danger) }
        }
    }
}

/// text, number, url, colour, duration: keeps what's typed while the value it means stays the same.
private struct TextSettingField: View {
    let setting: FormSetting
    let label: String
    let placeholder: String?
    let help: String?
    let error: String?
    let value: JSONValue?
    let instanceId: String
    let services: [DiscoveredService]
    let set: (JSONValue?) -> Void
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            FieldLabel(label: label, help: help, error: error)
            HStack {
                TextField(placeholder ?? "", text: Binding(get: { text }, set: { t in
                    text = t
                    set(PluginSettings.parseText(setting, t))
                }))
                .keyboardType(setting.type == SettingType.number ? .decimalPad : setting.type == SettingType.url ? .URL : .default)
                .textInputAutocapitalization(setting.type == SettingType.text ? .sentences : .never)
                .autocorrectionDisabled(setting.type != SettingType.text)
                .accessibilityLabel(label)
                if setting.type == SettingType.color, let color = Self.color(text) {
                    Circle().fill(color).frame(width: 24, height: 24).overlay(Circle().strokeBorder(Palette.border))
                }
            }
            .padding(12)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(error != nil ? Palette.danger : Palette.borderStrong))
            if setting.type == SettingType.url, !services.isEmpty {
                Text(L("plugins.found")).font(.footnote).foregroundStyle(Palette.textMuted)
                ChipRow(options: services.map { s in
                    (s.url, "\(s.name) · \(s.url.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "").trimmingCharacters(in: CharacterSet(charactersIn: "/")))")
                }, selected: text.trimmingCharacters(in: .whitespaces)) { url in
                    text = url
                    set(.string(url))
                }
            }
        }
        .onAppear { text = PluginSettings.text(value) }
        .onChange(of: value) { _, v in
            if (PluginSettings.parseText(setting, text) ?? setting.defaultValue) != v { text = PluginSettings.text(v) }
        }
    }

    static func color(_ s: String) -> Color? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard t.count == 7, t.hasPrefix("#"), let n = UInt32(t.dropFirst(), radix: 16) else { return nil }
        return Color(red: Double((n >> 16) & 0xff) / 255, green: Double((n >> 8) & 0xff) / 255, blue: Double(n & 0xff) / 255)
    }
}

/// A token or password: "Set" with Change / Remove when the server has one; only what changes is sent.
private struct SecretField: View {
    let key: String
    let label: String
    let help: String?
    let error: String?
    let draft: PluginInstanceDraft
    let change: ((inout PluginInstanceDraft) -> Void) -> Void
    @State private var changing = false

    var body: some View {
        let stored = draft.secretsSet.contains(key)
        let writing = draft.secretWrites.keys.contains(key)
        let written = draft.secretWrites[key] ?? nil
        VStack(alignment: .leading, spacing: 6) {
            FieldLabel(label: label, help: help, error: error)
            if stored, !writing, !changing {
                HStack(spacing: 8) {
                    Label(L("plugins.secretSet"), systemImage: "lock.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Palette.accentText)
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(Capsule().fill(Palette.accentSoft))
                    Button(L("plugins.secretChange")) { changing = true }.buttonStyle(.kf(.ghost))
                    Button(L("plugins.secretRemove")) { write(nil) }.buttonStyle(.kf(.dangerGhost))
                }
            } else if stored, writing, written == nil {
                HStack {
                    Text(L("plugins.secretRemoving")).font(.footnote).foregroundStyle(Palette.danger)
                    Button(L("common.undo")) { untouched() }.buttonStyle(.kf(.ghost))
                }
            } else {
                SecureField(label, text: Binding(get: { written ?? "" }, set: { v in v.isEmpty ? untouched() : write(v) }))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(error != nil ? Palette.danger : Palette.borderStrong))
                if stored {
                    Button(L("common.cancel")) { changing = false; untouched() }.buttonStyle(.kf(.ghost))
                }
            }
        }
    }

    private func write(_ v: String?) {
        let key = key
        change { $0.secretWrites[key] = .some(v) }
    }

    private func untouched() {
        let key = key
        change { $0.secretWrites.removeValue(forKey: key) }
    }
}

/// Home Assistant entities the frame found, searchable; typing an entity id by hand always works.
private struct EntityField: View {
    let setting: FormSetting
    let label: String
    let help: String?
    let error: String?
    let ids: [String]
    let entities: [DiscoveredEntity]?
    let pick: ([String]) -> Void
    @State private var query = ""

    var body: some View {
        let all = entities ?? []
        let q = query.trimmingCharacters(in: .whitespaces)
        VStack(alignment: .leading, spacing: 6) {
            FieldLabel(label: label, help: help, error: error)
            ForEach(ids, id: \.self) { id in
                let e = all.first { $0.entityId == id }
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(e?.name ?? id).font(.subheadline.weight(.semibold)).lineLimit(1)
                        if e?.name != nil { Text(id).font(.caption).lineLimit(1) }
                    }
                    Spacer()
                    Button {
                        pick(ids.filter { $0 != id })
                    } label: {
                        Image(systemName: "xmark").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("plugins.entityRemove", ["name": e?.name ?? id]))
                }
                .foregroundStyle(Palette.primarySoftText)
                .padding(.leading, 12)
                .background(Palette.primarySoft, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            }
            TextField(L("plugins.entitySearch"), text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(12)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.borderStrong))
            if all.isEmpty {
                Text(L("plugins.entityNone")).font(.footnote).foregroundStyle(Palette.textMuted)
            } else if !q.isEmpty || (ids.isEmpty && query.isEmpty) {
                let matches = Array(FormPlugin.entities(all, for: setting, query: q).filter { !ids.contains($0.entityId) }.prefix(q.isEmpty ? 5 : 12))
                ForEach(matches, id: \.entityId) { e in
                    Button {
                        add(e.entityId)
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text([e.name ?? e.entityId, e.unit].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(Palette.text).lineLimit(1)
                            Text(e.entityId).font(.caption).foregroundStyle(Palette.textMuted).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if !q.isEmpty, matches.isEmpty, !q.contains(".") {
                    Text(L("plugins.entityNoMatch")).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            if q.contains("."), !ids.contains(q), !all.contains(where: { $0.entityId == q }) {
                Button(L("plugins.entityAdd", ["id": q])) { add(q) }.buttonStyle(.kf(.ghost))
            }
        }
    }

    private func add(_ id: String) {
        pick(setting.multiple ? ids + [id] : [id])
        query = ""
    }
}

/// A time zone: the chosen one as its city with region and UTC offset, and a search by city, region or id.
private struct TimeZoneField: View {
    let label: String
    let help: String?
    let error: String?
    let id: String?
    let pick: (String?) -> Void
    @State private var query = ""
    @State private var options: [TimeZoneOption] = []

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        let matches: [TimeZoneOption] = if !q.isEmpty {
            Array(TimeZones.search(options, q).prefix(12))
        } else if id == nil {
            // Nothing chosen yet: offer this phone's own zone.
            options.filter { $0.id == TimeZone.current.identifier }
        } else {
            []
        }
        VStack(alignment: .leading, spacing: 6) {
            FieldLabel(label: label, help: help, error: error)
            if let id {
                let o = options.first { $0.id == id } ?? TimeZones.option(id)
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(o.city).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(o.offsetSeconds == nil ? o.id : o.detail).font(.caption).lineLimit(1)
                    }
                    Spacer()
                    Button {
                        pick(nil)
                    } label: {
                        Image(systemName: "xmark").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("ios.plugins.timezoneClear", ["name": o.city]))
                }
                .foregroundStyle(Palette.primarySoftText)
                .padding(.leading, 12)
                .background(Palette.primarySoft, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            }
            TextField(L("plugins.tzSearch"), text: $query)
                .autocorrectionDisabled()
                .padding(12)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.borderStrong))
            ForEach(matches.filter { $0.id != id }) { o in
                Button {
                    pick(o.id)
                    query = ""
                } label: {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(o.city).foregroundStyle(Palette.text)
                        Text(o.detail).font(.caption).foregroundStyle(Palette.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if !q.isEmpty, matches.isEmpty {
                Text(L("plugins.tzNoMatch")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .task { if options.isEmpty { options = TimeZones.options() } }
    }
}
