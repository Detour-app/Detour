import SwiftUI
import DetourShared

@main
struct DetourApp: App {

    init() {
        // The iOS half of what BuildConfig does on Android: push the baked-in
        // server endpoints into the shared core before anything reads them.
        // Every field is optional — blank means "no baked default", and each
        // consumer already falls back to the user's own server or the public
        // instance, so a build with no secrets behaves like CI's does.
        BuildDefaults.shared.doSet(
            routingUrl: info("DetourRoutingURL"),
            routingCfId: info("DetourRoutingCFId"),
            routingCfSecret: info("DetourRoutingCFSecret"),
            syncUrl: info("DetourSyncURL"),
            geocoderUrl: info("DetourGeocoderURL"),
            liveUrl: info("DetourLiveURL"),
            versionName: info("CFBundleShortVersionString", default: "0")
        )
        Settings.shared.doInit()
        SavedPlaces.shared.ensureLoaded()
    }

    var body: some Scene {
        WindowGroup {
            MapScreen()
                .onOpenURL { url in
                    // detour://reset?token=… from the sync server's mails.
                    guard url.scheme == "detour", url.host == "reset",
                          let token = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                            .queryItems?.first(where: { $0.name == "token" })?.value
                    else { return }
                    PendingReset.shared.offer(value: token)
                }
        }
    }
}

/// An Info.plist string, with `$(VAR)` substitution already done by the build.
/// An unset xcconfig variable expands to the empty string, which is the value
/// the shared core treats as "not configured".
private func info(_ key: String, default fallback: String = "") -> String {
    (Bundle.main.object(forInfoDictionaryKey: key) as? String)
        .flatMap { $0.isEmpty ? nil : $0 } ?? fallback
}
