package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ConvoyMember(val username: String, val status: String)

data class Convoy(
    val id: Int,
    val name: String,
    /** This device's own membership status in the convoy: "invited" or "accepted". */
    val status: String,
    val members: List<ConvoyMember>,
)

/** Convoy CRUD — creating/inviting/joining/leaving.
 *  Live location and push-to-talk are a separate WebSocket leg, see
 *  `net/ConvoyLiveClient.kt`; this object only manages membership, which is
 *  what gates access to that socket on the server. */
object Convoys {

    suspend fun create(name: String): Int =
        Api.requestJson("POST", "/convoys", buildJsonObject { put("name", name) })
            .optInt("id")

    suspend fun list(): List<Convoy> =
        jsonArrayOf(Api.request("GET", "/convoys")).objects().map { parseConvoy(it) }

    /** Returns the resulting status, e.g. "invited". Only accepted friends
     *  can be invited — the server enforces this, this just surfaces it. */
    suspend fun invite(convoyId: Int, username: String): String =
        Api.requestJson(
            "POST", "/convoys/$convoyId/invite", buildJsonObject { put("username", username) }
        ).optString("status")

    suspend fun respond(convoyId: Int, accept: Boolean) {
        Api.request(
            "POST", "/convoys/$convoyId/respond", buildJsonObject { put("accept", accept) })
    }

    suspend fun leave(convoyId: Int) {
        Api.request("POST", "/convoys/$convoyId/leave")
    }

    private fun parseConvoy(o: JsonObject): Convoy {
        val members = (o.optArray("members") ?: JsonArrayEmpty).objects().map { m ->
            ConvoyMember(m.optString("username"), m.optString("status"))
        }
        return Convoy(
            id = o.optInt("id"),
            name = o.optString("name"),
            status = o.optString("status"),
            members = members,
        )
    }
}
