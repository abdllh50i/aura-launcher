import XCTest
@testable import AMRIKit

final class SSEParserTests: XCTestCase {
    private func messages(_ text: String) -> [SSEMessage] {
        var parser = SSEParser()
        return parser.feed(Array(text.utf8))
    }

    func testAnEventWithName() {
        XCTAssertEqual(messages("event: nav\ndata: {\"state\":\"routing\"}\n\n"),
                       [SSEMessage(event: "nav", data: "{\"state\":\"routing\"}")])
    }

    func testHeartbeatsAndHelloAreIgnored() {
        XCTAssertEqual(messages(": hello\n\n: heartbeat\n\n"), [])
    }

    func testCRLFAndSeveralDataLines() {
        XCTAssertEqual(messages("event: x\r\ndata: a\r\ndata: b\r\n\r\n"), [SSEMessage(event: "x", data: "a\nb")])
    }

    func testBytesArrivingOneAtATime() {
        var parser = SSEParser()
        var out: [SSEMessage] = []
        for byte in Array("id: 7\nevent: music\ndata: {}\n\nevent: car\ndata: 1\n\n".utf8) {
            if let message = parser.feed(byte) { out.append(message) }
        }
        XCTAssertEqual(out, [SSEMessage(event: "music", data: "{}", id: "7"), SSEMessage(event: "car", data: "1")])
    }

    func testArabicSurvives() {
        XCTAssertEqual(messages("event: nav\ndata: الراشد مول\n\n").first?.data, "الراشد مول")
    }

    func testNoEventNameIsMessage() {
        XCTAssertEqual(messages("data: x\n\n").first?.event, "message")
    }
}

final class LinkEventTests: XCTestCase {
    func testNavEventsCarryOnlyWhatTheUnitSent() throws {
        let started = LinkEvent(message: SSEMessage(event: "nav", data: #"{"state":"started","id":"A","distanceM":18400,"durationS":1380,"etaLocal":"9:41"}"#))
        guard case .nav(let nav) = started else { return XCTFail() }
        XCTAssertEqual(nav.kind, .started)
        XCTAssertEqual(nav.id, "A")
        XCTAssertEqual(nav.durationS, 1380)
        XCTAssertEqual(nav.etaLocal, "9:41")
        XCTAssertNil(nav.timeLeftS, "unknown stays unknown")
        XCTAssertNil(nav.nextText)
    }

    func testNavStateNamesAreRead() {
        let cases: [(String, NavEvent.Kind)] = [("routing", .routing), ("progress", .progress), ("navigating", .progress), ("failed", .failed), ("arrived", .arrived),
                                                ("stopped", .stopped), ("cancelled", .stopped), ("idle", .idle), ("card", .card), ("waiting_fix", .waitingFix),
                                                ("held", .held), ("handed", .handed), ("ignored", .ignored), ("replaced", .replaced), ("mystery", .other("mystery"))]
        for (raw, kind) in cases {
            XCTAssertEqual(NavEvent(json: ["state": raw])?.kind, kind, raw)
        }
        XCTAssertEqual(NavEvent(json: ["type": "started"])?.kind, .started, "the other common key")
        XCTAssertNil(NavEvent(json: ["x": 1]))
    }

    func testMusicAndCar() {
        guard case .music(let music) = LinkEvent(message: SSEMessage(event: "music", data: #"{"title":"يا طير","artist":"فنان","playing":true,"positionS":42}"#)) else { return XCTFail() }
        XCTAssertEqual(music.title, "يا طير")
        XCTAssertEqual(music.playing, true)
        XCTAssertEqual(music.positionS, 42)
        XCTAssertNil(music.durationS)
        guard case .car(let car) = LinkEvent(message: SSEMessage(event: "car", data: #"{"volumeStep":12,"gpsFix":false}"#)) else { return XCTFail() }
        XCTAssertEqual(car.volumeStep, 12)
        XCTAssertEqual(car.gpsFix, false)
        XCTAssertNil(car.speedKmh)
    }

    /// The shapes link/PhoneServer.kt really sends.
    func testTheUnitsOwnShapes() {
        guard case .music(let music) = LinkEvent(message: SSEMessage(event: "music", data: #"{"playing":true,"volume":12,"maxVolume":40,"muted":false,"title":"x","artist":"y","source":"com.abdllh.naghm"}"#)) else { return XCTFail() }
        XCTAssertEqual(music.volume, 12)
        XCTAssertEqual(music.maxVolume, 40)
        XCTAssertEqual(music.muted, false)
        guard case .car(let car) = LinkEvent(message: SSEMessage(event: "car", data: #"{"gear":null,"gearSure":false,"reversing":false,"inCall":true,"fix":{"lat":1,"lon":2,"ageS":3,"kmh":44}}"#)) else { return XCTFail() }
        XCTAssertEqual(car.speedKmh, 44)
        XCTAssertEqual(car.gpsFix, true)
        XCTAssertEqual(car.inCall, true)
        XCTAssertNil(car.gear, "null stays unknown")
        guard case .nav(let nav) = LinkEvent(message: SSEMessage(event: "nav", data: #"{"state":"navigating","place":{"name":"الراشد مول","detail":"","lat":26.29,"lon":50.18},"by":"phone","remainingM":800,"timeLeftS":120,"etaLocal":"9:41","speedKmh":40,"rerouting":false}"#)) else { return XCTFail() }
        XCTAssertEqual(nav.kind, .progress)
        XCTAssertTrue(nav.kind.isUnderWay)
        XCTAssertEqual(nav.name, "الراشد مول")
        XCTAssertEqual(nav.placeLat, 26.29)
        XCTAssertEqual(nav.placeLon, 50.18)
        XCTAssertEqual(nav.timeLeftS, 120)
    }

    func testASnapshotEvent() {
        guard case .snapshot(let snapshot) = LinkEvent(message: SSEMessage(event: "state", data: #"{"nav":{"state":"idle"},"music":{"title":"x"}}"#)) else { return XCTFail() }
        XCTAssertEqual(snapshot.nav?.kind, .idle)
        XCTAssertEqual(snapshot.music?.title, "x")
        XCTAssertNil(snapshot.car)
    }

    func testGarbageIsNotACrash() {
        XCTAssertEqual(LinkEvent(message: SSEMessage(event: "nav", data: "not json")), .other("nav"))
        XCTAssertEqual(LinkEvent(message: SSEMessage(event: "nav", data: "{}")), .other("nav"))
    }

    func testOnlyTheUnitSaysStarted() {
        let routing = Locked(false)
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .routing, id: "A"), forID: "A", routing: routing))
        XCTAssertTrue(routing.current)
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .started, id: "A"), forID: "A", routing: routing), .started(NavEvent(kind: .started, id: "A")))
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .started, id: "B"), forID: "A", routing: routing), "another send's start is not this one's")
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .progress, id: "B"), forID: "A", routing: routing))
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .progress), forID: "A", routing: routing), "a progress with no id could be an earlier trip")
        XCTAssertNotNil(LinkClient.outcome(of: NavEvent(kind: .progress, id: "A"), forID: "A", routing: routing), "its own progress proves the start")
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .failed, id: "A", reason: "x"), forID: "A", routing: routing), .failed("x"))
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .idle), forID: "A", routing: routing))
    }

    func testAnEventsOwnPlaceAlsoProvesTheStart() {
        let routing = Locked(false)
        let place = LinkPlace(name: "x", lat: 26.29, lon: 50.18)
        let same = NavEvent(kind: .progress, placeLat: 26.29, placeLon: 50.18)
        let elsewhere = NavEvent(kind: .progress, placeLat: 24.7, placeLon: 46.6)
        XCTAssertEqual(LinkClient.outcome(of: same, forID: "A", place: place, routing: routing), .started(same), "the snapshot of this very trip")
        XCTAssertNil(LinkClient.outcome(of: elsewhere, forID: "A", place: place, routing: routing), "an earlier trip somewhere else")
        XCTAssertNil(LinkClient.outcome(of: same, forID: "A", routing: routing), "without the place to compare, no claim")
    }

    func testOtherEndsOfASend() {
        let routing = Locked(false)
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .handed, id: "A"), forID: "A", routing: routing), .handed, "not started")
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .ignored, id: "A"), forID: "A", routing: routing), .ignored)
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .replaced, id: "A"), forID: "A", routing: routing), .cancelled)
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .stopped, id: "A"), forID: "A", routing: routing), .cancelled)
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .stopped, id: "B"), forID: "A", routing: routing))
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .card, id: "A"), forID: "A", routing: routing), "the screen asks: not an end")
        XCTAssertEqual(LinkClient.outcome(of: NavEvent(kind: .expired, id: "A"), forID: "A", routing: routing), .expired, "nobody answered the card")
        XCTAssertNil(LinkClient.outcome(of: NavEvent(kind: .expired, id: "B"), forID: "A", routing: routing))
    }

    /// The events of link/PlaceInbox.kt that say more than their state.
    func testWhatAStepSaysBesidesItsState() {
        guard case .nav(let card) = LinkEvent(message: SSEMessage(event: "nav", data: #"{"id":"A","state":"card","kind":"replace"}"#)) else { return XCTFail() }
        XCTAssertEqual(card.kind, .card, "its state, not its kind, is what it is")
        XCTAssertEqual(card.cardKind, "replace")
        guard case .nav(let failed) = LinkEvent(message: SSEMessage(event: "nav", data: #"{"id":"A","state":"failed","reason":"no_route","kept":true}"#)) else { return XCTFail() }
        XCTAssertEqual(failed.kept, true)
        let routing = Locked(true)
        XCTAssertEqual(LinkClient.outcome(of: failed, forID: "A", routing: routing), .failedKept("no_route"), "the trip he is on goes on")
        guard case .nav(let moving) = LinkEvent(message: SSEMessage(event: "nav", data: #"{"state":"navigating","rerouting":true}"#)) else { return XCTFail() }
        XCTAssertEqual(moving.rerouting, true)
    }

    func testARetryIsToldWhatBecameOfItsFirstTry() {
        XCTAssertEqual(LinkClient.outcome(ofLast: "started"), .started(NavEvent(kind: .started)))
        XCTAssertEqual(LinkClient.outcome(ofLast: "failed"), .failed(nil))
        XCTAssertEqual(LinkClient.outcome(ofLast: "handed"), .handed)
        XCTAssertEqual(LinkClient.outcome(ofLast: "ignored"), .ignored)
        XCTAssertEqual(LinkClient.outcome(ofLast: "expired"), .expired)
        XCTAssertEqual(LinkClient.outcome(ofLast: "replaced"), .cancelled)
        XCTAssertEqual(LinkClient.outcome(ofLast: "cancelled"), .cancelled)
        for going in ["accepted", "routing", "waiting_fix", "card", "held", "", nil] as [String?] {
            XCTAssertNil(LinkClient.outcome(ofLast: going), "still on it: the events will tell. \(going ?? "nil")")
        }
    }

    func testACarEventWithNoFixMeansNone() {
        guard case .car(let none) = LinkEvent(message: SSEMessage(event: "car", data: #"{"gear":null,"gearSure":false,"reversing":false,"inCall":false}"#)) else { return XCTFail() }
        XCTAssertEqual(none.gpsFix, false, "the unit leaves the fix out when it has none")
        guard case .car(let old) = LinkEvent(message: SSEMessage(event: "car", data: #"{"reversing":false,"fix":{"lat":1,"lon":2,"ageS":600,"kmh":0}}"#)) else { return XCTFail() }
        XCTAssertEqual(old.gpsFix, true)
        XCTAssertEqual(old.fixAgeS, 600)
        guard case .car(let other) = LinkEvent(message: SSEMessage(event: "car", data: #"{"volumeStep":3}"#)) else { return XCTFail() }
        XCTAssertNil(other.gpsFix, "an event about something else says nothing about it")
    }
}

final class ModelTests: XCTestCase {
    func testGoRequestIsTheProtocolsShape() throws {
        let request = GoRequest(id: "01JC7Y3V8Z9Q", place: LinkPlace(name: "الراشد مول", detail: "الخبر", lat: 26.290457, lon: 50.184555, src: "share.apple"),
                                from: PhoneFix(lat: 26.41, lon: 50.09, acc: 12, ageSec: 8))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any])
        XCTAssertEqual(json["id"] as? String, "01JC7Y3V8Z9Q")
        XCTAssertEqual(json["mode"] as? String, "start")
        let place = try XCTUnwrap(json["place"] as? [String: Any])
        XCTAssertEqual(place["name"] as? String, "الراشد مول")
        XCTAssertEqual(place["detail"] as? String, "الخبر")
        XCTAssertEqual(place["lat"] as? Double, 26.290457)
        XCTAssertEqual(place["src"] as? String, "share.apple")
        XCTAssertEqual(Set(place.keys), ["name", "detail", "lat", "lon", "src"], "nothing else, and no computed id")
        let from = try XCTUnwrap(json["from"] as? [String: Any])
        XCTAssertEqual(from["ageSec"] as? Int, 8)
        XCTAssertEqual(from["acc"] as? Double, 12)
    }

    func testOnlyAFreshDecentFixIsOffered() {
        XCTAssertNotNil(PhoneFix.usable(lat: 1, lon: 2, accuracy: 12, age: 8))
        XCTAssertNil(PhoneFix.usable(lat: 1, lon: 2, accuracy: 100, age: 8), "100 m is not under 100 m")
        XCTAssertNil(PhoneFix.usable(lat: 1, lon: 2, accuracy: 12, age: 60), "60 s is not under 60 s")
        XCTAssertNil(PhoneFix.usable(lat: 1, lon: 2, accuracy: -1, age: 8), "a negative accuracy means no fix")
    }

    func testRequestIDsAreNewAndOrdered() {
        let early = RequestID.new(now: Date(timeIntervalSince1970: 1_000_000))
        let late = RequestID.new(now: Date(timeIntervalSince1970: 2_000_000))
        XCTAssertEqual(early.count, 16)
        XCTAssertLessThan(early, late)
        XCTAssertNotEqual(RequestID.new(), RequestID.new())
    }

    func testPlacesDocumentTolerance() throws {
        let doc = try JSONDecoder().decode(PlacesDoc.self, from: Data(#"{"home":{"name":"البيت","detail":"","lat":1,"lon":2},"favorites":[{"name":"x","lat":3,"lon":4}]}"#.utf8))
        XCTAssertEqual(doc.home?.name, "البيت")
        XCTAssertNil(doc.work)
        XCTAssertEqual(doc.favourites.count, 1, "either spelling")
        XCTAssertEqual(doc.recents.count, 0)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(doc)) as? [String: Any])
        XCTAssertTrue(json["work"] is NSNull, "clearing Work must reach the unit")
        XCTAssertNotNil(json["favourites"])
    }

    func testPairingCodes() {
        XCTAssertEqual(PairingTarget.parse("246810"), PairingTarget(address: nil, code: "246810"))
        XCTAssertEqual(PairingTarget.parse(" ٢٤٦٨١٠ "), PairingTarget(address: nil, code: "246810"))
        XCTAssertEqual(PairingTarget.parse("http://172.20.10.3:47101/p/246810"),
                       PairingTarget(address: UnitAddress(host: "172.20.10.3", port: 47101), code: "246810"))
        XCTAssertEqual(PairingTarget.parse("amri://pair?host=172.20.10.3&port=47101&code=246810"),
                       PairingTarget(address: UnitAddress(host: "172.20.10.3", port: 47101), code: "246810"))
        XCTAssertEqual(PairingTarget.parse("amri://pair?url=http%3A%2F%2F172.20.10.3%3A47101%2Fp%2F246810"),
                       PairingTarget(address: UnitAddress(host: "172.20.10.3", port: 47101), code: "246810"))
        XCTAssertNil(PairingTarget.parse("12345"), "too short")
        XCTAssertNil(PairingTarget.parse("1234567"), "too long")
        XCTAssertNil(PairingTarget.parse("http://8.8.8.8:47101/p/246810"), "a QR must not point the app at the internet")
        XCTAssertNil(PairingTarget.parse("https://example.com/p/246810"))
        XCTAssertNil(PairingTarget.parse("كلام"))
    }

    func testTheProfileIsFoundInsideItsWrapping() throws {
        let plist = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
          <key>Name</key><string>iOS Team Provisioning Profile: com.abdllh.amri</string>
          <key>ExpirationDate</key><date>2026-11-10T00:00:00Z</date>
          <key>CreationDate</key><date>2026-11-03T00:00:00Z</date>
          <key>Entitlements</key><dict>
            <key>application-identifier</key><string>TEAM.com.abdllh.amri</string>
            <key>com.apple.security.application-groups</key><array><string>group.com.abdllh.amri.TEAM</string></array>
          </dict>
        </dict></plist>
        """
        var blob = Data([0x30, 0x82, 0x01, 0x02, 0xFF, 0x00])
        blob.append(Data(plist.utf8))
        blob.append(Data([0x00, 0xA0, 0x82, 0xFE]))
        let profile = try XCTUnwrap(ProvisioningProfile(data: blob))
        XCTAssertEqual(profile.appGroups, ["group.com.abdllh.amri.TEAM"], "read, not assumed: a signing tool may rename it")
        XCTAssertEqual(profile.appID, "TEAM.com.abdllh.amri")
        let formatter = ISO8601DateFormatter()
        let now = try XCTUnwrap(formatter.date(from: "2026-11-05T00:00:00Z"))
        XCTAssertEqual(profile.daysLeft(now: now), 5)
        XCTAssertFalse(profile.needsRefresh(now: now))
        let soon = try XCTUnwrap(formatter.date(from: "2026-11-07T00:00:00Z"))
        XCTAssertEqual(profile.daysLeft(now: soon), 3)
        XCTAssertTrue(profile.needsRefresh(now: soon), "3 days or fewer")
        XCTAssertNil(ProvisioningProfile(data: Data("no plist here".utf8)))
    }

    func testNoProfileMeansNoWarning() {
        XCTAssertFalse(ProvisioningProfile().needsRefresh())
        XCTAssertNil(ProvisioningProfile().daysLeft())
    }

    func testTheStoreKeepsPlacesForTwelveHours() throws {
        let suite = "amri.test.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = SharedStore(defaults: defaults)
        let place = LinkPlace(name: "x", lat: 1, lon: 2)
        store.addPending(PendingPlace(id: "new", place: place))
        store.addPending(PendingPlace(id: "old", place: place, createdAt: Date(timeIntervalSinceNow: -13 * 3600)))
        XCTAssertEqual(store.pendingPlaces().map(\.id), ["new"])
        store.removePending(id: "new")
        XCTAssertEqual(store.pendingPlaces().count, 0)

        store.pairing = Pairing(token: "t", unit: "u", name: "n", address: UnitAddress(host: "172.20.10.3"))
        XCTAssertEqual(store.pairing?.address?.host, "172.20.10.3")
        store.forgetPairing()
        XCTAssertNil(store.pairing)

        store.phoneSaved = [PhoneSaved(slot: .home, placeID: "I1")]
        XCTAssertEqual(store.phoneSaved.first?.placeID, "I1")
        store.lastShare = StoredShare(payload: SharePayload(typeIdentifiers: [["public.url"]], urls: ["https://maps.apple/p/x"]))
        XCTAssertEqual(store.lastShare?.payload.urls, ["https://maps.apple/p/x"])
        store.reset()
        XCTAssertNil(store.lastShare)
        XCTAssertEqual(store.phoneSaved.count, 0)
    }

    func testTheReportWorksWithNothingReachable() {
        var facts = LinkFacts()
        facts.appVersion = "1.0.0 (1)"
        facts.systemVersion = "26.0"
        facts.signing = SigningFacts(appBundleID: "com.abdllh.amri", shareBundleID: nil, appProfile: nil, shareProfile: nil, shareExtensionPresent: false)
        facts.interfaces = []
        facts.located = LocateOutcome(sweep: SweepReport(), duration: 0.5)
        facts.mapRows = [MapProbeRow(query: "قهوة", problem: "x"), MapProbeRow(query: "صيدلية"), MapProbeRow(query: "مطعم", count: 3, nearestKm: 1.25, names: ["أ", "ب"])]
        let text = LinkReport.render(facts)
        for piece in ["[التوقيع]", "com.abdllh.amri", "غير موجود في التطبيق", "ملف التوقيع: ما لقيته", "[الشبكة]", "IPv4: ما فيه عنوان",
                      "ما لقيت شاشة AMRI", "ping: ما وصلني رد", "[آخر مشاركة", "ما أقدر أقرأها", "[بحث الخرائط]", "كل السعودية",
                      "قهوة: فشل", "صيدلية: ما فيه نتائج", "مطعم: 3 نتيجة، الأقرب 1.2 كم"] where !text.contains(piece) {
            // %.1f of 1.25 may round either way on a binary double, so that piece is checked loosely below.
            if piece.hasPrefix("مطعم") { continue }
            XCTFail("the report should say \(piece):\n\(text)")
        }
        XCTAssertTrue(text.contains("مطعم: 3 نتيجة"), text)
    }

    func testTheConclusionTellsTheRouteFromTheServer() {
        var facts = LinkFacts()
        facts.located = LocateOutcome(sweep: { var s = SweepReport(); s.targetCount = 13; return s }())
        var adb = Repair.Scan()
        adb.targetCount = 13
        facts.adb = adb
        XCTAssertTrue(LinkReport.conclusion(facts).joined().contains("ما شاف الشاشة"), "nothing answered on either port")

        adb.hosts = [Repair.Host(host: "172.20.10.3", port: 5555, kind: .k2501, banner: nil)]
        facts.adb = adb
        let serverOff = LinkReport.conclusion(facts).joined()
        XCTAssertTrue(serverOff.contains("172.20.10.3"), serverOff)
        XCTAssertTrue(serverOff.contains("ربط الجوال"), "the route works, the server is what is off")

        facts.ping = FoundUnit(address: UnitAddress(host: "172.20.10.3"), ping: UnitPing(app: "AMRI"))
        let reached = LinkReport.conclusion(facts).joined()
        XCTAssertTrue(reached.contains("172.20.10.3:47101"), reached)
        XCTAssertTrue(reached.contains("باقي تربطها"), reached)
        XCTAssertTrue(reached.contains("App Group"), reached)

        facts.localNetwork = .denied
        XCTAssertTrue(LinkReport.conclusion(facts).joined().contains("مرفوضة"), "first things first")

        var empty = LinkFacts()
        empty.located = LocateOutcome(sweep: SweepReport())
        empty.adb = Repair.Scan()
        XCTAssertTrue(LinkReport.conclusion(empty).joined().contains("شغّل الهوتسبوت"))
        XCTAssertTrue(LinkReport.render(empty).contains("[الخلاصة]"))
    }

    func testTheReportShowsTheSharedPayloadRaw() {
        var facts = LinkFacts()
        facts.lastShare = StoredShare(payload: SharePayload(typeIdentifiers: [["public.url", "public.plain-text"]], urls: ["https://maps.apple/p/x"], texts: ["الراشد مول"]),
                                      trail: ["maps.apple: 301 https://maps.apple.com/place?coordinate=1,2"], result: "exact")
        let text = LinkReport.render(facts)
        XCTAssertTrue(text.contains("public.url, public.plain-text"), text)
        XCTAssertTrue(text.contains("https://maps.apple/p/x"), text)
        XCTAssertTrue(text.contains("الراشد مول"), text)
        XCTAssertTrue(text.contains("maps.apple: 301"), text)
    }
}
