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
///
/// The decision every pass makes — which circles to post to, the geofence
/// evaluation, the transition recording, the idle backoff — lives in
/// `CirclePresence.tick` now (see its KDoc). This keeps only what
/// `commonMain` cannot have: the `while`/`Task.sleep` itself, the guard that
/// a fix actually exists to share, and the fix's age — see the divergence
/// noted at its computation below — plus the one thing `tick` deliberately
/// does *not* reproduce, `reconcileNotifyingCircles()` (see its own doc).
@MainActor
final class CircleSync {

    static let shared = CircleSync()
    private init() {}

    /// A circle is Life360-style presence, not a live ride feed, so this
    /// deliberately stays on the order of minutes rather than the convoy
    /// relay's ~2 second cadence — two minutes keeps "last seen" reading as
    /// current without turning a background circle into a battery cost
    /// anyone notices. Matches Android's `CIRCLE_SYNC_INTERVAL_MS` and
    /// `CirclePresence.ACTIVE_INTERVAL_MS`. Only the *first* sleep in
    /// `loop()` ever uses this value — every one after is whatever `tick`
    /// last returned.
    private static let syncIntervalSeconds = 2 * 60

    private var started = false

    func start() {
        guard !started else { return }
        started = true
        Task { await self.loop() }
    }

    private func loop() async {
        var intervalSeconds = Self.syncIntervalSeconds
        while true {
            try? await Task.sleep(for: .seconds(intervalSeconds))
            guard let fix = LocationBroadcast.shared.last else { continue }

            let fixTsMs = Int64(fix.timestamp.timeIntervalSince1970 * 1000)
            // DIVERGENCE FROM ANDROID, left unfixed in this slice: this is
            // wall clock minus wall clock (`nowMs()` and `fix.timestamp` are
            // both `Date`-based), not monotonic. Android computes
            // `SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs`
            // instead, so a device clock corrected mid-drive can't answer
            // "how old is this reading" wrong in whichever direction the
            // correction went — see `CirclePresence.tick`'s KDoc, "The three
            // clocks". A real fix needs an uptime-stamped fix time, and
            // `CLLocation.timestamp` doesn't offer one; that means stamping
            // `ProcessInfo.processInfo.systemUptime` where a fix is
            // *received* in the location plumbing (`LocationProvider` /
            // `TripRecorder` / `LocationBroadcast`), which is out of scope
            // here — tracked as a follow-up.
            let fixAgeMs = nowMs() - fixTsMs

            do {
                let intervalMs = try await CirclePresence.shared.tick(
                    lat: fix.coordinate.latitude,
                    lon: fix.coordinate.longitude,
                    accuracyM: fix.horizontalAccuracy,
                    fixTimeMs: fixTsMs,
                    fixAgeMs: fixAgeMs,
                    nowMs: nowMs()
                )
                intervalSeconds = Int(intervalMs / 1000)
            } catch {
                // tick() swallows every ordinary failure itself (offline, a
                // 5xx, one circle mid-removal) and only ever propagates
                // cancellation — see its own doc — so there is nothing
                // ordinary to recover from here; the cadence just holds.
            }

            await reconcileNotifyingCircles()
        }
    }

    /// Reconciles `ConvoyLiveClient`'s live-push join set against circle
    /// membership as it changes over a long session (left a circle, joined a
    /// new one) — on top of the immediate add/remove `CirclesScreen`'s
    /// toggle already does and the sweep
    /// `CircleNotifications.runCatchUpSweep` runs on every foreground. This
    /// is the backstop for whatever those two miss, not the primary path —
    /// up to `syncIntervalSeconds`/idle-interval stale is fine for a feature
    /// that already tolerates minutes of lag.
    ///
    /// `CirclePresence.tick` reproduces Android's `circleSyncLoop`, which
    /// does not do this — Android keeps it entirely separate, in
    /// `CircleNotifyService.refreshNotifyCircles`/`periodicRefreshLoop`, its
    /// own service with its own loop. iOS has no equivalent standalone
    /// service for this feature — `CircleSync`'s loop is the only long-lived
    /// background loop it has — so this stays here, right alongside the
    /// `tick` call, rather than moving to `CircleNotifications.swift`: it
    /// needs its own circle-list fetch (`tick` no longer hands back the list
    /// it fetched internally), and this loop is already the place that fetch
    /// used to live.
    private func reconcileNotifyingCircles() async {
        guard SyncClient.shared.configured(), Account.shared.signedIn,
              let circles = try? await Groups.shared.list(kind: "circle") else { return }
        let notifyIds = CircleNotifyPolicy.shared.circlesWantingDelivery(
            circles: circles,
            notifyArrivals: { CircleNotifications.shared.notifyEnabled(circleId: $0) }
        )
        ConvoyLiveClient.shared.setNotifyingCircles(notifyIds)
    }
}
