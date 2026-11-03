import KinframeKit
import OpenAPIRuntime
import SwiftUI

/// The frame settings' Plugins section (docs/plugins.md §6, §6.1, §7; web: PluginsSection). Every change is saved at
/// once as a merge patch with `If-Match`: a layout field, or the whole `instances` array. A `412` reloads the settings
/// and applies the same change to the newer list once more.
@MainActor
@Observable
final class PluginsModel {
    /// The instance being edited; `errors` come from the client check or the server's 422 and show at their fields.
    struct Editor {
        var draft: PluginInstanceDraft
        var isNew: Bool
        var errors: [InstanceFieldError] = []
    }

    enum Outcome {
        case saved(String), removed(String), updateApproved(String)
    }

    let api: API
    let frameId: String
    private(set) var doc: [String: JSONValue]?
    private(set) var report: PluginReport?
    var loadError: String?
    var editor: Editor?
    var adding = false
    /// A community plugin whose permissions are shown before it is added.
    var confirming: FormPlugin?
    private(set) var saving = false
    /// Updates put off for now ("Not now"), as `<instance id>@<version>`; not kept.
    private(set) var dismissedUpdates: Set<String> = []

    init(api: API, frameId: String) {
        self.api = api
        self.frameId = frameId
    }

    var layout: PluginLayout { PluginSettings.layout(doc ?? [:]) }
    var instances: [PluginInstanceDraft] { PluginSettings.instances(doc ?? [:]) }
    var catalog: [FormPlugin] { report?.catalog ?? [] }
    /// The frame app reports no catalog yet: "Update the frame app".
    var needsFrameUpdate: Bool { report.map { !$0.catalogReported } ?? false }
    var canAdd: Bool { report?.catalogReported == true && instances.count < PluginSettings.maxInstances && !saving }
    var tag: String { L10n.code }

    func plugin(_ id: String) -> FormPlugin? { report?.plugin(id) }

    func pendingUpdate(_ d: PluginInstanceDraft) -> PendingUpdate? {
        guard let u = PluginSettings.pendingUpdate(plugin(d.pluginId), d, report?.instance(d.instanceId)),
              !dismissedUpdates.contains("\(d.instanceId)@\(u.version)") else { return nil }
        return u
    }

    // MARK: Loading

    func load() async {
        do {
            doc = try await fetchSettings()
            loadError = nil
        } catch is CancellationError {
            return
        } catch {
            if doc == nil { loadError = errorMessage(error) }
            return
        }
        await refreshReport()
    }

    func refreshReport() async {
        let id = frameId
        do {
            let plugins = try await api.call { try await $0.getFramePlugins(path: .init(frameId: id)).ok.body.json }
            report = PluginReport.read(try JSONValue.from(plugins))
        } catch is CancellationError {
        } catch {
            if report == nil { loadError = errorMessage(error) }
        }
    }

    private func fetchSettings() async throws -> [String: JSONValue] {
        let id = frameId
        let settings = try await api.call { try await $0.getFrameSettings(path: .init(frameId: id)).ok.body.json }
        return try JSONValue.from(settings).object ?? [:]
    }

    // MARK: Layout and list

    func setLayout(_ field: String, _ value: String) async throws {
        try await save { _ in (PluginSettings.layoutPatch(field, value), []) }
    }

    /// Switch an instance on or off. An incomplete one opens its form with what's missing instead; returns false then.
    func setEnabled(_ instanceId: String, _ on: Bool) async throws -> Bool {
        guard var d = instances.first(where: { $0.instanceId == instanceId }) else { return false }
        if on, let p = plugin(d.pluginId), !PluginSettings.complete(p, d) {
            d.enabled = true
            open(Editor(draft: d, isNew: false, errors: PluginSettings.check(p, d)))
            return false
        }
        try await saveInstances { list in list.map { var i = $0; if i.instanceId == instanceId { i.enabled = on }; return i } }
        return true
    }

    /// Picks a catalog plugin: a community plugin first shows what it may do (`confirmAdd`).
    func add(_ plugin: FormPlugin) {
        adding = false
        if plugin.community { confirming = plugin } else { addNow(plugin) }
    }

    func confirmAdd() {
        guard let p = confirming else { return }
        confirming = nil
        addNow(p)
    }

    private func addNow(_ plugin: FormPlugin) {
        guard instances.count < PluginSettings.maxInstances else { return }
        open(Editor(draft: PluginSettings.newInstance(plugin, taken: instances.map(\.instanceId)), isNew: true))
    }

    func edit(_ instanceId: String) {
        guard let d = instances.first(where: { $0.instanceId == instanceId }) else { return }
        open(Editor(draft: d, isNew: false))
    }

    private func open(_ e: Editor) {
        editor = e
    }

    // MARK: Editor

    /// Edits the open instance; errors of the fields that changed are cleared.
    func update(_ change: (inout PluginInstanceDraft) -> Void) {
        guard var e = editor else { return }
        var next = e.draft
        change(&next)
        let keys = Set(next.settings.keys).union(e.draft.settings.keys).filter { next.settings[$0] != e.draft.settings[$0] }
            .union(Set(next.secretWrites.keys).union(e.draft.secretWrites.keys).filter { next.secretWrites[$0] != e.draft.secretWrites[$0] })
        let slotChanged = next.slot != e.draft.slot
        e.errors.removeAll { ($0.key.map(keys.contains) ?? false) || (slotChanged && $0.field == "slot") }
        e.draft = next
        editor = e
    }

    /// Saves the open instance. Returns nil when the form still has errors (shown at the fields).
    func saveEditor() async throws -> Outcome? {
        guard var e = editor, !saving else { return nil }
        let name = plugin(e.draft.pluginId)?.name(tag) ?? e.draft.pluginId
        if let p = plugin(e.draft.pluginId) {
            // A new instance is switched on once its form is complete.
            if e.isNew { e.draft.enabled = PluginSettings.complete(p, e.draft) }
            let errors = PluginSettings.check(p, e.draft)
            if !errors.isEmpty {
                e.errors = errors
                editor = e
                return nil
            }
        }
        let saved = e.draft
        try await saveInstances { list in
            list.contains { $0.instanceId == saved.instanceId } ? list.map { $0.instanceId == saved.instanceId ? saved : $0 } : list + [saved]
        }
        editor = nil
        return .saved(name)
    }

    func remove() async throws -> Outcome? {
        guard let d = editor?.draft else { return nil }
        let name = plugin(d.pluginId)?.name(tag) ?? d.pluginId
        try await saveInstances { list in list.filter { $0.instanceId != d.instanceId } }
        editor = nil
        return .removed(name)
    }

    /// Approves update `version` of `instanceId`; an open editor follows, so saving it afterwards keeps the approval.
    func approveUpdate(_ instanceId: String, _ version: String) async throws -> Outcome {
        try await saveInstances { PluginSettings.approveUpdate($0, instanceId: instanceId, version: version) }
        if editor?.draft.instanceId == instanceId { editor?.draft.version = version }
        return .updateApproved(version)
    }

    func dismissUpdate(_ instanceId: String, _ version: String) {
        dismissedUpdates.insert("\(instanceId)@\(version)")
    }

    // MARK: Saving

    /// Thrown when a 422 named fields of the open editor: they're marked, the caller says "check the marked fields".
    struct FieldsRejected: Error {}

    private func saveInstances(_ change: @escaping ([PluginInstanceDraft]) -> [PluginInstanceDraft]) async throws {
        var sent: [PluginInstanceDraft] = []
        do {
            try await save { doc in
                let list = change(PluginSettings.instances(doc))
                sent = list
                return (PluginSettings.instancesPatch(list), list)
            }
        } catch let e as APIError where !e.fieldErrors.isEmpty {
            if let editor, let mine = PluginSettings.fieldErrors(e.fieldErrors.map { ($0.field, $0.code) }, sent: sent)[editor.draft.instanceId],
               !mine.isEmpty {
                self.editor?.errors = mine
                throw FieldsRejected()
            }
            throw e
        }
    }

    /// Sends `build`'s patch on the current version; on `412` reloads and builds it again on the newer document once.
    private func save(_ build: ([String: JSONValue]) -> ([String: JSONValue], [PluginInstanceDraft])) async throws {
        guard let current = doc, !saving else { return }
        saving = true
        defer { saving = false }
        do {
            doc = try await send(build(current).0, version: Self.version(current))
        } catch let e as APIError where e.status == 412 || e.code == "version_conflict" {
            let latest = try await fetchSettings()
            doc = latest
            doc = try await send(build(latest).0, version: Self.version(latest))
        }
    }

    private static func version(_ doc: [String: JSONValue]) -> Int {
        if case .number(let n)? = doc["version"] { return Int(n) }
        return 0
    }

    private func send(_ patch: [String: JSONValue], version: Int) async throws -> [String: JSONValue] {
        let id = frameId
        let body = try JSONDecoder().decode(OpenAPIObjectContainer.self, from: JSONEncoder().encode(JSONValue.object(patch)))
        let updated = try await api.call {
            try await $0.updateFrameSettings(path: .init(frameId: id), headers: .init(ifMatch: "\"\(version)\""), body: .applicationMergePatchJson(body)).ok.body.json
        }
        return try JSONValue.from(updated).object ?? [:]
    }
}
