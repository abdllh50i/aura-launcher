import AMRIKit
import CoreLocation
import SwiftUI

/// The phone's own position: where to search around, and the route's origin while the unit has no GPS yet.
@MainActor
final class LocationModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var last: CLLocation?
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        authorization = manager.authorizationStatus
    }

    func start() {
        guard !AppLaunch.skipLocation else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    var near: CLLocationCoordinate2D? { last?.coordinate }

    var isDenied: Bool { authorization == .denied || authorization == .restricted }

    /// Only a recent and decent fix is offered to the unit.
    var fix: PhoneFix? {
        guard let last else { return nil }
        return PhoneFix.usable(lat: last.coordinate.latitude, lon: last.coordinate.longitude,
                               accuracy: last.horizontalAccuracy, age: -last.timestamp.timeIntervalSinceNow)
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let newest = locations.last else { return }
        Task { @MainActor in self.last = newest }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Unknown stays unknown: nothing to show, and the search just has no bias.
    }
}
