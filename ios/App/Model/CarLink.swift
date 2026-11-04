import AMRIKit
import SwiftUI

/// The car as far as the phone truly knows it: finds it, listens to its events while the app is open, and sends what the
/// driver asks. Every number here came from the unit. Unknown stays nil.
@MainActor
final class CarLink: ObservableObject {
    enum Presence: Equatable {
        case looking
        /// A live event stream from the paired unit.
        case online(UnitAddress)
        /// An AMRI unit answers, and this phone is not paired with it.
        case unpaired(FoundUnit)
        case notFound(denied: Bool)
    }

    @Published private(set) var presence: Presence = .looking
    @Published private(set) var pairing: Pairing?
    @Published private(set) var unitPing: UnitPing?
    /// The trip's last word: idle, routing, waiting for GPS, started, progress, arrived. What a send hears is in `flow`.
    @Published private(set) var trip: NavEvent?
    @Published private(set) var tripName: String?
    @Published private(set) var music: MusicStatus?
    @Published private(set) var car: CarStatus?
    @Published private(set) var places = PlacesDoc()
    @Published private(set) var phoneSaved: [PhoneSaved] = []
    /// Apple's data for the saved Place IDs, looked up again each launch and kept in memory only (section 2.6).
    @Published private(set) var phoneLookups: [String: PhonePlace] = [:]
    @Published private(set) var pending: [PendingPlace] = []
    /// The paired token was refused: the unit forgot this phone.
    @Published private(set) var tokenRefused = false
    /// One short line about the last remote command or save, then gone.
    @Published private(set) var note: String?
    /// A send under way or just finished.
    @Published var flow: ShareFlowModel?

    let store: SharedStore
    let locator: UnitLocator
    let profile: ProvisioningProfile?
    private var loop: Task<Void, Never>?
    private var noteClear: Task<Void, Never>?
    private let demo: Bool

    init(store: SharedStore = .shared) {
        self.store = store
        if AppLaunch.reset { store.reset() }
        if let fake = AppLaunch.fakeUnit {
            locator = UnitLocator(includeBonjour: false, sweepEnabled: false, extraTargets: [fake])
        } else {
            locator = UnitLocator(includeBonjour: true)
        }
        profile = ProvisioningProfile.embedded()
        demo = AppLaunch.screen != nil
        pairing = store.pairing
        phoneSaved = store.phoneSaved
        pending = store.pendingPlaces()
    }

    // MARK: The loop

    func start() {
        guard !demo, loop == nil else { return }
        pending = store.pendingPlaces()
        loop = Task { [weak self] in await self?.run() }
        Task { [weak self] in await self?.lookUpPhoneSaved() }
    }

    func stop() {
        loop?.cancel()
        loop = nil
    }

    /// He asked to look again now.
    func retryNow() {
        stop()
        presence = .looking
        start()
    }

    private func run() async {
        var delay: TimeInterval = 3
        while !Task.isCancelled {
            let paired = pairing
            let outcome = await locator.locate(expectedUnit: paired?.unit, last: paired?.address ?? store.lastAddress)
            if Task.isCancelled { return }
            guard let found = outcome.found else {
                unitPing = nil
                presence = .notFound(denied: outcome.localNetworkDenied)
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                // A home network's sweep is not free: look less often while nothing answers.
                delay = min(delay * 2, 30)
                continue
            }
            delay = 3
            store.lastAddress = found.address
            unitPing = found.ping
            if var paired, !tokenRefused {
                if paired.address != found.address {
                    paired.address = found.address
                    store.pairing = paired
                    pairing = paired
                }
                await listen(to: found, token: paired.token)
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            } else {
                presence = .unpaired(found)
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    /// Until the stream ends. A 401 or 403 means the unit no longer knows this phone.
    private func listen(to found: FoundUnit, token: String) async {
        let client = LinkClient(address: found.address, token: token)
        presence = .online(found.address)
        await refreshPlaces(client)
        do {
            for try await event in client.events() {
                apply(event)
            }
        } catch LinkError.unauthorized {
            tokenRefused = true
            presence = .unpaired(found)
            return
        } catch {
            // The hotspot blinked: the loop looks again.
        }
        if !Task.isCancelled { presence = .looking }
    }

    private func apply(_ event: LinkEvent) {
        switch event {
        case .nav(let nav):
            apply(nav: nav)
        case .music(let status):
            music = status
        case .car(let status):
            car = status
        case .snapshot(let snapshot):
            if let nav = snapshot.nav { apply(nav: nav) }
            if let status = snapshot.music { music = status }
            if let status = snapshot.car { car = status }
        case .other:
            break
        }
    }

    private func apply(nav: NavEvent) {
        switch nav.kind {
        case .idle, .stopped:
            trip = NavEvent(kind: .idle)
            tripName = nil
        case .routing, .waitingFix, .arrived:
            trip = nav
            if let name = nav.name { tripName = name }
        case .started, .progress:
            trip = nav
            if let name = nav.name ?? flow?.stage.place?.name { tripName = name }
        case .failed:
            if trip?.kind.isUnderWay != true {
                trip = NavEvent(kind: .idle)
                tripName = nil
            }
        case .card, .held, .handed, .ignored, .replaced, .other:
            // About one send, not about the trip.
            break
        }
    }

    // MARK: Pairing

    func didPair(_ new: Pairing) {
        store.pairing = new
        if let address = new.address { store.lastAddress = address }
        pairing = new
        tokenRefused = false
        retryNow()
    }

    func unpair() {
        store.forgetPairing()
        pairing = nil
        trip = nil
        music = nil
        car = nil
        places = PlacesDoc()
        retryNow()
    }

    // MARK: Sending

    func send(_ place: PhonePlace, saved which: String? = nil, fix: PhoneFix?, pendingID: String? = nil) {
        flow?.cancel()
        let model = ShareFlowModel(store: store, locator: locator, canKeepPending: true)
        flow = model
        model.send(place, saved: which, fix: fix, pendingID: pendingID)
    }

    func closeFlow() {
        flow?.cancel()
        flow = nil
        pending = store.pendingPlaces()
    }

    func sendPending(_ item: PendingPlace, fix: PhoneFix?) {
        let place = PhonePlace(name: item.place.name, detail: item.place.detail, lat: item.place.lat, lon: item.place.lon,
                               confidence: .exact, src: item.place.src ?? "phone")
        send(place, fix: fix, pendingID: item.id)
    }

    func dropPending(_ item: PendingPlace) {
        store.removePending(id: item.id)
        pending = store.pendingPlaces()
    }

    /// The client for what needs a live, paired unit.
    private var client: LinkClient? {
        guard case .online(let address) = presence, let token = pairing?.token else { return nil }
        return LinkClient(address: address, token: token)
    }

    var isOnline: Bool {
        if case .online = presence { return true }
        return false
    }

    func stopTrip() {
        guard let client else {
            say(String(localized: "السيارة مو متصلة الحين"))
            return
        }
        Task {
            do {
                try await client.stop()
                say(String(localized: "طلبت من الشاشة توقف الاتجاهات"))
            } catch {
                say(problem(error))
            }
        }
    }

    // MARK: The remote

    func remote(_ command: RemoteCommand) {
        guard let client else {
            say(String(localized: "السيارة مو متصلة الحين"))
            return
        }
        Task {
            do {
                let reply = try await client.remote(command)
                // What the unit really did, before its event arrives.
                if let volume = reply.volume { music?.volume = volume }
                if let muted = reply.muted { music?.muted = muted }
            } catch {
                say(problem(error))
            }
        }
    }

    private func problem(_ error: Error) -> String {
        switch error as? LinkError {
        case .conflict(let reason)?, .unauthorized(let reason?)?, .refused(_, let reason?)?:
            return Formats.reason(reason) ?? String(localized: "الشاشة ما قبلت")
        case .localNetworkDenied?:
            return String(localized: "الشبكة المحلية مقفلة على عمري")
        default:
            return String(localized: "ما ردت الشاشة")
        }
    }

    private func say(_ text: String) {
        note = text
        noteClear?.cancel()
        noteClear = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { self?.note = nil }
        }
    }

    // MARK: Places

    private func refreshPlaces(_ client: LinkClient) async {
        if let doc = try? await client.places() { places = doc }
    }

    private func lookUpPhoneSaved() async {
        for saved in store.phoneSaved where phoneLookups[saved.placeID] == nil {
            if let place = await PlaceQueries.lookup(placeID: saved.placeID) {
                phoneLookups[saved.placeID] = place
            }
        }
    }

    /// Home or Work, the phone's choice first. `which` is set when the unit itself holds it.
    func quick(_ slot: PhoneSaved.Slot) -> (place: PhonePlace?, which: String?, isSet: Bool) {
        if let saved = phoneSaved.first(where: { $0.slot == slot }) {
            return (phoneLookups[saved.placeID], nil, true)
        }
        let wire = slot == .home ? places.home : places.work
        guard let wire else { return (nil, nil, false) }
        let place = PhonePlace(name: wire.name, detail: wire.detail, lat: wire.lat, lon: wire.lon, confidence: .exact, src: wire.src ?? "unit")
        return (place, slot == .home ? "home" : "work", true)
    }

    /// Favourites from both homes: the unit's own, and the phone's looked-up Place IDs.
    var favourites: [PhonePlace] {
        let fromUnit = places.favourites.map { PhonePlace(name: $0.name, detail: $0.detail, lat: $0.lat, lon: $0.lon, confidence: .exact, src: $0.src ?? "unit") }
        let fromPhone = phoneSaved.filter { $0.slot == .favourite }.compactMap { phoneLookups[$0.placeID] }
        return fromPhone + fromUnit
    }

    var recents: [PhonePlace] {
        places.recents.map { PhonePlace(name: $0.name, detail: $0.detail, lat: $0.lat, lon: $0.lon, confidence: .exact, src: $0.src ?? "unit") }
    }

    func isFavourite(_ place: PhonePlace) -> Bool {
        if let id = place.placeID, phoneSaved.contains(where: { $0.slot == .favourite && $0.placeID == id }) { return true }
        return places.favourites.contains { abs($0.lat - place.lat) < 0.00001 && abs($0.lon - place.lon) < 0.00001 }
    }

    /// A place from Apple's data stays on the phone as a Place ID, which the licence allows. Anything else goes to the
    /// unit, which is the source of truth for places (section 2.6, §2.5).
    func save(_ place: PhonePlace, as slot: PhoneSaved.Slot) {
        if place.isAppleSourced {
            guard let id = place.placeID else {
                say(String(localized: "هذا المكان من خرائط أبل وما له رقم يتحفظ"))
                return
            }
            var list = store.phoneSaved
            if slot != .favourite { list.removeAll { $0.slot == slot } }
            list.removeAll { $0.slot == slot && $0.placeID == id }
            list.append(PhoneSaved(slot: slot, placeID: id))
            store.phoneSaved = list
            phoneSaved = list
            phoneLookups[id] = place
            say(String(localized: "انحفظ على الجوال"))
            return
        }
        guard let client else {
            say(String(localized: "السيارة مو متصلة الحين"))
            return
        }
        Task {
            do {
                var doc = try await client.places()
                let wire = place.wire
                switch slot {
                case .home: doc.home = wire
                case .work: doc.work = wire
                case .favourite:
                    doc.favourites.removeAll { $0.lat == wire.lat && $0.lon == wire.lon }
                    doc.favourites.append(wire)
                }
                try await client.putPlaces(doc)
                places = try await client.places()
                if slot != .favourite {
                    var list = store.phoneSaved
                    list.removeAll { $0.slot == slot }
                    store.phoneSaved = list
                    phoneSaved = list
                }
                say(String(localized: "انحفظ على الشاشة"))
            } catch {
                say(problem(error))
            }
        }
    }

    func removeFavourite(_ place: PhonePlace) {
        if let id = place.placeID, phoneSaved.contains(where: { $0.slot == .favourite && $0.placeID == id }) {
            var list = store.phoneSaved
            list.removeAll { $0.slot == .favourite && $0.placeID == id }
            store.phoneSaved = list
            phoneSaved = list
            phoneLookups[id] = nil
            return
        }
        guard let client else {
            say(String(localized: "السيارة مو متصلة الحين"))
            return
        }
        Task {
            do {
                var doc = try await client.places()
                doc.favourites.removeAll { $0.lat == place.lat && $0.lon == place.lon }
                try await client.putPlaces(doc)
                places = try await client.places()
            } catch {
                say(problem(error))
            }
        }
    }

    // MARK: The test and screenshot runs

    #if DEBUG
    func setDemo(presence: Presence, pairing: Pairing? = nil, ping: UnitPing? = nil, trip: NavEvent? = nil, tripName: String? = nil,
                 music: MusicStatus? = nil, car: CarStatus? = nil, places: PlacesDoc = PlacesDoc(), pending: [PendingPlace] = [], note: String? = nil, flow: ShareFlowModel? = nil) {
        self.presence = presence
        self.pairing = pairing
        self.unitPing = ping
        self.trip = trip
        self.tripName = tripName
        self.music = music
        self.car = car
        self.places = places
        self.pending = pending
        self.note = note
        self.flow = flow
    }
    #endif
}
