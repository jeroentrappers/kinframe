import XCTest

/// UI tests against a dev server (not part of the default test run; use the KinframeUITests scheme).
///
/// The share extension runs in a hosted scene whose views the simulator doesn't expose to XCUITest or screenshots,
/// so the composer is driven through the app's DEBUG preview of the same view and model (`-kfSharePreview`), which
/// takes the extension's real path: staging in the App Group, the outbox job and a background upload session.
///
/// Environment: KF_MEDIA (comma-separated files to share) and KF_FRAME (name of a frame to send to).
final class ShareExtensionUITests: XCTestCase {
    func testShareComposerQueuesAndSends() throws {
        let env = ProcessInfo.processInfo.environment
        let media = try XCTUnwrap(env["KF_MEDIA"], "set TEST_RUNNER_KF_MEDIA")
        let frame = env["KF_FRAME"] ?? "Oma’s frame"
        let app = XCUIApplication()
        app.launchArguments = ["-kfLanguage", "en", "-kfSharePreview", media]
        app.launch()

        let row = app.buttons.containing(NSPredicate(format: "label BEGINSWITH %@", frame)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15))
        attach(app, "share-composer")
        let send = app.navigationBars.buttons["Send"]
        if !send.isEnabled { row.tap() }
        let caption = app.textFields.firstMatch
        if caption.waitForExistence(timeout: 2) {
            caption.tap()
            caption.typeText("Shared from Photos")
        }
        XCTAssertTrue(send.isEnabled)
        send.tap()
        attach(app, "share-sending")
        // Keep the app alive while the queued send uploads.
        sleep(10)
    }

    /// Exploratory: Photos → Share → kinframe. Taps by position because the extension's scene isn't inspectable.
    func testShareFromPhotosApp() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["KF_PHOTOS"] != nil, "set TEST_RUNNER_KF_PHOTOS=1 to run")
        let photos = XCUIApplication(bundleIdentifier: "com.apple.mobileslideshow")
        photos.launch()
        let select = ["Select", "Selecteer"].map { photos.buttons[$0] }.first { $0.waitForExistence(timeout: 5) }
        try XCTUnwrap(select).tap()
        photos.coordinate(withNormalizedOffset: CGVector(dx: 0.17, dy: 0.24)).tap()
        photos.coordinate(withNormalizedOffset: CGVector(dx: 0.13, dy: 0.94)).tap()
        let kinframe = photos.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] 'kinframe'")).firstMatch
        XCTAssertTrue(kinframe.waitForExistence(timeout: 10))
        kinframe.tap()
        // The frames shared to last time are preselected; tap Send.
        sleep(4)
        photos.coordinate(withNormalizedOffset: CGVector(dx: 0.87, dy: 0.115)).tap()
        sleep(3)
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }
}
