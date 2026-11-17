package com.jellemax.detour.data

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
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
import io.ktor.utils.io.errors.IOException as KtorIOException
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

/**
 * A non-2xx response, carrying the body so callers can dig an error out.
 *
 * [message] lets a caller that rewords the error keep the status: rethrowing a
 * plain `IOException` instead left `failureText` reading every server refusal
 * as a connection problem (#461).
 *
 * [detail] is the API's own problem-details sentence ("No rider goes by X"),
 * set only by [Api], whose `detail` is written for the rider. Other servers'
 * bodies stay out of it: GraphHopper's or nginx's text is not.
 */
class HttpStatusException(
    val code: Int,
    val body: String,
    message: String = "HTTP $code",
    val detail: String? = null,
) : IOException(message)

/**
 * What the rider reads when the request never got an answer.
 *
 * The engine's own text ("Unable to resolve host "photon.komoot.io": No
 * address associated with hostname") reached search, spin, routing and
 * navigation errors verbatim, because every caller shows `e.message`. Mapping
 * it here, where every request passes, fixes all of them at once; the original
 * stays as [cause] for logs. Still an [IOException], so every `catch` that
 * falls back to the next server behaves as before.
 *
 * A DNS failure cannot tell "offline" from "wrong address", so both get the
 * one sentence that is true for either.
 */
internal fun networkFailure(e: Throwable): NetworkFailureException = NetworkFailureException(
    timedOut = e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is SocketTimeoutException,
    cause = e,
)

/**
 * [networkFailure]'s result, typed so [failureReason] can tell "never got an
 * answer" from any other [IOException] and [failureSentence] can keep its
 * already-worded [message]. Still an [IOException]: every `catch` that falls
 * back to the next server keeps catching it.
 */
class NetworkFailureException(val timedOut: Boolean, cause: Throwable) : IOException(
    if (timedOut) "The server took too long to answer — try again"
    else "Can't reach the server — check your connection",
    cause,
)

/**
 * A `401` that survived a good token refresh, or came with no session at all
 * ([Api.request]). [message] is the server's own explanation followed by an
 * `AUTH-401-*` identifier ([AuthRefusal]), which riders quote in bug reports,
 * so [failureSentence] passes it through unchanged.
 */
class TokenRefusedException(serverMessage: String, val code: String) :
    IOException(AuthRefusal.message(serverMessage, code))

/** [Api] was asked for something with no sync server set up. */
class NoServerException : IOException("No server configured")

/**
 * Why a request failed, as the second half of a rider-facing sentence —
 * `failureText` puts "Sync failed: " in front of it.
 *
 * Every branch is a thing the rider can act on; nothing carries the
 * exception's own text, which is also why this is worth a test — the mapping
 * is the whole feature. Lives in data/ rather than presentation/ because
 * [LoopSpin] words its own warning with it (#487), and data/ may not read
 * presentation/ (architecture.md §3).
 */
fun failureReason(e: Throwable): String = when {
    // Before HttpStatusException: AuthException is one too, and it is the
    // only status the rider fixes by signing in rather than by retrying.
    e is AuthException -> "the sign-in has expired. Sign in again."
    e is NoRoutingServerException -> "no routing server is set up. Add one in Settings → Servers & sync."
    e is NoRouteException -> "no road connects those points. Try moving them."
    // A 4xx the API explained says what to fix ("No rider goes by X") better
    // than any status reading; a 5xx detail may be internals, so it doesn't.
    e is HttpStatusException -> statusReason(e)
    e is NetworkFailureException ->
        if (e.timedOut) "the server took too long to answer. Try again."
        else "the server could not be reached. Check your connection and try again."
    // The AUTH-401-* code is kept: it is what a rider quotes in a report.
    e is TokenRefusedException -> "the server refused the sign-in [${e.code}]."
    e is NoServerException -> "no server is set up. Add one in Settings → Servers & sync."
    e is IOException -> "it could not be opened. Check your connection and try again."
    else -> "the contents were not what this app expected."
}

private fun statusReason(e: HttpStatusException): String = when {
    e.code in 400..499 && e.detail != null -> e.detail
    e.code in 401..403 -> "the server refused the sign-in."
    e.code == 404 -> "the server has nothing at that address."
    e.code >= 500 -> "the server hit a problem. Try again later."
    else -> "the server answered ${e.code}."
}

/**
 * A whole rider-facing sentence for a failed call, for a store's error field —
 * what used to be `e.message ?: FALLBACK_ERROR`, which handed the rider a
 * parser's "Unexpected JSON token at offset 41" or a bare "HTTP 502".
 *
 * The failures that already arrive worded for the rider keep their own text:
 * [networkFailure]'s two sentences, and both refusal paths whose messages
 * carry an `AUTH-401-*` identifier ([TokenRefusedException], and
 * [AuthException], whose every message is a written sentence and whose
 * `SESSION_ENDED` one carries the code). Those identifiers are an append-only
 * contract with riders (`AuthRetry.kt`), so they must survive. Everything
 * else is [failureReason] as a sentence. Not [failureText]: that lives in
 * presentation/, which data/ may not read (architecture.md §3).
 */
fun failureSentence(e: Throwable): String {
    val own = e.message?.takeIf { it.isNotBlank() }
    if (own != null && (e is NetworkFailureException || e is TokenRefusedException || e is AuthException)) {
        return own
    }
    return failureReason(e).replaceFirstChar { it.uppercaseChar() }
}

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
        // The body read is inside too: a connection can drop mid-body.
        val (status, text) = asNetworkFailure {
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
            response.status.value to response.bodyAsText()
        }
        if (status !in 200..299) {
            throw HttpStatusException(status, text)
        }
        return text
    }

    /** Rethrows [block]'s transport errors as [networkFailure]. */
    private inline fun <T> asNetworkFailure(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw networkFailure(e)
    } catch (e: KtorIOException) {
        // The same class as okio's on JVM, a different one on Kotlin/Native:
        // the Darwin engine's transport errors and ktor's timeouts extend
        // this one, so without it iOS shows raw NSError text and every
        // next-server fallback catching okio's IOException never fires (#446).
        throw networkFailure(e)
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
