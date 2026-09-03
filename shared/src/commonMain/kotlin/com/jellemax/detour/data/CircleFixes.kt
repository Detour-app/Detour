package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A circle member's last known position, as `GET /api/circles/{id}/positions`
 *  returns it. Identity only — the handle to draw comes from the group's
 *  membership, which [CircleFixes.othersFixes] fetches in the same breath. */
data class MemberFix(
    val riderId: RiderId,
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

    // @Throws(Exception::class) on [postFix] and [othersFixes] below, the two
    // called directly from iosApp/Detour: see the doc on [SyncClient.sync]
    // for why `Exception` and not just `IOException`. [fixes] is not
    // annotated — nothing outside this module calls it directly, only
    // [othersFixes] does, and that already carries the annotation.
    @Throws(Exception::class)
    suspend fun postFix(groupId: String, lat: Double, lon: Double, accuracyM: Double, tsMs: Long) {
        Api.request(
            "POST", "/circles/$groupId/positions",
            buildJsonObject {
                put("latitude", lat)
                put("longitude", lon)
                put("accuracyMeters", accuracyM)
                put("timestampMs", tsMs)
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
    @Throws(Exception::class)
    suspend fun othersFixes(selfUsername: String): List<MemberFix> =
        newestPerOtherMember(
            Groups.list("circle")
                .filter { it.status == "accepted" }
                .flatMap { fixes(it.id) },
            selfUsername,
        )

    /** Latest fix per accepted, currently-sharing member — the server drops a
     *  paused member's fix at the read, even though the row may still exist. */
    suspend fun fixes(groupId: String): List<MemberFix> {
        val o = Api.requestJson("GET", "/circles/$groupId/positions")
        return o.optArray("fixes")?.objects().orEmpty().map { memberFixFromJson(it) }
    }
}

/** Extracted from [CircleFixes.othersFixes] for the same reason
 *  [memberFixFromJson] is: it is the part with rules in it — drop yourself,
 *  collapse someone you share two circles with to their newest fix — and it
 *  is otherwise only reachable behind two HTTP calls. */
internal fun newestPerOtherMember(
    fixes: List<MemberFix>,
    selfUsername: String,
): List<MemberFix> = fixes
    .filter { it.riderId.value != selfUsername }
    .groupBy { it.riderId }
    .map { (_, forUser) -> forUser.maxBy { it.tsMs } }

/** Extracted from [CircleFixes.fixes] so JSON parsing is testable without a
 *  network round trip. */
internal fun memberFixFromJson(f: JsonObject): MemberFix = MemberFix(
    riderId = RiderId(f.optString("riderId")),
    lat = f.optDouble("latitude"),
    lon = f.optDouble("longitude"),
    // Null when the platform reported no accuracy; the map treats a
    // non-positive radius as "no circle to draw" already.
    accuracyM = f.optDouble("accuracyMeters", 0.0),
    tsMs = f.optLong("timestampMs"),
)
