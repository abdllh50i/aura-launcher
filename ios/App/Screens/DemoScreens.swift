#if DEBUG
import AMRIKit
import MapKit
import SwiftUI

/// Screens in a set state for tools/capture_simulator.py: `-screen <name>`. No network and no real data. Debug builds only.
@MainActor
enum DemoScreens {
    static let address = UnitAddress(host: "172.20.10.3")
    static let ping = UnitPing(app: "AMRI", unit: "k2501-ab12", name: "K2501", version: "2.0.0", proto: 2)
    static let pairing = Pairing(token: "demo", unit: "k2501-ab12", name: "K2501", address: address)
    static let music = MusicStatus(title: "يا طير", artist: "فنان تجريبي", source: "naghm", playing: true, volume: 12, maxVolume: 40, muted: false)
    static let car = CarStatus(speedKmh: 62, gpsFix: true, reversing: false, inCall: false)
    static let started = NavEvent(kind: .started, id: "D", distanceM: 18400, durationS: 1380, etaLocal: "9:41")
    static let progress = NavEvent(kind: .progress, name: "الراشد مول", remainingM: 16200, timeLeftS: 1260, etaLocal: "9:41", nextText: "بعد ٨٠٠ متر انعطف يمين على طريق الملك فهد")
    static let places = PlacesDoc(home: LinkPlace(name: "حي الراكة، الخبر", lat: 26.33, lon: 50.19), work: nil,
                                  favourites: [LinkPlace(name: "ورشة أبو سعد", detail: "الصناعية الثانية، الدمام", lat: 26.42, lon: 50.09)],
                                  recents: [LinkPlace(name: "مطار الملك فهد", detail: "الدمام", lat: 26.47, lon: 49.80)])

    static func apply(link: CarLink, search: SearchModel, router: Router) {
        guard let screen = AppLaunch.screen else { return }
        let mall = DemoSearch.mall
        let online = CarLink.Presence.online(address)

        func connected(trip: NavEvent? = nil, name: String? = nil, flow: ShareFlowModel? = nil, note: String? = nil) {
            link.setDemo(presence: online, pairing: pairing, ping: ping, trip: trip, tripName: name, music: music, car: car, places: places, note: note, flow: flow)
        }
        func flow(_ stage: ShareFlowModel.Stage, word: NavEvent? = nil) -> ShareFlowModel {
            ShareFlowModel(previewStage: stage, unitWord: word)
        }

        switch screen {
        case "first":
            link.setDemo(presence: .notFound(denied: false))
        case "unpaired":
            link.setDemo(presence: .unpaired(FoundUnit(address: address, ping: ping)))
        case "denied":
            link.setDemo(presence: .notFound(denied: true))
        case "home":
            connected()
        case "typing":
            connected()
            search.setDemo(query: "الراشد", suggestions: [PlaceSuggestion(title: "الراشد مول", subtitle: "طريق الملك فهد، الخبر"), PlaceSuggestion(title: "الراشد مول، الدمام", subtitle: "الدمام")], phase: .typing)
        case "results":
            connected()
            search.setDemo(results: [DemoSearch.pharmacy, DemoSearch.cafe, DemoSearch.long, mall], phase: .results, category: .pharmacy)
        case "empty":
            connected()
            search.setDemo(query: "قهوة", phase: .empty)
        case "place":
            connected()
            search.setDemo(results: [mall], phase: .results, selected: mall)
        case "place-long":
            connected()
            search.setDemo(results: [DemoSearch.long], phase: .results, selected: DemoSearch.long)
        case "locating":
            connected(flow: flow(.locating(mall)))
        case "sending":
            connected(flow: flow(.sending(mall, attempt: 2, of: 3)))
        case "waiting":
            connected(trip: NavEvent(kind: .routing), name: mall.name, flow: flow(.waiting(mall), word: NavEvent(kind: .routing)))
        case "asking":
            connected(flow: flow(.waiting(mall), word: NavEvent(kind: .card)))
        case "started":
            connected(trip: started, name: mall.name, flow: flow(.started(mall, started)))
        case "trip":
            connected(trip: progress, name: mall.name)
        case "failed":
            connected(flow: flow(.routeFailed(mall, "no_route")))
        case "card":
            link.setDemo(presence: .unpaired(FoundUnit(address: address, ping: ping)), flow: flow(.card(mall)))
        case "unreachable":
            link.setDemo(presence: .notFound(denied: false), pairing: pairing, flow: flow(.unreachable(mall, denied: false, saved: true)))
        case "noword":
            connected(flow: flow(.noWord(mall)))
        case "pending":
            link.setDemo(presence: online, pairing: pairing, ping: ping, music: music, car: car, pending: [PendingPlace(id: "P", place: mall.wire)])
        case "car", "car-idle":
            connected(trip: screen == "car" ? progress : nil, name: mall.name)
            openLater(router, .car)
        case "car-notfound":
            link.setDemo(presence: .notFound(denied: false), pairing: pairing)
            openLater(router, .car)
        case "pair":
            link.setDemo(presence: .unpaired(FoundUnit(address: address, ping: ping)))
            openLater(router, .pair(nil))
        case "check":
            connected()
            openLater(router, .check)
        case "repair":
            connected()
            openLater(router, .repair)
        default:
            break
        }
    }

    private static func openLater(_ router: Router, _ route: Router.Route) {
        Task {
            // After the panel's own sheet has finished coming up.
            try? await Task.sleep(nanoseconds: 700_000_000)
            router.route = route
        }
    }

    /// The live MapKit probe of the CI (section 10): real Apple results for the fixed Arabic list near Dammam and Khobar.
    static func runMapProbe() async {
        var output: [[String: Any]] = []
        let near: [(String, CLLocationCoordinate2D)] = [
            ("Dammam", CLLocationCoordinate2D(latitude: 26.4207, longitude: 50.0888)),
            ("Khobar", CLLocationCoordinate2D(latitude: 26.2794, longitude: 50.2083)),
        ]
        for (label, point) in near {
            let probe = await LinkCheck.mapProbe(near: point)
            for row in probe.rows + probe.categories {
                var item: [String: Any] = ["near": label, "query": row.query, "count": row.count]
                if let km = row.nearestKm { item["nearestKm"] = km }
                item["names"] = row.names
                if let problem = row.problem { item["problem"] = problem }
                output.append(item)
            }
        }
        let directions = await LinkCheck.directions(near: near[0].1)
        output.append(["directions": directions])
        if let data = try? JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .withoutEscapingSlashes]),
           let text = String(data: data, encoding: .utf8) {
            print("MAP-PROBE-BEGIN")
            print(text)
            print("MAP-PROBE-END")
        }
        fflush(stdout)
        exit(0)
    }
}

/// `-screen share-<stage>`: the extension's view, in the app, so its stages can be looked at.
struct ShareDemoView: View {
    let name: String

    var body: some View {
        ShareFlowView(model: model, onClose: {})
    }

    private var model: ShareFlowModel {
        let mall = DemoSearch.mall
        let payload = SharePayload(typeIdentifiers: [["public.url", "public.plain-text"]], urls: ["https://maps.apple/p/7E-Brjrk_THN14"], texts: ["الراشد مول\nhttps://maps.apple/p/7E-Brjrk_THN14"])
        switch name {
        case "share-reading": return ShareFlowModel(previewStage: .reading, payload: payload)
        case "share-confirm": return ShareFlowModel(previewStage: .confirm(mall), payload: payload)
        case "share-pick": return ShareFlowModel(previewStage: .pick(DemoSearch.all, query: "الراشد"), payload: payload)
        case "share-nothing": return ShareFlowModel(previewStage: .nothing, payload: payload)
        case "share-sending": return ShareFlowModel(previewStage: .sending(mall, attempt: 2, of: 3), payload: payload)
        case "share-waiting": return ShareFlowModel(previewStage: .waiting(mall), payload: payload)
        case "share-card": return ShareFlowModel(previewStage: .card(mall), payload: payload)
        case "share-started": return ShareFlowModel(previewStage: .started(mall, DemoScreens.started), payload: payload)
        case "share-failed": return ShareFlowModel(previewStage: .routeFailed(mall, "no_route"), payload: payload)
        case "share-unreachable": return ShareFlowModel(previewStage: .unreachable(mall, denied: false, saved: false), payload: payload)
        case "share-denied": return ShareFlowModel(previewStage: .unreachable(mall, denied: true, saved: false), payload: payload)
        case "share-notpaired": return ShareFlowModel(previewStage: .notPaired(mall), payload: payload)
        default: return ShareFlowModel(previewStage: .reading, payload: payload)
        }
    }
}

/// `-screen sharetest`: a share sheet with something in it, for the test that drives the real extension.
struct ShareTestView: View {
    var body: some View {
        VStack(spacing: Theme.Space.l) {
            ShareLink(item: URL(string: "https://maps.apple.com/?ll=26.290457,50.184555&q=%D8%A7%D9%84%D8%B1%D8%A7%D8%B4%D8%AF%20%D9%85%D9%88%D9%84")!) {
                Text(verbatim: "share")
            }
            .buttonStyle(MainKeyStyle())
            .accessibilityIdentifier("shareTestButton")
        }
        .padding(Theme.Space.l)
    }
}
#endif
