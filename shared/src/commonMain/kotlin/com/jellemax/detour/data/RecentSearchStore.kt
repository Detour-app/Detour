package com.jellemax.detour.data

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** Persists recently picked search results, most recent first, in app-private storage. */
object RecentSearchStore {

    private const val FILE_NAME = "recent_searches.json"
    private const val MAX_ENTRIES = 8

    fun save(result: GeocodeResult) {
        val array = buildJsonArray {
            for (r in withPicked(load(), result)) addJsonObject {
                put("name", r.name)
                put("lat", r.location.lat)
                put("lon", r.location.lon)
                if (r.secondary.isNotEmpty()) put("secondary", r.secondary)
                put("kind", r.kind.name)
            }
        }
        deviceFile(FILE_NAME).writeText(array.string())
    }

    /**
     * [entries] with [picked] moved to the front: an earlier entry for the same place goes, a
     * different place that shares its name stays (#1004). Capped at [MAX_ENTRIES].
     */
    internal fun withPicked(entries: List<GeocodeResult>, picked: GeocodeResult): List<GeocodeResult> =
        (listOf(picked) + entries.filterNot { it.isSamePlaceAs(picked) }).take(MAX_ENTRIES)

    fun load(): List<GeocodeResult> {
        val f = deviceFile(FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            parse(f.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The stored list. An entry written before #1006 is `{name, lat, lon}` only: it loads with no
     * second line and [PlaceKind.PLACE], as does a kind this build does not know.
     */
    internal fun parse(json: String): List<GeocodeResult> =
        jsonArrayOf(json).objects().map { o ->
            GeocodeResult(
                name = o.optString("name"),
                location = LatLon(o.optDouble("lat"), o.optDouble("lon")),
                secondary = o.optString("secondary"),
                kind = PlaceKind.entries.firstOrNull { it.name == o.optString("kind") } ?: PlaceKind.PLACE,
            )
        }
}
