package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class GroupMember(val username: String, val status: String, val sharing: Boolean)

data class Group(
    val id: Int,
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

    suspend fun create(kind: String, name: String): Int =
        Api.requestJson("POST", "/${ns(kind)}", buildJsonObject { put("name", name) })
            .optInt("id")

    suspend fun list(kind: String): List<Group> =
        jsonArrayOf(Api.request("GET", "/${ns(kind)}")).objects().map { groupFromJson(it, kind) }

    /** Returns the resulting status, e.g. "invited". Only accepted friends
     *  can be invited — the server enforces this, this just surfaces it.
     *
     *  These three take a [kind] only to pick the namespace. The server
     *  resolves [groupId] against the one shared `groups` table and never
     *  looks at which prefix it arrived under, so either path behaves
     *  identically today — but calling a convoy's endpoint `/circles/…`
     *  would read as a bug at every call site, and would become one the day
     *  a per-kind rule grows on one of them. */
    suspend fun invite(kind: String, groupId: Int, username: String): String =
        Api.requestJson(
            "POST", "/${ns(kind)}/$groupId/invite", buildJsonObject { put("username", username) }
        ).optString("status")

    suspend fun respond(kind: String, groupId: Int, accept: Boolean) {
        Api.request(
            "POST", "/${ns(kind)}/$groupId/respond", buildJsonObject { put("accept", accept) })
    }

    suspend fun leave(kind: String, groupId: Int) {
        Api.request("POST", "/${ns(kind)}/$groupId/leave")
    }

    /** Circles only — pause/resume sharing your position with the group.
     *  The server 404s if [groupId] is actually a convoy: a convoy
     *  connection *is* sharing, so there is nothing to pause. */
    suspend fun setSharing(groupId: Int, sharing: Boolean): Boolean =
        Api.requestJson(
            "POST", "/circles/$groupId/sharing", buildJsonObject { put("sharing", sharing) }
        ).optBoolean("sharing")

}

/** Extracted from [Groups.list] so JSON parsing is testable without a
 *  network round trip. */
internal fun groupFromJson(o: JsonObject, kind: String): Group {
    val members = (o.optArray("members") ?: JsonArrayEmpty).objects().map { m ->
        GroupMember(
            username = m.optString("username"),
            status = m.optString("status"),
            // Absent for a convoy: being connected to one already is
            // sharing, there's no separate flag for the server to send.
            sharing = if (m.has("sharing")) m.optBoolean("sharing") else true,
        )
    }
    return Group(
        id = o.optInt("id"),
        name = o.optString("name"),
        kind = kind,
        status = o.optString("status"),
        members = members,
    )
}
