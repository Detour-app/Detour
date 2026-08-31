import SwiftUI
import DetourShared

/// An observable source of "is there a session" for `RootView`'s launch
/// sync below, which needs to notice the moment sign-in completes, not just
/// the app's next unrelated re-render.
///
/// `Account.shared.signedIn` is a plain Kotlin getter — nothing publishes on
/// it, so keying a `.task(id:)` on it directly would only re-evaluate when
/// something else happened to recompute the view. Same shape as
/// `FriendsModel`: watch the refresh token itself.
@MainActor
final class LaunchSyncGate: ObservableObject {
    @Published var signedIn = false

    private let token = SettingsFlows.shared.authToken()

    init() {
        token.watch { [weak self] in
            self?.signedIn = !(self?.token.value.isEmpty ?? true)
        }
    }

    deinit { token.cancel() }
}

/// Tabs, and the one place the trip recorder is owned.
///
/// The recorder outlives every screen — a ride keeps recording while the user
/// is reading their badges — so it lives here and is handed down, rather than
/// being created by whichever view happens to need it.
struct RootView: View {

    @StateObject private var recorder = TripRecorder()
    @StateObject private var launchSyncGate = LaunchSyncGate()
    @State private var selected = Tab.map
    @ObservedObject private var pendingCircleOpen = PendingCircleOpen.shared
    @Environment(\.scenePhase) private var scenePhase

    enum Tab { case map, history, badges, places, routes, friends, circles, settings }

    var body: some View {
        TabView(selection: $selected) {
            MapScreen()
                .environmentObject(recorder)
                .tabItem { Label("Map", systemImage: "map") }
                .tag(Tab.map)

            HistoryScreen()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
                .tag(Tab.history)

            BadgesScreen()
                .tabItem { Label("Badges", systemImage: "seal") }
                .tag(Tab.badges)

            SavedPlacesScreen()
                .tabItem { Label("Places", systemImage: "mappin.and.ellipse") }
                .tag(Tab.places)

            RoutesScreen()
                .tabItem { Label("Routes", systemImage: "signpost.right.and.left") }
                .tag(Tab.routes)

            FriendsScreen()
                .tabItem { Label("Friends", systemImage: "person.2") }
                .tag(Tab.friends)

            CirclesScreen()
                .tabItem { Label("Circles", systemImage: "location.circle") }
                .tag(Tab.circles)

            SettingsScreen()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .task {
            recorder.startWatching()
            // Circles' second sink on that same location stream — started
            // once here, alongside the recorder it reads from, and left
            // running for the life of the app regardless of which tab is on
            // screen (docs/CIRCLES_AND_CONVOYS.md section 10). Re-checks
            // `signedIn` on every loop iteration, so a mid-session sign-in
            // reaches it on its own — nothing to key here.
            CircleSync.shared.start()
            // Cold launch counts as "foreground" too, but `.onChange(of:
            // scenePhase)` below never fires for a view's starting value —
            // only later transitions — so the very first sweep has to be
            // kicked off here instead. Re-runs on every later
            // `scenePhase == .active` too, so a mid-session sign-in reaches
            // it the next time the app foregrounds — nothing to key here
            // either.
            await CircleNotifications.shared.runCatchUpSweep()
        }
        // Pull from the sync server the moment a session exists: restores
        // everything after a reinstall and picks up trips recorded while the
        // app was closed. Keyed on `launchSyncGate.signedIn`, not folded into
        // the bare `.task` above — a bare `.task` only runs once, at this
        // view's first appearance, which used to be fine because Android was
        // the only signed-in platform. On iOS, sign-in can complete *during*
        // this session (there was no way to arrive already signed in before
        // this branch), so the bare task's `Account.shared.signedIn` check
        // was reading a session that could not exist yet — trip sync never
        // ran until a pull-to-refresh, a fog toggle or a restart. Keying on
        // the watched token makes SwiftUI re-run this the moment one appears.
        .task(id: launchSyncGate.signedIn) {
            guard SyncClient.shared.configured() && launchSyncGate.signedIn else { return }
            _ = try? await SyncClient.shared.sync()
        }
        // Re-fetch when sharing is switched on, and drop what we hold the
        // moment it is switched off — a stale union would keep revealing a
        // friend's roads.
        //
        // Keyed on the session as well as the toggle. `shareFog` alone is a
        // plain Kotlin getter nothing publishes on, and it does not change when
        // the rider does — so on a sign-in this never re-ran, and the new rider
        // saw no friend fog at all until they toggled it or relaunched. (Before
        // FriendFog's commit became epoch-guarded, what they saw instead was
        // the *previous* rider's fog, which was the more urgent half of the
        // same bug.)
        .task(id: "\(launchSyncGate.signedIn)-\(SettingsValues.shared.shareFog)") {
            if SettingsValues.shared.shareFog {
                // Documented never to throw: a friend's fog going missing is
                // not worth interrupting the map for.
                try? await FriendFog.shared.refresh()
            } else {
                FriendFog.shared.clear()
            }
        }
        // Every later foreground/activation — the app was never killed (iOS
        // can't wake this app up, see the phase-3 design decision), just
        // backgrounded and resumed — runs the same sweep again.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await CircleNotifications.shared.runCatchUpSweep() }
            }
        }
        // A notification tap opens the circle it was about.
        .onChange(of: pendingCircleOpen.circleId) { _, id in
            guard let id else { return }
            selected = .circles
            CircleMapState.shared.setViewed(id)
            pendingCircleOpen.consume()
        }
    }
}
