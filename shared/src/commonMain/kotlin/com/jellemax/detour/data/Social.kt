package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A reset code that arrived by deep link (`detour://reset?token=…`).
 *
 * Legacy: password reset belongs to the realm now, which mails a link into a
 * browser and never into the app, so nothing on Android offers this any more.
 * It stays for the iOS app, which still signs in the old way until it gets its
 * own authorization-code flow (docs/IOS_PORT.md).
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

/**
 * Account state, as the rest of the app asks about it.
 *
 * Registration, sign-in and password reset are gone from here: the realm owns
 * them, and the browser leg that drives them lives in the platform layer
 * (`app/auth/AuthBrowser.kt` on Android, `iosApp/Detour/SignIn.swift` on iOS).
 * What is left is the two questions every screen asks — who are we, and are
 * we signed in — plus signing out. See [Auth].
 */
object Account {

    val username: StateFlow<String> = Auth.username
    val signedIn: Boolean get() = Auth.signedIn

    // @Throws(Exception::class): called directly from iosApp/Detour — see
    // the doc on [SyncClient.sync] for why `Exception` and not just
    // `IOException`.
    @Throws(Exception::class)
    suspend fun signOut() = Auth.signOut()
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

    // @Throws(Exception::class) on [lists], [request], [respond] and [stats]
    // below, all called directly from iosApp/Detour: see the doc on
    // [SyncClient.sync] for why `Exception` and not just `IOException`.
    // [remove] is not annotated — nothing outside this module calls it.
    @Throws(Exception::class)
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
    @Throws(Exception::class)
    suspend fun request(username: String): String =
        Api.requestJson(
            "POST", "/friends/requests", buildJsonObject { put("username", username) }
        ).optString("status")

    @Throws(Exception::class)
    suspend fun respond(username: String, accept: Boolean) {
        // Handles are letters, digits, dot, underscore and hyphen — all safe in a
        // path segment, so there is nothing to encode here.
        Api.request(
            "POST", "/friends/requests/$username/respond",
            buildJsonObject { put("accept", accept) },
        )
    }

    suspend fun remove(username: String) {
        Api.request("DELETE", "/friends/$username")
    }

    @Throws(Exception::class)
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
    // The wire spells it out; the model keeps the short name it has always had.
    put("maxLeanDegrees", maxLeanDeg)
    put("municipalitiesVisited", municipalitiesVisited)
    put("bestCoveragePercent", bestCoveragePercent)
    put("tripCount", tripCount)
}

fun riderStatsFromJson(o: JsonObject): RiderStats = RiderStats(
    totalDistanceMeters = o.optDouble("totalDistanceMeters", 0.0),
    topSpeedKmh = o.optDouble("topSpeedKmh", 0.0),
    longestTripMeters = o.optDouble("longestTripMeters", 0.0),
    maxLeanDeg = o.optDouble("maxLeanDegrees", 0.0),
    municipalitiesVisited = o.optInt("municipalitiesVisited", 0),
    bestCoveragePercent = o.optDouble("bestCoveragePercent", 0.0),
    tripCount = o.optInt("tripCount", 0),
)
