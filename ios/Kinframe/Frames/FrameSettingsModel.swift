import KinframeKit
import OpenAPIRuntime
import SwiftUI

/// Remote frame settings (README §5.6). The document is edited as JSON so fields this app doesn't know survive, and
/// a save sends only what changed as a merge patch with `If-Match`. After a `412 version_conflict` the edit is
/// rebased like the web does: retried when the other change touched different fields, otherwise shown for review.
@MainActor
@Observable
final class FrameSettingsModel {
    enum SaveResult: Equatable {
        case saved, merged, review
    }

    let api: API
    let frameId: String
    private(set) var base: [String: JSONValue] = [:]
    var draft: [String: JSONValue] = [:]
    private(set) var loaded = false
    private(set) var saving = false
    var conflict = false
    var error: String?

    init(api: API, frameId: String) {
        self.api = api
        self.frameId = frameId
    }

    var version: Int {
        if case .number(let n)? = base["version"] { return Int(n) }
        return 0
    }
    var patch: [String: JSONValue] {
        var b = base, d = draft
        b["version"] = nil
        d["version"] = nil
        return MergePatch.diff(from: b, to: d)
    }
    var dirty: Bool { !patch.isEmpty }

    func load() async {
        do {
            base = try await fetch()
            draft = base
            loaded = true
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func fetch() async throws -> [String: JSONValue] {
        let id = frameId
        let settings = try await api.call { try await $0.getFrameSettings(path: .init(frameId: id)).ok.body.json }
        return try JSONValue.from(settings).object ?? [:]
    }

    func discard() {
        draft = base
        conflict = false
    }

    @discardableResult
    func save() async throws -> SaveResult {
        saving = true
        defer { saving = false }
        let ours = patch
        do {
            try await send(ours, version: version)
            conflict = false
            return .saved
        } catch let e as APIError where e.status == 412 || e.code == "version_conflict" {
            let latest = try await fetch()
            switch MergePatch.rebase(base: base, latest: latest, ours: ours) {
            case .retry(let p):
                base = latest
                draft = MergePatch.apply(p, to: latest)
                try await send(p, version: version)
                return .merged
            case .review(let merged):
                base = latest
                draft = merged
                conflict = true
                return .review
            }
        }
    }

    private func send(_ patch: [String: JSONValue], version: Int) async throws {
        let id = frameId
        let body = try JSONDecoder().decode(OpenAPIObjectContainer.self, from: JSONEncoder().encode(JSONValue.object(patch)))
        let updated = try await api.call {
            try await $0.updateFrameSettings(path: .init(frameId: id), headers: .init(ifMatch: "\"\(version)\""), body: .applicationMergePatchJson(body)).ok.body.json
        }
        base = try JSONValue.from(updated).object ?? [:]
        draft = base
    }

    // MARK: Typed access by path ("slideshow.interval_s")

    func value(_ path: String) -> JSONValue? {
        var cur: JSONValue? = .object(draft)
        for part in path.split(separator: ".") { cur = cur?[String(part)] }
        return cur
    }

    func set(_ path: String, _ value: JSONValue?) {
        let parts = path.split(separator: ".").map(String.init)
        func put(_ obj: [String: JSONValue], _ i: Int) -> [String: JSONValue] {
            var o = obj
            if i == parts.count - 1 {
                o[parts[i]] = value
            } else {
                o[parts[i]] = .object(put(o[parts[i]]?.object ?? [:], i + 1))
            }
            return o
        }
        draft = put(draft, 0)
    }

    func int(_ path: String, _ fallback: Int) -> Binding<Int> {
        Binding(get: { if case .number(let n)? = self.value(path) { Int(n) } else { fallback } },
                set: { self.set(path, .number(Double($0))) })
    }

    func bool(_ path: String, _ fallback: Bool) -> Binding<Bool> {
        Binding(get: { if case .bool(let b)? = self.value(path) { b } else { fallback } },
                set: { self.set(path, .bool($0)) })
    }

    func string(_ path: String, _ fallback: String) -> Binding<String> {
        Binding(get: { if case .string(let s)? = self.value(path) { s } else { fallback } },
                set: { self.set(path, .string($0)) })
    }

    var schedules: [SleepSchedule] {
        get { (try? value("display.sleep_schedules")?.decode([SleepSchedule].self)) ?? [] }
        set { set("display.sleep_schedules", try? JSONValue.from(newValue)) }
    }

    var location: Place? {
        guard let obj = value("location")?.object, case .number(let lat)? = obj["lat"], case .number(let lon)? = obj["lon"] else { return nil }
        let name: String? = if case .string(let s)? = obj["name"] { s } else { nil }
        return Place(name: name ?? L("location.unnamed"), lat: lat, lon: lon)
    }

    func setLocation(_ place: Place?) {
        guard let place else {
            set("location", .null)
            set("widgets.weather", .bool(false))
            return
        }
        let label = [place.name, place.admin1 ?? nil, place.countryCode ?? nil].compactMap { $0 }.joined(separator: ", ")
        set("location", .object(["name": .string(String(label.prefix(100))), "lat": .number(place.lat), "lon": .number(place.lon)]))
    }
}
