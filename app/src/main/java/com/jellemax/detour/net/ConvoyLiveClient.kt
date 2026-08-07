package com.jellemax.detour.net

import android.content.Context
import android.util.Base64
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FriendPosition(
    val username: String,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double?,
    val speedKmh: Double?,
    val tsMs: Long,
)

data class IncomingAudioChunk(val username: String, val pcm: ByteArray)

/**
 * Owns the convoy live-location/push-to-talk WebSocket. A singleton (same
 * shape as [TripTrackingService]'s companion state) so the map screen, the
 * friends list, and the foreground service that keeps this connection alive
 * can all observe it without binding to each other.
 *
 * Location updates ride on [TripTrackingService.lastFix] rather than opening
 * a second GPS listener - see the convoy-active mode escalation there. Only
 * the currently joined convoy's peers ever appear in [peers]; nothing here
 * is persisted, matching the server's in-memory-only relay.
 */
object ConvoyLiveClient {

    private const val LOCATION_SEND_INTERVAL_MS = 2_000L
    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val PEER_PRUNE_INTERVAL_MS = 5_000L
    /** ~10 missed 2s location updates - generous enough that a normal gap in
     *  GPS fixes doesn't flicker a peer's marker, but a peer who actually
     *  dropped off (backgrounded, lost signal, crashed) stops being shown as
     *  live rather than sitting frozen on the map forever. */
    private const val STALE_PEER_MS = 20_000L

    private val client = OkHttpClient.Builder()
        // Keeps NAT / the Cloudflare tunnel from idling the connection closed
        // during a quiet stretch with no location updates or PTT.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastLocationSentMs = 0L

    private val _activeConvoyId = MutableStateFlow<Int?>(null)
    /** The convoy this device is currently trying to stay connected to, or
     *  null when not joined. UI (FriendsScreen) should derive its "am I
     *  live" state from this, not from its own local toggle state - a
     *  screen that's been left and come back must show what's actually
     *  running, not what a `remember{}` last thought it set. */
    val activeConvoyId: StateFlow<Int?> = _activeConvoyId

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _lastError = MutableStateFlow<String?>(null)
    /** Set when the relay can't be reached at all (misconfigured server, not
     *  signed in) or rejects a join (not a member). Cleared on a successful
     *  join. Exists so a permanently-failing connection surfaces something
     *  instead of retrying silently forever. */
    val lastError: StateFlow<String?> = _lastError

    private val _peers = MutableStateFlow<Map<String, FriendPosition>>(emptyMap())
    val peers: StateFlow<Map<String, FriendPosition>> = _peers

    private val _talking = MutableStateFlow<Set<String>>(emptySet())
    val talking: StateFlow<Set<String>> = _talking

    private val _audioChunks = MutableSharedFlow<IncomingAudioChunk>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioChunks: SharedFlow<IncomingAudioChunk> = _audioChunks

    /** Effective live-relay URL: baked default (its own hostname) → derived
     *  from the shared server URL (Settings) — same host, ws(s):// scheme,
     *  /live path, matching the ingress rule from server/INSTALL.md. */
    fun liveUrl(context: Context): String {
        BuildConfig.LIVE_URL.takeIf { it.isNotBlank() }?.let { return it }
        val base = RoutingServer.loadCustom()?.url?.trimEnd('/') ?: return ""
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/live"
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/live"
            else -> ""
        }
    }

    /** Join [convoyId]'s live relay; forwards [TripTrackingService.lastFix]
     *  as throttled location updates until [leave] is called. Safe to call
     *  again with a different id to switch convoys. */
    fun join(context: Context, convoyId: Int) {
        if (_activeConvoyId.value == convoyId && scope != null) return
        leave()
        if (liveUrl(context).isBlank()) {
            // Refuse to start the retry loop at all rather than spin it
            // forever against a server that was never configured - see
            // ConvoyLiveService's matching guard, which is what stops the
            // foreground service and GPS escalation from running pointlessly.
            _lastError.value = "No live server configured"
            return
        }
        _activeConvoyId.value = convoyId
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        newScope.launch { runConnection(context, convoyId) }
        newScope.launch { forwardLocation() }
        newScope.launch { prunePeers() }
    }

    fun leave() {
        _activeConvoyId.value = null
        scope?.cancel()
        scope = null
        socket?.close(1000, "leaving")
        socket = null
        _connected.value = false
        _peers.value = emptyMap()
        _talking.value = emptySet()
    }

    fun sendPttStart() = send(JSONObject().put("type", "ptt_start"))
    fun sendPttEnd() = send(JSONObject().put("type", "ptt_end"))

    fun sendAudioChunk(pcm: ByteArray) {
        send(
            JSONObject()
                .put("type", "ptt_audio")
                .put("chunk", Base64.encodeToString(pcm, Base64.NO_WRAP)),
        )
    }

    private fun send(obj: JSONObject) {
        socket?.send(obj.toString())
    }

    private suspend fun forwardLocation() {
        TripTrackingService.lastFix.collect { fix ->
            if (fix == null) return@collect
            val now = System.currentTimeMillis()
            if (now - lastLocationSentMs < LOCATION_SEND_INTERVAL_MS) return@collect
            lastLocationSentMs = now
            send(
                JSONObject()
                    .put("type", "location")
                    .put("lat", fix.lat)
                    .put("lon", fix.lon)
                    .apply { fix.bearingDeg?.let { put("headingDeg", it.toDouble()) } }
                    .put("speedKmh", fix.speedMps * 3.6)
                    .put("ts", fix.timeMs),
            )
        }
    }

    private suspend fun prunePeers() {
        while (true) {
            delay(PEER_PRUNE_INTERVAL_MS)
            val cutoff = System.currentTimeMillis() - STALE_PEER_MS
            _peers.value = _peers.value.filterValues { it.tsMs >= cutoff }
        }
    }

    /** Connect, and reconnect with backoff, until [leave] resets
     *  [activeConvoyId] away from [convoyId]. */
    private suspend fun runConnection(context: Context, convoyId: Int) {
        var backoffMs = MIN_BACKOFF_MS
        while (_activeConvoyId.value == convoyId) {
            val everJoined = connectAndAwaitClose(context, convoyId)
            if (_activeConvoyId.value != convoyId) return
            _connected.value = false
            // A session that got in at all was probably fine - reset the
            // backoff rather than let one dropped connection ramp it up.
            backoffMs = if (everJoined) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    /** Suspends until the socket closes; returns whether it ever received a
     *  "joined" reply, which decides the next retry's backoff. */
    private suspend fun connectAndAwaitClose(context: Context, convoyId: Int): Boolean {
        val liveUrl = liveUrl(context)
        val token = Settings.authToken.value
        if (liveUrl.isBlank() || token.isBlank()) {
            _lastError.value = if (liveUrl.isBlank()) "No live server configured" else "Not signed in"
            return false
        }
        val cf = RoutingServer.load()

        val closed = CompletableDeferred<Boolean>()
        var everJoined = false

        val requestBuilder = Request.Builder().url(liveUrl)
            .addHeader("Authorization", "Bearer $token")
        if (cf.clientId.isNotBlank()) {
            requestBuilder
                .addHeader("CF-Access-Client-Id", cf.clientId)
                .addHeader("CF-Access-Client-Secret", cf.clientSecret)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject().put("type", "join").put("convoyId", convoyId).toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = try {
                    JSONObject(text).optString("type")
                } catch (e: JSONException) {
                    return
                }
                if (type == "error") {
                    // The server rejects a join it doesn't close the socket
                    // for (e.g. membership was removed) - close it ourselves
                    // so this attempt ends and the backoff loop picks it up
                    // rather than sitting connected-but-never-joined forever.
                    _lastError.value = try {
                        JSONObject(text).optString("message").ifBlank { null }
                    } catch (e: JSONException) {
                        null
                    }
                    webSocket.close(1000, "join rejected")
                    return
                }
                if (handleMessage(text)) everJoined = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(everJoined)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.isCompleted) closed.complete(everJoined)
            }
        }

        val ws = client.newWebSocket(requestBuilder.build(), listener)
        socket = ws
        return try {
            closed.await()
        } finally {
            // Only clear if this is still the current socket - a newer
            // connection (rapid leave/rejoin) may already have replaced it,
            // and this stale cleanup must not null that one out from under it.
            if (socket === ws) socket = null
        }
    }

    /** Applies an incoming relay message; returns true only for "joined", the
     *  one message [connectAndAwaitClose] needs to see to know auth worked. */
    private fun handleMessage(text: String): Boolean {
        val msg = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return false
        }
        when (msg.optString("type")) {
            "joined" -> {
                _connected.value = true
                _lastError.value = null
                return true
            }
            "location" -> {
                val username = msg.optString("user")
                if (username.isNotBlank()) {
                    _peers.value = _peers.value + (
                        username to FriendPosition(
                            username = username,
                            lat = msg.optDouble("lat"),
                            lon = msg.optDouble("lon"),
                            headingDeg = msg.optDouble("headingDeg").takeIf { !it.isNaN() },
                            speedKmh = msg.optDouble("speedKmh").takeIf { !it.isNaN() },
                            tsMs = msg.optLong("ts"),
                        )
                        )
                }
            }
            "ptt_start" -> msg.optString("user").takeIf { it.isNotBlank() }?.let { user ->
                _talking.value = _talking.value + user
            }
            "ptt_end" -> msg.optString("user").takeIf { it.isNotBlank() }?.let { user ->
                _talking.value = _talking.value - user
            }
            "ptt_audio" -> {
                val user = msg.optString("user")
                val chunk = msg.optString("chunk")
                if (user.isNotBlank() && chunk.isNotBlank()) {
                    // The server caps/validates chunk length but not that it's
                    // valid base64; a malformed chunk must not crash the
                    // OkHttp callback thread over one bad audio frame.
                    val pcm = try {
                        Base64.decode(chunk, Base64.NO_WRAP)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (pcm != null) _audioChunks.tryEmit(IncomingAudioChunk(user, pcm))
                }
            }
            "left" -> msg.optString("user").takeIf { it.isNotBlank() }?.let { user ->
                _peers.value = _peers.value - user
                _talking.value = _talking.value - user
            }
        }
        return false
    }
}
