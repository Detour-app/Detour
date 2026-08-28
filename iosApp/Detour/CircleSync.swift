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
/// noted at its computation below.
@MainActor
final class CircleSync {

    static let shared = CircleSync()
    private init() {}

    /// A circle is Life360-style presence, not a live ride feed, so this
    /// deliberately stays on the order of minutes rather than the convoy
    /// relay's ~2 second cadence — two minutes keeps "last seen" reading as
    /// current without turning a background circle into a battery cost
    /// anyone notices. Read off `CirclePresence.ACTIVE_INTERVAL_MS` rather
    /// than typed out again here — the point of the shared tick is that this
    /// number exists once, and a seed value that drifts from the one every
    /// later sleep uses would be the exact copy it was extracted to remove.
    /// Only the *first* sleep in `loop()` ever uses it; every one after is
    /// whatever `tick` last returned.
    private static let syncIntervalSeconds = Int(Enums.shared.circleActiveIntervalMs / 1000)

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
            // here — tracked as issue #75.
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
                // A suspend function's primitive return arrives boxed
                // across the ObjC export, so this is a `KotlinLong` — an
                // NSNumber, which has no `/` of its own. Same rule
                // `RoutesScreen`'s `pullInbox().intValue` follows.
                intervalSeconds = Int(intervalMs.int64Value / 1000)
            } catch {
                // tick() swallows every ordinary failure itself (offline, a
                // 5xx, one circle mid-removal) and only ever propagates
                // cancellation — see its own doc — so there is nothing
                // ordinary to recover from here; the cadence just holds.
            }

            // Deliberately no reconciliation of `ConvoyLiveClient`'s
            // live-push join set here. Two routes already do it:
            // `CirclesScreen`'s toggle, immediately on the change itself,
            // and `CircleNotifications.runCatchUpSweep` on every foreground.
            // A periodic third one would need its own
            // `Groups.list("circle")` — `tick` no longer hands back the list
            // it fetched — doubling this device's circle-list requests
            // against the cadence docs/CIRCLES_AND_CONVOYS.md section 10
            // exists to bound, to cover only one window: membership changing
            // server-side during a single long foreground session. Those
            // arrivals still reach the tray, on the next foreground sweep.
        }
    }
}
