import Foundation

/// A place waiting to be sent, kept for 12 hours.
public struct PendingPlace: Codable, Equatable, Identifiable, Sendable {
    public static let lifetime: TimeInterval = 12 * 60 * 60

    public var id: String
    public var place: LinkPlace
    public var createdAt: Date

    public init(id: String, place: LinkPlace, createdAt: Date = Date()) {
        self.id = id
        self.place = place
        self.createdAt = createdAt
    }

    public func isFresh(now: Date = Date()) -> Bool {
        now.timeIntervalSince(createdAt) < PendingPlace.lifetime
    }
}

/// The last share as the extension saw it, for "فحص الربط".
public struct StoredShare: Codable, Equatable, Sendable {
    public var payload: SharePayload
    public var trail: [String]
    public var result: String?

    public init(payload: SharePayload, trail: [String] = [], result: String? = nil) {
        self.payload = payload
        self.trail = trail
        self.result = result
    }
}

/// An Apple-sourced favourite. Only the Place ID stays, which Apple allows, and it is looked up again when used. It never
/// goes to the unit (section 2.6, §2.5).
public struct PhoneSaved: Codable, Equatable, Identifiable, Sendable {
    public enum Slot: String, Codable, CaseIterable, Sendable {
        case home, work, favourite
    }

    public var slot: Slot
    public var placeID: String

    public init(slot: Slot, placeID: String) {
        self.slot = slot
        self.placeID = placeID
    }

    public var id: String { "\(slot.rawValue):\(placeID)" }
}

/// What the app and the extension keep. With an App Group the two share it. Without one, each has its own copy and the
/// extension simply has no token, which the protocol allows for: the unit then shows a confirm card (section 5.6).
public final class SharedStore: @unchecked Sendable {
    public static let shared = SharedStore()

    /// From this bundle's own profile, never hardcoded. Nil on a build signed with no group.
    public let groupID: String?
    /// True when the group really opens, so the other side can read what is written here.
    public let isShared: Bool
    private let defaults: UserDefaults

    private enum Key {
        static let pairing = "amri.pairing"
        static let pending = "amri.pending"
        static let lastShare = "amri.lastShare"
        static let phoneSaved = "amri.phoneSaved"
        static let lastAddress = "amri.lastAddress"
    }

    public init(bundle: Bundle = .main) {
        let group = ProvisioningProfile.embedded(in: bundle)?.appGroups.first
        if let group, FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil,
           let suite = UserDefaults(suiteName: group) {
            groupID = group
            isShared = true
            defaults = suite
            SharedStore.moveOver(from: .standard, to: suite)
        } else {
            groupID = group
            isShared = false
            defaults = .standard
        }
    }

    /// For tests.
    public init(defaults: UserDefaults, groupID: String? = nil, isShared: Bool = false) {
        self.defaults = defaults
        self.groupID = groupID
        self.isShared = isShared
    }

    /// A build that gained a group keeps its pairing.
    private static func moveOver(from old: UserDefaults, to new: UserDefaults) {
        for key in [Key.pairing, Key.lastAddress, Key.phoneSaved] where new.object(forKey: key) == nil {
            if let value = old.object(forKey: key) { new.set(value, forKey: key) }
        }
    }

    private func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func save<T: Encodable>(_ value: T?, _ key: String) {
        if let value, let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    // MARK: Pairing

    public var pairing: Pairing? {
        get { load(Pairing.self, Key.pairing) }
        set { save(newValue, Key.pairing) }
    }

    /// The last address that answered, paired or not.
    public var lastAddress: UnitAddress? {
        get { load(UnitAddress.self, Key.lastAddress) }
        set { save(newValue, Key.lastAddress) }
    }

    // MARK: Waiting places

    public func pendingPlaces(now: Date = Date()) -> [PendingPlace] {
        let all = load([PendingPlace].self, Key.pending) ?? []
        let fresh = all.filter { $0.isFresh(now: now) }
        if fresh.count != all.count { save(fresh, Key.pending) }
        return fresh
    }

    public func addPending(_ place: PendingPlace) {
        var all = pendingPlaces()
        all.removeAll { $0.id == place.id }
        all.append(place)
        save(Array(all.suffix(5)), Key.pending)
    }

    public func removePending(id: String) {
        var all = pendingPlaces()
        all.removeAll { $0.id == id }
        save(all, Key.pending)
    }

    // MARK: The last share

    public var lastShare: StoredShare? {
        get { load(StoredShare.self, Key.lastShare) }
        set { save(newValue, Key.lastShare) }
    }

    // MARK: Apple-sourced favourites

    public var phoneSaved: [PhoneSaved] {
        get { load([PhoneSaved].self, Key.phoneSaved) ?? [] }
        set { save(newValue, Key.phoneSaved) }
    }

    /// Clears everything (a debug launch argument for tests, and the unpair button).
    public func reset() {
        for key in [Key.pairing, Key.pending, Key.lastShare, Key.phoneSaved, Key.lastAddress] {
            defaults.removeObject(forKey: key)
        }
    }

    public func forgetPairing() {
        pairing = nil
        lastAddress = nil
    }
}
