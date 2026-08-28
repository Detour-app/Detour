import Foundation
import UserNotifications
import DetourShared

/// Local-notification half of arrival/departure sharing (phase 3 of the
/// self-hosted notification feature; see docs/CIRCLES_AND_CONVOYS.md). There
/// is no push service behind this app — no Firebase, no APNs, no paid Apple
/// Developer account — so "notified" only ever means "the app noticed while
/// it was alive," either live over `ConvoyLiveClient`'s relay socket or by
/// catching up on foreground. `UNUserNotificationCenter` only ever raises
/// what this class decides to raise; nothing here talks to Apple's push
/// service, and nothing here reaches for location or the network on its own
/// — events are handed to it, same rule as `:shared` (docs/IOS_PORT.md).
@MainActor
final class CircleNotifications: NSObject {

    static let shared = CircleNotifications()
    private override init() { super.init() }

    /// Mirrors the OS's actual authorization state, refreshed on every
    /// foreground sweep (`syncAuthorizationStatus`) rather than trusted from
    /// whenever it was last asked — the user can revoke it from iOS Settings
    /// without ever touching this app again.
    private var authorized = false

    // MARK: Per-circle toggle

    /// Backed by shared's `Settings`, which owns the key and the "on"
    /// default for both platforms — Android's `CircleNotifySettings` reads
    /// the same pair. Storing it here in `UserDefaults` instead would work
    /// (it is device-local either way), but the two apps would then define
    /// the same user-facing switch twice and drift the first time either is
    /// touched.
    func notifyEnabled(circleId: String) -> Bool {
        Settings.shared.notifyArrivals(circleId: circleId)
    }

    func setNotifyEnabled(circleId: String, _ on: Bool) {
        Settings.shared.setNotifyArrivals(circleId: circleId, on: on)
    }

    // MARK: Authorization

    /// Refreshes `authorized` from the OS without prompting — safe to call
    /// on every foreground sweep, unlike `requestAuthorizationIfNeeded`.
    private func syncAuthorizationStatus() async {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: authorized = true
        default: authorized = false
        }
    }

    /// Requested the first time the user actually turns a circle's toggle
    /// on, not at launch — asking before there is anything to notify about
    /// wastes the one-shot system prompt on a feature nobody has touched
    /// yet, the same reasoning `LocationProvider.requestWhenInUse` documents
    /// for location. Returns whether notifications actually ended up
    /// authorized, so the caller can make the toggle reflect that rather
    /// than the tap that caused it.
    func requestAuthorizationIfNeeded() async -> Bool {
        await syncAuthorizationStatus()
        if authorized { return true }
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        authorized = granted
        return granted
    }

    // MARK: Live path

    /// Called from `ConvoyLiveClient`'s `placeEvents` watcher for a
    /// `place_event` frame decoded by the shared `ConvoyRelay`. The server
    /// already excludes the mover from its own broadcast (see
    /// docs/CIRCLES_AND_CONVOYS.md),
    /// so — unlike `runCatchUpSweep` — there is no self-transition to filter
    /// here.
    func handleLiveEvent(groupId: String, event: PlaceEvent) {
        guard notifyEnabled(circleId: groupId) else { return }
        raise(event: event, circleId: groupId)
        CircleEvents.shared.setLastSeenEventTsMs(circleId: groupId, tsMs: event.tsMs)
    }

    // MARK: Catch-up path

    /// Runs on app foreground/activation (`RootView`'s `.task` for cold
    /// launch, its `scenePhase` handler for every resume after). No
    /// background wakeup exists to hang this on instead: the app registers
    /// neither significant-location-change nor `CLCircularRegion` monitoring
    /// anywhere — `LocationProvider` only ever calls `startUpdatingLocation`
    /// / `requestAlwaysAuthorization`, and `CircleSync`'s own doc is explicit
    /// that geofencing deliberately runs over fixes already arriving rather
    /// than region monitoring. Foreground/activation is therefore the whole
    /// story, exactly as the phase-3 prompt's item 7 says to fall back to
    /// when that's the case.
    ///
    /// Also reconciles `ConvoyLiveClient`'s live-join set on every call. That
    /// is one of the only two routes that do — the other being
    /// `CirclesScreen`'s own toggle, which acts on the change itself; see
    /// `CircleSync.loop` for why there is no periodic third one.
    func runCatchUpSweep() async {
        await syncAuthorizationStatus()
        guard SyncClient.shared.configured(), Account.shared.signedIn else { return }
        let username = SettingsValues.shared.authUsername
        guard let circles = try? await Groups.shared.list(kind: "circle") else { return }
        // CircleNotifyPolicy.circlesWantingDelivery (shared/): accepted
        // membership plus this device's own per-circle toggle — the same
        // filter Android's CircleNotifyService.refreshNotifyCircles uses.
        let notifyIds = CircleNotifyPolicy.shared.circlesWantingDelivery(
            circles: circles,
            // `notifyArrivals` is a Kotlin `(String) -> Boolean`, i.e. a
            // generic `Function1`, and a generic type argument boxes on the
            // ObjC export — the same reason `ConvoyLiveClient` has to call
            // `.intValue` on a `Map<String, Int>`'s values. So this hands
            // back a `KotlinBoolean`, not a Swift `Bool`. Nothing else in
            // this app passes a Kotlin function-type parameter from Swift
            // (`FlowWatcher.watch(onChange:)` takes `() -> Unit`, which has
            // no return to box), so the exact lowering is unverified until
            // Xcode builds it.
            notifyArrivals: { KotlinBoolean(bool: self.notifyEnabled(circleId: $0)) }
        )
        ConvoyLiveClient.shared.setNotifyingCircles(notifyIds)

        guard authorized else { return }
        let notifying = circles.filter { notifyIds.contains($0.id) }
        for circle in notifying {
            let since = CircleEvents.shared.lastSeenEventTsMs(circleId: circle.id)
            guard let events = try? await CircleEvents.shared.events(groupId: circle.id, sinceMs: since),
                  !events.isEmpty else { continue }
            // Advance past everything fetched regardless of what gets
            // notified below, so a long-stale backlog is never re-fetched —
            // it is shown once (capped, filtered), and then it's gone.
            let maxTs = events.map { $0.tsMs }.max() ?? since
            // CircleNotifyPolicy.planCatchUp (shared/): drops this device's
            // own transitions and anything stale, caps the rest, and hands
            // them back newest-first — this device's sweep already raised
            // newest-first before the policy moved to shared/, so nothing
            // changes here; Android's copy sorted the other way and was the
            // one that changed (see planCatchUp's own doc). Counted per
            // circle, not per sweep — a noisy circle must not silently eat a
            // quiet one's only arrival.
            let plan = CircleNotifyPolicy.shared.planCatchUp(
                events: events,
                myUsername: username,
                nowMs: nowMs(),
                staleAfterMs: Enums.shared.circleStaleAfterMs,
                cap: Enums.shared.circleCatchUpCap
            )
            for event in plan.individual {
                raise(event: event, circleId: circle.id)
            }
            if plan.collapsedCount > 0 {
                raiseSummary(circleId: circle.id, collapsed: Int(plan.collapsedCount))
            }
            CircleEvents.shared.setLastSeenEventTsMs(circleId: circle.id, tsMs: maxTs)
        }
    }

    // MARK: Raising

    private func raise(event: PlaceEvent, circleId: String) {
        guard authorized else { return }
        let content = UNMutableNotificationContent()
        // `notificationText()` is a Kotlin extension fun on `PlaceEvent`
        // (CircleEvents.kt) — deliberately called rather than reworded here,
        // so the two platforms can never say this differently. Called as a
        // native Swift extension member on the exported `PlaceEvent` class,
        // which is how Kotlin/Native's Objective-C export represents an
        // extension whose receiver is one of the module's own classes (as
        // opposed to a plain top-level function, which lands on a `...Kt`
        // facade instead — see `CircleEventsKt.placeEventFromRelayFrame` for
        // that shape). Unverified against a real compile (no Swift
        // toolchain here); if this specific line fails to build, the
        // fallback spelling is `CircleEventsKt.notificationText(event)`.
        content.body = event.notificationText()
        content.sound = .default
        content.userInfo = ["circleId": circleId]
        // Deterministic per event rather than a random UUID: the same
        // transition arriving twice (a live frame followed by a catch-up
        // sweep that hadn't advanced `lastSeenEventTsMs` yet) replaces the
        // pending request instead of showing up twice.
        let request = UNNotificationRequest(
            identifier: "circle-\(circleId)-\(event.placeId)-\(event.kind)-\(event.tsMs)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    /// The stand-in for everything one sweep capped away. Its wording comes
    /// from `:shared` like `notificationText()` does, but as a plain
    /// top-level function it lands on the `CircleEventsKt` facade rather
    /// than on a type. One identifier per circle, not per sweep: a second
    /// sweep's summary should replace the first, not stack under it.
    private func raiseSummary(circleId: String, collapsed: Int) {
        guard authorized else { return }
        let content = UNMutableNotificationContent()
        content.body = CircleEventsKt.catchUpSummaryText(collapsed: Int32(collapsed))
        content.sound = .default
        content.userInfo = ["circleId": circleId]
        let request = UNNotificationRequest(
            identifier: "circle-\(circleId)-summary",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}

extension CircleNotifications: UNUserNotificationCenterDelegate {

    /// Without this, a notification raised while the app is in the
    /// foreground shows nothing at all — the one iOS default this feature
    /// cannot live with, since "while the app is running" is the whole
    /// coverage story (see the phase-3 prompt's design decision).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    /// A tap opens the circle it was about — handed off to `PendingCircleOpen`
    /// since a delegate callback has no view in scope to navigate with itself.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard let raw = response.notification.request.content.userInfo["circleId"] as? String else { return }
        await MainActor.run {
            PendingCircleOpen.shared.offer(raw)
        }
    }
}

/// Set when a notification tap should open a specific circle. Consumed by
/// `RootView`, which switches to the Circles tab and hands the id to
/// `CircleMapState`. Deliberately Swift-only rather than the shared Kotlin
/// `PendingReset`'s `StateFlow` pattern (see `Social.kt` / `FlowWatcher.kt`)
/// — this never needs to reach Android, it exists purely to get a tap on a
/// local notification from `CircleNotifications` (a delegate callback, no
/// view in scope) to the one screen that can act on it.
@MainActor
final class PendingCircleOpen: ObservableObject {
    static let shared = PendingCircleOpen()
    private init() {}

    @Published private(set) var circleId: String?

    func offer(_ id: String) { circleId = id }

    /// Consumed once by `RootView` after acting on it, so backgrounding and
    /// foregrounding again without a fresh tap doesn't reopen the same circle.
    func consume() {
        circleId = nil
    }
}
