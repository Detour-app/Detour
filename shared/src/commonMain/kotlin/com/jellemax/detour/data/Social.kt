package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

/** Account state and the sign-in/out calls. */
object Account {

    val username: StateFlow<String> = Settings.authUsername
    val signedIn: Boolean get() = Settings.authToken.value.isNotBlank()

    suspend fun register(
        user: String,
        password: String,
        invite: String = "",
        email: String = "",
    ) {
        val body = buildJsonObject {
            put("username", user)
            put("password", password)
            if (invite.isNotBlank()) put("invite", invite)
            if (email.isNotBlank()) put("email", email)
        }
        store(Api.requestJson("POST", "/auth/register", body, auth = false))
    }

    suspend fun login(user: String, password: String) {
        val body = buildJsonObject {
            put("username", user)
            put("password", password)
        }
        store(Api.requestJson("POST", "/auth/login", body, auth = false))
    }

    /**
     * Asks the server to mail a reset link to whoever owns [who] — a username
     * or an email address. The server answers the same way whether or not that
     * account exists, so there is nothing here to report back beyond "sent, if
     * there was anywhere to send it".
     */
    suspend fun forgotPassword(who: String) {
        Api.request(
            "POST", "/auth/forgot", buildJsonObject { put("username", who) }, auth = false,
        )
    }

    /**
     * Redeems the code from a reset mail. The server signs every device out as
     * part of this, so the caller lands back on the sign-in form with a
     * password that works.
     */
    suspend fun resetPassword(token: String, password: String) {
        Api.request(
            "POST", "/auth/reset",
            buildJsonObject {
                put("token", token)
                put("password", password)
            },
            auth = false,
        )
    }

    /**
     * Clears the local session, and tells the server to revoke the token so a
     * copy of it can't be replayed. A failing revoke must not strand the user
     * signed in on a device they're trying to sign out of.
     */
    suspend fun signOut() {
        try {
            Api.request("POST", "/auth/logout")
        } catch (e: Exception) {
            // Offline, or the token was already dead. Local clear is what matters.
        }
        Settings.setAuth("", "")
    }

    private fun store(response: JsonObject) {
        Settings.setAuth(response.optString("token"), response.optString("username"))
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

/** Friend requests and the shared leaderboard. */
object Friends {

    suspend fun lists(): FriendLists {
        val o = Api.requestJson("GET", "/friends")
        return FriendLists(
            friends = o.stringList("friends"),
            incoming = o.stringList("incoming"),
            outgoing = o.stringList("outgoing"),
        )
    }

    /** Returns the resulting status: "pending" or "accepted" (when they had
     *  already asked us, and this request answered theirs). */
    suspend fun request(username: String): String =
        Api.requestJson(
            "POST", "/friends/request", buildJsonObject { put("username", username) }
        ).optString("status")

    suspend fun respond(username: String, accept: Boolean) {
        Api.request(
            "POST", "/friends/respond",
            buildJsonObject {
                put("username", username)
                put("accept", accept)
            },
        )
    }

    suspend fun remove(username: String) {
        Api.request(
            "POST", "/friends/remove", buildJsonObject { put("username", username) })
    }

    suspend fun stats(): List<FriendStats> =
        jsonArrayOf(Api.request("GET", "/friends/stats")).objects().map { o ->
            val badges = o.optObject("badges")
            FriendStats(
                username = o.optString("username"),
                stats = riderStatsFromJson(o.optObject("stats") ?: jsonObjectOf("{}")),
                badgeIds = badges?.keys?.toList().orEmpty(),
            )
        }

    private fun JsonObject.stringList(key: String): List<String> {
        val array = optArray(key) ?: return emptyList()
        return array.indices.map { array.optString(it) }
    }
}

fun RiderStats.toJson(): JsonObject = buildJsonObject {
    put("totalDistanceMeters", totalDistanceMeters)
    put("topSpeedKmh", topSpeedKmh)
    put("longestTripMeters", longestTripMeters)
    put("maxLeanDeg", maxLeanDeg)
    put("municipalitiesVisited", municipalitiesVisited)
    put("bestCoveragePercent", bestCoveragePercent)
    put("tripCount", tripCount)
}

fun riderStatsFromJson(o: JsonObject): RiderStats = RiderStats(
    totalDistanceMeters = o.optDouble("totalDistanceMeters", 0.0),
    topSpeedKmh = o.optDouble("topSpeedKmh", 0.0),
    longestTripMeters = o.optDouble("longestTripMeters", 0.0),
    maxLeanDeg = o.optDouble("maxLeanDeg", 0.0),
    municipalitiesVisited = o.optInt("municipalitiesVisited", 0),
    bestCoveragePercent = o.optDouble("bestCoveragePercent", 0.0),
    tripCount = o.optInt("tripCount", 0),
)
