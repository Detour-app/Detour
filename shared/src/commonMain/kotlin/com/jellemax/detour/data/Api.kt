package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import okio.IOException

/** The bearer token was rejected: the session is gone, not merely offline. */
class AuthException(message: String) : IOException(message)

/**
 * One place that knows how to talk to the sync server.
 *
 * Two layers of credentials, doing different jobs: the Cloudflare Access
 * service token gets us to the hostname at all (it is shared by everyone who
 * has the app), while the bearer token says *which user* we are. Only the
 * second one decides whose trips come back.
 */
internal object Api {

    /** Returns the raw response body. */
    suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        auth: Boolean = true,
    ): String {
        val base = SyncClient.url() ?: throw IOException("No sync server configured")
        val token = Settings.authToken.value
        if (auth && token.isBlank()) throw AuthException("Sign in to sync")

        val cf = RoutingServer.load()
        val headers = buildMap {
            put("User-Agent", "Detour/${BuildDefaults.versionName}")
            if (auth) put("Authorization", "Bearer $token")
            if (cf.clientId.isNotBlank()) {
                put("CF-Access-Client-Id", cf.clientId)
                put("CF-Access-Client-Secret", cf.clientSecret)
            }
        }
        return try {
            Http.request(
                method = method,
                url = base.trimEnd('/') + path,
                body = body?.string(),
                headers = headers,
                // A sync upload re-sends the whole trip/trace history every time
                // (traces.jsonl alone is 1MB+ after a year of riding), and JSON
                // compresses roughly 10:1. The paired server always decompresses
                // Content-Encoding: gzip — there is no third-party server this
                // needs to stay compatible with, so this is unconditional rather
                // than negotiated.
                gzipBody = true,
            )
        } catch (e: HttpStatusException) {
            val message = errorMessage(e.body) ?: "HTTP ${e.code}"
            throw if (e.code == 401) AuthException(message) else IOException(message)
        }
    }

    suspend fun requestJson(
        method: String,
        path: String,
        body: JsonObject? = null,
        auth: Boolean = true,
    ): JsonObject = jsonObjectOf(request(method, path, body, auth))

    /** The server answers errors as `{"error": "..."}`; surface that verbatim
     *  so "username already taken" reaches the user instead of "HTTP 409". */
    private fun errorMessage(body: String): String? = try {
        jsonObjectOf(body).optString("error").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
