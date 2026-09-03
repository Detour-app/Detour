package com.jellemax.detour.data

import com.jellemax.detour.drive.FriendPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class ConvoysState(
    val convoys: List<Group> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** When the server last answered. Drives [isStale], so re-entering a
     *  screen seconds later shows what it showed instead of re-fetching.
     *  Null until a load has actually succeeded — a failure must not stamp,
     *  or it would suppress the next entry's attempt. */
    val loadedAtMs: Long? = null,
)

/**
 * Convoy membership: creating, listing, inviting, responding, leaving.
 *
 * Membership only. A convoy's live location and push-to-talk ride a WebSocket
 * that is still platform code (`app/net/ConvoyLiveClient.kt` and its Swift
 * twin), so whether this device is *connected* is not in this state — the
 * screens read that from the client itself, which is what keeps the button
 * honest about whether the service is actually running.
 *
 * Same no-scope rule as [FriendsStore]: actions are `suspend`, the caller
 * supplies the coroutine. Same never-throws-for-an-ordinary-failure contract
 * too — see `FriendsStore`'s private `act` for the full reasoning, since its
 * own `act` below is not part of this store's public surface either.
 */
object ConvoysStore {

    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(ConvoysState())
    val state: StateFlow<ConvoysState> = _state.asStateFlow()

    /** Serialises [resolveUnknown]'s reload - see its own doc for why one at
     *  a time, matching the `writeLock` pattern in `Coverage.kt`. */
    private val refreshGate = Mutex()

    /** Ids [resolveUnknown] found still unknown after a completed reload —
     *  not retried on the next frame that names them; see that function's
     *  own doc. A `MutableStateFlow` rather than a plain `var` for the same
     *  cross-thread-visible read it gives [_state]; [resolveUnknown] is the
     *  only writer, and only while holding [refreshGate]. */
    private val ignoredIds = MutableStateFlow<Set<RiderId>>(emptySet())

    /** Drops everything back to [ConvoysState]'s defaults. Called from
     *  [Auth.clear] rather than by a screen; see that function's doc for why. */
    internal fun reset() {
        _state.update { it.cleared() }
        ignoredIds.value = emptySet()
    }

    /**
     * The entry point a screen should use on open.
     *
     * [reload] is unconditional and stays that way — a pull-to-refresh and
     * every mutation want the server's answer whatever the clock says. This is
     * for the case a screen cannot distinguish: `LaunchedEffect(Unit)` fires on
     * every entry, and stepping away and back within [SOCIAL_TTL_MS] is not a
     * new visit.
     *
     * No parameters, so the generated Swift signature is unchanged and iOS can
     * adopt it by swapping the call. The decision itself is [isStale], which is
     * pure and tested; this only supplies the clock.
     */
    @Throws(Exception::class)
    suspend fun reloadIfStale() {
        if (!isStale(_state.value.loadedAtMs, nowMs())) return
        reload()
    }

    @Throws(Exception::class)
    suspend fun reload() {
        _state.update { it.starting() }
        fetchAndCommit(ConvoysState::loaded, ConvoysState::failed)
    }

    /** Same fetch and commit as [reload], minus the [starting] that flips
     *  [ConvoysState.busy] — the background self-heal in [resolveUnknown]
     *  calls this instead, so a reload it triggers is invisible to the
     *  screen. See [loadedQuietly] for why that flag specifically must stay
     *  untouched. */
    private suspend fun reloadQuietly() {
        fetchAndCommit(ConvoysState::loadedQuietly, ConvoysState::failedQuietly)
    }

    /** The part [reload] and [reloadQuietly] share: fetch the convoy list
     *  and commit whichever of [onLoaded]/[onFailed] the attempt earns,
     *  under the same session-epoch guard either way. Factored out so the
     *  two reload paths cannot drift on what they load or how they commit
     *  it — the only thing left for a caller to choose is whether
     *  [ConvoysState.busy] moves.
     *
     *  See FriendsStore.reload's comment: the transform is built from the
     *  awaits' results and only applied to the live `it` inside the final
     *  `update { }` below, not to a `_state.value` snapshot taken before
     *  either suspending call. */
    private suspend fun fetchAndCommit(
        onLoaded: (ConvoysState, List<Group>, Long) -> ConvoysState,
        onFailed: (ConvoysState, Exception) -> ConvoysState,
    ) {
        val epoch = Auth.sessionEpoch.value
        val apply: (ConvoysState) -> ConvoysState = try {
            val convoys = Groups.list(KIND)
            val transform: (ConvoysState) -> ConvoysState = { s -> onLoaded(s, convoys, nowMs()) }
            transform
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transform: (ConvoysState) -> ConvoysState = { s -> onFailed(s, e) }
            transform
        }
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, apply(it)) }
    }

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun create(name: String): Boolean =
        act { Groups.create(KIND, name) } != null

    /** Returns the resulting status, e.g. "invited" — or null if the invite
     *  failed, with the failure left in [state]'s `error`. Only accepted
     *  friends can be invited; the server enforces that and this surfaces
     *  its refusal. */
    @Throws(Exception::class)
    suspend fun invite(groupId: String, username: String): String? =
        act { Groups.invite(groupId, username) }

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun respond(groupId: String, accept: Boolean): Boolean =
        act { Groups.respond(groupId, accept) } != null

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun leave(groupId: String): Boolean =
        act { Groups.leave(groupId) } != null

    private suspend fun <T> act(block: suspend () -> T): T? {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Epoch-guarded for the same reason the commit in [reload] is: a
            // mutation failing after a sign-out must not put its banner on the
            // next rider's store. See FriendsStore.act.
            _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.failed(e)) }
            return null
        }
        reload()
        return result
    }

    /**
     * Keeps the member list able to name every peer the relay reports.
     *
     * Positions carry an id and no handle (#133): the label lives in
     * membership, so one source names a rider rather than every frame
     * repeating it. The cost is that a peer who joined since the last
     * reload arrives unnameable, and this is what closes that - centrally,
     * so the phone map, the car renderer and iOS all get it without each
     * wiring up its own.
     *
     * Not a roster frame on the relay, which would put a second id-to-name
     * source beside this list and leave the two to disagree.
     *
     * Debounced to one reload in flight ([refreshGate]) — that only bounds
     * concurrency, not repetition, so an id still unknown after a
     * *completed* reload is separately remembered in [ignoredIds] and not
     * retried on the next frame that names it: a `positions` frame repeats
     * every tick, and without this a single departed or not-yet-propagated
     * peer would drive a reload on every one of them. [ignoredIds] is
     * cleared the moment a reload actually changes membership, so a
     * genuinely new peer already written off gets a fresh look rather than
     * staying unresolved for the rest of the ride.
     */
    private suspend fun resolveUnknown(ids: Set<RiderId>) {
        val state = _state.value
        val known = state.convoys.flatMap { it.members }.map { it.id }.toSet()
        if (state.unresolvedAfterIgnoring(ids, known, ignoredIds.value).isEmpty()) return
        if (!refreshGate.tryLock()) return
        try {
            reloadQuietly()
            val knownAfter = _state.value.convoys.flatMap { it.members }.map { it.id }.toSet()
            ignoredIds.value = state.nextIgnoredIds(ids, ignoredIds.value, known, knownAfter)
        } finally {
            refreshGate.unlock()
        }
    }

    /** Started once, for the life of the process. The scope is the caller's
     *  - `commonMain` has no `Dispatchers` of its own (Platform.kt's
     *  ceiling), same reason every action in this store is `suspend`.
     *  [peers] is the caller's too, for the same reason: the one live
     *  `ConvoyRelay` instance is a platform call-site's own (see that
     *  class's "exactly one instance app-wide" doc), not something this
     *  store can reach on its own - the caller passes its `relay.peers`. */
    fun watchPeers(scope: CoroutineScope, peers: StateFlow<Map<RiderId, FriendPosition>>) {
        scope.launch {
            peers.collect { resolveUnknown(it.keys) }
        }
    }

    /** The discriminator [Groups] routes on. "convoy" here, "circle" in
     *  [CirclesStore]; one entity on the server, two kinds. */
    private const val KIND = "convoy"
}

internal fun ConvoysState.starting() = copy(busy = true, error = null)

internal fun ConvoysState.loaded(convoys: List<Group>, nowMs: Long) =
    copy(convoys = convoys, busy = false, error = null, loadedAtMs = nowMs)

internal fun ConvoysState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: ConvoysStore.FALLBACK_ERROR)

/** Same as [loaded], but leaves [ConvoysState.busy] exactly as it found it.
 *  Used only by [ConvoysStore.resolveUnknown]'s background self-heal, which
 *  must stay invisible to the screen the same way `CirclesScreen.kt` reads
 *  `CirclesState.busy` — a reload the rider never asked for must not flip
 *  it. */
internal fun ConvoysState.loadedQuietly(convoys: List<Group>, nowMs: Long) =
    copy(convoys = convoys, error = null, loadedAtMs = nowMs)

/** Same as [failed], but leaves [ConvoysState.busy] exactly as it found it
 *  — see [loadedQuietly] for why. */
internal fun ConvoysState.failedQuietly(e: Exception) =
    copy(error = e.message?.ifBlank { null } ?: ConvoysStore.FALLBACK_ERROR)

/** [result] if [epoch] still names the session an in-flight [ConvoysStore.reload]
 *  started under, checked against [currentEpoch] read fresh at commit time —
 *  or this state untouched otherwise. Same guard as [FriendsStore]'s
 *  matching function; see its doc for the full reasoning. */
internal fun ConvoysState.commitIfCurrent(epoch: Int, currentEpoch: Int, result: ConvoysState): ConvoysState =
    if (epoch == currentEpoch) result else this

/** Every field back to [ConvoysState]'s defaults. `internal` and pure,
 *  called from [ConvoysStore.reset] and asserted directly — see
 *  [FriendsState.cleared]'s doc for why. */
internal fun ConvoysState.cleared() = ConvoysState()

/**
 * Which of [ids] [ConvoysStore.resolveUnknown] should still attempt a
 * reload for: not already [known], and not already given up on in
 * [ignored]. An extension of [ConvoysState] — like [commitIfCurrent] and
 * every other reused name in this file — purely so this can share a name
 * with [CirclesStore]'s identical function on [CirclesState] without the
 * two clashing as top-level declarations in the same package. Pure so the
 * no-repeat-reload guarantee is testable directly — see
 * [ConvoysStore.resolveUnknown]'s own doc for why an id is remembered
 * rather than retried on every frame.
 */
internal fun ConvoysState.unresolvedAfterIgnoring(ids: Set<RiderId>, known: Set<RiderId>, ignored: Set<RiderId>): Set<RiderId> =
    ids - known - ignored

/**
 * The next [ignored] set after one of [ConvoysStore.resolveUnknown]'s
 * reloads completes. Cleared entirely the moment membership actually
 * changed ([knownAfter] differs from [knownBefore]) so a peer already
 * written off is not permanently ignored just because this reload's [ids]
 * are still missing; otherwise [ignored] plus whichever of [ids] the
 * reload still could not name.
 */
internal fun ConvoysState.nextIgnoredIds(
    ids: Set<RiderId>,
    ignored: Set<RiderId>,
    knownBefore: Set<RiderId>,
    knownAfter: Set<RiderId>,
): Set<RiderId> =
    if (knownAfter != knownBefore) emptySet() else ignored + (ids - knownAfter)
