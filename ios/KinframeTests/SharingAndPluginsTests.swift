@testable import KinframeKit
import XCTest

final class SharingTests: XCTestCase {
    func frame(_ id: String, role: Role = .member, send: Bool) -> FrameSummary {
        FrameSummary(id: id, name: id.uppercased(), role: role, permissions: Permissions(send: send), online: true)
    }

    func testCoverageFromDryRun() {
        let skipped = [("p1", "f2"), ("p2", "f2"), ("p1", "f3")]
        let c = Sharing.coverage(skipped: skipped.map { (placementId: $0.0, frameId: $0.1) }, frameIds: ["f2", "f3", "f4"], selectionSize: 2)
        XCTAssertEqual(c, ["f2": .all, "f3": .some, "f4": Sharing.Coverage.none])
    }

    func testTargetsAreOtherFramesYouCanSendTo() {
        let frames = [frame("src", send: true), frame("a", send: true), frame("b", send: false), frame("c", role: .admin, send: false)]
        XCTAssertEqual(Sharing.targets(frames, excluding: "src").map(\.id), ["a", "c"])
    }

    func testSourceIsFirstFrameHoldingEveryItem() {
        let send = Send(
            id: "s1", createdAt: Date(),
            items: [Item(id: "i1", kind: .photo, status: .ready, senderId: "u1", createdAt: Date()),
                    Item(id: "i2", kind: .live, status: .ready, senderId: "u1", createdAt: Date()),
                    Item(id: "i3", kind: .photo, status: .failed, senderId: "u1", createdAt: Date())],
            frames: [
                SendFrame(frameId: "f1", frameName: "Oma", placements: [
                    SendPlacement(placementId: "a1", itemId: "i1", status: .seen),
                    SendPlacement(placementId: "a2", itemId: "i2", status: .removed),
                ]),
                SendFrame(frameId: "f2", frameName: "Opa", placements: [
                    SendPlacement(placementId: "b1", itemId: "i1", status: .delivered),
                    SendPlacement(placementId: "b2", itemId: "i2", status: .ready, sharedByName: "Lisa"),
                    SendPlacement(placementId: "b3", itemId: "i2", status: .removed, sharedByName: "Tom"),
                ]),
            ]
        )
        XCTAssertEqual(Sharing.shareableItems(of: send), ["i1", "i2"])
        XCTAssertEqual(Sharing.source(of: send, itemIds: ["i1", "i2"]), Sharing.Source(frameId: "f2", placementIds: ["b1", "b2"]))
        XCTAssertEqual(Sharing.source(of: send, itemIds: ["i1"]), Sharing.Source(frameId: "f1", placementIds: ["a1"]))
        XCTAssertNil(Sharing.source(of: send, itemIds: []))
        XCTAssertEqual(Sharing.sharedBy(send.frames[1]), ["Lisa"])
    }
}

final class PluginSettingsTests: XCTestCase {
    func entry(_ manifest: String, builtin: Bool = true, locales: String = "{}") throws -> FormPlugin {
        let json = #"{"manifest": \#(manifest), "locales": \#(locales), "builtin": \#(builtin)}"#
        return try XCTUnwrap(FormPlugin.read(JSONDecoder().decode(JSONValue.self, from: Data(json.utf8))))
    }

    lazy var p1: FormPlugin = try! entry("""
    {"id": "kinframe.homewizard-p1", "name": "HomeWizard P1", "version": "1.2.0", "slots": ["top-right", "bottom"],
     "default_visibility": "on_change",
     "settings": [
       {"key": "url", "type": "url", "label": "Address", "required": true, "discover": "_hwenergy._tcp"},
       {"key": "token", "type": "secret", "label": "Token", "required": true},
       {"key": "grid", "type": "number", "label": "Grid", "required": true, "default": 3},
       {"key": "zone", "type": "timezone", "label": "Zone"},
       {"key": "station", "type": "text", "label": "Station"},
       {"key": "line", "type": "text", "label": "Line"}
     ],
     "presets": [{"id": "nmbs", "label": "NMBS", "settings": {"line": "{station} → Brussel", "grid": 5}}],
     "permissions": {"http": ["settings.url", "https://api.example.org/v1"], "location": true}}
    """, locales: #"{"nl": {"@name": "HomeWizard P1-meter", "@setting.url": "Adres"}}"#)

    func testReadsManifestLeniently() {
        XCTAssertEqual(p1.id, "kinframe.homewizard-p1")
        XCTAssertEqual(p1.slots, [PluginSlot.topRight])
        XCTAssertEqual(p1.defaultSlot, PluginSlot.topRight)
        XCTAssertEqual(p1.defaultVisibility, PluginVisibility.onChange)
        XCTAssertEqual(p1.hosts, ["https://api.example.org/v1"])
        XCTAssertEqual(p1.permissions, PluginPermissions(hosts: ["api.example.org"], addresses: ["url"], location: true))
        XCTAssertEqual(p1.name("nl-BE"), "HomeWizard P1-meter")
        XCTAssertEqual(p1.name("en"), "HomeWizard P1")
        XCTAssertEqual(p1.label(p1.setting("url")!, "nl"), "Adres")
        XCTAssertFalse(p1.setting("grid")!.mustFill, "a default fills a required setting")
        XCTAssertTrue(p1.setting("url")!.mustFill)
    }

    func testCheckMatchesTheServer() {
        var d = PluginSettings.newInstance(p1)
        XCTAssertFalse(d.enabled)
        XCTAssertEqual(PluginSettings.check(p1, d), [], "a switched-off instance may be incomplete")
        XCTAssertFalse(PluginSettings.complete(p1, d))
        d.enabled = true
        XCTAssertEqual(PluginSettings.check(p1, d).map(\.key), ["url", "token"])
        d = PluginSettings.withValue(d, "url", .string("http://192.168.1.20"))
        d.secretWrites["token"] = "abc"
        d = PluginSettings.withValue(d, "grid", PluginSettings.parseText(p1.setting("grid")!, "3,5"))
        XCTAssertEqual(d.settings["grid"], .number(3.5))
        XCTAssertEqual(PluginSettings.check(p1, d), [])
        d = PluginSettings.withValue(d, "zone", .string("Mars/Olympus"))
        d = PluginSettings.withValue(d, "grid", PluginSettings.parseText(p1.setting("grid")!, "lots"))
        XCTAssertEqual(PluginSettings.check(p1, d).map(\.code), [InstanceFieldError.type, InstanceFieldError.timezone])
        d = PluginSettings.withValue(d, "zone", .string("Asia/Tokyo"))
        d = PluginSettings.withValue(d, "grid", .string("  "))
        XCTAssertNil(d.settings["grid"], "empty values are left out")
        XCTAssertEqual(PluginSettings.check(p1, d), [])
    }

    func testInstanceJsonKeepsUnknownFieldsAndSendsOnlyChangedSecrets() throws {
        let raw = try JSONDecoder().decode(JSONValue.self, from: Data(#"""
        {"instance_id": "p-abc", "plugin_id": "kinframe.homewizard-p1", "slot": "top-left", "order": 3, "enabled": true,
         "settings": {"url": "http://p1"}, "secrets_set": ["token"]}
        """#.utf8))
        var d = try XCTUnwrap(PluginInstanceDraft(json: raw))
        XCTAssertTrue(d.secretSet("token"))
        XCTAssertEqual(d.json["order"], .number(3))
        XCTAssertNil(d.json["secrets"])
        XCTAssertNil(d.json["secrets_set"])
        d.secretWrites["token"] = .some(nil)
        XCTAssertFalse(d.secretSet("token"))
        XCTAssertEqual(d.json["secrets"], .object(["token": .null]))
    }

    func testPresetFillsPlaceholders() {
        XCTAssertEqual(p1.preset("nmbs")?.inputs, ["station"])
        XCTAssertNil(p1.applyPreset("nmbs", to: [:]), "asks for the station first")
        let filled = p1.applyPreset("nmbs", to: ["station": .string("Gent")])
        XCTAssertEqual(filled?["line"], .string("Gent → Brussel"))
        XCTAssertEqual(filled?["grid"], .number(5))
    }

    func testFieldErrorsMapToInstances() {
        let sent = [PluginInstanceDraft(instanceId: "p-1", pluginId: "x"), PluginInstanceDraft(instanceId: "p-2", pluginId: "y")]
        let errors = PluginSettings.fieldErrors([("plugins.instances.1.settings.grid", "type"), ("plugins.instances.1.secrets.token", "required"),
                                                 ("plugins.instances.0.slot", "enum"), ("plugins.layout.mode", "enum")], sent: sent)
        XCTAssertEqual(errors["p-2"], [InstanceFieldError(key: "grid", field: "settings", code: "type"),
                                       InstanceFieldError(key: "token", field: "secrets", code: "required")])
        XCTAssertEqual(errors["p-1"], [InstanceFieldError(key: nil, field: "slot", code: "enum")])
    }

    func testStatusAndWithdrawal() {
        var d = PluginInstanceDraft(instanceId: "p-1", pluginId: p1.id)
        var report = PluginReport(catalog: [p1], catalogReported: true)
        XCTAssertEqual(PluginSettings.status(d, report: report, onFrame: true), .waiting)
        report.instances = [PluginInstanceReport(instanceId: "p-1", running: true)]
        XCTAssertEqual(PluginSettings.status(d, report: report, onFrame: true), .running)
        XCTAssertEqual(PluginSettings.status(d, report: report, onFrame: false), .notOnFrame)
        d.enabled = false
        XCTAssertEqual(PluginSettings.status(d, report: report, onFrame: true), .off)
        report.instances[0].error = "withdrawn: leaks tokens"
        XCTAssertEqual(PluginSettings.status(d, report: report, onFrame: true), .withdrawn("leaks tokens"))
    }

    func testPendingUpdateDiffsAgainstTheRunningVersion() throws {
        let d = PluginInstanceDraft(instanceId: "p-1", pluginId: p1.id, version: "1.2.0")
        var st = PluginInstanceReport(instanceId: "p-1", version: "1.2.0")
        XCTAssertNil(PluginSettings.pendingUpdate(p1, d, st))
        st.update = ("1.3.0", PluginPermissions(hosts: ["api.example.org", "cdn.example.org"], addresses: ["url"], location: true, actions: true))
        let u = try XCTUnwrap(PluginSettings.pendingUpdate(p1, d, st))
        XCTAssertTrue(u.diffed)
        XCTAssertEqual(u.asks, [.internet(["cdn.example.org"]), .actions])
        st.update = ("1.3.0", PluginPermissions(addresses: ["url"]))
        XCTAssertEqual(PluginSettings.pendingUpdate(p1, d, st)?.fewer, true)
        let approved = PluginSettings.approveUpdate([d], instanceId: "p-1", version: "1.3.0")
        XCTAssertNil(PluginSettings.pendingUpdate(p1, approved[0], st))
    }

    func testReportAndLayout() throws {
        let v = try JSONDecoder().decode(JSONValue.self, from: Data(#"""
        {"catalog": [], "catalog_at": "2026-11-01T10:00:00Z",
         "instances": [{"instance_id": "p-1", "running": false, "entities": [{"entity_id": "sensor.power", "unit": "W"}],
                        "update": {"version": "2.0.0", "permissions": {"actions": true}}}],
         "services": {"_hwenergy._tcp": [{"name": "P1 meter", "url": "http://192.168.1.30"}]}}
        """#.utf8))
        let r = PluginReport.read(v)
        XCTAssertTrue(r.catalogReported)
        XCTAssertEqual(r.instance("p-1")?.entities, [DiscoveredEntity(entityId: "sensor.power", unit: "W")])
        XCTAssertEqual(r.instance("p-1")?.update?.version, "2.0.0")
        XCTAssertEqual(r.services["_hwenergy._tcp"], [DiscoveredService(name: "P1 meter", url: "http://192.168.1.30")])
        XCTAssertFalse(PluginReport.read(.object(["catalog": .array([])])).catalogReported)

        let layout = PluginSettings.layout(["plugins": .object(["layout": .object(["mode": .string("split"), "side": .string("left")])])])
        XCTAssertEqual(layout, PluginLayout(mode: PluginLayout.split, side: PluginLayout.left, background: PluginLayout.paper))
        XCTAssertEqual(PluginSettings.layoutPatch("mode", "band"), ["plugins": .object(["layout": .object(["mode": .string("band")])])])
    }

    func testEntitiesFilterByDomainUnitAndQuery() {
        let s = FormSetting(key: "e", type: SettingType.entity, label: "E", domain: "sensor", unit: "W")
        let all = [DiscoveredEntity(entityId: "sensor.power", name: "Power", unit: "W"),
                   DiscoveredEntity(entityId: "sensor.energy", unit: "kWh"),
                   DiscoveredEntity(entityId: "switch.power", unit: "W")]
        XCTAssertEqual(FormPlugin.entities(all, for: s).map(\.entityId), ["sensor.power"])
        XCTAssertEqual(FormPlugin.entities(all, for: s, query: "pow").map(\.entityId), ["sensor.power"])
        XCTAssertEqual(PluginSettings.entityValue(FormSetting(key: "e", type: "entity", label: "E", multiple: true), ["a", "b", "a"]),
                       .array([.string("a"), .string("b")]))
    }

    func testTimeZones() {
        XCTAssertEqual(TimeZones.formatOffset(0), "UTC")
        XCTAssertEqual(TimeZones.formatOffset(-4 * 3600), "UTC−4")
        XCTAssertEqual(TimeZones.formatOffset(19800), "UTC+5:30")
        let o = TimeZones.option("America/Argentina/Buenos_Aires")
        XCTAssertEqual(o.city, "Buenos Aires")
        XCTAssertEqual(o.region, "America / Argentina")
        XCTAssertEqual(TimeZones.search(TimeZones.options(), "new york").map(\.id), ["America/New_York"])
        XCTAssertTrue(TimeZones.known("Asia/Tokyo"))
        XCTAssertFalse(TimeZones.pickable.contains { $0.hasPrefix("Etc/") })
    }
}
