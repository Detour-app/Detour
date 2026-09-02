import Foundation
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

    /// A fix together with how old it is, read as one value.
    ///
    /// One value rather than two properties because the age belongs to *this*
    /// `fix` and nothing else: as separate properties the pairing would be a
    /// convention a later edit could break without any signal.
    struct Sample {
        let fix: CLLocation
        /// Milliseconds since the fix was taken, on the monotonic clock.
        /// This is `CirclePresence.tick`'s `fixAgeMs` contract — see "The
        /// three clocks" in its KDoc.
        let ageMs: Int64
    }

    static let shared = LocationBroadcast()

    private var continuations: [UUID: AsyncStream<CLLocation>.Continuation] = [:]

    private(set) var last: CLLocation?

    /// When the last fix was taken, on the `systemUptime` timeline.
    private var lastFixUptime: TimeInterval?

    func publish(_ fix: CLLocation) {
        last = fix

        // Wall clock is read exactly once per fix, across the sub-second gap
        // between CoreLocation taking the reading and delivering it here. Every
        // age computed from this point on is pure arithmetic on `systemUptime`,
        // so a device clock corrected later in the drive cannot move it — which
        // is the whole point, and what `CirclePresence.tick`'s `fixAgeMs`
        // contract asks for. Back-dated by the delivery lag rather than stamped
        // at arrival so the age means "since the fix was taken", matching
        // Android's `elapsedRealtime() - fix.elapsedRealtimeMs`.
        //
        // Clamped at zero because a clock correction landing inside that
        // sub-second window would otherwise stamp the fix in the future.
        let deliveryLagSeconds = max(0, Date().timeIntervalSince(fix.timestamp))
        lastFixUptime = ProcessInfo.processInfo.systemUptime - deliveryLagSeconds

        for continuation in continuations.values { continuation.yield(fix) }
    }

    /// The most recent fix and its age, or nil before the first fix arrives.
    ///
    /// Known limit: `systemUptime` does not advance while the device is
    /// suspended, so a fix held across a sleep reports an age smaller than the
    /// truth and can pass a staleness gate it should have failed. That is
    /// narrower than the wall-clock bug this replaced — it needs a resume with
    /// a stale fix still held, is bounded by how long the device slept, and any
    /// new fix corrects it, whereas a clock correction is unbounded and
    /// persists. iOS exposes no public monotonic clock that counts sleep;
    /// tracked as issue #123.
    var lastSample: Sample? {
        guard let last, let lastFixUptime else { return nil }
        let ageSeconds = max(0, ProcessInfo.processInfo.systemUptime - lastFixUptime)
        return Sample(fix: last, ageMs: Int64(ageSeconds * 1000))
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
