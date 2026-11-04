import AMRIKit
import SwiftUI

/// Which screen is up over the map.
@MainActor
final class Router: ObservableObject {
    enum Route: Identifiable, Equatable {
        case car
        case pair(PairingTarget?)
        case check
        case repair

        var id: String {
            switch self {
            case .car: return "car"
            case .pair: return "pair"
            case .check: return "check"
            case .repair: return "repair"
            }
        }
    }

    @Published var route: Route?
}
