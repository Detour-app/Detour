package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class GroupMember(
    val id: RiderId,
    val username: String,
    val status: String,
    val sharing: Boolean,
)

/**
 * The handle to draw for a rider, from the membership this screen already has.
 *
 * Exists because payloads stopped carrying a handle beside every id (#133):
 * positions, places and events identify a rider, and membership names them, so
 * every label is one lookup away rather than repeated on the wire. Empty for an
 * id no membership knows — a peer who joined since the last reload — which the
 * caller draws as a placeholder rather than treating as an error.
 */
fun List<GroupMember>.handleFor(riderId: RiderId): String =
    firstOrNull { it.id == riderId }?.username.orEmpty()

data class Group(
    /** The server's identifier. Opaque: a UUID today, and nothing here reads it
     *  as anything but a string to put back in a path. */
    val id: String,
    val name: String,
    val kind: String,
    /** This device's own membership status in the group: "invited" or "accepted". */
    val status: String,
    val members: List<GroupMember>,
)

/**
 * Convoys and circles are one entity on the server, discriminated by [kind]
 * ("convoy" or "circle") — see docs/CIRCLES_AND_CONVOYS.md. This object is
 * membership CRUD only, the same shape `Convoys.kt` used to be: creating,
 * listing, inviting, responding, leaving, and (circles only) pausing. It's
 * what gates access to the rest of a group's traffic — a convoy's live
 * location and push-to-talk are a separate WebSocket leg (see
 * `net/ConvoyLiveClient.kt`), and a circle's low-cadence position, places
 * and arrival events live in [CircleFixes], [CirclePlaces] and
 * [CircleEvents].
 */
object Groups {

    private fun ns(kind: String) = if (kind == "circle") "circles" else "convoys"

    // @Throws(Exception::class) on every suspend fun below, all of them
    // called directly from iosApp/Detour: see the doc on [SyncClient.sync]
    // for why `Exception` and not just `IOException`.

    @Throws(Exception::class)
    suspend fun create(kind: String, name: String): String =
        Api.requestJson("POST", "/${ns(kind)}", buildJsonObject { put("name", name) })
            .optString("id")

    @Throws(Exception::class)
    suspend fun list(kind: String): List<Group> =
        jsonArrayOf(Api.request("GET", "/${ns(kind)}")).objects().map { groupFromJson(it, kind) }

    /** Returns the resulting status, e.g. "invited". Only accepted friends
     *  can be invited — the server enforces this, this just surfaces it.
     *
     *  Membership is the one part of a group that is *not* per-kind: one set of
     *  routes under `/groups/{id}` serves both, because the rule being applied
     *  ("are you a member of this thing") is the same either way. Which kind it
     *  is stays a property of the group the server resolves [groupId] to. */
    @Throws(Exception::class)
    suspend fun invite(groupId: String, username: String): String =
        Api.requestJson(
            "POST", "/groups/$groupId/invitations", buildJsonObject { put("username", username) }
        ).optString("status")

    @Throws(Exception::class)
    suspend fun respond(groupId: String, accept: Boolean) {
        Api.request(
            "POST", "/groups/$groupId/invitations/respond",
            buildJsonObject { put("accept", accept) },
        )
    }

    @Throws(Exception::class)
    suspend fun leave(groupId: String) {
        Api.request("DELETE", "/groups/$groupId/membership")
    }

    /** Circles only — pause/resume sharing your position with the group.
     *  The server 404s if [groupId] is actually a convoy: a convoy
     *  connection *is* sharing, so there is nothing to pause. */
    @Throws(Exception::class)
    suspend fun setSharing(groupId: String, sharing: Boolean): Boolean =
        Api.requestJson(
            "PUT", "/circles/$groupId/sharing", buildJsonObject { put("sharing", sharing) }
        ).optBoolean("sharing")

}

/** Extracted from [Groups.list] so JSON parsing is testable without a
 *  network round trip. */
internal fun groupFromJson(o: JsonObject, kind: String): Group {
    val members = (o.optArray("members") ?: JsonArrayEmpty).objects().map { m ->
        GroupMember(
            id = RiderId(m.optString("id")),
            username = m.optString("username"),
            status = m.optString("status"),
            // Absent for a convoy: being connected to one already is
            // sharing, there's no separate flag for the server to send.
            sharing = if (m.has("sharing")) m.optBoolean("sharing") else true,
        )
    }
    return Group(
        id = o.optString("id"),
        name = o.optString("name"),
        kind = kind,
        status = o.optString("status"),
        members = members,
    )
}
