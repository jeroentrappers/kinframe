@testable import Kinframe
@testable import KinframeKit
import os
import XCTest

final class AccountLogicTests: XCTestCase {
    private func member(_ id: String, _ name: String, _ role: Role) -> Member {
        Member(userId: id, displayName: name, role: role, permissions: Permissions())
    }

    func testTransferCandidatesAreAdminsFirstWithoutMeOrTheOwner() {
        let members = [member("me", "Jeroen", .owner), member("a", "Zoë", .member), member("b", "Bart", .admin),
                       member("c", "anna", .member), member("d", "Yves", .admin)]
        XCTAssertEqual(TransferLogic.candidates(members, me: "me").map(\.userId), ["b", "d", "c", "a"])
    }

    func testTransferSuggestion() {
        let list = [member("b", "Bart", .admin), member("c", "Anna", .member)]
        XCTAssertEqual(TransferLogic.suggestion(list, preselect: "c"), "c", "the member asked for from People")
        XCTAssertEqual(TransferLogic.suggestion(list, preselect: "gone"), "b", "else the first admin")
        XCTAssertEqual(TransferLogic.suggestion([member("c", "Anna", .member)], preselect: nil), "c")
        XCTAssertNil(TransferLogic.suggestion([], preselect: nil))
    }

    func testFrameDetailsForm() throws {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let frame = try decoder.decode(FrameSummary.self, from: Data(frameJSON.utf8))
        XCTAssertFalse(FrameDetailsForm.canSave(name: " Oma’s frame ", location: "Living room", current: frame), "unchanged")
        XCTAssertTrue(FrameDetailsForm.canSave(name: "Oma’s frame", location: "Kitchen", current: frame))
        XCTAssertFalse(FrameDetailsForm.canSave(name: "   ", location: "Kitchen", current: frame), "a name is needed")
        XCTAssertEqual(FrameDetailsForm.clean(String(repeating: "x", count: 60)).count, 40)
    }

    private func reservation(_ status: ReservationStatus, _ qty: [Int]) -> ShopReservation {
        ShopReservation(id: UUID().uuidString, reference: "KF-1", status: status, name: "N", email: "n@example.org", country: "BE",
                        locale: "nl", items: qty.map { .init(productId: "p\($0)", name: "Frame", quantity: $0, unitCents: 100, lineCents: 100 * $0) },
                        subtotalCents: 0, shippingCents: 0, totalCents: 0, currency: "EUR", createdAt: Date(), updatedAt: Date())
    }

    func testReservationFramesCountLeavesOutCancelled() {
        let list = [reservation(.new, [2]), reservation(.confirmed, [1, 3]), reservation(.cancelled, [5])]
        XCTAssertEqual(ReservationLogic.framesCount(list), 6)
    }

    func testReservationFilterAndNote() {
        XCTAssertTrue(ReservationLogic.matches(reservation(.contacted, [1]), filter: nil))
        XCTAssertFalse(ReservationLogic.matches(reservation(.contacted, [1]), filter: .new), "drops out of the New filter")
        XCTAssertEqual(ReservationLogic.note("  paid  "), "paid")
        XCTAssertEqual(ReservationLogic.note(String(repeating: "a", count: 2100)).count, 2000)
        XCTAssertEqual(ReservationLogic.note("   "), "", "empty clears the note")
    }

    func testReservationExportNameAndMoney() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Brussels")!
        let date = cal.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: 10))!
        XCTAssertEqual(ReservationLogic.exportName(now: date, calendar: cal), "reservations-2026-11-03.csv")
        let money = ReservationLogic.money(30495, currency: "eur", locale: Locale(identifier: "nl_BE"))
        XCTAssertTrue(money.contains("304,95"), money)
        XCTAssertEqual(ReservationLogic.countryName("BE", locale: Locale(identifier: "en")), "Belgium")
    }

    func testWebLinks() {
        XCTAssertEqual(WebLinks.page("/privacy", web: "https://kinframe.appmire.be")?.absoluteString, "https://kinframe.appmire.be/privacy")
        XCTAssertEqual(WebLinks.page("terms", web: "https://example.org/kf/")?.absoluteString, "https://example.org/kf/terms")
        XCTAssertNil(WebLinks.page("/privacy", web: nil))
        XCTAssertNil(WebLinks.page("/privacy", web: "not a url"))
    }

    func testDiscoveryKnowsWhetherThereIsAShop() throws {
        let base = #""name":"k","version":"1","api":"https://k.example.org/v1""#
        let withShop = try JSONDecoder().decode(Discovery.self, from: Data("{\(base),\"shop\":{\"brand\":\"kinframe\",\"currency\":\"EUR\"}}".utf8))
        XCTAssertTrue(withShop.shop)
        XCTAssertFalse(try JSONDecoder().decode(Discovery.self, from: Data("{\(base)}".utf8)).shop)
        XCTAssertFalse(try JSONDecoder().decode(Discovery.self, from: Data("{\(base),\"shop\":null}".utf8)).shop)
        // Stored with the server choice and read back.
        let again = try JSONDecoder().decode(Discovery.self, from: JSONEncoder().encode(withShop))
        XCTAssertTrue(again.shop)
    }
}

@MainActor
final class AccountModelTests: XCTestCase {
    func testReplaceDeviceSendsTheCodeAndReportsErrors() async throws {
        let fail = OSAllocatedUnfairLock(initialState: true)
        let fake = FakeAPI([FakeAPI.on("POST", "/v1/frames/f1/replace-device") { _ in
            if fail.withLock({ $0 }) { return FakeAPI.json(#"{"type":"x","title":"Bad code","status":404,"code":"code_not_found"}"#, status: 404) }
            return FakeAPI.json(frameJSON)
        }])
        let model = ReplaceDeviceModel(api: fake.api, frameId: "f1")
        model.setCode("Code: 4821-39")
        XCTAssertFalse(model.canSubmit)
        model.setCode("4821 3907")
        XCTAssertTrue(model.canSubmit)
        let first = await model.submit()
        XCTAssertNil(first)
        XCTAssertNotNil(model.error)
        XCTAssertFalse(model.done)
        model.setCode("48213908")
        XCTAssertNil(model.error, "typing clears the error")
        fail.withLock { $0 = false }
        let frame = await model.submit()
        XCTAssertEqual(frame?.id, "f1")
        XCTAssertTrue(model.done)
        XCTAssertFalse(model.canSubmit)
        let last = String(decoding: fake.requests.last?.body ?? Data(), as: UTF8.self)
        XCTAssertTrue(last.contains("48213908"), last)
    }

    func testReservationStatusChangeDropsItFromTheFilter() async throws {
        let json = #"{"id":"r1","reference":"KF-1","status":"contacted","name":"N","email":"n@example.org","country":"BE","locale":"nl","items":[{"product_id":"p","name":"Frame","quantity":2,"unit_cents":100,"line_cents":200}],"subtotal_cents":200,"shipping_cents":0,"total_cents":200,"currency":"EUR","internal_note":"called","created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-03T10:00:00Z"}"#
        let listed = json.replacingOccurrences(of: #""status":"contacted""#, with: #""status":"new""#).replacingOccurrences(of: #","internal_note":"called""#, with: "")
        let fake = FakeAPI([
            FakeAPI.on("GET", "/v1/operator/reservations") { _ in FakeAPI.json(#"{"reservations":[\#(listed)]}"#) },
            FakeAPI.on("PATCH", "/v1/operator/reservations/r1") { _ in FakeAPI.json(json) },
        ])
        let model = ReservationsModel(api: fake.api)
        await model.load()
        XCTAssertEqual(model.list?.count, 1)
        XCTAssertEqual(model.framesCount, 2)
        model.filter = .new
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertTrue(fake.requests.contains { $0.url.query?.contains("status=new") == true })
        model.notes["r1"] = " called "
        XCTAssertTrue(model.noteChanged(model.list![0]))
        try await model.update(model.list![0], status: .contacted)
        let patch = String(decoding: fake.requests.last { $0.method == "PATCH" }?.body ?? Data(), as: UTF8.self)
        XCTAssertTrue(patch.contains("contacted") && !patch.contains("internal_note"), patch)
        XCTAssertEqual(model.list?.count, 0, "no longer New")
    }
}
