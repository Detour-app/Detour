package com.jellemax.maproulette.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * App settings backed by SharedPreferences, exposed as StateFlows so the
 * UI and the tracking service both react to changes. Call [init] before
 * reading (idempotent; done in MainActivity and the tracking service).
 */
object Settings {

    /** AUTO = light by day, dark by night (sun position at last location). */
    enum class Theme { SYSTEM, LIGHT, DARK, AUTO }

    const val FOG_RADIUS_DEFAULT = 200f
    const val DEFAULT_ZOOM_DEFAULT = 16f
    const val DEFAULT_ZOOM_MIN = 12f
    const val DEFAULT_ZOOM_MAX = 19f

    private lateinit var prefs: SharedPreferences

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

    /** User-entered sync server URL; blank = use the baked-in default. */
    private val _syncUrl = MutableStateFlow("")
    val syncUrl: StateFlow<String> = _syncUrl

    /** User-entered geocoder (Photon) URL; blank = baked default, else public Photon. */
    private val _geocoderUrl = MutableStateFlow("")
    val geocoderUrl: StateFlow<String> = _geocoderUrl

    /** Bearer token for the sync server; blank = signed out. App-private prefs. */
    private val _authToken = MutableStateFlow("")
    val authToken: StateFlow<String> = _authToken

    private val _authUsername = MutableStateFlow("")
    val authUsername: StateFlow<String> = _authUsername

    /** Mount-to-bike misalignment, subtracted from every lean reading. Zero
     *  until the user runs calibration (bike upright, engine off) from
     *  Settings; see [com.jellemax.maproulette.tracking.TripTrackingService]. */
    private val _leanOffsetDeg = MutableStateFlow(0f)
    val leanOffsetDeg: StateFlow<Float> = _leanOffsetDeg

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = runCatching {
            Theme.valueOf(prefs.getString("theme", Theme.AUTO.name)!!)
        }.getOrDefault(Theme.AUTO)
        _autoDetectDrives.value = prefs.getBoolean("auto_detect_drives", true)
        _avoidHighways.value = prefs.getBoolean("avoid_highways", false)
        _avoidSmallRoads.value = prefs.getBoolean("avoid_small_roads", false)
        _externalDisplayEnabled.value = prefs.getBoolean("external_display_enabled", false)
        _tripMode.value = TravelMode.of(prefs.getString("trip_mode", null))
        _shareFog.value = prefs.getBoolean("share_fog", false)
        _fogEnabled.value = prefs.getBoolean("fog_enabled", true)
        _fogRadiusMeters.value = prefs.getFloat("fog_radius_m", FOG_RADIUS_DEFAULT)
        _defaultZoom.value = prefs.getFloat("default_zoom", DEFAULT_ZOOM_DEFAULT)
        _syncUrl.value = prefs.getString("sync_url", "") ?: ""
        _geocoderUrl.value = prefs.getString("geocoder_url", "") ?: ""
        _authToken.value = prefs.getString("auth_token", "") ?: ""
        _authUsername.value = prefs.getString("auth_username", "") ?: ""
        _leanOffsetDeg.value = prefs.getFloat("lean_offset_deg", 0f)
        _vehicleDevices.value = readVehicleDevices()
    }

    private fun readVehicleDevices(): Map<String, VehicleDevice> {
        val raw = prefs.getString("vehicle_devices", null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { addr ->
                // New format: {address: {mode, name}}. Old format (v1.24):
                // {address: "MODE"} with no name — fall back to the address.
                when (val v = json.get(addr)) {
                    is JSONObject -> VehicleDevice(
                        addr, v.optString("name", addr), TravelMode.of(v.optString("mode")))
                    else -> VehicleDevice(addr, addr, TravelMode.of(v.toString()))
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
        val json = JSONObject()
        map.forEach { (addr, d) ->
            json.put(addr, JSONObject().put("mode", d.mode.name).put("name", d.name))
        }
        prefs.edit().putString("vehicle_devices", json.toString()).apply()
    }

    fun setAuth(token: String, username: String) {
        _authToken.value = token
        _authUsername.value = username
        prefs.edit().putString("auth_token", token)
            .putString("auth_username", username).apply()
    }

    fun setTheme(value: Theme) {
        _theme.value = value
        prefs.edit().putString("theme", value.name).apply()
    }

    fun setAutoDetectDrives(value: Boolean) {
        _autoDetectDrives.value = value
        prefs.edit().putBoolean("auto_detect_drives", value).apply()
    }

    fun setAvoidHighways(value: Boolean) {
        _avoidHighways.value = value
        prefs.edit().putBoolean("avoid_highways", value).apply()
    }

    fun setAvoidSmallRoads(value: Boolean) {
        _avoidSmallRoads.value = value
        prefs.edit().putBoolean("avoid_small_roads", value).apply()
    }

    fun setExternalDisplayEnabled(value: Boolean) {
        _externalDisplayEnabled.value = value
        prefs.edit().putBoolean("external_display_enabled", value).apply()
    }

    fun setTripMode(value: TravelMode) {
        _tripMode.value = value
        prefs.edit().putString("trip_mode", value.name).apply()
    }

    fun setShareFog(value: Boolean) {
        _shareFog.value = value
        prefs.edit().putBoolean("share_fog", value).apply()
    }

    fun setFogEnabled(value: Boolean) {
        _fogEnabled.value = value
        prefs.edit().putBoolean("fog_enabled", value).apply()
    }

    fun setFogRadiusMeters(value: Float) {
        _fogRadiusMeters.value = value
        prefs.edit().putFloat("fog_radius_m", value).apply()
    }

    fun setDefaultZoom(value: Float) {
        _defaultZoom.value = value
        prefs.edit().putFloat("default_zoom", value).apply()
    }

    fun setSyncUrl(value: String) {
        _syncUrl.value = value.trim()
        prefs.edit().putString("sync_url", value.trim()).apply()
    }

    fun setGeocoderUrl(value: String) {
        _geocoderUrl.value = value.trim()
        prefs.edit().putString("geocoder_url", value.trim()).apply()
    }

    fun setLeanOffsetDeg(value: Float) {
        _leanOffsetDeg.value = value
        prefs.edit().putFloat("lean_offset_deg", value).apply()
    }
}
