import Foundation
import MapKit

/// The app searches in Saudi Arabia only, as the unit's own search does. A place shared from anywhere still goes through.
public enum SaudiArea {
    public static let center = CLLocationCoordinate2D(latitude: 24.0, longitude: 45.0)
    public static let region = MKCoordinateRegion(center: center, span: MKCoordinateSpan(latitudeDelta: 18, longitudeDelta: 22))

    /// The country's bounding box, used only when a result does not say its country.
    public static func contains(lat: Double, lon: Double) -> Bool {
        lat >= 16 && lat <= 33 && lon >= 34 && lon <= 56
    }
}

/// The chips. Each is a real MapKit category (`MKPointOfInterestCategory` has all six and none is faked). A mosque chip
/// would have no category behind it, which is why there is none.
public enum PlaceCategory: String, CaseIterable, Identifiable, Sendable {
    case gas, coffee, pharmacy, atm, hospital, restaurant

    public var id: String { rawValue }

    public var mapKit: MKPointOfInterestCategory {
        switch self {
        case .gas: return .gasStation
        case .coffee: return .cafe
        case .pharmacy: return .pharmacy
        case .atm: return .atm
        case .hospital: return .hospital
        case .restaurant: return .restaurant
        }
    }

    public var arabicName: String {
        switch self {
        case .gas: return "بنزين"
        case .coffee: return "قهوة"
        case .pharmacy: return "صيدلية"
        case .atm: return "صراف"
        case .hospital: return "مستشفى"
        case .restaurant: return "مطعم"
        }
    }

    public var symbol: String {
        switch self {
        case .gas: return "fuelpump.fill"
        case .coffee: return "cup.and.saucer.fill"
        case .pharmacy: return "pills.fill"
        case .atm: return "banknote.fill"
        case .hospital: return "cross.fill"
        case .restaurant: return "fork.knife"
        }
    }
}

public struct PlaceSuggestion: Identifiable, Equatable, @unchecked Sendable {
    public var title: String
    public var subtitle: String
    let completion: MKLocalSearchCompletion?

    public init(title: String, subtitle: String) {
        self.title = title
        self.subtitle = subtitle
        self.completion = nil
    }

    init(completion: MKLocalSearchCompletion) {
        self.title = completion.title
        self.subtitle = completion.subtitle
        self.completion = completion
    }

    public var id: String { title + "|" + subtitle }

    public static func == (a: PlaceSuggestion, b: PlaceSuggestion) -> Bool {
        a.title == b.title && a.subtitle == b.subtitle
    }
}

public enum PlaceSearchError: Error, Equatable, Sendable {
    /// Apple asked to slow down. The results on screen stay.
    case throttled
    case failed(String)
}

/// The search the screens use. The app's debug builds can swap in a stand-in; the release build has only MapKit.
@MainActor
public protocol PlaceSearchEngine: AnyObject {
    var onSuggestions: (([PlaceSuggestion]) -> Void)? { get set }
    func updateQuery(_ text: String, near: CLLocationCoordinate2D?)
    func search(text: String, near: CLLocationCoordinate2D?) async throws -> [PhonePlace]
    func search(suggestion: PlaceSuggestion, near: CLLocationCoordinate2D?) async throws -> [PhonePlace]
    func search(category: PlaceCategory, near: CLLocationCoordinate2D?) async throws -> [PhonePlace]
    func lookup(placeID: String) async -> PhonePlace?
}

/// Apple's search through MapKit. Results stay on the phone, shown on a real MapKit map, and the driver picks one by hand
/// (option 1 of section 2.6). Nothing here is cached and nothing feeds the unit's search or index.
@MainActor
public final class MapKitSearch: NSObject, PlaceSearchEngine, MKLocalSearchCompleterDelegate {
    private let completer = MKLocalSearchCompleter()
    public var onSuggestions: (([PlaceSuggestion]) -> Void)?

    public override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest, .query]
        completer.region = SaudiArea.region
    }


    public func updateQuery(_ text: String, near: CLLocationCoordinate2D?) {
        // Near the phone when it is known: a region only biases the results, so a named place elsewhere still comes up.
        completer.region = near.map { MKCoordinateRegion(center: $0, latitudinalMeters: 60_000, longitudinalMeters: 60_000) } ?? SaudiArea.region
        completer.queryFragment = text
    }

    public nonisolated func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let results = completer.results.map { PlaceSuggestion(completion: $0) }
        Task { @MainActor in self.onSuggestions?(results) }
    }

    public nonisolated func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        // A failed completion is not worth a message: the search itself reports.
    }

    public func search(text: String, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = text
        return try await run(request, near: near)
    }

    public func search(suggestion: PlaceSuggestion, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        guard let completion = suggestion.completion else { return try await search(text: suggestion.title, near: near) }
        return try await run(MKLocalSearch.Request(completion: completion), near: near)
    }

    public func search(category: PlaceCategory, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        try await PlaceQueries.category(category, near: near)
    }

    public func lookup(placeID: String) async -> PhonePlace? {
        await PlaceQueries.lookup(placeID: placeID)
    }

    private func run(_ request: MKLocalSearch.Request, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        try await PlaceQueries.run(request, near: near)
    }
}

/// The MapKit requests, usable from the app, the extension and the link check.
public enum PlaceQueries {
    /// Near the phone first; if nothing inside the country comes back, the whole country.
    public static func run(_ request: MKLocalSearch.Request, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        request.resultTypes = [.address, .pointOfInterest]
        if let near {
            request.region = MKCoordinateRegion(center: near, latitudinalMeters: 60_000, longitudinalMeters: 60_000)
            let places = try await start(request, near: near)
            if !places.isEmpty { return places }
        }
        request.region = SaudiArea.region
        return try await start(request, near: near)
    }

    public static func text(_ text: String, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = text
        return try await run(request, near: near)
    }

    /// `MKLocalPointsOfInterestRequest` needs no Arabic matching, which is why chips use it. It has a radius limit, so when
    /// nothing is that close, the same category through a wider search.
    public static func category(_ category: PlaceCategory, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        let filter = MKPointOfInterestFilter(including: [category.mapKit])
        if let near {
            let poi = MKLocalPointsOfInterestRequest(center: near, radius: min(5_000, MKLocalPointsOfInterestRequest.maxRadius))
            poi.pointOfInterestFilter = filter
            let places = try await collect(MKLocalSearch(request: poi), near: near)
            if !places.isEmpty { return places }
        }
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = category.arabicName
        request.pointOfInterestFilter = filter
        request.resultTypes = [.pointOfInterest]
        request.region = near.map { MKCoordinateRegion(center: $0, latitudinalMeters: 40_000, longitudinalMeters: 40_000) } ?? SaudiArea.region
        return try await start(request, near: near)
    }

    /// Place IDs need iOS 18. His phone has it; on an older one a saved place simply cannot be looked up.
    public static func lookup(placeID: String) async -> PhonePlace? {
        guard #available(iOS 18.0, macOS 15.0, *) else { return nil }
        guard let identifier = MKMapItem.Identifier(rawValue: placeID) else { return nil }
        let request = MKMapItemRequest(mapItemIdentifier: identifier)
        return await withCheckedContinuation { (continuation: CheckedContinuation<PhonePlace?, Never>) in
            request.getMapItem { item, _ in
                continuation.resume(returning: item.flatMap { place(from: $0) })
            }
        }
    }

    static func placeID(of item: MKMapItem) -> String? {
        if #available(iOS 18.0, macOS 15.0, *) { return item.identifier?.rawValue }
        return nil
    }

    private static func start(_ request: MKLocalSearch.Request, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        try await collect(MKLocalSearch(request: request), near: near)
    }

    private static func collect(_ search: MKLocalSearch, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        do {
            let response = try await search.start()
            let places = response.mapItems.filter(isInSaudi).compactMap { place(from: $0) }
            return sorted(places, near: near)
        } catch {
            if let mapKitError = error as? MKError {
                switch mapKitError.code {
                case .placemarkNotFound:
                    return []
                case .loadingThrottled:
                    throw PlaceSearchError.throttled
                default:
                    break
                }
            }
            throw PlaceSearchError.failed(error.localizedDescription)
        }
    }

    private static func isInSaudi(_ item: MKMapItem) -> Bool {
        if let code = item.placemark.countryCode, !code.isEmpty { return code.uppercased() == "SA" }
        let c = item.placemark.coordinate
        return SaudiArea.contains(lat: c.latitude, lon: c.longitude)
    }

    private static func place(from item: MKMapItem) -> PhonePlace? {
        let c = item.placemark.coordinate
        guard CLLocationCoordinate2DIsValid(c) else { return nil }
        let title = item.placemark.title
        let name = item.name ?? title
        return PhonePlace(name: name, detail: title == name ? nil : title, lat: c.latitude, lon: c.longitude,
                          confidence: .guess, src: PhonePlace.appleSearchSource, placeID: placeID(of: item))
    }

    private static func sorted(_ places: [PhonePlace], near: CLLocationCoordinate2D?) -> [PhonePlace] {
        guard let near else { return places }
        let here = CLLocation(latitude: near.latitude, longitude: near.longitude)
        return places.sorted { a, b in
            here.distance(from: CLLocation(latitude: a.lat, longitude: a.lon)) < here.distance(from: CLLocation(latitude: b.lat, longitude: b.lon))
        }
    }
}

public extension PhonePlace {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }

    /// Straight-line metres from a point: a real measure, and never presented as a route or a time.
    func distance(from point: CLLocationCoordinate2D) -> CLLocationDistance {
        CLLocation(latitude: lat, longitude: lon).distance(from: CLLocation(latitude: point.latitude, longitude: point.longitude))
    }
}
