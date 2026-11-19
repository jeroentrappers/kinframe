@testable import Kinframe
@testable import KinframeKit
import XCTest

// Leaving a family (README §5.21): the leave sheet's defaults, result and group texts, keep request texts, the
// "former member" credit, read-only chats, push routes and websocket events.

final class LeavingTests: XCTestCase {
    override func setUp() {
        super.setUp()
        L10n.setLanguage(.en)
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }

    private let en = Locale(identifier: "en")

    func testLeaveChoicesDefaults() throws {
        let preview = try decode(LeavePreview.self, """
        {"scope":"feed","feed_id":"f1","name":"Janssens","member":true,"photos":214,"comments":38,"frames":[
          {"frame_id":"a","frame_name":"Oma's frame","role":"member","suggested":true,"can_leave":true},
          {"frame_id":"b","frame_name":"Kitchen","role":"admin","suggested":false},
          {"frame_id":"c","frame_name":"Mine","role":"owner","suggested":false,"can_leave":false}]}
        """)
        var c = LeaveChoices(preview: preview)
        XCTAssertEqual(c.photos, .keep, "photos stay by default (decision A)")
        XCTAssertEqual(c.comments, .keep)
        XCTAssertEqual(c.leaveFrameIds, ["a"], "only suggested frames are checked")
        XCTAssertEqual(LeaveChoices.leavableFrames(preview).map(\.frameId), ["a", "b"], "owned frames aren't offered")
        c.leaveFrameIds.insert("c")
        XCTAssertEqual(c.frameIds(for: preview), ["a"], "a frame that isn't offered never goes into the request")
    }

    func testResultLine() throws {
        let r = try decode(TakeAwayResult.self, """
        {"taking_id":"t1","photos_taken_away":214,"comments_deleted":0,"until":"2026-12-19T10:00:00Z",
         "frames":[{"frame_id":"a","frame_name":"Oma","photos":200},{"frame_id":"b","frame_name":"Kitchen","photos":14}],"remote_frames":1}
        """)
        XCTAssertEqual(LeavingText.result(r, locale: en), "214 photos were taken away from 3 frame(s). They stay in the frames’ trash until December 19.")
        let none = try decode(TakeAwayResult.self, #"{"photos_taken_away":0,"comments_deleted":0,"frames":[]}"#)
        XCTAssertNil(LeavingText.result(none))
    }

    func testGroupTexts() throws {
        let blocked = try decode(TakenAwayGroup.self, """
        {"taking_id":"t1","by_name":"Tom","taken_at":"2026-11-19T10:00:00Z","until":"2026-12-19T10:00:00Z","count":12,
         "can_ask":false,"cannot_ask_reason":"blocked"}
        """)
        XCTAssertEqual(LeavingText.groupTitle(blocked), "Taken away by Tom")
        XCTAssertEqual(LeavingText.groupDetail(blocked, locale: en), "12 photos · until December 19")
        XCTAssertEqual(LeavingText.cannotAsk(blocked), "Tom doesn’t accept requests")
        let open = try decode(TakenAwayGroup.self, """
        {"taking_id":"t1","by_name":"Tom","taken_at":"2026-11-19T10:00:00Z","until":"2026-12-19T10:00:00Z","count":1,"can_ask":true}
        """)
        XCTAssertNil(LeavingText.cannotAsk(open))
    }

    func testKeepRequestTexts() throws {
        let r = try decode(KeepRequest.self, """
        {"id":"k1","status":"partly_allowed","scope":"frame","frame_id":"a","frame_name":"Oma Janssens's frame","taking_id":"t1",
         "asked_by_name":"Lies","asked_at":"2026-11-19T10:00:00Z","expires_at":"2026-12-19T10:00:00Z","item_count":12,"allowed_count":5}
        """)
        XCTAssertEqual(LeavingText.requestToMe(r), "Lies asks to keep 12 photos on Oma Janssens's frame.")
        XCTAssertEqual(LeavingText.requestStatus(r, person: "Tom"), "Tom let 5 of 12 stay.")
    }

    func testFormerMemberCreditAndReadOnlyChat() throws {
        let item = try decode(Item.self, """
        {"id":"i1","kind":"photo","status":"ready","sender_id":"u9","created_at":"2026-11-19T10:00:00Z","former_sender_name":"Tom"}
        """)
        XCTAssertEqual(LeavingText.formerSender(item), "Tom (former member)")
        let c = try decode(Conversation.self, ChatFixtures.conversation(extra: #","read_only":true,"read_only_reason":"no_longer_contacts""#))
        XCTAssertEqual(LeavingText.readOnlyLine(c), "You can no longer message Anna.")
        let blocked = try decode(Conversation.self, ChatFixtures.conversation(extra: #","read_only":true,"read_only_reason":"blocked""#))
        XCTAssertNil(LeavingText.readOnlyLine(blocked))
    }

    func testPushRoutes() {
        XCTAssertEqual(PushRoute.parse(["key": "photos_taken_away", "ids": ["taking_id": "t1", "frame_id": "a"]]),
                       .leaving(.takenAway(frameId: "a", feedId: nil, takingId: "t1")))
        XCTAssertEqual(PushRoute.parse(["key": "keep_request", "ids": ["request_id": "k1"]]), .leaving(.keepRequest(requestId: "k1")))
        XCTAssertEqual(PushRoute.parse(["key": "keep_answered", "ids": ["request_id": "k1", "feed_id": "f1"]]),
                       .leaving(.keepAnswered(requestId: "k1", frameId: nil, feedId: "f1")))
        XCTAssertEqual(PushRoute.parse(["key": "ownership_changed", "ids": ["feed_id": "f1"]]), .leaving(.ownership(frameId: nil, feedId: "f1")))
        XCTAssertNil(PushRoute.parse(["key": "photos_taken_away", "ids": ["taking_id": "t1"]]))
    }

    func testLiveEvents() throws {
        let placement = Data(#"{"type":"placement.updated","frame_id":"a","placement_ids":["p1","p2"],"reason":"taken_away","taking_id":"t1"}"#.utf8)
        XCTAssertEqual(LiveEvent.parse(placement), .leaving(.placementUpdated(frameId: "a", placementIds: ["p1", "p2"], reason: .takenAway, takingId: "t1")))
        let keep = Data(#"{"type":"keep_request.updated","request":{"id":"k1","status":"open","scope":"feed","feed_id":"f1","taking_id":"t1","asked_by_name":"Lies","asked_at":"2026-11-19T10:00:00Z","expires_at":"2026-12-19T10:00:00Z","item_count":3}}"#.utf8)
        guard case .leaving(.keepRequestUpdated(let r))? = LiveEvent.parse(keep) else { return XCTFail("keep_request.updated not parsed") }
        XCTAssertEqual(r.id, "k1")
        XCTAssertEqual(r.scope, .feed)
    }
}
