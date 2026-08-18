package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bidirectional sync with the rider's own server (see backend/README.md). One
 * POST uploads local trips, fog-of-war traces, badges and the aggregate stats
 * friends are allowed to see; the server merges them with its copy and returns
 * the union, which replaces the local stores. Deleting and reinstalling the app
 * therefore restores everything on the first sync.
 *
 * The server keys everything on the signed-in rider, so syncing requires a
 * session ([Auth]). Traces and trips are only ever returned to their owner.
 */
object SyncClient {

    data class SyncResult(val trips: Int, val traces: Int, val badges: Int)

    /** Effective API base: the one server address the user set (Settings) →
     *  baked default. The custom address still wins, which is what lets someone
     *  point a release APK at their own server. */
    fun url(): String? =
        RoutingServer.apiBase(RoutingServer.loadCustom()).ifBlank { null }

    fun configured(): Boolean = url() != null

    suspend fun sync(): SyncResult {
        Settings.init()

        // Coverage is the only stat the server can't derive from the trips it
        // already holds — it needs the boundaries, which only we have.
        val stats = BadgeStore.stats(Coverage.compute())

        val payload = buildJsonObject {
            put("trips", tripsForUpload())
            // Deletions the server has not seen yet. Without them its copy comes
            // back in the merge and only this device's own tombstone filter
            // hides it — every other device would resurrect the trip.
            put("deletedTripStartTimes", buildJsonArrayOfLongs(TripStore.deletedStartTimes()))
            put("traces", buildJsonArrayOfStrings(TraceStore.rawLines()))
            put("badges", jsonObjectOf(BadgeStore.rawJson()))
            put("savedPlaces", jsonArrayOf(SavedPlaces.rawJson()))
            put("stats", stats.toJson())
            put("shareFog", Settings.shareFog.value)
        }

        val merged = Api.requestJson("POST", "/sync", payload)
        val trips = merged.optArray("trips") ?: JsonArrayEmpty
        val traces = merged.optArray("traces") ?: JsonArrayEmpty
        val badges = merged.optObject("badges") ?: jsonObjectOf("{}")

        TripStore.replaceRaw(trips.string())
        TraceStore.replaceLines(traces.indices.map { traces.optString(it) })
        BadgeStore.replaceRaw(badges.string())
        // Absent on an older server: leave the local shortcuts untouched.
        merged.optArray("savedPlaces")?.let {
            SavedPlaces.replaceFromServer(it.string())
        }
        return SyncResult(trips.size, traces.size, badges.size)
    }

    /**
     * The stored trips, each with `topSpeedKmh` alongside the `topSpeedMps` this
     * app records in.
     *
     * The server keeps a trip document opaque apart from a handful of fields the
     * read-only dashboard lists, and top speed is one of them — in km/h. Derived
     * here rather than written into the store so that trips recorded before this
     * build also arrive complete.
     */
    private fun tripsForUpload() = buildJsonArray {
        for (trip in jsonArrayOf(TripStore.rawJson()).objects()) {
            add(buildJsonObject {
                trip.forEach { (key, value) -> put(key, value) }
                put("topSpeedKmh", trip.optDouble("topSpeedMps", 0.0) * 3.6)
            })
        }
    }
}
