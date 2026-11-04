import AMRIKit
import MapKit
import SwiftUI

/// One place on its way to the car, shown as it really goes: read what was shared, find the place, find the car, send, and
/// say what the unit said. Never fire and forget. The share extension and the app's own send both run through this.
@MainActor
final class ShareFlowModel: ObservableObject {
    enum Stage: Equatable {
        case reading
        /// A guess: shown, and sent only when he says so.
        case confirm(PhonePlace)
        /// Search results to pick one from by hand.
        case pick([PhonePlace], query: String)
        case nothing
        case locating(PhonePlace)
        case sending(PhonePlace, attempt: Int, of: Int)
        /// The unit has it and is working. This is not "started".
        case waiting(PhonePlace)
        /// Sent with no token, so the screen asks on its own. The phone cannot see what happens next.
        case card(PhonePlace)
        /// The unit's own started event.
        case started(PhonePlace, NavEvent)
        /// Given to the other navigation app chosen on the screen, which says nothing back.
        case handed(PhonePlace)
        /// The screen kept the trip it had.
        case ignored(PhonePlace)
        /// Cancelled on the screen, or another place took its turn.
        case cancelled(PhonePlace)
        case routeFailed(PhonePlace, String?)
        case refused(PhonePlace, String?)
        case notPaired(PhonePlace)
        case unreachable(PhonePlace, denied: Bool, saved: Bool)
        /// It took the place, but no word of a start came in time.
        case noWord(PhonePlace)
    }

    @Published private(set) var stage: Stage = .reading
    /// The unit's last word about this send while it waits: routing, a card on the screen, waiting for GPS.
    @Published private(set) var unitWord: NavEvent?
    @Published private(set) var snapshot: UIImage?
    @Published private(set) var payload: SharePayload?
    @Published private(set) var trail: [String] = []
    @Published private(set) var copied = false

    private let store: SharedStore
    private let locator: UnitLocator
    private let resolver: ShareResolver
    /// The app can keep a place it could not deliver; the extension only can with an App Group.
    private let canKeepPending: Bool
    private var request: GoRequest?
    private var sendTask: Task<Void, Never>?
    private var snapshotFor: PhonePlace?

    init(store: SharedStore = .shared, locator: UnitLocator = UnitLocator(), resolver: ShareResolver? = nil, canKeepPending: Bool? = nil) {
        self.store = store
        self.locator = locator
        self.canKeepPending = canKeepPending ?? store.isShared
        self.resolver = resolver ?? ShareResolver(
            follow: { await ShareResolver.followOnce($0) },
            lookupPlaceID: { await PlaceQueries.lookup(placeID: $0) },
            search: { (try? await PlaceQueries.best($0, near: nil)) ?? [] })
    }

    /// For the test and screenshot screens: start in a given stage.
    init(previewStage: Stage, payload: SharePayload? = nil, unitWord: NavEvent? = nil) {
        self.store = .shared
        self.locator = UnitLocator()
        self.canKeepPending = true
        self.resolver = ShareResolver(follow: { _ in RedirectAnswer(status: nil, location: nil) }, lookupPlaceID: { _ in nil }, search: { _ in [] })
        self.stage = previewStage
        self.payload = payload
        self.unitWord = unitWord
    }

    func start(_ payload: SharePayload) {
        self.payload = payload
        store.lastShare = StoredShare(payload: payload)
        Task {
            let outcome = await resolver.resolve(payload)
            trail = outcome.trail
            remember(result: describe(outcome.resolution))
            switch outcome.resolution {
            case .place(let place):
                if place.confidence == .exact {
                    send(place)
                } else {
                    stage = .confirm(place)
                    loadSnapshot(for: place)
                }
            case .choices(let places, let query):
                if places.count == 1, let only = places.first {
                    stage = .confirm(only)
                    loadSnapshot(for: only)
                } else {
                    stage = .pick(places, query: query)
                }
            case .nothing:
                stage = .nothing
            }
        }
    }

    private func describe(_ resolution: ShareResolution) -> String {
        switch resolution {
        case .place(let place): return "\(place.confidence.rawValue) \(place.src) \(place.lat),\(place.lon) \(place.name)"
        case .choices(let places, let query): return "search \(query): \(places.count)"
        case .nothing: return "nothing"
        }
    }

    private func remember(result: String) {
        guard let payload else { return }
        store.lastShare = StoredShare(payload: payload, trail: trail, result: result)
    }

    /// The driver chose it, or it was exact. `saved` asks the unit for its own Home or Work. `fix` is the phone's own
    /// position, offered as the route's origin. `pendingID` keeps the id of a place that waited.
    func send(_ place: PhonePlace, saved which: String? = nil, fix: PhoneFix? = nil, pendingID: String? = nil) {
        sendTask?.cancel()
        // The same id only when the last try may have reached the unit without an answer. A retry after the unit's own
        // "no" is a new request: with the old id the unit would call it a duplicate and do nothing.
        let go: GoRequest
        if case .unreachable = stage, let earlier = request, earlier.place == place.wire {
            go = earlier
        } else if let pendingID {
            go = GoRequest(id: pendingID, place: place.wire, from: fix)
        } else {
            go = GoRequest(place: place.wire, from: fix)
        }
        request = go
        unitWord = nil
        stage = .locating(place)
        loadSnapshot(for: place)
        sendTask = Task { await run(go, place: place, saved: which) }
    }

    func cancel() {
        sendTask?.cancel()
    }

    func retry() {
        guard let place = stage.place else { return }
        send(place)
    }

    func copyPlace() {
        guard let place = stage.place else { return }
        UIPasteboard.general.string = "\(place.name)\nhttps://maps.apple.com/?ll=\(place.lat),\(place.lon)&q=\(place.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")"
        copied = true
    }

    func copyDetails() {
        guard let payload else { return }
        var lines = ["الأنواع: " + payload.typeIdentifiers.map { $0.joined(separator: ", ") }.joined(separator: " | ")]
        lines += payload.urls.map { "الرابط: \($0)" }
        lines += payload.texts.map { "النص: \($0)" }
        lines += trail.map { "خطوة: \($0)" }
        UIPasteboard.general.string = lines.joined(separator: "\n")
        copied = true
    }

    private func run(_ go: GoRequest, place: PhonePlace, saved which: String?) async {
        let pairing = store.pairing
        let last = pairing?.address ?? store.lastAddress
        let locator = self.locator
        let expected = pairing?.unit
        let token = pairing?.token
        let environment = DeliveryEnvironment(locate: {
            await locator.locate(expectedUnit: expected, last: last)
        }, post: { address, request, useToken, timeout in
            let client = LinkClient(address: address, token: useToken ? token : nil)
            if let which { return try await client.saved(which: which, id: request.id, timeout: timeout) }
            return try await client.go(request, timeout: timeout)
        })
        let outcome = await Delivery.run(go, hasToken: pairing != nil, known: last, environment: environment) { [weak self] step in
            Task { @MainActor in self?.show(step, place: place) }
        }
        if Task.isCancelled { return }

        switch outcome {
        case .accepted(let ack, let address, let usedToken):
            store.lastAddress = address
            store.removePending(id: go.id)
            if var stored = store.pairing, stored.address != address {
                stored.address = address
                store.pairing = stored
            }
            guard usedToken, let token else {
                stage = .card(place)
                remember(result: "sent: card on the screen")
                return
            }
            // A retry whose first try got through: the unit says what became of it.
            if ack.isDuplicate, ack.last == "started" {
                started(place, NavEvent(kind: .started, id: go.id))
                return
            }
            stage = .waiting(place)
            let client = LinkClient(address: address, token: token)
            let word = await client.waitForStart(id: ack.id ?? go.id, place: go.place, timeout: 45) { [weak self] event in
                Task { @MainActor in self?.hear(event) }
            }
            if Task.isCancelled { return }
            switch word {
            case .started(let nav):
                started(place, nav)
            case .failed(let reason):
                stage = .routeFailed(place, reason)
                remember(result: "route failed: \(reason ?? "")")
            case .handed:
                stage = .handed(place)
                remember(result: "handed to the other navigation app")
            case .ignored:
                stage = .ignored(place)
                remember(result: "the screen kept its trip")
            case .cancelled:
                stage = .cancelled(place)
                remember(result: "cancelled on the screen")
            case .noWord:
                stage = .noWord(place)
                remember(result: "accepted, no word of a start")
            }
        case .conflict(let reason):
            stage = .refused(place, reason)
            remember(result: "refused: \(reason)")
        case .refused(let status, let reason):
            stage = .refused(place, reason ?? String(status))
            remember(result: "refused \(status)")
        case .notPaired:
            stage = .notPaired(place)
            remember(result: "not paired, and not from the gateway")
        case .unreachable(let denied, _):
            // Kept for 12 hours when the app can see it, and sent with one tap there.
            let saved = canKeepPending && !denied
            if saved { store.addPending(PendingPlace(id: go.id, place: go.place)) }
            stage = .unreachable(place, denied: denied, saved: saved)
            remember(result: denied ? "local network denied" : "unit not found")
        }
    }

    private func started(_ place: PhonePlace, _ nav: NavEvent) {
        stage = .started(place, nav)
        remember(result: "started")
        // The only haptic there is: the unit's own started.
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    private func hear(_ event: NavEvent) {
        if case .waiting = stage { unitWord = event }
    }

    private func show(_ step: DeliveryProgress, place: PhonePlace) {
        // Late progress must not overwrite an answer.
        switch stage {
        case .locating, .sending:
            break
        default:
            return
        }
        switch step {
        case .locating:
            stage = .locating(place)
        case .sending(let attempt, let total):
            stage = .sending(place, attempt: attempt, of: total)
        }
    }

    /// A still picture of the map: no live `MKMapView` in the share sheet, because of the extension's memory limit.
    private func loadSnapshot(for place: PhonePlace) {
        guard snapshotFor != place else { return }
        snapshotFor = place
        snapshot = nil
        Task {
            let options = MKMapSnapshotter.Options()
            options.region = MKCoordinateRegion(center: place.coordinate, latitudinalMeters: 1400, longitudinalMeters: 1400)
            options.size = CGSize(width: 360, height: 150)
            if let image = try? await MKMapSnapshotter(options: options).start().image, snapshotFor == place {
                snapshot = image
            }
        }
    }
}

extension ShareFlowModel.Stage {
    var place: PhonePlace? {
        switch self {
        case .reading, .pick, .nothing:
            return nil
        case .confirm(let place), .locating(let place), .waiting(let place), .card(let place), .noWord(let place), .notPaired(let place),
             .handed(let place), .ignored(let place), .cancelled(let place):
            return place
        case .sending(let place, _, _), .started(let place, _), .routeFailed(let place, _), .refused(let place, _), .unreachable(let place, _, _):
            return place
        }
    }
}
