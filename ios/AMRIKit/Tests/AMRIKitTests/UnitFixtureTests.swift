import XCTest
@testable import AMRIKit

/// The unit's own list (tools/link-fixtures/share.json on the link branch, copied as ios/fixtures/unit-share.json until the
/// branches meet), held against this parser, so both sides read a shared link the same way. The phone has what the unit has
/// not, so two kinds read differently here: "search" is a list to pick from, and "unresolved" with words means searching them.
final class UnitFixtureTests: XCTestCase {
    struct File: Decodable {
        let cases: [Case]
    }

    struct Case: Decodable {
        let name: String
        let input: Input
        let redirects: [String: Redirect]?
        let expect: Expect
    }

    struct Input: Decodable {
        let text: String
    }

    /// `{code, location}`, or the word "error" for no answer at all.
    enum Redirect: Decodable {
        case answer(code: Int, location: String?)
        case error

        private enum Keys: String, CodingKey { case code, location }

        init(from decoder: Decoder) throws {
            if let text = try? decoder.singleValueContainer().decode(String.self), text == "error" {
                self = .error
                return
            }
            let c = try decoder.container(keyedBy: Keys.self)
            self = .answer(code: try c.decode(Int.self, forKey: .code), location: try c.decodeIfPresent(String.self, forKey: .location))
        }
    }

    struct Expect: Decodable {
        let kind: String
        let lat: Double?
        let lon: Double?
        let tolerance: Double?
        let name: String?
        let detail: String?
        let src: String?
        let confidence: String?
        let q: String?
    }

    /// The copy, or the file itself once it is in the same tree.
    private func listURL() -> URL {
        let shared = TestPaths.iosRoot.deletingLastPathComponent().appendingPathComponent("tools/link-fixtures/share.json")
        if FileManager.default.fileExists(atPath: shared.path) { return shared }
        return TestPaths.iosRoot.appendingPathComponent("fixtures/unit-share.json")
    }

    func testEveryCase() async throws {
        let cases = try JSONDecoder().decode(File.self, from: Data(contentsOf: listURL())).cases
        XCTAssertGreaterThan(cases.count, 40)
        for item in cases {
            try await check(item)
        }
    }

    private func check(_ item: Case) async throws {
        let redirects = item.redirects ?? [:]
        let strays = Locked<[String]>([])
        let resolver = ShareResolver(
            follow: { url in
                guard let answer = redirects[url.absoluteString] else {
                    strays.with { $0.append(url.absoluteString) }
                    return RedirectAnswer(status: nil, location: nil)
                }
                switch answer {
                case .error:
                    return RedirectAnswer(status: nil, location: nil)
                case .answer(let code, let location):
                    return RedirectAnswer(status: code, location: location)
                }
            },
            lookupPlaceID: { _ in nil },
            search: { _ in [PhonePlace(name: "x", lat: 1, lon: 2, confidence: .guess, src: "mapkit")] })

        let outcome = await resolver.resolve(SharePayload(texts: [item.input.text]))
        let label = "\(item.name): \(outcome.resolution), trail \(outcome.trail)"
        XCTAssertEqual(strays.current, [], "a URL no fixture lists must never be asked for. \(label)")

        let expect = item.expect
        switch expect.kind {
        case "place":
            guard case .place(let place) = outcome.resolution else { return XCTFail(label) }
            let tolerance = expect.tolerance ?? 0.000001
            XCTAssertEqual(place.lat, expect.lat ?? .nan, accuracy: tolerance, label)
            XCTAssertEqual(place.lon, expect.lon ?? .nan, accuracy: tolerance, label)
            XCTAssertEqual(place.src, expect.src, label)
            XCTAssertEqual(place.confidence.rawValue, expect.confidence, label)
            let name = expect.name ?? ""
            XCTAssertEqual(place.name, name.isEmpty ? PhonePlace.unnamed : name, label)
            let detail = expect.detail ?? ""
            XCTAssertEqual(place.detail ?? "", detail, label)
        case "search":
            guard case .choices(_, let query) = outcome.resolution else { return XCTFail(label) }
            XCTAssertEqual(query, expect.q, label)
        case "unresolved":
            if let q = expect.q {
                guard case .choices(_, let query) = outcome.resolution else { return XCTFail(label) }
                XCTAssertEqual(query, q, label)
            } else {
                guard case .nothing = outcome.resolution else { return XCTFail(label) }
            }
        case "nothing":
            guard case .nothing = outcome.resolution else { return XCTFail(label) }
        default:
            XCTFail("unknown kind in \(item.name)")
        }
    }
}
