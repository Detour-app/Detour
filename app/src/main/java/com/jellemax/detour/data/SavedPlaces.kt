package com.jellemax.detour.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    private val _places = MutableStateFlow<List<SavedPlace>>(emptyList())
    val places: StateFlow<List<SavedPlace>> = _places
    private var loaded = false

    /** Read from disk once; safe to call on every screen entry. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        _places.value = read(context)
    }

    /** Add a place (or rename in place if [id] already exists) and persist. */
    fun add(context: Context, name: String, location: LatLon, id: Long = System.currentTimeMillis()) {
        val cleaned = name.trim().ifEmpty { "Place" }
        val next = _places.value.filterNot { it.id == id } + SavedPlace(id, cleaned, location)
        write(context, next.sortedBy { it.name.lowercase() })
    }

    fun rename(context: Context, id: Long, name: String) {
        val cleaned = name.trim().ifEmpty { return }
        write(context, _places.value.map { if (it.id == id) it.copy(name = cleaned) else it }
            .sortedBy { it.name.lowercase() })
    }

    fun remove(context: Context, id: Long) {
        write(context, _places.value.filterNot { it.id == id })
    }

    /** Raw stored JSON array, uploaded to the sync server. Reads the file so it
     *  works even before any screen has triggered [ensureLoaded]. */
    fun rawJson(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText() else "[]"
    }

    /** Overwrite the local store with the server's merged array (the union it
     *  holds), so a reinstall restores every shortcut on the first sync. */
    fun replaceFromServer(context: Context, json: String) {
        val places = try {
            parse(JSONArray(json))
        } catch (e: Exception) {
            return // malformed payload: keep what we have
        }
        loaded = true
        write(context, places)
    }

    private fun write(context: Context, places: List<SavedPlace>) {
        _places.value = places
        val array = JSONArray()
        for (p in places) {
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("lat", p.location.lat)
                    .put("lon", p.location.lon)
            )
        }
        file(context).writeText(array.toString())
    }

    private fun read(context: Context): List<SavedPlace> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            parse(JSONArray(f.readText()))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(array: JSONArray): List<SavedPlace> =
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedPlace(
                id = o.getLong("id"),
                name = o.getString("name"),
                location = LatLon(o.getDouble("lat"), o.getDouble("lon")),
            )
        }.sortedBy { it.name.lowercase() }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
