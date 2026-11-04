import Foundation

/// What a share sheet handed over, raw. Kept as it came: "فحص الربط" reports it, because what Apple Maps and Google Maps
/// really share on his iOS version is one of the open questions.
public struct SharePayload: Codable, Equatable, Sendable {
    /// `registeredTypeIdentifiers` of each attachment.
    public var typeIdentifiers: [[String]]
    public var urls: [String]
    public var texts: [String]
    public var mapItem: SharedMapItem?
    public var at: Date

    public init(typeIdentifiers: [[String]] = [], urls: [String] = [], texts: [String] = [], mapItem: SharedMapItem? = nil, at: Date = Date()) {
        self.typeIdentifiers = typeIdentifiers
        self.urls = urls
        self.texts = texts
        self.mapItem = mapItem
        self.at = at
    }

    public var isEmpty: Bool { urls.isEmpty && texts.isEmpty && mapItem == nil }
}

/// A `com.apple.mapkit.map-item` provider's content, when Apple Maps sends one.
public struct SharedMapItem: Codable, Equatable, Sendable {
    public var name: String?
    public var detail: String?
    public var lat: Double
    public var lon: Double
    public var placeID: String?

    public init(name: String? = nil, detail: String? = nil, lat: Double, lon: Double, placeID: String? = nil) {
        self.name = name
        self.detail = detail
        self.lat = lat
        self.lon = lon
        self.placeID = placeID
    }
}

/// How sure the phone is. **exact**: send at once. **guess** (a viewport, a search by name): show it and ask.
public enum ShareConfidence: String, Codable, Sendable {
    case exact, guess
}

/// One place on the phone, from search or from a share.
public struct PhonePlace: Codable, Hashable, Sendable, Identifiable {
    /// The name shown when nothing better is known. It reaches the car screen, so it is Arabic in every language.
    public static let unnamed = "موقع من الجوال"
    /// Names Apple on purpose: the unit refuses to keep a place whose source says so.
    public static let appleSearchSource = "apple.mapkit"

    public var name: String
    public var detail: String?
    public var lat: Double
    public var lon: Double
    public var confidence: ShareConfidence
    public var src: String
    /// Apple's Place ID: the one thing that may be kept (the licence's §2.5).
    public var placeID: String?

    public init(name: String?, detail: String? = nil, lat: Double, lon: Double, confidence: ShareConfidence, src: String, placeID: String? = nil) {
        let cleaned = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.name = cleaned.isEmpty ? PhonePlace.unnamed : cleaned
        self.detail = detail
        self.lat = lat
        self.lon = lon
        self.confidence = confidence
        self.src = src
        self.placeID = placeID
    }

    public var id: String { placeID ?? "\(src):\(lat),\(lon),\(name)" }

    /// What goes to the unit.
    public var wire: LinkPlace { LinkPlace(name: name, detail: detail, lat: lat, lon: lon, src: src) }

    /// Apple's data may reach the car one place at a time, by hand, and may not be stored there (section 2.6).
    public var isAppleSourced: Bool { src == "mapkit" || src.contains("apple") }
}
