package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A place shared into a circle: a [SavedPlace] plus a geofence radius and
 * who owns it. [serverId] is the `circle_places` row id, needed for
 * [CirclePlaces.delete] — not [SavedPlace.id], which the owner assigned
 * locally and which two members could otherwise collide on, same reasoning
 * as [SharedRoute.serverId].
 */
data class CirclePlace(
    val serverId: Long,
    val groupId: Int,
    val owner: String,
    val radiusM: Double,
    val createdMs: Long,
    val place: SavedPlace,
)

/**
 * Client for the sync server's circle-places endpoints. Follows the
 * shared-routes precedent exactly (see [RouteShare]): user-owned, shared
 * into a group, revoked when the owner leaves that circle — the server
 * stores [place]'s JSON opaquely, it just requires the object to carry
 * `id`, `name` and `radiusM`.
 */
object CirclePlaces {

    /** Shares [place] into [groupId] with a geofence of [radiusM] metres.
     *  Re-sharing the same [SavedPlace.id] updates the existing row instead
     *  of creating a second one — see `do_circle_place_share`. */
    suspend fun share(groupId: Int, place: SavedPlace, radiusM: Double) {
        Api.request(
            "POST", "/circle-places/share",
            buildJsonObject {
                put("groupId", groupId)
                put("place", buildJsonObject {
                    put("id", place.id)
                    put("name", place.name)
                    put("lat", place.location.lat)
                    put("lon", place.location.lon)
                    put("radiusM", radiusM)
                })
            },
        )
    }

    /** Every place shared into [groupId], newest first — the server 403s if
     *  the caller isn't an accepted member. */
    suspend fun places(groupId: Int): List<CirclePlace> {
        val o = Api.requestJson("GET", "/circle-places?groupId=$groupId")
        return o.optArray("places")?.objects().orEmpty().mapNotNull { entry ->
            val p = entry.optObject("place") ?: return@mapNotNull null
            CirclePlace(
                serverId = entry.optLong("id"),
                groupId = groupId,
                owner = entry.optString("owner"),
                radiusM = entry.optDouble("radiusM"),
                createdMs = entry.optLong("createdMs"),
                place = SavedPlace(
                    id = p.optLong("id"),
                    name = p.optString("name"),
                    location = LatLon(p.optDouble("lat"), p.optDouble("lon")),
                ),
            )
        }
    }

    /** Removes one shared place by its server row id — owner only, the
     *  server silently no-ops otherwise (see `do_circle_place_delete`). */
    suspend fun delete(serverId: Long) {
        Api.request("POST", "/circle-places/delete", buildJsonObject { put("id", serverId) })
    }
}
