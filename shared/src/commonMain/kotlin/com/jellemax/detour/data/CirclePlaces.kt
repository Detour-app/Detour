package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A place shared into a circle: a [SavedPlace] plus a geofence radius and
 * who owns it. [serverId] is the server's own identifier for the share,
 * needed for [CirclePlaces.delete] — not [SavedPlace.id], which the owner
 * assigned locally and which two members could otherwise collide on, same
 * reasoning as [SharedRoute.serverId].
 */
data class CirclePlace(
    val serverId: String,
    val groupId: String,
    val ownerId: RiderId,
    val radiusM: Double,
    val createdMs: Long,
    val place: SavedPlace,
    /** True when the server withheld this place's coordinates — a home shared into the circle
     *  by someone who has not marked the viewer family (#270). [place] still carries a name
     *  ("{owner}'s home") but its [SavedPlace.location] is meaningless, so a screen must show
     *  the fact of the place without offering to navigate to it. */
    val coordinatesWithheld: Boolean = false,
)

/**
 * Client for the circle-places endpoints. Follows the shared-routes precedent
 * exactly (see [RouteShare]): user-owned, shared into a group, revoked when the
 * owner leaves that circle — the server stores the place's JSON opaquely and
 * only reads `id`, `name` and `radiusMeters` out of it, so the coordinates ride
 * along inside the same object without the server ever parsing them.
 */
object CirclePlaces {

    // @Throws(Exception::class) on every suspend fun below, all of them
    // called directly from iosApp/Detour: see the doc on [SyncClient.sync]
    // for why `Exception` and not just `IOException`.

    /** Shares [place] into [groupId] with a geofence of [radiusM] metres.
     *  Re-sharing the same [SavedPlace.id] updates the existing row instead
     *  of creating a second one. */
    @Throws(Exception::class)
    suspend fun share(groupId: String, place: SavedPlace, radiusM: Double) {
        Api.request(
            "POST", "/circles/$groupId/places",
            buildJsonObject {
                put("place", buildJsonObject {
                    put("id", place.id)
                    put("name", place.name)
                    put("radiusMeters", radiusM)
                    // Kind and coordinates are first-class to the server now: it withholds a
                    // home's coordinates from a circle member who is not family of the owner
                    // (#270), which a name it could not read never let it do.
                    put("kind", place.kind.name)
                    put("lat", place.location.lat)
                    put("lon", place.location.lon)
                })
            },
        )
    }

    /** Every place shared into [groupId], newest first — the server 403s if
     *  the caller isn't an accepted member. */
    @Throws(Exception::class)
    suspend fun places(groupId: String): List<CirclePlace> {
        val o = Api.requestJson("GET", "/circles/$groupId/places")
        return o.optArray("places")?.objects().orEmpty().mapNotNull { entry ->
            val p = entry.optObject("place") ?: return@mapNotNull null
            // A withheld home carries no lat/lon. optDouble would read an absent key as 0.0 —
            // a valid-looking null-island coordinate — so the presence of both keys is what
            // decides whether there is a place to navigate to at all.
            val withheld = !(p.has("lat") && p.has("lon"))
            CirclePlace(
                serverId = entry.optString("id"),
                groupId = groupId,
                ownerId = RiderId(entry.optString("ownerId")),
                radiusM = entry.optDouble("radiusMeters"),
                createdMs = entry.optLong("createdAtMs"),
                place = SavedPlace(
                    id = p.optLong("id"),
                    // The name the server holds wins: it is what every other
                    // member sees, and the owner may have renamed it since.
                    name = entry.optString("name").ifBlank { p.optString("name") },
                    location = LatLon(p.optDouble("lat"), p.optDouble("lon")),
                ),
                coordinatesWithheld = withheld,
            )
        }
    }

    /** Removes one shared place by its server identifier — owner only, the
     *  server 404s otherwise rather than saying whose it was. */
    @Throws(Exception::class)
    suspend fun delete(serverId: String) {
        Api.request("DELETE", "/circle-places/$serverId")
    }
}
