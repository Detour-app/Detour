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
 * a platform that forgets to call [configure] degrades exactly like a CI build with
 * no secrets, rather than crashing.
 */
object BuildDefaults {

    var routingUrl: String = ""
        private set

    /**
     * Base of the sync + social API, which serves everything under `/api`.
     *
     * On a single-hostname deployment the tunnel already routes `/api` to the
     * geocoder, so this service needs either a hostname of its own or an ingress
     * rule ahead of that one. See the path-routing note in app/build.gradle.kts.
     */
    var apiUrl: String = ""
        private set

    /**
     * The realm that issues rider tokens, e.g.
     * `http://localhost:7580/realms/detour`. Blank means signing in is
     * impossible, and every social feature behaves as it does when signed out.
     */
    var idpIssuer: String = ""
        private set
    var geocoderUrl: String = ""
        private set
    var liveUrl: String = ""
        private set
    var versionName: String = "0"
        private set

    /**
     * What a problem report (#811) says the device is: [DevicePlatform], the
     * OS release, and the phone's maker and model. Pushed in rather than read
     * through a new `expect`, the same way [versionName] is, by
     * [configureDevice]. A null platform means the platform didn't say, and
     * [ProblemReports.send] then answers `Unsupported` without posting: the
     * server takes `android` or `ios` only. A blank OS or model reads `unknown`.
     */
    var platform: DevicePlatform? = null
        private set
    var osVersion: String = ""
        private set
    var deviceModel: String = ""
        private set

    fun configure(
        routingUrl: String = "",
        apiUrl: String = "",
        idpIssuer: String = "",
        geocoderUrl: String = "",
        liveUrl: String = "",
        versionName: String = "0",
    ) {
        this.routingUrl = routingUrl
        this.apiUrl = apiUrl
        this.idpIssuer = idpIssuer
        this.geocoderUrl = geocoderUrl
        this.liveUrl = liveUrl
        this.versionName = versionName
    }

    /** The device half, apart from [configure] so neither passes seven parameters (§8.4). */
    fun configureDevice(platform: DevicePlatform? = null, osVersion: String = "", deviceModel: String = "") {
        this.platform = platform
        this.osVersion = osVersion
        this.deviceModel = deviceModel
    }
}

/** The platforms a problem report (#818) may name; the server refuses any other. */
enum class DevicePlatform(val wire: String) { ANDROID("android"), IOS("ios") }
