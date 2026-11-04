import Foundation
import MapKit

/// "فحص الربط": one run, one report. It doubles as the probe of section 8 P0, because nothing has been tried on his phone
/// and his hotspot, so every step must finish and report even when nothing answers.
public enum LinkCheck {
    /// The fixed Arabic list, the same as section 2.6's sample plus the Saudi short address.
    public static let queries = ["محطة بنزين", "صيدلية", "مطعم مندي", "مغسلة سيارات", "الراشد مول", "قهوة", "EDMA2510"]

    public struct Input: @unchecked Sendable {
        public var appVersion: String
        public var systemVersion: String
        public var store: SharedStore
        public var near: CLLocationCoordinate2D?
        public var locationNote: String?
        public var locator: UnitLocator
        public var adbExtra: [SweepTarget]
        public var runMapKit: Bool

        public init(appVersion: String, systemVersion: String, store: SharedStore, near: CLLocationCoordinate2D?, locationNote: String?,
                    locator: UnitLocator, adbExtra: [SweepTarget] = [], runMapKit: Bool = true) {
            self.appVersion = appVersion
            self.systemVersion = systemVersion
            self.store = store
            self.near = near
            self.locationNote = locationNote
            self.locator = locator
            self.adbExtra = adbExtra
            self.runMapKit = runMapKit
        }
    }

    /// `step` says what is being done now, for the screen.
    public static func run(_ input: Input, step: @escaping @Sendable (String) -> Void = { _ in }) async -> LinkFacts {
        var facts = LinkFacts()
        facts.appVersion = input.appVersion
        facts.systemVersion = input.systemVersion
        facts.when = Date()

        step("التوقيع")
        facts.signing = SigningFacts.current()
        facts.storeIsShared = input.store.isShared
        facts.storeGroup = input.store.groupID

        step("الشبكة")
        facts.interfaces = NetworkInterfaces.current()
        let bonjour = await Bonjour.browse(timeout: 1.5)
        if bonjour.denied { facts.localNetwork = .denied }

        step("البحث عن السيارة")
        let pairing = input.store.pairing
        facts.paired = pairing
        let located = await input.locator.locate(expectedUnit: pairing?.unit, last: pairing?.address ?? input.store.lastAddress)
        facts.located = located
        facts.ping = located.found
        if located.localNetworkDenied { facts.localNetwork = .denied }

        if let found = located.found {
            if isLocal(found.address.host) { facts.localNetwork = .allowed }
            if let pairing {
                step("حالة الشاشة")
                let client = LinkClient(address: found.address, token: pairing.token)
                facts.snapshot = try? await client.state()
                do {
                    facts.diag = try await client.diag()
                } catch {
                    facts.diagProblem = "\(LinkError.from(error))"
                }
            } else {
                facts.diagProblem = "يحتاج ربط"
            }
        }

        step("منفذ ADB")
        let scan = await Repair.scan(targets: Repair.targets(extra: input.adbExtra, interfaces: facts.interfaces))
        facts.adb = scan
        if scan.deniedSeen { facts.localNetwork = .denied }
        if facts.localNetwork == .unknown, scan.hosts.contains(where: { isLocal($0.host) }) { facts.localNetwork = .allowed }

        facts.lastShare = input.store.lastShare

        if input.runMapKit {
            step("بحث الخرائط")
            facts.nearPhone = input.near != nil
            facts.locationNote = input.locationNote
            let probe = await mapProbe(near: input.near)
            facts.mapRows = probe.rows
            facts.categoryRows = probe.categories
            facts.directions = await directions(near: input.near)
        }
        return facts
    }

    private static func isLocal(_ host: String) -> Bool {
        guard let bits = NetworkInterfaces.parseIPv4(host) else { return false }
        return NetworkInterfaces.isPrivate(bits)
    }

    /// The fixed list, each four ways, then each chip's category. Under Apple's roughly 50 requests a minute.
    public static func mapProbe(near: CLLocationCoordinate2D?) async -> (rows: [MapProbeRow], categories: [MapProbeRow]) {
        var rows: [MapProbeRow] = []
        for query in queries {
            do {
                rows.append(row("بحث عمري: \(query)", places: try await PlaceQueries.best(query, near: near), near: near))
            } catch {
                rows.append(MapProbeRow(query: "بحث عمري: \(query)", problem: "\(error)"))
            }
            do {
                rows.append(row("نص خام: \(query)", places: try await PlaceQueries.text(query, near: near), near: near))
            } catch {
                rows.append(MapProbeRow(query: "نص خام: \(query)", problem: "\(error)"))
            }
            do {
                rows.append(row("أماكن فقط: \(query)", places: try await PlaceQueries.pointsOfInterest(query, near: near), near: near))
            } catch {
                rows.append(MapProbeRow(query: "أماكن فقط: \(query)", problem: "\(error)"))
            }
            let suggestions = await PlaceQueries.suggestions(query, near: near)
            rows.append(MapProbeRow(query: "اقتراحات: \(query)", count: suggestions.count, names: suggestions.prefix(3).map { "\($0.title) (\($0.subtitle))" }))
        }
        var categories: [MapProbeRow] = []
        for category in PlaceCategory.allCases {
            let label = "تصنيف \(category.arabicName)"
            do {
                categories.append(row(label, places: try await PlaceQueries.category(category, near: near), near: near))
            } catch {
                categories.append(MapProbeRow(query: label, problem: "\(error)"))
            }
        }
        return (rows, categories)
    }

    private static func row(_ query: String, places: [PhonePlace], near: CLLocationCoordinate2D?) -> MapProbeRow {
        var nearest: Double?
        if let near {
            nearest = places.map { $0.distance(from: near) / 1000 }.min()
        }
        return MapProbeRow(query: query, count: places.count, nearestKm: nearest, names: places.prefix(3).map(\.name))
    }

    /// Apple's note on free accounts is unclear about routing, so the report tells.
    public static func directions(near: CLLocationCoordinate2D?) async -> String {
        let start = near ?? CLLocationCoordinate2D(latitude: 24.7136, longitude: 46.6753)
        let end = CLLocationCoordinate2D(latitude: start.latitude + 0.02, longitude: start.longitude + 0.02)
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: start))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: end))
        request.transportType = .automobile
        do {
            let response = try await MKDirections(request: request).calculate()
            if let route = response.routes.first {
                return "تشتغل (\(String(format: "%.1f", route.distance / 1000)) كم)"
            }
            return "ما رجع طريق"
        } catch {
            return "فشلت: \(error.localizedDescription)"
        }
    }
}
