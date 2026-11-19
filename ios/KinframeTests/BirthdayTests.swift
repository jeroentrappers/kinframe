@testable import KinframeKit
import XCTest

/// Birthdays in feeds: dates and the 29 February rule, sorting by the next birthday, ages, who adds or suggests,
/// which fields the edit form offers, year visibility, request bodies, greetings, live events, push routes and tags,
/// and decoding of the new schemas (fixtures follow docs/api/openapi.yaml).
final class BirthdayDateTests: XCTestCase {
    private func d(_ y: Int, _ m: Int, _ day: Int) -> LocalDate { LocalDate(year: y, month: m, day: day) }

    func testLocalDates() {
        XCTAssertEqual(LocalDate(iso: "2027-03-12"), d(2027, 3, 12))
        XCTAssertEqual(LocalDate(iso: "2027-03-12T00:00:00Z"), d(2027, 3, 12))
        XCTAssertNil(LocalDate(iso: "2027-02-29"), "not a leap year")
        XCTAssertNotNil(LocalDate(iso: "2028-02-29"))
        XCTAssertNil(LocalDate(iso: "12 March"))
        XCTAssertEqual(d(2027, 3, 12).iso, "2027-03-12")
        XCTAssertEqual(d(1970, 1, 1).ordinal, 0)
        XCTAssertEqual(d(2026, 12, 31).days(until: d(2027, 1, 1)), 1)
        XCTAssertEqual(d(2028, 2, 28).days(until: d(2028, 3, 1)), 2, "leap day in between")
        XCTAssertTrue(d(2026, 12, 31) < d(2027, 1, 1))
    }

    func testTodayFollowsTheZone() {
        // 23:30 UTC on 11 March is already 12 March in Brussels.
        let now = ISO8601DateFormatter().date(from: "2027-03-11T23:30:00Z")!
        XCTAssertEqual(LocalDate.today(in: TimeZone(identifier: "UTC")!, now: now), d(2027, 3, 11))
        XCTAssertEqual(LocalDate.today(in: TimeZone(identifier: "Europe/Brussels")!, now: now), d(2027, 3, 12))
    }

    func testValidation() {
        XCTAssertNil(BirthdayDates.problem(day: 12, month: 3, year: 1946, currentYear: 2026))
        XCTAssertNil(BirthdayDates.problem(day: 29, month: 2, year: nil, currentYear: 2026), "29 February without a year")
        XCTAssertNil(BirthdayDates.problem(day: 29, month: 2, year: 2000, currentYear: 2026))
        XCTAssertEqual(BirthdayDates.problem(day: 29, month: 2, year: 1900, currentYear: 2026), .invalidDay, "1900 isn't a leap year")
        XCTAssertEqual(BirthdayDates.problem(day: 30, month: 2, year: nil, currentYear: 2026), .invalidDay)
        XCTAssertEqual(BirthdayDates.problem(day: 31, month: 4, year: nil, currentYear: 2026), .invalidDay)
        XCTAssertEqual(BirthdayDates.problem(day: 1, month: 13, year: nil, currentYear: 2026), .invalidDay)
        XCTAssertEqual(BirthdayDates.problem(day: 1, month: 1, year: 1899, currentYear: 2026), .invalidYear)
        XCTAssertEqual(BirthdayDates.problem(day: 1, month: 1, year: 2027, currentYear: 2026), .invalidYear)
        XCTAssertNil(BirthdayDates.problem(day: 2, month: 6, year: 2026, currentYear: 2026), "born this year")
    }

    func testLeapDayOccurrences() {
        XCTAssertEqual(BirthdayDates.occurrence(day: 29, month: 2, in: 2027), d(2027, 2, 28))
        XCTAssertEqual(BirthdayDates.occurrence(day: 29, month: 2, in: 2028), d(2028, 2, 29))
        XCTAssertEqual(BirthdayDates.occurrence(day: 29, month: 2, in: 2100), d(2100, 2, 28))
        // On 28 February of a non-leap year a leap-day person is "today"; on 1 March the next one is next year.
        XCTAssertEqual(BirthdayDates.next(day: 29, month: 2, birthYear: nil, from: d(2027, 2, 28)), d(2027, 2, 28))
        XCTAssertEqual(BirthdayDates.next(day: 29, month: 2, birthYear: nil, from: d(2027, 3, 1)), d(2028, 2, 29))
        XCTAssertEqual(BirthdayDates.next(day: 29, month: 2, birthYear: nil, from: d(2028, 2, 29)), d(2028, 2, 29))
        XCTAssertEqual(BirthdayDates.next(day: 29, month: 2, birthYear: 2000, from: d(2027, 1, 1)), d(2027, 2, 28))
        XCTAssertEqual(BirthdayDates.age(birthYear: 2000, on: d(2027, 2, 28)), 27, "age on 28 February is Y − birth_year")
    }

    func testNextOccurrence() {
        let today = d(2027, 3, 7)
        XCTAssertEqual(BirthdayDates.next(day: 12, month: 3, birthYear: 1946, from: today), d(2027, 3, 12))
        XCTAssertEqual(BirthdayDates.next(day: 7, month: 3, birthYear: nil, from: today), today, "today counts")
        XCTAssertEqual(BirthdayDates.next(day: 6, month: 3, birthYear: nil, from: today), d(2028, 3, 6), "yesterday: next year")
        XCTAssertEqual(BirthdayDates.next(day: 1, month: 1, birthYear: nil, from: d(2026, 12, 31)), d(2027, 1, 1))
    }

    func testYearOfBirthIsNotABirthday() {
        // Lotte, born 2 June 2026: no birthday in 2026, the first is on 2 June 2027 (she turns 1).
        XCTAssertEqual(BirthdayDates.next(day: 2, month: 6, birthYear: 2026, from: d(2026, 6, 1)), d(2027, 6, 2))
        XCTAssertEqual(BirthdayDates.next(day: 2, month: 6, birthYear: 2026, from: d(2026, 6, 2)), d(2027, 6, 2))
        XCTAssertEqual(BirthdayDates.age(birthYear: 2026, on: d(2027, 6, 2)), 1)
        // Without the year every occurrence counts.
        XCTAssertEqual(BirthdayDates.next(day: 2, month: 6, birthYear: nil, from: d(2026, 6, 1)), d(2026, 6, 2))
    }

    func testAges() {
        XCTAssertEqual(BirthdayDates.age(birthYear: 1946, on: d(2026, 3, 12)), 80)
        XCTAssertNil(BirthdayDates.age(birthYear: nil, on: d(2026, 3, 12)))
    }
}

final class BirthdayListTests: XCTestCase {
    private let today = LocalDate(year: 2027, month: 3, day: 7)

    private func person(_ id: String, _ name: String, day: Int, month: Int, year: Int? = nil, status: String = "approved",
                        showAge: Bool = false, userId: String? = nil) throws -> BirthdayPerson {
        try BirthdayFixtures.decode(BirthdayPerson.self, BirthdayFixtures.person(id: id, name: name, day: day, month: month, year: year,
                                                                                 status: status, showAge: showAge, userId: userId))
    }

    func testSortedByNextBirthday() throws {
        let people = [
            try person("a", "Opa", day: 6, month: 3),        // yesterday: last
            try person("b", "Oma", day: 12, month: 3, year: 1946),
            try person("c", "Lotte", day: 7, month: 3),      // today: first
            try person("d", "Bram", day: 29, month: 2),      // 28 Feb 2028? no: 29 Feb 2028, after 12 March
            try person("e", "Anna", day: 12, month: 3),      // same day as Oma: by name
        ]
        XCTAssertEqual(BirthdayList.sorted(people, today: today).map(\.displayName), ["Lotte", "Anna", "Oma", "Bram", "Opa"])
    }

    func testLeapDaySortsOn28FebruaryInOtherYears() throws {
        let feb = LocalDate(year: 2027, month: 2, day: 20)
        let people = [try person("a", "Zoë", day: 1, month: 3), try person("b", "Bram", day: 29, month: 2), try person("c", "Cas", day: 28, month: 2)]
        // 2027 has no 29 February: Bram's birthday is 28 February, the same day as Cas (then by name).
        XCTAssertEqual(BirthdayList.sorted(people, today: feb).map(\.displayName), ["Bram", "Cas", "Zoë"])
        XCTAssertTrue(people[1].isToday(LocalDate(year: 2027, month: 2, day: 28)))
        XCTAssertFalse(people[1].isToday(LocalDate(year: 2028, month: 2, day: 28)))
    }

    func testSections() throws {
        let people = [
            try person("a", "Oma", day: 12, month: 3),
            try person("b", "Lotte", day: 2, month: 6, status: "pending"),
            try person("c", "Neef", day: 1, month: 1, status: "rejected"),
        ]
        let s = BirthdayList.sections(people, today: today)
        XCTAssertEqual(s.approved.map(\.id), ["a"])
        XCTAssertEqual(s.pending.map(\.id), ["b"])
        XCTAssertEqual(s.rejected.map(\.id), ["c"])
        XCTAssertFalse(people[2].isLive, "declined ones don't block a new suggestion")
    }

    func testAgeNeedsTheYear() throws {
        let oma = try person("a", "Oma", day: 12, month: 3, year: 1946)
        XCTAssertEqual(oma.nextAge(from: today), 81)
        XCTAssertEqual(oma.next(from: today), LocalDate(year: 2027, month: 3, day: 12))
        let hidden = try person("b", "Opa", day: 12, month: 3)
        XCTAssertNil(hidden.nextAge(from: today), "the server sends no year: no age")
    }

    func testAddMine() throws {
        let people = [try person("a", "Ben", day: 1, month: 5, userId: "u2"), try person("b", "Neef", day: 1, month: 1, status: "rejected", userId: "u3")]
        XCTAssertFalse(BirthdayList.canAddMine(people, me: "u2"))
        XCTAssertTrue(BirthdayList.canAddMine(people, me: "u3"), "a declined suggestion doesn't count")
        XCTAssertTrue(BirthdayList.canAddMine(people, me: "u4"))
        XCTAssertFalse(BirthdayList.canAddMine(people, me: nil))
    }

    func testDateText() {
        let en = Locale(identifier: "en_GB")
        XCTAssertEqual(BirthdayList.dateText(day: 12, month: 3, year: nil, locale: en), "12 March")
        XCTAssertEqual(BirthdayList.dateText(day: 29, month: 2, year: nil, locale: en), "29 February")
        XCTAssertEqual(BirthdayList.dateText(day: 12, month: 3, year: 1946, locale: en), "12 March 1946")
    }
}

final class BirthdayAbilityTests: XCTestCase {
    private func entry(status: String = "approved", userId: String? = nil, createdBy: String? = "Anna", canEdit: Bool? = nil,
                       canDecide: Bool? = nil) throws -> BirthdayPerson {
        try BirthdayFixtures.decode(BirthdayPerson.self, BirthdayFixtures.person(id: "b1", name: "Oma", day: 12, month: 3, status: status,
                                                                                 userId: userId, createdBy: createdBy, canEdit: canEdit, canDecide: canDecide))
    }

    func testAddOrSuggest() {
        XCTAssertTrue(BirthdayAbilities(role: .owner).addsDirectly)
        XCTAssertTrue(BirthdayAbilities(role: .admin).addsDirectly)
        XCTAssertTrue(BirthdayAbilities(role: .poster).suggests)
        XCTAssertTrue(BirthdayAbilities(role: .poster, postersCanAdd: true).addsDirectly)
        XCTAssertFalse(BirthdayAbilities(role: .poster, postersCanAdd: true).suggests)
        XCTAssertTrue(BirthdayAbilities(role: .admin).canChangeSetting)
        XCTAssertFalse(BirthdayAbilities(role: .poster, postersCanAdd: true).canChangeSetting)
    }

    func testDecideAndEditFollowTheServer() throws {
        let admin = BirthdayAbilities(role: .admin)
        let poster = BirthdayAbilities(role: .poster)
        XCTAssertTrue(admin.canDecide(try entry(status: "pending")))
        XCTAssertFalse(admin.canDecide(try entry(status: "approved")), "only pending ones")
        XCTAssertFalse(poster.canDecide(try entry(status: "pending")))
        XCTAssertFalse(admin.canDecide(try entry(status: "pending", canDecide: false)), "the server's flag wins")
        XCTAssertTrue(poster.canEdit(try entry(canEdit: true)))
        XCTAssertFalse(poster.canEdit(try entry()))
        XCTAssertTrue(admin.canEdit(try entry()))
    }

    func testEditFields() throws {
        let poster = BirthdayAbilities(role: .poster)
        let mine = try entry(userId: "u2", createdBy: "Anna", canEdit: true)
        XCTAssertEqual(poster.fields(mine, me: "u2", addedByMe: false), .privacy, "my own entry, added by someone else")
        XCTAssertEqual(BirthdayAbilities(role: .poster, postersCanAdd: true).fields(mine, me: "u2", addedByMe: true), .all)
        XCTAssertEqual(poster.fields(try entry(status: "pending", userId: "u2"), me: "u2", addedByMe: true), .all, "my own suggestion")
        XCTAssertEqual(BirthdayAbilities(role: .admin).fields(mine, me: "u2", addedByMe: false), .all)
        XCTAssertTrue(birthdayAddedBy(mine, me: "u1"), "Anna (u1) added it")
        XCTAssertFalse(birthdayAddedBy(mine, me: "u2"))
        XCTAssertFalse(birthdayAddedBy(try entry(createdBy: nil), me: "u1"), "the creator's account is gone")
        XCTAssertFalse(birthdayAddedBy(mine, me: nil))
    }

    func testYearVisibility() {
        XCTAssertTrue(BirthdayAbilities.yearVisible(role: .owner, isOwnEntry: false, addedByMe: false, showAge: false))
        XCTAssertTrue(BirthdayAbilities.yearVisible(role: .admin, isOwnEntry: false, addedByMe: false, showAge: false))
        XCTAssertFalse(BirthdayAbilities.yearVisible(role: .poster, isOwnEntry: false, addedByMe: false, showAge: false))
        XCTAssertTrue(BirthdayAbilities.yearVisible(role: .poster, isOwnEntry: true, addedByMe: false, showAge: false))
        XCTAssertTrue(BirthdayAbilities.yearVisible(role: .poster, isOwnEntry: false, addedByMe: true, showAge: false))
        XCTAssertTrue(BirthdayAbilities.yearVisible(role: .poster, isOwnEntry: false, addedByMe: false, showAge: true))
    }
}

final class BirthdayDraftTests: XCTestCase {
    private func json(_ value: some Encodable) throws -> String {
        let e = JSONEncoder()
        e.outputFormatting = .sortedKeys
        return String(decoding: try e.encode(value), as: UTF8.self)
    }

    func testProblems() {
        XCTAssertEqual(BirthdayDraft(name: "  ", day: 12, month: 3).problem(currentYear: 2026), .noName)
        XCTAssertEqual(BirthdayDraft(name: "Oma", day: 31, month: 4).problem(currentYear: 2026), .date(.invalidDay))
        XCTAssertEqual(BirthdayDraft(name: "Oma", day: 29, month: 2, year: 1947).problem(currentYear: 2026), .date(.invalidDay))
        XCTAssertEqual(BirthdayDraft(name: "Oma", day: 1, month: 1, year: 2030).problem(currentYear: 2026), .date(.invalidYear))
        XCTAssertNil(BirthdayDraft(name: "Oma", day: 12, month: 3, year: 1946).problem(currentYear: 2026))
        XCTAssertEqual(BirthdayDraft(name: String(repeating: "a", count: 50)).trimmedName.count, 40)
    }

    func testCreateBody() throws {
        let oma = BirthdayDraft(name: " Oma ", day: 12, month: 3, year: 1946, showAge: false)
        XCTAssertEqual(try json(oma.createBody), #"{"birth_year":1946,"day":12,"display_name":"Oma","month":3,"show_age":false}"#)
        // Show age without a year isn't sent as on.
        let noYear = BirthdayDraft(name: "Ben", day: 1, month: 5, showAge: true, userId: "u2")
        XCTAssertEqual(try json(noYear.createBody), #"{"day":1,"display_name":"Ben","month":5,"show_age":false,"user_id":"u2"}"#)
    }

    func testPatchSendsOnlyChangesWithExplicitNulls() throws {
        let original = BirthdayDraft(name: "Oma", day: 12, month: 3, year: 1946, showAge: true, userId: "u5")
        var edited = original
        XCTAssertEqual(edited.patch(from: original), [:])
        edited.year = nil
        edited.userId = nil
        XCTAssertEqual(try json(JSONValue.object(edited.patch(from: original))), #"{"birth_year":null,"show_age":false,"user_id":null}"#)
        edited = original
        edited.name = "Oma Jet"
        edited.day = 13
        XCTAssertEqual(try json(JSONValue.object(edited.patch(from: original))), #"{"day":13,"display_name":"Oma Jet"}"#)
    }

    func testPrivacyPatchKeepsToTheMembersFields() throws {
        let original = BirthdayDraft(name: "Ben", day: 1, month: 5, year: 1990, showAge: true, userId: "u2")
        var edited = original
        edited.name = "Benny"
        edited.showAge = false
        edited.year = nil
        edited.userId = nil
        XCTAssertEqual(try json(JSONValue.object(edited.patch(from: original, fields: .privacy))),
                       #"{"birth_year":null,"show_age":false,"user_id":null}"#)
        var relinked = original
        relinked.userId = "u9"
        XCTAssertEqual(relinked.patch(from: original, fields: .privacy), [:], "a member can only unlink")
    }
}

final class BirthdayGreetingAndPushTests: XCTestCase {
    func testGreetingsPerPersonAndDay() throws {
        let list = try BirthdayFixtures.decode([FeedBirthdayGreeting].self, "[\(BirthdayFixtures.greeting),\(BirthdayFixtures.greeting2),\(BirthdayFixtures.greetingOld)]")
        let today = try XCTUnwrap(LocalDate(iso: "2027-03-12"))
        let oma = BirthdayGreetings.forPerson(list, birthdayId: "b1", date: today)
        XCTAssertEqual(oma.map(\.frameName), ["Opa’s frame", "Kitchen"], "oldest first, last year's left out")
        let line = BirthdayGreetings.line(oma, zone: TimeZone(identifier: "Europe/Brussels")!, locale: Locale(identifier: "en_GB"))
        XCTAssertEqual(line, "Opa’s frame 10:14 · Kitchen 11:02")
        let merged = BirthdayGreetings.merge(list[0], into: [list[1]])
        XCTAssertEqual(merged.map(\.id), ["g2", "g1"], "newest first, no duplicates")
        XCTAssertEqual(BirthdayGreetings.merge(list[0], into: merged).count, 2)
    }

    func testPushRoutes() {
        XCTAssertEqual(PushRoute.parse(["key": "birthday_suggestion", "ids": ["feed_id": "feed1", "birthday_id": "b1"]]), .feed(feedId: "feed1", section: .birthdays))
        XCTAssertEqual(PushRoute.parse(["key": "birthday_decided", "ids": ["feed_id": "feed1", "birthday_id": "b1"]]), .feed(feedId: "feed1", section: .birthdays))
        XCTAssertEqual(PushRoute.parse(["key": "birthday_greeting", "feed_id": "feed1"]), .feed(feedId: "feed1", section: .birthdays))
        XCTAssertEqual(PushRoute.parse(["key": "birthday_today", "ids": ["feed_id": "feed1"]]), .feed(feedId: "feed1", section: .birthdays))
        XCTAssertEqual(PushRoute.parse(["key": "birthday_today"]), .feeds, "several feeds: the Feeds tab")
    }

    func testPushTags() {
        let received = LocalDate(year: 2027, month: 3, day: 12)
        XCTAssertEqual(BirthdayPush.tag(["key": "birthday_greeting", "ids": ["feed_id": "feed1", "birthday_id": "b1", "greeting_id": "g1"]], received: received),
                       "birthday:b1:2027-03-12")
        XCTAssertEqual(BirthdayPush.tag(["key": "birthday_greeting", "ids": ["birthday_id": "b1", "date": "2027-03-11"]], received: received),
                       "birthday:b1:2027-03-11", "the payload's date wins")
        XCTAssertEqual(BirthdayPush.tag(["key": "birthday_greeting", "tag": "birthday:b1:2027-03-11",
                                         "ids": ["birthday_id": "b1", "date": "2027-03-11", "frame_id": "f1", "greeting_id": "g1"]], received: received),
                       "birthday:b1:2027-03-11", "the server's tag")
        XCTAssertEqual(BirthdayPush.tag(["key": "birthday_today"], received: received), "birthday_today:2027-03-12")
        XCTAssertEqual(BirthdayPush.tag(["key": "birthday_today", "ids": ["date": "2027-03-11"]], received: received), "birthday_today:2027-03-11")
        XCTAssertNil(BirthdayPush.tag(["key": "birthday_suggestion", "ids": ["birthday_id": "b1"]], received: received))
        XCTAssertNil(BirthdayPush.tag(["key": "reaction"], received: received))
    }

    func testNewerGreetingReplacesTheOlder() {
        let t = Date(timeIntervalSince1970: 1_800_000_000)
        let delivered = [
            BirthdayPush.Delivered(id: "n1", tag: "birthday:b1:2027-03-12", date: t),
            BirthdayPush.Delivered(id: "n2", tag: "birthday:b1:2027-03-12", date: t.addingTimeInterval(60)),
            BirthdayPush.Delivered(id: "n3", tag: "birthday:b2:2027-03-12", date: t),
            BirthdayPush.Delivered(id: "n4", tag: nil, date: t),
            BirthdayPush.Delivered(id: "n5", tag: "birthday:b1:2027-03-12", date: t.addingTimeInterval(30)),
        ]
        XCTAssertEqual(Set(BirthdayPush.superseded(delivered)), ["n1", "n5"])
    }

    func testTimeZoneUpdate() {
        let brussels = TimeZone(identifier: "Europe/Brussels")!
        XCTAssertEqual(UserTimeZone.update(server: nil, device: brussels), "Europe/Brussels")
        XCTAssertEqual(UserTimeZone.update(server: "America/Toronto", device: brussels), "Europe/Brussels")
        XCTAssertNil(UserTimeZone.update(server: "Europe/Brussels", device: brussels))
    }

    func testLiveEvents() throws {
        func parse(_ s: String) -> LiveEvent? { LiveEvent.parse(Data(s.utf8)) }
        guard case .birthdaySuggested(let feed, let b)? = parse(#"{"type":"birthday.suggested","feed_id":"feed1","birthday":\#(BirthdayFixtures.oma)}"#) else {
            return XCTFail("suggested")
        }
        XCTAssertEqual(feed, "feed1")
        XCTAssertEqual(b?.displayName, "Oma")
        guard case .birthdayDecided(_, let d)? = parse(#"{"type":"birthday.decided","feed_id":"feed1","birthday":\#(BirthdayFixtures.oma)}"#) else {
            return XCTFail("decided")
        }
        XCTAssertEqual(d?.status, .approved)
        guard case .birthdayUpdated? = parse(#"{"type":"birthday.updated","feed_id":"feed1","birthday":\#(BirthdayFixtures.oma)}"#) else { return XCTFail("updated") }
        XCTAssertEqual(parse(#"{"type":"birthday.removed","feed_id":"feed1","birthday_id":"b1"}"#), .birthdayRemoved(feedId: "feed1", birthdayId: "b1"))
        XCTAssertEqual(parse(#"{"type":"birthday.today","date":"2027-03-12","birthdays":[{"feed_id":"feed1","birthday_id":"b1"},{"feed_id":"feed2"}]}"#),
                       .birthdayToday(date: "2027-03-12", birthdays: [.init(feedId: "feed1", birthdayId: "b1")]))
        guard case .birthdayGreeting(_, let g)? = parse(#"{"type":"birthday.greeting","feed_id":"feed1","greeting":\#(BirthdayFixtures.greeting)}"#) else {
            return XCTFail("greeting")
        }
        XCTAssertEqual(g?.frameName, "Opa’s frame")
        XCTAssertNil(parse(#"{"type":"birthday.removed","feed_id":"feed1"}"#))
    }
}

/// JSON shaped like the contract's schemas (docs/api/openapi.yaml examples: Oma, 12 March 1946, Opa's frame).
enum BirthdayFixtures {
    static let oma = """
    {"id":"b1","feed_id":"feed1","status":"approved","display_name":"Oma","day":12,"month":3,"birth_year":1946,"show_age":false,
     "photo_url":"https://kinframe.appmire.be/avatars/b/x7Kq.webp","has_own_photo":true,"user_id":null,"linked_member_name":null,
     "created_by_name":"Anna","created_by_id":"u1","suggested":false,"decided_by_name":null,"decided_at":null,"next_birthday":"2027-03-12","turns":81,
     "greetings_today":0,"can_edit":true,"can_decide":false,"created_at":"2026-11-19T08:00:00.123Z","updated_at":"2026-11-19T08:00:00Z"}
    """

    static let lotte = """
    {"id":"b2","feed_id":"feed1","status":"pending","display_name":"Lotte","day":2,"month":6,"birth_year":null,"show_age":false,
     "photo_url":null,"user_id":null,"created_by_name":"Carla","created_by_id":"u3","suggested":true,"next_birthday":"2027-06-02","turns":null,
     "can_edit":true,"can_decide":true,"created_at":"2026-11-19T08:00:00Z"}
    """

    static let greeting = """
    {"id":"g1","feed_id":"feed1","birthday_id":"b1","birthday_name":"Oma","frame_id":"f1","frame_name":"Opa’s frame",
     "frame_location":"Living room","date":"2027-03-12","created_at":"2027-03-12T09:14:00Z"}
    """

    static let greeting2 = """
    {"id":"g2","feed_id":"feed1","birthday_id":"b1","birthday_name":"Oma","frame_id":"f2","frame_name":"Kitchen",
     "frame_location":null,"date":"2027-03-12","created_at":"2027-03-12T10:02:00Z"}
    """

    static let greetingOld = """
    {"id":"g0","feed_id":"feed1","birthday_id":"b1","birthday_name":"Oma","frame_id":"f1","frame_name":"Opa’s frame",
     "date":"2026-03-12","created_at":"2026-03-12T09:00:00Z"}
    """

    static func person(id: String, name: String, day: Int, month: Int, year: Int? = nil, status: String = "approved",
                       showAge: Bool = false, userId: String? = nil, createdBy: String? = "Anna", canEdit: Bool? = nil,
                       canDecide: Bool? = nil) -> String {
        func opt<T>(_ v: T?) -> String { v.map { "\($0)" } ?? "null" }
        var fields = [
            #""id":"\#(id)""#, #""feed_id":"feed1""#, #""status":"\#(status)""#, #""display_name":"\#(name)""#,
            #""day":\#(day)"#, #""month":\#(month)"#, #""birth_year":\#(opt(year))"#, #""show_age":\#(showAge)"#,
            #""user_id":\#(userId.map { "\"\($0)\"" } ?? "null")"#, #""created_by_name":\#(createdBy.map { "\"\($0)\"" } ?? "null")"#,
            // The fixtures' creators: Anna is u1, Carla u3.
            #""created_by_id":\#(createdBy.map { "\"\($0 == "Anna" ? "u1" : "u3")\"" } ?? "null")"#,
            #""created_at":"2026-11-19T08:00:00Z""#,
        ]
        if let canEdit { fields.append(#""can_edit":\#(canEdit)"#) }
        if let canDecide { fields.append(#""can_decide":\#(canDecide)"#) }
        return "{" + fields.joined(separator: ",") + "}"
    }

    static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }
}

final class BirthdaySchemaTests: XCTestCase {
    func testBirthdayPerson() throws {
        let oma = try BirthdayFixtures.decode(BirthdayPerson.self, BirthdayFixtures.oma)
        XCTAssertEqual(oma.status, .approved)
        XCTAssertEqual(oma.birthYear, 1946)
        XCTAssertEqual(oma.turns, 81)
        XCTAssertEqual(oma.nextBirthday, "2027-03-12")
        XCTAssertEqual(oma.hasOwnPhoto, true)
        XCTAssertNil(oma.userId)
        XCTAssertEqual(oma.createdById, "u1")
        XCTAssertNotNil(oma.updatedAt)
        let lotte = try BirthdayFixtures.decode(BirthdayPerson.self, BirthdayFixtures.lotte)
        XCTAssertEqual(lotte.status, .pending)
        XCTAssertNil(lotte.birthYear)
        XCTAssertEqual(lotte.suggested, true)
        XCTAssertTrue(BirthdayAbilities(role: .admin).canDecide(lotte))
    }

    func testGreeting() throws {
        let g = try BirthdayFixtures.decode(FeedBirthdayGreeting.self, BirthdayFixtures.greeting)
        XCTAssertEqual(g.birthdayName, "Oma")
        XCTAssertEqual(g.frameLocation, "Living room")
        XCTAssertEqual(LocalDate(iso: g.date), LocalDate(year: 2027, month: 3, day: 12))
        let page = try BirthdayFixtures.decode(Operations.ListFeedBirthdayGreetings.Output.Ok.Body.JsonPayload.self,
                                               #"{"greetings":[\#(BirthdayFixtures.greeting)],"next_cursor":null}"#)
        XCTAssertEqual(page.greetings.count, 1)
    }

    func testFeedSubscriptionAndUser() throws {
        let feed = try BirthdayFixtures.decode(Feed.self, SchemaFixtures.feed.replacingOccurrences(
            of: #""post_count":"#, with: #""posters_can_add_birthdays":true,"birthday_notifications":false,"birthday_count":12,"pending_birthday_count":2,"post_count":"#))
        XCTAssertEqual(feed.value2.postersCanAddBirthdays, true)
        XCTAssertEqual(feed.value2.birthdayNotifications, false)
        XCTAssertEqual(feed.value2.birthdayCount, 12)
        XCTAssertEqual(feed.value2.pendingBirthdayCount, 2)
        XCTAssertTrue(BirthdayAbilities(feed).addsDirectly)

        let sub = try BirthdayFixtures.decode(FeedSubscription.self, SchemaFixtures.subscription.replacingOccurrences(
            of: #""status":"pending""#, with: #""status":"active","birthdays":false,"birthday_count":12"#))
        XCTAssertEqual(sub.birthdays, false)
        XCTAssertEqual(sub.birthdayCount, 12)
        let supported = try BirthdayFixtures.decode(FeedSubscription.self, SchemaFixtures.subscription.replacingOccurrences(
            of: #""online":true"#, with: #""online":true,"birthdays_supported":true"#))
        XCTAssertEqual(supported.frame.birthdaysSupported, true)

        let user = try BirthdayFixtures.decode(User.self, #"{"id":"u1","display_name":"Anna","timezone":"Europe/Brussels"}"#)
        XCTAssertEqual(user.timezone, "Europe/Brussels")
    }

    func testRequestBodies() throws {
        let e = JSONEncoder()
        e.outputFormatting = .sortedKeys
        func s(_ v: some Encodable) throws -> String { String(decoding: try e.encode(v), as: UTF8.self) }
        XCTAssertEqual(try s(Components.Schemas.SubscriptionUpdate(birthdays: false)), #"{"birthdays":false}"#, "history is optional now")
        XCTAssertEqual(try s(Components.Schemas.SubscriptionRequest(frameId: "f1", history: HistoryChoice.none.history, birthdays: false)),
                       #"{"birthdays":false,"frame_id":"f1","history":"none"}"#)
        XCTAssertEqual(try s(Components.Schemas.FeedPreferencesUpdate(birthdayNotifications: false)), #"{"birthday_notifications":false}"#)
        XCTAssertEqual(try s(Components.Schemas.FeedUpdate(postersCanAddBirthdays: true)), #"{"posters_can_add_birthdays":true}"#)
    }

    func testDiscoveryAndLimits() throws {
        let on = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1","feeds":true,"birthdays":true,"limits":{"max_items_per_send":200,"max_video_seconds":300,"max_upload_bytes":1,"max_birthdays_per_feed":50}}"#.utf8))
        XCTAssertTrue(on.birthdays)
        XCTAssertEqual(on.limits.maxBirthdaysPerFeed, 50)
        let withoutFeeds = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1","birthdays":true}"#.utf8))
        XCTAssertFalse(withoutFeeds.birthdays, "only together with feeds")
        let old = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1","feeds":true}"#.utf8))
        XCTAssertFalse(old.birthdays)
        XCTAssertEqual(old.limits.maxBirthdaysPerFeed, 200)
        // The stored copy keeps the flag.
        let again = try JSONDecoder().decode(Discovery.self, from: JSONEncoder().encode(on))
        XCTAssertTrue(again.birthdays)
    }

    func testDuplicateProblem() {
        let e = APIError.from(status: 409, body: Data(#"{"code":"duplicate_birthday","title":"Duplicate","detail":"b1"}"#.utf8))
        XCTAssertEqual(e.code, "duplicate_birthday")
        XCTAssertEqual(e.detail, "b1")
    }
}
