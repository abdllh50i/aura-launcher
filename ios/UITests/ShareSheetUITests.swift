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

        // The sheet's row is drawn by the system, in the system's language: it may show the app's name or the extension's.
        RunLoop.current.run(until: Date().addingTimeInterval(3))
        attach(app, "share-sheet")
        let names = ["إرسال للسيارة", "Send to Car", "عمري", "Amri"]
        var entry: XCUIElement?
        for name in names {
            let candidate = app.descendants(matching: .any)[name].firstMatch
            if candidate.waitForExistence(timeout: 2) {
                entry = candidate
                break
            }
        }
        if entry == nil {
            for more in ["المزيد", "More"] {
                let key = app.descendants(matching: .any)[more].firstMatch
                if key.exists {
                    key.tap()
                    break
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(2))
            attach(app, "share-sheet-more")
            for name in names {
                let candidate = app.descendants(matching: .any)[name].firstMatch
                if candidate.waitForExistence(timeout: 2) {
                    entry = candidate
                    break
                }
            }
        }
        guard let entry else {
            return XCTFail("the extension is not in the share sheet: \(app.debugDescription)")
        }
        entry.tap()

        // A paired-less send: the stand-in takes it from the gateway and shows a card.
        let status = app.descendants(matching: .any)["shareStatus"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "share-extension-running")
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline, !status.label.contains("اضغط"), !status.label.contains("Press") {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        attach(app, "share-extension-result")
        XCTAssertTrue(status.label.contains("اضغط") || status.label.contains("Press"), status.label)
        XCTAssertNotNil(fake.state().card, "the place reached the unit")
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
