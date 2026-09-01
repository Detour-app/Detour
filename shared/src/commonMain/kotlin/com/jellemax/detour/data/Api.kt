package com.jellemax.detour.data

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
        val bearer = if (auth) Auth.bearer() else null

        val cf = RoutingServer.load()
        val headers = buildMap {
            put("User-Agent", "Detour/${BuildDefaults.versionName}")
            if (bearer != null) put("Authorization", "Bearer $bearer")
            if (cf.clientId.isNotBlank()) {
                put("CF-Access-Client-Id", cf.clientId)
                put("CF-Access-Client-Secret", cf.clientSecret)
            }
        }
        return try {
            Http.request(
                method = method,
                url = base.trimEnd('/') + PREFIX + path,
                body = body?.string(),
                headers = headers,
                // A sync upload re-sends the whole trip/trace history every time
                // (traces.jsonl alone is 1MB+ after a year of riding), and JSON
                // compresses roughly 10:1. The server decompresses
                // Content-Encoding: gzip with a bound on the decompressed size,
                // so this is unconditional rather than negotiated.
                gzipBody = body != null,
                readTimeoutMs = readTimeoutMs,
            )
        } catch (e: HttpStatusException) {
            val message = errorMessage(e.body) ?: "HTTP ${e.code}"
            if (e.code != 401) throw IOException(message)

            // The token was refreshed before this request left, so a 401 here is
            // not a stale access token — the session itself is no longer
            // accepted (revoked, or minted for a realm this server does not
            // trust). Keeping it would fail the same way on every later request.
            Auth.clear()
            throw AuthException(message)
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
