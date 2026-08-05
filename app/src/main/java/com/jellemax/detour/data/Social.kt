package com.jellemax.detour.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A reset code that arrived by deep link (`detour://reset?token=…`),
 * parked until the Friends screen can show the form that spends it.
 *
 * Deliberately in memory only: a code left on disk outlives the mail it came
 * from, and the whole point of it is being short lived.
 */
object PendingReset {
    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    fun offer(value: String) {
        _token.value = value
    }

    fun clear() {
        _token.value = ""
    }
}

/** Account state and the sign-in/out calls. All network calls block. */
object Account {

    val username: StateFlow<String> = Settings.authUsername
    val signedIn: Boolean get() = Settings.authToken.value.isNotBlank()

    fun register(
        context: Context,
        user: String,
        password: String,
        invite: String = "",
        email: String = "",
    ) {
        val body = JSONObject().put("username", user).put("password", password)
        if (invite.isNotBlank()) body.put("invite", invite)
        if (email.isNotBlank()) body.put("email", email)
        store(context, Api.requestJson(context, "POST", "/auth/register", body, auth = false))
    }

    fun login(context: Context, user: String, password: String) {
        val body = JSONObject().put("username", user).put("password", password)
        store(context, Api.requestJson(context, "POST", "/auth/login", body, auth = false))
    }

    /**
     * Asks the server to mail a reset link to whoever owns [who] — a username
     * or an email address. The server answers the same way whether or not that
     * account exists, so there is nothing here to report back beyond "sent, if
     * there was anywhere to send it".
     */
    fun forgotPassword(context: Context, who: String) {
        Api.request(
            context, "POST", "/auth/forgot", JSONObject().put("username", who), auth = false,
        )
    }

    /**
     * Redeems the code from a reset mail. The server signs every device out as
     * part of this, so the caller lands back on the sign-in form with a
     * password that works.
     */
    fun resetPassword(context: Context, token: String, password: String) {
        Api.request(
            context, "POST", "/auth/reset",
            JSONObject().put("token", token).put("password", password),
            auth = false,
        )
    }

    /**
     * Clears the local session, and tells the server to revoke the token so a
     * copy of it can't be replayed. A failing revoke must not strand the user
     * signed in on a device they're trying to sign out of.
     */
    fun signOut(context: Context) {
        try {
            Api.request(context, "POST", "/auth/logout")
        } catch (e: Exception) {
            // Offline, or the token was already dead. Local clear is what matters.
        }
        Settings.setAuth("", "")
    }

    private fun store(context: Context, response: JSONObject) {
        Settings.setAuth(response.getString("token"), response.getString("username"))
    }
}

/** A friend's aggregate numbers. Never their trips or traces — the server
 *  doesn't send those, and this type has nowhere to put them. */
data class FriendStats(
    val username: String,
    val stats: RiderStats,
    val badgeIds: List<String>,
)

data class FriendLists(
    val friends: List<String>,
    val incoming: List<String>,
    val outgoing: List<String>,
)

/** Friend requests and the shared leaderboard. All network calls block. */
object Friends {

    fun lists(context: Context): FriendLists {
        val o = Api.requestJson(context, "GET", "/friends")
        return FriendLists(
            friends = o.stringList("friends"),
            incoming = o.stringList("incoming"),
            outgoing = o.stringList("outgoing"),
        )
    }

    /** Returns the resulting status: "pending" or "accepted" (when they had
     *  already asked us, and this request answered theirs). */
    fun request(context: Context, username: String): String =
        Api.requestJson(
            context, "POST", "/friends/request", JSONObject().put("username", username)
        ).optString("status")

    fun respond(context: Context, username: String, accept: Boolean) {
        Api.request(
            context, "POST", "/friends/respond",
            JSONObject().put("username", username).put("accept", accept),
        )
    }

    fun remove(context: Context, username: String) {
        Api.request(
            context, "POST", "/friends/remove", JSONObject().put("username", username))
    }

    fun stats(context: Context): List<FriendStats> {
        val array = JSONArray(Api.request(context, "GET", "/friends/stats"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val badges = o.optJSONObject("badges") ?: JSONObject()
            FriendStats(
                username = o.getString("username"),
                stats = riderStatsFromJson(o.optJSONObject("stats") ?: JSONObject()),
                badgeIds = badges.keys().asSequence().toList(),
            )
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
}

fun RiderStats.toJson(): JSONObject = JSONObject()
    .put("totalDistanceMeters", totalDistanceMeters)
    .put("topSpeedKmh", topSpeedKmh)
    .put("longestTripMeters", longestTripMeters)
    .put("maxLeanDeg", maxLeanDeg)
    .put("municipalitiesVisited", municipalitiesVisited)
    .put("bestCoveragePercent", bestCoveragePercent)
    .put("tripCount", tripCount)

fun riderStatsFromJson(o: JSONObject): RiderStats = RiderStats(
    totalDistanceMeters = o.optDouble("totalDistanceMeters", 0.0),
    topSpeedKmh = o.optDouble("topSpeedKmh", 0.0),
    longestTripMeters = o.optDouble("longestTripMeters", 0.0),
    maxLeanDeg = o.optDouble("maxLeanDeg", 0.0),
    municipalitiesVisited = o.optInt("municipalitiesVisited", 0),
    bestCoveragePercent = o.optDouble("bestCoveragePercent", 0.0),
    tripCount = o.optInt("tripCount", 0),
)
