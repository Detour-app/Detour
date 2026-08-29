package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** A named shortcut destination — Home, Work, a friend's place. */
data class SavedPlace(
    val id: Long,
    val name: String,
    val location: LatLon,
)

/**
 * Unlimited named shortcut locations, persisted as JSON in app-private storage.
 * Exposes a [StateFlow] so the map's shortcut chips and the manager screen both
 * recompose the moment one is added, renamed, or removed. Load once on first use.
 */
object SavedPlaces {

    private const val FILE_NAME = "saved_places.json"

    // internal, not private, for the same reason `loaded` below is: the
    // session-switch test has to seed a non-empty list and watch
    // Auth.resetAccountScopedStores empty it. Asserting on `loaded` alone
    // would leave the line that actually drops the previous rider's places
    // deletable with the suite still green.
    internal val _places = MutableStateFlow<List<SavedPlace>>(emptyList())
    val places: StateFlow<List<SavedPlace>> = _places
    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    //
    // @Volatile because this latch became cross-thread when reset() gained a
    // caller: Auth.clear()/Auth.store() run it on an IO coroutine while
    // ensureLoaded() reads it on the main thread. Without it a thread may see
    // `true` against an already-emptied list, return early from ensureLoaded()
    // and let a mutation write that empty list over the file — the truncation
    // 332d493 fixed, reachable again through the cache instead of the guard.
    @Volatile
    internal var loaded = false

    /** Read from disk once; safe to call on every screen entry. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _places.value = read()
    }

    /** Drops this rider's places so the next [ensureLoaded] reads the new
     *  account's file. The read-through stores need no equivalent — they hit
     *  the file on every call, so moving the directory is enough. */
    fun reset() {
        loaded = false
        _places.value = emptyList()
    }

    /** Add a place (or rename in place if [id] already exists) and persist. */
    fun add(name: String, location: LatLon, id: Long = nowMs()) {
        ensureLoaded() // a mutation can arrive while the cache is empty and
        // unloaded — a cold start, or [reset] having just run under a composed
        // screen whose own ensureLoaded already fired — and without this,
        // _places.value is still empty and write() below would truncate the
        // file to this one place. Same guard and same reason as
        // RouteStore.save; the two are one pattern.
        val cleaned = name.trim().ifEmpty { "Place" }
        val next = _places.value.filterNot { it.id == id } + SavedPlace(id, cleaned, location)
        write(next.sortedBy { it.name.lowercase() })
    }

    fun rename(id: Long, name: String) {
        ensureLoaded() // see add(): a rename can be the first call to touch the store.
        val cleaned = name.trim().ifEmpty { return }
        write(_places.value.map { if (it.id == id) it.copy(name = cleaned) else it }
            .sortedBy { it.name.lowercase() })
    }

    fun remove(id: Long) {
        ensureLoaded() // see add(): a remove can be the first call to touch the store.
        write(_places.value.filterNot { it.id == id })
    }

    /** Raw stored JSON array, uploaded to the sync server. Reads the file so it
     *  works even before any screen has triggered [ensureLoaded]. */
    fun rawJson(): String {
        val f = accountFile(FILE_NAME)
        return if (f.exists()) f.readText() else "[]"
    }

    /** Overwrite the local store with the server's merged array (the union it
     *  holds), so a reinstall restores every shortcut on the first sync. */
    fun replaceFromServer(json: String) {
        val places = try {
            parse(jsonArrayOf(json))
        } catch (e: Exception) {
            return // malformed payload: keep what we have
        }
        loaded = true
        write(places)
    }

    private fun write(places: List<SavedPlace>) {
        _places.value = places
        val array = buildJsonArray {
            for (p in places) addJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("lat", p.location.lat)
                put("lon", p.location.lon)
            }
        }
        accountFile(FILE_NAME).writeText(array.string())
    }

    private fun read(): List<SavedPlace> {
        val f = accountFile(FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            parse(jsonArrayOf(f.readText()))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(array: JsonArray): List<SavedPlace> =
        array.objects().map { o ->
            SavedPlace(
                id = o.optLong("id"),
                name = o.optString("name"),
                location = LatLon(o.optDouble("lat"), o.optDouble("lon")),
            )
        }.sortedBy { it.name.lowercase() }
}
