package com.jellemax.detour.data

/**
 * The server endpoints that used to arrive through Android's generated
 * BuildConfig. The shared core cannot read BuildConfig — it does not exist on
 * iOS — so each platform pushes its own build-time values in at startup
 * instead: Android from BuildConfig, iOS from the .xcconfig baked into
 * Info.plist.
 *
 * Every field defaults to blank, which each consumer already treats as "not
 * configured, fall back to the user's own server or the public instance". So
 * a platform that forgets to call [set] degrades exactly like a CI build with
 * no secrets, rather than crashing.
 */
object BuildDefaults {

    var routingUrl: String = ""
        private set
    var routingCfId: String = ""
        private set
    var routingCfSecret: String = ""
        private set
    var syncUrl: String = ""
        private set
    var geocoderUrl: String = ""
        private set
    var liveUrl: String = ""
        private set
    var versionName: String = "0"
        private set

    fun set(
        routingUrl: String = "",
        routingCfId: String = "",
        routingCfSecret: String = "",
        syncUrl: String = "",
        geocoderUrl: String = "",
        liveUrl: String = "",
        versionName: String = "0",
    ) {
        this.routingUrl = routingUrl
        this.routingCfId = routingCfId
        this.routingCfSecret = routingCfSecret
        this.syncUrl = syncUrl
        this.geocoderUrl = geocoderUrl
        this.liveUrl = liveUrl
        this.versionName = versionName
    }
}
