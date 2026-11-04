import XCTest

/// The extension through the real share sheet. This job's step is continue-on-error: the sheet belongs to the system and
/// changes between iOS versions, and the simulator's payload differs from a device's anyway (section 2.5).
final class ShareSheetUITests: XCTestCase {
    @MainActor
    func testTheExtensionLoadsAndSends() throws {
        continueAfterFailure = false
        let fake = FakeUnit()
        fake.call("reset")

        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-screen", "sharetest"]
        app.launch()
        let button = app.descendants(matching: .any)["shareTestButton"].firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 15))
        button.tap()

        let sheet = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let name = "إرسال للسيارة"
        var entry = app.descendants(matching: .any)[name].firstMatch
        if !entry.waitForExistence(timeout: 8) {
            entry = sheet.descendants(matching: .any)[name].firstMatch
        }
        attach(app, "share-sheet")
        if !entry.waitForExistence(timeout: 5) {
            // It may be behind "More".
            for more in ["المزيد", "More"] {
                let key = app.descendants(matching: .any)[more].firstMatch
                if key.exists {
                    key.tap()
                    break
                }
            }
            entry = app.descendants(matching: .any)[name].firstMatch
            attach(app, "share-sheet-more")
        }
        XCTAssertTrue(entry.waitForExistence(timeout: 10), "the extension is not in the share sheet: \(app.debugDescription)")
        entry.tap()

        // A paired-less send: the stand-in takes it from the gateway and shows a card.
        let status = app.descendants(matching: .any)["shareStatus"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "share-extension-running")
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline, !status.label.contains("اضغط") {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        attach(app, "share-extension-result")
        XCTAssertTrue(status.label.contains("اضغط"), status.label)
        XCTAssertNotNil(fake.state().card, "the place reached the unit")
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
