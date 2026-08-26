package com.jellemax.detour.net

import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.drive.RelaySocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.concurrent.Volatile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [RelaySocket] over OkHttp's `WebSocket` - the Android half of the seam
 * [com.jellemax.detour.drive.ConvoyRelay] runs against. One instance is
 * constructed once, by [ConvoyLiveClient], and reused for every
 * [ConvoyLiveClient.join]/[ConvoyLiveClient.setNotifyCircles]-triggered
 * `run()` for the app's process lifetime - the "one socket, many groups"
 * invariant lives one level up, in [ConvoyLiveClient]; this class only has
 * to guarantee it is safe to [connect] again after a previous attempt ended,
 * which [RelaySocket]'s own doc already requires.
 *
 * [connect] resolves the live-relay URL and Cloudflare Access headers fresh
 * on every call rather than once at construction - the same "read Settings
 * per attempt, not per client" shape the old `ConvoyLiveClient.kt`'s
 * `connectAndAwaitClose` used, so a routing-server change picked up
 * mid-session takes effect on the very next reconnect instead of needing an
 * app restart.
 */
class OkHttpRelaySocket : RelaySocket {

    companion object {
        /** Effective live-relay URL: baked default (its own hostname) →
         *  derived from the shared server URL (Settings) — same host,
         *  ws(s):// scheme, /api/live path. The relay is an ordinary
         *  endpoint of the API now rather than a second listener on its own
         *  port, so it sits under the same /api prefix and behind the same
         *  bearer auth as every other call.
         *
         *  Also read by [ConvoyLiveClient] and
         *  [ConvoyLiveService][com.jellemax.detour.convoy.ConvoyLiveService]
         *  for their own "refuse to start at all" guards - [RelaySocket]
         *  itself carries no such refusal (URL resolution moved down here,
         *  out of the shared relay), so both live entirely on the caller's
         *  side of this seam. */
        fun liveUrl(): String {
            BuildConfig.LIVE_URL.takeIf { it.isNotBlank() }?.let { return it }
            val base = RoutingServer.loadCustom()?.url?.trimEnd('/') ?: return ""
            return when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/api/live"
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/api/live"
                else -> ""
            }
        }
    }

    private val client = OkHttpClient.Builder()
        // Keeps NAT / the Cloudflare tunnel from idling the connection closed
        // during a quiet stretch with no location updates or PTT.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** One connect attempt's live wiring, replaced wholesale by every
     *  [connect] - so a frame or a close arriving for a *previous*, already-
     *  superseded attempt can never be mistaken for this one's. */
    private class Session {
        /**
         * Set by [close] so a [connect] still in flight does not hand back a
         * live socket nobody wants.
         *
         * `close()` can land between `session = s` and the `newWebSocket` call
         * below, where there is no socket yet to close - and
         * [com.jellemax.detour.drive.ConvoyRelay] calls it from whichever
         * thread ran `stop()` or a membership change, so that window is
         * reachable. Without this, that `close()` shut only the frame channel
         * and the socket opening a moment later survived it. The relay's own
         * retry structure happens to close it again immediately, but a socket
         * should honour its own contract rather than lean on its caller's.
         */
        @Volatile
        var closed = false

        /** Completed once (successfully or not) by the first of [onOpen]/
         *  [onClosed]/[onFailure] to fire - what [connect] suspends on to
         *  know the upgrade itself succeeded, before ever returning control
         *  to [com.jellemax.detour.drive.ConvoyRelay]'s own receive loop. */
        val opened = CompletableDeferred<Unit>()

        /** Unlimited rather than bounded: frames arrive on OkHttp's own
         *  reader thread via [onMessage], which cannot suspend to wait for
         *  room - a bounded channel would have to silently drop a frame
         *  instead, and a dropped `positions`/`spin_vote` frame is exactly
         *  the kind of thing that must not go missing. */
        val frames = Channel<String>(Channel.UNLIMITED)

        @Volatile var webSocket: WebSocket? = null
    }

    @Volatile private var session: Session? = null

    override suspend fun connect(bearer: String) {
        val url = liveUrl()
        if (url.isBlank()) throw IOException("No live server configured")

        // The same access token every API request carries — one credential
        // for the rider, wherever it is presented.
        val cf = RoutingServer.load()
        val requestBuilder = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $bearer")
        if (cf.clientId.isNotBlank()) {
            requestBuilder
                .addHeader("CF-Access-Client-Id", cf.clientId)
                .addHeader("CF-Access-Client-Secret", cf.clientSecret)
        }

        val s = Session()
        session = s
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                s.opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                s.frames.trySend(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                s.frames.close()
                if (!s.opened.isCompleted) {
                    s.opened.completeExceptionally(IOException("Closed before opening"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Puts the HTTP status on a refused upgrade into the message
                // itself - ConvoyRelay's own generic "can't reach" wording
                // otherwise makes a 401 unreadable, exactly why the old
                // client's wording here was "Live server refused the
                // connection (${response.code})".
                // The message goes to the rider verbatim (see RelaySocket.connect),
                // so both cases get worded here rather than upstream: a refusal
                // names its status code, and a transport failure says what it is
                // instead of surfacing OkHttp's own "failed to connect to /..".
                val err = if (response != null) {
                    IOException("Live server refused the connection (${response.code})")
                } else {
                    IOException("Can't reach the live server", t)
                }
                if (!s.opened.isCompleted) s.opened.completeExceptionally(err)
                s.frames.close(err)
            }
        }

        s.webSocket = client.newWebSocket(requestBuilder.build(), listener)
        // A close() during the upgrade set the flag but had no socket to act
        // on; honour it now rather than returning a live connection.
        if (s.closed) {
            s.webSocket?.close(1000, "leaving")
            s.frames.close()
        }
        s.opened.await()
    }

    override suspend fun receive(): String? {
        val frames = session?.frames ?: return null
        return try {
            frames.receive()
        } catch (e: ClosedReceiveChannelException) {
            // Graceful close (ours or the far end's) - see this function's
            // own contract on RelaySocket for why this is null, not a throw.
            null
        }
    }

    override fun send(text: String) {
        session?.webSocket?.send(text)
    }

    override fun close() {
        val s = session ?: return
        s.closed = true
        s.webSocket?.close(1000, "leaving")
        s.frames.close()
    }
}
