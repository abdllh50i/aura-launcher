import XCTest
@testable import AMRIKit

/// The shared list of what can be shared (ios/fixtures/share-links.json). Every row of section 2.5 of the plan.
final class ShareFixtureTests: XCTestCase {
    struct File: Decodable {
        let rows: [Row]
    }

    struct Row: Decodable {
        let id: String
        let input: Input
        let redirects: [String: Redirect]?
        let placeIDs: [String: Stub]?
        let searches: [String: [Stub]]?
        let expect: Expect
    }

    struct Input: Decodable {
        let urls: [String]?
        let texts: [String]?
        let mapItem: MapItem?
    }

    struct MapItem: Decodable {
        let name: String?
        let lat: Double
        let lon: Double
        let placeID: String?
    }

    struct Redirect: Decodable {
        let status: Int
        let location: String?
    }

    struct Stub: Decodable {
        let name: String
        let lat: Double
        let lon: Double
    }

    struct Expect: Decodable {
        let kind: String
        let lat: Double?
        let lon: Double?
        let confidence: String?
        let src: String?
        let name: String?
        let placeID: String?
        let query: String?
        let count: Int?
        let followed: Int?
        let tolerance: Double?
    }

    func loadRows() throws -> [Row] {
        let url = TestPaths.iosRoot.appendingPathComponent("fixtures/share-links.json")
        return try JSONDecoder().decode(File.self, from: Data(contentsOf: url)).rows
    }

    func testEveryRow() async throws {
        let rows = try loadRows()
        XCTAssertGreaterThan(rows.count, 40)
        XCTAssertEqual(Set(rows.map(\.id)).count, rows.count, "ids are unique")
        for row in rows {
            try await check(row)
        }
    }

    private func check(_ row: Row) async throws {
        let followed = Locked<[String]>([])
        let redirects = row.redirects ?? [:]
        let placeIDs = row.placeIDs ?? [:]
        let searches = row.searches ?? [:]
        let resolver = ShareResolver(
            follow: { url in
                followed.with { $0.append(url.absoluteString) }
                guard let answer = redirects[url.absoluteString] else { return RedirectAnswer(status: nil, location: nil) }
                return RedirectAnswer(status: answer.status, location: answer.location)
            },
            lookupPlaceID: { id in
                placeIDs[id].map { PhonePlace(name: $0.name, lat: $0.lat, lon: $0.lon, confidence: .guess, src: "mapkit", placeID: id) }
            },
            search: { query in
                (searches[query] ?? []).map { PhonePlace(name: $0.name, lat: $0.lat, lon: $0.lon, confidence: .guess, src: "mapkit") }
            })

        var payload = SharePayload(urls: row.input.urls ?? [], texts: row.input.texts ?? [])
        if let item = row.input.mapItem {
            payload.mapItem = SharedMapItem(name: item.name, lat: item.lat, lon: item.lon, placeID: item.placeID)
        }
        let outcome = await resolver.resolve(payload)
        let expect = row.expect
        let label = "row \(row.id): \(outcome.resolution), trail \(outcome.trail)"

        switch expect.kind {
        case "place":
            guard case .place(let place) = outcome.resolution else { return XCTFail(label) }
            let tolerance = expect.tolerance ?? 0.0000001
            XCTAssertEqual(place.lat, expect.lat ?? .nan, accuracy: tolerance, label)
            XCTAssertEqual(place.lon, expect.lon ?? .nan, accuracy: tolerance, label)
            XCTAssertEqual(place.confidence.rawValue, expect.confidence, label)
            XCTAssertEqual(place.src, expect.src, label)
            if let name = expect.name { XCTAssertEqual(place.name, name, label) }
            if let id = expect.placeID { XCTAssertEqual(place.placeID, id, label) }
        case "choices":
            guard case .choices(let places, let query) = outcome.resolution else { return XCTFail(label) }
            XCTAssertEqual(query, expect.query, label)
            XCTAssertEqual(places.count, expect.count, label)
        case "nothing":
            guard case .nothing = outcome.resolution else { return XCTFail(label) }
        default:
            XCTFail("unknown kind in \(row.id)")
        }
        if let count = expect.followed {
            XCTAssertEqual(followed.current.count, count, "requests in row \(row.id): \(followed.current)")
        }
    }
}

final class ShareParserTests: XCTestCase {
    func testNormalize() {
        XCTAssertEqual(ShareParser.normalize("٢٦٫٢٩،٥٠٫١٨"), "26.29,50.18")
        XCTAssertEqual(ShareParser.normalize("۲۶"), "26", "Persian digits too")
        XCTAssertEqual(ShareParser.normalize("\u{200F}a\u{200E}b\u{202A}c\u{202C}"), "abc")
        XCTAssertEqual(ShareParser.normalize("الراشد مول"), "الراشد مول")
    }

    func testShortAddress() {
        XCTAssertEqual(ShareParser.shortAddress(in: "EDMA2510"), "EDMA2510")
        XCTAssertEqual(ShareParser.shortAddress(in: "عنوان ecab2773 بالخبر"), "ECAB2773")
        XCTAssertNil(ShareParser.shortAddress(in: "EDMA251"))
        XCTAssertNil(ShareParser.shortAddress(in: "XEDMA25100"))
        XCTAssertNil(ShareParser.shortAddress(in: "1234EDMA"))
    }

    func testGeohash() throws {
        let point = try XCTUnwrap(Geohash.decode("thettfp30b"))
        XCTAssertEqual(point.lat, 26.290457, accuracy: 0.00001)
        XCTAssertEqual(point.lon, 50.184555, accuracy: 0.00001)
        XCTAssertNil(Geohash.decode("aaa"), "a is not in the alphabet")
        XCTAssertNil(Geohash.decode(""))
    }

    func testThePlanPutsExactBeforeGuesses() {
        let payload = SharePayload(urls: ["https://www.google.com/maps/@26.29,50.18,15z"], texts: ["26.290457, 50.184555"])
        let plan = ShareParser.plan(for: payload)
        guard case .place(let first) = plan.first else { return XCTFail("\(plan)") }
        XCTAssertEqual(first.confidence, .exact)
        XCTAssertEqual(first.lat, 26.290457)
    }

    func testSomethingThatIsNotMapsIsLeftAlone() {
        for text in ["https://example.com/maps/place/26.2,50.1", "https://notgoogle.com/maps?q=26.2,50.1", "ftp://maps.apple.com/?ll=1,2", "https://google.com/search?q=26.2,50.1"] {
            XCTAssertEqual(ShareParser.parseURL(URL(string: text)!), .notMaps, text)
        }
    }

    func testRedirectsAreOnlyForLinksHeShared() {
        guard case .followRedirect(let url, let hops, let src) = ShareParser.parseURL(URL(string: "http://maps.apple/p/abc")!) else { return XCTFail() }
        XCTAssertEqual(url.scheme, "https", "always https")
        XCTAssertEqual(hops, 1, "Apple: one request, then the first Location")
        XCTAssertEqual(src, "share.apple")
        guard case .followRedirect(_, let googleHops, _) = ShareParser.parseURL(URL(string: "https://maps.app.goo.gl/abc")!) else { return XCTFail() }
        XCTAssertEqual(googleHops, 2)
    }

    func testAppleSourcedIsMarked() {
        let apple = PhonePlace(name: "x", lat: 1, lon: 2, confidence: .exact, src: "share.apple")
        XCTAssertTrue(apple.isAppleSourced)
        XCTAssertTrue(PhonePlace(name: "x", lat: 1, lon: 2, confidence: .guess, src: "mapkit").isAppleSourced)
        XCTAssertFalse(PhonePlace(name: "x", lat: 1, lon: 2, confidence: .exact, src: "share.google").isAppleSourced)
        XCTAssertEqual(PhonePlace(name: "  ", lat: 1, lon: 2, confidence: .exact, src: "pin").name, PhonePlace.unnamed)
    }
}
