package com.jellemax.maproulette.data

import android.content.Context
import com.jellemax.maproulette.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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

    /** Blocking; call off the main thread. Returns the raw response body. */
    fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
    ): String {
        val base = SyncClient.url() ?: throw IOException("No sync server configured")
        val token = Settings.authToken.value
        if (auth && token.isBlank()) throw AuthException("Sign in to sync")

        val cf = RoutingServer.load(context)
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "MapRoulette/${BuildConfig.VERSION_NAME}")
            if (auth) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cf.clientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", cf.clientId)
                conn.setRequestProperty("CF-Access-Client-Secret", cf.clientSecret)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // A sync upload re-sends the whole trip/trace history every time
                // (traces.jsonl alone is 1MB+ after a year of riding), and JSON
                // compresses roughly 10:1. The paired server always decompresses
                // Content-Encoding: gzip — there is no third-party server this
                // needs to stay compatible with, so this is unconditional rather
                // than negotiated.
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.outputStream.use { out ->
                    GZIPOutputStream(out).use { gz -> gz.write(body.toString().toByteArray()) }
                }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val message = errorMessage(conn) ?: "HTTP $code"
                throw if (code == 401) AuthException(message) else IOException(message)
            }
            return decode(conn.inputStream, conn.contentEncoding)
        } finally {
            conn.disconnect()
        }
    }

    fun requestJson(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
    ): JSONObject = JSONObject(request(context, method, path, body, auth))

    /** The server answers errors as `{"error": "..."}`; surface that verbatim
     *  so "username already taken" reaches the user instead of "HTTP 409". */
    private fun errorMessage(conn: HttpURLConnection): String? = try {
        val stream = conn.errorStream ?: return null
        JSONObject(decode(stream, conn.contentEncoding)).optString("error").takeIf {
            it.isNotBlank()
        }
    } catch (e: Exception) {
        null
    }

    private fun decode(stream: java.io.InputStream, encoding: String?): String {
        val decoded = if (encoding == "gzip") GZIPInputStream(stream) else stream
        return decoded.bufferedReader().readText()
    }
}
