package com.jellemax.maproulette.data

import android.content.Context
import com.jellemax.maproulette.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

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
    fun url(context: Context): String? =
        (RoutingServer.loadCustom(context)?.url ?: "")
            .ifBlank { BuildConfig.SYNC_URL }
            .ifBlank { null }

    fun configured(context: Context): Boolean = url(context) != null

    /** Fire-and-forget sync for the tracking service; never throws. */
    fun syncQuietly(context: Context) {
        if (!configured(context) || !Account.signedIn) return
        Thread {
            try {
                sync(context)
            } catch (e: Exception) {
                // Offline, server down, or signed out; the next sync catches up.
            }
        }.start()
    }

    /** Blocking sync; call off the main thread. */
    fun sync(context: Context): SyncResult {
        Settings.init(context)

        // Coverage is the only stat the server can't derive from the trips it
        // already holds — it needs the boundaries, which only we have.
        val stats = BadgeStore.stats(context, Coverage.compute(context))

        val payload = JSONObject()
            .put("trips", JSONArray(TripStore.rawJson(context)))
            .put("traces", JSONArray(TraceStore.rawLines(context)))
            .put("badges", JSONObject(BadgeStore.rawJson(context)))
            .put("savedPlaces", JSONArray(SavedPlaces.rawJson(context)))
            .put("stats", stats.toJson())
            .put("shareFog", Settings.shareFog.value)

        val merged = Api.requestJson(context, "POST", "/sync", payload)
        val trips = merged.getJSONArray("trips")
        val traces = merged.getJSONArray("traces")
        val badges = merged.optJSONObject("badges") ?: JSONObject()

        TripStore.replaceRaw(context, trips.toString())
        TraceStore.replaceLines(
            context, (0 until traces.length()).map { traces.getString(it) })
        BadgeStore.replaceRaw(context, badges.toString())
        // Absent on an older server: leave the local shortcuts untouched.
        merged.optJSONArray("savedPlaces")?.let {
            SavedPlaces.replaceFromServer(context, it.toString())
        }
        return SyncResult(trips.length(), traces.length(), badges.length())
    }
}
