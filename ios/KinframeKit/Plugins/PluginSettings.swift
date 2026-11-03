import Foundation

// The Plugins section of the frame settings (docs/plugins.md §6, §6.1, §7): reading the frame's catalog, editing
// plugin instances and the layout, and the same checks the server makes. Mirrors the web and Android apps.

/// Setting types of a manifest (docs/plugins.md §3.1).
public enum SettingType {
    public static let text = "text"
    public static let number = "number"
    public static let boolean = "boolean"
    public static let choice = "choice"
    public static let url = "url"
    public static let secret = "secret"
    public static let entity = "entity"
    public static let color = "color"
    public static let duration = "duration"
    /// An IANA time zone id, "Asia/Tokyo".
    public static let timezone = "timezone"
}

public enum PluginSlot {
    public static let topLeft = "top-left"
    public static let topRight = "top-right"
    public static let all = [topLeft, topRight]
}

public enum PluginVisibility {
    public static let always = "always"
    public static let onChange = "on_change"
    public static let onTap = "on_tap"
    public static let all = [always, onChange, onTap]
}

/// One field of the settings form, read from a manifest setting.
public struct FormSetting: Equatable, Sendable {
    public var key: String
    public var type: String
    public var label: String
    public var required = false
    public var defaultValue: JSONValue?
    public var placeholder: String?
    public var help: String?
    public var options: [String] = []
    /// mDNS service type whose finds the form offers as suggestions (`url` settings).
    public var discover: String?
    /// Entity filters: the part of the entity id before the dot, and the unit.
    public var domain: String?
    public var unit: String?
    public var multiple = false

    /// Required for an enabled instance: `required` without a `default` (the server's rule).
    public var mustFill: Bool { required && (defaultValue == nil || defaultValue == .null) }
}

/// A named group of setting values; string values may hold `{key}` placeholders for text settings (`inputs`).
public struct FormPreset: Equatable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var settings: [String: JSONValue]

    static var placeholder: Regex<(Substring, Substring)> { /\{([a-z][a-z0-9_]{0,31})\}/ }

    /// The text settings the preset's placeholders need, in order of appearance.
    public var inputs: [String] {
        var out: [String] = []
        func scan(_ v: JSONValue) {
            switch v {
            case .string(let s):
                for m in s.matches(of: Self.placeholder) where !out.contains(String(m.1)) { out.append(String(m.1)) }
            case .array(let a): a.forEach(scan)
            default: break
            }
        }
        settings.keys.sorted().forEach { scan(settings[$0]!) }
        return out
    }
}

/// One thing a plugin may do, as the owner reads it before adding a community plugin.
public enum PluginPermission: Equatable, Sendable {
    /// Fixed internet hosts it reaches.
    case internet([String])
    /// The addresses the owner types in: these settings' keys.
    case addresses([String])
    /// The frame's (coarse) place.
    case location
    /// Buttons on the frame that change things.
    case actions
}

/// A manifest's `permissions`: hosts (from `http` and `websocket` origins), address settings, location, buttons.
public struct PluginPermissions: Equatable, Sendable {
    public var hosts: [String] = []
    public var addresses: [String] = []
    public var location = false
    public var actions = false

    public init(hosts: [String] = [], addresses: [String] = [], location: Bool = false, actions: Bool = false) {
        self.hosts = hosts
        self.addresses = addresses
        self.location = location
        self.actions = actions
    }

    /// What it allows, in the order the apps list it.
    public var items: [PluginPermission] {
        var out: [PluginPermission] = []
        if !hosts.isEmpty { out.append(.internet(hosts)) }
        if !addresses.isEmpty { out.append(.addresses(addresses)) }
        if location { out.append(.location) }
        if actions { out.append(.actions) }
        return out
    }

    /// What this allows that `other` doesn't.
    public func minus(_ other: PluginPermissions) -> PluginPermissions {
        PluginPermissions(hosts: hosts.filter { !other.hosts.contains($0) }, addresses: addresses.filter { !other.addresses.contains($0) },
                          location: location && !other.location, actions: actions && !other.actions)
    }

    public static func read(_ o: JSONValue?) -> PluginPermissions {
        guard let o = o?.object else { return PluginPermissions() }
        var entries: [String] = []
        for k in ["http", "websocket"] { entries += o[k]?.strings ?? [] }
        func unique(_ a: [String]) -> [String] { a.reduce(into: []) { if !$0.contains($1) { $0.append($1) } } }
        return PluginPermissions(
            hosts: unique(entries.filter { !$0.hasPrefix("settings.") }.map(host)),
            addresses: unique(entries.filter { $0.hasPrefix("settings.") }.map { String($0.dropFirst("settings.".count)) }.filter { !$0.isEmpty }),
            location: o["location"]?.bool ?? false,
            actions: o["actions"]?.bool ?? false
        )
    }

    /// "api.example.org" for "https://api.example.org/v1"; as written when it isn't an address.
    static func host(_ origin: String) -> String {
        guard let u = URLComponents(string: origin), let h = u.host, !h.isEmpty else { return origin }
        return u.port.map { "\(h):\($0)" } ?? h
    }
}

/// A catalog plugin as the settings form needs it. The reader is lenient: the frame validated the manifest before it
/// reported it, and checks again before it runs anything.
public struct FormPlugin: Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var version: String?
    public var description: String?
    public var slots: [String] = PluginSlot.all
    public var defaultVisibility = PluginVisibility.always
    public var settings: [FormSetting] = []
    public var presets: [FormPreset] = []
    /// Fixed internet origins from `permissions.http`: shown in the catalog.
    public var hosts: [String] = []
    public var location = false
    public var builtin = true
    public var author: String?
    public var license: String?
    public var permissions = PluginPermissions()
    /// Language tag → `@name`, `@setting.<key>` …
    public var locales: [String: [String: String]] = [:]

    /// A plugin from the community registry: it says what it may do before it is added.
    public var community: Bool { !builtin }

    public func setting(_ key: String) -> FormSetting? { settings.first { $0.key == key } }
    public func preset(_ id: String) -> FormPreset? { presets.first { $0.id == id } }

    /// The translations for `tag`: English, then the language, then the exact tag (as the frame resolves them).
    public func strings(_ tag: String) -> [String: String] {
        let lang = String(tag.split(separator: "-").first ?? "").lowercased()
        let exact = locales[tag] ?? locales.first { $0.key.caseInsensitiveCompare(tag) == .orderedSame }?.value ?? [:]
        return (locales["en"] ?? [:]).merging(locales[lang] ?? [:]) { $1 }.merging(exact) { $1 }
    }

    private func t(_ tag: String, _ key: String) -> String? {
        guard let s = strings(tag)[key], !s.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return s
    }

    public func name(_ tag: String) -> String { t(tag, "@name") ?? name }
    public func description(_ tag: String) -> String? { t(tag, "@description") ?? description }
    public func label(_ s: FormSetting, _ tag: String) -> String { t(tag, "@setting.\(s.key)") ?? s.label }
    public func help(_ s: FormSetting, _ tag: String) -> String? { t(tag, "@setting.\(s.key).help") ?? s.help }
    public func placeholder(_ s: FormSetting, _ tag: String) -> String? { t(tag, "@setting.\(s.key).placeholder") ?? s.placeholder }
    public func optionLabel(_ s: FormSetting, _ value: String, _ tag: String) -> String { t(tag, "@option.\(s.key).\(value)") ?? value }
    public func presetLabel(_ p: FormPreset, _ tag: String) -> String { t(tag, "@preset.\(p.id)") ?? p.label }

    /// The corner a new instance gets: top-right when the plugin allows it.
    public var defaultSlot: String { slots.contains(PluginSlot.topRight) || slots.isEmpty ? PluginSlot.topRight : slots[0] }

    /// `values` with preset `presetId` applied: its settings set (overwriting), `{placeholders}` filled from `values`.
    /// Nil when the preset is unknown or a placeholder's text setting is still empty (the form asks for it first).
    public func applyPreset(_ presetId: String, to values: [String: JSONValue]) -> [String: JSONValue]? {
        guard let p = preset(presetId) else { return nil }
        var inputs: [String: String] = [:]
        for k in p.inputs {
            let v = values[k]?.string?.trimmingCharacters(in: .whitespaces) ?? ""
            if v.isEmpty { return nil }
            inputs[k] = v
        }
        func fill(_ s: String) -> String {
            s.replacing(FormPreset.placeholder) { m in inputs[String(m.1)] ?? String(m.0) }
        }
        var out = values
        for (k, v) in p.settings {
            switch v {
            case .string(let s): out[k] = .string(fill(s))
            case .array(let a): out[k] = .array(a.map { if case .string(let s) = $0 { .string(fill(s)) } else { $0 } })
            default: out[k] = v
            }
        }
        return out
    }

    /// Reads a catalog entry (`{manifest, locales, builtin}`); nil when the manifest has no id.
    public static func read(_ entry: JSONValue) -> FormPlugin? {
        guard let m = entry["manifest"]?.object, let id = m["id"]?.string, !id.isEmpty else { return nil }
        let settings: [FormSetting] = (m["settings"]?.array ?? []).compactMap { e in
            guard let s = e.object, let key = s["key"]?.string else { return nil }
            var f = FormSetting(key: key, type: s["type"]?.string ?? SettingType.text, label: s["label"]?.string ?? key)
            f.required = s["required"]?.bool ?? false
            f.defaultValue = s["default"].flatMap { $0 == .null ? nil : $0 }
            f.placeholder = s["placeholder"]?.string
            f.help = s["help"]?.string
            f.options = s["options"]?.strings ?? []
            f.discover = s["discover"]?.string
            f.domain = s["domain"]?.string
            f.unit = s["unit"]?.string
            f.multiple = s["multiple"]?.bool ?? false
            return f
        }
        let presets: [FormPreset] = (m["presets"]?.array ?? []).compactMap { e in
            guard let p = e.object, let pid = p["id"]?.string, let values = p["settings"]?.object else { return nil }
            return FormPreset(id: pid, label: p["label"]?.string ?? pid, settings: values)
        }
        let perms = m["permissions"]
        var locales: [String: [String: String]] = [:]
        for (tag, table) in entry["locales"]?.object ?? [:] {
            locales[tag] = (table.object ?? [:]).compactMapValues(\.string)
        }
        let slots = (m["slots"]?.strings ?? []).filter { PluginSlot.all.contains($0) }
        let visibility = m["default_visibility"]?.string
        return FormPlugin(
            id: id, name: m["name"]?.string ?? id, version: m["version"]?.string, description: m["description"]?.string,
            slots: slots.isEmpty ? PluginSlot.all : slots,
            defaultVisibility: visibility.flatMap { PluginVisibility.all.contains($0) ? $0 : nil } ?? PluginVisibility.always,
            settings: settings, presets: presets,
            hosts: (perms?["http"]?.strings ?? []).filter { !$0.hasPrefix("settings.") },
            location: perms?["location"]?.bool ?? false,
            builtin: entry["builtin"]?.bool ?? true,
            author: m["author"]?["name"]?.string?.trimmingCharacters(in: .whitespaces).nonEmpty,
            license: m["license"]?.string?.trimmingCharacters(in: .whitespaces).nonEmpty,
            permissions: PluginPermissions.read(perms),
            locales: locales
        )
    }

    /// The entities `s` can pick from: its `domain` and `unit` when the manifest names them, matching `query`.
    public static func entities(_ all: [DiscoveredEntity], for s: FormSetting, query: String = "") -> [DiscoveredEntity] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return all.filter { e in
            (s.domain == nil || e.entityId.split(separator: ".").first.map(String.init) == s.domain)
                && (s.unit == nil || e.unit == s.unit)
                && (q.isEmpty || e.entityId.lowercased().contains(q) || (e.name?.lowercased().contains(q) ?? false))
        }
    }
}

/// A Home Assistant entity the frame sees through an instance.
public struct DiscoveredEntity: Equatable, Sendable {
    public var entityId: String
    public var name: String?
    public var unit: String?
}

/// A service the frame found on its network (mDNS).
public struct DiscoveredService: Equatable, Sendable {
    public var name: String
    public var url: String
}

/// How one instance is doing on the frame, from `GET /frames/{id}/plugins`.
public struct PluginInstanceReport: Equatable, Sendable {
    public var instanceId: String
    public var running = false
    public var error: String?
    public var entities: [DiscoveredEntity]?
    /// The plugin version the frame runs.
    public var version: String?
    /// A newer registry version waiting for the owner's approval, and what it may do.
    public var update: (version: String, permissions: PluginPermissions)?

    public static func == (a: Self, b: Self) -> Bool {
        a.instanceId == b.instanceId && a.running == b.running && a.error == b.error && a.entities == b.entities
            && a.version == b.version && a.update?.version == b.update?.version && a.update?.permissions == b.update?.permissions
    }
}

/// `GET /frames/{id}/plugins`: what the frame can run and how its instances are doing. `catalogReported` is false
/// for a frame app that doesn't report a catalog yet ("Update the frame app").
public struct PluginReport: Equatable, Sendable {
    public var catalog: [FormPlugin] = []
    public var catalogReported = false
    public var instances: [PluginInstanceReport] = []
    /// mDNS service type (a `discover` value) → what the frame found on its network.
    public var services: [String: [DiscoveredService]] = [:]

    public init(catalog: [FormPlugin] = [], catalogReported: Bool = false, instances: [PluginInstanceReport] = [], services: [String: [DiscoveredService]] = [:]) {
        self.catalog = catalog
        self.catalogReported = catalogReported
        self.instances = instances
        self.services = services
    }

    public func plugin(_ id: String) -> FormPlugin? { catalog.first { $0.id == id } }
    public func instance(_ id: String) -> PluginInstanceReport? { instances.first { $0.instanceId == id } }

    public static func read(_ v: JSONValue) -> PluginReport {
        var r = PluginReport()
        r.catalog = (v["catalog"]?.array ?? []).compactMap(FormPlugin.read)
        if let at = v["catalog_at"], at != .null { r.catalogReported = true }
        r.instances = (v["instances"]?.array ?? []).compactMap { i in
            guard let id = i["instance_id"]?.string else { return nil }
            var s = PluginInstanceReport(instanceId: id)
            s.running = i["running"]?.bool ?? false
            s.error = i["error"]?.string
            s.entities = i["entities"]?.array?.compactMap { e in
                e["entity_id"]?.string.map { DiscoveredEntity(entityId: $0, name: e["name"]?.string, unit: e["unit"]?.string) }
            }
            s.version = i["version"]?.string
            if let u = i["update"], let version = u["version"]?.string {
                s.update = (version, PluginPermissions.read(u["permissions"]))
            }
            return s
        }
        for (type, list) in v["services"]?.object ?? [:] {
            r.services[type] = (list.array ?? []).compactMap { s in
                guard let name = s["name"]?.string, let url = s["url"]?.string else { return nil }
                return DiscoveredService(name: name, url: url)
            }
        }
        return r
    }
}

/// `FrameSettings.plugins.layout`: off, the photo's top corners, a band beside the photo, or two photos and a dashboard.
public struct PluginLayout: Equatable, Sendable {
    public static let off = "off", overlay = "overlay", band = "band", split = "split"
    public static let left = "left", right = "right"
    public static let paper = "paper", photo = "photo"

    public var mode = PluginLayout.off
    public var side = PluginLayout.right
    public var background = PluginLayout.paper
}

/// One plugin instance as the form edits it. `secretWrites` are the secrets changed in this session (a value sets,
/// nil removes); only those are sent. `extra` keeps fields this app doesn't know (such as `order`).
public struct PluginInstanceDraft: Equatable, Sendable, Identifiable {
    public var instanceId: String
    public var pluginId: String
    public var version: String?
    public var slot = PluginSlot.topRight
    public var visibility = PluginVisibility.always
    public var enabled = true
    public var settings: [String: JSONValue] = [:]
    /// Read-only: the secrets that have a value on the server.
    public var secretsSet: Set<String> = []
    public var secretWrites: [String: String?] = [:]
    public var extra: [String: JSONValue] = [:]

    public var id: String { instanceId }

    public init(instanceId: String, pluginId: String, version: String? = nil, slot: String = PluginSlot.topRight,
                visibility: String = PluginVisibility.always, enabled: Bool = true, settings: [String: JSONValue] = [:]) {
        self.instanceId = instanceId
        self.pluginId = pluginId
        self.version = version
        self.slot = slot
        self.visibility = visibility
        self.enabled = enabled
        self.settings = settings
    }

    /// Whether secret `key` has a value after this session's writes.
    public func secretSet(_ key: String) -> Bool {
        if let write = secretWrites[key] { return !(write ?? "").isEmpty }
        return secretsSet.contains(key)
    }

    static let known: Set<String> = ["instance_id", "plugin_id", "version", "slot", "visibility", "enabled", "settings", "secrets", "secrets_set"]

    /// The instance as sent: no `secrets_set`, and `secrets` only for the secrets changed.
    public var json: JSONValue {
        var o = extra
        o["instance_id"] = .string(instanceId)
        o["plugin_id"] = .string(pluginId)
        if let version { o["version"] = .string(version) }
        o["slot"] = .string(slot)
        o["visibility"] = .string(visibility)
        o["enabled"] = .bool(enabled)
        o["settings"] = .object(settings)
        if !secretWrites.isEmpty {
            o["secrets"] = .object(secretWrites.mapValues { v in v.flatMap { $0.isEmpty ? nil : JSONValue.string($0) } ?? .null })
        }
        return .object(o)
    }

    public init?(json: JSONValue) {
        guard let o = json.object, let id = o["instance_id"]?.string else { return nil }
        self.init(instanceId: id, pluginId: o["plugin_id"]?.string ?? "", version: o["version"]?.string,
                  slot: o["slot"]?.string ?? PluginSlot.topRight, visibility: o["visibility"]?.string ?? PluginVisibility.always,
                  enabled: o["enabled"]?.bool ?? true, settings: o["settings"]?.object ?? [:])
        secretsSet = Set(o["secrets_set"]?.strings ?? [])
        extra = o.filter { !Self.known.contains($0.key) }
    }
}

/// A field problem of one instance: `key` is the setting (nil for the instance itself), `code` the server's reason.
public struct InstanceFieldError: Equatable, Sendable {
    public static let required = "required", type = "type", timezone = "timezone"

    public var key: String?
    public var field: String
    public var code: String

    public init(key: String?, field: String, code: String) {
        self.key = key
        self.field = field
        self.code = code
    }
}

/// How an instance is doing, for its status line.
public enum InstanceStatus: Equatable, Sendable {
    case off, waiting, running, notOnFrame
    case failed(String)
    /// Its plugin was withdrawn from the community registry, for this reason; best removed.
    case withdrawn(String)
}

/// A newer registry version waiting for approval. `asks` are the changes when the catalog holds the version the
/// frame runs (`diffed`), else everything the new version may do; `fewer`: diffed, and it only drops permissions.
public struct PendingUpdate: Equatable, Sendable {
    public var version: String
    public var asks: [PluginPermission]
    public var diffed: Bool
    public var fewer: Bool
}

/// The plugin settings form's logic, shared by the app's screens and the unit tests.
public enum PluginSettings {
    public static let maxInstances = 8

    public static func layout(_ settings: [String: JSONValue]) -> PluginLayout {
        let l = settings["plugins"]?["layout"]
        var out = PluginLayout()
        if let v = l?["mode"]?.string { out.mode = v }
        if let v = l?["side"]?.string { out.side = v }
        if let v = l?["background"]?.string { out.background = v }
        return out
    }

    public static func instances(_ settings: [String: JSONValue]) -> [PluginInstanceDraft] {
        (settings["plugins"]?["instances"]?.array ?? []).compactMap(PluginInstanceDraft.init(json:))
    }

    /// A new instance id: `p-` and 10 random lowercase letters or digits, not one of `taken`.
    public static func newInstanceId(taken: [String] = []) -> String {
        let chars = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        while true {
            let id = "p-" + String((0..<10).map { _ in chars.randomElement()! })
            if !taken.contains(id) { return id }
        }
    }

    /// A new, switched-off instance of `plugin`: top-right when allowed, the manifest's `default_visibility`.
    public static func newInstance(_ plugin: FormPlugin, taken: [String] = []) -> PluginInstanceDraft {
        PluginInstanceDraft(instanceId: newInstanceId(taken: taken), pluginId: plugin.id, version: plugin.version,
                            slot: plugin.defaultSlot, visibility: plugin.defaultVisibility, enabled: false)
    }

    /// What a field shows: the value, else the manifest's default.
    public static func displayValue(_ s: FormSetting, _ d: PluginInstanceDraft) -> JSONValue? {
        if let v = d.settings[s.key], v != .null { return v }
        return s.defaultValue
    }

    /// Sets `key`; an empty value (nil, blank text, empty list) is left out of `settings`.
    public static func withValue(_ d: PluginInstanceDraft, _ key: String, _ value: JSONValue?) -> PluginInstanceDraft {
        var out = d
        if isEmpty(value) { out.settings[key] = nil } else { out.settings[key] = value }
        return out
    }

    /// The value typed into a text-like field: a number for `number` (left as text when it isn't one).
    public static func parseText(_ s: FormSetting, _ text: String) -> JSONValue? {
        let t = s.type == SettingType.text ? text : text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        if s.type == SettingType.number {
            if let n = Int64(t) { return .number(Double(n)) }
            if let n = Double(t.replacingOccurrences(of: ",", with: ".")), n.isFinite { return .number(n) }
        }
        return .string(t)
    }

    /// The text a text-like field shows for `value`.
    public static func text(_ value: JSONValue?) -> String {
        switch value {
        case nil, .null?: ""
        case .string(let s)?: s
        case .bool(let b)?: b ? "true" : "false"
        case .number(let n)?: n.rounded() == n && abs(n) < 1e15 ? String(Int64(n)) : String(n)
        case .array(let a)?: a.map { text($0) }.filter { !$0.isEmpty }.joined(separator: ", ")
        case .object?: ""
        }
    }

    /// The entity ids of an entity field (one, or a list for `multiple`).
    public static func entityIds(_ value: JSONValue?) -> [String] {
        switch value {
        case .string(let s)?: s.trimmingCharacters(in: .whitespaces).isEmpty ? [] : [s]
        case .array(let a)?: a.compactMap { $0.string }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        default: []
        }
    }

    public static func entityValue(_ s: FormSetting, _ ids: [String]) -> JSONValue? {
        guard let last = ids.last else { return nil }
        if s.multiple { return .array(ids.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.map(JSONValue.string)) }
        return .string(last)
    }

    public static func isEmpty(_ v: JSONValue?) -> Bool {
        switch v {
        case nil, .null?: true
        case .string(let s)?: s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .array(let a)?: a.isEmpty
        default: false
        }
    }

    /// The client-side check, the same as the server's: an enabled instance needs every `required` setting without a
    /// `default` filled in, and those secrets set (or being set). Also flags a number field holding text and a time
    /// zone field holding an id that isn't a known zone (empty is fine unless required).
    public static func check(_ plugin: FormPlugin, _ d: PluginInstanceDraft) -> [InstanceFieldError] {
        var out: [InstanceFieldError] = []
        for s in plugin.settings {
            let v = d.settings[s.key]
            if s.type == SettingType.number, case .string? = v {
                out.append(InstanceFieldError(key: s.key, field: "settings", code: InstanceFieldError.type))
            }
            if s.type == SettingType.timezone, !isEmpty(v), !(v?.string.map(TimeZones.known) ?? false) {
                out.append(InstanceFieldError(key: s.key, field: "settings", code: InstanceFieldError.timezone))
            }
        }
        guard d.enabled else { return out }
        for s in plugin.settings where s.mustFill {
            if s.type == SettingType.secret {
                if !d.secretSet(s.key) { out.append(InstanceFieldError(key: s.key, field: "secrets", code: InstanceFieldError.required)) }
            } else if isEmpty(d.settings[s.key]) {
                out.append(InstanceFieldError(key: s.key, field: "settings", code: InstanceFieldError.required))
            }
        }
        return out
    }

    /// Whether `d` could be switched on: `check` passes with `enabled`.
    public static func complete(_ plugin: FormPlugin, _ d: PluginInstanceDraft) -> Bool {
        var on = d
        on.enabled = true
        return check(plugin, on).isEmpty
    }

    /// The merge patch that sets the whole instance list (an array, so it's always sent in full).
    public static func instancesPatch(_ instances: [PluginInstanceDraft]) -> [String: JSONValue] {
        ["plugins": .object(["instances": .array(instances.map(\.json))])]
    }

    public static func layoutPatch(_ field: String, _ value: String) -> [String: JSONValue] {
        ["plugins": .object(["layout": .object([field: .string(value)])])]
    }

    /// The 422 field errors of a save of `sent`, by instance id: `plugins.instances.0.settings.grid` → (grid,
    /// settings, code); `…secrets.token` → (token, secrets, code); `…slot` → (nil, slot, code).
    public static func fieldErrors(_ errors: [(field: String, code: String)], sent: [PluginInstanceDraft]) -> [String: [InstanceFieldError]] {
        var out: [String: [InstanceFieldError]] = [:]
        for e in errors {
            guard let m = e.field.wholeMatch(of: /plugins\.instances\.(\d+)\.([a-z_]+)(?:\.(.+))?/), let n = Int(m.1), sent.indices.contains(n) else { continue }
            let field = String(m.2)
            let key = (field == "settings" || field == "secrets") ? m.3.map(String.init) : nil
            out[sent[n].instanceId, default: []].append(InstanceFieldError(key: key, field: field, code: e.code))
        }
        return out
    }

    /// The status line of `d`: withdrawn, the frame's error, "waiting" before its first report, nothing when it runs.
    public static func status(_ d: PluginInstanceDraft, report: PluginReport?, onFrame: Bool) -> InstanceStatus {
        if let reason = withdrawnReason(report?.instance(d.instanceId)?.error) { return .withdrawn(reason) }
        if let report, report.catalogReported, !onFrame { return .notOnFrame }
        if !d.enabled { return .off }
        guard let s = report?.instance(d.instanceId) else { return .waiting }
        if let e = s.error, !e.trimmingCharacters(in: .whitespaces).isEmpty { return .failed(e) }
        return s.running ? .running : .waiting
    }

    /// The registry's reason when the frame reports the plugin as withdrawn (`withdrawn: …`), else nil.
    public static func withdrawnReason(_ error: String?) -> String? {
        guard let error, error.lowercased().hasPrefix("withdrawn:") else { return nil }
        return String(error.dropFirst("withdrawn:".count)).trimmingCharacters(in: .whitespaces)
    }

    /// The update of `d` waiting for approval, or nil (none, or `d` already names that version). Diffed against the
    /// catalog's manifest when that is the version the frame runs, else the new version's permissions in full.
    public static func pendingUpdate(_ plugin: FormPlugin?, _ d: PluginInstanceDraft, _ st: PluginInstanceReport?) -> PendingUpdate? {
        guard let u = st?.update, !u.version.isEmpty, d.version != u.version else { return nil }
        let current = st?.version.flatMap { $0.isEmpty ? nil : $0 } ?? d.version
        if let plugin, let current, plugin.version == current {
            let added = u.permissions.minus(plugin.permissions).items, removed = plugin.permissions.minus(u.permissions).items
            if !added.isEmpty || !removed.isEmpty { return PendingUpdate(version: u.version, asks: added, diffed: true, fewer: added.isEmpty) }
        }
        return PendingUpdate(version: u.version, asks: u.permissions.items, diffed: false, fewer: false)
    }

    /// The instance list with `version` approved for `instanceId`: saved like any other change.
    public static func approveUpdate(_ list: [PluginInstanceDraft], instanceId: String, version: String) -> [PluginInstanceDraft] {
        list.map { var d = $0; if d.instanceId == instanceId { d.version = version }; return d }
    }
}

/// A time zone as the `timezone` setting's picker shows it: "New York", "America · UTC−4".
public struct TimeZoneOption: Equatable, Sendable, Identifiable {
    /// The IANA id, "America/New_York": what is stored.
    public var id: String
    public var city: String
    public var region: String
    public var offsetSeconds: Int?

    public var offset: String? { offsetSeconds.map(TimeZones.formatOffset) }
    public var detail: String { [region.isEmpty ? nil : region, offset].compactMap { $0 }.joined(separator: " · ") }
}

/// The `timezone` setting type: an IANA time zone id such as `Asia/Tokyo`.
public enum TimeZones {
    static let available = Set(TimeZone.knownTimeZoneIdentifiers)

    /// Region ids ("Europe/Brussels"), not `Etc/GMT+5` or bare aliases.
    public static let pickable: [String] = available.filter { $0.contains("/") && !$0.hasPrefix("Etc/") && !$0.hasPrefix("SystemV/") }.sorted()

    public static func known(_ id: String) -> Bool { available.contains(id) || TimeZone(identifier: id) != nil }

    public static func city(_ id: String) -> String {
        String(id.split(separator: "/").last ?? Substring(id)).replacingOccurrences(of: "_", with: " ")
    }

    public static func region(_ id: String) -> String {
        let parts = id.split(separator: "/").dropLast()
        return parts.map { $0.replacingOccurrences(of: "_", with: " ") }.joined(separator: " / ")
    }

    /// "UTC−4" (a real minus sign), "UTC+5:30", "UTC" for no offset.
    public static func formatOffset(_ seconds: Int) -> String {
        if seconds == 0 { return "UTC" }
        let sign = seconds < 0 ? "−" : "+"
        let minutes = abs(seconds) / 60
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "UTC\(sign)\(h)" : "UTC\(sign)\(h):\(String(format: "%02d", m))"
    }

    public static func option(_ id: String, at now: Date = Date()) -> TimeZoneOption {
        TimeZoneOption(id: id, city: city(id), region: region(id), offsetSeconds: TimeZone(identifier: id)?.secondsFromGMT(for: now))
    }

    /// Every pickable zone, by city.
    public static func options(at now: Date = Date()) -> [TimeZoneOption] {
        pickable.map { option($0, at: now) }.sorted { ($0.city.lowercased(), $0.id) < ($1.city.lowercased(), $1.id) }
    }

    /// The options matching every word of `query` in the city, the region or the id ("new york", "tokyo").
    public static func search(_ options: [TimeZoneOption], _ query: String) -> [TimeZoneOption] {
        let words = query.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return options }
        return options.filter { o in
            let hay = "\(o.city) \(o.region) \(o.id) \(o.id.replacingOccurrences(of: "_", with: " "))".lowercased()
            return words.allSatisfy { hay.contains($0) }
        }
    }
}

extension JSONValue {
    public var string: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var bool: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var array: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    /// The string elements of an array.
    public var strings: [String]? { array?.compactMap(\.string) }
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
