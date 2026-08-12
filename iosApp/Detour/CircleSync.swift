import Foundation
import CoreLocation
import DetourShared

/// Circles' second sink on the location stream every fix already flows
/// through — the "one collector, two sinks" rule from
/// docs/CIRCLES_AND_CONVOYS.md section 10. Mirrors
/// `TripTrackingService.circleSyncLoop` on Android. Started once from
/// RootView's `.task`, the same place `TripRecorder.startWatching()` is, and
/// left running for the life of the app — independent of trip/convoy state,
/// same as Android's version runs regardless of what else the service is
/// doing.
///
/// Never opens a second location subscription: each tick just samples
/// whatever `LocationBroadcast` already holds from the one `CLLocationManager`
/// `TripRecorder` owns, the same way `ConvoyLiveClient.forwardLocation` reads
/// that stream for convoys instead of registering its own delegate. No
/// `CLCircularRegion` monitoring either — geofencing runs on-device over
/// fixes that already arrive (`GeofenceEvaluator`), which is both cheaper and
/// keeps this off the OS's per-app region budget.
@MainActor
final class CircleSync {

    static let shared = CircleSync()
    private init() {}

    /// A circle is Life360-style presence, not a live ride feed, so this
    /// deliberately stays on the order of minutes rather than the convoy
    /// relay's ~2 second cadence — two minutes keeps "last seen" reading as
    /// current without turning a background circle into a battery cost
    /// anyone notices. Matches Android's `CIRCLE_SYNC_INTERVAL_MS`.
    private static let syncIntervalSeconds = 2 * 60

    /// Cadence once a tick finds no circle to share with — the cost a user
    /// who never touches the feature pays, and the delay before joining their
    /// first circle starts working. Matches Android's `CIRCLE_IDLE_INTERVAL_MS`.
    private static let idleIntervalSeconds = 30 * 60

    /// A fix older than this says where the phone was, not where it is; it
    /// still gets posted (an honest "last seen") but is not allowed to decide
    /// a geofence transition. Matches Android's `CIRCLE_FIX_TRUST_MS`.
    private static let fixTrustMs: Int64 = 15 * 60_000

    private var started = false

    /// One evaluator per circle, kept across ticks — `GeofenceEvaluator` holds
    /// per-place dwell/inside state between calls, so a fresh instance every
    /// tick would never accumulate enough dwell time to fire "arrive".
    private var evaluators: [String: GeofenceEvaluator] = [:]

    func start() {
        guard !started else { return }
        started = true
        Task { await self.loop() }
    }

    private func loop() async {
        var intervalSeconds = Self.syncIntervalSeconds
        while true {
            try? await Task.sleep(for: .seconds(intervalSeconds))
            guard SyncClient.shared.configured(), Account.shared.signedIn else { continue }
            guard let fix = LocationBroadcast.shared.last else { continue }
            let username = SettingsValues.shared.authUsername

            let circles: [DetourShared.Group]
            do {
                circles = try await Groups.shared.list(kind: "circle").filter { $0.status == "accepted" }
            } catch {
                continue // offline or server down; retried next tick
            }
            // Drop bookkeeping for circles we're no longer in, so rejoining a
            // circle under the same id later doesn't inherit stale dwell state.
            let circleIds = Set(circles.map { $0.id })
            evaluators = evaluators.filter { circleIds.contains($0.key) }

            // Reconciles `ConvoyLiveClient`'s live-push join set against
            // circle membership as it changes over a long session (left a
            // circle, joined a new one) — on top of the immediate
            // add/remove `CirclesScreen`'s toggle already does and the sweep
            // `CircleNotifications.runCatchUpSweep` runs on every
            // foreground. This tick is the backstop for whatever those two
            // miss, not the primary path — up to `idleIntervalSeconds` stale
            // is fine for a feature that already tolerates minutes of lag.
            let notifyIds = Set(circles.filter { CircleNotifications.shared.notifyEnabled(circleId: $0.id) }.map { $0.id })
            ConvoyLiveClient.shared.setNotifyingCircles(notifyIds)

            let sharing = circles.filter { circle in
                circle.members.first { $0.username == username }?.sharing == true
            }
            intervalSeconds = sharing.isEmpty ? Self.idleIntervalSeconds : Self.syncIntervalSeconds

            let fixTsMs = Int64(fix.timestamp.timeIntervalSince1970 * 1000)
            // In the phone's most passive location mode a fix can go a long
            // time between updates. That is fine for a position nobody has
            // moved, but a fix old enough that the phone could be anywhere by
            // now must not drive a geofence decision.
            let fixAgeMs = nowMs() - fixTsMs

            for circle in sharing {
                do {
                    try await CircleFixes.shared.postFix(
                        groupId: circle.id,
                        lat: fix.coordinate.latitude,
                        lon: fix.coordinate.longitude,
                        accuracyM: fix.horizontalAccuracy,
                        tsMs: fixTsMs
                    )
                    if fixAgeMs > Self.fixTrustMs { continue }
                    let places = try await CirclePlaces.shared.places(groupId: circle.id)
                    // Dwell runs on wall clock, not the fix's own timestamp: a
                    // phone standing still stops producing new fixes, so
                    // timing dwell by fix timestamps would freeze the clock at
                    // exactly the moment someone parked, and arrival would
                    // never fire — which is the one thing a circle is for.
                    let evaluator = evaluators[circle.id]
                        ?? GeofenceEvaluator.companion.withDefaults()
                    evaluators[circle.id] = evaluator
                    let transitions = evaluator.evaluate(
                        lat: fix.coordinate.latitude,
                        lon: fix.coordinate.longitude,
                        tsMs: nowMs(),
                        places: places
                    )
                    for t in transitions {
                        try await CircleEvents.shared.record(
                            groupId: circle.id, placeId: t.placeId, kind: t.kind, tsMs: t.tsMs)
                    }
                } catch {
                    // One circle failing (removed mid-loop, one bad request)
                    // must not stop the others from posting this tick.
                }
            }
        }
    }
}
