import Foundation

/// Where a unit answers. Never fixed in the code: it comes from the QR code, the last success or a sweep.
public struct UnitAddress: Codable, Hashable, Sendable {
    public static let defaultPort: UInt16 = 47101

    public var host: String
    public var port: UInt16

    public init(host: String, port: UInt16 = UnitAddress.defaultPort) {
        self.host = host
        self.port = port
    }

    public var baseURL: URL {
        var parts = URLComponents()
        parts.scheme = "http"
        parts.host = host
        parts.port = Int(port)
        return parts.url ?? URL(fileURLWithPath: "/")
    }

    public var text: String { "\(host):\(port)" }
}

/// `GET /v1/ping`. No secrets in it.
public struct UnitPing: Codable, Equatable, Sendable {
    public var app: String
    public var unit: String?
    public var name: String?
    public var version: String?
    public var proto: Int?

    public init(app: String, unit: String? = nil, name: String? = nil, version: String? = nil, proto: Int? = nil) {
        self.app = app
        self.unit = unit
        self.name = name
        self.version = version
        self.proto = proto
    }

    public var isAMRI: Bool { app == "AMRI" }
}

/// What pairing leaves behind on the phone.
public struct Pairing: Codable, Equatable, Sendable {
    public var token: String
    public var unit: String
    public var name: String
    public var address: UnitAddress?
    public var pairedAt: Date

    public init(token: String, unit: String, name: String, address: UnitAddress?, pairedAt: Date = Date()) {
        self.token = token
        self.unit = unit
        self.name = name
        self.address = address
        self.pairedAt = pairedAt
    }
}

/// A place as the Link carries it (`Place` on the unit: name, detail, lat, lon).
public struct LinkPlace: Codable, Hashable, Sendable, Identifiable {
    public var name: String
    public var detail: String?
    public var lat: Double
    public var lon: Double
    /// For the unit's log only. An Apple-sourced place must never enter any index there.
    public var src: String?

    public init(name: String, detail: String? = nil, lat: Double, lon: Double, src: String? = nil) {
        self.name = name
        self.detail = detail
        self.lat = lat
        self.lon = lon
        self.src = src
    }

    public var id: String { "\(lat),\(lon),\(name)" }
}

/// The phone's own fix, offered as the route's origin while the unit has no GPS yet.
public struct PhoneFix: Codable, Equatable, Sendable {
    public var lat: Double
    public var lon: Double
    public var acc: Double
    public var ageSec: Int

    public init(lat: Double, lon: Double, acc: Double, ageSec: Int) {
        self.lat = lat
        self.lon = lon
        self.acc = acc
        self.ageSec = ageSec
    }

    /// Only a recent and decent fix is offered (under 60 s old, under 100 m).
    public static func usable(lat: Double, lon: Double, accuracy: Double, age: TimeInterval) -> PhoneFix? {
        guard accuracy >= 0, accuracy < 100, age >= 0, age < 60 else { return nil }
        return PhoneFix(lat: lat, lon: lon, acc: accuracy, ageSec: Int(age))
    }
}

/// `POST /v1/nav/go`.
public struct GoRequest: Codable, Equatable, Sendable {
    public var id: String
    public var place: LinkPlace
    public var from: PhoneFix?
    public var mode: String

    public init(id: String = RequestID.new(), place: LinkPlace, from: PhoneFix? = nil, mode: String = "start") {
        self.id = id
        self.place = place
        self.from = from
        self.mode = mode
    }
}

/// The 202 reply: the unit took it (`accepted`), or had it already (`duplicate`). Neither one means the trip started.
public struct GoAck: Codable, Equatable, Sendable {
    public var id: String?
    public var state: String

    public init(id: String?, state: String) {
        self.id = id
        self.state = state
    }

    public var isDuplicate: Bool { state == "duplicate" }
}

/// Ids in the same family as the protocol's example: time first, then randomness, Crockford base32.
public enum RequestID {
    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    public static func new(now: Date = Date()) -> String {
        var millis = UInt64(max(0, now.timeIntervalSince1970) * 1000)
        var head: [Character] = []
        for _ in 0..<10 {
            head.append(alphabet[Int(millis & 31)])
            millis >>= 5
        }
        let tail = (0..<6).map { _ in alphabet[Int.random(in: 0..<alphabet.count)] }
        return String(head.reversed()) + String(tail)
    }
}

/// `GET`, `PUT /v1/places`. The unit is the source of truth.
public struct PlacesDoc: Codable, Equatable, Sendable {
    public var home: LinkPlace?
    public var work: LinkPlace?
    public var favourites: [LinkPlace]
    public var recents: [LinkPlace]

    public init(home: LinkPlace? = nil, work: LinkPlace? = nil, favourites: [LinkPlace] = [], recents: [LinkPlace] = []) {
        self.home = home
        self.work = work
        self.favourites = favourites
        self.recents = recents
    }

    private enum CodingKeys: String, CodingKey {
        case home, work, favourites, favorites, recents
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        home = try? c.decodeIfPresent(LinkPlace.self, forKey: .home)
        work = try? c.decodeIfPresent(LinkPlace.self, forKey: .work)
        favourites = (try? c.decodeIfPresent([LinkPlace].self, forKey: .favourites))
            ?? (try? c.decodeIfPresent([LinkPlace].self, forKey: .favorites))
            ?? []
        recents = (try? c.decodeIfPresent([LinkPlace].self, forKey: .recents)) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        // Home and Work are written even when empty, so clearing one reaches the unit.
        try c.encode(home, forKey: .home)
        try c.encode(work, forKey: .work)
        try c.encode(favourites, forKey: .favourites)
        try c.encode(recents, forKey: .recents)
    }
}

/// The commands of `POST /v1/remote` this app sends.
public enum RemoteCommand: String, CaseIterable, Sendable {
    case musicToggle = "music.toggle"
    case musicNext = "music.next"
    case musicPrev = "music.prev"
    case volumeUp = "volume.up"
    case volumeDown = "volume.down"
}

/// What can go wrong on the Link, in the terms the screens need.
public enum LinkError: Error, Equatable, Sendable {
    /// No answer in time, or no connection at all.
    case noAnswer
    /// iOS refused: Local Network is off for this app.
    case localNetworkDenied
    /// 401 or 403: no valid token, and this peer gets no card either.
    case unauthorized(String?)
    /// 409, with the unit's own reason.
    case conflict(String)
    /// Any other refusal.
    case refused(Int, String?)
    /// It answered, but not with what the protocol says.
    case badReply
    case cancelled

    static func from(_ error: Error) -> LinkError {
        if let link = error as? LinkError { return link }
        if error is CancellationError { return .cancelled }
        if let url = error as? URLError {
            if url.code == .cancelled { return .cancelled }
            // URLSession has no typed value for this. The path it attaches names the reason.
            let path = (url as NSError).userInfo["_NSURLErrorNWPathKey"].map { String(describing: $0) } ?? ""
            if path.localizedCaseInsensitiveContains("local network prohibited") { return .localNetworkDenied }
        }
        return .noAnswer
    }
}

/// Reads loose JSON: numbers may come as text, and a key the unit does not send stays unknown.
enum JSONField {
    static func string(_ value: Any?) -> String? {
        switch value {
        case let text as String:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        case let number as NSNumber:
            return number.stringValue
        default:
            return nil
        }
    }

    static func double(_ value: Any?) -> Double? {
        switch value {
        case let number as NSNumber:
            return number.doubleValue
        case let text as String:
            return Double(text)
        default:
            return nil
        }
    }

    static func int(_ value: Any?) -> Int? { double(value).map { Int($0) } }

    static func bool(_ value: Any?) -> Bool? {
        switch value {
        case let number as NSNumber:
            return number.boolValue
        case let text as String:
            switch text.lowercased() {
            case "true", "yes", "1", "on": return true
            case "false", "no", "0", "off": return false
            default: return nil
            }
        default:
            return nil
        }
    }

    static func first(_ json: [String: Any], _ keys: String...) -> Any? {
        for key in keys {
            if let value = json[key], !(value is NSNull) { return value }
        }
        return nil
    }
}

/// One `nav` event: `routing`, `started {distanceM, durationS, etaLocal}`, `failed {reason}`,
/// `progress {remainingM, timeLeftS, etaLocal, nextText}`, `arrived`. Numbers the unit did not send stay nil.
public struct NavEvent: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        case idle, routing, started, progress, failed, arrived, stopped, card
        case other(String)

        init(raw: String) {
            switch raw.lowercased() {
            case "idle", "none", "off": self = .idle
            case "routing": self = .routing
            case "started", "start": self = .started
            case "progress", "navigating", "active": self = .progress
            case "failed", "error": self = .failed
            case "arrived": self = .arrived
            case "stopped", "ended", "cancelled", "canceled", "stop": self = .stopped
            case "card", "waiting": self = .card
            default: self = .other(raw)
            }
        }
    }

    public var kind: Kind
    public var id: String?
    public var name: String?
    public var distanceM: Double?
    public var durationS: Double?
    public var remainingM: Double?
    public var timeLeftS: Double?
    public var etaLocal: String?
    public var nextText: String?
    public var reason: String?

    public init(kind: Kind, id: String? = nil, name: String? = nil, distanceM: Double? = nil, durationS: Double? = nil,
                remainingM: Double? = nil, timeLeftS: Double? = nil, etaLocal: String? = nil, nextText: String? = nil,
                reason: String? = nil) {
        self.kind = kind
        self.id = id
        self.name = name
        self.distanceM = distanceM
        self.durationS = durationS
        self.remainingM = remainingM
        self.timeLeftS = timeLeftS
        self.etaLocal = etaLocal
        self.nextText = nextText
        self.reason = reason
    }

    public init?(json: [String: Any]) {
        guard let raw = JSONField.string(JSONField.first(json, "state", "type", "event", "phase", "status")) else { return nil }
        kind = Kind(raw: raw)
        id = JSONField.string(json["id"])
        let place = json["place"] as? [String: Any]
        name = JSONField.string(JSONField.first(json, "name", "destName", "destination")) ?? JSONField.string(place?["name"])
        distanceM = JSONField.double(json["distanceM"])
        durationS = JSONField.double(json["durationS"])
        remainingM = JSONField.double(json["remainingM"])
        timeLeftS = JSONField.double(json["timeLeftS"])
        etaLocal = JSONField.string(json["etaLocal"])
        nextText = JSONField.string(json["nextText"])
        reason = JSONField.string(json["reason"])
    }
}

/// One `music` event: what the unit says it plays. Unknown stays nil.
public struct MusicStatus: Equatable, Sendable {
    public var title: String?
    public var artist: String?
    public var source: String?
    public var playing: Bool?
    public var positionS: Double?
    public var durationS: Double?

    public init(title: String? = nil, artist: String? = nil, source: String? = nil, playing: Bool? = nil,
                positionS: Double? = nil, durationS: Double? = nil) {
        self.title = title
        self.artist = artist
        self.source = source
        self.playing = playing
        self.positionS = positionS
        self.durationS = durationS
    }

    public init(json: [String: Any]) {
        title = JSONField.string(json["title"])
        artist = JSONField.string(json["artist"])
        source = JSONField.string(json["source"])
        playing = JSONField.bool(JSONField.first(json, "playing", "isPlaying"))
        positionS = JSONField.double(JSONField.first(json, "positionS", "position"))
        durationS = JSONField.double(JSONField.first(json, "durationS", "duration"))
    }
}

/// One `car` event. The volume is the car's own step, never a percentage.
public struct CarStatus: Equatable, Sendable {
    public var volumeStep: Int?
    public var volumeMax: Int?
    public var speedKmh: Double?
    public var gpsFix: Bool?
    public var reversing: Bool?

    public init(volumeStep: Int? = nil, volumeMax: Int? = nil, speedKmh: Double? = nil, gpsFix: Bool? = nil, reversing: Bool? = nil) {
        self.volumeStep = volumeStep
        self.volumeMax = volumeMax
        self.speedKmh = speedKmh
        self.gpsFix = gpsFix
        self.reversing = reversing
    }

    public init(json: [String: Any]) {
        volumeStep = JSONField.int(JSONField.first(json, "volumeStep", "volume"))
        volumeMax = JSONField.int(JSONField.first(json, "volumeMax", "volumeSteps"))
        speedKmh = JSONField.double(JSONField.first(json, "speedKmh", "speed"))
        gpsFix = JSONField.bool(JSONField.first(json, "gpsFix", "gps"))
        reversing = JSONField.bool(JSONField.first(json, "reversing", "reverse"))
    }
}

/// `GET /v1/state`, and the same shape if the unit sends one snapshot event on connect.
public struct UnitSnapshot: Equatable, Sendable {
    public var nav: NavEvent?
    public var music: MusicStatus?
    public var car: CarStatus?

    public init(nav: NavEvent? = nil, music: MusicStatus? = nil, car: CarStatus? = nil) {
        self.nav = nav
        self.music = music
        self.car = car
    }

    public init(json: [String: Any]) {
        nav = (json["nav"] as? [String: Any]).flatMap { NavEvent(json: $0) }
        music = (json["music"] as? [String: Any]).map { MusicStatus(json: $0) }
        car = (json["car"] as? [String: Any]).map { CarStatus(json: $0) }
    }
}
