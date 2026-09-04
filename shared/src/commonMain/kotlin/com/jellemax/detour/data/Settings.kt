package com.jellemax.detour.data

import com.jellemax.detour.drive.FuelType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    /** The hint variant a fresh install gets. See `Settings.swipeHintVariant`. */
    const val SWIPE_HINT_VARIANT_DEFAULT = "nudge"

    const val FUEL_CALIBRATION_MIN = 50
    const val FUEL_CALIBRATION_MAX = 150

    /** The bag every setting here lives in, and the one key inside it that has
     *  to be readable before [init] has run: the #84 timing sink is installed
     *  from `Application.onCreate`, which is the only place that runs ahead of
     *  all four entry points that call [init] (the activity, the tracking
     *  service, the car session, the notify service). Reading the key directly
     *  there is what stops the install being written out four times. */
    const val PREFS_NAME = "settings"
    const val PERF_TRACING_KEY = "perf_tracing"

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
     *  handlebar-mounted screen). Off by default: it advertises the phone over
     *  Bluetooth while on. */
    private val _externalDisplayEnabled = MutableStateFlow(false)
    val externalDisplayEnabled: StateFlow<Boolean> = _externalDisplayEnabled

    /** The mode tab the user is on. The tracking service reads it to decide
     *  which motion sensors are worth registering, and stamps it on the trip —
     *  including an auto-detected one, which has no other way to know. */
    private val _tripMode = MutableStateFlow(TravelMode.CAR)
    val tripMode: StateFlow<TravelMode> = _tripMode

    /** How many times the spin dock's mode swipe has been used successfully.
     *  Drives the discoverability hint, which retires after
     *  `ModeSwipePolicy.HINT_AFTER_USES` successful swipes (in the app module).
     *
     *  Persisted rather than remembered because `AppRoot` swaps screens with a
     *  bare `AnimatedContent` and no `rememberSaveableStateHolder`: leaving the
     *  map for the Hub disposes MapScreen's whole composition, so even
     *  `rememberSaveable` would reset and a user who had already learned the
     *  gesture would be taught it again on every visit.
     *
     *  Long rather than Int because [Prefs] has no Int overload. */
    private val _modeSwipesUsed = MutableStateFlow(0L)
    val modeSwipesUsed: StateFlow<Long> = _modeSwipesUsed

    /** Which mode-swipe hint animation plays: "nudge" or "arrows". Deliberately
     *  a raw String and not an enum like [Theme] or [MapIcon] beside it: this is
     *  a temporary A/B knob, deleted along with the losing variant, and the enum
     *  it maps to lives in the app module where it can be parsed tolerantly.
     *  Parsed by `ModeSwipePolicy.HintVariant.of` (in the app module), which
     *  falls back to the default rather than throwing on an unknown name. */
    private val _swipeHintVariant = MutableStateFlow(SWIPE_HINT_VARIANT_DEFAULT)
    val swipeHintVariant: StateFlow<String> = _swipeHintVariant

    /** A Bluetooth device the user assigned to a vehicle. [name] is kept so the
     *  Settings list can show it even when the device isn't currently reachable.
     *  [obd2Address] is a *separate* paired device — the OBD2 dongle plugged into
     *  the port, independent of [address] (the car's stereo/headunit, or a moto
     *  intercom) that this same vehicle is auto-detected by; a vehicle can have
     *  one, the other, both, or neither. */
    data class VehicleDevice(
        val address: String,
        val name: String,
        val mode: TravelMode,
        val obd2Address: String? = null,
        /** For the MAF fuel estimate — see [com.jellemax.detour.drive.Obd2Pids]. */
        val fuelType: FuelType = FuelType.PETROL,
        /** Per-vehicle multiplier on the MAF fuel estimate, percent, clamped
         *  [FUEL_CALIBRATION_MIN]..[FUEL_CALIBRATION_MAX]. Tuned against the
         *  car's own trip computer / a pump fill-up. */
        val fuelCalibrationPct: Int = 100,
    )

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

    /** Record per-function timings against the size of the data they ran over,
     *  to `filesDir/perf.jsonl`. #84.
     *
     *  Off by default in every build, and shipped in release rather than gated
     *  to debug on purpose: growth shows up in a real rider's history, which a
     *  debug install does not have. With it off, `Perf` has no sink and costs a
     *  volatile read; the file never exists until someone turns this on. Never
     *  synced — see `app/.../perf/PerfSink.kt` for why it stays on one device. */
    private val _perfTracing = MutableStateFlow(false)
    val perfTracing: StateFlow<Boolean> = _perfTracing

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

    /** This device's own account id, as the server issued it — see
     *  [Auth.resolveRiderId] for how it is fetched and why a blank value is
     *  the safe default rather than an error. */
    private val _authRiderId = MutableStateFlow(RiderId(""))
    val authRiderId: StateFlow<RiderId> = _authRiderId

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
        // secureStore first: init() early-returns on `store != null`, so a concurrent
        // setSession() between these two assignments must never see `store` set
        // while `secureStore` is still null, which would crash on the `secure`
        // accessor instead of on the intended `store != null` guard.
        secureStore = securePrefs()
        // Before the key is read below, not after it. access_token,
        // refresh_token and auth_username are all in
        // CredentialMigration.SESSION_GROUP, so on an install still holding
        // plaintext credentials they live in the `settings` bag until this
        // runs — reading them earlier would derive a key from three empty
        // strings on precisely the installs the derivation exists for.
        // Safe this early: migrateOnce() opens its own bags and touches
        // neither `store`, nor AccountScope, nor any file.
        // Guarded once-per-process, shared with RoutingServer.loadCustom().
        CredentialMigration.migrateOnce()
        val persistedKey = secure.string("auth_scope_key")
        val storedKey = AccountScope.keyAtLaunch(
            storedKey = persistedKey,
            refreshToken = secure.string("refresh_token"),
            accessToken = secure.string("access_token"),
            username = secure.string("auth_username"),
        )
        // A throw here must not propagate: it would take down whichever
        // caller ran init() (Application.onCreate on Android) and skip
        // everything hydrated below — including vehicle_devices, which
        // TripTrackingService reads for trip classification. Same defence as
        // SecretBox's runCatchings, for the same "one bad value must not
        // abort init" reason.
        val reconciled = runCatching {
            AccountFiles.reconcileAtLaunch(fileSystem, appFilesDir(), storedKey)
        }.isSuccess
        // Only when the line above got the files where `storedKey` says they
        // are. Two orderings matter here and they are different claims.
        //
        // After, never before: an install already signed in when it upgraded
        // has never claimed its bucket, and pointing accountFile() at
        // `storedKey` while the files are still in `_local` is what makes
        // that split permanent — see AccountFiles.reconcileAtLaunch.
        //
        // Conditional, not unconditional: the alternative to setting the
        // scope anyway is not an unhandled throw, it is this runCatching
        // *and* skipping the set — which leaves the scope on `_local`, where
        // the files actually are. That costs one session of SyncClient.sync
        // refusing to upload, which is recoverable and cannot leak because
        // the refusal is exactly the anonymous-bucket one. Setting it anyway
        // costs a bucket that has never existed: history reads empty, the
        // first write creates it, and adopt() refuses `_local` from then on.
        // A recoverable failure over an unrecoverable one, the same trade
        // sync() makes three files away.
        if (reconciled) AccountScope.set(storedKey)
        // Only when it was derived rather than read: securePrefs() is
        // Keystore-backed, and a write on every cold start for a value that
        // has not changed is one this project has already paid for once.
        if (persistedKey.isEmpty() && storedKey.isNotEmpty()) {
            secure.put("auth_scope_key", storedKey)
        }
        // Last, not first. This is the assignment the early-return above
        // guards on, and everything between it and the top of the function
        // has to have happened before another thread may skip past it and
        // resolve a store path — securePrefs() alone measured 1.6-1.8s on a
        // Galaxy S928B, so the window is a real one. Two threads running the
        // body concurrently instead is harmless: migrateOnce() is guarded,
        // reconcileAtLaunch() is idempotent and catches per file, and every
        // line below is an assignment. `secure` is reachable throughout
        // because it hangs off secureStore, not off this.
        store = prefs(PREFS_NAME)
        _theme.value = runCatching {
            Theme.valueOf(prefs.string("theme", Theme.AUTO.name))
        }.getOrDefault(Theme.AUTO)
        _autoDetectDrives.value = prefs.bool("auto_detect_drives", true)
        _avoidHighways.value = prefs.bool("avoid_highways", false)
        _avoidSmallRoads.value = prefs.bool("avoid_small_roads", false)
        _externalDisplayEnabled.value = prefs.bool("external_display_enabled", false)
        _tripMode.value = TravelMode.of(prefs.string("trip_mode").takeIf { it.isNotEmpty() })
        _modeSwipesUsed.value = prefs.long("mode_swipes_used", 0L)
        _swipeHintVariant.value = prefs.string("swipe_hint_variant", SWIPE_HINT_VARIANT_DEFAULT)
        _shareFog.value = prefs.bool("share_fog", false)
        _perfTracing.value = prefs.bool(PERF_TRACING_KEY, false)
        _fogEnabled.value = prefs.bool("fog_enabled", true)
        _fogRadiusMeters.value = prefs.float("fog_radius_m", FOG_RADIUS_DEFAULT)
        _defaultZoom.value = prefs.float("default_zoom", DEFAULT_ZOOM_DEFAULT)
        _geocoderPublicFallback.value = prefs.bool("geocoder_public_fallback", true)
        _accessToken.value = secure.string("access_token")
        _refreshToken.value = secure.string("refresh_token")
        _accessTokenExpiresAtMs.value = secure.long("access_token_expires_at", 0L)
        _authUsername.value = secure.string("auth_username")
        _authRiderId.value = RiderId(secure.string("auth_rider_id"))
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
            jsonObjectOf(raw).mapNotNull { (addr, v) ->
                // A mode name that no longer exists (e.g. a device mapped to
                // the removed WALK/BIKE modes) is dropped rather than silently
                // reassigned to CAR — a stale mapping would both mistag trips
                // and defeat the slow-trip drop gate.
                val modeName = when (v) {
                    is JsonObject -> v.optString("mode")
                    else -> v.toString().trim('"')
                }
                if (TravelMode.entries.none { it.name == modeName }) return@mapNotNull null
                addr to decodeVehicleDevice(addr, v)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** New format: `{address: {mode, name, obd2Address?}}`. Old format (v1.28,
     *  pre-OBD2): `{address: {mode, name}}`, no `obd2Address` key — decodes to
     *  `null`. Oldest format (v1.24): `{address: "MODE"}` as a bare JSON string,
     *  no name — falls back to the address as the display name. */
    internal fun decodeVehicleDevice(address: String, v: JsonElement): VehicleDevice = when (v) {
        is JsonObject -> VehicleDevice(
            address,
            v.optString("name", address),
            TravelMode.of(v.optString("mode")),
            v.optString("obd2Address").takeIf { it.isNotBlank() },
            fuelType = runCatching { FuelType.valueOf(v.optString("fuelType")) }.getOrDefault(FuelType.PETROL),
            fuelCalibrationPct = v.optInt("fuelCalibrationPct", 100)
                .coerceIn(FUEL_CALIBRATION_MIN, FUEL_CALIBRATION_MAX),
        )
        else -> VehicleDevice(address, address, TravelMode.of(v.toString().trim('"')), null)
    }

    internal fun encodeVehicleDevice(d: VehicleDevice): JsonObject = buildJsonObject {
        put("mode", d.mode.name)
        put("name", d.name)
        d.obd2Address?.let { put("obd2Address", it) }
        if (d.fuelType != FuelType.PETROL) put("fuelType", d.fuelType.name)
        if (d.fuelCalibrationPct != 100) put("fuelCalibrationPct", d.fuelCalibrationPct)
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

    /** Assign or clear [address]'s OBD2 adapter. `null` un-pairs it — the
     *  vehicle keeps its auto-detect [VehicleDevice.address] either way. */
    fun setObd2Address(address: String, obd2Address: String?) {
        val current = _vehicleDevices.value[address] ?: return
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = current.copy(obd2Address = obd2Address)
        writeVehicleDevices(next)
    }

    /** Set [address]'s fuel type for the MAF fuel estimate. */
    fun setFuelType(address: String, fuelType: FuelType) {
        val current = _vehicleDevices.value[address] ?: return
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = current.copy(fuelType = fuelType)
        writeVehicleDevices(next)
    }

    /** Set [address]'s fuel-estimate calibration, percent, clamped
     *  [FUEL_CALIBRATION_MIN]..[FUEL_CALIBRATION_MAX]. */
    fun setFuelCalibrationPct(address: String, pct: Int) {
        val current = _vehicleDevices.value[address] ?: return
        val next = _vehicleDevices.value.toMutableMap()
        next[address] = current.copy(
            fuelCalibrationPct = pct.coerceIn(FUEL_CALIBRATION_MIN, FUEL_CALIBRATION_MAX),
        )
        writeVehicleDevices(next)
    }

    private fun writeVehicleDevices(map: Map<String, VehicleDevice>) {
        _vehicleDevices.value = map
        val json = buildJsonObject {
            map.forEach { (addr, d) -> put(addr, encodeVehicleDevice(d)) }
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
        scopeKey: String,
    ) {
        _accessToken.value = accessToken
        // `_refreshToken` before `_authUsername` is load-bearing: each
        // `Watcher` (FlowWatcher.kt, iOS only) runs its own
        // `Dispatchers.Main` collector, so the token watcher's callback is
        // scheduled — and observes `signedIn` as already true — before the
        // name watcher's runs. `FriendsModel.name.watch` in
        // FriendsScreen.swift depends on that to tell "cleared" apart from
        // "not yet caught up". Reordering these two silently reintroduces
        // that bug.
        _refreshToken.value = refreshToken
        _accessTokenExpiresAtMs.value = expiresAtMs
        _authUsername.value = username
        secure.put("access_token", accessToken)
        secure.put("refresh_token", refreshToken)
        secure.put("access_token_expires_at", expiresAtMs)
        secure.put("auth_username", username)
        secure.put("auth_scope_key", scopeKey)
    }

    /** Written once `/me` answers — see [Auth.resolveRiderId]. Not a
     *  [setSession] parameter: unlike [username], the id is not in the token
     *  and is not known at the moment the session itself is written. */
    fun setRiderId(id: String) {
        _authRiderId.value = RiderId(id)
        secure.put("auth_rider_id", id)
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

    fun setModeSwipesUsed(value: Long) {
        _modeSwipesUsed.value = value
        prefs.put("mode_swipes_used", value)
    }

    /** Increments from this object's own value rather than from a copy the
     *  caller happens to be holding. The UI reads this counter through a
     *  Compose snapshot, and a read-modify-write across that boundary is
     *  correct only while the propagation has settled - which is a scheduling
     *  detail, not a contract. The hint's whole retirement rule hangs off this
     *  number. */
    fun incrementModeSwipesUsed() {
        setModeSwipesUsed(_modeSwipesUsed.value + 1)
    }

    fun setSwipeHintVariant(value: String) {
        _swipeHintVariant.value = value
        prefs.put("swipe_hint_variant", value)
    }

    fun setShareFog(value: Boolean) {
        _shareFog.value = value
        prefs.put("share_fog", value)
    }

    fun setPerfTracing(value: Boolean) {
        _perfTracing.value = value
        prefs.put(PERF_TRACING_KEY, value)
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

    /** The push registration token last handed to the server (an FCM token on
     *  Android), so sign-out can `DELETE /api/devices` for it and a re-register
     *  can skip when nothing changed. Empty when never registered. */
    fun pushToken(): String? = prefs.string("push_token").takeIf { it.isNotEmpty() }

    fun setPushToken(token: String?) {
        prefs.put("push_token", token ?: "")
    }

    /** Wall-clock time of the last successful [SyncClient.sync], any trigger.
     *  Lets [SyncClient.syncIfDue] skip the launch-time full-history round
     *  trip when one just happened. */
    fun lastSyncMs(): Long = prefs.long("last_sync_ms", 0L)

    fun setLastSyncMs(tsMs: Long) {
        prefs.put("last_sync_ms", tsMs)
    }

    /** When the update check last ran, throttling it to once an hour. Stamped
     *  before the request, not after: a device with no connectivity would
     *  otherwise retry on every foreground. */
    fun lastUpdateCheckMs(): Long = prefs.long("last_update_check_ms", 0L)

    fun setLastUpdateCheckMs(tsMs: Long) {
        prefs.put("last_update_check_ms", tsMs)
    }

    /** The version a notification has already been posted for. One per version,
     *  so a rider who declines an update is not told about it hourly. */
    fun notifiedUpdateVersion(): String = prefs.string("notified_update_version", "")

    fun setNotifiedUpdateVersion(version: String) {
        prefs.put("notified_update_version", version)
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
