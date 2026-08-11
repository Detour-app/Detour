package com.jellemax.detour.net

import android.content.Context
import android.util.Base64
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.Auth
import com.jellemax.detour.data.RelayPlaceEvent
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.placeEventFromRelayFrame
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
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

/** One `spin_offer` candidate, wire shape - see sync_server.py's protocol
 *  comment near `_valid_spin_offer`. [distanceM]/[durationS] are whatever
 *  the sharer's own spin already knew; a receiving member has no route of
 *  its own for these until it commits and [MapScreen]'s startNavigation
 *  fetches one. */
data class SpinCandidate(
    val lat: Double,
    val lon: Double,
    val distanceM: Double?,
    val durationS: Double?,
    val name: String?,
)

/**
 * A convoy's shared spin, either just sent by this device or just received
 * from a peer's.
 *
 * A **one-candidate** offer is not a sheet to vote on, it's the sharer
 * announcing the winner: every device that sees one commits it. That's the
 * whole reason [fromMe] exists — only the device that opened the round
 * decides when it's over and sends that closing offer, so a member whose
 * view of who's still live differs (a peer gone quiet for 20s is pruned
 * from [peers] on one phone and not another) can't resolve the same votes
 * into a different destination. Everyone commits off one frame instead of
 * each tallying their own answer.
 */
data class GroupSpin(val candidates: List<SpinCandidate>, val fromMe: Boolean)

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
 *
 * The relay is multi-group on the wire (docs/CIRCLES_AND_CONVOYS.md section 6):
 * every frame after `join` carries a `groupId`, and one socket can hold
 * several memberships at once. This client still stays single-*convoy* -
 * [send] stamps every outgoing frame with whichever convoy is currently
 * joined, not a set of groups, and a circle's `location`/`ptt_*`/`spin_*`
 * never rides this socket either (a circle posts fixes over plain HTTP at a
 * much lower cadence - see `CircleFixes.postFix`). What a circle *does* now
 * join this socket for is read-only: `place_event` notifications - see
 * [setNotifyCircles] below.
 *
 * Group spin (`spin_offer`/`spin_vote`) rides the same relay and is exactly
 * as ephemeral as everything else here: [spinOffer]/[spinVotes] are a
 * client-side tally of relayed votes, not server state, so every joined
 * device has to reach the same commit independently off the same frames -
 * see MapScreen's commit rule for how that's kept deterministic.
 *
 * Phase 2 (docs/CIRCLES_AND_CONVOYS.md section 6) breaks the single-convoy
 * rule above just enough for circles' arrival notifications: [setNotifyCircles]
 * joins this same socket to a set of circle groups, independent of whichever
 * convoy (if any) is active, so [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService]
 * can hold one socket open for both instead of running a second connection.
 * A circle's `location`/`ptt_*`/`spin_*` frames still never touch this
 * client either way - the server itself only relays those for a `convoy`
 * kind group (see handle_live_socket in sync_server.py) - so the only new
 * traffic a notify-circle join brings in is `place_event`.
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

    private val _activeConvoyId = MutableStateFlow<String?>(null)
    /** The convoy this device is currently trying to stay connected to, or
     *  null when not joined. UI (FriendsScreen) should derive its "am I
     *  live" state from this, not from its own local toggle state - a
     *  screen that's been left and come back must show what's actually
     *  running, not what a `remember{}` last thought it set. */
    val activeConvoyId: StateFlow<String?> = _activeConvoyId

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

    private val _spinOffer = MutableStateFlow<GroupSpin?>(null)
    /** The convoy's current group spin, if any candidate set is on the
     *  table - set locally the moment this device shares one (the relay
     *  never echoes a sender's own frame back to it, same as ptt_*), or
     *  when a peer's `spin_offer` arrives. Null once nobody has shared a
     *  spin yet, once the convoy is left, or across a disconnect - a stale
     *  vote is worse than no vote. */
    val spinOffer: StateFlow<GroupSpin?> = _spinOffer

    private val _spinVotes = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** username -> candidate index. Tallied client-side only, from every
     *  spin_vote this device has sent or received for the current
     *  [spinOffer] - the server holds none of this, so every device must
     *  arrive at the same tally from the same relayed votes to commit the
     *  same candidate (see MapScreen's commit rule). Reset whenever
     *  [spinOffer] changes. */
    val spinVotes: StateFlow<Map<String, Int>> = _spinVotes

    private val _audioChunks = MutableSharedFlow<IncomingAudioChunk>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioChunks: SharedFlow<IncomingAudioChunk> = _audioChunks

    /** Circles [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService]
     *  wants `place_event` notifications for, joined onto this socket
     *  alongside whatever convoy is active - see [setNotifyCircles]. There is
     *  no wire message to leave a single group (only closing the whole
     *  socket parts every membership at once), so turning a circle's
     *  notifications off just drops it from this set; the socket may stay
     *  joined to it server-side until the next reconnect, but nothing here
     *  acts on its frames once it's gone. */
    private val _notifyCircleIds = MutableStateFlow<Set<String>>(emptySet())

    private val _placeEvents = MutableSharedFlow<RelayPlaceEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Every `place_event` frame the socket receives for a joined group -
     *  in practice always one of [_notifyCircleIds], since a convoy never
     *  produces this frame. [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService]
     *  turns these into local notifications; this object only relays what
     *  the wire said. */
    val placeEvents: SharedFlow<RelayPlaceEvent> = _placeEvents

    /** Context passed to whichever of [join]/[setNotifyCircles] runs first.
     *  Nothing this class actually does with a Context needs a fresh one per
     *  call - [liveUrl] only reads Settings/BuildConfig - so the first real
     *  one is kept application-scoped rather than threading one through
     *  every internal reconnect ([leave] tearing down a convoy while a
     *  notify-circle join is still wanted, or vice versa, has to be able to
     *  restart the connection with nothing but what it already has). */
    @Volatile private var appContext: Context? = null

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
     *  again with a different id to switch convoys - a full teardown/reopen
     *  either way, so a peer of the old convoy sees a proper "left" rather
     *  than a member who just goes quiet. */
    fun join(context: Context, convoyId: String) {
        if (_activeConvoyId.value == convoyId && scope != null) return
        appContext = context.applicationContext
        if (liveUrl(context).isBlank()) {
            // Refuse to start the retry loop at all rather than spin it
            // forever against a server that was never configured - see
            // ConvoyLiveService's matching guard, which is what stops the
            // foreground service and GPS escalation from running pointlessly.
            _lastError.value = "No live server configured"
            return
        }
        // A different convoy's peers/talking/vote state must not bleed into
        // this one - cleared here rather than left for prunePeers' staleness
        // sweep, which would take up to STALE_PEER_MS to catch up.
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
        teardown()
        _activeConvoyId.value = convoyId
        // A previous attempt's failure must not be shown as this one's state
        // while it is still connecting - the UI reads this the moment a join
        // starts, before any reply has come back.
        _lastError.value = null
        startConnection()
    }

    /** Leaves the convoy - not necessarily the connection itself, which a
     *  notify-circle join (see [setNotifyCircles]) may still need alive. */
    fun leave() {
        _activeConvoyId.value = null
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
        teardown()
        if (_notifyCircleIds.value.isNotEmpty()) startConnection()
    }

    /** Circles to receive live `place_event` notifications for, joined onto
     *  this same socket - "one socket, many groups" per
     *  docs/CIRCLES_AND_CONVOYS.md section 6. Independent of [join]/[leave]:
     *  [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService]
     *  calls this whether or not a convoy is running, and the connection
     *  stays open for it even after a convoy is left, or starts for this
     *  alone when no convoy is active. */
    fun setNotifyCircles(context: Context, circleIds: Set<String>) {
        if (_notifyCircleIds.value == circleIds) return
        appContext = context.applicationContext
        _notifyCircleIds.value = circleIds
        teardown()
        if (circleIds.isNotEmpty() || _activeConvoyId.value != null) startConnection()
    }

    /** True while either a convoy or at least one notify-circle wants this
     *  socket open - what [runConnection]'s retry loop keeps running for. */
    private fun shouldStayConnected(): Boolean =
        _activeConvoyId.value != null || _notifyCircleIds.value.isNotEmpty()

    /** Tears down whatever connection is currently running, if any. Callers
     *  are responsible for deciding whether to [startConnection] again right
     *  after - this alone leaves nothing joined to anything. */
    private fun teardown() {
        scope?.cancel()
        scope = null
        socket?.close(1000, "leaving")
        socket = null
        _connected.value = false
    }

    /** Opens the connection [runConnection] then keeps alive/reconnects,
     *  using whichever [Context] was last handed to [join] or
     *  [setNotifyCircles] - see [appContext]'s doc for why a fresh one
     *  isn't needed here. No-ops (leaving nothing running) if neither has
     *  ever supplied one, or the live server isn't configured. */
    private fun startConnection() {
        val context = appContext ?: return
        if (liveUrl(context).isBlank()) {
            _lastError.value = "No live server configured"
            return
        }
        _lastError.value = null
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        newScope.launch { runConnection() }
        newScope.launch { forwardLocation() }
        newScope.launch { prunePeers() }
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

    /** Shares a spin with the convoy. Sets [spinOffer] on this device
     *  immediately - the relay excludes the sender from its own broadcast,
     *  same as every other frame here, so waiting for it to come back would
     *  just never happen. Silently does nothing outside 1-3 candidates,
     *  matching the server's own cap - the candidate sheet never produces
     *  any other count, so this is a guard against a future caller, not a
     *  path that should ever run today. */
    fun sendSpinOffer(candidates: List<SpinCandidate>) {
        if (candidates.isEmpty() || candidates.size > 3) return
        _spinOffer.value = GroupSpin(candidates, fromMe = true)
        _spinVotes.value = emptyMap()
        val arr = JSONArray()
        candidates.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("lat", c.lat)
                    put("lon", c.lon)
                    c.distanceM?.let { put("distanceM", it) }
                    c.durationS?.let { put("durationS", it) }
                    c.name?.let { put("name", it) }
                },
            )
        }
        send(JSONObject().put("type", "spin_offer").put("candidates", arr))
    }

    /** Casts this device's vote and records it in the local tally right
     *  away, for the same reason [sendSpinOffer] updates [spinOffer]
     *  locally - the relay won't echo it back to us. */
    fun sendSpinVote(index: Int) {
        val username = Settings.authUsername.value
        if (username.isNotBlank()) _spinVotes.value = _spinVotes.value + (username to index)
        send(JSONObject().put("type", "spin_vote").put("index", index))
    }

    /** Drops the current spin locally (a commit landed, or the sheet was
     *  cancelled) without telling anyone - there is nothing to tell, the
     *  vote was never server state to begin with. Other members' devices
     *  reach their own commit independently off the same relayed votes. */
    fun clearSpinOffer() {
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
    }

    /** Stamps [obj] with the currently joined convoy's id (every non-join
     *  frame needs one now, see the class doc) and sends it, if a convoy is
     *  actually joined. */
    private fun send(obj: JSONObject) {
        val groupId = _activeConvoyId.value ?: return
        socket?.send(obj.put("groupId", groupId).toString())
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

    /** Connect, and reconnect with backoff, for as long as [shouldStayConnected]
     *  holds - a convoy, a notify-circle join, or (the common case while a
     *  session runs) both. */
    private suspend fun runConnection() {
        var backoffMs = MIN_BACKOFF_MS
        while (shouldStayConnected()) {
            val everJoined = connectAndAwaitClose()
            if (!shouldStayConnected()) return
            _connected.value = false
            // A vote tallied against a socket that's no longer relaying
            // anyone's frames is just wrong by the time it reconnects -
            // drop it rather than let a stale offer sit on screen looking
            // live through a dead connection.
            _spinOffer.value = null
            _spinVotes.value = emptyMap()
            // A session that got in at all was probably fine - reset the
            // backoff rather than let one dropped connection ramp it up.
            backoffMs = if (everJoined) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    private fun sendJoin(webSocket: WebSocket, groupId: String) {
        webSocket.send(JSONObject().put("type", "join").put("groupId", groupId).toString())
    }

    /** Suspends until the socket closes; returns whether it ever received a
     *  "joined" reply, which decides the next retry's backoff. */
    private suspend fun connectAndAwaitClose(): Boolean {
        val context = appContext ?: return false
        val liveUrl = liveUrl(context)
        // The same access token every API request carries — one credential for the
        // rider, wherever it is presented. The relay is the one surface the
        // rewrite has not reached yet, so it is still the legacy server that has
        // to start accepting these.
        val token = try {
            Auth.bearer()
        } catch (e: Exception) {
            ""
        }
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
                // Every group this device currently wants live - the convoy
                // (if any) plus every notify-circle - joins fresh on each
                // (re)connect, since a `join` sent before the socket existed
                // obviously never reached the server.
                _activeConvoyId.value?.let { sendJoin(webSocket, it) }
                _notifyCircleIds.value.forEach { sendJoin(webSocket, it) }
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
                // The case the status line exists for. A relay that can't be
                // reached, or an Access/auth rejection that never gets as far
                // as a join reply, otherwise leaves the UI on "Connecting..."
                // for as long as the backoff loop keeps trying — which is
                // indistinguishable from a convoy nobody else has joined yet.
                // Only the first failure of an attempt speaks: anything after
                // the socket is already closing is noise about a teardown.
                if (closed.isCompleted) return
                _lastError.value = when {
                    response != null -> "Live server refused the connection (${response.code})"
                    else -> t.message?.takeIf { it.isNotBlank() }
                        ?.let { "Can't reach the live server: $it" }
                        ?: "Can't reach the live server"
                }
                closed.complete(everJoined)
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
            "place_event" -> {
                // Re-parsed with kotlinx rather than threaded through as a
                // second param: placeEventFromRelayFrame is shared with iOS
                // (docs/CIRCLES_AND_CONVOYS.md section 6 - one wording, one
                // parser, on both platforms) and takes a kotlinx JsonObject,
                // not the org.json one the rest of this class uses.
                val relay = try {
                    placeEventFromRelayFrame(Json.parseToJsonElement(text).jsonObject)
                } catch (e: Exception) {
                    null
                }
                if (relay != null) _placeEvents.tryEmit(relay)
            }
            "spin_offer" -> {
                val arr = msg.optJSONArray("candidates")
                val list = arr?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val o = a.optJSONObject(i) ?: return@mapNotNull null
                        val lat = o.optDouble("lat")
                        val lon = o.optDouble("lon")
                        if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                        SpinCandidate(
                            lat = lat,
                            lon = lon,
                            distanceM = o.optDouble("distanceM").takeIf { !it.isNaN() },
                            durationS = o.optDouble("durationS").takeIf { !it.isNaN() },
                            name = o.optString("name").takeIf { it.isNotBlank() },
                        )
                    }
                }
                // A new offer starts a fresh vote, even mid-round - the
                // candidates it names are a different sheet than whatever
                // was being voted on before.
                if (!list.isNullOrEmpty()) {
                    _spinOffer.value = GroupSpin(list, fromMe = false)
                    _spinVotes.value = emptyMap()
                }
            }
            "spin_vote" -> {
                val user = msg.optString("user")
                val index = msg.optInt("index", -1)
                if (user.isNotBlank() && index in 0..2) {
                    _spinVotes.value = _spinVotes.value + (user to index)
                }
            }
        }
        return false
    }
}
