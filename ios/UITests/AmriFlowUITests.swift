import XCTest

/// The app's flows against tools/fake_unit.mjs, in Arabic, with screenshots. The stand-in runs next to the simulator on the
/// Mac (ios.yml starts it on 47101, with adbd on 15555 as a K2501 and on 15556 as another device).
final class AmriFlowUITests: XCTestCase {
    private let fake = FakeUnit()

    override func setUp() {
        continueAfterFailure = false
        fake.call("reset")
    }

    private func launch(_ extra: [String] = [], reset: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-fakeUnit", "127.0.0.1:47101",
                               "-fakeAdb", "127.0.0.1:15555,127.0.0.1:15556", "-demoSearch", "-skipMapKitCheck"]
            + (reset ? ["-reset"] : []) + extra
        app.launch()
        return app
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    @discardableResult
    private func tap(_ app: XCUIApplication, _ id: String, timeout: TimeInterval = 15, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let target = element(app, id)
        if !target.waitForExistence(timeout: timeout) {
            shot("missing-\(id)")
            XCTFail("no \(id) on the screen. \(app.debugDescription)", file: file, line: line)
        }
        target.tap()
        return target
    }

    private func waitForLabel(_ app: XCUIApplication, _ id: String, contains text: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(app, id)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if target.exists, target.label.contains(text) || (target.value as? String)?.contains(text) == true { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        }
        shot("waited-\(id)")
        XCTFail("\(id) never said \(text); it says: \(target.exists ? target.label : "nothing")\n\(app.debugDescription)", file: file, line: line)
    }

    private func pair(_ app: XCUIApplication) {
        waitForLabel(app, "statusPill", contains: "اربطها")
        tap(app, "statusPill")
        let field = element(app, "pairCode")
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        shot("pair-screen")
        field.tap()
        field.typeText("246810")
        tap(app, "pairGo")
        XCTAssertTrue(element(app, "pairDone").waitForExistence(timeout: 15), app.debugDescription)
        shot("pair-done")
        tap(app, "pairFinish")
        // On a loaded CI machine a tap on a button in a scroll view is lost now and then (one run in about fifteen): as a person
        // would, tap again rather than fail the whole flow over it.
        let finish = element(app, "pairFinish")
        if !wait(6, { !finish.exists }), finish.exists {
            finish.tap()
        }
        waitForLabel(app, "statusPill", contains: "السيارة متصلة")
    }

    private func searchForMall(_ app: XCUIApplication) {
        let field = element(app, "searchField")
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("الراشد")
        XCTAssertTrue(element(app, "suggestion-0").waitForExistence(timeout: 10), app.debugDescription)
        shot("typing")
        tap(app, "suggestion-0")
        XCTAssertTrue(element(app, "placeName").waitForExistence(timeout: 10), app.debugDescription)
    }

    // MARK: The owner's sentence

    @MainActor
    func testSearchSendStatusInArabic() {
        let app = launch()
        XCTAssertTrue(element(app, "searchField").waitForExistence(timeout: 20))
        shot("first-launch")
        pair(app)
        shot("home-online")

        searchForMall(app)
        shot("place-card")
        XCTAssertEqual(element(app, "placeName").label, "الراشد مول")

        tap(app, "sendKey")
        // Never "started" before the unit's own started event.
        waitForLabel(app, "shareStatus", contains: "بدأت الاتجاهات", timeout: 25)
        shot("started")
        waitForLabel(app, "shareTripLine", contains: "٩:٤١")
        waitForLabel(app, "statusPill", contains: "٩:٤١")

        let log = fake.log()
        let goes = log.requests.filter { $0["path"] as? String == "/v1/nav/go" }
        XCTAssertEqual(goes.count, 1, "one send, one request")
        let body = goes.first?["body"] as? [String: Any]
        let place = body?["place"] as? [String: Any]
        XCTAssertEqual(place?["name"] as? String, "الراشد مول")
        XCTAssertEqual(place?["src"] as? String, "apple.mapkit", "the unit is told where it came from, and never keeps it")
        XCTAssertEqual(log.starts.count, 1)

        tap(app, "shareDone")
        XCTAssertTrue(element(app, "tripCard").waitForExistence(timeout: 10), app.debugDescription)
        waitForLabel(app, "tripName", contains: "الراشد مول")
        shot("trip-card")

        tap(app, "stopTrip")
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: element(app, "tripCard"))
        waitForExpectations(timeout: 15)
        XCTAssertEqual(fake.state().nav["state"] as? String, "idle")
        shot("stopped")
    }

    @MainActor
    func testChipsShowResultsOnTheMap() {
        let app = launch()
        XCTAssertTrue(element(app, "chip-pharmacy").waitForExistence(timeout: 20))
        tap(app, "chip-pharmacy")
        XCTAssertTrue(element(app, "result-0").waitForExistence(timeout: 10), app.debugDescription)
        shot("chip-results")
        tap(app, "result-0")
        XCTAssertTrue(element(app, "sendKey").waitForExistence(timeout: 10))
        tap(app, "placeBack")
        XCTAssertTrue(element(app, "result-0").waitForExistence(timeout: 10))
        tap(app, "searchClear")
    }

    // MARK: When things go wrong

    @MainActor
    func testAFrozenHotspotRetriesWithTheSameID() {
        let app = launch()
        pair(app)
        fake.call("config", ["dropGo": 1])
        searchForMall(app)
        tap(app, "sendKey")
        waitForLabel(app, "shareStatus", contains: "بدأت الاتجاهات", timeout: 45)
        let goes = fake.log().requests.filter { $0["path"] as? String == "/v1/nav/go" }
        XCTAssertGreaterThanOrEqual(goes.count, 2, "the first try got no answer")
        let ids = Set(goes.compactMap { ($0["body"] as? [String: Any])?["id"] as? String })
        XCTAssertEqual(ids.count, 1, "the same id on every try")
        XCTAssertEqual(fake.log().starts.count, 1, "and one trip")
    }

    @MainActor
    func testNoTokenGoesToTheCardAndSaysSo() {
        let app = launch()
        XCTAssertTrue(element(app, "searchField").waitForExistence(timeout: 20))
        searchForMall(app)
        tap(app, "sendKey")
        waitForLabel(app, "shareStatus", contains: "اضغط «ابدأ»")
        shot("sent-no-token-card")
        XCTAssertEqual(fake.log().starts.count, 0, "nothing starts until the card is tapped on the screen")
        XCTAssertNotNil(fake.state().card)
    }

    @MainActor
    func testAFailedRouteIsSaidAndNotStarted() {
        let app = launch()
        pair(app)
        fake.call("config", ["failRoute": "no_route"])
        searchForMall(app)
        tap(app, "sendKey")
        waitForLabel(app, "shareStatus", contains: "ما قدرت تحسب الطريق")
        waitForLabel(app, "shareReason", contains: "ما لقت طريق")
        shot("route-failed")
        XCTAssertFalse(element(app, "shareTripLine").exists)
    }

    @MainActor
    func testNoAnswerFromTheUnitIsSaid() {
        let app = XCUIApplication()
        // A port nothing listens on: no unit at all.
        app.launchArguments = ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-fakeUnit", "127.0.0.1:47199", "-demoSearch", "-reset"]
        app.launch()
        waitForLabel(app, "statusPill", contains: "ما لقيت السيارة")
        shot("not-found")
        searchForMall(app)
        tap(app, "sendKey")
        waitForLabel(app, "shareStatus", contains: "ما لقيت السيارة", timeout: 45)
        shot("send-unreachable")
        // Kept, and offered again on the start of the panel.
        tap(app, "shareCopy")
        XCTAssertTrue(element(app, "shareSend").exists)
    }

    // MARK: The car

    @MainActor
    func testRemoteShowsWhatTheUnitReports() {
        let app = launch()
        pair(app)
        tap(app, "carButton")
        waitForLabel(app, "musicTitle", contains: "يا طير")
        waitForLabel(app, "volumeStep", contains: "١٢")
        shot("car-remote")
        tap(app, "musicNext")
        waitForLabel(app, "musicTitle", contains: "على البال")
        XCTAssertEqual(fake.state().music["title"] as? String, "على البال")
        tap(app, "volumeUp")
        waitForLabel(app, "volumeStep", contains: "١٣")
        XCTAssertEqual((fake.state().music["volume"] as? NSNumber)?.intValue, 13)
        tap(app, "musicPlay")
        XCTAssertTrue(wait { self.fake.state().music["playing"] as? Bool == false })
        shot("car-remote-after")
    }

    @MainActor
    func testHomeOneTapUsesTheUnitsOwn() {
        fake.call("places", ["home": ["name": "حي الراكة", "lat": 26.33, "lon": 50.19], "favourites": [["name": "ورشة أبو سعد", "lat": 26.42, "lon": 50.09]]])
        let app = launch()
        pair(app)
        XCTAssertTrue(element(app, "favourite-0").waitForExistence(timeout: 10), app.debugDescription)
        shot("home-with-places")
        tap(app, "quick-home")
        waitForLabel(app, "shareStatus", contains: "بدأت الاتجاهات", timeout: 25)
        XCTAssertEqual(fake.log().requests.filter { $0["path"] as? String == "/v1/nav/saved" }.count, 1)
        XCTAssertEqual(fake.log().starts.first?["place"].flatMap { ($0 as? [String: Any])?["name"] as? String }, "حي الراكة")
    }

    @MainActor
    func testAnAppleResultStaysOnThePhoneAsAPlaceID() {
        let app = launch()
        pair(app)
        searchForMall(app)
        tap(app, "save-home")
        waitForLabel(app, "linkNote", contains: "على الجوال")
        shot("saved-on-phone")
        XCTAssertEqual(fake.log().requests.filter { $0["method"] as? String == "PUT" }.count, 0, "nothing of Apple's reaches the unit's list")
        tap(app, "placeBack")
        tap(app, "searchClear")
        waitForLabel(app, "quick-home", contains: "الراشد مول")
    }

    // MARK: فحص الربط, and the repair

    @MainActor
    func testLinkCheckMakesAReport() {
        let app = launch()
        pair(app)
        tap(app, "carButton")
        tap(app, "toolCheck")
        tap(app, "checkRun")
        let report = element(app, "checkReport")
        XCTAssertTrue(report.waitForExistence(timeout: 60), app.debugDescription)
        shot("check-report")
        for piece in ["[التوقيع]", "[الشبكة]", "[البحث عن السيارة]", "AMRI k2501-test", "K2501", "Pixel 7"] {
            XCTAssertTrue(report.label.contains(piece), "the report should say \(piece): \(report.label)")
        }
        tap(app, "checkCopy")
        XCTAssertEqual(fake.log().adbOpens.count, 0, "the check only shakes hands")
    }

    @MainActor
    func testLinkCheckWorksWithNoUnit() {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-fakeUnit", "127.0.0.1:47199", "-demoSearch", "-skipMapKitCheck", "-reset"]
        app.launch()
        waitForLabel(app, "statusPill", contains: "ما لقيت السيارة")
        tap(app, "carButton")
        shot("car-not-found")
        tap(app, "toolCheck")
        tap(app, "checkRun")
        let report = element(app, "checkReport")
        XCTAssertTrue(report.waitForExistence(timeout: 60), app.debugDescription)
        XCTAssertTrue(report.label.contains("ping: ما وصلني رد"), report.label)
        shot("check-no-unit")
    }

    @MainActor
    func testRepairNeedsTwoConfirmationsAndTouchesOnlyAK2501() {
        let app = launch()
        pair(app)
        tap(app, "carButton")
        tap(app, "toolRepair")
        XCTAssertTrue(element(app, "repairCommand").waitForExistence(timeout: 10))
        shot("repair-intro")
        tap(app, "repairScan")
        tap(app, "repairUnit-15555", timeout: 20)
        shot("repair-found-then-first-confirm")
        XCTAssertEqual(fake.log().adbOpens.count, 0, "a scan never opens anything")
        tap(app, "repairUnderstood")
        let go = element(app, "repairGo")
        XCTAssertTrue(go.waitForExistence(timeout: 10))
        XCTAssertFalse(go.isEnabled, "not without the word")
        let word = element(app, "repairWord")
        word.tap()
        word.typeText("رجوع")
        shot("repair-second-confirm")
        XCTAssertTrue(wait { go.isEnabled })
        go.tap()
        // rollback.sh answers at once and works on by itself: only its result file says how it went.
        waitForLabel(app, "repairOutput", contains: "MODE=back")
        shot("repair-watching")
        XCTAssertTrue(element(app, "repairDone").waitForExistence(timeout: 20), app.debugDescription)
        waitForLabel(app, "repairOutput", contains: "EXIT=0")
        waitForLabel(app, "repairVersion", contains: "1.19.0")
        waitForLabel(app, "repairTitle", contains: "رجعت الشاشة")
        shot("repair-result")
        let opens = fake.log().adbOpens
        XCTAssertGreaterThanOrEqual(opens.count, 2)
        XCTAssertEqual(Set(opens.compactMap { $0["model"] as? String }), ["K2501"], "nothing else is touched")
        XCTAssertEqual(opens.first?["service"] as? String, "shell:sh /data/local/tmp/amri-rollback/rollback.sh")
        XCTAssertEqual(Set(opens.dropFirst().compactMap { $0["service"] as? String }), ["shell:cat /data/local/tmp/amri-rollback/result"])
    }

    /// On a trip a second place makes the screen ask. Nobody answers: the phone says so, and the trip he is on is the only one.
    @MainActor
    func testAnUnansweredCardIsSaid() {
        let app = launch()
        pair(app)
        searchForMall(app)
        tap(app, "sendKey")
        waitForLabel(app, "shareStatus", contains: "بدأت الاتجاهات", timeout: 25)
        tap(app, "shareDone")
        tap(app, "chip-coffee")
        tap(app, "result-0")
        tap(app, "sendKey")
        waitForLabel(app, "shareStatus", contains: "تبدّل الرحلة")
        shot("card-replace")
        XCTAssertNotNil(fake.state().card)
        fake.call("card", ["expire": true])
        waitForLabel(app, "shareStatus", contains: "ما أحد رد")
        shot("card-expired")
        XCTAssertEqual(fake.log().starts.count, 1)
        XCTAssertFalse(element(app, "shareTripLine").exists)
    }

    private func wait(_ timeout: TimeInterval = 10, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        }
        return false
    }
}

/// The test's hand on the stand-in: /__fake/.
final class FakeUnit {
    struct Log {
        var requests: [[String: Any]]
        var starts: [[String: Any]]
        var adbOpens: [[String: Any]]
    }

    struct State {
        var nav: [String: Any]
        var music: [String: Any]
        var card: [String: Any]?
    }

    private let base = URL(string: "http://127.0.0.1:47101/__fake/")!

    @discardableResult
    func call(_ path: String, _ body: [String: Any]? = nil) -> [String: Any] {
        var request = URLRequest(url: base.appendingPathComponent(path), timeoutInterval: 5)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpMethod = body == nil ? (path == "reset" ? "POST" : "GET") : "POST"
        if let body { request.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        let done = DispatchSemaphore(value: 0)
        var out: [String: Any] = [:]
        URLSession.shared.dataTask(with: request) { data, _, _ in
            if let data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { out = json }
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 6)
        return out
    }

    func log() -> Log {
        let json = call("log")
        return Log(requests: json["requests"] as? [[String: Any]] ?? [], starts: json["starts"] as? [[String: Any]] ?? [], adbOpens: json["adbOpens"] as? [[String: Any]] ?? [])
    }

    func state() -> State {
        let json = call("state")
        return State(nav: json["nav"] as? [String: Any] ?? [:], music: json["music"] as? [String: Any] ?? [:], card: json["card"] as? [String: Any])
    }
}
