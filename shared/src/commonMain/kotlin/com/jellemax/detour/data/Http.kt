package com.jellemax.detour.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.ContentType
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.IOException
import okio.buffer

/**
 * The one HTTP client the shared core uses, replacing HttpURLConnection.
 *
 * Ktor picks its engine off the classpath: OkHttp on Android, NSURLSession on
 * iOS. Both honour the system proxy and the platform trust store.
 *
 * Everything here is suspending. HttpURLConnection was blocking and every
 * caller already wrapped it in a background dispatcher; those wrappers become
 * plain suspend calls, which is the only structural change the port forces on
 * the call sites.
 */

/** A non-2xx response, carrying the body so callers can dig an error out. */
class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")

internal object Http {

    /** What an OAuth token endpoint takes. Everything else here posts JSON. */
    const val FORM_URLENCODED = "application/x-www-form-urlencoded"

    private val client = HttpClient {
        // Ktor throws on non-2xx only when asked to; we want the body first.
        expectSuccess = false
        // Transparent gzip on responses, matching the old
        // "Accept-Encoding: gzip" + GZIPInputStream pair.
        install(ContentEncoding) { gzip() }
        install(HttpTimeout) { connectTimeoutMillis = 5_000 }
    }

    /**
     * [gzipBody] compresses the request body, for the sync upload that resends
     * the whole trip/trace history each time. The paired server always
     * decompresses Content-Encoding: gzip, so this stays a caller's choice
     * rather than something negotiated.
     */
    suspend fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        // Bounds both the whole call (requestTimeoutMillis) and the gap between
        // bytes (socketTimeoutMillis) — without it the engine keeps its own ~10s
        // read default. Applied per-request, not on the client, so /sync (a
        // multi-MB upload the self-hosted server answers only after a long silent
        // decompress-and-merge) can ask for 120s while every small social call
        // keeps the 30s default.
        readTimeoutMs: Long = 30_000,
        gzipBody: Boolean = false,
        contentType: String = ContentType.Application.Json.toString(),
    ): String {
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            headers.forEach { (k, v) -> header(k, v) }
            timeout {
                requestTimeoutMillis = readTimeoutMs
                socketTimeoutMillis = readTimeoutMs
            }
            if (body != null) {
                contentType(ContentType.parse(contentType))
                if (gzipBody) {
                    header("Content-Encoding", "gzip")
                    setBody(gzip(body))
                } else {
                    setBody(body)
                }
            }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw HttpStatusException(response.status.value, text)
        }
        return text
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Long = 30_000,
    ): String = request("GET", url, null, headers, readTimeoutMs)

    /** okio rather than java.util.zip: this has to run on Kotlin/Native too. */
    private fun gzip(text: String): ByteArray {
        val out = Buffer()
        val sink: BufferedSink = GzipSink(out).buffer()
        sink.writeUtf8(text)
        sink.close() // flushes the gzip trailer; without it the body is truncated
        return out.readByteArray()
    }
}
