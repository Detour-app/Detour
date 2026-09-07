package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * What a saved place is *to the rider* — not a label matched on its name.
 *
 * [HOME] and [WORK] are singletons per rider (the store demotes a previous
 * holder when a new one is marked); [FAVOURITE] is a set of any size; [NONE]
 * is a plain place. The kind, not the name, drives the home-sheet shortcuts
 * and — from the parent issue — the per-recipient sharing rule, which a
 * client-side name comparison could never tell the server.
 */
enum class SavedPlaceKind { HOME, WORK, FAVOURITE, NONE }

/** A named shortcut destination — Home, Work, a friend's place. */
data class SavedPlace(
    val id: Long,
    val name: String,
    val location: LatLon,
    val kind: SavedPlaceKind = SavedPlaceKind.NONE,
)

/**
 * The kind a place written before the kind field existed should take, derived
 * from its name once at migration time. After the migrated list is persisted
 * every row carries an explicit `kind`, so this is never consulted again — a
 * place renamed away from "Home" keeps the kind it was promoted to.
 */
internal fun legacyKindFromName(name: String): SavedPlaceKind = when {
    name.equals("home", ignoreCase = true) -> SavedPlaceKind.HOME
    name.equals("work", ignoreCase = true) -> SavedPlaceKind.WORK
    else -> SavedPlaceKind.NONE
}

/**
 * Enforce the single-[SavedPlaceKind.HOME]/single-[SavedPlaceKind.WORK]
 * invariant: the first holder of each singleton kind (by the list's existing
 * order) keeps it, any later one is demoted to [SavedPlaceKind.NONE]. Applied
 * after migration, where two places both named "Home" would otherwise both be
 * promoted; [SavedPlaces.setKind] keeps the invariant on every later mutation.
 */
internal fun enforceSingletonKinds(places: List<SavedPlace>): List<SavedPlace> {
    var homeSeen = false
    var workSeen = false
    return places.map { p ->
        when (p.kind) {
            SavedPlaceKind.HOME -> if (homeSeen) p.copy(kind = SavedPlaceKind.NONE)
                else { homeSeen = true; p }
            SavedPlaceKind.WORK -> if (workSeen) p.copy(kind = SavedPlaceKind.NONE)
                else { workSeen = true; p }
            else -> p
        }
    }
}

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
        val (places, migrated) = read()
        _places.value = places
        // Persist promotions once, so a place named "Home" that the rider later
        // renames keeps the kind it was promoted to instead of losing it the
        // next time the name no longer matches. write() re-sets _places.value,
        // which is harmless — it is the value we just set.
        if (migrated) write(places)
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

    /** Set a place's [SavedPlaceKind], holding the singleton invariant: marking
     *  a place [SavedPlaceKind.HOME] or [SavedPlaceKind.WORK] demotes whichever
     *  place currently holds that kind. See [withKind] for the pure rule. */
    fun setKind(id: Long, kind: SavedPlaceKind) {
        ensureLoaded() // see add(): setKind can be the first call to touch the store.
        write(withKind(_places.value, id, kind))
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
            decodeSavedPlaces(json).first
        } catch (e: Exception) {
            return // malformed payload: keep what we have
        }
        loaded = true
        // write() persists an explicit kind for every row, so a server payload
        // written before the kind field existed is migrated the same way a local
        // file is — a reinstall that syncs down old rows gets the promotion once.
        write(places)
    }

    private fun write(places: List<SavedPlace>) {
        _places.value = places
        accountFile(FILE_NAME).writeText(encodeSavedPlaces(places))
    }

    private fun read(): Pair<List<SavedPlace>, Boolean> {
        val f = accountFile(FILE_NAME)
        if (!f.exists()) return emptyList<SavedPlace>() to false
        return try {
            decodeSavedPlaces(f.readText())
        } catch (e: Exception) {
            emptyList<SavedPlace>() to false
        }
    }
}

/** Apply the single-[SavedPlaceKind.HOME]/single-[SavedPlaceKind.WORK] invariant
 *  when a place's kind changes: the target takes [kind]; any other holder of a
 *  singleton kind being assigned is demoted to [SavedPlaceKind.NONE]. Pure, so
 *  the store's file I/O is not in the way of testing the rule. Result is sorted
 *  by lowercased name to match every other mutation on the store. */
internal fun withKind(places: List<SavedPlace>, id: Long, kind: SavedPlaceKind): List<SavedPlace> {
    val singleton = kind == SavedPlaceKind.HOME || kind == SavedPlaceKind.WORK
    return places.map { p ->
        when {
            p.id == id -> p.copy(kind = kind)
            singleton && p.kind == kind -> p.copy(kind = SavedPlaceKind.NONE)
            else -> p
        }
    }.sortedBy { it.name.lowercase() }
}

/** The stored JSON array for [places], with an explicit `kind` on every row. */
internal fun encodeSavedPlaces(places: List<SavedPlace>): String =
    buildJsonArray {
        for (p in places) addJsonObject {
            put("id", p.id)
            put("name", p.name)
            put("lat", p.location.lat)
            put("lon", p.location.lon)
            put("kind", p.kind.name)
        }
    }.string()

/**
 * Parse a stored/synced places array. Returns the places (sorted, singleton
 * invariant enforced) and whether any row lacked an explicit `kind` — a
 * pre-kind payload whose promotions [SavedPlaces.ensureLoaded] persists once.
 *
 * A row with no `kind` is promoted from its name by [legacyKindFromName], the
 * one-time migration off name matching. A row with an *unknown* kind value —
 * an older client reading a newer one's field — reads as [SavedPlaceKind.NONE]
 * rather than the name match: once the field is present it is authoritative,
 * and an unknown key never drops the place. Throws only on malformed JSON.
 */
internal fun decodeSavedPlaces(json: String): Pair<List<SavedPlace>, Boolean> {
    val objects = jsonArrayOf(json).objects()
    val migrated = objects.any { !it.has("kind") }
    val places = objects.map { o ->
        val name = o.optString("name")
        val kind = if (o.has("kind")) {
            SavedPlaceKind.entries.firstOrNull { it.name == o.optString("kind") }
                ?: SavedPlaceKind.NONE
        } else {
            legacyKindFromName(name)
        }
        SavedPlace(
            id = o.optLong("id"),
            name = name,
            location = LatLon(o.optDouble("lat"), o.optDouble("lon")),
            kind = kind,
        )
    }.sortedBy { it.name.lowercase() }
    // Two places both named "Home" would both promote; keep the invariant.
    return enforceSingletonKinds(places) to migrated
}
