import XCTest
@testable import KinframeKit

final class AlbumLogicTests: XCTestCase {
    struct P: Identifiable, Equatable { let id: String }

    func testOrderFollowsTheAlbumWithUnknownsLast() {
        let items = ["a", "b", "c", "d"].map(P.init)
        XCTAssertEqual(AlbumLogic.ordered(items, by: ["c", "a"]).map(\.id), ["c", "a", "b", "d"])
        XCTAssertEqual(AlbumLogic.ordered(items, by: []).map(\.id), ["a", "b", "c", "d"])
    }

    func testArrangedOrderKeepsMembersTheViewDoesntShow() {
        XCTAssertEqual(AlbumLogic.arrangedIds(shown: ["c", "a"], album: ["a", "h", "c"]), ["c", "a", "h"])
    }

    func testMonthDays() {
        XCTAssertEqual(AlbumLogic.daysInMonth(2), 29)
        XCTAssertEqual(AlbumLogic.daysInMonth(4), 30)
        XCTAssertEqual(AlbumLogic.daysInMonth(12), 31)
        XCTAssertEqual(AlbumLogic.clampDay(31, month: 4), 30)
        XCTAssertEqual(AlbumLogic.clampDay(0, month: 1), 1)
        XCTAssertEqual(AlbumLogic.monthDay(3, 7), "03-07")
        XCTAssertEqual(AlbumLogic.parseMonthDay("02-29")?.day, 29)
        XCTAssertNil(AlbumLogic.parseMonthDay("02-30"))
        XCTAssertNil(AlbumLogic.parseMonthDay("13-01"))
        XCTAssertNil(AlbumLogic.parseMonthDay("1-01"))
        XCTAssertNil(AlbumLogic.parseMonthDay(nil))
    }

    func testScheduleCoversIncludingOverNewYear() {
        XCTAssertTrue(AlbumLogic.covers(from: "12-01", to: "12-31", today: "12-24"))
        XCTAssertFalse(AlbumLogic.covers(from: "12-01", to: "12-31", today: "11-30"))
        XCTAssertTrue(AlbumLogic.covers(from: "12-20", to: "01-06", today: "01-02"))
        XCTAssertTrue(AlbumLogic.covers(from: "12-20", to: "01-06", today: "12-20"))
        XCTAssertFalse(AlbumLogic.covers(from: "12-20", to: "01-06", today: "06-01"))
        XCTAssertFalse(AlbumLogic.covers(from: nil, to: "01-06", today: "01-01"))
        XCTAssertTrue(AlbumLogic.wraps(from: "12-20", to: "01-06"))
        XCTAssertFalse(AlbumLogic.wraps(from: "07-01", to: "08-31"))
    }

    func testFormatsAndTodayAndYearsAgo() throws {
        XCTAssertEqual(AlbumLogic.format("12-01", locale: Locale(identifier: "en_GB")), "1 December")
        XCTAssertEqual(AlbumLogic.format("12-01", locale: Locale(identifier: "nl_BE")), "1 december")
        var cal = Foundation.Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Brussels")!
        let now = try XCTUnwrap(cal.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: 9)))
        XCTAssertEqual(AlbumLogic.todayMonthDay(now, calendar: cal), "11-03")
        let taken = try XCTUnwrap(cal.date(from: DateComponents(year: 2021, month: 11, day: 3, hour: 12)))
        XCTAssertEqual(AlbumLogic.yearsAgo(taken, now: now, calendar: cal), 5)
        XCTAssertEqual(AlbumLogic.yearsAgo(nil, now: now), 0)
        XCTAssertEqual(AlbumLogic.monthNames(locale: Locale(identifier: "en_GB")).first, "January")
    }

    func testNamesAndLanguages() {
        XCTAssertNil(AlbumLogic.cleanName("   "))
        XCTAssertEqual(AlbumLogic.cleanName("  Zomer  "), "Zomer")
        XCTAssertEqual(AlbumLogic.cleanName(String(repeating: "x", count: 80))?.count, 60)
        XCTAssertEqual(AlbumLogic.frameLanguages.count, 36)
        XCTAssertTrue(AlbumLogic.frameLanguages.contains { $0.id == "nl" && $0.name == "Nederlands" })
    }

    func testStringValue() {
        XCTAssertEqual(JSONValue.string("a").stringValue, "a")
        XCTAssertNil(JSONValue.null.stringValue)
    }
}
