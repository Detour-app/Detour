import CoreLocation

/// One place fixes are published to, so a second consumer never opens a second
/// GPS listener.
///
/// The trip recorder owns the `CLLocationManager` and feeds this; the convoy
/// client reads it. That is the same arrangement the Android app has, where
/// `ConvoyLiveClient` collects `TripTrackingService.lastFix` rather than
/// registering its own callback — two location listeners on one device is
/// double the battery for the same fixes.
@MainActor
final class LocationBroadcast {

    static let shared = LocationBroadcast()

    private var continuations: [UUID: AsyncStream<CLLocation>.Continuation] = [:]

    private(set) var last: CLLocation?

    func publish(_ fix: CLLocation) {
        last = fix
        for continuation in continuations.values { continuation.yield(fix) }
    }

    /// A stream of fixes for one consumer. Ends when the consumer's task is
    /// cancelled, which unregisters it.
    func stream() -> AsyncStream<CLLocation> {
        AsyncStream { continuation in
            let id = UUID()
            continuations[id] = continuation
            if let last { continuation.yield(last) }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.continuations[id] = nil }
            }
        }
    }
}
