import Foundation
import CoreLocation
import UIKit

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

    private init() {
        // `systemUptime` freezes while the device is suspended, so a fix held
        // across a sleep would report an age far short of the truth and could
        // pass a staleness gate it should have failed. There is no public iOS
        // clock that counts sleep, so instead drop the held age on every
        // foreground: `lastSample` then returns nil — the consumer skips the
        // tick, which is the safe direction — until the next fix re-stamps the
        // clock. A resume with a fresh fix already in hand re-stamps it in
        // `publish` before this fires and loses nothing. #123
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            MainActor.assumeIsolated { LocationBroadcast.shared.lastFixUptime = nil }
        }
    }

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
    /// `systemUptime` does not advance while the device is suspended, so a fix
    /// held across a sleep would report an age smaller than the truth. The
    /// `didBecomeActiveNotification` observer in `init` clears `lastFixUptime`
    /// on every foreground, so a fix held across a suspend reports *no* age
    /// (this returns nil) rather than a wrong one, until the next fix re-stamps
    /// the clock. #123
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
