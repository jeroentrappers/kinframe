@testable import KinframeKit
import XCTest

/// The simple family app's logic (docs/spec/simple-app.md): the current family, the view mode, wording.
final class SimpleAppTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "SimpleAppTests"

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
        L10n.setLanguage(.en)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    // MARK: Home family and filter (§3)

    private func c(_ id: String, _ account: String = "a") -> FamilyChoice { FamilyChoice(account: account, feedId: id) }

    func testTheHomeFamilyIsTheOneYouPostToMost() {
        let new = Date()
        XCTAssertEqual(HomeFamily.pick([
            .init(choice: c("trappers"), posts: 3, isOwner: false, since: new),
            .init(choice: c("opa"), posts: 9, isOwner: false, since: new),
        ]), c("opa"))
    }

    func testTiesGoToTheFamilyYouMadeThenTheOldest() {
        let old = Date(timeIntervalSince1970: 0), new = Date()
        XCTAssertEqual(HomeFamily.pick([
            .init(choice: c("sister"), posts: 0, isOwner: false, since: old),
            .init(choice: c("mine"), posts: 0, isOwner: true, since: new),
        ]), c("mine"))
        XCTAssertEqual(HomeFamily.pick([
            .init(choice: c("new"), posts: 1, isOwner: false, since: new),
            .init(choice: c("old"), posts: 1, isOwner: false, since: old),
        ]), c("old"))
        XCTAssertNil(HomeFamily.pick([]))
    }

    func testTheHomeFamilyIsListedFirst() {
        let list = [c("a"), c("b"), c("c")]
        XCTAssertEqual(HomeFamily.ordered(list, home: c("c")) { $0 }, [c("c"), c("a"), c("b")])
        XCTAssertEqual(HomeFamily.ordered(list, home: nil) { $0 }, list)
    }

    func testPostsAreCountedOnTheDevice() {
        FamilyPostCounts.posted([c("a"), c("b")], defaults)
        FamilyPostCounts.posted([c("a")], defaults)
        var counts = FamilyPostCounts.load(defaults)
        XCTAssertEqual(FamilyPostCounts.count(c("a"), in: counts), 2)
        FamilyPostCounts.observed([c("a"): 1, c("b"): 5], defaults)
        counts = FamilyPostCounts.load(defaults)
        XCTAssertEqual(FamilyPostCounts.count(c("a"), in: counts), 2, "never fewer")
        XCTAssertEqual(FamilyPostCounts.count(c("b"), in: counts), 5)
    }

    func testTheHomeFilterIsAFamilyYouAreStillIn() {
        let all = [c("a"), c("b")]
        XCTAssertEqual(HomeFilter.resolve(stored: c("b"), available: all), c("b"))
        XCTAssertNil(HomeFilter.resolve(stored: c("gone"), available: all), "a family you left: All")
        XCTAssertNil(HomeFilter.resolve(stored: c("a"), available: [c("a")]), "one family: no chips")
        XCTAssertNil(HomeFilter.resolve(stored: c("a", "other"), available: all), "same id on another server")
        HomeFilter.save(c("b"), defaults)
        XCTAssertEqual(HomeFilter.load(defaults), c("b"))
        HomeFilter.save(nil, defaults)
        XCTAssertNil(HomeFilter.load(defaults))
    }

    // MARK: Composer (§3.2)

    func testSeveralFamiliesCanBeTicked() {
        var d = ComposeDestination.families([.init(c("trappers"))])
        d = d.toggling(c("opa"))
        XCTAssertEqual(d, .families([.init(c("trappers")), .init(c("opa"))]))
        d = d.with(circle: "cousins", for: c("opa"))
        XCTAssertEqual(d, .families([.init(c("trappers")), .init(c("opa"), circleId: "cousins")]))
        d = d.toggling(c("trappers"))
        XCTAssertEqual(d, .families([.init(c("opa"), circleId: "cousins")]))
        XCTAssertEqual(d.toggling(c("opa")), d, "the last family stays")
    }

    // MARK: Simple view (§6)

    func testViewModeIsNormalUntilChosenAndAskedOnce() {
        XCTAssertEqual(ViewMode.load(defaults), .normal)
        XCTAssertFalse(ViewMode.asked(defaults))
        ViewMode.save(.simple, defaults)
        XCTAssertEqual(ViewMode.load(defaults), .simple)
        XCTAssertTrue(ViewMode.asked(defaults))
        ViewMode.save(.normal, defaults)
        XCTAssertEqual(ViewMode.load(defaults), .normal)
        XCTAssertTrue(ViewMode.asked(defaults))
    }

    func testNextAndPreviousStayInsideTheList() {
        XCTAssertEqual(SimpleViewLogic.step(0, by: 1, count: 3), 1)
        XCTAssertEqual(SimpleViewLogic.step(2, by: 1, count: 3), 2)
        XCTAssertEqual(SimpleViewLogic.step(0, by: -1, count: 3), 0)
        XCTAssertEqual(SimpleViewLogic.step(5, by: 0, count: 0), 0)
    }

    // MARK: Adding a frame (§5, §7)

    func testAFrameIsAddedWithTheLastFiftyPosts() {
        XCTAssertEqual(SimpleDefaults.addFrameHistory, .lastN(50))
        XCTAssertEqual(SimpleDefaults.addFrameHistory.count, 50)
    }

    func testTheOutcomeOfAddingAFrame() {
        XCTAssertEqual(AddFrameOutcome(.active), .showing)
        XCTAssertEqual(AddFrameOutcome(.pending), .waiting)
        XCTAssertEqual(AddFrameOutcome(.rejected), .failed)
        XCTAssertEqual(AddFrameOutcome(.pending).message(frame: "Oma", family: "Peeters family"),
                       "Oma: waiting for an admin of Peeters family.")
        XCTAssertEqual(AddFrameOutcome(.active).message(frame: "Oma", family: "Peeters family"), "Oma now shows Peeters family.")
    }

    // MARK: Lists

    func testOnlyMineKeepsYourOwnPosts() {
        let posts = [("p1", "me"), ("p2", "oma"), ("p3", "me")]
        XCTAssertEqual(FamilyPhotos.filter(posts, onlyMine: true, me: "me") { $0.1 }.map(\.0), ["p1", "p3"])
        XCTAssertEqual(FamilyPhotos.filter(posts, onlyMine: false, me: "me") { $0.1 }.count, 3)
        XCTAssertTrue(FamilyPhotos.filter(posts, onlyMine: true, me: nil) { $0.1 }.isEmpty)
    }

    // MARK: Wording (§4)

    func testTheDestinationRow() {
        XCTAssertEqual(FamilyText.destination(family: "Peeters family", frames: 3), "Peeters family · everyone and its 3 frames")
        XCTAssertEqual(FamilyText.destination(family: "Peeters family", frames: 1), "Peeters family · everyone and its frame")
        XCTAssertEqual(FamilyText.destination(family: "Peeters family", frames: 0), "Peeters family · everyone")
        XCTAssertEqual(FamilyText.destination(family: "Peeters family", frames: 3, circle: "The cousins"), "Peeters family · The cousins")
        XCTAssertEqual(FamilyText.destination(families: 3, frames: 9), "3 families · 9 frames")
    }

    func testWhichFamilyAFrameShows() {
        XCTAssertEqual(FamilyText.shows(["Peeters family"]), "Shows Peeters family")
        XCTAssertEqual(FamilyText.shows(["Trappers", "A", "B", "C", "D"]), "Shows Trappers and 4 other families")
        XCTAssertEqual(FamilyText.shows(["Trappers", "A"]), "Shows Trappers and 1 other family")
        XCTAssertEqual(FamilyText.shows([]), "Doesn't show a family yet")
    }

    func testTheProfileCountsAndNames() {
        XCTAssertEqual(FamilyText.counts(posts: 12, people: 1, frames: 2), "12 posts · 1 person · 2 frames")
        XCTAssertEqual(FamilyText.counts(posts: nil, people: 4, frames: 1), "4 people · 1 frame")
        XCTAssertEqual(FamilyText.memberNames(["Oma", "Lotte"]), "Oma and Lotte")
        XCTAssertEqual(FamilyText.memberNames(["A", "B", "C", "D", "E", "F"]), "A, B, C, D and 2 more")
    }

    func testTheFamilyNameIsSuggestedFromTheSurname() {
        XCTAssertEqual(FamilyText.suggestedName(for: "Jan Peeters"), "Peeters family")
        XCTAssertNil(FamilyText.suggestedName(for: "Jan"))
        XCTAssertNil(FamilyText.suggestedName(for: nil))
        L10n.setLanguage(.nl)
        XCTAssertEqual(FamilyText.suggestedName(for: "Jan Peeters"), "Familie Peeters")
        L10n.setLanguage(.en)
    }

    /// The interface never says follow, follower, subscription or feed (§2) in the strings the new screens use.
    func testTheNewWordingHasNoFollowing() {
        for lang: AppLanguage in [.en, .nl] {
            L10n.setLanguage(lang)
            for text in [L("simple.frame.showsNone"), L("ios.feeds.frameNone", ["frame": "Oma"]), L("ios.feeds.title"),
                         L("simple.addFrame.hint"), L("ios.feeds.subscribe.title"), L("social.role.memberHint")] {
                XCTAssertNil(text.range(of: lang == .en ? "follow" : "volg", options: .caseInsensitive), text)
                XCTAssertNil(text.range(of: "feed", options: .caseInsensitive), text)
            }
        }
        L10n.setLanguage(.en)
    }
}
