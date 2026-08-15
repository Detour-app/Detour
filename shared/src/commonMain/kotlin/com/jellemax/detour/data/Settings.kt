package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * App settings backed by the platform key-value store, exposed as StateFlows
 * so the UI and the tracking service both react to changes. Call [init] before
 * reading (idempotent; done at app start on both platforms).
 */
object Settings {

    /** AUTO = light by day, dark by night (sun position at last location). */
    enum class Theme { SYSTEM, LIGHT, DARK, AUTO }

    /** ASK = today's behavior, the Go dropdown opens every tap. Anything else
     *  and a tap goes straight to that app; long-press still reopens the menu. */
    enum class NavApp { ASK, IN_APP, GOOGLE_MAPS, WAZE, OTHER }

    /** What gets drawn where you are, on the phone map and the car screen.
     *  DOT is the plain blue location dot; every other entry is a vehicle seen
     *  from above and rotated to your heading. Only the identity is stored here
     *  — the artwork lives in each platform's resources. */
    enum class MapIcon { DOT, FRONTERA, SUV, SEDAN, RACECAR, MOTORCYCLE, PICKUP }

    /** The colour of the route line. THEME is what the line always was: the app
     *  accent, amber on the dark basemap and blue on the light one. Every other
     *  entry is that one colour whatever the basemap. Only the identity is
     *  stored here — [RouteColors] turns it into the hexes every platform
     *  draws with. */
    enum class RouteColor { THEME, AMBER, BLUE, GREEN, TEAL, PURPLE, PINK, RED }

    const val FOG_RADIUS_DEFAULT = 200f
    const val DEFAULT_ZOOM_DEFAULT = 16f
    const val DEFAULT_ZOOM_MIN = 12f
    const val DEFAULT_ZOOM_MAX = 19f

    private var store: Prefs? = null
    private val prefs: Prefs get() = store ?: error("Settings.init() not called")

    private var secureStore: Prefs? = null
    private val secure: Prefs get() = secureStore ?: error("Settings.init() not called")

    private val _theme = MutableStateFlow(Theme.AUTO)
    val theme: StateFlow<Theme> = _theme

    private val _autoDetectDrives = MutableStateFlow(true)
    val autoDetectDrives: StateFlow<Boolean> = _autoDetectDrives

    private val _fogRadiusMeters = MutableStateFlow(FOG_RADIUS_DEFAULT)
    val fogRadiusMeters: StateFlow<Float> = _fogRadiusMeters

    /** Draw the fog of war over the map. On by default; the map toolbar's eye
     *  toggles it, and the choice sticks across launches. */
    private val _fogEnabled = MutableStateFlow(true)
    val fogEnabled: StateFlow<Boolean> = _fogEnabled

    /** Baseline map zoom while following/navigating; speed and turn proximity
     *  shift the camera up to two levels either side of it. */
    private val _defaultZoom = MutableStateFlow(DEFAULT_ZOOM_DEFAULT)
    val defaultZoom: StateFlow<Float> = _defaultZoom

    /** In-app navigation avoids motorways/trunks (matters for car mode). */
    private val _avoidHighways = MutableStateFlow(false)
    val avoidHighways: StateFlow<Boolean> = _avoidHighways

    /** Keep routes off the unclassified/residential layer and off unpaved
     *  tracks where a bigger road will do — the narrow rural lanes a router
     *  picks because they're short, not because anyone wants to drive them. */
    private val _avoidSmallRoads = MutableStateFlow(false)
    val avoidSmallRoads: StateFlow<Boolean> = _avoidSmallRoads

    /** Broadcast turn-by-turn state over BLE for an external display (e.g. a
     *  handlebar-mounted screen), alongside the existing Wear OS relay. Off by
     *  default: it advertises the phone over Bluetooth while on. */
    private val _externalDisplayEnabled = MutableStateFlow(false)
    val externalDisplayEnabled: StateFlow<Boolean> = _externalDisplayEnabled

    /** The mode tab the user is on. The tracking service reads it to decide
     *  which motion sensors are worth registering, and stamps it on the trip —
     *  including an auto-detected one, which has no other way to know. */
    private val _tripMode = MutableStateFlow(TravelMode.CAR)
    val tripMode: StateFlow<TravelMode> = _tripMode

    /** A Bluetooth device the user assigned to a vehicle. [name] is kept so the
     *  Settings list can show it even when the device isn't currently reachable. */
    data class VehicleDevice(val address: String, val name: String, val mode: TravelMode)

    /** Bluetooth devices mapped to a vehicle, keyed by address. When a mapped
     *  device connects, the tracking service logs the trip under its [mode], so a
     *  drive auto-logs under the right vehicle. Empty = feature off. These are
     *  Bluetooth Classic bonds (a Cardo intercom, a car's infotainment), not BLE. */
    private val _vehicleDevices = MutableStateFlow<Map<String, VehicleDevice>>(emptyMap())
    val vehicleDevices: StateFlow<Map<String, VehicleDevice>> = _vehicleDevices

    /** Opt in to the shared fog of war. Off by default, and the server only
     *  hands a friend's traces to someone who is also sharing theirs. */
    private val _shareFog = MutableStateFlow(false)
    val shareFog: StateFlow<Boolean> = _shareFog

    /** Whether search may retry via the public Photon instance (komoot.io) when
     *  a configured custom/baked geocoder is unreachable — sends the query and
     *  the user's approximate location off their own hardware. Only consulted
     *  when there is a non-public primary to fail over from: with no
     *  custom/baked geocoder set, public Photon is the only option either way,
     *  so this can default on without breaking search for anyone who never
     *  looks at the setting. See [Geocoder.search]. */
    private val _geocoderPublicFallback = MutableStateFlow(true)
    val geocoderPublicFallback: StateFlow<Boolean> = _geocoderPublicFallback

    /** The identity provider's access token — short-lived (15 minutes), sent on
     *  every API request. Also blank between refreshes, so it is [refreshToken]
     *  that answers "is there a session at all". */
    private val _accessToken = MutableStateFlow("")
    val accessToken: StateFlow<String> = _accessToken

    /** The session itself: good for as long as it keeps being used inside the
     *  realm's 90-day idle horizon. Blank = signed out. App-private prefs. */
    private val _refreshToken = MutableStateFlow("")
    val refreshToken: StateFlow<String> = _refreshToken

    /** When [accessToken] stops being accepted, as an epoch millisecond. */
    private val _accessTokenExpiresAtMs = MutableStateFlow(0L)
    val accessTokenExpiresAtMs: StateFlow<Long> = _accessTokenExpiresAtMs

    private val _authUsername = MutableStateFlow("")
    val authUsername: StateFlow<String> = _authUsername

    /** Mount-to-bike misalignment, subtracted from every lean reading. Zero
     *  until the user runs calibration (bike upright, engine off) from
     *  Settings; see [com.jellemax.detour.tracking.TripTrackingService]. */
    private val _leanOffsetDeg = MutableStateFlow(0f)
    val leanOffsetDeg: StateFlow<Float> = _leanOffsetDeg

    /** Remembered nav app for the Go button. ASK until the user picks one. */
    private val _preferredNavApp = MutableStateFlow(NavApp.ASK)
    val preferredNavApp: StateFlow<NavApp> = _preferredNavApp

    /** Spoken turn instructions while navigating. On by default — a car screen
     *  you have to look at to be told about a turn is worse than useless — and
     *  toggled from the speaker button on the car nav screen. */
    private val _voiceGuidance = MutableStateFlow(true)
    val voiceGuidance: StateFlow<Boolean> = _voiceGuidance

    /** The marker drawn at your own position. DOT until the user picks a
     *  vehicle from Settings. */
    private val _mapIcon = MutableStateFlow(MapIcon.DOT)
    val mapIcon: StateFlow<MapIcon> = _mapIcon

    /** The colour the route line is drawn in. THEME (the accent) until the user
     *  picks one from Settings. */
    private val _routeColor = MutableStateFlow(RouteColor.THEME)
    val routeColor: StateFlow<RouteColor> = _routeColor

    fun init() {
        if (store != null) return
        store = prefs("settings")
        secureStore = securePrefs()
        // Two phases: this run copies and keeps, a later run deletes once the marker
        // reads back. See CredentialMigration.
        CredentialMigration.step(prefs, secure, CredentialMigration.SESSION_GROUP)
        _theme.value = runCatching {
            Theme.valueOf(prefs.string("theme", Theme.AUTO.name))
        }.getOrDefault(Theme.AUTO)
        _autoDetectDrives.value = prefs.bool("auto_detect_drives", true)
        _avoidHighways.value = prefs.bool("avoid_highways", false)
        _avoidSmallRoads.value = prefs.bool("avoid_small_roads", false)
        _externalDisplayEnabled.value = prefs.bool("external_display_enabled", false)
        _tripMode.value = TravelMode.of(prefs.string("trip_mode").takeIf { it.isNotEmpty() })
        _shareFog.value = prefs.bool("share_fog", false)
        _fogEnabled.value = prefs.bool("fog_enabled", true)
        _fogRadiusMeters.value = prefs.float("fog_radius_m", FOG_RADIUS_DEFAULT)
        _defaultZoom.value = prefs.float("default_zoom", DEFAULT_ZOOM_DEFAULT)
        _geocoderPublicFallback.value = prefs.bool("geocoder_public_fallback", true)
        _accessToken.value = secure.string("access_token")
        _refreshToken.value = secure.string("refresh_token")
        _accessTokenExpiresAtMs.value = secure.long("access_token_expires_at", 0L)
        _authUsername.value = secure.string("auth_username")
        _leanOffsetDeg.value = prefs.float("lean_offset_deg", 0f)
        _voiceGuidance.value = prefs.bool("voice_guidance", true)
        _mapIcon.value = runCatching {
            MapIcon.valueOf(prefs.string("map_icon", MapIcon.DOT.name))
        }.getOrDefault(MapIcon.DOT)
        _routeColor.value = runCatching {
            RouteColor.valueOf(prefs.string("route_color", RouteColor.THEME.name))
        }.getOrDefault(RouteColor.THEME)
        _preferredNavApp.value = runCatching {
            NavApp.valueOf(prefs.string("preferred_nav_app", NavApp.ASK.name))
        }.getOrDefault(NavApp.ASK)
        _vehicleDevices.value = readVehicleDevices()
    }

    private fun readVehicleDevices(): Map<String, VehicleDevice> {
        val raw = prefs.string("vehicle_devices").takeIf { it.isNotEmpty() } ?: return emptyMap()
        return runCatching {
            jsonObjectOf(raw).mapValues { (addr, v) ->
                // New format: {address: {mode, name}}. Old format (v1.24):
                // {address: "MODE"} with no name — fall back to the address.
                when (v) {
                    is kotlinx.serialization.json.JsonObject -> VehicleDevice(
                        addr, v.optString("name", addr), TravelMode.of(v.optString("mode")))
                    else -> VehicleDevice(
                        addr, addr, TravelMode.of(v.toString().trim('"')))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Assign [address] ([name]) to [mode]. */
    fun addVehicleDevice(address: String, name: String, mode: TravelMode) {
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = VehicleDevice(address, name, mode)
        writeVehicleDevices(next)
    }

    /** Forget a device assignment. */
    fun removeVehicleDevice(address: String) {
        val next = _vehicleDevices.value.toMutableMap()
        if (next.remove(address) != null) writeVehicleDevices(next)
    }

    private fun writeVehicleDevices(map: Map<String, VehicleDevice>) {
        _vehicleDevices.value = map
        val json = buildJsonObject {
            map.forEach { (addr, d) ->
                putJsonObject(addr) {
                    put("mode", d.mode.name)
                    put("name", d.name)
                }
            }
        }
        prefs.put("vehicle_devices", json.string())
    }

    /** Writes the whole session at once — the three token fields are only ever
     *  meaningful together, and a half-written pair is a session that either
     *  cannot be used or cannot be refreshed. See [Auth]. */
    fun setSession(
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long,
        username: String,
    ) {
        _accessToken.value = accessToken
        _refreshToken.value = refreshToken
        _accessTokenExpiresAtMs.value = expiresAtMs
        _authUsername.value = username
        secure.put("access_token", accessToken)
        secure.put("refresh_token", refreshToken)
        secure.put("access_token_expires_at", expiresAtMs)
        secure.put("auth_username", username)
    }

    fun setTheme(value: Theme) {
        _theme.value = value
        prefs.put("theme", value.name)
    }

    fun setAutoDetectDrives(value: Boolean) {
        _autoDetectDrives.value = value
        prefs.put("auto_detect_drives", value)
    }

    fun setAvoidHighways(value: Boolean) {
        _avoidHighways.value = value
        prefs.put("avoid_highways", value)
    }

    fun setAvoidSmallRoads(value: Boolean) {
        _avoidSmallRoads.value = value
        prefs.put("avoid_small_roads", value)
    }

    fun setExternalDisplayEnabled(value: Boolean) {
        _externalDisplayEnabled.value = value
        prefs.put("external_display_enabled", value)
    }

    fun setTripMode(value: TravelMode) {
        _tripMode.value = value
        prefs.put("trip_mode", value.name)
    }

    fun setShareFog(value: Boolean) {
        _shareFog.value = value
        prefs.put("share_fog", value)
    }

    fun setFogEnabled(value: Boolean) {
        _fogEnabled.value = value
        prefs.put("fog_enabled", value)
    }

    fun setFogRadiusMeters(value: Float) {
        _fogRadiusMeters.value = value
        prefs.put("fog_radius_m", value)
    }

    fun setDefaultZoom(value: Float) {
        _defaultZoom.value = value
        prefs.put("default_zoom", value)
    }

    fun setVoiceGuidance(value: Boolean) {
        _voiceGuidance.value = value
        prefs.put("voice_guidance", value)
    }

    fun setGeocoderPublicFallback(value: Boolean) {
        _geocoderPublicFallback.value = value
        prefs.put("geocoder_public_fallback", value)
    }

    fun setLeanOffsetDeg(value: Float) {
        _leanOffsetDeg.value = value
        prefs.put("lean_offset_deg", value)
    }

    fun setMapIcon(value: MapIcon) {
        _mapIcon.value = value
        prefs.put("map_icon", value.name)
    }

    fun setRouteColor(value: RouteColor) {
        _routeColor.value = value
        prefs.put("route_color", value.name)
    }

    fun setPreferredNavApp(value: NavApp) {
        _preferredNavApp.value = value
        prefs.put("preferred_nav_app", value.name)
    }

    /** High-water mark for arrive/depart events [CircleEvents] has already
     *  surfaced as a notification for circle [circleId] — lets a client
     *  catch up after being offline (`CircleEvents.events(circleId, since)`)
     *  without re-notifying for anything it already showed. Dynamically
     *  keyed rather than a StateFlow like everything else here: there's no
     *  fixed, small set of circles the way there is a fixed set of
     *  settings, so a flow per circle would never stop growing. */
    fun lastSeenEventTsMs(circleId: String): Long = prefs.long("place_event_last_seen_$circleId", 0L)

    fun setLastSeenEventTsMs(circleId: String, tsMs: Long) {
        prefs.put("place_event_last_seen_$circleId", tsMs)
    }

    /** Whether arrive/depart notifications are raised for circle [circleId].
     *  Device-local, unlike the circle's `sharing` flag (real server state,
     *  see `Groups.setSharing`) — muting a circle on the phone says nothing
     *  about the tablet. It lives here anyway, rather than in each platform's
     *  own bag, so that one key and one default ("on", matching the sharing
     *  switch next to it) define the setting for both apps instead of two
     *  spellings that only look the same. Dynamically keyed for the same
     *  reason as [lastSeenEventTsMs] above. */
    fun notifyArrivals(circleId: String): Boolean = prefs.bool("notify_arrivals_$circleId", true)

    fun setNotifyArrivals(circleId: String, on: Boolean) {
        prefs.put("notify_arrivals_$circleId", on)
    }
}
