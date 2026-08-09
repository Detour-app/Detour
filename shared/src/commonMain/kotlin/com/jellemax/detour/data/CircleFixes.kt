package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A circle member's last known position, as `GET /circles/{id}/fixes` returns it. */
data class MemberFix(
    val username: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val tsMs: Long,
)

/**
 * The low-cadence position transport for circles (docs/CIRCLES_AND_CONVOYS.md
 * section 10): a plain `POST` of the latest fix rather than holding a
 * WebSocket open all day for something that updates on the order of
 * minutes. This is deliberately separate from the convoy live relay — a
 * circle's position never needs a socket, only [Groups] gates access to it.
 */
object CircleFixes {

    suspend fun postFix(groupId: Int, lat: Double, lon: Double, accuracyM: Double, tsMs: Long) {
        Api.request(
            "POST", "/circles/$groupId/fix",
            buildJsonObject {
                put("lat", lat)
                put("lon", lon)
                put("accuracyM", accuracyM)
                put("ts", tsMs)
            },
        )
    }

    /** What a map wants to draw: one newest fix per *other* person, across
     *  every circle you're in — a circle is the always-on relationship, so
     *  the map never waits for one to be picked. Your own fix comes back
     *  from the server too and is dropped here, since drawing it would stack
     *  a second marker on your own position; someone you share two circles
     *  with reports once per circle and is collapsed to their newest.
     *
     *  Both the phone map and the car map read this, so they can't drift
     *  apart on which members count. */
    suspend fun othersFixes(selfUsername: String): List<MemberFix> =
        Groups.list("circle")
            .filter { it.status == "accepted" }
            .flatMap { fixes(it.id) }
            .filter { it.username != selfUsername }
            .groupBy { it.username }
            .map { (_, forUser) -> forUser.maxBy { it.tsMs } }

    /** Latest fix per accepted, currently-sharing member — the server drops
     *  a paused member's fix here even though the row may still exist, see
     *  `do_circle_fixes`. */
    suspend fun fixes(groupId: Int): List<MemberFix> {
        val o = Api.requestJson("GET", "/circles/$groupId/fixes")
        return o.optArray("fixes")?.objects().orEmpty().map { memberFixFromJson(it) }
    }
}

/** Extracted from [CircleFixes.fixes] so JSON parsing is testable without a
 *  network round trip. */
internal fun memberFixFromJson(f: JsonObject): MemberFix = MemberFix(
    username = f.optString("username"),
    lat = f.optDouble("lat"),
    lon = f.optDouble("lon"),
    accuracyM = f.optDouble("accuracyM"),
    tsMs = f.optLong("ts"),
)
