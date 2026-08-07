import SwiftUI
import DetourShared

/// Tabs, and the one place the trip recorder is owned.
///
/// The recorder outlives every screen — a ride keeps recording while the user
/// is reading their badges — so it lives here and is handed down, rather than
/// being created by whichever view happens to need it.
struct RootView: View {

    @StateObject private var recorder = TripRecorder()
    @State private var selected = Tab.map

    enum Tab { case map, history, badges, places, friends, settings }

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

            FriendsScreen()
                .tabItem { Label("Friends", systemImage: "person.2") }
                .tag(Tab.friends)

            SettingsScreen()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .task {
            recorder.startWatching()
            // Pull from the sync server on launch: restores everything after a
            // reinstall and picks up trips recorded while the app was closed.
            if SyncClient.shared.configured() && Account.shared.signedIn {
                _ = try? await SyncClient.shared.sync()
            }
        }
        // Re-fetch when sharing is switched on, and drop what we hold the
        // moment it is switched off — a stale union would keep revealing a
        // friend's roads.
        .task(id: SettingsValues.shared.shareFog) {
            if SettingsValues.shared.shareFog {
                await FriendFog.shared.refresh()
            } else {
                FriendFog.shared.clear()
            }
        }
    }
}
