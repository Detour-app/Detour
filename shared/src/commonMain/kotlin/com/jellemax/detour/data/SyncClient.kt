package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bidirectional sync with the owner's sync server (see
 * server/INSTALL.md). One POST
 * uploads local trips, fog-of-war traces, badges and the aggregate stats
 * friends are allowed to see; the server merges them with its copy and returns
 * the union, which replaces the local stores. Deleting and reinstalling the app
 * therefore restores everything on the first sync.
 *
 * The server keys everything on the signed-in user, so syncing requires an
 * account ([Account]). Traces and trips are only ever returned to their owner.
 */
object SyncClient {

    data class SyncResult(val trips: Int, val traces: Int, val badges: Int)

    /** Effective sync URL: the one server address (Settings) → baked default. */
    fun url(): String? =
        (RoutingServer.loadCustom()?.url ?: "")
            .ifBlank { BuildDefaults.syncUrl }
            .ifBlank { null }

    fun configured(): Boolean = url() != null

    suspend fun sync(): SyncResult {
        Settings.init()

        // Coverage is the only stat the server can't derive from the trips it
        // already holds — it needs the boundaries, which only we have.
        val stats = BadgeStore.stats(Coverage.compute())

        val payload = buildJsonObject {
            put("trips", jsonArrayOf(TripStore.rawJson()))
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
}
