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
        BuildDefaults.shared.configure(
            routingUrl: info("DetourRoutingURL"),
            routingCfId: info("DetourRoutingCFId"),
            routingCfSecret: info("DetourRoutingCFSecret"),
            apiUrl: info("DetourApiURL"),
            idpIssuer: info("DetourIdpIssuer"),
            geocoderUrl: info("DetourGeocoderURL"),
            liveUrl: info("DetourLiveURL"),
            versionName: info("CFBundleShortVersionString", default: "0")
        )
        Settings.shared.doInit()
        SavedPlaces.shared.ensureLoaded()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .onOpenURL { url in
                    // A redirect arriving here rather than in the sign-in
                    // session's completion handler means the session is gone —
                    // the app was killed behind the browser. Shared Oidc has
                    // nothing parked, so complete() will refuse it with the
                    // "app restarted" message, which is the thing worth saying:
                    // silently dropping it reads as a Sign in button that did
                    // nothing. Same case Android reports from MainActivity.
                    if Oidc.shared.isCallback(url: url.absoluteString) {
                        Task { await SignIn.reportOrphanedCallback(url) }
                        return
                    }
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
