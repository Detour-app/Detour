package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** An ordered waypoint in a planned route: a coordinate with an optional
 *  label picked up from a search result or a saved place. */
data class RouteStop(val at: LatLon, val name: String = "")

/**
 * A route the user planned and chose to keep: the stops that define it, plus
 * the geometry GraphHopper returned for them, so it can be shown or resumed
 * without asking the routing server again on every open.
 */
data class SavedRoute(
    val id: Long,
    val name: String,
    val createdMs: Long,
    val mode: TravelMode,
    val stops: List<RouteStop>,
    /** Routed geometry. May be empty — never fetched yet, or an imported file
     *  that only carried waypoints — in which case callers re-route it. */
    val polyline: List<LatLon>,
    val distanceMeters: Double?,
    val timeMs: Long?,
    /** Username this route arrived from via sharing; blank for one of the
     *  user's own routes. */
    val sharedBy: String = "",
)

/**
 * Encodes [SavedRoute] the same way for local storage, the sync-server
 * payload, and the JSON half of file import/export (see RouteGpx.kt for the
 * GPX half) — one shape, read by [routeFromJson].
 *
 * The polyline is a flat array of numbers `[lat, lon, lat, lon, ...]` rather
 * than one `{"lat":..,"lon":..}` object per point: a routed polyline is
 * commonly a few thousand points, and the object-per-point form would
 * roughly triple the file for information the fixed stride already carries.
 */
fun SavedRoute.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("createdMs", createdMs)
    put("mode", mode.name)
    putJsonArray("stops") {
        for (s in stops) addJsonObject {
            put("lat", s.at.lat)
            put("lon", s.at.lon)
            if (s.name.isNotEmpty()) put("name", s.name)
        }
    }
    putJsonArray("polyline") {
        for (p in polyline) {
            add(p.lat)
            add(p.lon)
        }
    }
    distanceMeters?.let { put("distanceMeters", it) }
    timeMs?.let { put("timeMs", it) }
    if (sharedBy.isNotEmpty()) put("sharedBy", sharedBy)
}

/** Inverse of [SavedRoute.toJson]. Null for a route with fewer than two
 *  stops or with unparseable (NaN) coordinates — not enough to display or
 *  navigate, so it is better dropped than kept half-broken. */
fun routeFromJson(o: JsonObject): SavedRoute? {
    val stops = o.optArray("stops")?.objects().orEmpty().map { s ->
        RouteStop(LatLon(s.optDouble("lat"), s.optDouble("lon")), s.optString("name"))
    }
    if (stops.size < 2 || stops.any { it.at.lat.isNaN() || it.at.lon.isNaN() }) return null
    val flat = o.optArray("polyline") ?: JsonArrayEmpty
    val polyline = (0 until flat.size step 2).mapNotNull { i ->
        val lat = flat.optDouble(i)
        val lon = flat.optDouble(i + 1)
        if (lat.isNaN() || lon.isNaN()) null else LatLon(lat, lon)
    }
    return SavedRoute(
        id = o.optLong("id"),
        name = o.optString("name"),
        createdMs = o.optLong("createdMs"),
        mode = TravelMode.of(o.optString("mode")),
        stops = stops,
        polyline = polyline,
        distanceMeters = o.optDouble("distanceMeters").takeIf { !it.isNaN() },
        timeMs = if (o.has("timeMs")) o.optLong("timeMs") else null,
        sharedBy = o.optString("sharedBy"),
    )
}

/**
 * Unlimited saved multi-stop routes, persisted as JSON in app-private
 * storage. Mirrors [SavedPlaces]: a [StateFlow] so the routes list and the
 * map both recompose the moment one is added, renamed or removed, loaded
 * once on first use.
 */
object RouteStore {

    private const val FILE_NAME = "routes.json"

    private val _routes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val routes: StateFlow<List<SavedRoute>> = _routes
    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    internal var loaded = false

    /** Read from disk once; safe to call on every screen entry. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _routes.value = read()
    }

    /** Drops this rider's routes so the next [ensureLoaded] reads the new
     *  account's file. See [SavedPlaces.reset] for why only the caching
     *  stores need one. */
    fun reset() {
        loaded = false
        _routes.value = emptyList()
    }

    /** Add a route (or replace in place if [route]'s id already exists) and persist. */
    fun save(route: SavedRoute) {
        ensureLoaded() // a mutation can arrive before any screen has loaded the store —
        // e.g. saving straight from the map on a cold start — and without this,
        // _routes.value is still empty and write() below would wipe every
        // previously saved route instead of adding to them.
        val next = _routes.value.filterNot { it.id == route.id } + route
        write(next.sortedByDescending { it.createdMs })
    }

    fun rename(id: Long, name: String) {
        ensureLoaded() // see save(): a rename can be the first call to touch the store.
        val cleaned = name.trim().ifEmpty { return }
        write(_routes.value.map { if (it.id == id) it.copy(name = cleaned) else it }
            .sortedByDescending { it.createdMs })
    }

    fun remove(id: Long) {
        ensureLoaded() // see save(): a remove can be the first call to touch the store.
        write(_routes.value.filterNot { it.id == id })
    }

    fun byId(id: Long): SavedRoute? {
        ensureLoaded() // see save(): a lookup can be the first call to touch the store.
        return _routes.value.find { it.id == id }
    }

    /** Raw stored JSON array, uploaded to the sync server. Reads the file so it
     *  works even before any screen has triggered [ensureLoaded]. */
    fun rawJson(): String {
        val f = accountFile(FILE_NAME)
        return if (f.exists()) f.readText() else "[]"
    }

    /** Overwrite the local store with the server's merged array (the union it
     *  holds), so a reinstall restores every route on the first sync. */
    fun replaceFromServer(json: String) {
        val routes = try {
            jsonArrayOf(json).objects().mapNotNull { routeFromJson(it) }
        } catch (e: Exception) {
            return // malformed payload: keep what we have
        }
        loaded = true
        write(routes.sortedByDescending { it.createdMs })
    }

    private fun write(routes: List<SavedRoute>) {
        _routes.value = routes
        val array = buildJsonArray { for (r in routes) add(r.toJson()) }
        accountFile(FILE_NAME).writeText(array.string())
    }

    private fun read(): List<SavedRoute> {
        val f = accountFile(FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            jsonArrayOf(f.readText()).objects().mapNotNull { routeFromJson(it) }
                .sortedByDescending { it.createdMs }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
