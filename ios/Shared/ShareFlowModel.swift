import AMRIKit
import MapKit
import SwiftUI

/// The share sheet's one job, shown as it really goes: read what was shared, find the place, find the car, send, and say
/// what the unit said. Never fire and forget.
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
        case routeFailed(PhonePlace, String?)
        case refused(PhonePlace, String?)
        case notPaired(PhonePlace)
        case unreachable(PhonePlace, denied: Bool, saved: Bool)
        /// It took the place, but no word of a start came in time.
        case noWord(PhonePlace)
    }

    @Published private(set) var stage: Stage = .reading
    @Published private(set) var snapshot: UIImage?
    @Published private(set) var payload: SharePayload?
    @Published private(set) var trail: [String] = []
    @Published private(set) var copied = false

    private let store: SharedStore
    private let locator: UnitLocator
    private let resolver: ShareResolver
    private var request: GoRequest?
    private var sendTask: Task<Void, Never>?
    private var snapshotFor: PhonePlace?

    init(store: SharedStore = .shared, locator: UnitLocator = UnitLocator(), resolver: ShareResolver? = nil) {
        self.store = store
        self.locator = locator
        self.resolver = resolver ?? ShareResolver(
            follow: { await ShareResolver.followOnce($0) },
            lookupPlaceID: { await PlaceQueries.lookup(placeID: $0) },
            search: { (try? await PlaceQueries.text($0, near: nil)) ?? [] })
    }

    /// For the app's debug screens: start in a given stage.
    init(previewStage: Stage, payload: SharePayload? = nil) {
        self.store = .shared
        self.locator = UnitLocator()
        self.resolver = ShareResolver(follow: { _ in RedirectAnswer(status: nil, location: nil) }, lookupPlaceID: { _ in nil }, search: { _ in [] })
        self.stage = previewStage
        self.payload = payload
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

    /// The driver chose it, or it was exact.
    func send(_ place: PhonePlace) {
        sendTask?.cancel()
        // The same id only when the last try may have reached the unit without an answer. A retry after the unit's own
        // "no" is a new request: with the old id the unit would call it a duplicate and do nothing.
        let go: GoRequest
        if case .unreachable = stage, let earlier = request, earlier.place == place.wire {
            go = earlier
        } else {
            go = GoRequest(place: place.wire)
        }
        request = go
        stage = .locating(place)
        loadSnapshot(for: place)
        sendTask = Task { await run(go, place: place) }
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

    private func run(_ go: GoRequest, place: PhonePlace) async {
        let pairing = store.pairing
        let last = pairing?.address ?? store.lastAddress
        let locator = self.locator
        let expected = pairing?.unit
        let environment = DeliveryEnvironment.live(token: pairing?.token, locate: {
            await locator.locate(expectedUnit: expected, last: last)
        })
        let outcome = await Delivery.run(go, hasToken: pairing != nil, known: last, environment: environment) { [weak self] step in
            Task { @MainActor in self?.show(step, place: place) }
        }
        if Task.isCancelled { return }

        switch outcome {
        case .accepted(let ack, let address, let usedToken):
            store.lastAddress = address
            if var stored = store.pairing, stored.address != address {
                stored.address = address
                store.pairing = stored
            }
            guard usedToken, let token = pairing?.token else {
                stage = .card(place)
                remember(result: "sent: card on the screen")
                return
            }
            stage = .waiting(place)
            let client = LinkClient(address: address, token: token)
            let word = await client.waitForStart(id: ack.id ?? go.id, timeout: 45)
            if Task.isCancelled { return }
            switch word {
            case .started(let nav):
                stage = .started(place, nav)
                remember(result: "started")
                // The only haptic there is: the unit's own started.
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            case .failed(let reason):
                stage = .routeFailed(place, reason)
                remember(result: "route failed: \(reason ?? "")")
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
            let saved = store.isShared && !denied
            if saved { store.addPending(PendingPlace(id: go.id, place: go.place)) }
            stage = .unreachable(place, denied: denied, saved: saved)
            remember(result: denied ? "local network denied" : "unit not found")
        }
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
        case .confirm(let place), .locating(let place), .waiting(let place), .card(let place), .noWord(let place), .notPaired(let place):
            return place
        case .sending(let place, _, _), .started(let place, _), .routeFailed(let place, _), .refused(let place, _), .unreachable(let place, _, _):
            return place
        }
    }
}
