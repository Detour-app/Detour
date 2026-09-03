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

    /** Drops everything back to [ConvoysState]'s defaults. Called from
     *  [Auth.clear] rather than by a screen; see that function's doc for why. */
    internal fun reset() {
        _state.update { it.cleared() }
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
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        // See FriendsStore.reload's comment: the transform is built from the
        // awaits' results and only applied to the live `it` inside the final
        // `update { }` below, not to a `_state.value` snapshot taken before
        // either suspending call.
        val apply: (ConvoysState) -> ConvoysState = try {
            val convoys = Groups.list(KIND)
            val transform: (ConvoysState) -> ConvoysState = { s -> s.loaded(convoys, nowMs()) }
            transform
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transform: (ConvoysState) -> ConvoysState = { s -> s.failed(e) }
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
     * Debounced to one reload in flight. An id still unknown after a
     * *completed* reload is not retried: it means the peer left between the
     * frame and the response, and the relay's own TTL expires them.
     * Retrying would turn one departed rider into an unbounded reload loop.
     */
    private suspend fun resolveUnknown(ids: Set<RiderId>) {
        val known = _state.value.convoys.flatMap { it.members }.map { it.id }.toSet()
        if (ids.all { it in known }) return
        if (!refreshGate.tryLock()) return
        try {
            reload()
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
