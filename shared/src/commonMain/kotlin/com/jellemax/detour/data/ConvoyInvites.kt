package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.IOException

/**
 * A freshly minted invite link's secret. [token] is in the create answer and nowhere else, ever:
 * the server keeps only its hash, so a host who loses it mints a new one (which revokes this).
 */
data class ConvoyInvite(val token: String, val expiresAtMs: Long)

data class ConvoyGuest(val rider: RiderRef, val joinedAtMs: Long)

/**
 * What a link opens, read before joining it (#775), for the join confirm screen. [riderCount] is
 * the accepted members, host included. [alreadyMember] is true when the caller already rides in
 * it, in which case a join answers the convoy even if the link has since expired.
 */
data class ConvoyInvitePreview(
    val convoyName: String,
    val hostUsername: String,
    val riderCount: Int,
    val expiresAtMs: Long,
    val alreadyMember: Boolean,
)

/**
 * What the host sees of the riders who came in by link. [inviteExpiresAtMs] is null when no link
 * is usable right now (none minted, revoked, or expired), which is the host's cue to offer a new
 * one rather than a share of the old.
 */
data class ConvoyGuests(val inviteExpiresAtMs: Long?, val guests: List<ConvoyGuest>)

/** Why the server refused a join by link, one per status in CIRCLES_AND_CONVOYS.md §4.1. */
enum class ConvoyJoinRefusal(val status: Int) {
    /** No link has that token. Also what a server from before #641 answers, having no such path. */
    UNKNOWN_LINK(404),

    /** The host revoked the link, it passed its 12 hours, or the convoy ended. */
    EXPIRED(410),

    /** The host removed this rider; every link the convoy has refuses them from then on. */
    REMOVED(403),

    /** The convoy already has its 8 link guests. The only 400 a well-formed join gets back. */
    FULL(400),

    /** Too many attempts on this token or from this address; refused ones count too. */
    TOO_MANY_ATTEMPTS(429),
    ;

    companion object {
        /** Null for a status §4.1 gives no meaning on join (a 500, a proxy's 502). */
        fun fromStatus(status: Int): ConvoyJoinRefusal? = entries.firstOrNull { it.status == status }
    }
}

/**
 * A join the server refused for one of §4.1's reasons. Still an [IOException], so a caller that
 * only knows "the request failed" keeps working; a screen that tells the rider why reads
 * [refusal]. The server's own answer stays as [cause].
 */
class ConvoyJoinException(val refusal: ConvoyJoinRefusal, cause: HttpStatusException) :
    IOException(cause.message, cause)

/**
 * Client for the convoy invite-link endpoints (CIRCLES_AND_CONVOYS.md §4.1): the host mints,
 * revokes and lists, anyone signed in joins. Wire calls only. Every host call answers 403 to a
 * member who isn't the host and 404 to a non-member, as a plain [HttpStatusException].
 */
object ConvoyInvites {

    // @Throws(Exception::class) on every suspend fun below, for the iOS app — see the doc on
    // [SyncClient.sync] for why `Exception` and not just `IOException`.

    /** Host only. Revokes any link still usable, so a convoy has at most one. */
    @Throws(Exception::class)
    suspend fun createLink(convoyId: String): ConvoyInvite =
        convoyInviteFromJson(Api.requestJson("POST", "/convoys/$convoyId/invite-link"))

    /** Host only. Stops new joins; guests already in stay. */
    @Throws(Exception::class)
    suspend fun revokeLink(convoyId: String) {
        Api.request("DELETE", "/convoys/$convoyId/invite-link")
    }

    /** Host only. Guests still in the convoy; one who left or was removed is not listed. */
    @Throws(Exception::class)
    suspend fun guests(convoyId: String): ConvoyGuests =
        convoyGuestsFromJson(Api.requestJson("GET", "/convoys/$convoyId/guests"))

    /** Host only. Drops the guest's live sockets at once, and keeps them out of every link. */
    @Throws(Exception::class)
    suspend fun removeGuest(convoyId: String, riderId: RiderId) {
        Api.request("DELETE", "/convoys/$convoyId/guests/${riderId.value}")
    }

    /**
     * Which convoy [token] opens and who hosts it, without joining. Refuses exactly as [join]
     * would, with the same [ConvoyJoinException], and spends from the same per-token and
     * per-address caps, so a screen previews once per link rather than on every recomposition.
     */
    @Throws(Exception::class)
    suspend fun preview(token: String): ConvoyInvitePreview = try {
        convoyInvitePreviewFromJson(Api.requestJson("POST", "/convoys/join/preview", tokenBody(token)))
    } catch (e: HttpStatusException) {
        throw joinFailure(e)
    }

    /**
     * Joins the convoy [token] opens, as an accepted member, and returns it. Throws
     * [ConvoyJoinException] for a refusal §4.1 names. The token goes in the body, never the
     * path, which keeps it out of access logs.
     */
    @Throws(Exception::class)
    suspend fun join(token: String): Group = try {
        groupFromJson(Api.requestJson("POST", "/convoys/join", tokenBody(token)), "convoy")
    } catch (e: HttpStatusException) {
        throw joinFailure(e)
    }
}

private fun tokenBody(token: String): JsonObject = buildJsonObject { put("token", token) }

/** Extracted from [ConvoyInvites.join] and [ConvoyInvites.preview] so the status mapping is
 *  testable without a server. */
internal fun joinFailure(e: HttpStatusException): IOException =
    ConvoyJoinRefusal.fromStatus(e.code)?.let { ConvoyJoinException(it, e) } ?: e

internal fun convoyInviteFromJson(o: JsonObject): ConvoyInvite =
    ConvoyInvite(token = o.optString("token"), expiresAtMs = o.optLong("expiresAtMs"))

internal fun convoyInvitePreviewFromJson(o: JsonObject): ConvoyInvitePreview = ConvoyInvitePreview(
    convoyName = o.optString("convoyName"),
    hostUsername = o.optString("hostUsername"),
    riderCount = o.optInt("riderCount"),
    expiresAtMs = o.optLong("expiresAtMs"),
    alreadyMember = o.optBoolean("alreadyMember"),
)

internal fun convoyGuestsFromJson(o: JsonObject): ConvoyGuests = ConvoyGuests(
    inviteExpiresAtMs = if (o.has("inviteExpiresAtMs")) o.optLong("inviteExpiresAtMs") else null,
    guests = o.optArray("guests")?.objects().orEmpty().map { g ->
        ConvoyGuest(RiderRef(RiderId(g.optString("id")), g.optString("username")), g.optLong("joinedAtMs"))
    },
)

/** A parsed invite link: the server it belongs to, and the token to join with. */
data class ConvoyInviteLink(val serverUrl: String, val token: String) {
    /** True when this link was minted on [apiBase] ([SyncClient.url]), the only server a token
     *  means anything to. A trailing slash on either side doesn't count. */
    fun isOn(apiBase: String?): Boolean = apiBase != null && apiBase.trimEnd('/') == serverUrl
}

/**
 * The path an invite link opens, on the API host. Not under `/route…` (§4.1's path-naming note:
 * a tunnel shared with GraphHopper swallows that prefix).
 */
private const val INVITE_PATH = "/convoy-invite"

/** base64url, which is all the server's tokens are made of (§4.1). */
private val TOKEN = Regex("[A-Za-z0-9_-]+")

/**
 * The shareable link for [token] on [serverUrl] (the API base, [SyncClient.url]). The token rides
 * in the fragment: a browser never sends a fragment to the server, so a link opened where the app
 * isn't installed doesn't put the token in that server's access log either — the same reason
 * [ConvoyInvites.join] carries it in the body. [parseConvoyInviteLink] is the inverse.
 */
fun convoyInviteLink(serverUrl: String, token: String): String =
    serverUrl.trimEnd('/') + INVITE_PATH + "#" + token

/**
 * The server and token from a link [convoyInviteLink] built, or null for anything else: another
 * path, no token, or characters a token can't hold. Surrounding whitespace is ignored, as a
 * pasted link often carries some. The server is returned rather than checked, so a screen can
 * tell the rider a link belongs to a server they aren't signed in to.
 */
fun parseConvoyInviteLink(link: String): ConvoyInviteLink? {
    val trimmed = link.trim()
    val hash = trimmed.indexOf('#').takeIf { it >= 0 } ?: return null
    val token = trimmed.substring(hash + 1).takeIf { TOKEN.matches(it) } ?: return null
    val beforeToken = trimmed.substring(0, hash).trimEnd('/')
    if (!beforeToken.endsWith(INVITE_PATH)) return null
    val server = beforeToken.removeSuffix(INVITE_PATH)
    if (!server.contains("://")) return null
    return ConvoyInviteLink(serverUrl = server, token = token)
}
