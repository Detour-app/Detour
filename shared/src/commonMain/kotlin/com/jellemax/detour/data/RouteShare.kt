package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A route a friend sent us, as the inbox lists it. [serverId] is the server's
 * own identifier for the share — not [SavedRoute.id], which the sender
 * assigned locally and may collide with an id we already have on this device.
 * [serverId] only matters for [RouteShare.delete]; once [route] is saved into
 * [RouteStore] it lives under its own id like any other route.
 */
data class SharedRoute(
    val serverId: String,
    val from: String,
    val createdMs: Long,
    val route: SavedRoute,
)

/**
 * Client for the sync server's route-sharing endpoints (deployed separately;
 * see server/). Lives here rather than in app/ because [Api] is internal to
 * this module — nothing outside shared can call it directly.
 */
object RouteShare {

    // The paths are /shared-routes/… rather than the obvious /routes/…: a
    // Cloudflare tunnel fronting both this server and a GraphHopper on one
    // hostname matches its /route rule as a prefix, and answers every
    // /routes/* request from the router instead.

    /**
     * Sends [route] to [username]. The server answers 403 when the two
     * accounts aren't accepted friends, 400 on a route it can't parse, and
     * 413 past its 512 KB size cap — all three surface as [okio.IOException]
     * (or [AuthException] for an expired session) via [Api.request].
     */
    suspend fun share(username: String, route: SavedRoute) {
        Api.request(
            "POST", "/shared-routes",
            buildJsonObject {
                put("to", username)
                put("route", route.toJson())
            },
        )
    }

    /**
     * Routes friends have sent that are waiting in the inbox. Pulling this
     * list doesn't clear it server-side — most callers want [pullInbox]
     * instead, which also saves and drains it; this is exposed separately for
     * anything that just wants to look without consuming (or as the ingredient
     * [pullInbox] is built from).
     */
    suspend fun inbox(): List<SharedRoute> {
        val o = Api.requestJson("GET", "/shared-routes")
        return o.optArray("routes")?.objects().orEmpty().mapNotNull { entry ->
            val routeObj = entry.optObject("route") ?: return@mapNotNull null
            val route = routeFromJson(routeObj) ?: return@mapNotNull null
            SharedRoute(
                serverId = entry.optString("id"),
                from = entry.optString("from"),
                createdMs = entry.optLong("createdAtMs"),
                route = route,
            )
        }
    }

    /** Removes one inbox entry by its server identifier — see [SharedRoute.serverId]. */
    suspend fun delete(serverId: String) {
        Api.request("DELETE", "/shared-routes/$serverId")
    }

    /**
     * Pulls the inbox, saves every route into [RouteStore] and drains the
     * server side of it — the one operation both Android and iOS want on
     * "refresh", written once here instead of twice (Swift also has no
     * ergonomic way to call a Kotlin data class's `copy()`, which the fresh-id
     * remap below needs). Returns how many routes were pulled, for a status
     * line.
     *
     * Every pulled route gets a fresh local id, never the sender's own
     * [SavedRoute.id] — see the collision note on [SharedRoute.serverId]:
     * reusing it could silently overwrite one of this device's own routes.
     * [SavedRoute.sharedBy] is left as the server set it, so the saved route
     * still shows who sent it.
     *
     * Failure mode, chosen deliberately: if the local save succeeds but the
     * follow-up [delete] then fails, the row is left on the server, and the
     * *next* [pullInbox] call saves it again under a second fresh id — a
     * duplicate. That is accepted rather than avoided, because avoiding it
     * would mean either skipping the save until a delete is known to succeed
     * (delete can only be attempted after the save, so that would drop the
     * route on a network blip instead of just duplicating it) or reusing some
     * remembered id across pulls (reintroducing the exact collision risk this
     * whole fresh-id scheme exists to avoid). A duplicate the user can just
     * delete; a silently dropped or overwritten route can't be recovered.
     */
    suspend fun pullInbox(): Int {
        val entries = inbox()
        var nextId = nowMs()
        for (shared in entries) {
            RouteStore.save(shared.route.copy(id = nextId))
            nextId++
            try {
                delete(shared.serverId)
            } catch (e: Exception) {
                // Left on the server; see the failure-mode note above.
            }
        }
        return entries.size
    }
}
