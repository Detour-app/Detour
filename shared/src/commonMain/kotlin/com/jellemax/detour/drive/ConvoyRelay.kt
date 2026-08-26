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

/** Forwarded on every reconnect, unthrottled by [ConvoyRelay.sendLocation]. */
private const val LOCATION_SEND_INTERVAL_MS = 2_000L
private const val MIN_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val PEER_PRUNE_INTERVAL_MS = 5_000L

/**
 * How a device should treat the [ConvoyRelay.spinOffer]/[ConvoyRelay.spinVotes]
 * currently on the table - the convoy vote rule ported from
 * `app/.../map/GroupSpinRules.kt`, which documented the rule and its
 * correctness argument but - being reachable only from two live phones - was
 * never actually exercised by a test against a live relay loop. See
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
 * The convoy live-relay's state machine: peers, push-to-talk membership, the
 * group spin vote, and the connect/backoff/reconnect loop that feeds them -
 * one implementation of what `app/.../net/ConvoyLiveClient.kt` (693 lines)
 * and `iosApp/Detour/ConvoyLiveClient.swift` (600 lines) each wrote by hand,
 * running against [RelaySocket] rather than OkHttp or `URLSessionWebSocketTask`
 * directly. This is a port, not a redesign: every reconnect/backoff/lastError
 * decision below is checked against what those two files actually do, not
 * what seemed cleaner.
 *
 * **URL and header resolution stay a platform concern.** Android's derives a
 * `wss://` URL from a `Context`-backed `Settings`/`BuildConfig` read; iOS's
 * does not go through the same derivation at all. Both differences are real
 * and neither belongs in `commonMain` - see `Platform.kt`'s module-boundary
 * doc - so a [RelaySocket] arrives here already pointed at the right place,
 * and [run] only ever hands it a bearer token.
 *
 * **`bearer` is a supplier, not a resolved string**, for two reasons at once.
 * First, fidelity: both existing clients call `Auth.bearer()` fresh inside
 * their own per-attempt connect function, not once for the whole reconnect
 * loop, so a socket that comes back after an access token's 15-minute
 * lifetime presents a current one rather than a stale one - a supplier
 * preserves that, a resolved string would not. Second, testability: calling
 * `Auth.bearer()` directly from here would make every test in this file
 * depend on `Settings.init()`, which needs a real platform `Context` this
 * module's tests do not have (see `FriendsStore`'s own doc for the same
 * constraint) - `Auth.signedIn` reads a safe default and never crashes, but
 * `Auth.bearer()` would then just throw `AuthException` on every call,
 * closing off the very connect-flow tests this class exists to make
 * possible. The real call site (a later task) passes `Auth::bearer`.
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
 * [run] wires [stop] to `Auth.sessionEpoch` as well, alongside whatever
 * button-press calls [stop] directly: a 401 or a server switch bump the
 * epoch the same way a sign-out does (see [Auth.sessionEpoch]'s own doc), and
 * only reacting to a "go offline" button is exactly the gap the leak above
 * came through.
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
     *  the class doc. Set at the top of every [run] call. */
    private var currentSocket: RelaySocket? = null

    /** The group [run] is currently joined to, for the plain outbound sends
     *  below - mirrors what the old singletons stamped onto every frame from
     *  their own `_activeConvoyId`. Null outside a [run] call, so a send
     *  attempted before joining or after leaving is silently dropped rather
     *  than mis-addressed. */
    private var currentGroupId: String? = null

    private var lastLocationSentMs = 0L

    /**
     * Connects to [groupId] over [socket], forwarding [bearer]'s result on
     * every (re)connect attempt, and keeps reconnecting with backoff until
     * [stop] is called or `Auth.sessionEpoch` moves on - returning only then.
     * Owns the whole connect → receive → backoff → reconnect cycle; nothing
     * about it gives up early on its own, matching both existing clients,
     * which retry forever for as long as something wants the socket open.
     *
     * Resets every piece of state below to this call's own baseline first -
     * peers, talking, spin offer/votes, `lastError` - so a previous group's
     * state can never bleed into this one, the same guard `join()` applied
     * on both existing clients before starting their own connection.
     */
    @Throws(Exception::class)
    suspend fun run(groupId: String, socket: RelaySocket, bearer: suspend () -> String): Unit = coroutineScope {
        currentSocket = socket
        currentGroupId = groupId
        _stopped.value = false
        _peers.value = emptyMap()
        _talking.value = emptySet()
        _spinOffer.value = null
        _spinVotes.value = emptyMap()
        _lastError.value = null
        _connected.value = false

        val startEpoch = Auth.sessionEpoch.value
        val sessionWatcher = launch {
            Auth.sessionEpoch.first { it != startEpoch }
            stop()
        }
        val pruner = launch {
            while (true) {
                delay(PEER_PRUNE_INTERVAL_MS)
                prunePeers(nowMs())
            }
        }
        try {
            var failures = 0
            while (!_stopped.value) {
                val everJoined = attempt(groupId, socket, bearer)
                if (_stopped.value) return@coroutineScope
                _connected.value = false
                // A vote tallied against a socket that is no longer relaying
                // anyone's frames is wrong by the time it reconnects - drop
                // it rather than let a stale offer sit on screen looking
                // live through a dead connection. Matches both existing
                // clients' `runConnection`/`connectionLoop`.
                _spinOffer.value = null
                _spinVotes.value = emptyMap()
                failures = if (everJoined) 0 else failures + 1
                sleepUnlessStopped(backoffDelayMs(MIN_BACKOFF_MS, MAX_BACKOFF_MS, failures))
            }
        } finally {
            sessionWatcher.cancel()
            pruner.cancel()
            currentSocket = null
            currentGroupId = null
        }
    }

    /**
     * Ends [run] and closes [socket] so a blocked [RelaySocket.receive]
     * returns instead of hanging - see the class doc for why this is a flag
     * plus a forced close rather than relying on cancelling [run]'s
     * coroutine. Idempotent, and safe to call with nothing running.
     */
    fun stop() {
        _stopped.value = true
        currentSocket?.close()
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
    private suspend fun attempt(groupId: String, socket: RelaySocket, bearer: suspend () -> String): Boolean {
        try {
            val token = try {
                bearer()
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
            if (_stopped.value) return false

            try {
                socket.connect(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = unreachableMessage(e)
                return false
            }

            socket.send(RelayProtocol.buildJoin(groupId))
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
            socket.close()
        }
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

    // --- outbound sends -----------------------------------------------------
    //
    // Every one of these is fire-and-forget and non-suspend, matching both
    // existing clients' own `send`/`sendUnscoped` - a caller does not await a
    // send, it watches the relevant StateFlow for whatever comes back.

    fun sendPttStart() {
        currentGroupId?.let { send(RelayProtocol.buildPttStart(it)) }
    }

    fun sendPttEnd() {
        currentGroupId?.let { send(RelayProtocol.buildPttEnd(it)) }
    }

    fun sendAudioChunk(pcm: ByteArray) {
        currentGroupId?.let { send(RelayProtocol.buildPttAudio(it, pcm)) }
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
        currentGroupId?.let { send(RelayProtocol.buildSpinOffer(it, candidates)) }
    }

    /** Casts [username]'s vote and records it in the local tally right away,
     *  unconditionally - for the same reason [sendSpinOffer] updates
     *  [spinOffer] locally regardless of the wire send below - the relay
     *  will not echo it back. */
    fun sendSpinVote(username: String, index: Int) {
        if (username.isNotBlank()) _spinVotes.update { it + (username to index) }
        currentGroupId?.let { send(RelayProtocol.buildSpinVote(it, index)) }
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
        while (remaining > 0 && !_stopped.value) {
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
    } catch (e: Exception) {
        return null
    }
    if (obj.optString("type") != "error") return null
    return ErrorFrame(obj.optString("message").ifBlank { null })
}

/** `lastError`'s wording for "the relay could not be reached at all" -
 *  shared by a failed [RelaySocket.connect] and a [RelaySocket.receive] that
 *  throws before ever joining. */
private fun unreachableMessage(e: Exception): String =
    e.message?.takeIf { it.isNotBlank() }?.let { "Can't reach the live server: $it" }
        ?: "Can't reach the live server"

/** Tie-break rule for [SpinRoundOutcome.CloseRound]'s leader: ties (including
 *  "nobody's voted yet", every count 0) go to the lowest index. `>` rather
 *  than `>=` is what makes that deterministic - every device tallying the
 *  same votes lands on the same leader without needing to compare who voted
 *  when. Ported from `app/.../map/GroupSpinRules.kt`'s `leadingSpinIndex`,
 *  which this same task's brief forbids touching directly - see this file's
 *  class doc and the task report for why a second, shared-owned copy is the
 *  right call here rather than reaching into `app/`. */
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
 * actually tested against it.
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
