package com.jellemax.detour.data

import com.jellemax.detour.data.RoutingServer.accessHeaders
import kotlinx.serialization.json.JsonObject
import okio.IOException

/** The session was rejected: it is gone, not merely unreachable. */
class AuthException(message: String) : IOException(message)

/**
 * One place that knows how to talk to the sync + social API.
 *
 * Two layers of credentials, doing different jobs: the Cloudflare Access
 * service token gets us to the hostname at all (it is shared by everyone who
 * has the app), while the bearer token says *which rider* we are. Only the
 * second one decides whose trips come back — and it is now an access token the
 * identity provider minted, kept fresh by [Auth], rather than something this
 * server issued.
 */
internal object Api {

    /** Every route the app calls lives under this. */
    private const val PREFIX = "/api"

    /** Returns the raw response body. */
    suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        auth: Boolean = true,
        // The default suits the small social calls. /sync resends the whole
        // trace history and merges it server-side, which on a self-hosted box
        // runs well past 30s — SyncClient passes its own.
        readTimeoutMs: Long = 30_000,
    ): String {
        val base = SyncClient.url() ?: throw IOException("No server configured")
        val cf = RoutingServer.load()
        val url = base.trimEnd('/') + PREFIX + path
        return try {
            // The token the most recent attempt actually sent. Auth.refreshAfterRefusal
            // compares it against the current one to tell "nobody has refreshed yet"
            // from "a concurrent caller already did", so it has to be what went on
            // the wire rather than what was read before the call.
            var sent = ""
            retryingRefusedAuth(
                auth = auth,
                refresh = { Auth.refreshAfterRefusal(sent) },
            ) {
                // Headers are rebuilt per attempt. The retry exists precisely
                // because the first token was refused, so re-reading the bearer
                // is the point — hoisting this out would resend it.
                val headers = buildMap {
                    putAll(cf.accessHeaders())
                    if (auth) {
                        sent = Auth.bearer()
                        put("Authorization", "Bearer $sent")
                    }
                }
                Http.request(
                    method = method,
                    url = url,
                    body = body?.string(),
                    headers = headers,
                    // A sync upload re-sends the whole trip/trace history every
                    // time (traces.jsonl alone is 1MB+ after a year of riding),
                    // and JSON compresses roughly 10:1. The server decompresses
                    // Content-Encoding: gzip with a bound on the decompressed
                    // size, so this is unconditional rather than negotiated.
                    gzipBody = body != null,
                    readTimeoutMs = readTimeoutMs,
                )
            }
        } catch (e: HttpStatusException) {
            // Every non-2xx lands here, 401 included. A 401 reaching this point
            // survived a successful token refresh, so the session is alive and
            // this server is refusing the token for its own reasons — a wrong
            // `aud`, an issuer it does not trust, a scope the endpoint wants.
            //
            // Auth.clear() is deliberately NOT called here any more. It is
            // documented for "the one case where the provider has already told
            // *us* the session is gone", and the provider has just said the
            // opposite. Clearing also drops FriendsStore, ConvoysStore,
            // CirclesStore and FriendFog, so a server-side misconfiguration used
            // to cost the rider every cached account state as well as the login.
            //
            // The session-is-over path still clears, one level down:
            // Auth.refresh() calls clear() when the token endpoint rejects the
            // refresh token, and the AuthException it throws is not an
            // HttpStatusException, so it passes straight through this catch.
            val detail = errorMessage(e.body) ?: "HTTP ${e.code}"
            if (e.code != 401) throw IOException(detail)
            // Which of the two 401s this was, named so a rider can report it.
            // Reaching here at all means the session survived: the provider
            // either vouched for it (SERVER_REFUSED) or was never asked
            // (NO_SESSION). The third code, SESSION_ENDED, is raised by
            // Auth.refresh as an AuthException and never lands in this catch.
            val code = if (auth) AuthRefusal.SERVER_REFUSED else AuthRefusal.NO_SESSION
            throw IOException(AuthRefusal.message(detail, code))
        }
    }

    suspend fun requestJson(
        method: String,
        path: String,
        body: JsonObject? = null,
        auth: Boolean = true,
        readTimeoutMs: Long = 30_000,
    ): JsonObject = jsonObjectOf(request(method, path, body, auth, readTimeoutMs))

    /**
     * The API answers errors as RFC 9457 problem details. `detail` carries the
     * localized message a rider can act on ("username already taken"); `title`
     * is the generic one behind it. Either beats "HTTP 409".
     */
    private fun errorMessage(body: String): String? = try {
        val o = jsonObjectOf(body)
        o.optString("detail").ifBlank { o.optString("title") }.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
