package com.jellemax.detour.drive

import com.jellemax.detour.data.Auth
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RelayPlaceEvent
import com.jellemax.detour.data.jsonObjectOf
import com.jellemax.detour.data.nowMs
import com.jellemax.detour.data.optString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** [ConvoyRelay.sendLocation]'s own throttle: at most one location frame goes
 *  out this often, whatever cadence fixes actually arrive at. */
private const val LOCATION_SEND_INTERVAL_MS = 2_000L
private const val MIN_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val PEER_PRUNE_INTERVAL_MS = 5_000L

/**
 * How a device should treat the [ConvoyRelay.spinOffer]/[ConvoyRelay.spinVotes]
 * currently on the table - the convoy vote rule ported from
 * `app/.../map/GroupSpinRules.kt`, which documented the rule and its
 * correctness argument but - being reachable only from two live phones - was
 * never actually exercised by a test against a live relay loop. Task 5
 * repointed both platforms at this copy and deleted that file (and its own
 * `GroupSpinRulesTest.kt`, ported into `ConvoyRelayTest.kt`'s "the spin
 * rule" section) - this is the only implementation left. See
 * [ConvoyRelay.spinRoundOutcome].
 */
sealed interface SpinRoundOutcome {
    /** Nothing to do: not this device's round to close, or votes still out. */
    data object Wait : SpinRoundOutcome

    /** This offer *is* the decision - commit its only candidate. Reached
     *  identically whether the offer arrived on the wire or was just sent by
     *  this device itself, which is the whole point: it is what makes every
     *  member (the sharer included) land on the same destination off one
     *  frame, rather than each resolving its own tally of votes. */
    data object CommitOnly : SpinRoundOutcome

    /** Every expected voter has voted, and this device opened the round: it
     *  alone re-offers [leadIndex] as a fresh one-candidate offer, which is
     *  what commits it everywhere - see [CommitOnly]. */
    data class CloseRound(val leadIndex: Int) : SpinRoundOutcome
}

/**
 * Supplies a fresh bearer token to [ConvoyRelay.run] on every (re)connect
 * attempt - a `fun interface` rather than the bare `bearer: suspend () ->
 * String` function-type parameter this replaced, for two reasons that only
 * matter once Swift is the one implementing it.
 *
 * **A bare suspend function type has no Objective-C block to lower to.**
 * Kotlin/Native does not - cannot - compile an exported suspend function
 * type to a block, since a block cannot suspend; it generates a
 * `KotlinSuspendFunction0` protocol instead, whose sole requirement is
 * `invokeWithCompletionHandler:`. A Swift **closure literal** cannot
 * conform to a protocol existential, so a call site handing `run` a
 * `bearer: { ... }` closure directly does not compile. A `fun interface`
 * lowers to an ordinary Objective-C protocol with one plain method, which a
 * small Swift type - a struct or a class, not a closure - genuinely can
 * implement.
 *
 * **A bare function-type parameter has nowhere for `@Throws` to attach.**
 * That annotation targets a declared function; it cannot land on a
 * parameter whose type merely happens to be a suspend function. That is
 * exactly why `Auth.bearer` reached Swift unannotated for as long as it
 * did - there was no annotation site on this side of the call even after
 * `Auth.bearer` itself was fixed - and an unmarked suspend function
 * propagates only `CancellationException`, terminating the process on
 * anything else. [bearer] gives that annotation a home, so a caller-side
 * `try?`/`signedIn` workaround is no longer what stands between an expired
 * refresh token and a crash.
 */
fun interface BearerSource {
    @Throws(Exception::class)
    suspend fun bearer(): String
}

/**
 * The convoy live-relay's state machine: peers, push-to-talk membership, the
 * group spin vote, and the connect/backoff/reconnect loop that feeds them -
 * one implementation of what `app/.../net/ConvoyLiveClient.kt` (693 lines)
 * and `iosApp/Detour/ConvoyLiveClient.swift` (600 lines) each wrote by hand,
 * running against [RelaySocket] rather than OkHttp or `URLSessionWebSocketTask`
 * directly. This is a port, not a redesign: every reconnect/backoff/lastError
 * decision below is checked against what those two files actually do, not
 * what seemed cleaner.
 *
 * **What the socket is joined to is state, not a [run] parameter - the
 * socket is additive, but only in one direction.** One socket serves a
 * convoy and any number of circles at once: [setConvoy] names the convoy (or
 * clears it), [setNotifyingCircles] names the circles that want live
 * `place_event` arrival/departure pushes, and the two are independent -
 * `CircleNotifyService` on Android holds this socket open purely for
 * circles, with no convoy joined at all, which an earlier revision of this
 * class made impossible by requiring a `groupId` to call [run] in the first
 * place. [run] stays connected for as long as either is set (see
 * [shouldStayConnected], reproduced from the Android client's function of
 * the same name) and returns once neither is, without needing [stop] to be
 * called - see [run]'s own doc.
 *
 * Neither existing client has a wire frame to leave a single group - the
 * outbound protocol is exactly seven frame types (`join`, `location`,
 * `ptt_start`, `ptt_end`, `ptt_audio`, `spin_offer`, `spin_vote`) and closing
 * the whole socket is the only way any of them parts a membership at all.
 * That makes an *addition* (a convoy where there was none, a new circle id)
 * cheap - it joins fresh on the *live* socket, no reconnect - but a
 * *removal* (leaving a convoy, switching convoys - a removal plus an
 * addition - or dropping a circle id) has no cheap path: it closes whatever
 * socket [run] currently has open and lets [run]'s own reconnect loop bring
 * a fresh one back joined to exactly what remains wanted, never to whatever
 * was just left - see [setConvoy] and [setNotifyingCircles]'s own docs. This
 * is why the removal side cannot stay additive too: [sendLocation] forwards
 * this device's GPS for the life of the connection, so a socket left joined
 * to a group this device actually departed keeps broadcasting its position
 * onto it - the exact shape of leak documented on `ConvoyLiveClient.swift`'s
 * `sessionEnded()`, for a socket that outlived a sign-out instead of a
 * membership change. Do not ship a third instance of it.
 *
 * **Exactly one instance app-wide, and exactly one live [run] on it at a
 * time - this class enforces only the second.** Today's `ConvoyLiveClient`
 * is an `object`, which is *why* `ConvoyLiveService` and `CircleNotifyService`
 * share one socket - the "one socket, many groups" invariant the additive
 * design above rests on. [ConvoyRelay] being a `class` instead (two live
 * instances at once are exactly what the convergence test needs - see
 * `ConvoyRelayTest`) drops that guarantee: nothing stops Task 3 or 4 from
 * handing each service its own instance, which would mean two sockets, two
 * location broadcasts, and neither service's [stop] able to part the other's
 * memberships. Holding one instance where both services can reach it has to
 * stay a call-site discipline - this class cannot enforce it from inside
 * itself. What it *can* enforce, and does: [run] refuses a second concurrent
 * call on the same instance (see the check at its top), since two live loops
 * would both write [currentSocket] and only [stop] the newer one, leaving
 * the older loop's socket open and joined with nothing left to close it.
 *
 * **Two guards both existing clients apply live entirely outside this
 * class, and Task 3/4 must supply them at the call site.** Android refuses
 * to start its retry loop at all when no live server is configured
 * (`ConvoyLiveClient.kt:257-263`, `:330-335` - "Refuse to start the retry
 * loop at all rather than spin it forever against a server that was never
 * configured"), and its foreground service has a matching guard so it and
 * GPS escalation do not run pointlessly either. URL resolution now lives in
 * [RelaySocket] (see the paragraph above), so [run] has nothing left to
 * check "blank" against - whatever constructs a [RelaySocket] must refuse to
 * call [run] at all when it has nowhere to point. Likewise `Features.liveRelay`
 * - "the one switch that turns every live feature off on both platforms at
 * once" per the iOS client's own comment - gates every membership entry
 * point on both existing clients, and has no equivalent here: [run],
 * [setConvoy] and [setNotifyingCircles] all do exactly what they are told
 * regardless of the flag. Both guards stay the caller's job.
 *
 * **URL and header resolution stay a platform concern.** Android's derives a
 * `wss://` URL from a `Context`-backed `Settings`/`BuildConfig` read; iOS's
 * does not go through the same derivation at all. Both differences are real
 * and neither belongs in `commonMain` - see `Platform.kt`'s module-boundary
 * doc - so a [RelaySocket] arrives here already pointed at the right place,
 * and [run] only ever hands it a bearer token.
 *
 * **`bearer` is a [BearerSource] supplier, not a resolved string**, for two
 * reasons at once. First, fidelity: both existing clients call
 * `Auth.bearer()` fresh inside their own per-attempt connect function, not
 * once for the whole reconnect loop, so a socket that comes back after an
 * access token's 15-minute lifetime presents a current one rather than a
 * stale one - a supplier preserves that, a resolved string would not.
 * Second, testability: calling `Auth.bearer()` directly from here would make
 * every test in this file depend on `Settings.init()`, which needs a real
 * platform `Context` this module's tests do not have (see `FriendsStore`'s
 * own doc for the same constraint) - `Auth.signedIn` reads a safe default
 * and never crashes, but `Auth.bearer()` would then just throw
 * `AuthException` on every call, closing off the very connect-flow tests
 * this class exists to make possible. The real call site passes
 * `BearerSource(Auth::bearer)` - see [BearerSource]'s own doc for why a
 * `fun interface`, not the bare function type this parameter started as:
 * that type could not cross to Swift at all, and gave `Auth.bearer`'s
 * `@Throws` nowhere to attach on this side of the call either.
 *
 * Plainly, since the design brief's own shorthand can read otherwise: **this
 * class never calls `Auth.bearer()` or `RoutingServer` itself.** Every access
 * token crosses [run]'s boundary already resolved, via [bearer]; every URL
 * crosses [RelaySocket]'s boundary already resolved, per the paragraph
 * above. That is this implementation's own interpretive call rather than the
 * brief's literal text - the platform call sites wiring the real
 * `Auth::bearer` and a platform [RelaySocket] should read this signature
 * rather than assume it matches the brief verbatim.
 *
 * **`stop()` is a flag, not a cancellation - deliberately.** Cancelling a
 * Swift `Task` does not cancel the Kotlin coroutine behind an exported
 * `suspend fun` (see [RelaySocket]'s doc for why), so relying on cancellation
 * to end [run] is exactly what let iOS's relay socket outlive a sign-out and
 * keep broadcasting the *next* rider's GPS onto the *previous* rider's
 * convoy - raising that convoy's arrival notifications on the new rider's
 * phone in the process. [stop] instead sets a flag [run] checks at every
 * await boundary, and closes the live [RelaySocket] so a blocked
 * [RelaySocket.receive] returns instead of hanging. [run] is *also*
 * cancellation-safe - Android's job is genuinely cancellable, and a
 * cancelled coroutine must not leave a socket open either - so both paths
 * close the socket; only the flag path is the one a Swift caller can
 * actually reach.
 *
 * [run] wires [clearMembershipForSessionChange] to `Auth.sessionEpoch` while
 * it is live, alongside whatever button-press calls [stop] directly: a 401
 * or a server switch bump the epoch the same way a sign-out does (see
 * [Auth.sessionEpoch]'s own doc), and a session change, unlike an ordinary
 * [stop], must clear [_convoyId] and [_notifyingCircleIds] rather than
 * preserve them for a reconnect - see [clearMembershipForSessionChange]'s
 * own doc for the exact shape of that. But that watcher is scoped to [run]'s
 * own coroutine and is cancelled the instant [run] returns, for *any*
 * reason - not just [stop] but [shouldStayConnected] simply going false -
 * and nothing outside [run] watches the epoch in between. A "go offline"
 * button that calls [stop] directly (see its own doc) is exactly the caller
 * that opens this gap: [stop] deliberately preserves membership for its own
 * reconnect, so a session change landing after [run] has returned but before
 * anything wants the socket again leaves [_convoyId] pointed at the departed
 * session with nothing left to notice - until the *next* rider's own
 * [setNotifyingCircles] call resurrects it. [setConvoy] and
 * [setNotifyingCircles] close that window themselves instead of relying on
 * [run] having been live to catch it - see [discardMembershipIfSessionChanged]'s
 * own doc. The `Auth.sessionEpoch` wire-up itself is implemented but not
 * unit-tested here - see the comment on [run]'s own `sessionWatcher` for
 * why - but what both paths call is, directly.
 */
class ConvoyRelay {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Set when the relay cannot be reached at all, or rejects a join - never
     *  when it is reached and joined but simply has nothing to say, which is
     *  what makes this distinguishable from an idle-but-healthy convoy.
     *  Cleared on every fresh [run] call and again on every successful
     *  "joined" reply. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, FriendPosition>>(emptyMap())
    val peers: StateFlow<Map<String, FriendPosition>> = _peers.asStateFlow()

    private val _talking = MutableStateFlow<Set<String>>(emptySet())
    val talking: StateFlow<Set<String>> = _talking.asStateFlow()

    private val _spinOffer = MutableStateFlow<GroupSpin?>(null)
    val spinOffer: StateFlow<GroupSpin?> = _spinOffer.asStateFlow()

    private val _spinVotes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val spinVotes: StateFlow<Map<String, Int>> = _spinVotes.asStateFlow()

    private val _audioChunks = MutableSharedFlow<IncomingAudioChunk>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioChunks: SharedFlow<IncomingAudioChunk> = _audioChunks

    private val _placeEvents = MutableSharedFlow<RelayPlaceEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val placeEvents: SharedFlow<RelayPlaceEvent> = _placeEvents

    private val _stopped = MutableStateFlow(false)

    /** The socket [stop] closes to unblock a live [RelaySocket.receive] - see
     *  the class doc. Set at the top of every [run] call.
     *
     *  `@Volatile` because it crosses threads without a happens-before edge
     *  otherwise: [run] writes it on the relay coroutine's own thread
     *  (`Dispatchers.IO` on Android), while [stop], [setConvoy],
     *  [setNotifyingCircles], [send] and [sendLocation] all read it from
     *  whatever thread the caller is on (main, typically). Same shape as
     *  `MunicipalityStore.misses` in `data/Coverage.kt` - "the discovery
     *  coroutine writes it while the location callback reads it" - just with
     *  a plain nullable reference instead of a replace-the-whole-set write.
     *  Without this, a stale read on the removal path (`hadConvoy ->
     *  currentSocket?.close()` in [setConvoy]) can see `null` for a socket
     *  that is very much still open, leaving [run] parked in [RelaySocket.receive]
     *  and [sendLocation] still broadcasting this device's GPS onto a convoy
     *  it just left - and defeats [stop] the identical way, since [stop]'s
     *  flag write is safely published through `_stopped` (a `StateFlow`) but
     *  the *close* that unblocks a parked `receive()` was not. */
    @Volatile
    private var currentSocket: RelaySocket? = null

    /** Guards against a second concurrent [run] call on this same instance -
     *  see the class doc's "exactly one instance, exactly one live run()"
     *  paragraph. Not a lock, just a fail-fast flag: a call that finds it
     *  already `true` throws rather than silently starting a second loop
     *  that would overwrite [currentSocket] out from under the first one,
     *  after which [stop] could only ever close the newer socket while the
     *  older loop sits parked in [RelaySocket.receive] on one that stays
     *  open and joined. Written/read across the same run()-thread/
     *  caller-thread boundary as [currentSocket] - see its doc. */
    @Volatile
    private var running: Boolean = false

    /** True once this attempt's [joinEverythingWanted] has sent the initial
     *  join batch, cleared in [attempt]'s own `finally` - what [setConvoy]
     *  and [setNotifyingCircles] check before sending an *addition* straight
     *  onto the live socket, instead of [_connected]. [_connected] only
     *  flips true once a `joined` reply actually arrives, and that is a real
     *  network round trip after the join batch already went out - an
     *  addition landing in that window used to gate on [_connected] and be
     *  neither sent then nor replayed later, silently never joining at all.
     *  The socket is already open and receiving by the time
     *  [joinEverythingWanted] runs, so anything wanted from that point on
     *  can go straight out, same as it does once genuinely connected. Same
     *  cross-thread shape as [currentSocket] - see its doc. */
    @Volatile
    private var attemptLive: Boolean = false

    /** The convoy this device is in, or null - see [setConvoy]. Persists
     *  independently of [run]'s own lifecycle: a caller may set this before
     *  [run] is ever called (or after it has returned), and [run] simply
     *  reads whatever is current when it needs to. Also what the plain
     *  outbound sends below stamp onto every frame, mirroring what the old
     *  singletons stamped from their own `_activeConvoyId` - null means a
     *  send is silently dropped rather than mis-addressed, same as before. */
    private val _convoyId = MutableStateFlow<String?>(null)

    /**
     * The convoy this device is joined to, or null.
     *
     * Exposed so a platform reads it rather than keeping its own copy. Android
     * used to mirror it in `net/ConvoyLiveClient.kt`, and the mirror went stale
     * on a session change - [clearMembershipForSessionChange] cleared this one
     * and left the mirror naming the departed rider's convoy, which the map
     * gates the push-to-talk button and the spin-share affordance on. A rider
     * could offer a destination to a convoy they had left. One source of truth
     * is the fix; clearing two of them in step is the bug waiting to happen
     * again.
     */
    val convoyId: StateFlow<String?> = _convoyId.asStateFlow()

    /** Circles that want live `place_event` pushes on this same socket,
     *  independent of [_convoyId] - see [setNotifyingCircles]. Same
     *  persist-across-[run] shape as [_convoyId]. */
    private val _notifyingCircleIds = MutableStateFlow<Set<String>>(emptySet())

    /** `@Volatile` for parity with the field it replaces,
     *  `net/ConvoyLiveClient.kt`'s own `@Volatile private var lastLocationSentMs`
     *  - [sendLocation] can be called from any caller thread, same as
     *  [currentSocket]'s readers, and there is only ever the one writer. */
    @Volatile
    private var lastLocationSentMs = 0L

    /** What [run]'s own loop keeps looping for, and what [setConvoy]/
     *  [setNotifyingCircles] check before deciding whether clearing their
     *  own membership should end the connection entirely. Reproduces
     *  `ConvoyLiveClient.kt`'s function of the same name and the identical
     *  rule - a convoy, a notify-circle join, or (ordinarily) both. */
    private fun shouldStayConnected(): Boolean =
        _convoyId.value != null || _notifyingCircleIds.value.isNotEmpty()

    /**
     * The `Auth.sessionEpoch` [discardMembershipIfSessionChanged] last saw -
     * `null` until the first call, so a relay that has never touched
     * membership at all is never treated as stale against it. `internal`
     * rather than private so a test can set it directly and simulate a
     * session change that happened while nothing was live to react to it
     * reactively - the same shortcut [clearMembershipForSessionChange]'s own
     * tests already take, and for the identical reason: this module's tests
     * stay isolated from the real `Auth`/`Settings` singletons (see [run]'s
     * own comment on its `sessionWatcher`).
     *
     * `@Volatile` for the same cross-thread reason as [currentSocket] -
     * [setConvoy], [setNotifyingCircles] and [run] can each be the first
     * caller to read/write this, from whatever thread each was called on.
     */
    @Volatile
    internal var membershipEpoch: Int? = null

    /**
     * Discards [_convoyId], [_notifyingCircleIds] and the convoy-scoped
     * display state - [_peers], [_talking], [_spinOffer], [_spinVotes] - if
     * `Auth.sessionEpoch` has moved since [membershipEpoch] last checked,
     * then stamps the current value either way. Called first thing in
     * [setConvoy], [setNotifyingCircles] and [run], before any of the three
     * reads or writes its own state.
     *
     * This is the other half of session-change handling - see the class
     * doc's `Auth.sessionEpoch` paragraph for the full shape of the gap it
     * closes: [run]'s own `sessionWatcher` reacts the moment the epoch moves
     * *while [run] is live*, closing a socket that might otherwise keep
     * broadcasting this device's location onto a convoy it just left. But
     * that watcher is scoped to [run]'s own coroutine and is cancelled the
     * instant [run] returns, for any reason, and nothing outside [run]
     * watches the epoch in between. [setConvoy] and [setNotifyingCircles]
     * are exactly the entry points that can turn a departed session's
     * leftover [_convoyId] back into a live join - a *reconnect* (the epoch
     * unchanged) must still preserve membership, which is why this checks
     * the epoch rather than clearing unconditionally, but a *session change*
     * must not, whether [run] happened to be live to notice it or not.
     *
     * Deliberately does not call [stop]: unlike
     * [clearMembershipForSessionChange], every caller of this is about to
     * apply its own fresh membership change right after - a live [run] (if
     * any) simply keeps going once it next checks what it still wants, and
     * an idle one is started fresh by whatever `ensureRunning` the platform
     * caller triggers next, joined to exactly what survived this call.
     */
    private fun discardMembershipIfSessionChanged() {
        val current = Auth.sessionEpoch.value
        val previous = membershipEpoch
        membershipEpoch = current
        if (previous == null || previous == current) return
        _convoyId.value = null
        _notifyingCircleIds.value = emptySet()
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
    }

    /**
     * Connects over [socket], forwarding [bearer]'s result on every
     * (re)connect attempt, and keeps reconnecting with backoff for as long
     * as [shouldStayConnected] holds - a convoy, a notify-circle join, or
     * both, set via [setConvoy]/[setNotifyingCircles] before or during this
     * call. Returns once [stop] has been called, **or** once neither wants
     * the socket any more (see [shouldStayConnected]) - the second case
     * needs no [stop] call at all, matching how neither existing client's
     * connection loop needs one either: Android's `runConnection` and iOS's
     * `connectionLoop` both simply stop looping once their own
     * `shouldStayConnected`/`activeConvoyId != nil || !wantedCircleIds.isEmpty()`
     * check goes false. Owns the whole connect → receive → backoff →
     * reconnect cycle; nothing about it gives up early on its own otherwise,
     * matching both existing clients, which retry forever for as long as
     * something wants the socket open.
     *
     * Resets every piece of state below to this call's own baseline first -
     * peers, talking, spin offer/votes, `lastError` - so a previous run's
     * state can never bleed into this one, the same guard `join()` applied
     * on both existing clients before starting their own connection. Neither
     * [setConvoy]'s nor [setNotifyingCircles]'s own state is reset here,
     * deliberately: a caller may set either before ever calling [run] (see
     * the class doc's `CircleNotifyService` case), and this call must see
     * it, not wipe it out from under a caller who set it first. This is
     * unconditional and distinct from [discardMembershipIfSessionChanged],
     * called just above - that one only clears [_convoyId]/
     * [_notifyingCircleIds] (and this same display state again, redundantly
     * but harmlessly) when `Auth.sessionEpoch` has actually moved since they
     * were last touched; an ordinary reconnect leaves them exactly as a
     * caller set them.
     *
     * Throws [IllegalStateException] immediately if this instance already
     * has a live [run] - see [running] and the class doc's "exactly one
     * instance, exactly one live run()" paragraph. Not a condition normal
     * operation should ever hit; a Task 3/4 call site racing two [run] calls
     * on the same instance is a bug worth failing loudly on, not one to
     * paper over the way a silent no-op would.
     */
    @Throws(Exception::class)
    suspend fun run(socket: RelaySocket, bearer: BearerSource): Unit = coroutineScope {
        check(!running) {
            "ConvoyRelay.run() called while already running - exactly one live run() " +
                "per instance is required, see the class doc"
        }
        running = true
        currentSocket = socket
        // Belt and braces alongside setConvoy/setNotifyingCircles's own call
        // - every reachable caller already goes through one of those before
        // ensureRunning() ever invokes this, so this is normally a no-op,
        // but run() should not depend on that call-site discipline to stay
        // correct on its own - see discardMembershipIfSessionChanged's doc.
        discardMembershipIfSessionChanged()
        _stopped.value = false
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
        _lastError.value = null
        _connected.value = false

        val startEpoch = Auth.sessionEpoch.value
        val sessionWatcher = launch {
            // The wire-up itself - Auth.sessionEpoch actually moving while a
            // fake-socket run() is live - is not exercised by this file's
            // tests: every test here drives ConvoyRelay in isolation from
            // the real Auth/Settings singletons on purpose, and mutating
            // them mid-test would risk bleeding into every other test
            // sharing this JVM test process (see the class doc's bearer-
            // supplier paragraph for the matching Settings.init() constraint
            // on Auth.bearer() itself). Left wired rather than removed - a
            // 401, sign-out or server switch must still end run() - but this
            // one line is verified only by manual repro, not a unit test.
            // What it calls, clearMembershipForSessionChange, is unit-tested
            // directly - see its own doc.
            Auth.sessionEpoch.first { it != startEpoch }
            clearMembershipForSessionChange()
        }
        val pruner = launch {
            while (true) {
                delay(PEER_PRUNE_INTERVAL_MS)
                prunePeers(nowMs())
            }
        }
        try {
            var failures = 0
            while (!_stopped.value && shouldStayConnected()) {
                val everJoined = attempt(socket, bearer)
                if (_stopped.value || !shouldStayConnected()) {
                    // Publish the disconnect before leaving, not only on the
                    // reconnect path below. The socket is shut either way, but
                    // a screen reads `connected` rather than the socket: a
                    // terminal exit that left it true had FriendsScreen still
                    // showing "Connected", the convoy notification still up,
                    // and the toggle button's first tap calling stop() again
                    // instead of reconnecting - after a sign-out, a 401, or a
                    // server switch, none of which the rider asked for.
                    _connected.value = false
                    return@coroutineScope
                }
                _connected.value = false
                // A vote tallied against a socket that is no longer relaying
                // anyone's frames is wrong by the time it reconnects - drop
                // it rather than let a stale offer sit on screen looking
                // live through a dead connection. Matches both existing
                // clients' `runConnection`/`connectionLoop`. This fires on
                // *every* reconnect for *any* reason the previous attempt
                // ended, including one this device caused itself by dropping
                // an unrelated notify-circle while the convoy never changed
                // (see setNotifyingCircles's own doc) - a rider mid-vote can
                // watch the destination sheet vanish because a CircleSync
                // tick landed mid-drive for a reason that has nothing to do
                // with the convoy. Deliberate, not overlooked: once the
                // socket has been down at all, any vote frame could have
                // been missed during the gap regardless of why it dropped,
                // so the tally is no more trustworthy after a self-inflicted
                // reconnect than after a network blip, and this loop would
                // have to start tracking *why* the previous attempt ended to
                // tell the two apart - coupling it to setConvoy/
                // setNotifyingCircles for a case that already fits the
                // broader "removal reopens" trade-off accepted everywhere
                // else in this file. Clearing here is judged correct, not
                // merely convenient; see the task report for the full case.
                _spinOffer.value = null
                _spinVotes.value = emptyMap()
                failures = if (everJoined) 0 else failures + 1
                sleepUnlessStopped(backoffDelayMs(MIN_BACKOFF_MS, MAX_BACKOFF_MS, failures))
            }
        } finally {
            sessionWatcher.cancel()
            pruner.cancel()
            currentSocket = null
            running = false
        }
    }

    /**
     * Ends [run] and closes [socket] so a blocked [RelaySocket.receive]
     * returns instead of hanging - see the class doc for why this is a flag
     * plus a forced close rather than relying on cancelling [run]'s
     * coroutine. Idempotent, and safe to call with nothing running.
     *
     * **Deliberately leaves [_convoyId] and [_notifyingCircleIds] alone** -
     * an ordinary [stop] (a button press, or [run] itself noticing nothing
     * is wanted any more) is what lets a caller-initiated reconnect resume
     * the exact same membership, matching [run]'s own doc on why it does not
     * reset either. A *session* change is the one caller that must clear
     * them instead - see [clearMembershipForSessionChange], which calls this
     * after doing so, not the other way round.
     */
    fun stop() {
        _stopped.value = true
        currentSocket?.close()
    }

    /**
     * What [run]'s own `Auth.sessionEpoch` watcher calls once the epoch has
     * actually moved *while [run] is live* - a session change (sign-out, a
     * 401, a server switch), not a reconnect. Clears every piece of state a
     * caller only ever *adds* to ([setConvoy], [setNotifyingCircles]) plus
     * the convoy-scoped display state [run]'s own next start would otherwise
     * reset on its own - [peers], [talking], [spinOffer], [spinVotes] - and
     * only then calls [stop], so nothing observing those flows mid-teardown
     * can still see the departed session's convoy.
     *
     * This is the fix for a real leak, stated plainly: without it, [stop]
     * alone closes the socket but leaves [_convoyId] pointed at the convoy
     * the signed-out rider was in. A membership-sync call that has nothing
     * to do with a convoy at all - `CircleSync`'s periodic
     * [setNotifyingCircles] tick, running for whichever rider is signed in
     * *now* - then makes [shouldStayConnected] true again on that stale id
     * alone, and the very next [run] rejoins it, broadcasting the new
     * rider's [sendLocation] fixes onto the previous rider's convoy.
     *
     * That leak's other half - the epoch moving while [run] is *not* live to
     * notice, so this function is never reached at all before the next
     * membership call - is what [discardMembershipIfSessionChanged] closes
     * instead, directly from [setConvoy]/[setNotifyingCircles] themselves;
     * see its own doc. This function also stamps [membershipEpoch] to the
     * current value, so a [setConvoy]/[setNotifyingCircles] call landing
     * right after does not redundantly discard what this call already just
     * cleared.
     *
     * `internal` rather than private so a test can drive this directly,
     * without needing `Auth.sessionEpoch` to actually move while a
     * fake-socket [run] is live - the same shortcut [applyEvent] and
     * [prunePeers] take, and for the identical reason: exercising the real
     * epoch wire-up needs the real `Auth`/`Settings` singletons this
     * module's tests deliberately stay isolated from (see [run]'s own
     * comment on its `sessionWatcher` for why).
     */
    internal fun clearMembershipForSessionChange() {
        _convoyId.value = null
        _notifyingCircleIds.value = emptySet()
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
        membershipEpoch = Auth.sessionEpoch.value
        stop()
    }

    /**
     * The convoy this device is in, or null - independent of
     * [setNotifyingCircles] (see the class doc's "additive, but only in one
     * direction" paragraph).
     *
     * There is no wire frame to leave a single group on either existing
     * client - only closing the whole socket parts every membership at once.
     * A **fresh** convoy (there was none joined before) is cheap: it joins
     * right on the live socket if one is already up, same as a
     * freshly-notifying circle. But leaving, or switching away from, a
     * convoy this device *was* in is a **removal**, and the only way the
     * relay actually stops treating this device as a member of the old
     * [groupId] is a reconnect - so this closes whatever socket [run]
     * currently has open, and [run]'s own reconnect loop brings a fresh one
     * back joined to exactly the new [groupId] (if any) and every circle
     * still wanted, never the departed convoy. This is the case
     * `ConvoyLiveClient.swift`'s `join(convoyId:)` comment is about: simply
     * joining a new convoy on top of the old one would leave this device
     * receiving both convoys' traffic - and, worse, still broadcasting
     * [sendLocation]'s fixes onto the one it left. [peers]/[talking]/
     * [spinOffer]/[spinVotes] are cleared locally right away either way, so
     * what is shown never lags behind what was just requested even while
     * the reconnect is in flight.
     *
     * If this call leaves nothing wanted at all (see [shouldStayConnected])
     * the live socket - if any - is closed the same way (a reconnect loop
     * that finds nothing wanted just lets [run] return), which is what lets
     * [run] notice and return; see [run]'s own doc.
     */
    fun setConvoy(groupId: String?) {
        // First thing, before this call's own change - see
        // discardMembershipIfSessionChanged's own doc for why this and
        // setNotifyingCircles are the two entry points that must not build
        // on a departed session's leftover membership.
        discardMembershipIfSessionChanged()
        if (_convoyId.value == groupId) return
        val hadConvoy = _convoyId.value != null
        _convoyId.value = groupId
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
        when {
            !shouldStayConnected() -> currentSocket?.close()
            // Leaving, or switching away from, a convoy is a removal -
            // reopen rather than join on top of it, see this function's own
            // doc for why there is no cheaper option.
            hadConvoy -> currentSocket?.close()
            // attemptLive, not _connected: an addition landing after the
            // join batch already went out but before the server's own
            // "joined" reply arrives must still be sent now, not lost - see
            // attemptLive's own doc for the window this closes.
            groupId != null && attemptLive -> send(RelayProtocol.buildJoin(groupId))
        }
    }

    /**
     * Circles that want live `place_event` arrival/departure pushes on this
     * same socket, independent of [setConvoy] - see the class doc. Replaces
     * the whole set at once, matching `ConvoyLiveClient.swift`'s
     * `setNotifyingCircles(_:)` (Android's `net/ConvoyLiveClient.kt` names
     * the same operation `setNotifyCircles`), rather than adding/removing
     * one id at a time.
     *
     * A newly-added id (nothing dropped in the same call) is joined fresh on
     * the live socket at once if one is already up - same immediate join
     * [setConvoy] does for a fresh convoy. A dropped id is a **removal**,
     * and deliberately *not* handled the way either existing client handles
     * it: Android reconnects for every membership change regardless, but
     * iOS's `removeNotifyingCircle` only forgets the id locally and lets its
     * `place_event` frames keep arriving - filtered client-side by
     * `CircleNotifications` - until whatever reconnect happens next for some
     * other reason. This class has no such downstream filter, and more to
     * the point: there is no wire "leave a single group" (see [setConvoy]'s
     * own doc for the same constraint), so a reconnect is the *only* way the
     * relay actually stops treating this device as a member of the dropped
     * circle. Staying joined server-side to a circle this device was just
     * told to leave is the same shape of leak the class doc warns about for
     * a convoy, so a dropped id reopens the socket the same way a left
     * convoy does, even though a circle set can change while a rider is
     * doing nothing in particular (a membership sync landing mid-drive) and
     * an addition never pays this cost. The trade-off is deliberate: one
     * reconnect's cost (peers/talking/spin cleared, back within a backoff
     * step) against staying joined to a group this device has left, and the
     * latter is the more expensive mistake between the two.
     *
     * If this call leaves nothing wanted at all, the live socket - if any -
     * is closed, same as [setConvoy] - see [run]'s own doc for why.
     */
    fun setNotifyingCircles(ids: Set<String>) {
        // First thing, before this call's own change - see
        // discardMembershipIfSessionChanged's own doc for why this and
        // setConvoy are the two entry points that must not build on a
        // departed session's leftover membership.
        discardMembershipIfSessionChanged()
        val previous = _notifyingCircleIds.value
        if (previous == ids) return
        val removed = previous - ids
        _notifyingCircleIds.value = ids
        // Unlike setConvoy, this deliberately does not clear _peers/_talking
        // locally right away - a dropped circle's members just linger in
        // [peers] until their own TTL expires (prunePeers self-heals it).
        // Matches Android (which never clears them for a circle drop either)
        // and differs from iOS (whose removeNotifyingCircle also leaves
        // peers as-is); a convoy leave clears immediately because leaving
        // *the* convoy is the common, deliberate case a rider is looking at
        // the screen for, whereas a circle drop is usually a background
        // membership sync nobody is watching land.
        when {
            !shouldStayConnected() -> currentSocket?.close()
            // Any dropped id is a removal - reopen rather than leave this
            // device joined to it server-side, see this function's own doc.
            removed.isNotEmpty() -> currentSocket?.close()
            // attemptLive, not _connected - see setConvoy's matching comment.
            attemptLive -> (ids - previous).forEach { send(RelayProtocol.buildJoin(it)) }
        }
    }

    /**
     * One connect → join → receive-until-closed attempt. Returns whether a
     * "joined" reply was ever seen, which decides the next attempt's backoff
     * (see [run]) - identical in spirit to Android's `connectAndAwaitClose`
     * and iOS's `connectAndAwaitClose`, merged into one.
     *
     * [socket] is closed on every exit from the inner `try` - success,
     * `stop()`, an ordinary failure, or a genuine coroutine cancellation
     * (Android's case, not Swift's - see the class doc) - via the outer
     * `finally`, rather than repeating a close call on each of those paths.
     */
    private suspend fun attempt(socket: RelaySocket, bearer: BearerSource): Boolean {
        try {
            val token = try {
                bearer.bearer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Matches both existing clients' exact wording for this
                // case - deliberately not `e.message`, since a caller's
                // `bearer` throwing is always "not signed in" here, whatever
                // the underlying reason.
                _lastError.value = "Not signed in"
                return false
            }
            if (token.isBlank()) {
                // bearer.bearer() didn't throw but handed back nothing - matches
                // Android's own treatment of a blank token as "Not signed
                // in" (net/ConvoyLiveClient.kt:488-491). Without this check a
                // supplier returning "" connects with `Authorization: Bearer `
                // and just retries forever instead of surfacing anything.
                _lastError.value = "Not signed in"
                return false
            }
            if (_stopped.value) return false

            try {
                socket.connect(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = unreachableMessage(e)
                return false
            }
            // Re-checked here too, not just before connect() above: the
            // class doc claims _stopped is checked at every await boundary,
            // and connect() is itself a suspend call - without this, a
            // stop() landing while connect() was suspended still sends the
            // whole join batch below before the loop ever notices the flag.
            if (_stopped.value) return false

            joinEverythingWanted(socket)
            // From here on the socket is open and has this attempt's join
            // batch on the wire - anything setConvoy/setNotifyingCircles
            // adds from now on can go straight out too, see attemptLive's
            // own doc for the window this closes.
            attemptLive = true
            var everJoined = false
            while (!_stopped.value) {
                val text = try {
                    socket.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only the case the status line exists for: a failure
                    // before ever joining leaves the UI on "connecting..."
                    // forever otherwise, indistinguishable from a convoy
                    // nobody else has joined yet. A drop *after* joining is
                    // an ordinary reconnect, not a fresh error to report.
                    // Deliberate deviation from Android, which sets lastError
                    // on a receive failure regardless of everJoined
                    // (net/ConvoyLiveClient.kt's onFailure); iOS matches this
                    // file's !everJoined gate, not Android's. Not a
                    // transcription slip - see the task report.
                    if (!everJoined) _lastError.value = unreachableMessage(e)
                    return everJoined
                }
                if (text == null || _stopped.value) return everJoined

                val errorFrame = errorFrameOrNull(text)
                if (errorFrame != null) {
                    // The relay rejects a join without closing the socket
                    // for it (e.g. membership was removed) - `error` is not
                    // one of RelayProtocol.decode's nine frame types by
                    // design (see its own doc), so it is read straight off
                    // the raw text here, same as both existing clients do
                    // outside their own per-frame switch. Returning ends
                    // this attempt so the backoff loop above picks it up,
                    // rather than sitting connected-but-never-joined.
                    _lastError.value = errorFrame.message
                    return everJoined
                }

                val event = RelayProtocol.decode(text, nowMs())
                if (event != null) {
                    if (event is RelayEvent.Joined) everJoined = true
                    applyEvent(event)
                }
            }
            return everJoined
        } finally {
            attemptLive = false
            socket.close()
        }
    }

    /**
     * Sends a `join` for every group currently wanted - the convoy, if any,
     * then every notifying circle - right after a fresh connect, since a new
     * connection starts with no memberships at all and the relay only adds,
     * never assumes. One frame per group, each naming exactly one id: there
     * is no combined "join these several groups" frame on the wire, matching
     * both existing clients' post-connect join batch (Android's
     * `net/ConvoyLiveClient.kt` `onOpen`, iOS's `ConvoyLiveClient.swift`
     * `connectAndAwaitClose`), which each loop the same way over "the
     * convoy, then every circle".
     */
    private fun joinEverythingWanted(socket: RelaySocket) {
        _convoyId.value?.let { socket.send(RelayProtocol.buildJoin(it)) }
        _notifyingCircleIds.value.forEach { socket.send(RelayProtocol.buildJoin(it)) }
    }

    /** Applies one already-decoded frame to this relay's state - what
     *  [attempt]'s receive loop calls for every frame it understands, and
     *  what a test can call directly to drive peers/talking/spin state
     *  without a live socket or coroutine, the same shortcut
     *  `FriendsStore`'s own `internal` transforms exist for. */
    internal fun applyEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.Joined -> {
                _connected.value = true
                _lastError.value = null
            }
            is RelayEvent.Left -> {
                _peers.update { it - event.username }
                _talking.update { it - event.username }
            }
            is RelayEvent.Positions -> if (event.peers.isNotEmpty()) {
                // Replaces rather than duplicates: associateBy keys the
                // update by username, and `+` on a Map overwrites any
                // existing entry for the same key.
                _peers.update { it + event.peers.associateBy { p -> p.username } }
            }
            is RelayEvent.PttStart -> _talking.update { it + event.username }
            is RelayEvent.PttEnd -> _talking.update { it - event.username }
            is RelayEvent.PttAudio -> _audioChunks.tryEmit(event.chunk)
            is RelayEvent.PlaceEventReceived -> _placeEvents.tryEmit(event.relayEvent)
            is RelayEvent.SpinOffer -> {
                // A new offer starts a fresh vote even mid-round - the
                // candidates it names are a different sheet than whatever
                // was being voted on before.
                _spinOffer.value = GroupSpin(event.candidates, fromMe = false)
                _spinVotes.value = emptyMap()
            }
            is RelayEvent.SpinVote -> _spinVotes.update { it + (event.username to event.index) }
        }
    }

    /**
     * Sweeps [peers] at [nowMs], dropping every peer whose
     * [FriendPosition.expiresAtMs] is at or before it. What [run]'s own
     * timer calls every [PEER_PRUNE_INTERVAL_MS] with a real clock; exposed
     * directly, and taking [nowMs] rather than reading a clock itself, so a
     * test can drive it without a live coroutine or a real clock - the same
     * "no ambient clock" shape as `RelayProtocol.decode` and
     * `RouteGpx.parseGpx`.
     */
    fun prunePeers(nowMs: Long) {
        _peers.update { it.filterValues { p -> p.expiresAtMs > nowMs } }
    }

    /**
     * What [spinOffer]/[spinVotes] currently resolve to for a device whose
     * own username is [myUsername] - see [SpinRoundOutcome]. Pure and
     * side-effect-free: nothing here sends a frame or clears the offer, both
     * of which stay the caller's job (committing a destination is a
     * navigation concern, well outside this class).
     *
     * [myUsername] is a parameter rather than a `Settings.authUsername` read
     * for the same reason [sendSpinVote] takes one: this class is handed
     * things and does not reach for them.
     */
    fun spinRoundOutcome(myUsername: String): SpinRoundOutcome {
        val offer = _spinOffer.value ?: return SpinRoundOutcome.Wait
        val expected = _peers.value.keys + setOfNotNull(myUsername.takeIf { it.isNotBlank() })
        return resolveSpinRound(offer.candidates.size, offer.fromMe, expected, _spinVotes.value)
    }

    /**
     * The candidate [spinVotes] currently leads with among [candidateCount]
     * candidates - see [leadingSpinIndex] for the tie-break itself. Unlike
     * [spinRoundOutcome], this never waits for anyone: it is what the
     * sharer's own "go with the lead" affordance calls to force a round
     * closed *before* every expected voter has, on both platforms - the one
     * legitimate reason a caller needs the tally outside
     * [spinRoundOutcome]'s own "has everyone voted" gate.
     */
    fun currentLeadIndex(candidateCount: Int): Int = leadingSpinIndex(_spinVotes.value, candidateCount)

    /**
     * Whether [spinRoundOutcome] resolves to [SpinRoundOutcome.CloseRound]
     * for [myUsername] right now - never true for [SpinRoundOutcome.Wait] or
     * [SpinRoundOutcome.CommitOnly]. A `Boolean`-only projection of
     * [spinRoundOutcome] for `iosApp/Detour/ConvoyLiveClient.swift`
     * specifically: this codebase has no existing precedent for a Swift
     * caller switching over a Kotlin sealed interface with a
     * payload-carrying case like [SpinRoundOutcome.CloseRound], so the iOS
     * call site reads this plus [currentLeadIndex] - both plain
     * `Boolean`/`Int`, the primitive shapes already proven to cross the
     * Kotlin/Native boundary elsewhere in this class - rather than risk an
     * unverifiable export shape neither this task nor CI can compile-check
     * (see the task report). Kotlin callers - Android, this file's own
     * tests - use [spinRoundOutcome] directly instead; this exists for
     * Swift alone.
     */
    fun spinRoundIsReadyToClose(myUsername: String): Boolean =
        spinRoundOutcome(myUsername) is SpinRoundOutcome.CloseRound

    // --- outbound sends -----------------------------------------------------
    //
    // Every one of these is fire-and-forget and non-suspend, matching both
    // existing clients' own `send`/`sendUnscoped` - a caller does not await a
    // send, it watches the relevant StateFlow for whatever comes back.

    fun sendPttStart() {
        _convoyId.value?.let { send(RelayProtocol.buildPttStart(it)) }
    }

    fun sendPttEnd() {
        _convoyId.value?.let { send(RelayProtocol.buildPttEnd(it)) }
    }

    fun sendAudioChunk(pcm: ByteArray) {
        _convoyId.value?.let { send(RelayProtocol.buildPttAudio(it, pcm)) }
    }

    /** Shares a spin with the convoy - or, with a single candidate, closes a
     *  round on its winner (see [SpinRoundOutcome.CloseRound]). Sets
     *  [spinOffer] locally right away, *unconditionally* - the relay
     *  excludes the sender from its own broadcast, so waiting for the frame
     *  to come back would mean waiting forever, same as both existing
     *  clients - and only the wire send itself is gated on a group actually
     *  being joined; a local caller resolving its own round must not be held
     *  hostage to that. Silently does nothing outside 1-3 candidates,
     *  matching the relay's own cap. */
    fun sendSpinOffer(candidates: List<SpinCandidate>) {
        if (candidates.isEmpty() || candidates.size > 3) return
        _spinOffer.value = GroupSpin(candidates, fromMe = true)
        _spinVotes.value = emptyMap()
        _convoyId.value?.let { send(RelayProtocol.buildSpinOffer(it, candidates)) }
    }

    /** Casts [username]'s vote and records it in the local tally right away,
     *  unconditionally - for the same reason [sendSpinOffer] updates
     *  [spinOffer] locally regardless of the wire send below - the relay
     *  will not echo it back. */
    fun sendSpinVote(username: String, index: Int) {
        if (username.isNotBlank()) _spinVotes.update { it + (username to index) }
        _convoyId.value?.let { send(RelayProtocol.buildSpinVote(it, index)) }
    }

    /** Drops the current spin locally (a commit landed, or it was dismissed)
     *  without telling anyone - there is nothing to tell, the vote was never
     *  server state to begin with. */
    fun clearSpinOffer() {
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
    }

    /**
     * Forwards one fix, throttled to at most one every [LOCATION_SEND_INTERVAL_MS] -
     * the fix is handed in rather than read from a platform location API,
     * per `Platform.kt`'s three-concern ceiling (no location `expect`). Sent
     * unscoped (no `groupId`): a fix belongs to whoever sent it, and the
     * relay resolves who may see it from that rider's memberships, exactly
     * as `RelayProtocol.buildLocation`'s own doc explains.
     */
    fun sendLocation(lat: Double, lon: Double, headingDeg: Double?, speedKmh: Double) {
        val now = nowMs()
        if (now - lastLocationSentMs < LOCATION_SEND_INTERVAL_MS) return
        lastLocationSentMs = now
        send(RelayProtocol.buildLocation(LatLon(lat, lon), headingDeg, speedKmh, now))
    }

    private fun send(text: String) {
        currentSocket?.send(text)
    }

    private suspend fun sleepUnlessStopped(totalMs: Long) {
        var remaining = totalMs
        // Also cut short by shouldStayConnected() going false mid-wait - the
        // same "checked at every await boundary" treatment _stopped already
        // gets, extended to the other way run()'s own loop decides to stop:
        // otherwise setConvoy(null)/setNotifyingCircles(emptySet()) landing
        // during a backoff wait would leave run() sleeping out the rest of
        // it - up to MAX_BACKOFF_MS - before noticing nothing wants it any
        // more.
        while (remaining > 0 && !_stopped.value && shouldStayConnected()) {
            val step = if (remaining < BACKOFF_POLL_STEP_MS) remaining else BACKOFF_POLL_STEP_MS
            delay(step)
            remaining -= step
        }
    }
}

/** How finely a backoff wait is chopped up so [ConvoyRelay.stop] is noticed
 *  promptly rather than only once the whole wait has elapsed - the "checked
 *  at every await boundary" rule applied to a wait that can span up to
 *  [MAX_BACKOFF_MS]. */
private const val BACKOFF_POLL_STEP_MS = 100L

/** [text] decoded as a relay `error` frame, or `null` if it is not one -
 *  wrapped in a data class rather than returning a bare nullable `String`,
 *  because "not an error frame" and "an error frame with a blank/absent
 *  message" both need to read back as "nothing to report" in different ways:
 *  the first must fall through to [RelayProtocol.decode], the second must
 *  still end the connection attempt. A bare `String?` cannot tell those two
 *  `null`s apart. */
private data class ErrorFrame(val message: String?)

/** `error` is deliberately not one of [RelayProtocol.decode]'s nine frame
 *  types (see that function's own doc), so it is read here instead, straight
 *  off the raw text - the same thing both existing clients do outside their
 *  own per-frame switch.
 *
 *  A blank/absent message on a genuine error frame reads back as `null`
 *  (clearing rather than setting `lastError`) - matching the Android
 *  client's `.ifBlank { null }` exactly, an existing quirk kept rather than
 *  "fixed" per this task's port-not-redesign brief. */
private fun errorFrameOrNull(text: String): ErrorFrame? {
    val obj = try {
        jsonObjectOf(text)
    } catch (e: CancellationException) {
        // Not suspend, so this can't actually observe a cancellation in
        // practice - added to satisfy the house rule unconditionally
        // (every catch (e: Exception) preceded by this) rather than
        // claiming an exemption for a case that costs nothing to cover.
        throw e
    } catch (e: Exception) {
        return null
    }
    if (obj.optString("type") != "error") return null
    return ErrorFrame(obj.optString("message").ifBlank { null })
}

/**
 * `lastError`'s wording for a connection that never came up - a failed
 * [RelaySocket.connect], or a [RelaySocket.receive] that throws before ever
 * joining.
 *
 * The socket's own message is used verbatim, because the socket is the only
 * part that knows *why*: a refused upgrade carries a status code, a blank
 * server address is not a network problem at all, and a transport failure is
 * neither. Prefixing them all with one phrase produced "Can't reach the live
 * server: Live server refused the connection (401)" - two sentences arguing
 * with each other. [RelaySocket.connect]'s doc makes the wording the
 * implementation's job for this reason.
 */
private fun unreachableMessage(e: Exception): String =
    e.message?.takeIf { it.isNotBlank() } ?: "Can't reach the live server"

/** Tie-break rule for [SpinRoundOutcome.CloseRound]'s leader, and for
 *  [ConvoyRelay.currentLeadIndex]'s forced-close case: ties (including
 *  "nobody's voted yet", every count 0) go to the lowest index. `>` rather
 *  than `>=` is what makes that deterministic - every device tallying the
 *  same votes lands on the same leader without needing to compare who voted
 *  when. Ported from `app/.../map/GroupSpinRules.kt`'s `leadingSpinIndex` -
 *  Task 5 deleted that file once both platforms were repointed here; this is
 *  the only implementation left. */
private fun leadingSpinIndex(votes: Map<String, Int>, candidateCount: Int): Int {
    val counts = IntArray(candidateCount)
    votes.values.forEach { if (it in counts.indices) counts[it]++ }
    var lead = 0
    for (i in 1 until candidateCount) if (counts[i] > counts[lead]) lead = i
    return lead
}

/**
 * How a vote round ends. Two halves, and which one runs depends on whether
 * this device opened the round ([GroupSpin.fromMe]):
 *
 *  - Anyone receiving a one-candidate offer commits it. That offer *is* the
 *    decision, so every member lands on the same destination off the same
 *    frame instead of each resolving the votes itself.
 *  - The sharer, once every peer currently live plus itself has voted, sends
 *    the leader back out as exactly that one-candidate offer - which then
 *    commits here too, through the branch above.
 *
 * Tallying independently on each device would have been simpler and wrong:
 * pruning drops a member who has gone quiet for [RelayProtocol.FALLBACK_PEER_TTL_MS],
 * so one device can consider a round complete on two votes while another -
 * with a different peer set, from a different prune timing or a dropped
 * frame - is still waiting for a third, and the two resolve to different
 * candidates. Splitting a convoy across two destinations is the exact
 * failure this rule exists to prevent - ported from
 * `app/.../map/GroupSpinRules.kt`'s `resolveSpinRound`, which stated this
 * argument but, being reachable only from two live phones, was never
 * actually tested against it. [ConvoyRelayTest] is the first thing that
 * does; Task 5 then deleted that file once both platforms were repointed at
 * this one.
 */
private fun resolveSpinRound(
    candidateCount: Int,
    fromMe: Boolean,
    expected: Set<String>,
    votes: Map<String, Int>,
): SpinRoundOutcome {
    if (candidateCount <= 0) return SpinRoundOutcome.Wait
    if (candidateCount == 1) return SpinRoundOutcome.CommitOnly
    if (!fromMe) return SpinRoundOutcome.Wait
    if (expected.isEmpty() || !votes.keys.containsAll(expected)) return SpinRoundOutcome.Wait
    return SpinRoundOutcome.CloseRound(leadingSpinIndex(votes, candidateCount))
}
