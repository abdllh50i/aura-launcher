#if DEBUG
import AMRIKit
import MapKit

/// Fixed places in place of MapKit, so a test or a screenshot shows the same thing on every run. Debug builds only.
@MainActor
final class DemoSearch: PlaceSearchEngine {
    var onSuggestions: (([PlaceSuggestion]) -> Void)?

    static func place(_ name: String, _ detail: String, _ lat: Double, _ lon: Double, _ id: String) -> PhonePlace {
        PhonePlace(name: name, detail: detail, lat: lat, lon: lon, confidence: .guess, src: PhonePlace.appleSearchSource, placeID: id)
    }

    static let mall = place("الراشد مول", "طريق الملك فهد، الخبر", 26.290457, 50.184555, "demo-mall")
    static let cafe = place("قهوة الشاطئ", "كورنيش الخبر، الخبر", 26.3009, 50.2141, "demo-cafe")
    static let fuel = place("محطة وقود الظهران", "طريق الظهران، الخبر", 26.2795, 50.1630, "demo-fuel")
    static let pharmacy = place("صيدلية الحي", "حي العقربية، الخبر", 26.2967, 50.1958, "demo-pharmacy")
    static let long = place("مستشفى تجريبي باسم طويل جدًا عشان نشوف كيف ينكسر السطر في القائمة", "حي الجامعيين، طريق طويل جدًا يوصل إلى آخر المدينة، الدمام", 26.3927, 50.1980, "demo-long")
    static let all = [mall, cafe, fuel, pharmacy, long]

    func updateQuery(_ text: String, near: CLLocationCoordinate2D?) {
        guard !text.isEmpty else {
            onSuggestions?([])
            return
        }
        onSuggestions?(DemoSearch.all.filter { $0.name.contains(text) || text.count < 2 }.prefix(5).map { PlaceSuggestion(title: $0.name, subtitle: $0.detail ?? "") })
    }

    func search(text: String, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        try await Task.sleep(nanoseconds: 300_000_000)
        return DemoSearch.all.filter { $0.name.contains(text) }
    }

    func search(suggestion: PlaceSuggestion, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        try await Task.sleep(nanoseconds: 300_000_000)
        return DemoSearch.all.filter { $0.name == suggestion.title }
    }

    func search(category: PlaceCategory, near: CLLocationCoordinate2D?) async throws -> [PhonePlace] {
        try await Task.sleep(nanoseconds: 300_000_000)
        switch category {
        case .gas: return [DemoSearch.fuel]
        case .coffee: return [DemoSearch.cafe, DemoSearch.mall]
        case .pharmacy: return [DemoSearch.pharmacy]
        case .hospital: return [DemoSearch.long]
        case .atm, .restaurant: return DemoSearch.all
        }
    }

    func lookup(placeID: String) async -> PhonePlace? {
        DemoSearch.all.first { $0.placeID == placeID }
    }
}
#endif
