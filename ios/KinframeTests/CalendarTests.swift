@testable import Kinframe
@testable import KinframeKit
import XCTest

final class CalendarLogicTests: XCTestCase {
    let tz = "Europe/Brussels"

    private func date(_ iso: String) -> Date {
        FlexibleDateTranscoder.parse(iso)!
    }

    private func timed(_ id: String, _ start: String, _ end: String?, title: String = "x") -> CalendarEvent {
        CalendarEvent(id: id, sourceId: "s", title: title, allDay: false, startAt: date(start), endAt: end.map(date))
    }

    private func allDay(_ id: String, _ start: String, _ end: String? = nil, title: String = "x") -> CalendarEvent {
        CalendarEvent(id: id, sourceId: "s", title: title, allDay: true, startDate: start, endDate: end)
    }

    func testPlainDates() {
        XCTAssertEqual(CalendarLogic.addDays("2026-02-27", 2), "2026-03-01")
        XCTAssertEqual(CalendarLogic.addDays("2026-01-01", -1), "2025-12-31")
        XCTAssertEqual(CalendarLogic.daysBetween("2026-10-24", "2026-10-26"), 2, "across the DST change")
        XCTAssertEqual(CalendarLogic.weekdayIndex("2026-11-03"), 1, "a Tuesday")
        XCTAssertEqual(CalendarLogic.startOfWeek("2026-11-03"), "2026-11-02")
        XCTAssertEqual(CalendarLogic.startOfWeek("2026-11-03", first: .sun), "2026-11-01")
        XCTAssertEqual(CalendarLogic.startOfWeek("2026-11-03", first: .sat), "2026-10-31")
        XCTAssertEqual(CalendarLogic.isoWeek("2026-01-01"), 1)
        XCTAssertEqual(CalendarLogic.isoWeek("2027-01-01"), 53)
        XCTAssertTrue(CalendarLogic.isPlainDate("2026-11-03"))
        XCTAssertFalse(CalendarLogic.isPlainDate("2026-11-3"))
    }

    func testMonthGridCoversWholeWeeks() {
        let weeks = CalendarLogic.monthGrid(year: 2026, month: 11)
        XCTAssertEqual(weeks.first?.first, "2026-10-26")
        XCTAssertEqual(weeks.last?.last, "2026-12-06")
        XCTAssertEqual(weeks.count, 6)
        XCTAssertTrue(weeks.allSatisfy { $0.count == 7 })
        XCTAssertEqual(CalendarLogic.monthGrid(year: 2026, month: 2, first: .sun).first?.first, "2026-02-01")
        XCTAssertEqual(CalendarLogic.weekdayOrder(.sun).first, "sun")
        XCTAssertEqual(CalendarLogic.weekdayOrder(.sat), ["sat", "sun", "mon", "tue", "wed", "thu", "fri"])
    }

    func testSpanInTheFramesTimeZone() {
        // 23:30 UTC is already the next day in Brussels.
        let late = timed("a", "2026-11-03T23:30:00Z", "2026-11-04T00:30:00Z")
        XCTAssertEqual(CalendarLogic.span(late, tz).0, "2026-11-04")
        // Ending exactly at midnight doesn't touch the next day.
        let toMidnight = timed("b", "2026-11-03T21:00:00Z", "2026-11-03T23:00:00Z")
        XCTAssertEqual(CalendarLogic.span(toMidnight, tz).1, "2026-11-03")
        XCTAssertFalse(CalendarLogic.isMultiDay(toMidnight, tz))
        let trip = allDay("c", "2026-11-05", "2026-11-08")
        XCTAssertEqual(CalendarLogic.span(trip, tz).1, "2026-11-08")
        XCTAssertTrue(CalendarLogic.isMultiDay(trip, tz))
        XCTAssertEqual(CalendarLogic.span(allDay("d", "2026-11-05", "2026-11-01"), tz).1, "2026-11-05", "an end before the start")
    }

    func testDaysSortAllDayFirstThenByStart() {
        let events = [
            timed("late", "2026-11-03T15:00:00Z", "2026-11-03T16:00:00Z", title: "Late"),
            timed("early", "2026-11-03T08:00:00Z", "2026-11-03T09:00:00Z", title: "Early"),
            allDay("all", "2026-11-03", title: "Birthday"),
            allDay("trip", "2026-11-02", "2026-11-04", title: "Trip"),
        ]
        let byDay = CalendarLogic.byDay(events, tz, from: "2026-11-01", to: "2026-11-10")
        XCTAssertEqual(byDay["2026-11-03"]?.map(\.id), ["trip", "all", "early", "late"])
        XCTAssertEqual(byDay["2026-11-02"]?.map(\.id), ["trip"])
        XCTAssertNil(byDay["2026-11-05"])
    }

    func testAgendaShowsTodayAndMultiDayEventsOnce() {
        let events = [allDay("trip", "2026-11-02", "2026-11-06"), timed("t", "2026-11-05T10:00:00Z", "2026-11-05T11:00:00Z")]
        let byDay = CalendarLogic.byDay(events, tz, from: "2026-10-27", to: "2026-11-30")
        let agenda = CalendarLogic.agenda(byDay, today: "2026-11-03", until: "2026-11-30", tz: tz)
        XCTAssertEqual(agenda.map(\.day), ["2026-11-03", "2026-11-05"])
        XCTAssertEqual(agenda[0].events.map(\.id), ["trip"], "a trip that started earlier shows on today")
        XCTAssertEqual(agenda[1].events.map(\.id), ["t"])
        let empty = CalendarLogic.agenda([:], today: "2026-11-03", until: "2026-11-30", tz: tz)
        XCTAssertEqual(empty.map(\.day), ["2026-11-03"], "today is always there")
    }

    func testPastAndCountdowns() {
        let now = date("2026-11-03T12:00:00Z")
        XCTAssertTrue(CalendarLogic.isPast(timed("a", "2026-11-03T08:00:00Z", "2026-11-03T09:00:00Z"), tz, now: now))
        XCTAssertFalse(CalendarLogic.isPast(allDay("b", "2026-11-03"), tz, now: now))
        XCTAssertTrue(CalendarLogic.isPast(allDay("c", "2026-11-02"), tz, now: now))
        XCTAssertEqual(CalendarLogic.daysUntil(allDay: true, startAt: nil, startDate: "2026-12-25", tz, now: now), 52)
        XCTAssertEqual(CalendarLogic.daysUntil(allDay: false, startAt: date("2026-11-03T22:30:00Z"), startDate: nil, tz, now: now), 0)
        XCTAssertEqual(CalendarLogic.daysUntil(allDay: false, startAt: date("2026-11-03T23:30:00Z"), startDate: nil, tz, now: now), 1, "tomorrow in Brussels")
    }

    func testWeather() {
        XCTAssertEqual(CalendarLogic.weatherKind(0), .clear)
        XCTAssertEqual(CalendarLogic.weatherKind(2), .partly)
        XCTAssertEqual(CalendarLogic.weatherKind(45), .fog)
        XCTAssertEqual(CalendarLogic.weatherKind(53), .drizzle)
        XCTAssertEqual(CalendarLogic.weatherKind(63), .rain)
        XCTAssertEqual(CalendarLogic.weatherKind(81), .showers)
        XCTAssertEqual(CalendarLogic.weatherKind(75), .snow)
        XCTAssertEqual(CalendarLogic.weatherKind(96), .storm)
        XCTAssertEqual(CalendarLogic.temperature(21.6), "22°")
        XCTAssertEqual(CalendarLogic.temperature(20, unit: "f"), "68°")
    }

    func testColours() {
        XCTAssertEqual(CalendarLogic.nextColor([]), .coral)
        XCTAssertEqual(CalendarLogic.nextColor([.coral, .amber]), .tangerine)
        XCTAssertEqual(CalendarLogic.nextColor(CalendarLogic.colors), .coral, "cycles when all are taken")
        XCTAssertEqual(CalendarLogic.palette.count, 12)
    }

    func testCalendarAddresses() {
        XCTAssertTrue(CalendarLogic.isCalendarURL("https://calendar.google.com/calendar/ical/x/basic.ics"))
        XCTAssertTrue(CalendarLogic.isCalendarURL("webcal://p01-caldav.icloud.com/published/2/abc"))
        XCTAssertTrue(CalendarLogic.isCalendarURL(" https://cloud.example.org "))
        XCTAssertFalse(CalendarLogic.isCalendarURL("calendar.google.com/x.ics"))
        XCTAssertFalse(CalendarLogic.isCalendarURL("ftp://x.org/a.ics"))
        XCTAssertFalse(CalendarLogic.isCalendarURL("https:// spaces.org"))
    }

    func testInvitationForm() {
        var f = CalendarLogic.EventForm()
        XCTAssertEqual(try? CalendarLogic.invitation(f).get(), nil)
        if case .failure(let e) = CalendarLogic.invitation(f) { XCTAssertEqual(e.error, .title) }
        f.title = "  Sunday lunch  "
        f.start = date("2026-11-08T11:00:00Z")
        f.end = date("2026-11-08T10:00:00Z")
        if case .failure(let e) = CalendarLogic.invitation(f) { XCTAssertEqual(e.error, .order) } else { XCTFail("end before start") }
        f.end = date("2026-11-08T14:00:00Z")
        f.location = " Ours "
        let timedBody = try? CalendarLogic.invitation(f).get()
        XCTAssertEqual(timedBody?.title, "Sunday lunch")
        XCTAssertEqual(timedBody?.allDay, false)
        XCTAssertEqual(timedBody?.startAt, date("2026-11-08T11:00:00Z"))
        XCTAssertEqual(timedBody?.location, "Ours")
        XCTAssertNil(timedBody?.notes)

        f.allDay = true
        f.startDate = "2026-12-24"
        f.endDate = ""
        let day = try? CalendarLogic.invitation(f).get()
        XCTAssertEqual(day?.startDate, "2026-12-24")
        XCTAssertEqual(day?.endDate, "2026-12-24", "one day when the end is empty")
        XCTAssertNil(day?.startAt)
        f.endDate = "2026-12-20"
        if case .failure(let e) = CalendarLogic.invitation(f) { XCTAssertEqual(e.error, .order) } else { XCTFail("until before from") }
        f.startDate = "24/12"
        if case .failure(let e) = CalendarLogic.invitation(f) { XCTAssertEqual(e.error, .start) } else { XCTFail("bad date") }
    }

    func testDefaultEventTimes() {
        var cal = Foundation.Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: tz)!
        let d = CalendarLogic.defaultEventTimes(now: date("2026-11-03T09:20:00Z"), calendar: cal)
        XCTAssertEqual(d.start, date("2026-11-03T10:00:00Z"), "the next full hour")
        XCTAssertEqual(d.end.timeIntervalSince(d.start), 3600)
        XCTAssertEqual(d.date, "2026-11-03")
        XCTAssertEqual(CalendarLogic.plainDate(CalendarLogic.pickerDate("2026-03-29", calendar: cal), calendar: cal), "2026-03-29")
    }

    func testTimedViewDefault() {
        let v = CalendarLogic.newTimedView(id: "t1")
        XCTAssertEqual(v.from, "07:00")
        XCTAssertEqual(v.to, "09:00")
        XCTAssertEqual(v.days.count, 7)
        XCTAssertTrue(v.enabled)
    }

    func testInvitationDecidedEvent() throws {
        let json = """
        {"type":"event_invitation.decided","frame_id":"f1","invitation":{"id":"i1","frame_id":"f1","sender_id":"u1","sender_name":"Jeroen",
        "status":"accepted","title":"Lunch","all_day":true,"start_date":"2026-11-08","end_date":"2026-11-08","has_photo":false,
        "created_at":"2026-11-03T10:00:00Z","decided_at":"2026-11-03T10:05:00.123Z"}}
        """
        guard case .invitationDecided(let frame, let inv)? = LiveEvent.parse(Data(json.utf8)) else { return XCTFail("not parsed") }
        XCTAssertEqual(frame, "f1")
        XCTAssertEqual(inv.status, .accepted)
        XCTAssertEqual(inv.title, "Lunch")
        XCTAssertNil(LiveEvent.parse(Data(#"{"type":"event_invitation.decided","frame_id":"f1"}"#.utf8)))
    }
}

final class PhotoUploadTests: XCTestCase {
    func testEventPhotoIsPreparedBeforeItGoesUp() async throws {
        let fake = FakeServer()
        let api = API(baseURL: URL(string: "https://k.example.org")!, tokens: StubTokens())
        let src = FileManager.default.temporaryDirectory.appending(path: "event-\(UUID().uuidString).heic")
        try Data(repeating: 3, count: 5000).write(to: src)
        defer { try? FileManager.default.removeItem(at: src) }
        let id = try await PhotoUpload.upload(src, filename: "IMG_1.heic", api: api, preparer: FakePreparer(), session: fake.session)
        XCTAssertEqual(id, "u1")
        XCTAssertEqual(fake.state.withLock { $0.uploads["u1"]?.data }, Data(repeating: 7, count: 300), "the master, not the original")
        XCTAssertEqual(fake.state.withLock { $0.metadata.first?["prepared"] }, PreparedProfile.photo)
        XCTAssertEqual(fake.state.withLock { $0.metadata.first?["filename"] }, "IMG_1.jpg")
    }

    func testEventPhotoTooLargeToGoAsItIsFails() async throws {
        let fake = FakeServer()
        let api = API(baseURL: URL(string: "https://k.example.org")!, tokens: StubTokens())
        let src = FileManager.default.temporaryDirectory.appending(path: "event-\(UUID().uuidString).jpg")
        try Data(repeating: 3, count: 500).write(to: src)
        defer { try? FileManager.default.removeItem(at: src) }
        do {
            _ = try await PhotoUpload.upload(src, filename: "a.jpg", api: api, preparer: AsIsPreparer(within: false), session: fake.session)
            XCTFail("uploaded an unprepared original")
        } catch let e as APIError {
            XCTAssertEqual(e.code, "media_unprepared")
        }
        XCTAssertEqual(fake.log, [])
    }
}

@MainActor
final class FrameCalendarModelTests: XCTestCase {
    let source = #"{"id":"c1","kind":"ics","name":"Gezin","color":"sky","enabled":true,"status":"ok","event_count":3,"url_hint":"calendar.google.com/…"}"#
    let view = #"{"generated_at":"2026-11-03T10:00:00Z","timezone":"Europe/Brussels","window":{"from":"2026-10-04","to":"2026-12-03"},"sources":[{"id":"c1","kind":"ics","name":"Gezin","color":"sky","status":"ok"}],"events":[{"id":"e1","source_id":"c1","title":"Lunch","all_day":true,"start_date":"2026-11-08","end_date":"2026-11-08"}],"countdowns":[],"weather":null}"#
    let invitation = #"{"id":"i1","frame_id":"f1","sender_id":"me","sender_name":"Jeroen","status":"pending","title":"Lunch","all_day":true,"start_date":"2026-11-08","has_photo":false,"created_at":"2026-11-03T10:00:00Z"}"#

    func frame(role: Role, send: Bool = true, settings: Bool = false) -> FrameSummary {
        FrameSummary(id: "f1", name: "Oma", role: role, permissions: Permissions(send: send, changeSettings: settings), online: true)
    }

    func fake() -> FakeAPI {
        FakeAPI([
            FakeAPI.on("GET", "/v1/frames/f1/calendars") { _ in FakeAPI.json("[\(self.source)]") },
            FakeAPI.on("GET", "/v1/frames/f1/calendar") { _ in FakeAPI.json(self.view) },
            FakeAPI.on("GET", "/v1/frames/f1/events") { _ in FakeAPI.json("[\(self.invitation)]") },
            FakeAPI.on("PATCH", "/v1/frames/f1/calendars/c1") { _ in FakeAPI.json(self.source.replacingOccurrences(of: #""enabled":true"#, with: #""enabled":false"#)) },
            FakeAPI.on("PUT", "/v1/frames/f1/countdowns/e1") { _ in .response(status: 204) },
            FakeAPI.on("DELETE", "/v1/frames/f1/events/i1") { _ in .response(status: 204) },
        ])
    }

    func testOwnerLoadsEverything() async throws {
        let f = fake()
        let model = FrameCalendarModel(api: f.api, frame: frame(role: .owner))
        await model.load()
        XCTAssertNil(model.error)
        XCTAssertEqual(model.sources?.map(\.id), ["c1"])
        XCTAssertEqual(model.view?.value1.events.first?.title, "Lunch")
        XCTAssertEqual(model.invitations?.map(\.id), ["i1"])
        XCTAssertEqual(model.label(sourceId: "c1"), "Gezin")

        try await model.setEnabled(model.sources![0], false)
        XCTAssertEqual(model.sources?.first?.enabled, false)
        let patch = try XCTUnwrap(f.requests.first { $0.method == "PATCH" })
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: patch.body), .object(["enabled": .bool(false)]))

        try await model.setCountdown(model.view!.value1.events[0], true)
        XCTAssertTrue(f.requests.contains { $0.method == "PUT" && $0.url.path == "/v1/frames/f1/countdowns/e1" })

        XCTAssertTrue(model.canWithdraw(model.invitations![0], me: "someone-else"), "the owner may withdraw anyone's")
        try await model.withdraw(model.invitations![0])
        XCTAssertEqual(model.invitations?.first?.status, .withdrawn)
    }

    func testMemberWhoMaySendOnlySeesSentEvents() async {
        let f = fake()
        let model = FrameCalendarModel(api: f.api, frame: frame(role: .member))
        await model.load()
        XCTAssertNil(model.sources)
        XCTAssertNil(model.view)
        XCTAssertEqual(model.invitations?.count, 1)
        XCTAssertFalse(f.requests.contains { $0.url.path.hasSuffix("/calendars") })
        XCTAssertTrue(model.canWithdraw(model.invitations![0], me: "me"))
        XCTAssertFalse(model.canWithdraw(model.invitations![0], me: "other"))
    }

    func testDecidedInvitationUpdatesTheList() async throws {
        let f = fake()
        let model = FrameCalendarModel(api: f.api, frame: frame(role: .member))
        await model.load()
        withExtendedLifetime(f) {}
        var inv = model.invitations![0]
        inv.status = .declined
        XCTAssertEqual(model.apply(.invitationDecided(frameId: "f1", invitation: inv)), L("cal.decidedDeclined", ["frame": "Oma", "title": "Lunch"]))
        XCTAssertEqual(model.invitations?.first?.status, .declined)
        XCTAssertNil(model.apply(.invitationDecided(frameId: "other", invitation: inv)))
    }
}
