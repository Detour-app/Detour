package com.jellemax.detour.net

import com.jellemax.detour.data.Auth
import com.jellemax.detour.data.Features
import com.jellemax.detour.data.RelayPlaceEvent
import com.jellemax.detour.data.Settings
import com.jellemax.detour.drive.BearerSource
import com.jellemax.detour.drive.ConvoyRelay
import com.jellemax.detour.drive.FriendPosition
import com.jellemax.detour.drive.GroupSpin
import com.jellemax.detour.drive.IncomingAudioChunk
import com.jellemax.detour.drive.SpinCandidate
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The single app-wide [ConvoyRelay] instance - see its class doc's "exactly
 * one instance app-wide, and exactly one live run() at a time" paragraph -
 * plus the Android glue [ConvoyRelay] deliberately does not provide:
 * [Features.liveRelay]/"no live server configured" guards, launching its
 * `run()` loop, and the stable names
 * [FriendsScreen][com.jellemax.detour.ui.FriendsScreen],
 * [MapScreen][com.jellemax.detour.ui.MapScreen],
 * [CarMapRenderer][com.jellemax.detour.car.CarMapRenderer],
 * [PushToTalk][com.jellemax.detour.audio.PushToTalk] and
 * [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService] read
 * or send through, none of which should reach into [ConvoyRelay] directly.
 *
 * **Why the single instance lives here** rather than inside
 * [ConvoyLiveService][com.jellemax.detour.convoy.ConvoyLiveService], which
 * is the more obvious "owns a scope, launches run()" home: because
 * [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService] has
 * to be able to open this same socket for circle notifications with no
 * convoy joined and [ConvoyLiveService] never started at all - precisely the
 * scenario [ConvoyRelay]'s own class doc calls out ("CircleNotifyService on
 * Android holds this socket open purely for circles"). [ConvoyRelay]
 * enforces only "one live `run()` at a time" on itself, not "one caller" -
 * see its class doc - so if each service launched `run()` independently on
 * its own scope, the second one to do so while the first was already
 * running would crash on `run()`'s own `check(!running)`. This object,
 * already imported by both services, is the one place [ensureRunning]
 * coordinates that instead.
 *
 * The two guards [ConvoyRelay] states as the caller's job (see its class
 * doc) live here too: [Features.liveRelay] and "no live server configured"
 * (via [liveUrl]) are both checked in [join] and [setNotifyCircles], before
 * [ensureRunning] ever runs - refusing to start the retry loop at all,
 * rather than letting [ConvoyRelay.run] spin forever against nothing, is
 * what [ConvoyLiveService] also refuses to start its foreground service (and
 * GPS escalation) over.
 */
object ConvoyLiveClient {

    /** The one [ConvoyRelay] every convoy/circle caller shares - see the
     *  class doc. */
    val relay = ConvoyRelay()

    /** The one [OkHttpRelaySocket] [relay] runs against, for the same "one
     *  socket, many groups" reason there is only one [relay] - two sockets
     *  would mean two location broadcasts, per [ConvoyRelay]'s class doc. */
    private val socket = OkHttpRelaySocket()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The currently in-flight [ConvoyRelay.run] job, if any - what
     *  [ensureRunning] checks before deciding a fresh one is needed.
     *  [ConvoyRelay.run] itself returns once nothing is wanted any more (see
     *  its own doc), so finding this idle is the ordinary case between
     *  sessions, not a failure. */
    @Volatile private var runJob: Job? = null

    /** Serializes [ensureRunning] - [join] and [setNotifyCircles] can each
     *  be the first caller to want this socket open, from different
     *  threads ([ConvoyLiveService.onStartCommand] runs on the main thread;
     *  [com.jellemax.detour.notif.CircleNotifyService]'s own refresh runs on
     *  `Dispatchers.IO`) - and an unguarded check-then-launch race between
     *  them could let both call [ConvoyRelay.run], which throws on the
     *  second concurrent call. */
    private val runLock = Any()



    /** The convoy this device is currently trying to stay connected to, or
     *  null when not joined. UI (FriendsScreen) should derive its "am I
     *  live" state from this, not from its own local toggle state - a
     *  screen that's been left and come back must show what's actually
     *  running, not what a `remember{}` last thought it set.
     *
     *  [ConvoyRelay] tracks the same thing internally but [ConvoyRelay.setConvoy]
     *  is write-only by design (no matching getter - see its own doc), so
     *  this is this object's own record of what it last asked for, kept in
     *  step with every [join]/[leave] call rather than read back from
     *  [relay]. */
    /** Delegated to [ConvoyRelay.convoyId] rather than mirrored. The mirror
     *  this replaced went stale on a session change: the relay cleared its own
     *  membership and this kept naming the departed rider's convoy, which
     *  `MapScreen` gates the push-to-talk button and the spin-share affordance
     *  on. */
    val activeConvoyId: StateFlow<String?> get() = relay.convoyId

    private val _guardError = MutableStateFlow<String?>(null)

    /** [relay]'s own [ConvoyRelay.lastError], overlaid with whatever this
     *  object's own guards refused to even start [relay] over - a blank
     *  live-server URL or [Features.liveRelay] being off. [ConvoyRelay]
     *  never sees either case (its `run()` is simply never called), so
     *  without this overlay a misconfigured server would leave [lastError]
     *  silently null instead of explaining why nothing is connecting.
     *  [_guardError] wins whenever it is set; falls through to [relay]'s own
     *  [ConvoyRelay.lastError] otherwise. */
    val lastError: StateFlow<String?> =
        combine(_guardError, relay.lastError) { guard, relayError -> guard ?: relayError }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val connected: StateFlow<Boolean> get() = relay.connected
    val peers: StateFlow<Map<String, FriendPosition>> get() = relay.peers
    val talking: StateFlow<Set<String>> get() = relay.talking
    val spinOffer: StateFlow<GroupSpin?> get() = relay.spinOffer
    val spinVotes: StateFlow<Map<String, Int>> get() = relay.spinVotes
    val audioChunks: SharedFlow<IncomingAudioChunk> get() = relay.audioChunks
    val placeEvents: SharedFlow<RelayPlaceEvent> get() = relay.placeEvents

    /** Forwards [TripTrackingService.lastFix] to [relay] for the life of the
     *  process, not just while a convoy/circle is joined - [ConvoyRelay.sendLocation]
     *  is a cheap no-op while nothing is connected (it throttles, then hands
     *  off to a socket that is simply null), so there is no cost to leaving
     *  this running versus starting/stopping it around every [join]/
     *  [setNotifyCircles] cycle, and doing it once here avoids needing to.
     *  Location is handed in rather than read from a platform API by
     *  [ConvoyRelay] itself - see its `sendLocation` doc - so this is the
     *  one Android-side collector that closes that gap. */
    private val locationForwarder: Job = scope.launch {
        TripTrackingService.lastFix.collect { fix ->
            if (fix == null) return@collect
            relay.sendLocation(fix.lat, fix.lon, fix.bearingDeg?.toDouble(), fix.speedMps * 3.6)
        }
    }

    /** Effective live-relay URL - see [OkHttpRelaySocket.liveUrl], which
     *  this defers to so [ConvoyLiveService]'s own "refuse to start" guard
     *  and [OkHttpRelaySocket.connect] itself never resolve it two different
     *  ways. */
    fun liveUrl(): String = OkHttpRelaySocket.liveUrl()

    /** Joins [convoyId]'s live relay - see [ConvoyRelay.setConvoy]. Refuses
     *  when [Features.liveRelay] is off or no live server is configured,
     *  the two guards [ConvoyRelay] deliberately does not apply - see the
     *  class doc. Safe to call again with a different id to switch convoys;
     *  [ConvoyRelay.setConvoy] itself is the one that decides a switch needs
     *  a reconnect. */
    fun join(convoyId: String) {
        if (!Features.liveRelay) return
        if (relay.convoyId.value == convoyId && runJob?.isActive == true) return
        if (liveUrl().isBlank()) {
            // Refuse to start the retry loop at all rather than spin it
            // forever against a server that was never configured - see
            // ConvoyLiveService's matching guard, which is what stops the
            // foreground service and GPS escalation from running pointlessly.
            _guardError.value = "No live server configured"
            return
        }
        // A previous attempt's failure must not be shown as this one's state
        // while it is still connecting - the UI reads this the moment a join
        // starts, before any reply has come back.
        _guardError.value = null
        relay.setConvoy(convoyId)
        ensureRunning()
    }

    /** Leaves the convoy - not necessarily the connection itself, which a
     *  notify-circle join (see [setNotifyCircles]) may still need alive.
     *  [ConvoyRelay.setConvoy]'s own doc explains why leaving reopens the
     *  socket rather than just forgetting the id locally. */
    fun leave() {
        relay.setConvoy(null)
    }

    /** Circles to receive live `place_event` notifications for, joined onto
     *  this same socket - independent of [join]/[leave], see
     *  [ConvoyRelay.setNotifyingCircles]. */
    fun setNotifyCircles(circleIds: Set<String>) {
        if (!Features.liveRelay) return
        relay.setNotifyingCircles(circleIds)
        if (circleIds.isEmpty() && relay.convoyId.value == null) return
        if (liveUrl().isBlank()) {
            _guardError.value = "No live server configured"
            return
        }
        _guardError.value = null
        ensureRunning()
    }

    fun sendPttStart() = relay.sendPttStart()
    fun sendPttEnd() = relay.sendPttEnd()
    fun sendAudioChunk(pcm: ByteArray) = relay.sendAudioChunk(pcm)
    fun sendSpinOffer(candidates: List<SpinCandidate>) = relay.sendSpinOffer(candidates)

    /** Casts this device's vote - [username] is read here, not inside
     *  [ConvoyRelay], which takes it as a parameter rather than reaching for
     *  [Settings.authUsername] itself; see [ConvoyRelay.sendSpinVote]'s own
     *  doc. */
    fun sendSpinVote(index: Int) = relay.sendSpinVote(Settings.authUsername.value, index)

    fun clearSpinOffer() = relay.clearSpinOffer()

    /** Launches [ConvoyRelay.run] if it is not already running - see
     *  [runJob] and [runLock]'s own docs for why more than one caller needs
     *  to be able to trigger this safely. [Auth.bearer] is wrapped in a
     *  [BearerSource], not called here - [ConvoyRelay] resolves it fresh on
     *  every (re)connect attempt, see its class doc for why, and
     *  [BearerSource]'s own doc for why a `fun interface` rather than
     *  `Auth::bearer` passed directly (which this still reads as, on the
     *  Kotlin side - the wrapping only matters to the Swift caller). */
    private fun ensureRunning() {
        synchronized(runLock) {
            if (runJob?.isActive == true) return
            runJob = scope.launch { relay.run(socket, BearerSource(Auth::bearer)) }
        }
    }
}
