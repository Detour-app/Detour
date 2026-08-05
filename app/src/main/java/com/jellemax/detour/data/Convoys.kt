package com.jellemax.detour.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ConvoyMember(val username: String, val status: String)

data class Convoy(
    val id: Int,
    val name: String,
    /** This device's own membership status in the convoy: "invited" or "accepted". */
    val status: String,
    val members: List<ConvoyMember>,
)

/** Convoy CRUD — creating/inviting/joining/leaving. All network calls block.
 *  Live location and push-to-talk are a separate WebSocket leg, see
 *  `net/ConvoyLiveClient.kt`; this object only manages membership, which is
 *  what gates access to that socket on the server. */
object Convoys {

    fun create(context: Context, name: String): Int =
        Api.requestJson(context, "POST", "/convoys", JSONObject().put("name", name)).getInt("id")

    fun list(context: Context): List<Convoy> {
        val array = JSONArray(Api.request(context, "GET", "/convoys"))
        return (0 until array.length()).map { i -> parseConvoy(array.getJSONObject(i)) }
    }

    /** Returns the resulting status, e.g. "invited". Only accepted friends
     *  can be invited — the server enforces this, this just surfaces it. */
    fun invite(context: Context, convoyId: Int, username: String): String =
        Api.requestJson(
            context, "POST", "/convoys/$convoyId/invite", JSONObject().put("username", username)
        ).optString("status")

    fun respond(context: Context, convoyId: Int, accept: Boolean) {
        Api.request(
            context, "POST", "/convoys/$convoyId/respond", JSONObject().put("accept", accept)
        )
    }

    fun leave(context: Context, convoyId: Int) {
        Api.request(context, "POST", "/convoys/$convoyId/leave")
    }

    private fun parseConvoy(o: JSONObject): Convoy {
        val membersArray = o.optJSONArray("members") ?: JSONArray()
        val members = (0 until membersArray.length()).map { i ->
            val m = membersArray.getJSONObject(i)
            ConvoyMember(m.getString("username"), m.getString("status"))
        }
        return Convoy(
            id = o.getInt("id"),
            name = o.getString("name"),
            status = o.getString("status"),
            members = members,
        )
    }
}
