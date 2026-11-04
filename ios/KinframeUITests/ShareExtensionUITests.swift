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

    /// Remote settings: flip "Reverse the order" and save (a merge patch with If-Match). KF_FRAME_ID picks the frame.
    func testFrameSettingsSave() throws {
        let id = try XCTUnwrap(ProcessInfo.processInfo.environment["KF_FRAME_ID"], "set TEST_RUNNER_KF_FRAME_ID")
        let app = XCUIApplication()
        app.launchArguments = ["-kfLanguage", "en", "-kfFrame", id, "-kfSection", "settings"]
        app.launch()
        let toggle = app.switches["Reverse the order"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 15))
        toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap()
        let save = app.buttons["Save"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        save.tap()
        XCTAssertTrue(save.waitForNonExistence(timeout: 10), "the save bar goes away once saved")
        attach(app, "settings-saved")
    }

    /// People: let in the first person waiting to join KF_FRAME_ID.
    func testApproveJoinRequest() throws {
        let id = try XCTUnwrap(ProcessInfo.processInfo.environment["KF_FRAME_ID"], "set TEST_RUNNER_KF_FRAME_ID")
        let app = XCUIApplication()
        app.launchArguments = ["-kfLanguage", "en", "-kfFrame", id, "-kfSection", "people"]
        app.launch()
        let letIn = app.buttons["Let in"].firstMatch
        XCTAssertTrue(letIn.waitForExistence(timeout: 15))
        letIn.tap()
        XCTAssertTrue(letIn.waitForNonExistence(timeout: 10))
    }

    /// Overview: send "Identify" to KF_FRAME_ID (the frame must be online).
    func testRemoteCommand() throws {
        let id = try XCTUnwrap(ProcessInfo.processInfo.environment["KF_FRAME_ID"], "set TEST_RUNNER_KF_FRAME_ID")
        let app = XCUIApplication()
        app.launchArguments = ["-kfLanguage", "en", "-kfFrame", id, "-kfSection", "overview"]
        app.launch()
        let identify = app.buttons["Identify"]
        XCTAssertTrue(identify.waitForExistence(timeout: 15))
        identify.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'showing its name'")).firstMatch.waitForExistence(timeout: 10))
    }

    /// Opens Logto's sign-in page for the production server (no credentials are entered). KF_LOGTO=1 to run.
    func testLogtoSignInOpens() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["KF_LOGTO"] != nil, "set TEST_RUNNER_KF_LOGTO=1 to run")
        let app = XCUIApplication()
        app.launchArguments = ["-kfLanguage", "en", "-kfReset", "YES", "-kfServer", "https://kinframe.appmire.be", "-kfStep", "signIn"]
        app.launch()
        let signIn = app.buttons["Sign in"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 15))
        signIn.tap()
        sleep(8)
        if let dir = ProcessInfo.processInfo.environment["KF_SHOTS"] {
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/logto.png"))
        }
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
