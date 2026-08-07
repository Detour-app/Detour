import CoreLocation
import Combine

/// CoreLocation wrapper, the iOS stand-in for the Android app's fused-location
/// client plus the foreground service that keeps it alive.
///
/// The lifecycle is genuinely different and not worth pretending otherwise:
/// Android holds a foreground service with a notification for as long as a
/// trip runs, while iOS grants background delivery through the `location`
/// background mode plus `allowsBackgroundLocationUpdates`, and shows its own
/// blue status bar. Nothing here belongs in the shared core — the core is
/// handed fixes, it never reaches for them.
@MainActor
final class LocationProvider: NSObject, ObservableObject {

    @Published private(set) var last: CLLocation?
    @Published private(set) var authorization: CLAuthorizationStatus

    private let manager = CLLocationManager()

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        // Roughly the Android app's fastest interval, expressed the only way
        // iOS lets you: a distance rather than a time.
        manager.distanceFilter = 5
        manager.pausesLocationUpdatesAutomatically = false
    }

    /// When In Use first. Always is only worth asking for once a trip actually
    /// starts recording — iOS only ever shows the upgrade prompt once, and
    /// asking before the user has seen why wastes it.
    func requestWhenInUse() {
        manager.requestWhenInUseAuthorization()
    }

    func start() {
        manager.startUpdatingLocation()
    }

    /// Call when a trip starts, not at launch. Requires the `location`
    /// background mode, which Info.plist declares.
    func startBackground() {
        manager.requestAlwaysAuthorization()
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    func stopBackground() {
        manager.allowsBackgroundLocationUpdates = false
    }
}

extension LocationProvider: CLLocationManagerDelegate {

    nonisolated func locationManager(
        _ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]
    ) {
        guard let latest = locations.last else { return }
        Task { @MainActor in self.last = latest }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.start()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // A transient failure is normal indoors; the next fix supersedes it.
    }
}
